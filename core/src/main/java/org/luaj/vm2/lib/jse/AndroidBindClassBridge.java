package org.luaj.vm2.lib.jse;

import org.luaj.vm2.LuaValue;

/**
 * 把 luaj 的 bindClass 能力暴露给包外代码的桥接类。
 *
 * <p><b>为什么必须放在 {@code org.luaj.vm2.lib.jse} 这个包里</b>：
 * luaj 3.0.1 的 {@code JavaClass} 是<b>包私有类</b>，它的
 * {@code static JavaClass forClass(Class)} 也是<b>包私有静态方法</b>。
 * 包外代码只能靠反射去够它，而这里有连续两个坑：</p>
 *
 * <ol>
 *   <li>{@code Class.getMethod("forClass", Class.class)} 只搜 <b>public</b> 方法，
 *       对包私有方法永远抛 {@code NoSuchMethodException}。{@code SkinLuaAccessor}
 *       里原来那段包装就是这么写的 —— 所以即便找到了 luajava 表，它也一次都没成功过，
 *       每次都静默回退到 luaj 原始的 bindClass（用 JVM 默认 ClassLoader，
 *       Android 上找不到 APK 里的类），表现就是
 *       {@code ClassNotFoundException: com.badlogic.gdx.Gdx}。</li>
 *   <li>退一步改用 {@code getDeclaredMethod + setAccessible} 又会踩 R8：方法名只以
 *       字符串形式出现在代码里，混淆器看不到任何引用，改名或内联都不会有人拦。</li>
 * </ol>
 *
 * <p>放进同一个包，编译期就能直接访问，不依赖反射、也不需要额外的 keep 规则。
 * 代价是耦合了 luaj 的内部结构 —— 升 luaj 版本时这里会编译失败，那正是想要的信号
 * （比运行时静默失效好得多）。</p>
 */
public final class AndroidBindClassBridge {

    private AndroidBindClassBridge() {
    }

    /**
     * 等价于 {@code luajava.bindClass(name)}，但改用调用方指定的 ClassLoader。
     *
     * @param name   完整类名
     * @param loader 优先使用的 ClassLoader；Android 上应传 app 的 ClassLoader
     * @return luaj 的 JavaClass 实例（对 Lua 侧就是一个类对象）
     * @throws ClassNotFoundException 两个 ClassLoader 都找不到该类
     */
    public static LuaValue bindClass(String name, ClassLoader loader) throws ClassNotFoundException {
        Class<?> cls;
        try {
            cls = Class.forName(name, true, loader);
        } catch (ClassNotFoundException notFound) {
            // 兜底再试调用方 ClassLoader。Android 上多数情况两者是同一个,
            // 但多 dex / 插件化环境不一定。
            cls = Class.forName(name);
        }
        return JavaClass.forClass(cls);
    }
}
