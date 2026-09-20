package com.starxh.beatoraja.desktop;

import bms.player.beatoraja.song.FolderData;
import bms.player.beatoraja.song.SongData;
import bms.player.beatoraja.song.SongDatabaseAccessor;

/**
 * Temporary stub, kept only as a fallback. The real reader is JdbcSongDatabaseAccessor.
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
        // stub, nothing is persisted
    }

    @Override
    public void updateSongTail(String sha256, int tail) {
        // stub, nothing is persisted
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
        System.out.println("[desktop] song scanning is not implemented (stub)");
    }

    @Override
    public void updateSongDatas(String updatepath, String[] bmsroot, boolean updateAll, SongScanProgress progress) {
        System.out.println("[desktop] song scanning is not implemented (stub)");
        if (progress != null) {
            progress.onFileScanned(0, 0);
        }
    }

    @Override
    public String[] getBmsRoot() {
        return new String[0];
    }
}
