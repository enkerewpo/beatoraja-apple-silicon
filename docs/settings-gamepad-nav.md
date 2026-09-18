# 首发设置界面（SettingsActivity）手柄导航：诊断与修复

涉及文件：`android/src/main/java/com/starxh/beatoraja/android/compose/SettingsActivity.java`

`SettingsActivity` 是 App 的 **launcher Activity**（manifest 里的 MAIN），
也就是"打开游戏第一眼看到的配置界面"。它的手柄导航是自建的：
`setupGamepadFocusable()` 注册可聚焦控件 → `buildFocusableControlsList()` 收集 →
四个方向各写一个几何就近搜索 → 自绘高光 `focusIndicator` → `ensureViewVisible()` 滚动。

## 一、四个确证缺陷（已修）

### 0. 禁用控件挡路 → 焦点卡住不动（真机复现：卡在"添加歌曲路径"按钮）

`View.isFocusable()` **不看 enabled**（`isFocusable() = focusableInTouchMode || FOCUSABLE`），
而 `requestFocus()` 内部 `canTakeFocus()` 要求 `ENABLED`。于是：

- 默认歌曲路径那一行的 `EditText` 被 `setEnabled(false)`（`refreshBmsPathList()`），
  它在"添加路径"按钮正下方，`isFocusable()` 为 true → 被选成 DOWN 的目标；
- `requestFocus()` 静默失败（返回 false）→ 原来的代码不看返回值 → **焦点卡死在按钮上**。
- 同类还有：拉伸全屏开启时被锁定的音频频谱开关（disabled）。

**修法**：
- 收集候选时跳过 `!isEnabled()` 的控件（容器仍继续递归，避免连带跳过子控件）；
- `moveFocus()` 改成**按次优目标重试**（最多 6 次）：`requestFocus()` 失败就把该控件
  从候选池剔掉重找 —— 这样即使还有别的"拿不到焦点"的情况也不会卡死。

### 1. 方向键被控件吞掉 → 某些选项永远到不了

`dispatchKeyEvent` 原来对四个方向键一律 `super.dispatchKeyEvent(event)` 优先，
只要任何控件消费了就 return。而：

- `SeekBar`（`AbsSeekBar.onKeyDown`）会消费 **UP/DOWN/LEFT/RIGHT**；
- `Spinner` 会消费 UP/DOWN（展开下拉）。

于是焦点进入滑块/下拉框后**上下左右都出不来** —— 这就是"某些选项怎么操作都到不了"。

**修法**：只有"数值类控件"（`SeekBar` / `Spinner`）的**左右键**才先交给系统（改值/换项），
**上下键一律由我们做焦点移动**，保证任何控件都能上下离开。下拉框仍可用 A/CENTER
（`activateCurrentFocus()` → `performClick()`）打开。

### 2. 展开/收起后立刻按方向键 → 用旧坐标就近搜索，"跳到很后面"

两个可展开区块（Play Options / Input Options）在 `setVisibility()` 之后立刻
`buildFocusableControlsList()`；但此时还没走 measure/layout，新显示的控件
`getLocationOnScreen` 返回的是**旧值（未测量时常为 0）**，几何搜索自然选中离谱目标。
BMS 路径 / Table URL 行是 `removeAllViews()` + `addView()` 整体重建，同理。

**修法**：
- 结构变化处打标记 `navNeedsLayout = true`；
- `moveFocus()` 见到标记就把这次移动**推迟到布局完成之后**执行
  （`afterNextLayout()` 用 `OnGlobalLayoutListener` 一次性回调 + 32ms 兜底，按键不丢）；
- `collectFocusableViews()` 跳过 `getWidth()/getHeight() <= 0`（还没布局）的控件和高光层本身，
  避免"0 坐标"参与几何比较。

### 3. 摇杆（轴）完全不支持

Android 上摇杆是 `AXIS_X/AXIS_Y` 的 **MotionEvent**，不产生 KeyEvent；
原来只实现了 `dispatchKeyEvent`，所以左/右摇杆根本无法导航
（`lastGamepadInputTime` 还只写不读，是半成品痕迹）。

**修法**：新增 `onGenericMotionEvent()`：
- 只处理 `SOURCE_JOYSTICK` / `SOURCE_GAMEPAD`；
- AXIS_X/AXIS_Y（并兼容部分手柄把十字键走 HAT 轴上报的 `AXIS_HAT_X/Y`）；
- 阈值 `STICK_THRESHOLD = 0.6`，取偏离更大的轴（斜推不抖）；
- 重复间隔 `STICK_REPEAT_MS = 180`，避免漂移值连跳。

