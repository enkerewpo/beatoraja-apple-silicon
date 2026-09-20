package com.starxh.beatoraja.desktop;

import bms.player.beatoraja.PlayerData;
import bms.player.beatoraja.PlayerInformation;
import bms.player.beatoraja.ScoreData;
import bms.player.beatoraja.ScoreDatabaseAccessor;
import bms.player.beatoraja.song.SongData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Score database stub. Scores are not persisted yet.
 *
 * A real implementation would port upstream's SQLiteScoreDatabaseAccessor; core already
 * provides SQLiteDatabaseAccessor with the JDBC plumbing to build on.
 *
 * getPlayerData() must not return null - MusicSelector.create() dereferences it directly.
 */
final class StubScoreDatabaseAccessor extends ScoreDatabaseAccessor {

    private final PlayerData playerData = new PlayerData();
    private PlayerInformation information = new PlayerInformation();

    @Override
    public void createTable() {
        // stub, nothing is persisted
    }

    @Override
    public PlayerInformation getInformation() {
        return information;
    }

    @Override
    public void setInformation(PlayerInformation info) {
        if (info != null) {
            this.information = info;
        }
    }

    @Override
    public ScoreData getScoreData(String hash, int mode) {
        return null;
    }

    @Override
    public void getScoreDatas(ScoreDataCollector collector, SongData[] songs, int mode) {
        // no scores to report
    }

    @Override
    public List<ScoreData> getScoreDatas(String sql) {
        return new ArrayList<>();
    }

    @Override
    public void setScoreData(ScoreData[] scores) {
        // stub, nothing is persisted
    }

    @Override
    public void setScoreData(Map<String, Map<String, Object>> map) {
        // stub, nothing is persisted
    }

    @Override
    public void deleteScoreData(String sha256, int mode) {
        // stub, nothing is persisted
    }

    @Override
    public PlayerData getPlayerData() {
        return playerData;
    }

    @Override
    public PlayerData[] getPlayerDatas(int count) {
        return new PlayerData[0];
    }

    @Override
    public void setPlayerData(PlayerData pd) {
        // stub, nothing is persisted
    }

    @Override
    public void setScoreLog(ScoreLog log) {
        // stub, nothing is persisted
    }

    @Override
    public void setScoreDataLog(ScoreData[] scores) {
        // stub, nothing is persisted
    }
}
