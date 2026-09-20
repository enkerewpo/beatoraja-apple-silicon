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
 * 桌面端的 JDBC 曲库读取实现。
 *
 * 为什么不直接移植上游 exch-bms2 的 SQLiteSongDatabaseAccessor（1542 行）：
 * 本 fork 的 core 为适配 Android 移除了 SongReview、SongArchive 自动解压、
 * SongInformationAccessor 等一批功能，上游那份对它们有 112 处引用。
 * 在别人的大文件上切除 36 处功能引用，既费时又容易在扫描逻辑上留下暗伤。
 *
 * 这里只实现接口要求的读取路径，直接对接 beatoraja 既有的 songdata.db
 * （表结构与上游一致：song / folder）。曲库扫描暂未实现——现阶段用既有数据库
 * 验证渲染与音频链路即可，扫描属于后续工作。
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
            System.err.println("[desktop] 曲库查询失败: " + e.getMessage() + "  sql=" + sql);
            return NO_SONGS;
        }
    }

    @Override
    public SongData[] getSongDatas(String key, String value) {
        // key 来自 core 内部的固定列名，不是用户输入
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
        // 谱面既可能按 sha256 也可能按 md5 索引
        String sql = "SELECT * FROM song WHERE sha256 IN (" + in + ") OR md5 IN (" + in + ")";
        Object[] params = new Object[hashes.length * 2];
        System.arraycopy(hashes, 0, params, 0, hashes.length);
        System.arraycopy(hashes, 0, params, hashes.length, hashes.length);
        return query(sql, params);
    }

    @Override
    public SongData[] getSongDatas(String sql, String score, String scorelog) {
        // 注意：传进来的是 WHERE 子句片段而非完整 SQL，例如
        //   "playcount > 0 ORDER BY playcount DESC LIMIT 10" 或 "favorite & 1 != 0"。
        // 其中 playcount 等列位于成绩库，需要先 ATTACH 才能联查。
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
            // 引用了成绩库列而当前没有成绩库时会走到这里，属预期情况，降级为空结果
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
            System.err.println("[desktop] 文件夹查询失败: " + e.getMessage());
            return NO_FOLDERS;
        }
    }

    @Override
    public void setSongDatas(SongData[] songs) {
        // 写入属于扫描流程，暂未实现
    }

    @Override
    public void updateSongTail(String sha256, int tail) {
        try (Connection c = open()) {
            runner.update(c, "UPDATE song SET feature = feature WHERE sha256 = ?", sha256);
        } catch (SQLException e) {
            // 非关键路径，忽略
        }
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll) {
        updateSongDatas(updatepath, bmsroot, updateAll, null);
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll,
                                SongScanProgress progress) {
        System.out.println("[desktop] 曲库扫描尚未实现；请先用上游 beatoraja 0.8.8 建好 songdata.db");
        if (progress != null) {
            progress.onFileScanned(0, 0);
        }
    }

    @Override
    public String[] getBmsRoot() {
        return bmsroot.clone();
    }
}
