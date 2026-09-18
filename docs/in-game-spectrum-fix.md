# In-Game Spectrum：坐标映射修复 + 浮動菜单调整页重构

日期：2026-09-18
涉及：`SideSpectrumRenderer`、`BeatorajaGame`、`InGameSpectrumConfig`（新增）、
`FloatingMenu`、`MainController`、`android/assets/skin/GenericTheme/play/spectrumconfig.json`

## 一、坐标映射（渲染侧）

### 坐标约定（定论，勿再猜）

- 频谱配置（`spectrumconfig.json` / PlayerConfig 的 spectrumOffset*）单位是
  **皮肤坐标**，x 从左、**y 从底部起算**。
  依据：JSON/Lua 皮肤对象走 `Skin.setDestination`，其中 dst 只做 `y * dh`，**不翻转**；
  LR2 皮肤的 y 翻转在 LR2 loader 里已经做完。
  （GenericTheme 的 `geo.lane.y = 226` 与截图底部 UI 高度吻合，可交叉验证。）
- 因此**不要**再对 y 做 `skinH - y - h` 的翻转——那是针对 LR2 CSV 源坐标的写法。

### 屏幕映射

```
scaleX = viewportW / skinW ; scaleY = viewportH / skinH
specScreenX      = viewportX + x * scaleX
specScreenYbottom= viewportY + y * scaleY      // y 已是"从底部"
specScreenW/H    = w/h * scaleX/scaleY
```

`viewportX/Y/W/H` 与 `skinW/H` 由 `BeatorajaGame.configureSpectrumRenderer()`
每帧从 `MainController` 与当前皮肤同步（拉伸全屏 / 等比黑边两种模式都覆盖）。

### 修掉的两个旧 bug

1. 相机投影只在**屏幕分辨率变化**时重建（`needsMatrixUpdate`），进入游戏内区域渲染时
   viewport 已变成小矩形而 camera 仍是全屏 ortho → 内容只剩 13%×7% 可见（"极小一块"）。
   现在改为 `applyCamera()`：按正交尺寸变化重建，切模式也不会残留。
2. 坐标空间写死 1920×1080，皮肤不是该分辨率时整块错位。现在用皮肤 header 的 w/h。

## 二、浮動菜单「In-Game Spectrum」调整页（重构）

### 旧实现的问题

12 个频谱按钮（X/Y/W/H 各 值/[-]/[+]）被塞进通用分页列表，而分页用的是
**可见项索引**、`SPECTRUM_START` 却是**数组索引**：两套索引空间混用 →
布局错位（列不对）、`refreshSpectrumLabels()` 还用错了基址（`13 + i*3`，应为 `15 + i*3`），
把别的按钮标签覆盖成 `X: 0`，于是出现"按 Y 的 ± 把 X 的 + 弄没"。
另外菜单只读 PlayerConfig（默认全 0），所以界面永远显示 0，看到的不是生效值。

### 新实现

- **独立模态页**：`spectrumAdjustOpen` + `drawSpectrumPage()` + `hitTestSpectrumPage()`，
  3 列（值 / [-] / [+]）× 4 行（X/Y/W/H），标题栏含返回按钮；
  **绘制与命中判定共用 `spectrumCellRect()` 同一份矩形计算**，结构上杜绝错位。
- **入口**：菜单里一个 `In-Game Spectrum` 按钮（keycode -135）→ `enterSpectrumAdjust()`。
- **显示生效值**：`InGameSpectrumConfig.resolve()` = PlayerConfig（非 0）> 皮肤 json > 内置默认；
  进入页面时把生效值**物化**进 PlayerConfig（避免 0 被当作"未设置"而回退 json 的歧义）。
- **实时生效**：每次调整 `InGameSpectrumConfig.apply()` 写入 PlayerConfig 并调用
  `BeatorajaGame.updateSpectrumConfig()`；抬手/退出时 `save()` 落盘一次（长按期间不写文件）。
- **长按连发**：按住 300ms 后每 60ms 一步，每 400ms 步长翻倍（上限 64×）；X/Y 步长 1，W/H 步长 10。
- **仅 PLAY 界面可用**：入口按钮只在 PLAY 界面的浮動菜单里出现——
  `isItemVisible()` 里加了独立硬规则
  `item.keycode == SPECTRUM_ENTRY_KEYCODE && !isPlayMode → false`
  （原有的四条判定是"某模式生效时要求对应标记"，未设置任何模式的状态如 RESULT 会全部放行，
  所以"仅 PLAY"不能靠 `showOnSelect/showOnPlay` 表达）。
  离开 PLAY（`setPlayMode(false)`，MainController 只在状态切换时调用一次）会强制
  `exitSpectrumAdjust()` 收起页面并落盘，避免模态页残留在结果等界面。
  频谱渲染同样收回到只在 PLAY 显示（`BeatorajaGame.render()` 的 `state instanceof BMSPlayer`）。
- 边界值裁剪（位置 ±4000、尺寸 1..4000），避免长按跑飞。

## 三、默认位置

`GenericTheme/play/spectrumconfig.json` 与 `InGameSpectrumConfig.DEFAULT_*` 统一为
`{x:445, y:7, w:220, h:50}` —— 左下角、note 密度图右侧（真机上调好并记下来的值）。

> 注：定标实验期间设备上那份 json 被临时改成了 `{0,0,960,540}`，已用 adb 写回上面同一份值。
> 皮肤目录的覆盖只发生在 versionCode 变化时，所以同版本重装不会自动刷新该文件。

## 四、验证

- `:core:compileJava`、`:android:assembleDebug` 通过。
- 真机：PLAY 中频谱出现在左下角 note 密度图右侧（皮肤坐标 445,7 尺寸 220×50）；
  浮動菜单 → In-Game Spectrum：4 行数值为真实生效值，点 ± 立即移动，长按连续移动，
  返回/点面板外退出；退出后数值持久化（重进仍在）。
- 入口可见性：选曲 / KeyConfig / 皮肤选择 / 结果等界面均**不应**出现
  `In-Game Spectrum`（仅 PLAY 可见）。注意 PLAY 里浮動菜单本身受
  `Config.showFloatingMenuInPlay` 开关控制，需先在设置里打开才能看到入口。
