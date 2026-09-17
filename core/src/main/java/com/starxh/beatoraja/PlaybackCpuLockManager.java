package com.starxh.beatoraja;

/**
 * {@link PlaybackCpuLock} 的全局注册点。
 *
 * <p>沿用 {@link AudioSpectrumManager} 的做法:core 定义接口 + 静态持有者,
 * 平台层(android)在初始化时注入实现,避免 core 反向依赖 Android SDK。</p>
 *
 * <p>没有注入实现时所有调用都是 no-op,桌面端(LWJGL)不受影响。</p>
 */
public class PlaybackCpuLockManager {

	private static volatile PlaybackCpuLock globalLock = null;

	private PlaybackCpuLockManager() {
	}

	public static void setGlobalLock(PlaybackCpuLock lock) {
		globalLock = lock;
	}

	public static PlaybackCpuLock getGlobalLock() {
		return globalLock;
	}

	/** 便捷方法:获取 CPU 锁,未注入实现时静默跳过。 */
	public static void acquire(Object owner, String tag) {
		PlaybackCpuLock lock = globalLock;
		if (lock != null) {
			try {
				lock.acquire(owner, tag);
			} catch (Throwable t) {
				// 保活失败不应该影响播放本身
			}
		}
	}

	/** 便捷方法:释放 CPU 锁,未注入实现时静默跳过。 */
	public static void release(Object owner) {
		PlaybackCpuLock lock = globalLock;
		if (lock != null) {
			try {
				lock.release(owner);
			} catch (Throwable t) {
				// 同上
			}
		}
	}
}
