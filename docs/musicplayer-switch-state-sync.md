# MusicPlayer 切歌时的状态同步

## 现象

按 PREV / NEXT（以及曲尾自动切歌）时：

- 列表高亮和歌名立刻变成新歌；
- 但 **进度条还在走上一首的进度**，甚至被截到 100%；
- 封面消失、音频中断几百毫秒到几秒；
- 整体观感是"傻快"——切了，但没切干净。

## 根因

`playBaseNanos` 是进度条的唯一时间源，而它要等到 `setModel()` **之后**才更新：

```text
t0      用户按 NEXT
t0      selectedIndex / currentSong 更新 → 列表高亮、歌名立刻变
t0      retireStagefile()              → 封面消失
t0      stopBgThread() + stopAllNotes() → 旧歌停，开始静音
t0→t1   loadBMSModel()                  （几百毫秒）
t1→t2   setModel()                      （几百毫秒~几秒，解码几百个音源）
t2      playBaseNanos = now             ← 这里才更新
```

t0 到 t2 之间：

- `getCurrentPlaybackMs()` 仍然拿**旧的** `playBaseNanos` 去减 → 数值继续增长；
- `totalDurationMs` 还是上一首的 → 进度条按旧歌的比例走，很容易到 100%；
- 于是画面就是"新歌名 + 上一首的进度条"。

`silenceAdvanceWatcher()` 原本置 `Long.MAX_VALUE`，那个更糟：进度条虽然归零了，但时间文字
会变成 `formatTime(Long.MAX_VALUE)` —— 一串天文数字。

## 修复

### 1. 加载一开始就退出"正在播放"的显示态（`requestLoad()`）

```java
loading = true;
isTransitioning = true;
playBaseNanos = 0;      // getCurrentPlaybackMs() 对 0 直接返回 0
totalDurationMs = 0;    // 进度条归零、时间显示 0:00 / 0:00
```

### 2. `silenceAdvanceWatcher()` 改成置 0

监视任务的判据是 `total > 0`，置 0 一样能让它闭嘴，但不会污染时间文字的格式化。

### 3. `loadBMSModel()` 提到 `stopBgThread()` 之前

它只读文件、完全不碰音频驱动，所以旧歌可以一直播到这一步。原来放在停旧歌**之后**，
那几百毫秒就是纯静音 —— 听感上就是"切歌时莫名其妙断一下"。

调整后静音窗口只剩 `stopBgThread()` + `stopAllNotes()` + `setModel()`。

### 4. `startBgThread()` 之后立刻给一个估算时长

```java
totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + Math.max(tail, 0));
```

`tail` 的精确值要读几千个音频头才算得出来；不先给一个的话，进度条会在那几百毫秒里停在 0
—— 看起来像卡住。这个值偏小无害（只表示"至少有这么久"），精确值算完会覆盖它。

**这里有个必须留意的点**：这次赋值让 `totalDurationMs` 从 0 变成非 0，理论上就给了监视任务
触发条件。之所以安全，是因为 `requestLoad()` 设的 `isTransitioning` 要到 `performLoad()`
的 `finally` 才复位，覆盖了整个 `loadSingle()`。

### 5. `render()` 增加 Loading 提示

在封面区中央画一行 `Loading...`。切歌时封面本来就是空的，不会挡到任何东西。

### 6. 自动切歌路径同样处理（`transitionToNextInBackground()`）

- 第 1 步停播放线程后清零 `playBaseNanos` / `totalDurationMs`；
- 第 8/9 步改成"先起播放线程 + 给估算时长"；
- 所有 `Long.MAX_VALUE` 换成 `silenceAdvanceWatcher()`。

## 效果

按 NEXT 之后：

| 时刻 | 界面 |
| --- | --- |
| 立刻 | 列表高亮移到新歌（按键有反馈） |
| 立刻 | 封面清空、进度条归零、显示 `Loading...` |
| 几百 ms | 旧歌在后台一直播到 `setModel()` 之前（没有静音感） |
| 加载完 | Loading 消失、进度条从 0 开始走、新歌开始播 |

任何一个时刻，音频和界面都是一致的，"新歌名 + 上一首进度条"的组合不会再出现。

## 关于"等加载完再切"

没有把列表高亮也延后到加载完成。那样按 NEXT 会有几百毫秒完全没反应，比"切得快"更难受。
需要的其实是**切换过程中界面说清自己在干什么**，而不是把切换本身推迟。
