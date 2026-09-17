package com.starxh.beatoraja;

/**
 * 平台侧 CPU 保活接口。
 *
 * <p>为什么需要它:MusicPlayer 的音符调度不是跑在音频回调线程上,而是跑在一个普通
 * Java 线程({@code MusicPlayer-BGAutoplay})上 —— 它按时间轴睡醒后往 AudioDriver 里
 * 灌 note。这条链路的精度完全依赖宿主线程的调度精度。</p>
 *
 * <p>锁屏后 Android 会做两件事,都会直接打烂这条链路:
 * <ol>
 *   <li>CPU 进入低功耗,用户态线程唤醒间隔被拉长(10ms 的 sleep 实际可能几百毫秒才醒);</li>
 *   <li>进程被划入 background cgroup,CPU 份额被压缩,定时器 slack 被放大。</li>
 * </ol>
 * 表现就是用户听到的"音频不同步和停顿":轻则 note 晚点到(不同步),重则被
 * BGAutoplayThread 的 Starve 保护整批丢弃(听感上直接断一截)。</p>
 *
 * <p>Android 实现持有 {@code PARTIAL_WAKE_LOCK},保证屏幕关闭后 CPU 仍然全速运行。
 * core 层只依赖本接口,不碰 Android SDK。</p>
 */
public interface PlaybackCpuLock {

	/**
	 * 获取 CPU 锁。可重入 —— 同一 owner 重复 acquire 只增加一次引用计数。
	 *
	 * @param owner 持有者标识,用于配对 release;传 null 视为无主锁(需手动 release)
	 * @param tag   调试用标签,会写进平台日志
	 */
	void acquire(Object owner, String tag);

	/**
	 * 释放 CPU 锁。
	 *
	 * @param owner 与 {@link #acquire(Object, String)} 配对的持有者标识
	 */
	void release(Object owner);
}
