# 锁屏瞬间的主线程停顿（Oboe 生命周期调用）

## 现象

锁屏瞬间 logcat：

```text
ImeTracker       onCancelled at PHASE_CLIENT_ALREADY_HIDDEN
Choreographer    Skipped 189 frames!  The application may be doing too much work on its main thread.
BatteryNotifier  send audio device update msg!          ← 音频设备切换
```

封面回收、密集段落断音、native 遍历开销三个问题修完之后播放流畅度已经明显改善，
但**锁屏这一下**仍会掉帧。

## 根因

`AndroidLauncher.onPause()` / `onResume()` 在**主线程**（Activity 生命周期回调）同步调用
`oboeAudio.resume()` / `oboeAudio.pause()`：

```java
protected void onPause() {
    ...
    if (isMusicPlayer) oboeAudio.resume(); else oboeAudio.pause();
    super.onPause();
}
protected void onResume() {
    super.onResume();
    oboeAudio.resume();
    ...
}
```

这两个 JNI 调用最终进到 `oboe_engine::resume()` / `stop()`，它们第一件事就是抢
`m_lifecycle_mutex`：

```cpp
void oboe_engine::resume() {
    std::lock_guard<std::recursive_mutex> lifecycle(m_lifecycle_mutex);
    ...
}
```

**而锁屏恰恰是音频设备变化的高发点**（日志里的 `send audio device update msg`）。
设备切换时 Oboe 回调 `onErrorAfterClose(ErrorDisconnected)`，引擎随之进
`connect_to_device()` —— 它同样持有这把锁，而且在锁内做 `close()` + `openStream()`：

```cpp
void oboe_engine::connect_to_device() {
    std::lock_guard<std::recursive_mutex> lifecycle(m_lifecycle_mutex);   // 全程持锁
    ...
    stream->close();                       // 等流真正关闭
    ...
    result = builder.openStream(...);      // 设备切换时可耗到秒级
    ...
}
```

两件事撞在一起：主线程要拿锁，native 侧正持锁重连 → **主线程等约 3 秒** → 189 帧丢失
（189 / 60 ≈ 3.15s，与停顿时间吻合）。

顺带说明为什么之前没暴露：这两个调用一直存在，只是以前主线程被别的东西挡着，
现在其它瓶颈清掉了，它就浮上来成了最大的一笔。

## 修复

在 `AndroidLauncher` 加一个专用 daemon 单线程 executor，把两处调用都改成异步：

```java
private static final java.util.concurrent.ExecutorService OBOE_LIFECYCLE_EXECUTOR =
        java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "OboeAudio-Lifecycle");
            t.setDaemon(true);
            return t;
        });

private void postOboeLifecycle(final boolean resume) { ... }
```

- `onPause()` → `postOboeLifecycle(isMusicPlayer)`（`isMusicPlayer` 恰好就是"保持流
  running"的语义：true 保持，false 停流）
- `onResume()` → `postOboeLifecycle(true)`

流的状态切换不需要同步语义：晚几十毫秒生效完全无害，而主线程一卡就是明显掉帧。
单线程 executor 顺带保证了 pause / resume 的相对顺序。

## 加了诊断日志

```text
AndroidLauncher: oboeAudio resume took Nms     (仅当 > 50ms)
AndroidLauncher: super.onPause took Nms        (仅当 > 50ms)
AndroidLauncher: super.onResume took Nms       (仅当 > 50ms)
```

- `postOboeLifecycle` 的耗时跑在自己的线程上，**再长也不会变成 `Skipped N frames`**。
  但如果这个数值很大，说明 native 侧仍在长时间重连，值得继续往下追。
- `super.onPause()/onResume()` 单独计时是为了区分责任：libGDX 在里面要等
  `GLSurfaceView` 暂停/恢复，慢的话是框架侧的等待，跟 Oboe 无关。

## 还没做（如果这版仍不够）

1. **`onErrorAfterClose` 里同步重连会阻塞音频回调线程**。
   Oboe 官方建议错误回调里不要做重活。目前 `connect_to_device()` 直接在回调线程上跑，
   `close()` + `openStream()` 期间**完全没有音频输出** —— 这就是切换设备时那几秒静音的来源。
   正解是把重连 post 到一个专用线程（需要线程生命周期管理，风险中等）。

2. **buffer 只有 `2 × framesPerBurst`（约 8ms）**，这是给交互式低延迟场景配的。
   MusicPlayer 是纯播放，放宽到 4–8× burst 能显著提升抗抖动能力。
   代价是延迟增加（会影响游戏内打谱的按键音跟手度），所以更适合做成按状态切换 ——
   但切换 buffer 需要重建流，也就是上面那个慢操作，得权衡。

3. **播放中的 `stopAllNotes()` 是全量遍历**：`stop((Note) null)` 会走一遍完整的
   `wavmap` 和 `slicesound`，每个音源一次 JNI + native `soundpool::stop()`。
   按 2000 个音源估算约 2–5ms，目前看不是瓶颈，但如果以后切歌空白仍然明显，
   可以改成只停"当前活跃的 voice"。
