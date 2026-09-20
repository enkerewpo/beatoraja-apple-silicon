package com.starxh.beatoraja.desktop;

import bms.player.beatoraja.song.FolderData;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * 临时桩件：让桌面端启动路径先跑通，用于验证 LWJGL3 后端在 Apple Silicon 上能否原生开窗渲染。
 *
 * 真正的实现要从上游 exch-bms2/beatoraja 的 SQLiteSongDatabaseAccessor（约 1500 行 JDBC）
 * 移植过来，并补上本 fork 新增的 updateSongTail 与 SongScanProgress 回调。
 * 两边同为 GPL-3.0，移植合规。
 */
final class StubSongDatabaseAccessor implements SongDatabaseAccessor {

    private static final SongData[] NO_SONGS = new SongData[0];
    private static final FolderData[] NO_FOLDERS = new FolderData[0];

    @Override
    public SongData[] getSongDatas(String key, String value) {
        return NO_SONGS;
    }

    @Override
    public SongData[] getSongDatas() {
        return NO_SONGS;
    }

    @Override
    public SongData[] getSongDatas(String[] hashes) {
        return NO_SONGS;
    }

    @Override
    public SongData[] getSongDatas(String sql, String score, String scorelog) {
        return NO_SONGS;
    }

    @Override
    public void setSongDatas(SongData[] songs) {
        // 桩件不持久化
    }

    @Override
    public void updateSongTail(String sha256, int tail) {
        // 桩件不持久化
    }

    @Override
    public SongData[] getSongDatasByText(String text) {
        return NO_SONGS;
    }

    @Override
    public FolderData[] getFolderDatas(String key, String value) {
        return NO_FOLDERS;
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll) {
        System.out.println("[desktop] 曲库扫描未实现（桩件）");
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll, SongScanProgress progress) {
        System.out.println("[desktop] 曲库扫描未实现（桩件）");
        if (progress != null) {
            progress.onFileScanned(0, 0);
        }
    }

    @Override
    public String[] getBmsRoot() {
        return new String[0];
    }
}
