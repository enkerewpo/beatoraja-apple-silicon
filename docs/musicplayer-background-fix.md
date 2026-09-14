# MusicPlayer 后台播放崩溃 / 断音修复

涉及文件：

- `core/src/main/java/bms/player/beatoraja/play/MusicPlayer.java`
- `core/src/main/java/bms/player/beatoraja/audio/AbstractAudioDriver.java`

现象：选曲界面的 Music Player 跑 BMS autoplay 时，把应用切到后台（最小化 / 锁屏）后：

1. 音频会断开；
2. 线程行为不稳定；
3. 切回前台后容易闪退。

---

## 1. 根因 A：`setModel()` 与播放线程竞争 —— native use-after-free（闪退主因）

`AbstractAudioDriver` 里只有 `setModel()` 是 `synchronized`：

```java
public synchronized void setModel(BMSModel model) { ... cache.disposeOld(); wavmap = ...; ... }
```

而播放路径 `play0()` / `stop(Note)` **完全没有锁**，且 `wavmap` / `slicesound` 是普通字段（非 volatile）：

```java
private final void play0(Note n, float volume, int pitchShift) {
    final T wav = (T) wavmap[id];   // 无锁读
    stop(wav, channel);
    play(wav, channel, volume, pitch);
}
```

Android 上 `T` = libGDX `Sound`（libgdx-oboe `OboeSound`），PCM 是 **native 对象**。
自动切歌的 `transitionToNextInBackground()` 原来直接调 `setModel(newModel)`：

- `setModel()` 整体替换 `wavmap` 并 `disposeOld()` 释放旧 PCM；
- 播放线程 / 混音器可能正拿着旧引用在播；
- 释放后继续用 → **use-after-free → SIGSEGV**。

旧的自动切歌路径甚至刻意"不调 `audio.stop()`"（注释说让最后一条 note 自然播完），
等于把混音器里还在排队的 sample 直接暴露给释放操作。

### 修复

1. **音频驱动加锁**：`AbstractAudioDriver.play(Note, float, int)` 与 `stop(Note)` 改为
   `synchronized`，与 `setModel()` 共用同一把 monitor。从此"换模型"与"播/停音符"天然互斥。
   （无子类覆写这两个方法，改动只在基类。）
2. **换模型前做交接**：新增 `stopBgThread()`（`stop` 标志 + `interrupt()` + `join(1000)`）
   与 `stopAllNotes()`（`audio.stop((Note) null)`），在 `setModel()` **之前**调用。
   手工切歌（`loadAndPlaySelected`）和自动切歌（`transitionToNextInBackground`）都走这条路径。

---

## 2. 根因 B：后台被饿过之后一次性补播积压音符（断音 / 线程不稳主因）

`BGAutoplayThread` 原来：

- 时间源是 `System.currentTimeMillis()`（wall clock，会被 NTP 校时 / 用户改表跳变）；
- 循环里 `sleep` 上限 5 ms，忙等。

应用切后台后系统可能长时间不给这个线程 CPU（Doze / 后台配额 / 大核下线）。
唤醒时 `elapsed` 一下前进好几秒，旧实现把这几秒内积压的**上千个 note 一次性全灌进
AudioDriver** —— soundpool 瞬间打满、Oboe 回调堆积，表现就是爆音、声音断掉、
线程卡死，严重时整个音频层失联。

### 修复

- 时间源换成 `System.nanoTime()`（单调时钟）。`playStartTimeMs` → `playBaseNanos`，
  `getCurrentPlaybackMs()` 同步改基准。
- 新增"追赶保护"：两次唤醒间隔超过 `STARVE_THRESHOLD_MS`（500 ms）就判定被系统饿过，
  **丢弃**跳过的 timeline（只推进游标 `p`，不调用 `audio.play()`），从当前时间点继续。
- `sleep` 上限从 5 ms 放宽到 10 ms：后台时系统定时器本来就没那么准，5 ms 空转只是白烧 CPU。
- 整段 `run()` 包 `try/catch(Throwable)` 并打日志 —— 播放线程"静默消失"本身就是断音的一种表现。

---

## 3. 根因 C：GL 资源在上下文重建后变成野句柄（切回前台闪退）

- `solidColorPixmap` / `solidColorTexture` 是 **static**。Android 切后台会销毁并重建 GL 上下文，
  static 字段里的 Texture 句柄会失效，而 static 又不会随实例重来 —— 回到前台再画它就是野句柄。
  另外这两个方法还有个逻辑 bug：`ensureSolidColorTexture()` 只在 null 时创建，之后反复用不同
  颜色 fill 同一个 Pixmap 但纹理不重传，实际颜色全靠 `batch.setColor()` 二次相乘。
- `stagefile` 同理。旧 `resume()` 的判据是 `if (stagefile == null) loadStagefile();` ——
  但失效的 Texture **不是 null**，所以永远重载不了。
