# 轨道分割线造成的触摸真空区（横竖屏通用）

日期：2026-09-20
涉及文件：
- `core/src/main/java/bms/player/beatoraja/play/PlayTouchKeyMapper.java`
- `android/assets/skin/GenericTheme/play/touchscreen_play/play.lua`（几何来源 + bomb 尺寸）

## 一、现象

`GenericTheme for Touchscreen` 皮肤下，相邻轨道之间那条"分割线"上触摸没有反应 ——
手指顺着轨道横滑/竖滑时会在每条线处"卡一下"，手感发虚。用户给的定位：分割线把轨道
分开了，真空区应按 50/50 分给左右两条轨道。

## 二、根因（两段，缺一不可）

### 2.1 皮肤：分割线画在轨道矩形**之外**

竖屏（`play.lua` 405–450）：

```lua
local sep_w = 5                                  -- 竖屏分割线宽（2026-09-20 由 6 改为 5）
local total_lane_w = 1080 - (num_seps * sep_w)   -- 轨道区总长扣掉所有分割线
for i = 1, #geo.lane.order do
    local y_offset = (i - 1) * sep_w
    geo.lane.each_y[lane_idx] = current_lane_y + y_offset
    geo.lane.each_w[lane_idx] = next_lane_y - current_lane_y
end
```

→ `each_y[i+1] - (each_y[i] + each_w[i]) == sep_w`：**相邻轨道矩形之间正好留 sep_w 的空隙**
（当前值 5px）。而分割线自己画的正是这条空隙（1951 行）：

```lua
{x = geo.lane.x, y = geo.lane.each_y[order[i]] - geo.lane.separateline_w,
 w = geo.lane.w, h = geo.lane.separateline_w, ...}
```

横屏同理，`geo.lane.separateline_w = 3`，`each_x[i] = each_x[i-1] + each_w[i-1] + 3`（627 行），
分割线在 1955 行画在这条 3px 空隙里。

而触摸区域直接用的是 note 目的矩形（941 / 1005 / 1031 行）：

```lua
-- 竖屏：占据 [each_y[i], each_y[i] + each_w[i]]，不含空隙
d[i] = {x = geo.lane.x - 40, y = geo.lane.each_y[i], w = geo.lane.w + 40, h = geo.lane.each_w[i]}
-- 横屏：占据 [each_x[i], each_x[i] + each_w[i]]，不含空隙
d[i] = {x = geo.lane.each_x[i], y = geo.lane.y - 32, w = geo.lane.each_w[i], h = geo.lane.h}
```

**所以 5px（竖屏，改动前是 6px）/ 3px（横屏）的空隙在触摸上是真空区。**

### 2.2 代码：扩展逻辑被 `!isPortrait` 挡住，且方向也不对

旧 `PlayTouchKeyMapper.updateRegionsFromLanes()`：

```java
float touchExtension = isPortrait ? logicH * 0.05f : logicH * 0.15f;
...
float extendUp = 0, extendDown = 0;
if (!isPortrait) {                 // ← 竖屏完全不扩展
    extendUp = extendDown = touchExtension;
    if (i > 0) { ... gap = stageY - prevBottom; if (gap > MIN_GAP_FOR_MIDLINE) extendUp = min(touchExtension, gap/2f); }
    ...
}
keyButtons[i].updateBounds(finalX, extendedY, r.width, extendedHeight);   // ← 宽度从不扩展
```

三个问题：

1. **竖屏被 `!isPortrait` 整段跳过** → 竖屏扩展量为 0，5px 空隙全额暴露（用户遇到的正是这个）。
2. **只沿 Y 扩、从不改宽度** → 横屏时空隙在 **X** 上，而代码扩的是 Y（横屏所有轨道的 Y 范围
   相同，这个扩展只是把触摸区上下撑到屏幕边），3px 空隙同样暴露。所以**横竖屏都不通**。
