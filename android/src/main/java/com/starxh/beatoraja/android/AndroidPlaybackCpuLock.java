package com.starxh.beatoraja.android;

import android.content.Context;
import android.os.PowerManager;
import android.util.Log;

import com.starxh.beatoraja.PlaybackCpuLock;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * {@link PlaybackCpuLock} 的 Android 实现 —— 持有一个 PARTIAL_WAKE_LOCK。
 *
 * <p>PARTIAL_WAKE_LOCK 只保证 CPU 继续跑,不点亮屏幕/键盘背光,正是"锁屏后继续播放"
 * 需要的语义。屏幕熄灭后系统默认会让 CPU 进入低功耗并把普通线程唤醒精度放粗,
 * 音符调度线程(BGAutoplayThread)会因此晚点甚至被判定为 Starve 而整批丢音。</p>
 *
 * <p>引用计数按 owner 去重:MusicPlayer 的 create()/切歌路径可能重复 acquire,
 * 只要 terminatePlayback() 里 release 一次就该真正放开。
 * 另外每次 acquire 都带超时兜底,万一 release 被漏掉也不会永久耗电。</p>
 */
public class AndroidPlaybackCpuLock implements PlaybackCpuLock {

    private static final String TAG = "PlaybackCpuLock";
    private static final String LOCK_TAG = "beatoraja:MusicPlayerPlayback";
    /** 兜底超时:即使 release 被漏掉,最多也就多耗这么多电 */
    private static final long TIMEOUT_MS = 60L * 60L * 1000L;

    private final PowerManager.WakeLock wakeLock;
    private final Set<Object> owners =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());

    public AndroidPlaybackCpuLock(Context context) {
        PowerManager.WakeLock lock = null;
        try {
            PowerManager pm = (PowerManager) context.getApplicationContext()
                    .getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                // 非引用计数模式:由本类自己按 owner 去重,语义更清晰
                lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, LOCK_TAG);
                lock.setReferenceCounted(false);
            }
        } catch (Throwable t) {
            Log.w(TAG, "Failed to create WakeLock, playback may stutter when screen is off", t);
        }
        this.wakeLock = lock;
    }

    @Override
    public synchronized void acquire(Object owner, String tag) {
        if (wakeLock == null) return;
        try {
            if (owner != null && !owners.add(owner)) {
                // 同一 owner 重复 acquire —— 引用计数不增加,只续一下超时
                wakeLock.acquire(TIMEOUT_MS);
                return;
            }
            wakeLock.acquire(TIMEOUT_MS);
            Log.i(TAG, "CPU lock acquired (" + tag + "), owners=" + owners.size());
        } catch (Throwable t) {
            Log.w(TAG, "acquire failed", t);
        }
    }

    @Override
    public synchronized void release(Object owner) {
        if (wakeLock == null) return;
        try {
            if (owner != null && !owners.remove(owner)) {
                // 没有配对的 acquire —— 忽略,不要误放别人的锁
                return;
            }
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Log.i(TAG, "CPU lock released, remaining owners=" + owners.size());
            }
        } catch (Throwable t) {
            Log.w(TAG, "release failed", t);
        }
    }
}
