# `#PREVIEW` 不生效修复

## 现象

- BMS 里写 `#PREVIEW preview_music.ogg` → 选曲界面**没有预览音**，静默回退到默认 BGM。
- 不写 `#PREVIEW`、只把 `preview_music.ogg` 放进歌曲文件夹 → **正常播放**。

## 根因

`SongData.preview` 这一个字段里混着**两种语义**：

| 来源 | 存的值 | 例子 |
| --- | --- | --- |
| BMS 的 `#PREVIEW` | **相对谱面目录的文件名** | `preview_music.ogg` |
| 扫描目录自动发现的 `preview.*` | **绝对路径** | `/sdcard/beatoraja/songs/foo/preview_music.ogg` |

前半段：`BMSDecoder.PREVIEW` 只是 `model.setPreview(arg.replace('\\','/'))`，原样保存文件名，
不做任何路径解析；`SongData.setBMSModel()` 再 `setPreview(model.getPreview())` 照抄。
后半段：`AndroidSQLiteSongDatabaseAccessor` 扫描目录时发现 `preview*` 文件，取
`child.file().getAbsolutePath()` 存进去。

而播放侧 `PreviewMusicProcessor.start(SongData)` 拿到 `song.getPreview()` 后直接
`new java.io.File(p).exists()` 判定 —— 相对名会按**进程工作目录**（Android 上通常是 `/`）解析，
`exists()` 恒为 false，于是走"回退默认音乐"分支。结果就是"定义了 `#PREVIEW` 反而没声音"。

上游 beatoraja 是在播放侧解析的：

```java
previewPath = Paths.get(song.getPath()).getParent().resolve(song.getPreview()).toString();
```

本项目移植时把这条路径解析**漏掉了**，只保留了存在性校验，于是相对名全被判为无效。

## 修复

### 1. `SongData`（core）新增统一解析入口

```java
public String getPreviewPath()                          // 实例入口，播放侧用
public static String resolvePreviewPath(String songPath, String preview)
```

- 已是绝对路径 → 规范化后原样返回（兼容扫描器写入的旧数据）
- 相对路径 → 按**谱面文件所在目录**补全（`new File(songPath).getParentFile()`）
- 谱面路径未知 → 返回 `null`，由调用方决定回退

用 `java.io.File` 而不是 `java.nio.file.Paths`：Android 上 `Paths` 要 API 26+，且对
含非法字符的 BMS 文件名会抛 `InvalidPathException`；`File` 语义宽松且全版本可用。

### 2. `PreviewMusicProcessor.start(SongData)` 改用绝对路径

判定存在性之前先 `song.getPreviewPath()`。**旧库里已经存成相对名的记录也靠这里兜住，
不需要重新 update song 就能恢复播放。**

### 3. `SongData.setBMSModel()` 入库前归一化

`#PREVIEW` 的相对名在构造 `SongData` 时就解析成绝对路径，让库和播放侧语义一致。
`model.getPath()` 为空（解析不出）时保留原值，不改变既有行为。

### 4. 扫描时兜底（android）

`AndroidSQLiteSongDatabaseAccessor.processBmsFile()`：按最终 `pathName` 再解析一次并校验
存在性 —— `#PREVIEW` 指向的文件确实不存在（文件名写错 / 文件缺失）时，回退到扫描目录
自动发现的 `preview.*`；两者都没有才留空。

## 涉及文件

- `core/src/main/java/bms/player/beatoraja/song/SongData.java`
- `core/src/main/java/bms/player/beatoraja/select/PreviewMusicProcessor.java`
- `android/src/main/java/bms/player/beatoraja/song/AndroidSQLiteSongDatabaseAccessor.java`

## 验证

1. 谱面写 `#PREVIEW preview_music.ogg`，文件放在同一目录 → 选曲停留约 0.4s 后应播放该 ogg。
2. 不重扫曲库直接验证第 1 条（验证运行时解析对存量相对名记录生效）。
3. `#PREVIEW` 写不存在的文件名 + 目录里有 `preview.*` → 回退到 `preview.*`。
4. `#PREVIEW` 写不存在的文件名 + 目录里没有 `preview.*` → 回退默认 BGM，不报错。
5. 已入库的绝对路径记录不受影响，仍正常播放。

logcat 关注 `PreviewMusicProcessor` 的 `Starting preview: <绝对路径>`；若出现
`Preview file does not exist` 说明路径仍有问题，需看打印出来的是相对名还是绝对路径。
