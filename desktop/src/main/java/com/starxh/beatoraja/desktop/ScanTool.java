package com.starxh.beatoraja.desktop;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Files;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rebuild songdata.db from the configured BMS roots, without starting the game.
 *
 * Useful on its own and much easier to verify than driving the in-game menu.
 *
 * core's Config.read() is deliberately not used here: it reads through Gdx.files and logs
 * through Gdx.app, so a headless tool would have to stand up most of an Application just to
 * learn one setting. The bmsroot list is read straight out of config.json instead.
 *
 * Usage: ./gradlew :desktop:scanSongs
 */
public final class ScanTool {

    private static final Pattern BMSROOT =
            Pattern.compile("\"bmsroot\"\\s*:\\s*\\[(.*?)]", Pattern.DOTALL);
    private static final Pattern STRING_ITEM = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    private ScanTool() {
    }

    public static void main(String[] args) throws Exception {
        final File root = new File(System.getProperty("beatoraja.root", ".")).getAbsoluteFile();
        final File config = new File(root, "config.json");
        if (!config.isFile()) {
            System.err.println("[scan] no config.json under " + root);
            System.exit(2);
        }

        // ChartDecoder reads charts through Gdx.files, so the file backend has to exist.
        // Gdx.app is not needed: nothing on this path logs through it.
        Gdx.files = new Lwjgl3Files();

        final String[] bmsroot = readBmsRoot(config);
        if (bmsroot.length == 0) {
            System.err.println("[scan] no bmsroot configured in " + config);
            System.exit(2);
        }

        System.out.println("[scan] root = " + root);
        for (String r : bmsroot) {
            System.out.println("[scan] bmsroot = " + r);
        }

        final File db = new File(root, "songdata.db");
        final JdbcSongDatabaseAccessor accessor =
                new JdbcSongDatabaseAccessor(db.getAbsolutePath(), bmsroot);
        accessor.updateSongDatas(null, bmsroot, true, (scanned, total) -> {
            if (scanned % 10 == 0) {
                System.out.println("[scan] " + scanned + " charts");
            }
        });
    }

    private static String[] readBmsRoot(File config) throws Exception {
        final String text = new String(Files.readAllBytes(config.toPath()), StandardCharsets.UTF_8);
        final Matcher block = BMSROOT.matcher(text);
        if (!block.find()) {
            return new String[0];
        }
        final List<String> out = new ArrayList<>();
        final Matcher item = STRING_ITEM.matcher(block.group(1));
        while (item.find()) {
            out.add(item.group(1).replace("\\\\", "\\").replace("\\\"", "\""));
        }
        return out.toArray(new String[0]);
    }
}
