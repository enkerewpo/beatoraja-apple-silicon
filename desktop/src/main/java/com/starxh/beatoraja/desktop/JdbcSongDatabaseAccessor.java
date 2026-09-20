package com.starxh.beatoraja.desktop;

import bms.player.beatoraja.SQLiteDatabaseAccessor;
import bms.player.beatoraja.song.FolderData;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;

import org.apache.commons.dbutils.QueryRunner;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * JDBC song database reader for desktop.
 *
 * Upstream's SQLiteSongDatabaseAccessor (1542 lines) was not ported directly: the core here
 * dropped SongReview, SongArchive extraction and SongInformationAccessor for the Android
 * port, and upstream's file references them in 112 places. Cutting 36 feature sites out of
 * someone else's large file is slow and risks leaving subtle damage in the scan logic.
 *
 * This implements only the read paths the interface requires, against beatoraja's existing
 * songdata.db (same schema as upstream: song / folder). Song scanning is not implemented.
 */
final class JdbcSongDatabaseAccessor implements SongDatabaseAccessor {

    private static final SongData[] NO_SONGS = new SongData[0];
    private static final FolderData[] NO_FOLDERS = new FolderData[0];

    private final String url;
    private final QueryRunner runner = new QueryRunner();
    private final String[] bmsroot;

    JdbcSongDatabaseAccessor(String dbPath, String[] bmsroot) {
        this.url = "jdbc:sqlite:" + dbPath;
        this.bmsroot = bmsroot == null ? new String[0] : bmsroot.clone();
        System.out.println("[desktop] songdata.db = " + dbPath);
    }

    private Connection open() throws SQLException {
        return DriverManager.getConnection(url);
    }

    private SongData[] query(String sql, Object... params) {
        try (Connection c = open()) {
            List<SongData> list = runner.query(c, sql,
                    new SQLiteDatabaseAccessor.AndroidBeanListHandler<>(SongData.class), params);
            return list == null ? NO_SONGS : list.toArray(new SongData[0]);
        } catch (SQLException e) {
            System.err.println("[desktop] song query failed: " + e.getMessage() + "  sql=" + sql);
            return NO_SONGS;
        }
    }

    @Override
    public SongData[] getSongDatas(String key, String value) {
        // key is a fixed column name from core, never user input
        return query("SELECT * FROM song WHERE " + key + " = ?", value);
    }

    @Override
    public SongData[] getSongDatas() {
        return query("SELECT * FROM song");
    }

