# MusicPlayer 修复：封面回收、播放卡顿与列表触摸

本轮修三个用户直接反馈的问题：

1. 切歌时 stagefile 完全没有回收处理，导致显示不正确
2. 播放卡顿仍然存在
3. 歌曲列表的触摸命中区域不对（点第 4 首选到第 6 首；列表右半边点不动、滑不了）

前两个落在"异步解码封面"和"音符调度线程"两条链路上，第三个是坐标系用错了源。

---

## 一、stagefile 回收

### 现象

切歌后中央的封面不更新：要么短暂显示上一首的图，要么（新歌没有封面时）**永远**停在上一首的图上。

### 根因

**A. 手动切歌路径根本不动 stagefile**

`loadSingle()` 里换 `currentSong` 之后只是重置了 `stagefileDecodeKey` 并调 `decodeStagefile()`，
从来没有把旧的 `stagefile` 摘下来。

而 `stagefile` 是"当前显示的封面"，换歌就必须立刻下屏。后果：

- 新歌封面解出来之前，屏幕上一直挂着上一首的图（解码要几百毫秒，肉眼可见）；
- 新歌**没有封面**时，`decodeStagefile()` 直接 return，那张旧图就永久留在那儿了。

**B. 自动切歌路径的清理被错误地套在 `if (stagefile != null)` 里**

```java
if (this.stagefile != null) {          // ← 问题在这
    this.stagefileToDispose = this.stagefile;
    this.stagefile = null;
    stagefileDecodeKey = currentSong.getPath();
    decodeStagefile();                 // ← 解码也被连坐了
}
```

"摘掉旧封面"和"解码新封面"是两件互相独立的事，却被同一个 `if` 门控。
上一首没封面、或者上一首的封面还躺在 `pendingStagefilePixmap` 里没上传时，
`stagefile` 是 null —— 于是**既不清也不解**，新歌同样永远等不到封面。

**C. `pendingStagefilePixmap` 没有归属标记**

解码在后台线程、上传在 GL 线程，两个时刻之间可能已经切过歌。
上传端只看"有没有 pixmap"，就会把上一首的图当成当前这一首画上去。

更隐蔽的后果是它和 `ensureStagefileRequested()` 的去重逻辑叠加：

```java
if (stagefile != null || pendingStagefilePixmap != null) {
    stagefileDecodeKey = key;   // 认为"这首已经请求过了"
    return;                     // 于是永远不再请求
}
```

一旦那张残留 pixmap 被当成"本曲已有封面"，`stagefileDecodeKey` 就被写上，
当前这首从此再也不会去解码 —— 一路错下去，只能靠切歌复位。

**D. 单槽位 `stagefileToDispose` 会丢纹理**

`volatile Texture stagefileToDispose` 只能挂一张。连着切两首时，前一张被覆盖 → 直接泄漏。

### 修复

1. 引入统一的 `retireStagefile()`：把当前封面从 `stagefile` 摘下来（同步、立即生效），
   投递进无锁队列 `glTextureGarbage`，GL 线程每帧 `drainRetiredTextures()` 释放。
   **所有切歌路径**（`loadSingle` / `transitionToNextInBackground`）都调它。
   摘除和解码彻底解耦，不再受任何 `if` 门控。

2. `pendingStagefilePixmap` 配上归属标记 `pendingStagefileKey`（记 `currentSong.getPath()`）：
   - 解码端解完先校验"还是不是当前这首"，不是就 `dispose()` 丢弃；
   - 上传端 `applyPendingStagefile()` 再校验一次，不匹配同样丢弃，
     并且**把 `stagefileDecodeKey` 清掉**，让下一帧重新请求 —— 这是修复 C 里
     "永久错下去"的关键。

3. `decodeStagefile()` 内部改用 `currentSong` 的**单次快照**。
   它原来是 `volatile` 逐字段读（先读 `song.getStagefile()`、再读 `song.getPath()`），
   切歌时可能拼出"用 A 的目录 + B 的封面名"这种半新半旧的组合。

4. `applyPendingStagefile()` 改成"先挂新纹理、再放旧句柄"，中间不会出现
   `stagefile == null` 的空窗；上传失败时也不会误放正在使用的那张。

---

