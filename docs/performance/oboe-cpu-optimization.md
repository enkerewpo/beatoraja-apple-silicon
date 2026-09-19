# libgdx-oboe CPU 占用优化方案

## Context

当前 beatoraja 在 Android 上的音频播放存在 CPU 占用过高的问题，特别是在低端设备上。`generate_audio()` 在 Oboe 音频回调线程（实时线程）上每帧都需要竞争 3 把 mutex（audio_player、music、executor），这是主要的性能瓶颈。

## 当前已有优化

- Low-Latency 模式已启用（oboe_engine.cpp）
- 根据设备架构调整缓冲区大小（32-bit ARM: 4x burst，64-bit: 2x burst）
- 使用 condition_variable 等待（非忙等待）
- LTO 编译优化已启用
- 音频后端自动选择（AAudio > OpenSLES）

## 优化方案

### 优化 1：移除 music::render() 中的冗余锁（高优先级）

**问题**：music.cpp:75-119 中 `render()` 获取 mutex 后读取 `frames_in_pcm`（第 83 行），但后续 `raw_render()`（第 56-73 行）本身只读 `m_main_pcm`、`m_current_frame`、`m_volume`、`m_pan`，不需要锁。多重加锁造成音频回调线程的锁竞争。

**方案**：将 `frames_in_pcm = m_main_pcm.size() / m_channels` 的读取移到锁外（`m_main_pcm.size()` 是无锁的）。只在 swap_buffers() 时加锁。

**文件**：`libgdx-oboe/library/src/cpp/audio/music.cpp`

**预期收益**：music 是主音频源，减少每帧锁竞争可显著降低 CPU。

**风险**：中。需要确保 audio_player 调用 music::render() 期间不会有其他线程调用 music::position()。当前调用链无此问题。

---

### 优化 2：设置 executor worker 线程优先级（中优先级）

**问题**：executor worker 线程（负责异步解码）使用默认优先级，低于音频回调线程。解码速度跟不上时会造成音频断续。

**方案**：在 executor 构造函数中，创建线程后调用 `setpriority(PRIO_PROCESS, 0, ANDROID_PRIORITY_AUDIO)` 设置 Android 标准音频优先级。

**文件**：`libgdx-oboe/library/src/cpp/utility/executor.hpp`

**预期收益**：确保解码线程及时完成，提高 buffer 填充速度。

**风险**：低。Android 音频应用标准做法。

---

### 优化 3：track 清理延迟到 play_audio() 时（低优先级）

**问题**：audio_player::generate_audio() 每次调用都执行 `m_tracks.erase(remove_if(...))`（第 52-55 行），O(n) 复杂度，且 mutex 在音频回调线程竞争。

**方案**：将清理操作移到 play_audio() 时（仅 add 新 track 时清理）。

**文件**：`libgdx-oboe/library/src/cpp/audio/audio_player.cpp`

**预期收益**：降低每帧 generate_audio() 计算量。

**风险**：低。

---

### 优化 4：spectrum_analyzer FFT 计算节流（中优先级）

**问题**：每次 generate_audio() 都调用 `m_analyzer.feed()`（audio_player.cpp:63），FFT 计算量大（KissFFT 1024 点），但 UI 频谱更新可能不需要这么高频率。

**方案**：内部引入节流机制，每 2-3 帧实际计算一次 FFT。

**文件**：`libgdx-oboe/library/src/cpp/audio/spectrum_analyzer.hpp`

**预期收益**：减少 FFT 计算次数，降低 CPU 占用。

**风险**：低。

---

## 实测现状核对（2026-09-19，针对 32 位低端机 60→58 抖帧排查）

源码位置：`libgdx-oboe/library/src/cpp/`；产物 `android/libs/libgdx-oboe.aar`。
注意 **AAR 是预编译产物**，改 C++ 必须 `libgdx-oboe/build.bat`（`gradlew :library:build`）重建并替换。

### 已落地（不必再做）

- `audio_player.cpp`：`m_rendering_flag` 原子自旋替代多把 mutex；空闲池 `active()` 跳过渲染；
  32 位用 int32 混音 + 定点音量（`VOL_SHIFT=12`）；32 位下 track 清理改按 `CLEANUP_INTERVAL` 节流；
- `soundpool::active()` 无锁实现（注释说明了两类竞态为何都无害）；
- `oboe_engine.cpp`：LowLatency、AAudio 优先 + OpenSL ES 回退、断连重连与熔断、LTO。

### 仍未落地（按性价比排序）