    @Override
    public SongData[] getSongDatas(String[] hashes) {
        if (hashes == null || hashes.length == 0) {
            return NO_SONGS;
        }
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < hashes.length; i++) {
            in.append(i == 0 ? "?" : ",?");
        }
        // charts may be indexed by sha256 or by md5
        String sql = "SELECT * FROM song WHERE sha256 IN (" + in + ") OR md5 IN (" + in + ")";
        Object[] params = new Object[hashes.length * 2];
        System.arraycopy(hashes, 0, params, 0, hashes.length);
        System.arraycopy(hashes, 0, params, hashes.length, hashes.length);
        return query(sql, params);
    }

    @Override
    public SongData[] getSongDatas(String sql, String score, String scorelog) {
        // Note: what arrives is a WHERE fragment, not a full statement, e.g.
        //   "playcount > 0 ORDER BY playcount DESC LIMIT 10" or "favorite & 1 != 0".
        // Columns like playcount live in the score database and need an ATTACH first.
        try (Connection c = open()) {
            if (score != null && !score.isEmpty()) {
                runner.update(c, "ATTACH DATABASE ? AS score", score);
            }
            if (scorelog != null && !scorelog.isEmpty()) {
                runner.update(c, "ATTACH DATABASE ? AS scorelog", scorelog);
            }
            List<SongData> list = runner.query(c, "SELECT * FROM song WHERE " + sql,
                    new SQLiteDatabaseAccessor.AndroidBeanListHandler<>(SongData.class));
            return list == null ? NO_SONGS : list.toArray(new SongData[0]);
        } catch (SQLException e) {
            // expected when the fragment references score columns and no score DB exists
            return NO_SONGS;
        }
    }

    @Override
    public SongData[] getSongDatasByText(String text) {
        String like = "%" + text + "%";
        return query("SELECT * FROM song WHERE title LIKE ? OR subtitle LIKE ? OR artist LIKE ?"
                + " OR subartist LIKE ? OR genre LIKE ?", like, like, like, like, like);
    }

    @Override
    public FolderData[] getFolderDatas(String key, String value) {
        try (Connection c = open()) {
            List<FolderData> list = runner.query(c, "SELECT * FROM folder WHERE " + key + " = ?",
                    new SQLiteDatabaseAccessor.AndroidBeanListHandler<>(FolderData.class), value);
            return list == null ? NO_FOLDERS : list.toArray(new FolderData[0]);
        } catch (SQLException e) {
            System.err.println("[desktop] folder query failed: " + e.getMessage());
            return NO_FOLDERS;
        }
    }

    @Override
    public void setSongDatas(SongData[] songs) {
        if (songs == null || songs.length == 0) {
            return;
        }
        final String sql = "INSERT OR REPLACE INTO song"
                + " (md5, sha256, title, subtitle, genre, artist, subartist, tag, path, folder,"
                + "  stagefile, banner, backbmp, preview, parent, level, difficulty, maxbpm, minbpm,"
                + "  length, mode, judge, feature, content, date, favorite, adddate, notes, charthash)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = open()) {
            c.setAutoCommit(false);
            final long now = System.currentTimeMillis() / 1000L;
            for (SongData s : songs) {
                runner.update(c, sql,
                        s.getMd5(), s.getSha256(), s.getTitle(), s.getSubtitle(), s.getGenre(),
                        s.getArtist(), s.getSubartist(), s.getTag(), s.getPath(), s.getFolder(),
                        s.getStagefile(), s.getBanner(), s.getBackbmp(), s.getPreview(),
                        s.getParent(), s.getLevel(), s.getDifficulty(), s.getMaxbpm(), s.getMinbpm(),
                        s.getLength(), s.getMode(), s.getJudge(), s.getFeature(), s.getContent(),
                        now, s.getFavorite(), now, s.getNotes(), s.getCharthash());
            }
            c.commit();
        } catch (SQLException e) {
            System.err.println("[desktop] writing songs failed: " + e.getMessage());
        }
    }

    /** Insert the directories a scan found, so the select screen can build its tree. */
    private void writeFolders(java.util.Map<String, String> dirs, String[] bmsroot) {
        if (dirs == null || dirs.isEmpty()) {
            return;
        }
        final String sql = "INSERT OR REPLACE INTO folder"
                + " (title, subtitle, command, path, banner, parent, type, date, adddate, max)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?)";
        try (Connection c = open()) {
            c.setAutoCommit(false);
            final long now = System.currentTimeMillis() / 1000L;
            for (java.util.Map.Entry<String, String> e : dirs.entrySet()) {
                final java.io.File dir = new java.io.File(e.getKey());
                // beatoraja stores folder paths with a trailing separator, and identifies the
                // parent by the hash of the absolute path above it (see SongScanner).
                runner.update(c, sql,
                        e.getValue(), "", "", e.getKey() + java.io.File.separator, "",
                        bms.player.beatoraja.song.SongUtils.crc32(dir.getParent(), bmsroot, ""),
                        0, now, now, 0);
            }
            c.commit();
        } catch (SQLException e) {
            System.err.println("[desktop] writing folders failed: " + e.getMessage());
        }
    }

    /** The configured root that a path lives under, or empty if none matches. */
    private static String matchingRoot(String path, String[] bmsroot) {
        String best = "";
        if (bmsroot != null) {
            for (String r : bmsroot) {
                if (r != null && path.startsWith(r) && r.length() > best.length()) {
                    best = r;
                }
            }
        }
        return best;
    }

    @Override
    public void updateSongTail(String sha256, int tail) {
        try (Connection c = open()) {
            runner.update(c, "UPDATE song SET feature = feature WHERE sha256 = ?", sha256);
        } catch (SQLException e) {
            // not on a critical path, ignore
        }
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll) {
        updateSongDatas(updatepath, bmsroot, updateAll, null);
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll,
                                SongScanProgress progress) {
        final String[] roots = (bmsroot == null || bmsroot.length == 0) ? this.bmsroot : bmsroot;
        // updatepath narrows the scan to one directory; null means everything
        final String[] targets = (updatepath == null || updatepath.isEmpty())
                ? roots : new String[] { updatepath };

        final long t0 = System.nanoTime();
        final SongScanner scanner = new SongScanner(targets, progress);
        scanner.scan();

        writeFolders(scanner.getFolders(), roots);
        setSongDatas(scanner.getSongs().toArray(new SongData[0]));

        System.out.printf("[desktop] scan finished: %d charts, %d folders, %d ms%n",
                scanner.getSongs().size(), scanner.getFolders().size(),
                (System.nanoTime() - t0) / 1_000_000L);
        if (progress != null) {
            progress.onFileScanned(scanner.getSongs().size(), scanner.getSongs().size());
        }
    }

    @Override
    public String[] getBmsRoot() {
        return bmsroot.clone();
    }
}
