# 同一 #WAV 定义同时触发 → 双 voice（音量 200%）

> 现象：BMS 谱面里两条 note 落在**同一横坐标**、且指向**同一个 #WAV 定义**（例如
> 同一时刻两个 `#WAV4D`）时，PC 版只响一次（100%），Android 版响两次（200%，听感更响、
> 带轻微 comb/flam）。
> 结论：**移植 libgdx-oboe 时引入的回归**，不在 Java 侧。修复见第五节。
> 状态：源码已改 + 双架构 NDK 语法校验通过；AAR 已重建；**未装机验证、未 commit**。

---

## 一、BMS 的预期语义：channel 按 #WAV **定义下标**分

上游（`beatoraja-master`）与移植版的 `AbstractAudioDriver` 逐行等价，播放路径是：

```java
// AbstractAudioDriver.play0() —— 两边完全一致
final int channel = channel(id, pitchShift);   // = id * 256 + pitch + 128，id = n.getWav()
stop(wav, channel);                            // 先停
play(wav, channel, volume, pitch);             // 后放
```

**`id` 是 #WAV 的定义下标，不是文件路径。** 由此得到两条都"正确"的行为：

| 谱面写法 | 定义下标 | channel | 期望行为 |
| --- | --- | --- | --- |
| 同一时刻两个 `#WAV4D` | 相同 | 相同 | **互斥 → 只响一个（100%）** |
| `#WAV4D` 与 `#WAV4E` 指向同一文件 | 不同 | 不同 | **两个都响（200%）**，合法叠音 |

所以修复**绝不能按音频文件路径去重** —— 那会把 `4D/4E` 这种合法的"不同定义、同文件"
叠音误杀。判据只能是 voice id（源自 #WAV 下标）。

## 二、调用链（Java 侧是无辜的）

```
JudgeManager / KeySoundProcessor
  └─ AbstractAudioDriver.play(Note, vol, pitch)      synchronized
       └─ play0(): stop(wav, channel) → play(wav, channel, vol, pitch)
            └─ GdxSoundDriver.play(...)  → Sound.play(vol, pitch, pan) → 返回 voice id
                                          存进 sounds[256] ring
            └─ GdxSoundDriver.stop(Sound, channel)
                 → ring 里匹配 (sound, channel) 的项 → sound.stop(旧的 id)
                      └─ OboeSound.stop(long)  (native)
                           └─ soundpool::do_by_id(id) → 只扫 m_sounds，erase
```

`GdxSoundDriver` 是 Android 上的实际驱动（`MainController.java:569` 实例化），
`Gdx.audio` 由 `AndroidLauncher.createAudio()` 里的 `OboeAudio` 提供 →
`newSound()` 返回 `OboeSound` → JNI 到 C++ `soundpool`（`native/oboe_sound.cpp` 已核实
`stop__J` → `soundpool::stop(long)`）。

Java 侧 `AbstractAudioDriver` / `GdxSoundDriver` / `JudgeManager` / `KeySoundProcessor`
与上游**功能等价**（只差换行、`synchronized`、注释）。

## 三、根因：`stop(id)` 看不见 `m_pending`

移植时为"UI 线程不再自旋等音频锁"，把 `play()` 改成了 lock-free 的 pending 队列：

- `play()`：把新 voice 塞进 `m_pending`（持 `m_pending_flag`），**不当场进 `m_sounds`**；
- `render()`（音频回调线程）：下一次回调里把 `m_pending` 提升进 `m_sounds`。

| | `play()` 放哪 | `stop(id)` 扫哪 | 结果 |
| --- | --- | --- | --- |
| 上游 | `m_sounds.emplace_back()` | 只扫 `m_sounds` | 命中 → erase → **100%** |
| 移植版 | 只进 `m_pending`，等回调提升 | 只扫 `m_sounds` | **找不到 → erase 空操作 → 200%** |