1. **`executor::wait()` 是忙等**（`utility/executor.hpp:27-31`）：
   `while (!m_done) m_cond.notify_all();` —— 每轮一个 syscall 的自旋。
   调用方含**音频回调线程**（`music::render()` 补缓冲分支）与 `music::position()`（跳转/续播）。
   4 核 A7 上等于"音频线程周期性烧满一个核 + 每轮一次 syscall"，既可能造成音频毛刺，
   也会从 GL 线程抢核。改法：真条件变量等待（`wait(lock, pred)`），或提前 buffering 到"音频线程永不等待"。
2. **FFT 无节流也无缓存**：`audio_player.cpp:118` 每次回调都 `m_analyzer.feed()`；
   `spectrum_analyzer::get_bands()` 每次查询跑 2×512 点 kiss_fft + 加窗 + 32 段 sqrt。
   查询链 `Lua spectrum()` / `SideSpectrumRenderer` → `AudioSpectrumAdapter` →
   JNI `getSpectrum()`（`NewFloatArray(32)`）→ **Java 再 `new float[64]`**，Lua 侧还要建 64 项 LuaTable。
   即"每次查询 = 2 次 FFT + 2 次数组分配 + 64 个装箱数"。改法：FFT 移出查询路径
   （工作线程算、发布原子快照），Java 侧复用数组，Lua 侧按可见性/帧节流。
   注：Java 侧 `BeatorajaGame.render()` 已有 `MODE_OFF` 门控（不画就不算），但 **Lua `spectrum()` 无门控**，
   皮肤每帧调用就会每帧跑 FFT。
3. **`setBufferSizeInFrames` 在 OpenSL ES 上是 no-op**：API<26（SD210 常见 Android 5.1~7.1）
   走 OpenSL ES，实际缓冲由系统决定；`oboe_engine.cpp:155` 目前对所有架构统一 `framesPerBurst * 2`，
   本节开头写的"32-bit 4× burst"策略**并不在当前代码里**。低端 32 位机若求稳，可只在 32 位分支改 4×（延迟换 CPU）。
4. **解码线程优先级未设置**（`executor` 用默认优先级）。建议方向与本文档原方案相反：
   音频回调本身已是音频优先级，解码线程只需跟上 371ms 缓冲（`m_cache_size = 16*1024*channels`），
   应低于渲染线程，避免与 GL 线程/音频回调抢核。
5. `generate_audio()` 每回调遍历**全部** soundpool（BMS 里每个 wav 一条，常达数千条），
   每条一次 `weak_ptr::lock()` 原子引用计数。`active()` 只省了渲染，没省原子操作。
   可维护紧凑活动列表或改用裸指针 + 代际计数。
6. `music::render()` 的 `while (m_buffer_swap.test_and_set())` 与 `soundpool::render()` 的纯自旋同理：
   与 UI 线程的 `position()` 对撞时会出现双线程互旋。

---

## 关键文件

- `libgdx-oboe/library/src/cpp/audio/music.cpp` - 优化 1
- `libgdx-oboe/library/src/cpp/utility/executor.hpp` - 优化 2
- `libgdx-oboe/library/src/cpp/audio/audio_player.cpp` - 优化 3
- `libgdx-oboe/library/src/cpp/audio/spectrum_analyzer.hpp` - 优化 4

---

## 验证方法

1. 使用 systrace/perfetto 采集音频回调线程的 CPU 占用
2. 对比优化前后同样游戏场景的帧率稳定性和电池消耗
3. 在低端设备（如 32-bit ARM）上验证优化效果

---

## 附：`setSustainedPerformanceMode(true)` 该不该关（2026-09-19）

### 现状

两个调用点，都是硬编码 `true`，且只在 `SDK_INT >= N(24)` 时才真正调用：

- `AndroidLauncher.java:1016-1027` `setupSustainedPerformance()`（onCreate 末尾 500 行 + onResume 1010 行各调一次）
- `compose/SettingsActivity.java:210`（设置界面的窗口创建）

### 它到底做什么

**不是"降频开关"，而是"请求稳定而非峰值"的 hint**：框架把它转给 PowerHAL /
SurfaceFlinger，请求系统"别用 boost 频率，给一个能长期维持的稳定档位"。
官方语义是 *以可能更低的峰值换取更一致的性能*（CPU/GPU 最大频率可能被压低）。

关键推论：**关掉它 ≠ 变快**，而是"拿回 boost 余量、但放弃稳定性承诺"——两种后果同时发生：

