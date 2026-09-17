# MusicPlayer 锁屏后音频不同步 / 停顿 —— 根因与修复

## 现象

应用在前台、进入 MusicPlayer 后锁屏（屏幕熄灭），音频开始**不同步**（音符晚点）并伴随**停顿**。
切回前台后之前的闪退问题已修，但音质问题依然存在。

## 根因

MusicPlayer 的音符调度**不是**跑在音频回调线程上，而是跑在一个普通 Java 线程
`MusicPlayer-BGAutoplay` 上：它按时间轴睡醒，然后往 `AudioDriver` 里灌 note。
**这条链路的精度完全依赖宿主线程被调度的精度。**

屏幕熄灭后 Android 会连做几件事，每一件都直接打在这条链路上：

### 1. CPU 保活锁在锁屏瞬间被释放（主因）

`AndroidLauncher` 里配了 `config.useWakelock = true`，看起来像是已经有了保活。
实际上 libGDX 这把锁是 `FULL_WAKE_LOCK`，而且它在 `AndroidApplication.onPause()`
里被直接 `release()` —— **屏幕一灭就没了**。

它和我们为 MusicPlayer 清空的 `LifecycleListener` 无关，是 `AndroidApplication`
自己的字段，清 listener 管不到它。

结果：屏幕关闭后 CPU 进入低功耗，用户态线程的定时器 slack 被放大，
`sleep(10)` 实际可能几百毫秒后才醒 → 音符集体迟到（不同步）。

### 2. 没有 PARTIAL_WAKE_LOCK，也没有前台服务

全项目此前**没有任何代码使用 WakeLock**（`android.permission.WAKE_LOCK` 在
Manifest 里声明了但没人用），也没有 `startForeground` / `MediaSession`。

锁屏后进程降到后台优先级：

- 被划入 background cgroup，只能跑小核、CPU 份额被压缩；
- 屏幕关闭一段时间后进入 Doze，届时连 WakeLock 都会被忽略；
- 进程被 LMK 回收的优先级也变高。

### 3. 每秒 100 次空转唤醒，正中 throttle 的下怀

上一版 `BGAutoplayThread` 的 sleep 上限是 10ms：

```java
long sleepMs = (timelines[p].getMicroTime() - timeMicros) / 1000L;
if (sleepMs < 1) sleepMs = 1;
if (sleepMs > 10) sleepMs = 10;      // 不管下一个音符在多远，最多睡 10ms
```

不管下一个音符在 20ms 后还是 3 秒后，线程都每 10ms 醒一次 —— 约 100 次/秒，
其中绝大多数是纯空转（稀疏段落里相邻音符常隔几百毫秒）。
在已被压到小核且受 cgroup 限制的情况下，这么高的唤醒频率是最容易被限频的目标，
一被限就是整批音符迟到。

### 4. 调度线程是默认优先级

`BGAutoplayThread` 用默认优先级（Java 5 / nice 0），要和 GL 线程、GC、
曲库扫描线程一起抢 CPU。锁屏后被压到小核时排队更久。

## 修复

### 1. PARTIAL_WAKE_LOCK —— 生命周期由 MusicPlayer 自己管

新增三个文件，沿用 `AudioSpectrumManager` 的静态注入模式（core 定义接口，
android 注入实现，避免 core 反向依赖 Android SDK）：

| 文件 | 作用 |
| --- | --- |
| `core/.../com/starxh/beatoraja/PlaybackCpuLock.java` | 平台无关接口 `acquire(owner, tag)` / `release(owner)` |
| `core/.../com/starxh/beatoraja/PlaybackCpuLockManager.java` | 全局注册点 + 空实现兜底（桌面端 no-op） |
| `android/.../com/starxh/beatoraja/android/AndroidPlaybackCpuLock.java` | 持有 `PARTIAL_WAKE_LOCK` |

要点：

