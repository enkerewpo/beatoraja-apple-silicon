# 帧限制器时序修正（MainController.render 尾部 / API<30 路径）

对应 `docs/performance/oboe-cpu-optimization.md` 末尾"嫌疑项 ① 应用层帧限制器睡眠过冲"。
本文记录**核实过的平台事实**、旧实现的三处不合理、以及落地改法（方案 A）。

## 一、平台事实（先钉死，别再猜）

### 1.1 GLSurfaceView 不做 vsync 等待，但 BufferQueue 会反压

- **AOSP 5.1.1 的 `GLSurfaceView` 全文件不调用 `eglSwapInterval`**（`EglHelper` 里只有
  `eglCreateWindowSurface` / `eglSwapBuffers` 等，没有任何 vsync / Choreographer 等待代码）
  → swap interval 取 EGL 默认值 1。
- 但 Android 的呈现路径是 **BufferQueue 反压**：队列没满时 `eglSwapBuffers` 立刻返回
  （最多领先 1~2 帧），队列满时才阻塞。**"靠 vsync 天然限帧"不成立** —— vsync 只管
  "能不能更快呈现"，不管生产者跑多快。
- 结论：**应用层限帧器是必要的，不能删**（删了会跑满 CPU/GPU、顶满队列、发热）。
  问题只在于它怎么限。

### 1.2 限帧器在哪些设备上真的生效

| 设备 | `setFrameRateMethod` | `doFrameLimit` |
| --- | --- | --- |
| API ≥ 30（Android 11+，含所有高刷机） | 反射成功 | **false（整段跳过）** |
| API < 30（如本文目标机 Android 5.1 / API 22） | 反射必然失败 | **true（本改动生效）** |

`updateFrameRateAPI()` 在构造（208）、`changeState()`（333）、`resume()`（1141）三处调用，
一旦成功 `setFrameRateMethod` 就永久非 null。所以 **Android 11+ 的高刷设备根本不走这段代码**，
本改动对它们是零影响。

### 1.3 FPS 读数的语义（决定能在哪里找原因）

libGDX 的 fps = `onDrawFrame` 调用次数 / ≥1s 窗口
（`AndroidGraphics.java:529`：`if (time - frameStart > 1e9) { fps = frames; frames = 0; ... }`）。
所以 **58 = 那一秒渲染循环少跑了 2 次**，不是"画面晚了几毫秒"。要找的是
"哪一步让循环丢掉整整一个周期"。

### 1.4 限帧器不是这一帧的最后一步

`MainController.render()` 末尾等待结束后，`BeatorajaGame.render()` 还要跑
`configureSpectrumRenderer()` + `spectrumRenderer.render()`（JNI + 若干 sprite），
最后才是 GLSurfaceView 的 `eglSwapBuffers`。即：

```
真实提交时刻 = 限帧器目标时刻 + 唤醒过冲 ε + (频谱 + swap 提交) 1~3ms
```

**PLAY 界面（掉帧发生处）这段开销最大**，而它完全不在预算里。

## 二、旧实现的三处不合理

### ① 第三阶段忙等在 Android 被跳过 → 过冲全额转嫁

```java
// 旧代码
if (!isAndroid) { while (System.nanoTime() < nextFrameTimeNanos) { } }
```

`nanosleep` / `parkNanos` 的语义是"**不早于**"：低端机在 cgroup 限频 / 深 idle 下实测唤醒
过冲 0.3~2ms，**偏差单向（只会晚、不会早）**。非 Android 用末尾忙等抹掉它，Android 直接返回
→ 每帧都以 `T + ε` 交付。
另：注释里"保留 1ms 缓冲"不准 —— `parkNanos(200_000)` 循环的退出条件是"剩余 ≤200µs"，
而每次 park 实际可能睡 300~500µs，它在目标前 0.2~0.5ms 就退出了，**交付相位本身是随机的**。

### ② 目标时刻选成"帧周期终点"，零余量

目标 = 估算的 vsync 网格点，没有余量吸收 ① 的 ε；再叠加 1.4 的频谱 + swap 开销，
提交必然落在网格点右侧。改法是给"提交前置量"（提前醒来），顺带把延迟往低的方向推。

### ③ Choreographer 相位量化的机制是错的

```java
// 旧代码
long intervals = Math.round((double) (nextFrameTimeNanos - lastVsyncTimeNanos) / frameIntervalNanos);
nextFrameTimeNanos = lastVsyncTimeNanos + intervals * frameIntervalNanos;
```

- **动机是对的**：不锁相位的话，"应用周期 `1e9/maxFPS` ≠ 面板真实周期"会累积相位漂移
  （典型 10~20µs/帧），提交时刻缓步滑过合成截止时间，现场表现就是周期性微卡顿。
- **机制是错的**：这是"就**近**取整"→ 一次最多 **±半个周期**的硬跳变（60Hz = ±8.3ms，
  120Hz = ±4.2ms），比它想消除的漂移大两个数量级；跳变方向朝后时，那一帧的提交直接被推迟。