## 二、播放卡顿

### 根因 D（主因）：播放循环自身的耗时被误判成"被系统饿过"

`BGAutoplayThread` 里有一段"追赶保护"，本意是防止应用切后台后被系统饿过、
唤醒时把积压的上千个音符一次性灌进 AudioDriver（那会打爆 soundpool）。

判据是"实际醒来时间比计划醒来时间晚了多少"：

```java
long elapsedMs  = (System.nanoTime() - baseNanos) / 1000000L;   // t0
long timeMicros = elapsedMs * 1000L;
// ...判断 starved...
while (p < timelines.length && timelines[p].getMicroTime() <= timeMicros) {
    audio.play(n, vol, 0);        // ← 可能耗时几百毫秒
    p++;
}
// 修复前：没有重算 elapsedMs / timeMicros
long sleepMs = (timelines[p].getMicroTime() - timeMicros) / 1000L;
scheduledWakeMs = elapsedMs + sleepMs;     // 用的还是 t0
sleep(sleepMs);
```

下一轮算出来的 `overshootMs` 是：

```
overshootMs = (t0 + T_play + sleep_actual) - (t0 + sleep_planned)
            ≈ T_play          ← 播放循环本身的耗时
```

**也就是说，只要一次播放循环耗时超过 `STARVE_THRESHOLD_MS`（500ms），
它就必然被误判成"被系统饿过"，然后丢掉接下来的整批音符。**

密集段落一次要发几百个 note，每个 `audio.play()` 都要过 JNI 并抢 AudioDriver 的
monitor，几百毫秒非常容易达到。结果是：

- 越密集的地方越容易触发；
- 丢弃后游标 `p` 大幅前进，下一轮又快速循环一遍，**自我强化**；
- 听感就是"鼓点密的地方突然断一截"，而且每首曲子重复出现。

**修复**：播放循环结束后重新采样时间，用"循环之后"的时刻作为下一次调度基准。

```java
elapsedMs  = (System.nanoTime() - baseNanos) / 1000000L;
timeMicros = elapsedMs * 1000L;
```

这样 `overshootMs` 才真正只反映"系统有没有给 CPU"，追赶保护回归它本来的职责。

### 根因 E：切歌时的静音窗口里塞了大量不发声的重活

`loadSingle()` 和 `transitionToNextInBackground()` 都遵循这个顺序：

```
stopBgThread()  →  stopAllNotes()  →  加载/换模型  →  startBgThread()
        ↑                                                  ↑
     音符调度停下                                        音符调度恢复
```

**这中间就是用户听到的切歌空白。** 而窗口里还塞着：

| 步骤 | 代价 |
| --- | --- |
| `calculateMaxTailMs()` | 读几千个音频文件头 |
| `updateSongTail()` | 写一次 SQLite |
| `decodeStagefile()` | 解码整张封面 |

这三件都不发声，却让空白长了可感知的一截（tail 计算在冷启动时尤其慢）。

**修复**：把 `startBgThread()` 提到它们前面。

- 时长先用一个安全值让监视任务闭嘴（`totalDurationMs = Long.MAX_VALUE`，
  否则它可能拿偏短的旧值误判曲尾提前切歌），tail 算完再写入真实值；
- `decodeStagefile()` 挪到播放线程起来之后；
- 异常兜底：`calculateMaxTailMs()` 失败时 `tail` 退化为 0，
  绝不能让 `totalDurationMs` 永远停在 `Long.MAX_VALUE` —— 那样就再也不切歌了。

### 根因 F：Oboe 每帧遍历几千个空 soundpool（native）

这条不在 Java 侧。`libgdx-oboe` 里**每个 wav 文件都是一个独立 `soundpool`**，
全部注册进 `audio_player::m_tracks`：

```cpp
// oboe_audio.cpp
auto* player = get_or_create_shared_player(env, self);
auto ptr = new std::shared_ptr<soundpool>();
*ptr = std::make_shared<soundpool>(buffer, k_channels);
player->play_audio(*ptr);        // 每个音源一个 track
```

一首 BMS 有几百到几千个 wav，所以 `m_tracks` 就是几千个元素。而
`generate_audio()`（Oboe 回调，约每 4ms 一次）无条件遍历全部：

