# 浮动菜单按钮的可见性与顺序规则

涉及文件：`core/src/main/java/bms/player/beatoraja/FloatingMenu.java`

浮动菜单的按钮定义在 `items[]` 数组里，**数组顺序就是所有界面的显示顺序**。
界面差异通过两个维度表达：

## 一、可见性（`MenuItem` 的四个开关）

```java
new MenuItem(label, keycode, isToggle,
             showOnSelect, showOnKeyConfig, showOnPlay, showOnSkinSelect)
```

`isItemVisible(item)` 的判定：

| 条件 | 结果 |
| --- | --- |
| `selectMode && !showOnSelect` | 隐藏 |
| `keyConfigMode && !showOnKeyConfig` | 隐藏 |
| `skinSelectMode && !showOnSkinSelect` | 隐藏 |
| `isPlayMode && !showOnPlay` | 隐藏 |
| 其它（未设置任何模式的状态，如 RESULT） | **全部放行** |

模式标记由 `MainController.changeState()` 在**状态切换时**设置一次
（`setSelectMode/setKeyConfigMode/setSkinSelectMode/setPlayMode`）。

> ⚠️ 注意最后一行：这四条是"某模式生效时要求对应标记"，所以**"只在 PLAY 显示"
> 无法用 `showOnSelect=false` 表达**。这类"仅某界面"的需求要在 `isItemVisible()`
> 里加独立硬规则（例：In-Game Spectrum 入口的 `SPECTRUM_ENTRY_KEYCODE && !isPlayMode`）。

## 二、顺序（`playInsertBefore`，仅 PLAY 生效）

有些按钮"在其他界面位置不变、但在 PLAY 界面要挪到别处"。这时不要改数组顺序，
而是给该项的第 8 个构造参数填锚点 keycode：

```java
// 数组里仍在末尾（选曲等界面顺序不变）；PLAY 界面插到 "^ UP" 之前
new MenuItem("Show FPS", Keys.F1, false, true, false, true, false, Keys.UP),
```

`buildVisibleIndexOrder()` 负责组装：

1. 按数组顺序收集可见项；
2. PLAY 界面下，声明了 `playInsertBefore` 的项**不在原位出现**，而是在遍历到
   锚点项时插到它前面；
3. 锚点项在 PLAY 界面自身不可见时，**不启用**该条覆盖（退回原位置），
   避免"锚点被隐藏 → 带覆盖的项直接消失"；
4. 其他界面不传锚点 → 顺序与数组完全一致。

**硬约束**：`drawPanel()`（绘制）与 `hitTestPanel()`（命中判定）必须共用
`buildVisibleIndexOrder()`。若各自遍历一遍，一旦两者顺序不一致，就会出现
"看到的按钮和点到的按钮不是同一个"。历史上频谱调整页出过的
"按 Y 的 ± 把 X 的 + 弄没"就是这类索引空间混用的后果。

## 三、当前的 PLAY 界面顺序

| # | 按钮 | 备注 |
| --- | --- | --- |
| 1 | Touch Key | |
| 2 | **Show FPS** | 由 `playInsertBefore=WALKURE_KEYCODE` 占用已隐藏的 Walkure 那一格 |
| 3-6 | Update Song / Music Player / Skin Select / Key Config | |
| 7 | ESC | |
| 8-11 | ^ UP / v DOWN / < LEFT / > RIGHT | |
| 12 | In-Game Spectrum | 仅 PLAY 可见 |

- `Walkure` 在 PLAY 界面隐藏（`showOnPlay=false`），其位置由 Show FPS 占用；
  在选曲等界面 Walkure 仍显示在数组原位。
- `Enter` 在 PLAY 界面已隐藏（`showOnPlay=false`）；在 KeyConfig / SkinSelect 仍正常显示。
- PLAY 里浮动菜单本身还受 `Config.showFloatingMenuInPlay` 开关控制（默认 true），
  但 **practice 模式例外**（见下节）。

## 四、Practice 模式：图标常驻 + 30% 不透明度

仅对 practice 生效（普通游玩 / AUTOPLAY / REPLAY 行为不变）。

**判定**：`MainController.changeState()` 里

```java
state == PLAY && resource.getPlayMode().mode == BMSPlayerMode.Mode.PRACTICE
```

为 true 时调用 `floatingMenu.setPracticeMode(true)`；离开 PLAY 一律置回 false。

**效果**（`FloatingMenu`）：

1. **常驻显示**：不参与 PLAY 的"无操作自动隐藏"（`render()` 的超时分支加了
   `!practiceMode`），且 `setPracticeMode(true)` 会清掉已累积的隐藏状态；
   同时**不受 `Config.showFloatingMenuInPlay` 限制** —— practice 下强制可见；
2. **30% 不透明度**：图标以 `PRACTICE_ICON_ALPHA = 0.3f` 绘制（普通模式 0.55f），
   常驻但不抢视线。只影响收起态的浮动图标；展开后的面板仍是正常不透明度。

要调透明度改 `FloatingMenu.PRACTICE_ICON_ALPHA` 一个值即可。

## 五、按键覆盖层（PLAYOPTION 1/2）：7 键（scratch 已回退）

**入口**：`items[]` 里的 `PLAYOPTION 1`（keycode `Keys.NUM_5`）与 `PLAYOPTION 2`
（keycode `-141`）是**切换式**按钮：点一次开始模拟长按（NUM5 / START）并把菜单面板收起、
只留图标；再点一次释放。长按期间绘制 `draw7KOverlay()`，面板上的按键直接写核心层槽位
（`send7KKey` → `BMSPlayerInputProcessor.setKeyChanged`）。

