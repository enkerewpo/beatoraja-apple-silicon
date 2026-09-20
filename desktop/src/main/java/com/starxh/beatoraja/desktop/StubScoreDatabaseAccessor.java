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
 * 临时桩件：成绩数据库。
 *
 * 与 {@link StubSongDatabaseAccessor} 同理，用于先打通桌面端启动路径。
 * 真正的实现要从上游 exch-bms2/beatoraja 的 SQLiteScoreDatabaseAccessor 移植
 * （core 里已有 SQLiteDatabaseAccessor 提供 JDBC 基础设施可复用）。
 *
 * 注意 getPlayerData() 不能返回 null —— MusicSelector.create() 会直接解引用。
 */
final class StubScoreDatabaseAccessor extends ScoreDatabaseAccessor {

    private final PlayerData playerData = new PlayerData();
    private PlayerInformation information = new PlayerInformation();

    @Override
    public void createTable() {
        // 桩件无持久化
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
        // 无成绩可回调
    }

    @Override
    public List<ScoreData> getScoreDatas(String sql) {
        return new ArrayList<>();
    }

    @Override
    public void setScoreData(ScoreData[] scores) {
        // 桩件无持久化
    }

    @Override
    public void setScoreData(Map<String, Map<String, Object>> map) {
        // 桩件无持久化
    }

    @Override
    public void deleteScoreData(String sha256, int mode) {
        // 桩件无持久化
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
        // 桩件无持久化
    }

    @Override
    public void setScoreLog(ScoreLog log) {
        // 桩件无持久化
    }

    @Override
    public void setScoreDataLog(ScoreData[] scores) {
        // 桩件无持久化
    }
}