```cpp
for (const auto &weak_track : m_tracks) {
    is_dirty |= weak_track.expired();
    if (auto track = weak_track.lock()) {          // shared_ptr 原子加解锁
        std::fill(m_buffer.begin(), m_buffer.end(), 0);   // 每个 track 清一次
        track->render(m_buffer.data(), num_frames / m_engine.channels());
        ...混音...
    }
}
```

同一时刻真正在响的往往只有几十个。为剩下几千个空池付出的代价是：

- `std::fill` 清空混音缓冲：每个 768B 量级，几千个就是 **MB 级**
- 在约 250Hz 的回调频率下 → **每秒几百 MB 的无谓内存写入**
- 全部发生在**实时音频线程**上，且**持着 `audio_player` 的自旋锁**

在锁屏低功耗（CPU 被压到小核）时，这会直接吃满一个回调的时间预算，
把 Oboe 推到 xrun —— 表现就是爆音、断续。

**修复**（`libgdx-oboe`）：

1. `renderable_audio` 新增虚函数 `active()`，默认 `true`（保守）；
2. `soundpool::active()` 返回 `!m_sounds.empty() || !m_pending.empty()`；
   - `m_sounds` 只有音频线程写，而 `audio_player::generate_audio()` 也在音频线程调它 → 无需加锁；
   - `m_pending` 由 UI 线程 push，无锁读最坏是"晚一帧看到"，且 pending 不会被清掉，下一帧照常处理；
3. `generate_audio()` 加一行 `if (!track->active()) continue;`。

`music`（流式播放，用于 BGM 文件）沿用默认的 `true`，行为不变。

---

## 三、列表触摸命中错误

### 现象

- 点第 4 首会选中第 6 首左右（约差两行）
- 列表偏右半边点不动，也拖不起来

### 根因

`handleListTouch()` 取的是**屏幕物理像素**：

```java
int gx = Gdx.input.getX();
int gy = skinH - Gdx.input.getY();   // 只是把 Y 翻成 libGDX 方向
```

而同一帧里画列表、判定上方按钮用的都是另一套坐标：

```java
int gx = main.getInputProcessor().getMouseX();
int gy = main.getInputProcessor().getMouseY();
```

后者的 `mousex/mousey` 在 `KeyBoardInputProcesseor.touchDown/touchDragged/mouseMoved` 里赋值，
并且做了一次必要的变换：

```java
int gameX = bmsPlayerInputProcessor.convertScreenX(x);
int gameY = bmsPlayerInputProcessor.convertScreenY(y);
mousex = gameX;
mousey = resolution.height - gameY;
```

`convertScreenX/Y` 反射调用 `MainController.screenToGameX/Y`：

```java
return Math.round((screenX - viewportX) * (float) lastGameW / viewportW);
```

也就是**等比缩放 + pillarbox/letterbox 居中偏移** —— 因为皮肤分辨率（如 1280x720）
和实际屏幕（如 2400x1080）并不一致，`MainController.render()` 要用等比视口把画面居中放。

`handleListTouch()` 把这两步都跳过了，于是：

**1. 缺缩放 → 纵向偏移随 y 累积**

判定行号用 `row = round((baseRow0Y - gy) / 56)`，行高 56。
屏幕越高、缩放比越大，`gy` 与正确值的差距越大 —— 实测差两行左右，正好是
"点第 4 首落到第 6 首"。列表越往下偏得越多。

**2. 缺居中偏移 → 横向判定范围缩水**

列表在游戏坐标里是 `x ∈ [24, 348]`，命中判定放宽到 `[12, 360]`。
屏幕比 16:9 更宽时左右各有黑边（20:9 的 2400x1080 上每边约 240px），
列表实际画在屏幕 `x ∈ [264, 588]` —— 而判定仍按 `[12, 360]` 比，
**屏幕上 360 往右那一段画着列表却点不到**，拖拽自然也起不来。

### 修复

`handleListTouch()` 改用 `main.getInputProcessor().getMouseX()/getMouseY()`，
和上方按钮、和渲染统一到同一套坐标。

