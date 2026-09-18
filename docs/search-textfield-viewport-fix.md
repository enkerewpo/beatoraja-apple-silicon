# 搜索框（SearchTextField）在皮肤拉伸全屏后纵向错位 — 修复

日期：2026-09-18
涉及：`core/.../select/SearchTextField.java`、`core/.../select/MusicSelector.java`（调用点未改）

## 现象

LR2 皮肤开启「拉伸至全屏」（`config.stretchFullscreen`）后，原生搜索框
（search song）与皮肤里画出的搜索框位置对不上，纵向明显错位。

## 根因

`SearchTextField extends Stage`，其视口原先固定为
`new FitViewport(config.getResolution())`（默认 HD 1280×720），而：

- 搜索框区域 `MusicSelectSkin.getSearchTextRegion()` 的坐标是
  **皮肤空间**——`LR2SkinCSVLoader` 用 `dstw/dsth` 把 LR2 模板坐标换算到皮肤分辨率；
- 主渲染的投影是 `setToOrtho2D(0, 0, skin.getWidth(), skin.getHeight())`，
  拉伸模式下把皮肤空间**线性铺满整个 surface**（`glViewport(0,0,screenW,screenH)`），
  等比模式下铺进按**皮肤宽高比**算出的 pillarbox/letterbox 矩形。

于是当皮肤分辨率 ≠ config.resolution（如 LR2 皮肤声明 FULLHD/SD，而 config 仍是
HD）时，stage 把皮肤空间的 y 当成了 720 空间的 y → 纵向错位（横向在宽高比不一致
时同样错）。

## 修复

`SearchTextField.createViewport()`：视口世界尺寸改用**皮肤宽高**，并按渲染模式选类型：

- `stretchFullscreen = true` → `StretchViewport(skinW, skinH)`
  （与主渲染"线性铺满"完全一致）；
- 否则 → `FitViewport(skinW, skinH)`
  （与 `MainController.render()` 的 pillarbox/letterbox 计算等价）。

`MainController.render()` 每帧已调用
`stage.getViewport().setScreenBounds(viewportX, viewportY, viewportW, viewportH)`，
所以两种模式下的屏幕矩形都与主渲染一致，不需要额外改动。

同时把用于"点击框外收起键盘"判定的 `screen` Group 边界从 `resolution` 改为
视口世界尺寸（即皮肤空间），保证命中判定同坐标系。

皮肤未声明尺寸（≤0）时回退到 `config.getResolution()`。

## 验证

- `:core:compileJava`、`:android:assembleDebug` 通过。
- 真机：LR2 皮肤 + 拉伸全屏，search song 应与皮肤搜索框重合；关闭拉伸后同样重合。