- `PARTIAL_WAKE_LOCK` 只保证 CPU 跑，不点亮屏幕 —— 正是锁屏播放需要的语义；
- `setReferenceCounted(false)`，由本类按 owner 去重；
- 每次 `acquire(timeout)` 带 1 小时超时兜底，万一 `release` 被漏掉也不会永久耗电；
- 用 `WeakHashMap` 存 owner，避免 MusicPlayer 实例被本类强引用住。

调用点：

- `MusicPlayer.create()` 末尾 `PlaybackCpuLockManager.acquire(this, "MusicPlayer")`
- `MusicPlayer.terminatePlayback()` 开头 release —— 放在最前是因为
  `shutdownResources()` 里的 `join` 可能耗时，没必要让 CPU 在这段时间一直被强行唤醒。

`AndroidLauncher.onCreate()` 里注入实现。

### 2. 播放线程不再空转轮询

```java
long sleepMs = (timelines[p].getMicroTime() - timeMicros) / 1000L;
if (sleepMs < 1) sleepMs = 1;
if (sleepMs > MAX_SLEEP_MS) sleepMs = MAX_SLEEP_MS;   // 2000ms，仅防御用
scheduledWakeMs = elapsedMs + sleepMs;
```

直接睡到下一个音符时间点。停止走 `interrupt()`，`stopBgThread()` 里本来就有
`interrupt()` + `join(1000)`，长 sleep 照样能立刻打断，那个 10ms 上限本来就没必要。

### 3. 追赶保护改用"计划醒来时间"做基准

原来用「两次唤醒间隔 > 500ms」判定被系统饿过。一旦允许长 sleep，
曲子里本来就有几秒的静音段，按间隔判会**误判成被饿**，
把静音段后的第一个音符丢掉 —— 听感上就是莫名其妙少一个音。

改成比较「实际醒来时间 vs 上次计划醒来时间」：

```java
long overshootMs = elapsedMs - scheduledWakeMs;
final boolean starved = overshootMs > STARVE_THRESHOLD_MS;
```

静音段睡 3 秒醒来，`overshoot` 接近 0，不触发；真被系统饿 5 秒，`overshoot`
约 5000ms，触发并丢弃积压（不补播——一次性灌进去只会打满 soundpool、
让 Oboe 回调堆积，反而整段卡住）。

### 4. 线程优先级

- `BGAutoplayThread` → `Thread.MAX_PRIORITY`（Android 上约映射为 nice -8）；
- `MusicPlayer-Worker`（切歌/加载线程）→ `Thread.NORM_PRIORITY + 2`。
  不给 MAX —— 它跑的是 BMSModel 加载和 `setModel()` 解码这类重活，抢太狠反而拖慢音符调度。

## 追加发现：1.2 秒的停顿其实是 GC，不是 CPU 被限

真机 logcat：

```
Choreographer: Skipped 144 frames!  wall=1217ms
MusicPlayer:   BGAutoplay starved for 1207ms
```

两个数字几乎相等。这一点很关键：**Java 线程不会因为主线程忙而停**，能做到"主线程卡多久、
播放线程就 starve 多久"的只有 stop-the-world GC。

GC 是谁招来的？`render()` 里的这一行：

```java
if (stagefile == null && currentSong != null) {
    loadStagefile();          // 解码整张封面 —— 在主线程
}
```

切歌时 `transitionToNextInBackground()`（worker 线程）把 `stagefile` 置 null，
下一帧就在 **GL 线程（= 主线程）** 上解码一张几百 KB 的封面：读文件、解压、
展开成数 MB 的 Pixmap。耗时几百毫秒，顺带触发一次大 GC，
GC 的 STW 把 `BGAutoplayThread` 一起暂停 —— 音符整批迟到。

`resume()` 里的 `loadStagefile()` 同理，所以"切回前台"也会断一下。

### 修复：解码挪出 GL 线程

拆成两步：

- `decodeStagefile()` —— 读文件 + 解压 + 缩放，纯 CPU **不碰 GL**，任意线程可调用，
  结果放进 `pendingStagefilePixmap`；
- `applyPendingStagefile()` —— 只在 GL 线程跑，做 `new Texture(pixmap)` 上传。

调用点随之调整：

