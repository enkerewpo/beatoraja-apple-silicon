# Oboe 相关改动的内存安全审查

针对"oboe 改动之后会不会有内存溢出或相关问题"做的一次系统复查。

**结论：没有内存泄漏，也没有会无限增长的结构。** 但查的过程中发现并修掉了 3 处真实隐患 ——
其中 2 处是**既有问题**，跟本轮的优化改动无关，只是之前一直没暴露。

---

## 一、修掉的

### 1. `m_pending` 的交接是 use-after-free（既有问题）

`soundpool::play()`（UI 线程）用 `m_pending_flag` 保护 `m_pending.push_back()`：

```cpp
while (m_pending_flag.test_and_set(std::memory_order_acquire)) {
    std::this_thread::yield();
}
m_pending.push_back(std::move(s));
m_pending_flag.clear(std::memory_order_release);
```

而 `render()`（音频线程）消费它时**没有取同一把 flag**：

```cpp
if (!m_pending.empty()) {
    for (auto& s : m_pending) m_sounds.push_back(std::move(s));
    m_pending.clear();
}
```

`push_back` 一旦触发扩容，会分配新内存并**释放旧存储**；此刻音频线程正在遍历旧存储 →
use-after-free，直接崩在音频回调里。

触发条件并不苛刻：`clear()` 不释放容量，所以只在列表**超过历史峰值**时才扩容 ——
而每秒几百个 note 的密集段落正好在不断刷新峰值。

**修复**：音频线程用**非阻塞**方式取同一把 flag，拿不到就跳过这一帧（~4ms 后的下一帧再消费）。

```cpp
if (!m_pending.empty() &&
    !m_pending_flag.test_and_set(std::memory_order_acquire)) {
    ...
    m_pending_flag.clear(std::memory_order_release);
}
```

不自旋是刻意的：这是实时线程，不能为了等 UI 线程空转（优先级反转）。pending 条目不会被
丢弃，所以延后一帧是无损的。

### 2. `m_sample_buffer` 用 `reserve` 却当 `resize` 用（既有问题）

```cpp
m_sample_buffer.reserve(num_frames * m_channels + 16);
...
int used_frames = it->m_resampler.process(iter, m_pcm.cend(),
                                          m_sample_buffer.begin(), size);
```

`reserve` 只给容量，**`size()` 仍然是 0** —— 而后面是往 `begin()` 起写 `size * m_channels`
个元素（`resampler::process` 内部还会 `&(*output)` 解引用）。对空 vector 解引用 `begin()`
是未定义行为。`float` 是 POD，reserve 出来的内存实践中能写，所以一直没炸 —— 但那是运气。

**修复**：改成"不够才 resize"：

```cpp
const size_t needed_samples = static_cast<size_t>(num_frames) * m_channels + 16;
if (m_sample_buffer.size() < needed_samples) {
    m_sample_buffer.resize(needed_samples);
}
```

只在扩展时填 0，之后每帧只是一次比较。（直接无条件 `resize` 会每帧重填一遍 0，
反而吃掉一部分"跳过空闲音源"省下来的收益。）

### 3. `postOboeLifecycle` 的 lambda 捕获了 Activity（本轮改动引入，已修）

```java
OBOE_LIFECYCLE_EXECUTOR.execute(() -> {
    OboeAudio audio = oboeAudio;   // ← 隐式捕获 this
    ...
});
```

`this` 被捕获后，任务在队列里排队的这段时间 Activity 释放不掉。单次执行很快，影响有限，
但没必要留这个尾巴。

**修复**：提交前把引用读进局部变量，lambda 只捕获 `OboeAudio`（与进程同寿命，不依赖 Activity）。

---

## 二、查过、确认没问题的

### `soundpool::active()` 的无锁读（本轮改动）

`active()` 每帧被调用几千次；如果在这里也去拿 `m_rendering_flag`，会把"跳过空闲音源"
省下的收益全部吃回去。它能不加锁，靠的是**写入者的结构**：

- **`m_sounds`**：增长它的只有 `render()`（音频线程自身）。两个跨线程写者 `stop()` 和
  `stop(id)` **只会让它变小**。和它们竞争最坏是读到过期的结束指针、误报"非空"，
  代价是多走一次 render（那一次会在锁内重新检查，发现没声音就空转）。
  **它不会被误报成"空"** —— 所以还有声音的池永远不会被跳过。
- **`m_pending`**：由 UI 线程 push。竞争读可能报"空"而跳过一帧，但条目不会被丢弃，
  下一帧照常消费。最坏是一个音符晚一个缓冲（~4ms）起播。

两种失败模式都只是"多干一点活"或"晚 4ms"，**不会丢音，也不会无限增长**。
（已把这段推理写进代码注释，免得以后有人以为这个无锁读是随手写的。）

### `audio_player::m_tracks` 不会无限增长

每个 wav 一个 `soundpool` 注册进 `m_tracks`，但存的是 `weak_ptr`。旧音源在
`AbstractAudioDriver.setModel()` → `cache.disposeOld()` 释放后 `weak_ptr` 过期，
`generate_audio()` 里的 `is_dirty` 分支会把它们 erase。

`active()` 的 `continue` **不影响**这个清理：`is_dirty |= weak_track.expired();` 在
`continue` **之前**执行，erase 在循环**之外**。

### Java 侧的线程与资源

- `MusicPlayer` 的 `loader` / `worker` 两个 executor 都在 `terminatePlayback()` 里
  `shutdownNow()` 并置 null，`create()` 会重新创建。
- `OBOE_LIFECYCLE_EXECUTOR` 是 static daemon 单线程，进程退出即回收；队列只会积压极短的
  任务，且修掉第 3 条后不再持有 Activity。
- `PlaybackCpuLock` 用 `WeakHashMap` 按 owner 记账 + 1 小时超时兜底，release 漏掉也不会
  永久唤醒 CPU。
- 封面路径：`pendingStagefilePixmap` 在覆盖 / 上传 / `disposeGlResources` 三处都会 dispose；
  `glTextureGarbage` 每帧 drain 且 `pause()` 里也清一次 —— 锁屏期间 `stagefile` 已是 null，
  `retireStagefile()` 会直接返回，队列不会攒。
- `AUDIO` 之外的 `MusicPlayer.pause()` 只做三件事（置标志、关持续渲染、释放几张纹理），
  没有同步 I/O。

### 加虚函数 `active()` 的 ABI 影响

会改变 `renderable_audio` 的 vtable 布局。但整个 `.so` 是一起重编译的，替换 aar 后不存在
新旧混用，所以没有影响 —— 前提是**不要只替换一部分**：`liblibgdx-oboe.so` 必须和
`soundpool.cpp` / `audio_player.cpp` / `renderable_audio.hpp` 的改动一起走。