| | `true`（现状） | `false` |
|---|---|---|
| 峰值频率 | 被压低 | 拿回 boost，短帧更短 |
| 短时掉帧 | 偶发（余量小） | 可能减少 |
| 长时游玩 | 频率平稳 | 更快进热区 → 热限频到**比 sustained 更低**，出现"前 3 分钟顺、之后变糙"的锯齿 |
| 功耗/发热/耗电 | 低 | 高 |

而该 flag 存在的理由，正是消除"boost → 发热 → 降频"的锯齿。**对一个靠帧节奏吃饭的音游，
稳定性 > 峰值**，所以关它属于方向相反的操作，收益上限有限。

### 三个必须先确认的前提

1. **API < 24 时这个调用是空操作**（被 `SDK_INT >= N` 挡住）。
   **已确认（2026-09-19）：目标设备是 Android 5.1 = API 22 → 该调用从未生效，此项结案。**
   改 `false` 不会有任何变化，也不需要改成可配置项 —— 代码里那三处 `true` 保持原样即可。
2. **OEM 实现差异极大**，很多机型（尤其低端/白牌）基本忽略这个 hint —— 那么"改 false"与"不改"无差别；
3. 该调用在 onResume 里也会重放（`AndroidLauncher:1010`），**运行期无法靠布局或配置单点关闭**，
   要 A/B 只能改代码或加开关。

### SM210（4×A7 1.1GHz / 28nm / Adreno 304）的判断

功耗包络低、热限频温和 → 关掉的风险比 8 系小；但**收益上限也小**，因为：

- 那"少画 2 帧"更可能来自**应用层帧限制器的睡眠过冲**（`MainController.java` API<30 路径：
  `Thread.sleep` + `parkNanos` + Choreographer 相位对齐，第 1004-1046 行）——余量不足时睡眠过冲 1ms
  就错过一个 vsync → 33.3ms 一帧。睡眠过冲主要是**调度/唤醒延迟**，不随频率线性改善；
- 其余来源是 ART GC、音频线程抢核 / `executor::wait()` 忙等（见上文第 1 项）——这些是**抢占**问题，
  提高频率帮不上，反而更容易被热限频放大。

结论：**不值得为了这个 60→58 优先动它**；若要试，按 A/B 实验做，别当正式改动。

### 建议的 A/B 测试协议

1. 基线：同一谱面、同一皮肤，游玩 10 分钟，记录帧时间 p50/p99/max（`adb shell dumpsys gfxinfo` 或
   在渲染循环里加临时统计），并记录 10 分钟后的稳定帧率（看是否出现热限频）；
2. 改成 `false`，同样跑 10 分钟，对比**第 1 分钟**与**第 9-10 分钟**两段：
   - 第 1 分钟变好、后段变差 → 热限频出现，应保持 `true`（或折中：只在短时会话关闭）；
   - 全程变好 → 该机型确实在压频，可考虑关掉或做成可选设置；
   - 无差别 → 命中"OEM 忽略 hint"或 API<24，恢复 `true`，把精力转回上面第 1、2 项（忙等 / FFT）。
3. 在低端设备（如 32-bit ARM）上验证优化效果

---

## 优化准入规则与逐项判定（2026-09-19 用户约束）

以下三条是**硬约束**，后续任何 oboe / 音频侧改动都要先过这道闸；不过闸的一律不写进代码。

### R1．以「减少 GC」和「保证稳定性」为优先

优化目标只有两个：**削减每帧/每次回调的分配与 CPU**，以及**让时序更稳**。
不以「跑分更高」「延迟更低」这类与稳定性冲突的目标为由改动。

### R2．如果优化要拿稳定性换，就不得修改

任何可能引入新竞态、死锁、音频 underrun、回调延迟增长、崩溃面的改动，**宁可不优化**。
禁止以"理论上更快"为理由，去改音频回调线程的同步协议。

### R3．oboe（C++）改动尽量只对 32 位生效

目标设备是 32 位 ARM（Android 5.1），64 位路径的行为应保持不变。
复用仓库里既有的架构判定，不要另造一套：

```c
#if defined(__LP64__) || defined(__aarch64__) || defined(__x86_64__) || defined(__amd64__)
    // 64 位
#else
    // 32 位（要改的逻辑放这里）
#endif
```

已用该宏的文件：`audio/audio_player.cpp`、`audio/audio_player.hpp`、`audio/soundpool.cpp`、
`audio/soundpool.hpp`（各 1 处）。

**注意**：`audio/spectrum_analyzer.hpp` 与 `utility/executor.hpp` **目前没有**这个宏 ——
要在其中加 32 位专属分支得自己引入（引入前先确认是否真有必要，见下方判定）。
另外，**输出逐位相同的等价改写（纯计算重排）不属于"改变 64 位行为"**，
这类改动不必加 gate，加了反而多一份要维护的分支。