| 场景 | 原来 | 现在 |
| --- | --- | --- |
| 自动切歌（worker 线程） | 置 null，留给 render 解码 | 直接在 worker 上 `decodeStagefile()` |
| `render()` | 同步解码 + 上传 | 只上传；没待上传的才兜底解码 |
| `resume()` | 同步解码（切回前台卡顿） | `scheduleStagefileDecode()` 丢给 worker |

另外两处顺手修：

- 解码后按长边 640px 等比缩小（显示区只有 480×320，没必要把 2000px 原图传进显存）；
- 上传完成后 `pm.dispose()` —— 老实现从未释放 Pixmap，切几次歌就攒下几张原图的
  native 内存。

## 锁屏时只保留播放逻辑

锁屏后屏幕什么都看不见，但列表字形布局、6 次 `batch.begin/end`、频谱解析、封面解码
照样吃 CPU 并制造垃圾。这些对播放毫无贡献，却会招来 GC 打断音符调度。

新增 `backgrounded` 标志（`pause()` 置 true / `resume()` 置 false / `create()` 复位）：

- `render()` 开头直接 return —— 不绘制、不布局、不解析频谱；
- `decodeStagefile()` 开头 return —— 后台不解码；
- `pause()` 额外 `setContinuousRendering(false)`。

播放线程、切歌、音频输出完全不受影响 —— 那正是锁屏时唯一该继续的事。

### 连带修掉的纹理泄漏

`render()` 提前 return 之后，切歌时塞进 `stagefileToDispose` 的旧纹理**没人清理**了，
锁屏播一晚上能攒下几十张。所以在 `pause()` 里主动 `disposeGlResources(true)`
—— pause 走 GL 线程且此刻上下文仍然有效，是安全的。

同时把 `disposeGlResources()` 拆成带参版本，区分两种语义：

- `contextAlive = true`（pause / 退出）—— 上下文有效，真正 `dispose()`；
- `contextAlive = false`（resume）—— 上下文已重建，旧句柄全是野指针，**只能丢弃引用**，
  拿去 dispose 反而可能误伤新上下文里被复用的纹理 ID。

## 第三轮：只挪"解码"不够，还要挪"谁来付这笔钱"

改完上面两轮，真机仍然报（甚至更严重）：

```
Choreographer: Skipped 211 frames!
MusicPlayer:   BGAutoplay starved for 1771ms
```

说明还有别的重活留在 GL 线程上。逐个排查后找到三处。

### 根因 5：BMS 解析 + `setModel()` + 算 tail 全在 GL 线程

前两轮只处理了"封面解码要挪走"，但 `create()` 和 `loadAndPlaySelected()` 里还藏着
一整套更重的活，而这两个方法都跑在 GL 线程：

| 步骤 | 代价 |
| --- | --- |
| `getSongDatas()` | 读全曲库，几千条记录 |
| `resource.loadBMSModel()` | 解析谱面文件 |
| `audio.setModel()` | **解码几百个音源文件**（真正的耗时主体，几百毫秒~数秒） |
| `calculateMaxTailMs()` | 读几千个音频文件的头 |
| `updateSongTail()` | 写 SQLite |

所以每次进 MusicPlayer、每次手动 PREV/NEXT 都会卡这么一下 —— 而随之而来的
stop-the-world GC 会把 `BGAutoplayThread` 一起暂停。

**修复**：把整套重活搬到专职的 `MusicPlayer-Loader` 单线程上，GL 线程只提交请求。
顺序保持不变（停旧线程 → 停音符 → 解析 → `setModel()` → 算时长 → 解封面 → 起播）。
手动切歌的 `loadAndPlaySelected()` 现在只做 `requestLoad()`。

### 根因 6：手动切歌和自动切歌用两把不同的锁（竞态）

| 路径 | 线程 | 锁 |
| --- | --- | --- |
| 手动 PREV/NEXT → `loadSingle()` | loader | `loadLock` |
| 自动切歌 → `transitionToNextInBackground()` | worker | `synchronized(this)` |