- `font`：`MainController.resume()` 会 dispose 旧的 `systemfont18` 再重建；而 `dispose()` 里的
  判据是 `font != main.getSystemFont18()`，resume 换过实例后这个引用比较就不再可靠，可能
  double free。

### 修复

- 删掉 static 的纯色资源，改成实例字段 `whiteTexture`（1×1 纯白）+ `getWhiteTexture()`，
  纯色块一律靠 `batch.setColor()` 染色。
- 新增 `disposeGlResources()`，`resume()` 里**无条件**先释放再重载（不再用 `== null` 判断）。
- 新增 `fontOwned` 标志：只有本类自己 `new BitmapFont()` 的才在 `dispose()` 里释放；
  来自 `main.getSystemFont18()` 的一律不碰，`resume()` 里直接换引用（旧的已经被
  MainController 释放过，不能再 dispose）。

---

## 4. 根因 D：自动切歌的线程模型

原来是每首歌 `new AutoAdvanceThread()`，切歌时新起两个线程、旧的 `bgThread` 直接被字段覆盖
（没有显式 stop，只靠它自己播完自然退出）。这个模型下线程创建/销毁的窗口会和 GL 线程的手工
切歌抢同一把 `synchronized(this)`，也容易漏停。

### 修复

- 删掉 `AutoAdvanceThread`，改为 **worker 单线程上的常驻监视任务**
  （`Executors.newSingleThreadExecutor`，守护线程 `MusicPlayer-Worker`）：每 200 ms 检查
  `getCurrentPlaybackMs() >= totalDurationMs`，到点直接调 `transitionToNextInBackground()`。
  只有一个后台线程，和 GL 线程的手工切歌共用 `synchronized(this)`，天然串行。
- 监视任务用 `try/catch(Throwable)` 兜底；`dispose()` 用 `worker.shutdownNow()` 打断它的 sleep。
- 切歌失败（无歌 / 加载 BMSModel 失败）时把 `totalDurationMs` 置为 `Long.MAX_VALUE`，
  否则监视任务会每 200 ms 重新触发一次切歌，变成死循环。
- 切歌起新线程前再查一次 `disposed`，避免 dispose 期间起出孤儿线程。
- `calculateMaxTailMs()` + `updateSongTail()`（读几千个音频文件头 + 写 SQLite）包 `try/catch`，
  后台数据库被别的线程占用时不能直接把切歌流程带崩。

---

## 5. 根因 E：EXIT 后仍播放 —— `shutdown()` 与 `dispose()` 不对称

现象：从 Music Player 点 EXIT（或按 BACK / ESC）切回选曲界面后，音乐没停，继续播放。

根因：`MainController.changeState()` 切换状态时调的是 `current.shutdown()`，**不是** `dispose()`。
而 `MusicPlayer.shutdown()` 原本只调了 `shutdownResources()`，**既没有** `disposed = true`、**也没有**
`worker.shutdownNow()`。于是：

- worker 上的常驻"自动切歌监视任务"继续存活（死循环 `while(!disposed && !interrupted)`）；
- 一旦播放进度到达 `totalDurationMs`，它就会调 `transitionToNextInBackground()`，
  重新 `setModel()` + `startBgThread()` → 退出后音乐又响起来；
- 而且这是在 MusicPlayer 已不在前台状态之后发生的，表现为"退出后音乐还在播"。

修复：

- 抽出 `terminatePlayback()`：先 `disposed = true` → `worker.shutdownNow()` → `shutdownResources()`
  → `disposeGlResources()`。`shutdown()` 与 `dispose()` 都复用它（`shutdown()` 不碰 font/shapeRenderer，
  留待下次进入复用）。
- `disposed = true` 必须作为第一件事：正在 worker 上跑的 `transitionToNextInBackground()` 会在它的多处
  `if (disposed) return false` 守卫点尽快 return，不会在 transition 中途 `startBgThread()` 起出孤儿线程。
- `create()` 开头复位 `disposed = false` / `isTransitioning = false`，否则再次进入 MusicPlayer 时
  因 `disposed` 仍为 true，自动切歌会被永久拦下（能播当前曲但永远不切歌）。

---

## 验证

`./gradlew :core:compileJava` 与 `./gradlew :android:compileDebugJavaWithJavac` 均通过。

需要在真机上确认的行为：

1. Music Player 播放中按 Home 切后台 → 音乐继续，不爆音；
2. 后台跨过曲尾 → 自动切下一首，不静音；
3. 后台待 5～10 分钟再切回 → 不闪退，进度条位置合理，舞台图正常显示；
4. 频繁点 PREV / NEXT → 不崩、不重叠；
5. 退出 Music Player → 无孤儿线程（logcat 里不应再出现 `MusicPlayer-BGAutoplay` 的新日志）。
6. EXIT 后立刻、以及 EXIT 后等曲尾时长再观察 → 音乐彻底停住，不会"退出后自动续播下一首"。

`BGAutoplayThread` 被饿到时会打 `MusicPlayer: BGAutoplay starved for Nms, skipping backlog`，
这是预期日志，不是错误。