顺带修正 `computeBarIndexAtTouch()` 里行号换算公式的符号：
应为 `row = (baseRow0Y - gy - listDragOffset) / LIST_LINE_H`，原来写成了 `+`。
当前不出错只是因为"按下时 `listDragOffset` 必然已被上一轮归零"，属于不能依赖的巧合。

### 验证

点列表里任意一行应当选中它自己；按住任意一行上下拖，列表应当跟手滚动，
松手后按拖动行数换曲。

---

## 改动文件

**Java（core）**

- `core/src/main/java/bms/player/beatoraja/play/MusicPlayer.java`
  - 新增 `pendingStagefileKey`、`glTextureGarbage`、`retireStagefile()`、
    `drainRetiredTextures()`、`isStillCurrent()`；删除 `stagefileToDispose`
  - `decodeStagefile()` / `applyPendingStagefile()` 加归属校验与快照
  - `loadSingle()` / `transitionToNextInBackground()` 重排：`startBgThread()` 提到
    不发声的收尾之前，并补上 `retireStagefile()`
  - `BGAutoplayThread`：播放循环后重算时间基准
  - `handleListTouch()` 改用 `getInputProcessor().getMouseX()/getMouseY()`；
    `computeBarIndexAtTouch()` 的行号换算公式修正为减号

**native（libgdx-oboe submodule）**

- `library/src/cpp/audio/renderable_audio.hpp` — 新增 `active()`
- `library/src/cpp/audio/soundpool.hpp` / `soundpool.cpp` — 覆写 `active()`
- `library/src/cpp/audio/audio_player.cpp` — `generate_audio()` 跳过空闲音源

改完需要重新编译并替换 `android/libs/libgdx-oboe.aar`（原文件已备份为
`android/libs/libgdx-oboe.aar.bak`）。

---

## 重新编译 native 库

`libgdx-oboe` 是 submodule（指向 `github.com/starxh-1/libgdx-oboe`）。
**它自带的 `gradlew` 在 Windows + Git Bash 下不可用** —— wrapper jar 是 2017 年的老版本，
会直接报 `ClassNotFoundException: org.gradle.wrapper.GradleWrapperMain`。
绕过它，用本地已经下载好的 Gradle 8.14.3：

```bash
GRADLE=$(ls -d ~/.gradle/wrapper/dists/gradle-8.14.3-all/*/gradle-8.14.3/bin/gradle | head -1)

cd libgdx-oboe
cp libgdx-oboe/library/build/outputs/aar/library-release.aar /tmp/ 2>/dev/null   # 可选
"$GRADLE" :library:assembleRelease --console=plain

# 产物替换进主工程
cp library/build/outputs/aar/library-release.aar ../android/libs/libgdx-oboe.aar
```

前置条件：

- `ANDROID_HOME` 指向 SDK（本机为 `E:\Android-SDK`）
- NDK `29.0.13599879` —— 配置在 `libgdx-oboe/buildSrc/src/main/kotlin` 的 `AndroidConfig`
- FFmpeg 预编译库已在 `library/libs/`，**不需要**重建 FFmpeg（重建脚本 `build_ffmpeg.sh` 只在
  换 FFmpeg 版本时才需要）
- 只编 `armeabi-v7a` 和 `arm64-v8a`，见 `library/build.gradle.kts` 的 `abiFilters`

一次完整编译约 1 分钟（LTO 已开、增量编译）。替换后建议跑一次
`:android:assembleDebug` 确认 aar 能被正常打包。

---

## 验证要点

1. **切歌封面**：连续快速按 NEXT 十余次，中央封面应当每次都跟上当前曲目；
   特别试一首**没有封面**的歌接在后面，不应残留上一首的图。
2. **切歌空白**：手动切歌时，空白应当明显变短（尤其是曲库里 tail 记录为空、
   需要现场计算的那些曲子首次播放时）。
3. **密集段落**：找一首鼓点密集的曲子放完，不应再出现"某一段突然断掉"。
4. **logcat**：`MusicPlayer: BGAutoplay starved for Nms` 的出现频率应大幅下降。
   它仍然存在是正常的（那是真的被系统饿过），但不应再和密集段落同步出现。
5. **native 优化**：`AudioCache` 加载完成后，卡顿与爆音应减少；
   如果仍能复现，抓一段 `Atrace`/`perfetto` 看 `onAudioReady` 的耗时。
