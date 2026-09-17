# Lua 脚本 `ClassNotFoundException: com.badlogic.gdx.Gdx`

## 现象

皮肤 Lua 脚本一加载就失败：

```text
Lua 加载/编译失败: @resultExpand.lua:4
vm error: java.lang.ClassNotFoundException: com.badlogic.gdx.Gdx
```

第 4 行正是 `luajava.bindClass("com.badlogic.gdx.Gdx")`。

## 根因：两层问题叠加

`SkinLuaAccessor.customizeLuajavaClassLoading()` 存在的意义就是修这个 —— 把
`luajava.bindClass` 换成走 app ClassLoader 的版本（luaj 默认用 JVM 默认 ClassLoader，
在 Android 上找不到 APK 里的类）。它坏了两处，**第二处才是致命的**。

### 第一层：找错了 luajava 表的位置

原实现只去找 `package.preload.luajava`：

```java
final LuaValue originalPreload = pkg.get("preload").get("luajava");
if (originalPreload.isnil() || !originalPreload.isfunction()) return;   // ← 在这里就返回了
```

而 luaj 3.0.1 的 `LuajavaLib.call()` 实际注册在（源码 114~115 行）：

```java
env.set("luajava", t);                                  // 全局变量
env.get("package").get("loaded").set("luajava", t);     // package.loaded
```

**没有 preload。** 实测：

```text
global  luajava  = table
preload.luajava  = nil      ← 原代码找的正是这里
loaded.luajava   = table
```

所以整段包装被直接跳过。

### 第二层（致命）：`JavaClass.forClass` 是包私有的，反射够不着

即使表位置找对了，包装体里这句也永远失败：

```java
Method m = Class.forName("org.luaj.vm2.lib.jse.JavaClass")
        .getMethod("forClass", Class.class);      // ← 永远抛 NoSuchMethodException
```

luaj 源码 `JavaClass.java` 第 49、59 行：

```java
class JavaClass extends JavaInstance implements CoerceJavaToLua.Coercion {   // 包私有类
    static JavaClass forClass(Class c) {                                    // 包私有方法
```

**类和它的工厂方法都是包私有的**，而 `Class.getMethod()` 只搜 **public** 方法 —— 必然抛
`NoSuchMethodException`。

加了诊断之后的真机日志正是如此：

```text
SkinLua: luajava bindClass wrapped at 1 site(s), appLoader=dalvik.system.PathClassLoader
SkinLua: luajava.bindClass fallback for com.badlogic.gdx.Gdx:
         java.lang.NoSuchMethodException: org.luaj.vm2.lib.jse.JavaClass.forClass [class java.lang.Class]
```

注意 `wrapped at 1 site(s)`：全局和 `package.loaded` 指向同一张表，包一次就够 ——
这个数字是**正常**的，说明包装确实生效了，失败发生在包装体内部。

改用 `getDeclaredMethod + setAccessible` 也治不了本：方法名只以字符串形式出现在代码里，
混淆器看不到任何引用，改名或内联都不会有人拦。

## 修复

把桥接类放进**同一个包**，用编译期访问替代反射：
`core/src/main/java/org/luaj/vm2/lib/jse/AndroidBindClassBridge.java`

```java
package org.luaj.vm2.lib.jse;

public final class AndroidBindClassBridge {
    public static LuaValue bindClass(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> cls;
        try {
            cls = Class.forName(name, true, loader);
        } catch (ClassNotFoundException notFound) {
            cls = Class.forName(name);      // 兜底:多 dex / 插件化环境两者可能不同
        }
        return JavaClass.forClass(cls);     // 同包,直接访问
    }
}
```

同包即可直接访问包私有成员，不依赖反射、也不需要额外的 keep 规则。
代价是耦合了 luaj 的内部结构 —— 升 luaj 版本时这里会**编译失败**，那正是想要的信号
（比运行时静默失效好得多）。

包装位置也覆盖全（全局 / `package.loaded` / `package.preload`），并用
`__android_classloader_wrapped` 标记保证即使三处指向同一张表也只包一次。

## 实测

用 `luaj-jse-3.0.1.jar` 在桌面 JVM 上按脚本同构的写法跑：

```text
luajava.bindClass('java.lang.System')  = userdata                    ← 拿到真正的 JavaClass
Sys.out                                = java.io.PrintStream@17c68925 ← 静态字段
Integer.MAX_VALUE                      = 2147483647                  ← 静态常量
Sys:currentTimeMillis()                = 1789624320430               ← 方法调用
```

## 附：luaj 的方法调用约定

实测中确认的一点，写脚本时会踩：

```lua
local Sys = luajava.bindClass('java.lang.System')

Sys.out                    -- ✓ 静态字段用点号
Sys:currentTimeMillis()    -- ✓ 方法必须用冒号(显式传 self)
Sys.currentTimeMillis()    -- ✗ bad argument: userdata expected, got nil
```

luaj 的 `JavaInstance.get()` 返回的方法包装需要 self，**点号调用会缺 self**。
`System.out.println(...)` 这类还会撞上重载解析（`println` 有 10 个重载），报的也是
`userdata expected` —— 那是 luaj 重载解析的报错方式，不是"类找不到"。

## 备注

- `proguard-rules.pro` 里已有 `-keep class com.badlogic.gdx.** { *; }`，**不是混淆问题**。
- 改的是 Java 代码，**必须重新构建 APK 才生效** —— 老安装包里这段逻辑仍然是坏的。
- 诊断日志走 `Gdx.app.log/error`，不用 `java.util.logging`（后者在 Android 上不保证进 logcat）：
  - `SkinLua: luajava bindClass wrapped at N site(s), appLoader=...`
  - `SkinLua: luajava.bindClass fallback for <类名>: <异常>`