**这是确定性 bug，不是偶发竞态。** 提升窗口 = 一个音频回调
（`m_payload_size = getFramesPerBurst()*2`，约 480–512 帧 ≈ **10ms**），而同一帧里处理
的两个同时 note 只隔**微秒级**，后一个的 `stop` **必然**落在前一个还在 `m_pending`
的窗口内。反过来，间隔超过一个回调的两次同 id 触发就正常 —— 这正好解释了"只有同时
出现才复现"。

同一个坑还波及另外几个入口（同属"看不见 pending"）：

| 入口 | 后果 |
| --- | --- |
| `stop(long)` | 用户报的双声（100% → 200%） |
| `stop()`（全停，`AbstractAudioDriver.stop(String)` 走这条） | stop 之后刚排入的 voice 仍会出声 |
| `volume(id, v)`（`setVolume(AudioElement,…)`） | 音量设置丢失 → 整段按默认音量播 |
| `pause()/resume()/pause(id)/resume(id)/looping/speed/pan(id)` | 同上，按各自语义失效 |

## 四、修复（`libgdx-oboe/library/src/cpp/audio/soundpool.cpp` / `.hpp`）

把 **`m_pending` 提升为一等容器**：所有 by-id / 全停操作都覆盖它。

1. `do_by_id(id, cb)`：先扫 `m_pending`，再扫 `m_sounds`（`.hpp` 里写明契约：
   callback 只允许改 sound 自身字段，**不得依赖它来自哪个 vector**）。
2. `stop(long id)`：因为 `erase` 需要知道迭代器属于哪个 vector，单独实现 ——
   两个容器各 `find_if + erase` 一次。**先 `m_pending` 后 `m_sounds`**。
3. `stop()`（全停）：`m_pending.clear()` → `m_sounds.clear()`。**顺序不能反**：
   反过来的话，两步之间被 `render()` 提升进 `m_sounds` 的 voice 会漏掉。
4. `pause()` / `resume()`（全停形式）：两个容器都改 `m_paused`。

**`render()` 一行未改** —— 音频回调线程仍然对 `m_pending_flag` 做**非阻塞**
`test_and_set`（拿不到就下次回调再提升）。这是刻意的：见下节 R2。

## 五、稳定性影响（结论：不下降）

### 为什么安全

1. **音频回调线程的协议完全没动。** `render()` 逐字节不变；新增的 flag 获取全部发生在
   **调用方线程**（GL/游戏线程），而它本来就在 `do_by_id` 里纯自旋等 `m_rendering_flag`。
   实时行为不变 → 满足项目硬约束 **R2**（不得改音频回调线程的同步协议）。
2. **不可能死锁。** 新代码的 flag 顺序是「先 `m_pending_flag`，**释放后**再拿
   `m_rendering_flag`」，从不同时持有；而 `render()` 的顺序是 `m_rendering_flag` →
   `m_pending_flag`，且对后者是**非阻塞**获取（失败就跳过，不 spin）。即便出现"调用方
   持有 pending、音频线程持有 rendering"的瞬间，音频线程也会立刻放开 rendering，调用方
   随即前进 —— 这是设计上不可阻塞的双向路径。
3. **pending 的持锁时间有界且无分配。** `render()` 只在提升循环里持它（把 pending 里
   0–4 个元素 `push_back` 进 `m_sounds` 再 `clear`，无 I/O 无锁等待）；调用方侧是
   `find_if` + `erase`（`erase` 不缩容、不分配），亚微秒级。
4. **调用方新增成本可忽略。** 每次 `stop` 多一次 flag 获取/释放 + 一次**正常的空/1–4
   元素**线性扫描；同一函数里紧接着就要自旋等 `m_rendering_flag` 并线性扫 `m_sounds`
   （可能几十上百个 voice）。新增的部分被原有部分完全掩盖。
5. **没有新增 underrun 面。** 音频线程不新增任何等待点；`m_sounds` 的遍历与混音循环未改。
6. **无新分配 / 无 GC 压力。** 未新增堆操作（`std::function` 构造沿用原有代码路径，
   捕获都是指针/浮点，走 SBO 不上堆）。

### 需要承认的代价

- `m_pending_flag` 多了一个共享者。若**将来**有人在这把 flag 下做长耗时操作，调用方会
  多等（当前唯一的长持有者是 `render()` 的提升循环，本身很短）。