### 逐项判定表

| # | 候选 | 位置 | 收益量级 | 稳定性风险 | 判定 |
|---|------|------|----------|------------|------|
| 1 | `executor::wait()` 忙等改真条件变量 | `utility/executor.hpp:27` | 只在缓冲/跳转期间省一个核的自旋，**稳态无收益** | **高** ← 音频回调线程的同步协议，改错即死锁或增加回调延迟 | **拒绝**（R2） |
| 2 | 同上，但只在自旋里加 `yield` | 同上 | 无法证明（4 核且可运行线程≤核数时让出无效） | 低 | **拒绝**（收益不可证明，宁可不优化） |
| 3 | FFT 结果按「音频块」缓存 | `audio/spectrum_analyzer.hpp:49` | ≈50%（查询 60Hz vs 音频块 ~115Hz），约 0.05–0.1ms/帧 | **零** ← 输出逐位相同、只跑在查询线程 | **已实现（2026-09-19，见下节）** |
| 4 | JNI 复用 `jfloatArray` | `native/oboe_audio.cpp:163` | ≈280B/帧 ≈ 17KB/s 垃圾 | 低，但引入"返回数组被改写"的别名风险 | **拒绝**（收益远小于风险面） |
| 5 | Lua `spectrum()` 复用 LuaTable | `skin/lua/MainStateAccessor.java:170` | 当前项目自带皮肤**未调用** → 收益 0 | 中（表可能被跨帧引用） | **不动** |
| 6 | 缓冲 2×→4× burst | `audio/oboe_engine.cpp:155` | 抗 underrun | 行为变更：直接增加音频延迟，音游手感变差 | **拒绝**（除实测 underrun 且明确接受延迟） |
| 7 | 解码线程优先级 | `utility/executor.hpp`（未设） | 不可预测 | 中：降低优先级可能饿死解码线程 → 反而 underrun | **拒绝**（方向无法证明） |
| 8 | `generate_audio()` 遍历全部 soundpool | `audio/audio_player.cpp:45` | 每回调数千次原子操作 | **高** ← 音频回调路径上的数据结构重构 | **拒绝**（R2） |
| 9 | `m_buffer_swap` 自旋改造 | `audio/music.cpp` / `soundpool.cpp` | 小 | **高** ← 与 UI 线程 `position()` 对撞的协议 | **拒绝**（R2） |

**结论：按 R1-R3 过滤后，oboe 侧 9 项里只有第 3 项过闸，且已实现（见下节）；
其余 8 项一律不动。**

### 第 3 项落地记录（2026-09-19）

改动只在一个文件：`audio/spectrum_analyzer.hpp`。

**做法**

1. `feed()` 末尾新增一次数据版本号自增：`m_feed_seq.fetch_add(1, std::memory_order_release)`；
2. `get_bands()` 用 `m_feed_seq != m_last_feed_seq` 判断"自上次查询以来是否有新 PCM"，
   只在有新数据时执行加窗 + 2×`kiss_fft(512)` + 32 段求和，结果存进 `m_band_avg_l/m_band_avg_r`；
3. **平滑（`process_band`：抬升立即、下落 0.65:0.3）仍然逐调用执行** —— 这是"输出逐位相同"的关键，
   不能跟着一起缓存，否则频谱下落速度会变慢（见上文事实修正 2）。

**为什么缓存键不能用 `m_write_pos`**

`m_write_pos` 是对 `FFT_SIZE(512)` 取模的环形位置，而 `m_payload_size = getFramesPerBurst() * 2`。
当某设备的 `framesPerBurst == 256` 时，每次回调正好推进 512 帧 → 位置绕回原值 →
"位置没变"被误判为"没有新数据" → **缓存永远命中、频谱冻结**。
（OpenSL ES 路径下 `setBufferSizeInFrames` 是 no-op，实际回调帧数由系统定，更难假设不是整数倍。）
用单调递增的 `m_feed_seq` 做键则不存在这个漏洞。

**是否违反 R2 / R3**

- R2：音频回调线程只多了一次 `release` 计数自增 —— 无阻塞、无等待、**不建立任何新的同步关系**，
  与既有的 `m_write_pos.store(release)` 发布语义一致；没有改任何协议；
- R3：这是**输出逐位相同的等价改写**，不改变 64 位行为，因此按 R3 的说明**没有加 32 位 gate**
  （加了只是多一份要维护的分支）。若坚持要严格 gate，需把 `spectrum_analyzer.hpp` 引入
  `__LP64__` 宏判断 —— 不建议。