## 二、下拉列表（Spinner）看不见光标

现象：音频可视化、BGM 播放模式等**所有下拉**点开后是一列选项，但移动时看不到光标在哪一项。

原因：
1. `spinner_dropdown_item.xml` 原来是**不透明的纯色背景** `#333333`。列表高光由系统以
   **list selector** 的形式画在项背景**下面**，于是被整块盖住；
2. 应用主题是 `android:Theme.Material.Light.NoActionBar`，其默认 list selector 是浅色主题用的
   半透明黑，深色行上本来也几乎看不见。

修法（三层，任一层生效都能看到光标）：
- `res/drawable/list_selector_highlight.xml`：对比度足够的蓝色高光 `#804FC3F7`；
- 主题 `GdxTheme` 增加 `<item name="android:listSelector">` 指向它 —— 作用于 Spinner 下拉、
  字母轮盘 GridView、对话框单选列表等所有 `AbsListView`；
- `res/drawable/dropdown_item_bg.xml`：下拉项背景改为**透明 + 状态高光**
  （pressed/focused/selected/activated → 同一份高光），换掉原来的不透明纯色；
- 下拉面板的深色底改由 Spinner 的 `android:popupBackground="#F2333333"` 提供
  （`activity_settings.xml` 里 16 个 Spinner 全部加上，因为项背景已透明）。

## 三、滑块（音量等）的"选中—调整"两段式

需求：滑块平时不能直接用左右改值；要先用 A（或手柄的确认键）**选中**，才能用左右 / 摇杆左右
调值，此时上下**不滚动也不移动焦点**；再按一次 A（或 B）退出，恢复上下滚动。

实现（`SettingsActivity`）：
- 新增字段 `adjustingSeekBar`（null = 无滑块处于调整态）；
- `activateCurrentFocus()`：焦点是 `SeekBar` 时，A 键在"进入 / 退出调整态"之间切换
  （其它控件仍是原来的 `performClick()`）；
- `dispatchKeyEvent()` 最前面加调整态分支：左右 → `adjustSeekBar(±1)`；上下 → 直接吞掉；
  B/BACK → **先退出调整态**（而不是直接离开设置界面）；A/CENTER/ENTER → 退出调整态；
- `onGenericMotionEvent()`：调整态下只响应水平轴（同样按 `STICK_REPEAT_MS` 节流），
  垂直轴一律忽略 —— 摇杆也不会滚走；
- 步长 = 量程的 2%（至少 1），`setProgress()` 触发已有的 `OnSeekBarChangeListener` 落值；
  ⚠️ 因此那三个音量 listener **不能**再写 `if (fromUser)` —— 手柄调整走的是
  `setProgress()`（`fromUser == false`），只认 `fromUser` 会导致右侧百分比文本不刷新，
  而且 `selectedVolume/selectedKeyVolume/selectedBgmVolume` 字段也不更新，
  保存时写回 JSON 的还是旧值（等于白调）。现已改为无条件同步（见下）。
- 高光加强：调整态用 `res/drawable/focus_highlight_active.xml`
  （实心 #994FC3F7 + 3dp 边框），普通态仍是 `focus_highlight.xml`（#33 + 2dp）；
- 焦点离开滑块或切换到手柄以外的输入（触摸）时，自动退出调整态。

未进入调整态时，滑块的左右键不再改值，改为普通的焦点移动（例如可以走到这一行右端的 `?` 按钮）。
Spinner（下拉框）仍保持"左右直接换项"，没有改成两段式。

### 3.6 按住不能连续调整（真机反馈已修）

现象：调整态下按住十字键左/右只走一步，必须一下一下地按。

根因：原实现每收到一个 `ACTION_DOWN` 调一次 `adjustSeekBar()`，
等于**依赖系统的 key repeat 自动重复**。键盘有自动重复，但**手柄十字键
绝大多数设备不上报自动重复**（长按只发一个 DOWN），于是按住只动一步。

修法（软件连发，`startSeekRepeat`/`stopSeekRepeat`）：
- `ACTION_DOWN`（LEFT/RIGHT）：立即调一步，并 `postDelayed` 首延 175ms 的连发任务；
  连发中每 55ms 一步，累计 6 步后加速到 30ms（按住越久调得越快）；