- 而且 `lastVsyncTimeNanos` 由 **UI 线程** Choreographer 回调写（`AndroidLauncher.java:82-90`），
  GL 线程只读 —— 4×A7 上 GL 线程 / 1000Hz 输入轮询 / 音频回调一起抢 CPU 时，
  UI 线程的回调会滞后好几帧。

### ④（附带）卡顿后的重置会白等一个周期

```java
if (nextFrameTimeNanos == 0 || now - nextFrameTimeNanos > frameIntervalNanos * 3) {
    nextFrameTimeNanos = now + frameIntervalNanos;   // 往后追加一个整周期
}
```

卡顿 >3 帧后额外等一个空周期，把"一次卡顿"放大成"卡顿 + 一帧空白"。

## 三、落地改法（方案 A）

只改 `core/.../MainController.java`，不新增跨模块接口（`setLastVsyncTimeNanos` 签名不变）。

1. **提交前置量** `SUBMIT_LEAD_MAX_NANOS = 2ms`
   （`waitTarget = nextFrameTimeNanos - min(2ms, frameInterval/4)`）：
   语义从"准点起床"改成"提前起床"，唤醒过冲与频谱/swap 开销由余量吸收，
   最后一段交给 `eglSwapBuffers` 自己收尾。`/4` 的 clamp 保证高刷下前置量不喧宾夺主。
2. **末尾忙等恢复（Android 也执行）** `BUSY_WAIT_TAIL_NANOS = 250µs`，
   并把 park 循环改成步长收缩（1ms → 500µs → 200µs），压小最后一次 park 的过冲。
   成本：250µs / 16.67ms ≈ 单核 1.5%（60Hz）；120Hz 帧周期 8.33ms ≈ 3%（该线程本来就在睡，不影响其他线程）。
3. **相位纠正改成"限量 + 只往回拉"** `PHASE_FIX_MAX_NANOS = 250µs`：
   保留旧实现"锁相位"的意图（抵消 10~20µs/帧的漂移，每帧都被完全抵消），
   但任何坏输入（时间戳抖动/滞后）最多造成 250µs 偏差，**绝不可能把提交推晚**。
   新增 `lastVsyncRecordNanos` + 2 个帧周期的新鲜度判据，超期就不采信该时间戳。
4. **重置改 `= now`**（不再 `now + interval`）：卡顿后立即恢复，允许 catch-up；
   落后 >3 周期才重锚，落后 <3 周期时保持落后，由 `remaining <= 0` 自然跳过睡眠逐帧追平。
5. 每阶段都保证"剩余 ≤0 时全部跳过、立即返回"，不会出现负时长睡眠或忙等长转。

## 四、对高刷设备的影响（用户关注点）

- **Android 11+（API≥30）的所有高刷机器：`doFrameLimit == false`，整段不执行 → 零影响。**
- **API<30 且高刷（如 Android 10 的 90Hz / 120Hz 机）**：限帧器生效，本改动的收益更大 ——
  旧实现的硬跳变幅度是 ±frameInterval/2（90Hz = ±5.6ms，120Hz = ±4.2ms），相对周期占比更高；
  改后同样的漂移由 250µs/帧的限速纠正抵消，且前置量 clamp 到 `frameInterval/4`
  （60Hz 也才 2ms，120Hz 下同样是 2ms 且不超过周期的 1/4）。
- 桌面路径（非 Android，VSync 关闭 + maxFPS 限制）：逻辑同构，仅"醒来提前 + 忙等有上限"，
  不会改变目标帧率（目标时刻仍每帧推进一个周期）。

## 五、验证状态

- **已做**：`javac`（JDK 21，core 模块 classpath）单文件编译 → **exit=0**，无新增告警。
  本改动不涉及任何对外签名，android 模块无需重新校验。
- **未做**：真机验证（未打包、未 commit）。
- **建议装机观察**：① PLAY 界面 FPS 读数是否稳定（是否仍出现 58）；
  ② 手感延迟是否有变化；③ 长时段游玩是否出现新的周期性微卡顿（相位纠正的反效果）。

## 六、仍未结案的"58"

FPS 读数语义（1.3）决定了 58 还有两个与本改动无关的成因，需实测区分：

1. **帧预算本身不够**：`BeatorajaGame.render()` 的实际工作耗时 ≈17ms > 16.67ms 预算
   → 循环自然只有 ~58 次/秒，与限帧器无关；
2. **卡顿事件**（GC / BGA 解码 / 生命周期 / 线程抢占）触发重置分支。

区分方法：加 1 秒一行的只读诊断（不改行为）——
`工作耗时 max/avg`、`唤醒误差 (wake−target) max/avg`、`重置次数`、`本秒帧数`。
本次未加（用户只要求落地方案 A），需要时再补。

## 七、相对旧实现的优点与代价（决策复盘）

### 7.1 优点