**验证（本轮实际做过的）**

- NDK clang++（28.2.13676358）对 `armv7a-linux-androideabi21` 与 `aarch64-linux-android21`
  各跑 `-fsyntax-only -Wall -Wextra -Wreorder`：**exit=0，零警告**（含初始化列表顺序检查）；
- 用 Python 复刻新旧两版控制流（FFT 用确定性替代函数，等价性与 FFT 实现无关）：
  - 旧版（每次全算）与新版的输出**逐位相同**（frames=384，1.92 次回调/帧的节奏）；
  - 若改用 `write_pos` 当缓存键且 frames=512 → 输出确实**冻结**（复现上述漏洞）；
  - 改用 feed 序号当键且 frames=512 → 正常更新。

**收益**：查询 60Hz、音频块 ~115Hz 时约 **48% 的查询命中缓存**（OpenSL ES 缓冲更大时命中率更高）；
按上文测算，约省 0.05–0.1ms/帧，即帧预算的 0.3%–0.6%。

**尚未生效**：源码改了，但 `android/libs/libgdx-oboe.aar` **仍是旧的** ——
必须重建（`libgdx-oboe/build.bat` → `gradlew :library:build`）并替换 AAR 才会生效。

### 三处事实修正（此前记录有误，以本节为准）

1. **FFT 不在音频回调线程里跑。** `feed()`（`audio_player.cpp:118`）在回调线程只做环形写入；
   2×`kiss_fft(512)` + 32 段合成分解在 `get_bands()`，由**查询线程（渲染线程）**经 JNI 调用
   （链路：`SideSpectrumRenderer.render()` → `AudioSpectrumAdapter` → Kotlin → JNI）。
   这一点决定改动是否合规：**改 `get_bands()` 内部只影响渲染线程 → 合规**；
   **改 `feed()` / `executor` 会触碰音频线程 → 按 R2 拒绝**。
2. **`get_bands()` 的平滑是按"调用次数"衰减的**（`process_band()`：抬升立即、下落 `0.65/0.3`）。
   因此任何"减少查询次数"的优化（节流、隔帧查询、合并多次查询）都会**改变频谱下落速度**，
   属于行为变更而非等价优化 → 不做。第 3 项必须实现成
   "缓存原始 band 值、平滑仍逐调用执行"，才能保证输出逐位相同。
3. **`AudioSpectrumAdapter.java:24` 的 `raw.length == 32` 分支是死代码**：
   native 的 `m_combined_result.resize(BANDS * 2)` = **64**，该分支永不命中，
   当前有效行为是 64 值直通。**不要"修正"这个判断** —— 改成 32 会走进插值分支、改变频谱形状。

### 量级测算

- 频谱开启（`MODE_ON`/`MODE_WAVE`）且处于 PLAY 界面时：**每帧 1 次查询**
  （`BeatorajaGame.render()` 的门控：非 PLAY、或 `audioVisualizationMode == MODE_OFF` 时为 0 次）；
- 单次成本 ≈ 2×`kiss_fft(512)` + 512 点加窗 + 32 段×7 bin 的 `sqrtf` ≈ **0.1–0.2ms**
  （A7@1.1GHz 量级估算，非实测）；
- 即占 16.6ms 帧预算的 **0.6%–1.2%**，其中过闸的第 3 项能省掉约一半。

**所以 oboe 侧对 60→58 的贡献上限约 1%，不是主因。** 既然按 R2 不能为这点收益去动音频同步，
排查应转向与音频无关、且改动不碰实时线程的 Render 侧候选（按嫌疑排序，尚未验证）：

1. 应用层帧限制器的睡眠过冲（`MainController.java` 第 1004–1046 行，API<30 路径：
   `Thread.sleep` + `parkNanos` + Choreographer 相位对齐）—— 余量不足 1ms 时过冲即错过 vsync；
   **已核实并落地方案 A 修正，详见 `docs/performance/frame-limiter-pacing.md`**
   （要点：GLSurfaceView 不做 vsync 等待、限帧器必须留；API≥30 该分支本就跳过；
   修正 = 提交前置量 2ms + 末尾忙等 250µs + 相位纠正改 250µs 限速单向 + 重置改 `= now`）；
2. 皮肤/绘制侧的每帧分配（LR2 类皮肤，见 `lr2-skin-slowness-analysis.md`）；
3. ART GC 尖峰（先看 logcat 的 GC 行，再决定是否值得动）。