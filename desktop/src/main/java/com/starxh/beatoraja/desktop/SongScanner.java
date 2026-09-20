package com.starxh.beatoraja.desktop;

import bms.model.BMSModel;
import bms.model.ChartDecoder;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor.SongScanProgress;
import bms.player.beatoraja.song.SongUtils;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Walks the configured BMS roots, parses every chart and returns the rows to store.
 *
 * Upstream's scanner was not ported: it is 1542 lines and leans on SongReview, SongArchive
 * extraction and SongInformationAccessor, all of which this core dropped for the Android
 * port. This covers the plain case - charts sitting in directories on disk - which is what
 * the desktop build needs.
 *
 * Folder identity follows SongUtils.crc32, so the rows written here are interchangeable with
 * a database written by upstream beatoraja.
 */
final class SongScanner {

    /** Extensions ChartDecoder can handle. */
    private static final String[] CHART_EXTENSIONS = { ".bms", ".bme", ".bml", ".pms", ".bmson" };

    private final String[] roots;
    private final SongScanProgress progress;
    private final AtomicInteger scanned = new AtomicInteger();

    /** Directories that contained at least one chart, keyed by absolute path. */
    private final Map<String, String> folders = new LinkedHashMap<>();
    private final List<SongData> songs = new ArrayList<>();

    SongScanner(String[] roots, SongScanProgress progress) {
        this.roots = roots == null ? new String[0] : roots.clone();
        this.progress = progress;
    }

    List<SongData> getSongs() {
        return songs;
    }

    /** Absolute directory path to its title, for the folder table. */
    Map<String, String> getFolders() {
        return folders;
    }

    void scan() {
        for (String root : roots) {
            if (root == null || root.isEmpty()) {
                continue;
            }
            final File dir = new File(root);
            if (!dir.isDirectory()) {
                System.err.println("[scan] not a directory, skipped: " + root);
                continue;
            }
            // The root itself needs a folder row, otherwise it never shows up in the tree
            folders.putIfAbsent(dir.getAbsolutePath(), dir.getName());
            walk(dir.toPath(), root);
        }
        System.out.println("[scan] " + songs.size() + " charts in " + folders.size() + " directories");
    }

    private void walk(Path dir, String root) {
        final File[] entries = dir.toFile().listFiles();
        if (entries == null) {
            return;
        }
        for (File entry : entries) {
            if (entry.isDirectory()) {
                walk(entry.toPath(), root);
            } else if (isChart(entry.getName())) {
                readChart(entry, root);
            }
        }
    }

    private static boolean isChart(String name) {
        final String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : CHART_EXTENSIONS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private void readChart(File file, String root) {
        try {
            final ChartDecoder decoder = ChartDecoder.getDecoder(file.getAbsolutePath());
            if (decoder == null) {
                return;
            }
            final BMSModel model = decoder.decode(file);
            if (model == null) {
                return;
            }

            final File parentDir = file.getParentFile();
            final String parentPath = parentDir.getAbsolutePath();

            // A chart carries a text file alongside it often enough that core has a flag for it
            final boolean hasText = new File(parentDir, "LICENSE.txt").isFile()
                    || new File(parentDir, "readme.txt").isFile();

            final SongData song = new SongData(model, hasText);
            song.setPath(file.getAbsolutePath());
            // folder identifies the directory the chart sits in; parent is the level above.
            // bmspath is deliberately empty: beatoraja 0.8.8 stores these as hashes of the
            // absolute path, and passing a root here would strip it to a relative one and
            // produce values its database does not agree with.
            song.setFolder(SongUtils.crc32(parentPath, roots, ""));
            song.setParent(SongUtils.crc32(parentDir.getParent(), roots, ""));
            songs.add(song);

            folders.putIfAbsent(parentPath, parentDir.getName());

            final int done = scanned.incrementAndGet();
            if (progress != null) {
                progress.onFileScanned(done, -1);
            }
        } catch (Exception e) {
            // A single unreadable chart must not abort the whole scan
            System.err.println("[scan] failed to read " + file + ": " + e);
        }
    }

    /** Best-effort check that a path is readable before walking into it. */
    static boolean readable(Path p) {
        try {
            return Files.isReadable(p);
        } catch (Exception e) {
            return false;
        }
    }
}
