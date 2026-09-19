# 数字键误触发 scratch 长按（幽灵按键）—— 根因与修复

## 一、现象

KeyConfig 允许把数字键 1~9 绑定为轨道键后，游玩中按数字键**有概率**触发
scratch 键长按（音符全接到 scratch 上），且按 F-SCR 的绑定键无法解除，
必须重新按一次 R-SCR 的绑定键才能恢复正常。

## 二、根因：两套索引空间重叠

`BMSPlayerInputProcessor`（输入中枢）的 `keystate[]` 是 **槽位（slot）索引**空间：

- 5K 模式：槽位 0~5 = 音符键，6/7 = START/SELECT（由 startChanged/setSelectPressed 管理）；
- 7K 模式：槽位 0~6 = 音符键，**槽位 7 = F-SCR、槽位 8 = R-SCR**，9/10 = START/SELECT。

而 libGDX 的物理键值（keycode）恰好有：`NUM_0=7、NUM_1=8、NUM_2=9、NUM_3=10 …`。

历史代码里有三处**按 keycode 写核心层**的误写（`setKeyState(keycode, ...)`），
等于把物理键值当槽位写进了核心层：

| # | 位置 | 后果 |
| --- | --- | --- |
| 1 | `KeyBoardInputProcesseor.poll()` 的 ControlKeys 循环 | 按数字键 0~3 直接写槽位 7~10 → 7K 模式下 F-SCR/R-SCR/START/SELECT 被当成"按下"，且不受 UP 转换管理，释放时序错乱 |
| 2 | `poll()` 的模拟长按心跳循环 | 心跳里 `setKeyState(i, true)` 的 `i` 是 keycode，同样误写核心层 |
| 3 | `setSimulatedKeyState()`（触摸按键 / FloatingMenu 模拟路径） | 模拟数字键时同样按 keycode 误写核心层 |

**为什么表现为"长按"且难解除**：核心层 `keystate[slot]` 是电平触发的
（JudgeManager 每帧读 `keystate[slot]`）。误写 true 后：

1. 没有对应的 `keyChanged(false)` 转换来清它（槽位状态不由这套转换管理）；
2. `kbinput.clear()`（每首歌开始时清空所有状态）会与误写竞争——如果 clear 在后，
   状态被清掉没事；如果误写发生在 clear 之后的一帧内，就**卡死在 true**；
3. 重按 F-SCR 的绑定键发出的 `keyChanged(slot7, ...)` 只在"状态与当前不同"时才写
   （`keyChanged` 有 `keystate[i] != pressed` 判断），救不了卡死的槽位 8（R-SCR）；
   重按 R-SCR 绑定键发出 `keyChanged(slot8, false)` 才能复位——与用户观察完全一致。

## 三、修复

三处 keycode→槽位误写**全部移除**（`KeyBoardInputProcesseor.java`）：

1. `poll()` ControlKeys 循环：删除 `setKeyState(key.keycode, ...)`。
   绑定到轨道的按键由同文件的 lane 循环按槽位 `keyChanged(i, ...)` 正确传播；
2. 模拟长按心跳：只保持本地 `keystate[i]`（本地的 `keystate[]` 是 keycode 索引，
   `isKeyPressed`/`isControlKeyPressed` 等消费方读的就是它），核心层不动；
3. `setSimulatedKeyState()`：删除 `setKeyState(keycode, ...)`，
   轨道传播只走其内部的 keyChanged 循环（按槽位）。

核心层的写入路径收敛为三条，全部按槽位：
- `keyChanged(device, time, slot, pressed)`（键盘 lane 循环、控制器、触摸模拟共用）；
- `setKeyState(slot, ...)`（仅控制器/MIDI 等本来就按槽位工作的设备调用）；
- `startChanged` / `setSelectPressed`（START/SELECT 专用槽位）。

## 四、防回归

今后任何想"把按键同步到核心层"的代码，先问一句：**手里的下标是槽位还是物理键值？**
核心层 `keystate[]` 只接受槽位。物理键值→槽位的唯一合法桥梁是
`keys[]`（KeyConfig.getKeyAssign）+ `keyChanged`。

javac 校验：exit=0（2026-09-19）。
