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
        // writes belong to the scan flow, not implemented
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
        System.out.println("[desktop] song scanning is not implemented; build songdata.db with upstream beatoraja first");
        if (progress != null) {
            progress.onFileScanned(0, 0);
        }
    }

    @Override
    public String[] getBmsRoot() {
        return bmsroot.clone();
    }
}