- 调用方每个 note 的 `stop` 多花一点点时间（ns 量级），在几百 note/秒下也测不出来。

### 关于 R3（只对 32 位生效）

**这条修复不能加 32 位 gate** —— 它是**行为修正**（不是"输出逐位相同的等价改写"），
bug 在 32/64 位都存在，只给 32 位打补丁等于把 64 位留在错误状态。故两个 ABI 同改。

## 六、验证

### 1. 双架构离线语法编译

```
cd libgdx-oboe/library
$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/armv7a-linux-androideabi22-clang++ \
    -fsyntax-only -std=gnu++17 -Wall -Wextra \
    -I dependencies/libsamplerate/include -I dependencies/fmt/include \
    src/cpp/audio/soundpool.cpp
# 64 位换 aarch64-linux-android22-clang++
```

两个 ABI 都 **exit=0**，只剩改动前就存在的 `render ... is not marked 'override'` 提示。

### 2. 交错穷举模型检查（把"窗口关上了"从推理变成事实）

脚本：`.workbuddy/tmp/soundpool_interleaving_check.py`。
把两个容器 + 两把 flag 建成状态机，对 UI 线程与音频线程的**所有交错**做可达性穷举。
场景取真实触发序列 `play(id1) → stop(id1) → play(id2)`（同一 #WAV 定义的同时两条 note），
`render()` 的 pending 获取按**非阻塞**建模（拿不到就 no-op），阻塞步在模型里表现为"该步不可
执行"（等价自旋）。终态判据：id1 是否仍残留在**任一**容器。

| 实现 | 可达状态 | 终态数 | id1 残留的终态 | 结论 |
| --- | --- | --- | --- | --- |
| 旧：`stop` 只扫 `m_sounds` | 92 | 4 | **3** | ❌ 双 voice（200%） |
| 新：`stop` 扫 `pending` + `m_sounds` | 107 | 2 | **0** | ✅ 只剩 id2（100%） |

同时验证两条不变量（两版均 0 违例）：

1. 同一个 id 从不**同时**存在于两个容器；
2. UI 线程从不"持有 `m_pending_flag` 的同时去抢 `m_rendering_flag`" —— 即**锁序反转不存在**。

> 这个穷举只覆盖"同一 channel 的 stop/play 序列 + 提升窗口"这条路径，不是全系统证明；
> 它的作用是排除"修复只是缩小了窗口而没关掉"这种可能，并确认 flag 顺序没有反转。

### 3. AAR

已重建并替换（见下节）。**未做**真机听感验证。

## 七、部署状态

- **AAR 已重建并替换**：`~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle
  -p libgdx-oboe :library:assembleRelease` → BUILD SUCCESSFUL（armeabi-v7a + arm64-v8a），
  产物 `library/build/outputs/aar/library-release.aar`
  （md5 `e8ac0bef3794870bce7e162303371731`）→ 已覆盖 `android/libs/libgdx-oboe.aar`。
  旧版备份：`.workbuddy/tmp/libgdx-oboe.aar.bak-before-soundpool-fix`。
  文件清单与旧 AAR 完全一致 → 确认装机用的是 **release** 变体。
- ⚠️ `libgdx-oboe` 的 `./gradlew` 在本机用不了（`ClassNotFoundException:
  GradleWrapperMain`），用上面缓存发行版的命令绕过。
- **源码改了不重建 AAR = 两边不一致**，务必确认 AAR 已更新。
- C++ 改动在 `libgdx-oboe` 子模块内 → 提交时需**先提交子模块**，再在外层仓库提交
  指针 + `android/libs/libgdx-oboe.aar`。

## 八、装机验证要点

1. 找一张"同一横坐标两条同 #WAV 定义 note"的铺面，听是否仍是双声；
2. `#WAV4D` 与 `#WAV4E` 指向同一文件的合法叠音**应仍然双声**（不能被误杀）；
3. 选曲预览（`MusicPlayer` 走同一条 `audio.play(Note)` 路径）一并覆盖；
4. BGM/键音 stop 之后不应再有"尾音漏出"。
