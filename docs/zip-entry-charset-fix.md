# zip 导入崩溃：条目名编码猜错 + unchecked 异常逃出后台线程

## 现象

启动时导入 `Download/beatoraja/skins/*.zip`，前一个包正常，第二个包直接崩进程：

```text
AndroidLauncher  I  Deleted skin zip after extract: RED_BELT.zip
AndroidLauncher  I  Moved skin zip to: .../files/skin/LR2.zip
AndroidRuntime   E  FATAL EXCEPTION: ZipExtractor
                    java.lang.IllegalArgumentException: java.nio.charset.MalformedInputException: Input length = 1
                    	at java.util.zip.ZipCoder.toString(ZipCoder.java:66)
                    	at java.util.zip.ZipFile$Source.initCEN(ZipFile.java:1720)
                    	at com.starxh.beatoraja.android.AndroidLauncher.openZipFile(AndroidLauncher.java:782)
                    	at com.starxh.beatoraja.android.AndroidLauncher.extractZip(AndroidLauncher.java:679)
                    	...
                    Caused by: java.nio.charset.MalformedInputException: Input length = 1
```

用户最初的判断是"skin 的 zip 不支持多个 zip 解压"。**不是** —— 前一个包成功、第二个失败只是
巧合（见下文实测：前一个包的名字全是 ASCII，任何编码都能解）。**`songs` 那边是同一条代码路径，
存在同样的问题。**

## 根因一：条目名编码只按设备语言猜一次

`getZipCharset()` 原来是这么选的：

```java
if ("ja".equals(language))      return Charset.forName("MS932");
else if ("zh".equals(language)) return Charset.forName("GBK");
return StandardCharsets.UTF_8;
```

设备语言是 `zh` → 用 **GBK** 解码所有条目名 → LR2.zip 里那条非 ASCII 条目解不动 → 抛异常。

zip 的条目名只有带 **UTF-8 标志位**（general purpose flag bit 11 = `0x800`）时才一定是 UTF-8，
否则得看打包方的本地代码页。而 Android 的 `ZipFile(File, Charset)` 会用构造参数解码**所有**
条目名（实测**不检查** per-entry 的 `0x800` 标志），所以"按设备语言猜"必然会在跨语言场景翻车。

### 实测（用 Python 直接解析中央目录，看原始字节）

| zip | 条目数 | flag 分布 | 非 ASCII 条目 | utf-8 | gbk | cp932(MS932) |
| --- | --- | --- | --- | --- | --- | --- |
| `LR2.zip` | 460 | `0x0`, **`0x800`** | 1 | **460/460 ✓** | 459/460 ✗ | 459/460 ✗ |
| `OA_DX_03.zip` | 466 | `0x0`（无标志位） | 15 | 451/466 ✗ | 466/466 ⚠ | 466/466 ✓ |
| `RED_BELT.zip`（已成功） | — | — | **0** | ✓ | ✓ | ✓ |

- `LR2.zip` **是 UTF-8 的**，设备却按 GBK 解 → 失败。**这就是崩溃的直接原因。**
- `RED_BELT.zip` 名字全 ASCII → 任何编码都能解 → 所以它"看起来正常"，与"多个 zip"无关。

## 根因二：抛出的是 unchecked 异常，`catch (IOException)` 抓不到

`ZipCoder.toString()` 把 `MalformedInputException` **包成了 `IllegalArgumentException`**：

```java
// java.util.zip.ZipCoder
throw new IllegalArgumentException(e);   // unchecked!
```

而两个解压方法都只写了 `catch (IOException e)`，`ZipExtractor` 线程又没有顶层兜底 →
异常一路逃到 `Thread.run` → 未捕获 → **进程被 SIGKILL**。这才是"导入一个 zip 会把 App 打崩"
而不是"这个 zip 导入失败"的原因。

## 修复

### 新增 `android/.../ZipCharsetSupport.java`

逐个候选编码尝试，取第一个"能完整解码且名字正常"的：

| 顺序 | 编码 | 理由 |
| --- | --- | --- |
| 1 | UTF-8 | 现代 zip 的事实标准（Win10+ 资源管理器、7-Zip UTF-8 选项） |
| 2 | GBK / MS932 | 按设备语言偏好（zh→GBK、ja→MS932） |
| 3 | 另一种 CJK | 覆盖"中文机导入日文皮肤包"这类跨语言场景 |
| 4 | ISO-8859-1 | 永不抛异常（字节 1:1），"宁可名字乱码也不崩" |

"名字正常" = 不含 `U+FFFD` 替换符、不含 C0/C1 控制字符（文件名里出现控制字符在任何平台都非法）。
选中的编码会打 logcat（tag `ZipCharset`）便于事后诊断；全部候选都不行才抛 `IOException`。

### `AndroidLauncher`

