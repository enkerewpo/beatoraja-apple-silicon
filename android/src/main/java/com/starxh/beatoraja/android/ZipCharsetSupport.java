package com.starxh.beatoraja.android;

import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * zip 条目名编码兜底。
 *
 * <h3>为什么需要这个</h3>
 * zip 的中央目录里，条目名只是"一串字节"：只有当条目带 UTF-8 标志位（general purpose
 * flag bit 11 = 0x800）时才一定是 UTF-8，否则要看打包方的本地代码页
 * （Windows 中文 = GBK/CP936，日文 = MS932/Shift-JIS）。
 *
 * <p>Android 的 {@link ZipFile#ZipFile(File, Charset)} 会用**构造时传入的编码解码所有
 * 条目名**（实测不检查 per-entry 的 0x800 标志），所以一旦猜错：
 * {@code ZipCoder.toString()} 抛 {@code MalformedInputException}，并被包成
 * <b>unchecked 的 IllegalArgumentException</b> —— {@code catch (IOException)} 抓不到，
 * 会一路逃出后台线程把进程杀掉（实测崩溃栈：
 * {@code ZipFile$Source.initCEN → ZipCoder.toString}）。</p>
 *
 * <p>所以不能只按设备语言猜一次，必须"逐个候选试到能完整解码为止"。</p>
 *
 * <h3>候选顺序</h3>
 * <ol>
 *   <li>UTF-8 —— 现代 zip 的事实标准（Windows 10+ 资源管理器、7-Zip "UTF-8" 选项）。</li>
 *   <li>按设备语言偏好的 CJK 编码（zh→GBK、ja→MS932）。</li>
 *   <li>另一种 CJK 编码 —— 覆盖"在中文机上读日文皮肤包"这类跨语言场景。</li>
 *   <li>ISO-8859-1 —— 永不抛异常（字节 1:1 映射到字符），作为"宁可名字乱码也不崩"的兜底。</li>
 * </ol>
 *
 * <h3>已知局限</h3>
 * GBK 与 MS932 对同一串字节**可能都解码成功但结果不同**（例如 Shift-JIS 的"金網"
 * 会被 GBK 解成"嬥栐"）。没有内容级交叉验证就无法 100% 判定，这里靠"先抛异常的先排除 +
 * 设备语言优先"来选。"能解码但选了另一种"的结果是文件名乱码 —— 由于 LR2 皮肤大量使用
 * {@code #IMAGE,…,lanecover\AC\*.png} 这类<b>通配符</b>引用（按目录加载，不看具体文件名），
 * 实际影响通常只是显示难看。选中哪种编码会写进 logcat（tag {@code ZipCharset}）。
 */
public final class ZipCharsetSupport {

    private static final String TAG = "ZipCharset";

    private ZipCharsetSupport() {
    }

    /** 候选编码列表，按尝试优先级排列。 */
    public static List<Charset> candidates() {
        List<Charset> list = new ArrayList<>(4);
        list.add(StandardCharsets.UTF_8);

        String lang = Locale.getDefault().getLanguage();
        if ("ja".equals(lang)) {
            addCharset(list, "MS932");
            addCharset(list, "GBK");
        } else {
            addCharset(list, "GBK");
            addCharset(list, "MS932");
        }

        list.add(StandardCharsets.ISO_8859_1);
        return list;
    }

    private static void addCharset(List<Charset> list, String name) {
        try {
            Charset cs = Charset.forName(name);
            if (!list.contains(cs)) {
                list.add(cs);
            }
        } catch (Exception ignored) {
            // 某些精简 ROM 可能没有该编码，跳过即可
        }
    }

    /**
     * 打开 zip，自动挑选能正确解码条目名的编码。
     *
     * @throws IOException 所有候选编码都无法解码中央目录时抛出（调用方按普通 IO 失败处理即可，
     *                     不会再抛 unchecked 异常）
     */
    public static ZipFile open(File file) throws IOException {
        Exception lastError = null;
        ZipFile degenerate = null;

        for (Charset cs : candidates()) {
            ZipFile zip = null;
            try {
                zip = openWithCharset(file, cs);
                if (namesLookSane(zip)) {
                    Log.i(TAG, "opened " + file.getName() + " with " + cs.name());
                    return zip;
                }
                // 能打开但名字里有替换字符/控制字符 —— 记下第一个，若后面全都不行再用
                if (degenerate == null) {
                    degenerate = zip;
                } else {
                    closeQuietly(zip);
                }
            } catch (Exception e) {
                lastError = e;
                closeQuietly(zip);
            }
        }

        if (degenerate != null) {
            Log.w(TAG, "no clean charset for " + file.getName() + ", falling back to decoding with mojibake risk");
            return degenerate;
        }
        throw new IOException("zip entry names are not decodable with any candidate charset: "
                + file.getName(), lastError);
    }

    private static ZipFile openWithCharset(File file, Charset cs) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            return new ZipFile(file, cs);
        }
        // API 24 以下没有带 Charset 的构造器，只能用平台默认（UTF-8）
        return new ZipFile(file);
    }

    /**
     * 条目名看起来是否"正常"：不含 U+FFFD（解码失败替换符）与 C0/C1 控制字符。
     * 文件名里出现控制字符在任何平台上都是非法的，可用来识别明显的解错。
     */
    private static boolean namesLookSane(ZipFile zip) {
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            for (int i = 0; i < name.length(); i++) {
                char c = name.charAt(i);
                if (c == '\uFFFD' || (c < 0x20 && c != '\t') || (c >= 0x7F && c <= 0x9F)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void closeQuietly(ZipFile zip) {
        if (zip != null) {
            try {
                zip.close();
            } catch (Exception ignored) {
            }
        }
    }
}
