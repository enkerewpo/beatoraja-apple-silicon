# 练习模式参数面板：分层与触摸版皮肤适配

涉及文件：
- `core/src/main/java/bms/player/beatoraja/play/SkinBGA.java`
- `core/src/main/java/bms/player/beatoraja/play/PracticeConfiguration.java`
- `core/src/main/java/bms/player/beatoraja/play/BMSPlayer.java`
- `core/src/main/java/bms/player/beatoraja/MainController.java`、`skin/Skin.java`（叠加层通路）

## 一、面板是什么

练习模式（`BMSPlayerMode.Mode.PRACTICE`）下，上游把 BGA 层的内容**替换**成练习参数面板：
`SkinBGA.draw()` → `PracticeConfiguration.draw(region, ...)`，即
`START TIME / END TIME / GAUGE TYPE / ... / OPTION-1P` 等参数行 + 右下 6 行判定计数 + 底部 note 分布图。
面板坐标全部相对 **BGA 层的 region**（`x + w/8`、`y + 7h/8`）。

## 二、为什么触摸版皮肤看不到

`GenericTheme for Touchscreen` 的 BGA 区域是**整屏**（portrait/landscape 两个分支都是
`0,0,header.w,header.h`），而它的轨道背景是一整块**不透明**矩形：

```lua
-- lane（play.lua）：a = 255 + offset.lane.a，默认 offset.lane.a = 0 → 不透明
{id = -111, offset = 3, dst = {merge_all({x=geo.lane.x, ..., a = 255 + offset.lane.a}, color)}}
```

轨道层在 BGA 层之后绘制（1446 → 1925），竖屏时轨道铺满全屏 →
**画在 BGA 层的面板被整块盖住**，表现为"练习模式看不到参数调整"。
（普通皮肤 BGA 在侧边条带内，轨道不会盖住它，所以一直正常。）

另外 `dst` 上挂着 `op = {41}`（OPTION_BGAON），而 `BMSResource` 只在
`Config.BGA_ON` 或（`BGA_AUTO` 且 AUTOPLAY/REPLAY）时把 `bgaon` 置真 ——
`BGA_AUTO` 下普通游玩和练习都会是假，面板同样整块消失。

## 三、现在的做法

### 1. 触摸版皮肤：叠加到最上层 + 半透明 + 不画密度图

`PracticeConfiguration#isOverlayOnTop(skin)` 判定是否为触摸版皮肤
（`skin.header.getName()` 含 `touchscreen`），为真时：

| 项 | 触摸版 | 其他皮肤 |
| --- | --- | --- |
| 绘制位置 | 皮肤画完后叠加（`MainController` → `BMSPlayer#drawPracticeOverlay`） | `SkinBGA` 的 BGA 层（在 lane/notes 之下，上游行为） |
| 文字透明度 | 30% | 100% |
| note 分布图 | 不绘制 | 绘制 |
| 文字旋转 | 竖屏（Layout 选项 = Portrait）时 270° | 0° |

叠加通路复用皮肤自己的 `SkinObjectRenderer`（新增 `Skin#getObjectRenderer()`），
保证视口/变换矩阵/纹理过滤与皮肤一致。区域取整个皮肤范围 ——
触摸版的 BGA 层本来也铺满全屏，所以位置与"画在 BGA 层"时一致。

**叠加层门控**（`BMSPlayer#drawPracticeOverlay`）：`STATE_FAILED` / `STATE_FINISHED` /
`STATE_PRACTICE_FINISHED`（收尾动画、淡出）期间不绘制 —— 面板在皮肤之上，
否则会盖住 stage failed / stage clear 动画。其他皮肤的面板在 BGA 层，
天然被收尾层遮住，所以只有叠加层需要这条门控。

`SkinBGA` 侧对触摸皮肤跳过面板绘制（避免画两遍），并保留：
- 练习模式下强制 `draw = true`：`BGA_AUTO` 时 `bgaon` 为假也不让面板消失；
- `prepareBGA` 加 `getBGAManager() != null` 保护（BGA 关闭时该值可能为 null）。

### 2. 竖屏旋转 270°

判定：皮肤自定义选项 `Layout` 的 `Portrait` 项被选中
（按选项名/项名匹配，不用硬编码 op 数值）。

旋转后坐标映射（`PracticeConfiguration#drawText`）：参数 `(u, v)` = 未旋转时的
「阅读方向 / 换行方向」偏移，按同一角度旋转：

```
阅读方向 (1,0) → (cosθ, sinθ)
换行方向 (0,-1) → (sinθ, -cosθ)
```

θ = 270° 时：阅读方向 = 皮肤 -y，换行方向 = 皮肤 -x。

**竖屏轴向约定**（真机验证过）：皮肤坐标 **x = 设备水平方向**
（note 沿 x 水平下落，判定线 x=226 在左端），**y = 设备垂直方向**。
所以：
- 文字块沿 x 排列 → 也就是在设备上左右移动；
- 每行的阅读方向沿 y → 设备上的上下方向，配合 270° 字形旋转后正好正立。

据此锚点直接放在**右边缘**：`x = r.x + r.width - 40`，文字块向左展开
（13 行 × 22px ≈ 286px），整体落在屏幕右下区域。

> 注意：不要用 `skin/GenericTheme/play/touchscreen_play/play.lua` 里
> "portrait: x=vertical, y=horizontal" 那句注释来推断轴向 —— 实际是反的，
> 以 `docs/portrait-issue-analysis-round2.md`（note 沿 X 轴下落）和真机表现为准。

## 四、当前状态与可调项

- 竖屏文字方向、位置已确认：锚点在右边缘（`r.x + r.width - 40`，文字块向左展开），
  透明度 30%。
- 若想上下挪：改锚点 y（`r.y + r.height * 7 / 8`）—— 竖屏下 y 才是设备的上下方向。
- 若想让面板"在轨道之下"（不再是叠加层）：需要给皮肤一个可在图层顺序中摆放的
  面板对象（例如新增一个 play 皮肤专用的 dst id / 皮肤字段，在 `play.lua` 里放到
  "轨道背景之后、note 之前"），那时面板天然在 note 之下、也被收尾动画盖住。
  当前叠加层方案是折中：保证触摸皮肤竖屏可见（原因见第二节）。