1. `openZipFile()` 改为委托 `ZipCharsetSupport.open()`。
2. `extractZip()` / `extractSongZip()` 的 `catch (IOException)` → **`catch (Exception)`**，
   并顺手 `deleteRecursive(解压目录)`。后半句很重要：解压到一半的目录会让下次启动被判成
   "已解压完成"而跳过重试，等于永久卡住。
3. `ZipExtractor` 线程体加 `try/catch (Throwable)` 顶层兜底 —— 导入失败只能表现为"这次没导进去"，
   不该让用户丢进程。
4. **残留 zip 重试**：流程是"把外部 zip 搬进 `files/skin/` 再解压"，一旦失败，zip 就留在内部目录
   而外部目录里已经没有了 —— 只扫外部目录的用户只能重新拷一份。由于解压成功后 zip 必被删除，
   "内部目录里还有 zip"等价于"上次没成功"，所以补一次扫描重试是安全的。
   （这一条让此前卡住的 `LR2.zip` / `OA_DX_03.zip` 在下次启动时自动恢复，不需要用户重新拷贝。）

## 已知局限：GBK 与 MS932 无法完全区分

同一串字节**可能两种编码都能解码成功但结果不同**，没有内容级交叉验证就判不出来。实测例子：

```text
raw = 8B E0 96 D4 .png
  cp932 → '金網.png'    ← 原始（Shift-JIS 皮肤）
  gbk   → '嬥栐.png'    ← 能解码但是乱码
```

`OA_DX_03.zip` 就属于这种（没有 UTF-8 标志位 + Shift-JIS 名字）。此时"先抛异常先排除 + 设备语言
优先"会选中 GBK，产生乱码文件名。

**为什么通常无害**：LR2 皮肤大量使用通配符引用，例如

```text
#IMAGE,.\LR2files\Theme\OA_DX_03\lanecover\AC\*.png
```

按**目录**加载，不看具体文件名。实测 `OA_DX_03.zip` 的 15 个非 ASCII 名字里只有 2 个被文本
按名引用，其余靠通配符。真正需要精确匹配的场景（同一语言环境下的包）会被候选顺序正确处理。

另外，`#IMAGE` 之类的引用字节与条目名是**同一份字节**，所以只要名字与文本用同一编码解码，
两者就是一致的 —— 这也是"跨语言包里名字会乱但一般不缺图"的原因。

## 排查这类问题的可复用手段

不要靠猜，直接解析中央目录看原始字节（`zipfile` 模块会按 `0x800` 标志自动选编码，
反而看不到真相）：

```python
import struct
data = open('x.zip', 'rb').read()
i = data.rfind(b'PK\x05\x06')
total   = struct.unpack_from('<H', data, i+10)[0]
cd_off  = struct.unpack_from('<I', data, i+16)[0]
p = cd_off
for _ in range(total):
    flag = struct.unpack_from('<H', data, p+8)[0]
    nl   = struct.unpack_from('<H', data, p+28)[0]
    el   = struct.unpack_from('<H', data, p+30)[0]
    cl   = struct.unpack_from('<H', data, p+32)[0]
    raw  = data[p+46:p+46+nl]
    if any(b > 127 for b in raw):
        print(f'flag={flag:#06x}', raw)
        for enc in ('utf-8', 'gbk', 'cp932'):
            try:    print('   ', enc, raw.decode(enc))
            except Exception: print('   ', enc, 'FAIL')
    p += 46 + nl + el + cl
```

要点：

- `flag & 0x800` 非 0 → 条目名就是 UTF-8，别再猜。
- **每个候选编码分别统计"成功解码的条目数"**：只要有一个候选能全解，就说明那不是异常而是选错了编码。
- 判断哪个"正确"要看**归档内文本文件里的引用**（见上面通配符那节），而不是看哪个能解码。
- 中央目录里还有一个 Info-ZIP Unicode Path 扩展字段（`0x7075`）存"UTF-8 名"，
  但**它可能和条目名一样是错的**（打包工具先把 Shift-JIS 当 GBK 解、再把结果写成 UTF-8）。
  实测 `OA_DX_03.zip` 的 `0x7075` 存的就是乱码 `嬥栐`，所以不能盲信。

## 未改动但值得知道的地方

`SettingsActivity.importScoreFromUri()` 用 `new ZipInputStream(in)`（固定 UTF-8）导入成绩库。
它只读本 App 自己导出的 zip（`ZipOutputStream` + Java 默认 UTF-8，非 ASCII 名会带 `0x800` 标志），
而且已经 `catch (Exception)` 不会崩，所以本次未动。若以后要支持外部成绩 zip，把它也接到
`ZipCharsetSupport` 即可（需要先落到临时文件，因为 `ZipInputStream` 无法换编码重试）。