3. `MIN_GAP_FOR_MIDLINE = 10f` 的判据是"空隙 <10px 就不限制扩展，直接用 15% 高度" ——
   竖屏空隙只有 5px（改动前 6px，仍小于那个 10px 阈值），若只是简单去掉 `!isPortrait`，
   每条轨道会向上/下各吃 54px，
   相邻轨道大面积重叠 → 触摸会被靠前下标的那条抢走。**所以不能只删守卫。**

## 三、修法

改 `updateRegionsFromLanes()`，思路是"轨道在哪根轴上铺开，就沿哪根轴把分割线空隙 50/50 分掉"：

1. 先算出每条轨道本帧在 Stage 坐标系下的矩形（含 LIFT / LaneCover 等皮肤偏移，行为与旧版一致）。
2. **判定堆叠轴**：比较全部轨道中心在 X / Y 上的铺开跨度，跨度大的那根轴就是堆叠轴。
   - 竖屏：X 跨度 = 0、Y 跨度 ≈ 1080 → 堆叠轴 = Y
   - 横屏：X 跨度 ≈ 轨道区宽、Y 跨度 = 0 → 堆叠轴 = X
3. **沿堆叠轴排序**找真邻居：皮肤的可视次序与 lane 下标并不一致
   （7K 竖屏 `order = {7,6,5,4,3,2,1,8}`、横屏 `order = {8,1,2,3,4,5,6,7}`），
   旧的 `lanes[i-1]` 邻居假设本身就是错的。8 个元素插入排序，零分配。
4. **空隙 50/50 对分**：`gapHalf(a,b) = min(gap/2, min(两轨厚度))`，
   两轨已相接/重叠时返回 0；最外侧没有邻居，沿用旧的宽松扩展（竖屏 5% / 横屏 15% 逻辑高度）。
5. 最后统一裁剪到 `[0,logicW] × [0,logicH]`。

顺带两处清理（同类改动内的顺手修，无行为影响）：

- 删掉**恒被局部变量遮蔽的死字段** `private boolean isPortrait`（以及同处未使用的 `OP_PORTRAIT`）——
  这是很容易再次踩坑的命名陷阱。
- `SkinNote` 引用改为在 `updateRegionsFromSkin()` 里缓存（皮肤在一局内不变）：
  旧代码在 `render()` 里**每帧** `skin.getAllSkinObjects()` 全表扫描 + 产生迭代器分配，
  属于无谓 GC 压力。保留 `skinForNoteCache != skin` 的兜底重查。

### 关键性质

- 扩展量恒被空隙限制，**相邻触摸区只在空隙中线处相接，不可能重叠** → 不会双键同触发。
- 空隙两侧各拿一半，完全覆盖 → **真空区消失**（用户要求的"五五对分"）。
- 非堆叠轴（轨道长度方向）保持旧的宽松扩展，横屏手感不变。

## 四、验证

1. **类型检查**：`javac`（JDK 21，core classpath）单文件 → **exit=0**。
2. **数值仿真**（用皮肤真实参数：white 60 / black 48 / scratch 64，竖屏 sep 6、横屏 sep 3）：

   | 场景 | 原空隙 | 扩展后相邻状态 | 任意两轨重叠 | 堆叠轴覆盖 |
   | --- | --- | --- | --- | --- |
   | 竖屏 7K（stackAlongY=true） | 5.0px（改前 6.0px） | 中线严丝合缝 | 0 对 | [0, 1080) 全覆盖 |
   | 横屏 7K（stackAlongY=false） | 3.0px | 中线严丝合缝 | 0 对 | [0, 1920) 全覆盖 |

   触摸区分到的量 = 空隙的一半（竖屏 2.5px/侧、横屏 1.5px/侧），相邻触摸区边界在空隙**中线**重合。