**两把锁互不排斥。** 在曲尾附近点 NEXT 时两条路径会并发 `setModel()`：
一个刚把 wavmap 换成新模型的，另一个还拿着旧引用在播，而 `setModel()` 会
`disposeOld()` 释放 native PCM（Oboe）—— 就是 use-after-free。

**修复**：自动切歌也进 `loadLock`，和手动切歌真正串行。
自动切歌的触发时机和播放模式推进逻辑完全没动。

### 根因 7：没有封面的曲子每帧重试一次文件系统查找

`render()` 里原来的兜底是"stagefile == null 就再解一次"。而没有封面的曲子
`decodeStagefile()` 会直接返回，`stagefile` 永远是 null —— 于是**每帧**都跑一遍
`findImagePath()`（对目录做列举 + 大小写不敏感匹配）。60fps 下每秒 60 次文件系统操作。

**修复**：加 `stagefileDecodeKey` 按曲去重，每首只请求一次，失败就认了。
GL 线程上同步解码的兜底路径整体删掉。

### 顺带修掉的两个隐患

- **监视任务重复启动**：`startAdvanceWatcher()` 每加载一首 submit 一次，
  会累加出多个监视循环，各自触发切歌 —— 表现是曲尾连跳好几首。加 `advanceWatcherStarted` 守卫。
- **加载失败后 200ms 死循环**：加载失败时 `totalDurationMs` 还是上一首的旧值，
  监视任务发现"早过曲尾了"就每 200ms 重触发切歌。加 `silenceAdvanceWatcher()` 置 `Long.MAX_VALUE`。

## 未做 / 后续选项

- **前台服务（Foreground Service）+ MediaSession**：这是把进程彻底拉出 background
  cgroup、豁免 Doze 的标准做法，也是所有音乐 App 的做法。WakeLock 在 Doze 的 idle
  阶段会被忽略，长时间锁屏（>30 分钟）后仍可能失效。需要新增 Service、
  Notification channel、`FOREGROUND_SERVICE_MEDIA_PLAYBACK` 权限，改动面较大，
  本轮未做。如果 WakeLock 方案在长时间锁屏下仍不稳，下一步就上它。
- **切歌时预加载下一首**：`transitionToNextInBackground()` 是同步串行的
  `loadBMSModel` → `setModel`（持 AudioDriver 锁完整解码）→ `calculateMaxTailMs`
  → `updateSongTail`，锁屏后 CPU 受限时可能耗时数秒，期间 `setModel` 占住 AudioDriver
  的锁会让播放阻塞，表现为曲尾空一拍。要改善得提前预加载，属优化非 bug。
- **音频焦点**：未 `requestAudioFocus`，其它应用发声时可能被 duck。

## 验证

1. 进入 MusicPlayer，锁屏，听 2–3 分钟：音符不应迟到，不应出现明显断句；
2. logcat 里应出现 `PlaybackCpuLock: CPU lock acquired (MusicPlayer)`；
3. 若仍频繁出现 `MusicPlayer: BGAutoplay starved for Nms, skipping backlog`，
   说明 CPU 仍在被限 —— 此时应考虑前台服务方案；
4. 退出 MusicPlayer 后应出现 `CPU lock released`，且之后耗电恢复正常；
5. **跨曲尾自动切歌**和**切回前台**时，不应再出现 `Skipped N frames`；
   若 GC 停顿仍在，抓一段 logcat 看 `Background concurrent`/`Explicit` GC 的
   `paused` 时间，确认停顿来自哪次分配；
6. 新增 `MusicPlayer: loaded "<曲名>" in Nms` —— 每首加载耗时直接可读。
   若这个数字仍然很大（>1s），说明瓶颈在音源解码本身，那就要考虑
   preload 下一首（在播放当前曲尾前提前在 loader 上把下一首的 model 解好）；
7. 快速连点 PREV/NEXT：只有最后一次生效（靠 `loadSeq` 序号），中间的 job
   会空转返回，不应出现串音或崩溃；
8. 切歌不应连跳多首（验证 `advanceWatcherStarted` 守卫生效）。
