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
| 1-6 | Touch Key / Walkure / Update Song / Music Player / Skin Select / Key Config | |
| 7 | ESC | |
| 8 | **Show FPS** | 由 `playInsertBefore=Keys.UP` 移到这里（原 Enter 的位置）|
| 9-12 | ^ UP / v DOWN / < LEFT / > RIGHT | |
| 13 | In-Game Spectrum | 仅 PLAY 可见 |

- `Enter` 在 PLAY 界面已隐藏（`showOnPlay=false`）；在 KeyConfig / SkinSelect 仍正常显示。
- `Show FPS` 在选曲等界面仍是数组末尾位置。
- PLAY 里浮動菜单本身还受 `Config.showFloatingMenuInPlay` 开关控制（默认 true）。