| # | 旧实现 | 新实现 | 性质 |
| --- | --- | --- | --- |
| 1 | Android 上跳过忙等 → 唤醒误差 = 随机 0.3~2ms（单向偏晚） | 忙等 250µs 收尾 → 唤醒误差 µs 级，**恒不晚于** `waitTarget` | **确定性**（可测量的硬改善） |
| 2 | 目标 = vsync 网格点（零余量），提交 = 网格点 + ε + 频谱/swap 1~3ms → 必然落在网格点**之后** | `waitTarget = 网格点 − min(2ms, interval/4)` → 提交**落在网格点之前** | **相位前移**，更可能赶上本次合成 |
| 3 | `Math.round` 就近取整 → 单次最多把提交推晚 **±半周期**（60Hz = 8.3ms） | 限量 250µs 且 `late > 0` 才动 → **结构上不可能把提交推晚** | **单向性 / 风险封顶** |
| 4 | 直接采信 UI 线程写的 `lastVsyncTimeNanos`（可能滞后数帧） | 加 `lastVsyncRecordNanos` 新鲜度判据（>2 周期不采信） | **跨线程输入隔离** |
| 5 | 卡顿重置 `= now + interval`（白等一个空周期） | `= now`（立即 catch-up） | **消除自伤** |
| 6 | — | 帧周期不变（`nextFrameTimeNanos` 仍每帧 `+= interval`，前置量只作用于 `waitTarget`） | **帧率语义中性** |
| 7 | — | API≥30 走 `doFrameLimit=false`，改动是死代码路径 | **对高刷机零影响** |

注：1/2/3/5 都是"去掉一种**可能**丢帧/变晚的机制"，**不是**对 58 的因果证明（见第六节）。

### 7.2 代价与已知缺点

1. **CPU/功耗**：250µs/帧 忙等 → 单核 ≈1.5%（60Hz）/ 3%（120Hz）。这段自旋在 **GL 线程**上，
   与音频回调、1000Hz 输入轮询抢同一批 A7 核心；虽然远小于一帧预算，但在 512MB/低端机上
   属于"白拿的确定性"的固定代价。常数可置 0（退化为只靠前置量兜底）。
2. **前置量是估值，无实测**：`SUBMIT_LEAD = 2ms` 来自"频谱 + swap ≈1~3ms"的推算。
   偏小 → 余量不足，仍可能晚；偏大 → 提交过早、被 BufferQueue 反压挡回来（等价旧行为，**不丢帧**，
   但前置量白给）。⇒ 该常数**偏大比偏小安全**，这是刻意的保守选择。
3. **放弃了双向锁相**（`只往回拉` 的固有代价）：旧代码的 snap 能把相位**往后**拉回网格点（真正的相位锁定），
   新代码只往前拉。若 `1e9/maxFPS` **小于**面板真实周期（如面板 59.94Hz + `maxFPS=60`，
   差 16.7µs/帧），`nextFrameTimeNanos` 会相对 `now` 单调后退，最终落到过去 →
   **限帧器逐渐退化为"完全不睡眠"**，节流改由 BufferQueue 反压承担（= 备选方案 B 的行为）。
   稳定性上无害（反压本身就是 vsync 驱动），但这意味着**"锁相位"这一项长期收益实际拿不到**，
   且若某设备驱动的 `eglSwapBuffers` 不满队列时不阻塞，就会失去限帧保护。**此项需真机确认。**
4. **允许瞬时超速**：catch-up 生效期间（落后 <3 周期）连续不睡眠 → 短暂帧率 > `maxFPS`。
   旧实现因"永远往后排一个整周期"而严格 ≤ `maxFPS`。Android 上有 BufferQueue 兜底；
   桌面 + 关闭 vsync 时最明显。语义变化，非 bug。
5. **复杂度上升**：新增 3 个常量（`SUBMIT_LEAD` / `BUSY_WAIT_TAIL` / `PHASE_FIX`）+ 1 个 volatile 字段
   + 3 个分支，且 `SUBMIT_LEAD` 与 `BUSY_WAIT_TAIL` 功能重叠（都在吸收过冲）——失效时难以定位该调哪个。
6. **残留未清理**：`lastVsyncTimeNanos` + `AndroidLauncher` 里每帧跨线程写的 Choreographer 回调
   仍然保留，但换来的收益已只剩"每帧最多 250µs 的往回拉"。要彻底简化可以整条链路退役
   （改动面会扩到 `AndroidLauncher`，本轮为控制风险未动）。
7. **未验证**：无真机数据、无诊断打点，"58 是否消失"未知；且 58 另有"帧预算不够"的独立成因（第六节）。

### 7.3 一句话取舍

> 用 **1.5% 的单核自旋 + 一次 2ms 的相位前移**，换 **"提交时刻不可能变晚"** 这一条硬保证。
> 拿不到的东西是"长期相位锁定"和"严格不超速"——前者由 BufferQueue 反压等价兜底，后者无害。