- `ACTION_UP`（LEFT/RIGHT）、退出调整态（A/B）、焦点离开滑块、
  切回触摸模式、`onResume`：一律 `stopSeekRepeat()` ——
  Handler removeCallbacks，绝不会有"松手了还在自己跑"的残留；
- 同方向的连续 `ACTION_DOWN` 幂等忽略：万一接的是有自动重复的设备
  （USB 键盘方向键），系统连发的多个 DOWN 不会叠加出双倍步进；
- 摇杆侧本来就是事件流驱动（持续推杆持续来事件），天然连续；
  调整态改用独立节流 `lastStickAdjustTime`、间隔 45ms（原共用导航节流 180ms，
  对调音量来说偏慢），与十字键连发手感对齐。

### 3.7 导航按住不能连续移动（真机反馈已修）

现象：十字键按住上/下只移动一次焦点，必须一下一下地按 —— 与 3.6 同根因
（`moveFocus` 逐个 `ACTION_DOWN` 执行，依赖系统 key repeat，手柄没有）。

修法（导航软件连发，`startNavRepeat`/`stopNavRepeat`，与 3.6 同一套模式）：
- `ACTION_DOWN`（UP/DOWN/LEFT/RIGHT，非调整态）：立即走一步 + 首延 200ms 连发，
  每 80ms 一步、4 步后加速到 60ms（初版 400/160/120ms 被真机反馈嫌太保守，整体砍半）；
- `ACTION_UP`（任一方向键）、切回触摸、`onResume`：`stopNavRepeat()` 兜底；
- 同方向重复 `ACTION_DOWN` 幂等忽略（有自动重复的设备不叠加）；
- 连发走的就是普通 `moveFocus()`，所以"展开后等布局再移动"的推迟逻辑、
  视口优先的就近搜索全部自动继承，不会因为连发而绕过防护。

注意导航连发与滑块连发（3.6）互相独立、可并存：调整态下方向键由
`seekRepeat` 接管；导航连发只在非调整态启动。

### 3.5 音量数字不跟随（真机反馈已修）

现象：手柄进入调整态后调值，滑块动了但右侧的百分比数字不变。

根因：三个音量滑块的 `onProgressChanged` 全写成
`if (fromUser) { selectedX = progress; xPercent.setText(...); }`。
手柄/摇杆调整是**代码调用** `setProgress()`，`fromUser` 为 false，
于是文本与字段都被跳过 —— 保存时把旧值写回 JSON。

修法：去掉 `fromUser` 判断，无条件同步字段与文本。
初始化顺序上三处 `setProgress(selectedX)` 都发生在
`setOnSeekBarChangeListener()` **之前**，所以不会在 init 阶段误触发；
即使触发也只是赋同值、设同文本，无副作用。

通用教训：**只要某控件的值可能由代码（非用户触摸）改写，
它的监听器就不能带 `fromUser` 门控。**

## 四、顺手加固：几何搜索优先"视口内可见"的控件

原来的四方向搜索（`findNextDown/Up/Right/Left`）只看屏幕坐标，
会把"同方向但在滚动视口之外、甚至更靠后一屏"的控件选出来 —— 表现为焦点乱飘。
现在合并为一个 `findNearestInDirection()`：打分仍是"主方向距离 × 1000 + 次方向距离"，
但**只在与滚动视口有交集的控件里选**；视口内没有候选时才退回原来的行为
（否则滚不到底部的项）。

## 五、结论：需不需要重构

- **输入层与驱动时机**是真正的病根，已按上面三点重做（这是"重构"的部分）。
- **导航模型**（纯几何就近搜索）保留：它能表达"行的概念"，但缺少行/列语义，
  自动换行、同排控件对齐等场景仍可能出现"斜着跳一格"。
  如果手感仍不满意，下一步应改成**有序列表 + 行分组**模型：
  按 `(top, left)` 排序分组成"行"，UP/DOWN 走行、LEFT/RIGHT 走行内，
  而不是每次做全局几何比较。改动集中在 `moveFocus()` + `findNearestInDirection()`。
- 未做：弹窗（帮助/新建角色/字母轮盘 `CharacterWheelDialog`）内的手柄导航。
  它们各自是独立窗口，走系统默认焦点导航（没有自绘高光）；
  字母轮盘只处理了 BACK/B，摇杆在弹窗里仍不可用。
