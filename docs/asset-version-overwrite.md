# 内置皮肤随版本更新覆盖安装（skin overwrite on version update）

日期：2026-09-17
涉及：`android/src/main/java/com/starxh/beatoraja/android/AndroidLauncher.java`

## 背景

原逻辑中，APK assets 落盘到 `filesDir` 的资源一律"补缺"语义
（`copyAssetFolder` 内部 `if (!destSub.exists())` 跳过已存在文件）。
后果：用户覆盖安装新版本 APK 后，`filesDir/skin/` 里的内置皮肤仍是旧版——
"更新了版本但没更新皮肤"（如 GenericTheme 的代码文件小修改不会生效）。

## 方案

- `onCreate()` 主线程在 `createDefaultDirectories()` 与 ZipExtractor 线程启动前，
  调用 `detectAndRecordVersionUpdate()`：
  - 读取当前 APK `versionCode`（API >= 28 用 `getLongVersionCode()`，否则 `versionCode`）；
  - 与 SharedPreferences `asset_version / installed_version_code` 中记录的上次值比较；
  - 不同（含首次安装无记录）→ 返回 true 并立即写入新值；相同 → false。
- 结果存入字段 `volatile boolean forceAssetsOverwrite`，本次启动内生效。

## 覆盖范围（forceAssetsOverwrite = true 时）

| assets 来源 | 落盘目标 | 行为 |
|---|---|---|
| `skin/` | `filesDir/skin/` | **版本更新时覆盖**，平时补缺 |
| `sound/default/` | `filesDir/sound/default/` | 始终补缺（允许用户定制，不覆盖） |
| `inochi_ogg/` | 默认歌曲目录 | 仅首次拷贝（允许用户整理，不覆盖） |
| `font/VL-Gothic-Regular.ttf` | `filesDir/font/` | 仅首次拷贝 |

不落盘、无需处理的 assets：`walkure/`（WebView 经 `getAssets().open()` 直读）、
`glsl/`、`folder/`、`random/` 等（`Gdx.files.internal` 直读），天然随 APK 更新。
bgm 不在 assets 中（用户外部导入），不涉及。

## 语义边界（重要）

- 覆盖是**按路径覆盖**：只覆盖 assets/skin 里存在的同名文件。用户自行导入的皮肤
  （`Download/beatoraja/skins/*.zip` 导入到 `filesDir/skin/<name>`）因不在
  assets 中，不会被删除或覆盖。
- 用户**手动修改过**的内置皮肤文件（如改过 `skin/default`、`skin/GenericTheme`
  下的图或代码）会在版本更新后被 APK 内的版本覆盖——这是本特性的预期行为。
- assets/skin 中已删除的旧文件不会从 `filesDir/skin` 中清除（纯覆盖，不做镜像同步）。
- 单文件拷贝失败（源缺失、IO 错误）仅记 warning，不中断其余文件。

## 验证

- `:android:compileDebugJavaWithJavac`、`:android:assembleDebug` 通过；
  APK `classes4.dex` 含新日志串 `overwrite-extracting built-in skins`。
- 机制自检方法：修改 `android/build.gradle` 的 `versionCode` 后装机启动，
  logcat 应出现
  `Version change detected: previous=... current=... -> overwrite-extracting built-in skins`。