3. **未做**：真机验证。
4. **竖屏分隔线间隙 6 → 5 后的复算**（2026-09-20 应用户要求把 `sep_w` 由 6 改为 5）：

   | 模式 | 间隙 | 轨道厚度（按视觉顺序） | 覆盖末端 |
   | --- | --- | --- | --- |
   | 7K | 5.0px | 139 / 112 / 140 / 112 / 140 / 112 / 140 / 150 | 1080 |
   | 5K | 5.0px | 179 / 180 / 180 / 144 / 180 / 192 | 1080 |
   | 9K | 5.0px | 130 / 121 / 98 / 122 / 97 / 122 / 98 / 122 / 130 | 1080 |

   少的 1px 由轨道厚度吸收（每条约 +1px），覆盖末端仍精确到 1080。
   `sep_w` 是**唯一定义点**（`initPortraitGeo` 内 427 行）：`each_y` 偏移、`each_w` 分配、
   `total_lane_w`、1951 行分割线绘制全部派生自它；note 的 lane 区域（943 行
   `h = geo.lane.each_w[i]`）与触摸区（`PlayTouchKeyMapper.gapHalf` 按几何推导，无硬编码）
   都自动跟随。横屏走另一条分支、仍是写死的 `geo.lane.separateline_w = 3`（556 行），未受影响。
   lua 语法校验通过（luaj `loadfile`）。

## 五、竖屏 bomb 尺寸：接入 "Bomb size offset" 皮肤选项（默认 2.5）

`play.lua` 的 bomb 段落横竖屏用两套尺寸：

- **横屏**：`size_w = geo.lane.each_w[numLanes] * 2 + offset.bomb.w` —— 最宽轨道宽 × 2 加皮肤偏移，
  全体轨道共用同一尺寸（原有行为，未改动）。
- **竖屏（改动后）**：按**每条轨道自己的厚度**算，并叠加**同一个**皮肤选项：

```lua
if isPortraitLayout() then
    size_h_final = geo.lane.each_w[i] * 2.5 + offset.bomb.w
    size_w_final = size_h_final
end
```

`offset.bomb.w` = 皮肤自定义偏移 "Bomb size offset"（`play.lua` 的 `offset_source` 里 id 44、
只有 `w` 分量），来自游戏内皮肤设置的 `SkinConfiguration.updateCustomOffsets()`，
自定义项取值范围 −9999..9999，所以竖屏现在和横屏一样可以自己调 bomb 大小。
`offset = 0`（默认）时 = **2.5 × 轨道厚度**。

变化史：竖屏原本是硬编码 `× 2.0` 且**不吃**该选项 → 2026-09-20 先按要求调到 `× 2.4`，
随后按要求改为「接入该选项 + 默认 2.5」。

- `explosion.lua` 里的 600×600 只决定源帧，不参与竖屏尺寸计算。
- fast/slow bomb 分支（`divx == 16`）与被 `isFastSlowBomb` 判定的其余分支共用同一对
  `size_w_final / size_h_final`，一并生效。
- 若用户此前在横屏调过 "Bomb size offset"，竖屏会继承同一数值 —— 因为横竖屏读的是同一个
  皮肤配置项，这是"与横屏一致"的必然结果。
- 改动未加下限保护（与横屏同构）：偏移调到极小理论上会让边长趋近 0，与横屏行为一致。

## 六、部署注意（皮肤改动要生效）

内置皮肤是"APK assets → `filesDir/skin/`"落盘的，**只在 `versionCode` 变化时才覆盖**
（见 `docs/asset-version-overwrite.md`）。`android/build.gradle` 的 `versionCode` 已于 2026-09-20
由 **14 提升到 15**（正是为了让本轮的 play.lua 改动生效），因此：

1. 直接装机即可，启动日志会出现 `overwrite-extracting built-in skins`；
2. 若仍不生效，可卸载重装，或手动删掉设备上 `filesDir/skin/GenericTheme` 目录再启动。

Java 侧改动（触摸区域）不受此限制，跟着 APK 走。