**布局**：`K7_BUTTON_COUNT = 7` 个按钮。**按钮索引 i 直接就是核心层槽位 i**
（面板上的 1~7 = 键 1~7 = 槽位 0~6，同一个索引空间，没有换算）。
整体宽约屏幕宽 55%、高约屏幕高 30%。

> 2026-09-19 曾短暂加过"最左一格 scratch"（8 格、按钮索引 0 = 槽位 7），
> 同日按要求**回退**：PLAYOPTION 的调整用不到它，而多一套索引换算就多一处
> "按钮索引 ≠ 槽位"的出错面。若将来确实要 TARGET_UP/DOWN 调整，建议加两个按钮并
> 显式写明槽位 7/8 是**相反方向**。

**键位显示**：`refresh7KKeyNamesCache()` 读 `kbInput.getKeys()` 的槽 i（= 按钮 i），
越界时回退 `SEVEN_KEYS_KEYCODES_DEFAULT[i]`；只在 keycode 变化时重算 `Keys.toString`，
避免每帧分配 String。

**收尾（两条路径都要走）**：

1. `releaseHoldKey()`：关闭按钮 / 再点图标 / 再点 PLAYOPTION 时，释放 NUM5/START
   与所有仍按着的覆盖层键；
2. `releaseStuckPresses()`：界面切走时的兜底，见 5.1。

### 5.1 历史 bug：切进 PLAY 后某个 lane 常亮（2026-09-19 修复）

**现象**：点 PLAYOPTION 1（长按模拟 NUM5）后进入 play 界面，5 号键常亮，
必须重新按一下对应物理键才松开。

**根因**：`KeyBoardInputProcesseor.setSimulatedKeyState(keycode, false)` 的**释放
从来没同步到核心层**。原实现把整段 `keyChanged(...)` 放在
`if (!keystate[keycode] || pressed)` 里，而释放时 `keystate[keycode]` 恰好为 true
（被长按锁定）、`pressed` 为 false → 条件恒假 → `keyChanged(slot, false)` 永不发出。
核心层 keystate 是**电平**语义，于是：

- PLAYOPTION 1 长按 = 模拟 `NUM_5` 按下 → 若玩家把某条 lane 绑在数字键 5 上，
  该槽位被写成 true；
- 释放时 false 发不出去 → 槽位永久留在 true → 切进 play 后该 lane 常亮；
- 只有重新按一次物理数字键 5，`poll()` 的双向 `keyChanged` 才把它清成 false。

**修复**（`KeyBoardInputProcesseor`）：改成**按下/释放严格成对**发送——

- 新增 `boolean[] simulatedKeyNotified` 记录"已向核心层发过 true"；
- 按下：首次按下才 `keyChanged(slot, true)`（重复按下不重发，避免污染 keylog/replay）；
- 释放：只要之前发过 true，就**必须补发** `keyChanged(slot, false)`，然后清标记；
- `clear()` 里一并清标记。

**第二道防线**（`FloatingMenu`）：`releaseStuckPresses()` 在
`setSelectMode(true→false)`（离开选曲）与 `setVisible(true→false)` 时调用，
释放覆盖层与展开面板里所有"只按下、没抬起"的模拟按键。它针对另一类泄漏：
按下走 `touchDown`、释放走 `touchUp`，界面在按住期间被切走时 `touchUp` 永不到达。
**只靠 `setVisible` 不够**——PLAY 下浮动菜单通常仍 visible
（`showFloatingMenuInPlay` 默认开），所以必须同时在 `setSelectMode` 挂这个钩子。

## 六、RESULT 界面：图标点击**直出**覆盖层（无菜单面板）

仅 `RESULT` / `COURSERESULT` 生效（`floatingMenu.setResultMode(true)`），
需求是"结果界面也要浮动菜单，但点图标不做菜单，直接给 playoption 的那几个键"。

**与其它界面的三点不同**：

1. **不展开菜单面板**：`touchDown` 命中图标时直接 `openDirectKeyOverlay()`
   （`holdKeyType = DIRECT`），`expanded` 恒为 false；`setResultMode(true)` 时
   还会强制收起残留的展开态与频谱调整页；
2. **摆放跟随浮动图标**：覆盖层布局在 `HoldKeyType.DIRECT` 下走
   `anchoredOverlayX/Top()`（与 `anchorPanel()` 同一套锚定规则：右下/右上右对齐，
   下中/下右向上弹出，其余居中，含屏幕边界保护）。PLAYOPTION 1/2 的覆盖层仍是
   屏幕居中 —— 只改结果界面这一种；
3. **与"点一下退出结果界面"共存**：`MainController.changeState()` 在
   RESULT/COURSERESULT 下把输入设为 `InputMultiplexer(floatingMenu, escapeMapper)`——
   命中图标/覆盖层的触摸被浮动菜单消费（覆盖层打开时是模态，全部消费），
   其余触摸落到 `escapeMapper` → ESCAPE，保持原有的退出行为。

**为什么键位选择是这 7 个键**：结果界面的键位表是 `ResultKeyProperty.BEAT_7K`
`{OK, OK, OK, OK, REPLAY_DIFFERENT, CHANGE_GRAPH, REPLAY_SAME, null, null}`——
槽位 0~3 = OK（退出）、4 = REPLAY_DIFFERENT、**5 = CHANGE_GRAPH（切换 gauge 曲线，
不会退出）**、6 = REPLAY_SAME（覆盖层只发槽位 0~6；结果界面里槽位 7/8 本来也无绑定）。
`MusicResult.input()` 读法为 `getKeyState(i) && resetKeyChangedTime(i)`，
所以按下/抬起必须成对（`releaseHoldKey` 的兜底释放就是为这条路径准备的），
且多指同按天然支持（`pointer7KKey[]` 按指针存）。

