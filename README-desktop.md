<div align="center">
  <img src="desktop/packaging/icon.png" width="128" alt="beatoraja desktop">
  <h1>beatoraja — native Apple Silicon</h1>
  <p>为 beatoraja 补上 LWJGL3 桌面后端，使其在 Apple Silicon 上原生运行，不经 Rosetta。</p>
</div>

---

## 这是什么

[beatoraja](https://github.com/exch-bms2/beatoraja) 是目前最主流的 BMS 播放器，但它基于 libGDX 1.9.9 + **LWJGL 2**，而 LWJGL 2 自 2016 年起停止维护，**从来没有也永远不会支持 arm64 macOS**。在 Apple Silicon 上它只能通过 Rosetta 以 x86_64 运行，代价是：

- **崩溃**：`SIGSEGV` 发生在 `AppleMetalOpenGLRenderer` 的纹理路径（`glClear → gldClearFramebufferData → GLDTextureRec::getTextureResource`），由 BGA 视频解码触发，点 Play 即闪退。
- **掉音与卡顿**：1572 个 Vorbis 切片在 Rosetta 下解码需 2~3 分钟，而曲子本身只有 124 秒——键音还没解出来歌就放完了。对应上游 [issue #851](https://github.com/exch-bms2/beatoraja/issues/851)，至今未解。

本仓库补上桌面后端，让它原生跑在 arm64 上，上述问题从根源消失。

## 归属：站在谁的肩上

本仓库是三层工作的叠加，全部 GPL-3.0：

| 层 | 来源 | 内容 |
|---|---|---|
| 原作 | [exch-bms2/beatoraja](https://github.com/exch-bms2/beatoraja) | BMS 播放器本体 |
| **关键迁移** | [starxh-1/beatoraja-Android](https://github.com/starxh-1/beatoraja-Android) | **把 core 迁移到 libGDX 1.14 并改造为后端无关** |
| 本仓库 | — | `desktop/` 模块（LWJGL3 后端）+ 若干 core 修正 |

**必须强调：最难的部分不是本仓库做的。** 从上游出发移植需要独自跨越 7 年的 libGDX API 漂移——Application 类、配置类、输入层从 `org.lwjgl.input.Keyboard` 换到 GLFW、gdx-controllers 的大版本断裂。`starxh-1` 为了让 beatoraja 跑在 Android 上已经完成了这段迁移，本仓库只是给那份后端无关的 core 补上了桌面端后端。没有这份前置工作，本仓库不可能存在。

## Apple 平台兼容性

| 项 | 状态 |
|---|---|
| Apple Silicon (arm64) 原生 | ✅ 无 Rosetta，`os.arch=aarch64` |
| macOS 26 / 27 | ✅ 实测 macOS 27，M3 |
| Intel Mac (x86_64) | ⚠️ 未测试，理论可行（libGDX 1.14 同时提供两种原生库） |
| Retina / HiDPI | ✅ 已处理帧缓冲与逻辑像素的差异 |
| BGA 视频 | ✅ 通过 `gdx-video-lwjgl3` |
| 游戏手柄 | ⚠️ 未测试 |

## 性能实测

原生 arm64 + libGDX 1.14 + LWJGL3 OpenAL，测试曲目 Katakoi Echo（1572 个 ogg 切片，曲长 124 秒）：

```
解码加载   1572 个 → 1396 ms（0.9 ms/个），0 个失败
并发发音   1920 次触发 → 0 个被拒绝
实际游玩   1102 个键音载入 316 ms
```

加载耗时相对曲长可忽略，Rosetta 下的掉音在原生环境不再可能发生。

复现：`./gradlew :desktop:audioBench -Poraja.songdir=<含 .ogg 的谱面目录>`

## 运行

```bash
./gradlew :desktop:run
```

默认在 `~/Games/beatoraja0.8.8-modernchic` 下运行，可在 `desktop/build.gradle` 中修改 `workingDir`。

可选参数：`-Doraja.fps=120` 覆盖默认 60 帧上限。

### 数据库兼容性

**本仓库的 `SongUtils.crc32` 与上游 beatoraja 0.8.8 的 `songdata.db` 不兼容**——同一目录算出的 CRC 不同，直接共用会导致目录层级对不上、曲目全部消失。

桌面端读取 `songdata-desktop.db`，不存在则回退到 `songdata.db`，原库保持不动，两者可共存。迁移方式是按本仓库的算法重算 `folder.parent`、`song.folder`、`song.parent`。

## 本仓库改了什么

### 新增 `desktop/` 模块

- `DesktopLauncher` — LWJGL3 入口
- `JdbcSongDatabaseAccessor` — JDBC 曲库读取
- `StubScoreDatabaseAccessor` — 成绩库桩件（未实现）
- `AudioBenchmark` — 音频验证工具

### core 修正

1. **`SkinLoader.normalizePath()`** — 原实现用「输入是否以 `/` 开头」决定输出是否加前导斜杠，但中途已用 `getCanonicalPath()` 转成绝对路径，导致相对路径进去、少一个斜杠的绝对路径出来，再按 CWD 解析造成根目录翻倍、纹理全部加载失败（**黑屏**）。
2. **`SkinLoader.getPath()`** — 把「绝对路径」等同于以 `/storage/` 或 `/Android/` 开头，macOS 的 `/Users` 会被剥掉斜杠。改为仅 Android 生效。
3. **`MainController` 视口** — `glViewport` 单位是帧缓冲像素，Retina 上为逻辑尺寸的 2 倍，用 `getWidth()` 会让画面只占左下四分之一。

### 启动器关键设置

- **不要在带 JavaFX 的路径上加 `-XstartOnFirstThread`**（会死锁），但纯 LWJGL3 的桌面端**必须**加（GLFW 要求在进程首个线程运行）。
- **OpenAL 并发声部数必须显式放开**：libGDX 默认仅 16 个，高密度谱面必然丢键音。beatoraja 自己的 `audio.deviceSimultaneousSources` 只作用于上层逻辑，硬上限在 `Lwjgl3ApplicationConfiguration.setAudioConfig()`。
- **不要请求 GL core profile**：libGDX 与 beatoraja 的着色器都是 GLSL 1.20 语法（`varying`/`attribute`），core profile 下非法，`SpriteBatch` 直接编译失败。

## TODO

- [ ] **成绩数据库** — 目前是桩件，成绩不保存
- [ ] **曲库扫描** — 未实现，依赖上游 beatoraja 建好数据库后迁移
- [ ] **原生 .app 打包** — 用 jpackage 生成内嵌 JRE 的 `.app`，双击即用
- [ ] **启动配置窗口** — 上游的 JavaFX 配置界面尚未适配
- [ ] **皮肤切换** — 需要在游戏内验证
- [ ] **帧率优化** — 60Hz 屏上 vsync 未真正生效，目前靠显式限帧；游玩时帧率待测
- [ ] **Intel Mac 验证**
- [ ] **手柄支持验证**

## 许可

GPL-3.0，与上游及 Android 分支一致。

---

<sub>本仓库的移植工作由 Claude（Opus 5 模型）完成。</sub>
