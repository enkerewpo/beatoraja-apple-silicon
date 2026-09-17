# Lua 皮肤脚本相对路径 IO 在非选曲界面全部失效

## 现象

WMII_FHD 结果皮肤（`resultExpand.lua`）在 RESULT 界面报：

```text
E  Lua 加载/编译失败: @resultExpand.lua:95 attempt to index ? (a nil value)
```

第 95 行是 `local mode = playerConfig[modeName]`，也就是 `playerConfig` 是 nil。

## 根因

`playerConfig` 由第 92 行的 `loadPlayerConfig()` 赋值，它只有两条 `return nil` 路径，
两条都源于 **`io.open` 读不到文件**：

```lua
local function loadJson(path)
    local file = io.open(path, "r")          -- 相对路径
    if not file then
        print("Failed to open: " .. path)    -- ← 就是这里返回 nil
        return nil
    end
    ...
end

local function loadPlayerConfig()
    local sysConfig = loadJson("config_sys.json")                    -- 相对路径
    ...
    "player/" .. playerName .. "/config_player.json"                 -- 相对路径
    return loadJson(playerConfigPath)
end
```

Android 上进程工作目录是 `/`，而脚本里的相对路径全部是按 **beatoraja 数据根目录**
（`/storage/emulated/0/Android/data/com.starxh.beatoraja/files`）写的 —— 也就是桌面端
`user.dir` 的语义。放在 `/` 下解析必然不存在，于是 `io.open` 返回 nil。

### 为什么是 RESULT 界面才炸

引擎里本来就有针对这个问题的重定向，但它的生效条件被限制在选曲界面：

```java
// LuaSkinLoader（修改前）
private void installIoRedirectIfMusicSelect(File skinDir) {
    if (ioRedirectInstalled) return;
    if (state == null || !(state instanceof MusicSelector)) return;   // ← 这里
    ...
}
```

`resultExpand.lua` 由 `result.luaskin → require("resultMain") → require("resultExpand")`
加载，整个过程发生在 `loadHeader()` 的 `getExecResult()` 里 —— 也就是刚刚
`installIoRedirect...` 提前返回之后。所以 RESULT 皮肤拿到的始终是原始 `io.open`。

同一道限制也把 `SkinConfiguration.java:565` 的 `new LuaSkinLoader()`（`state == null`）
挡在外面。

### 为什么报错行离失败点很远

`loadJson` 失败时只是 `print` 一行然后返回 nil，没有中断执行。nil 会一路传给
`playerConfig`，直到第 95 行做索引才抛错。**真正的失败点在第 60 行，报错却在第 95 行**，
中间隔了 35 行 —— 这是这个 bug 难查的主要原因。

皮肤作者其实早就知道这件事，`m_select/load.lua` 里留了注释和绕法：

```lua
-- Android 下 CWD 是 "/" 且皮肤位于 filesDir/skin/m_select/，相对路径无法解析，
-- 必须借助 skin_config.get_path() 转成绝对路径（GenericTheme 同款做法）。
-- 桌面端 skin_config 为 nil 时，回退到原始的 "skin/<folder>/..." 写法以保持兼容。
local function adv_path(rel)
    if skin_config and skin_config.get_path then
        return skin_config.get_path("customize/advanced/" .. rel)
    end
    return "skin/" .. folder_name .. "/customize/advanced/" .. rel
end
```

即：**皮肤脚本假设相对路径按数据根目录解析**，引擎只在选曲界面满足了这个假设。

## 修复

`LuaSkinLoader`：

1. **去掉状态限制**，重命名为 `installIoRedirect()`。所有状态（RESULT / PLAY / …）
   以及 `state == null` 的皮肤扫描路径都会装上。
2. **补全解析顺序**（`resolvePath`），四步各对应一类真实场景：

   | 顺序 | 基准 | 解决的情况 |
   | --- | --- | --- |
   | 1 | 脚本同目录 | 与脚本放一起的辅助文件 |
   | 2 | `beatoraja.root` | 皮肤约定：`config_sys.json`、`player/<id>/…`、`skin/<folder>/…` |
   | 3 | 进程工作目录 | **桌面端**：`beatoraja.root` 未设置时 `user.dir` 就是数据根 |
   | 4 | 目标不存在（写入） | 第一级目录是数据根下的真实目录 → 判给根目录，否则落在脚本目录旁 |

   第 3 步是必要的：`beatoraja.root` 只有 Android 会设置（`AndroidLauncher:329`），
   桌面端为 null。少了它，桌面端 `config_sys.json` 反而会被解析到皮肤目录下而不存在。

3. **同时包装 `dofile` / `loadfile`**。它们走 `BaseLib`，不经过 `io.open`，同样按 CWD
   解析；皮肤里确有使用（`GenericTheme/play/play.lua`、`m_select/result/resultmain.lua`）。
   这两个用法目前都先用 `skin_config.get_path()` 转成了绝对路径，所以包装对它们是
   透明的（绝对路径原样透传），只是把相对路径这条路也补齐。

## 验证

用设备上拉下来的真实文件搭本地镜像（`config_sys.json` + 真实的 76KB
`player/<id>/config_player.json` + `json.lua`），把工作目录切到一个**空目录**来模拟
Android 的 `user.dir == "/"`，用 `luaj-jse-3.0.1.jar` 跑同构脚本：

| 场景 | 结果 |
| --- | --- |
| 修复前（无重定向） | `Failed to open: config_sys.json` → `attempt to index ? (a nil value)` **精确重现用户报错** |
| 修复后 | `Loading: player/Star/config_player.json` → `mode7.keyboard.select = 51` |
| `dofile("skin/.../helper.lua")` | 加载成功 |
| 桌面端回归（无 `beatoraja.root`、CWD = 数据根） | 正常 |
| 写入 `player/<id>/test_write.json` | 文件正确落在 `root/player/Star/` 下 |

## 已知边界

- **`require` 不受影响**，也不该受影响：它走 `SkinLuaAccessor` 里自定义的
  `globals.finder`（`package.path` + 多基准查找），和 `io.open` 是两条完全独立的路径。
  `require("json")` 一直能成功而 `io.open("config_sys.json")` 一直失败，就是这个原因。
- 同名的相对路径如果**既存在于脚本目录、又存在于数据根目录**，脚本目录优先
  （与修改前的选曲界面行为一致）。
- 写入时不会自动创建父目录。像 `skin/WMII_FHD/result/courseData.json` 这种路径，
  如果实际皮肤目录名不是 `WMII_FHD`，`io.open(..., "w")` 仍会失败 —— 那是皮肤自己
  写死了目录名（实际目录为 `WMII_FHD_result_oraja_bmz_260830`），属皮肤侧问题。
