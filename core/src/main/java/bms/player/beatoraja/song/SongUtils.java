package bms.player.beatoraja.song;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.zip.CRC32;

public class SongUtils {

    private static final String ROOT_CRC = "e2977170";

    /**
     * 计算路径的 CRC32 值。
     * 兼容 beatoraja 的路径处理逻辑，同时使用 Java 标准库进行优化。
     */
    /**
     * Hash a folder path the way beatoraja does, so that a songdata.db written by upstream
     * beatoraja and one written here are interchangeable.
     *
     * [desktop] This used to differ from upstream in two ways that were added here and are
     * not in the original: it rebased the path against the *parent* of bmspath, and it
     * converted '/' to '\\'. Both change the hash, so the same directory produced a
     * different CRC, the folder hierarchy stopped matching and every chart disappeared.
     *
     * Upstream (SongUtils.crc32) hashes the path as given - forward slashes preserved,
     * only stripped when it actually starts with bmspath - followed by a backslash and a
     * NUL byte, with the standard CRC-32 polynomial. Verified against a database built by
     * beatoraja 0.8.8: all six chart folders and the root folder hash identically.
     */
    public static String crc32(String path, String[] rootdirs, String bmspath) {
        if (path == null) return "0";

        // A directory that is the parent of a configured BMS root is the tree root.
        if (rootdirs != null) {
            for (String s : rootdirs) {
                if (s == null) continue;
                try {
                    String parent = java.nio.file.Paths.get(s).toAbsolutePath().getParent().toString();
                    if (parent.equals(path)) {
                        return ROOT_CRC;
                    }
                } catch (Exception ignored) {
                    // malformed root entry, skip it
                }
            }
        }

        String target = path;
        if (bmspath != null && !bmspath.isEmpty()
                && target.startsWith(bmspath) && target.length() > bmspath.length()) {
            target = target.substring(bmspath.length() + 1);
        }

        final CRC32 crc32 = new CRC32();
        crc32.update((target + "\\\0").getBytes(StandardCharsets.UTF_8));
        return Integer.toHexString((int) crc32.getValue());
    }
}
