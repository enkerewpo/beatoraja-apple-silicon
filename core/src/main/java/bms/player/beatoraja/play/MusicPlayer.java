package bms.player.beatoraja.play;

import java.io.File;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.glutils.ShapeRenderer;
import com.badlogic.gdx.utils.Array;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import bms.model.BMSModel;
import bms.model.Note;
import bms.model.TimeLine;
import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.PixmapResourcePool;
import bms.player.beatoraja.audio.AudioDriver;
import bms.player.beatoraja.audio.PCM;
import bms.player.beatoraja.select.BarManager;
import bms.player.beatoraja.select.bar.Bar;
import bms.player.beatoraja.select.bar.SongBar;
import bms.player.beatoraja.song.SongData;
import com.starxh.beatoraja.AudioSpectrumManager;
import com.starxh.beatoraja.AudioSpectrumProvider;

/**
 * 选曲界面中的 Music Player 状态 —— 跑当前选中歌曲的 BMS autoplay,只播放 BG 音轨,
 * 不渲染 BGA / 判定画面 / lane 视觉,改画歌曲列表 + 频谱 + 进度条 + 播放控制按钮。
 *
 * 复用 {@link MainState} 生命周期;BG 音轨调度逻辑仿
 * {@link KeySoundProcessor.AutoplayThread} 在内嵌 BGAutoplayThread 里复刻,隔离。
 */
public class MusicPlayer extends MainState {

	// 自己的歌曲列表（显示所有文件夹的歌曲）
	private SongData[] allSongs;
	// 跨线程读写的字段 —— 后台 worker (切歌) 写, render() / BGAutoplayThread 读,
	// 加 volatile 保证可见性。
	private volatile int selectedIndex = 0;
	private volatile SongData currentSong;
	private volatile BMSModel currentModel;
	private volatile BGAutoplayThread bgThread;
	/**
	 * 播放基准时间({@link System#nanoTime()})。
	 * 这是进度条和切歌判定的唯一时间源。BGAutoplayThread 检测到"时间跳跃"(后台被系统
	 * 限制 CPU 或进入 Doze 后唤醒) 时会前移该基准,避免一次性补播积压的上千个音符
	 * —— 那会把 soundpool 打满、Oboe 回调堆积,表现为爆音 / 声音断掉 / 线程卡死。
	 */
	private volatile long playBaseNanos = 0;
	private volatile long totalDurationMs;
	private volatile Texture stagefile;
	// dispose 之后 transition 不要再起新线程 —— 防止 dispose 和 transition 竞争导致孤儿线程
	private volatile boolean disposed = false;
	private Pixmap stagefilePixmap;
	/**
	 * 串行执行所有"重活"的单线程 executor:加载 BMSModel、换 AudioDriver 音频模型、启停播放线程。
	 *
	 * 存在的原因:AudioDriver 只有 setModel() 是 synchronized,而播放线程走的 play0() 完全
	 * 不加锁,wavmap / slicesound 也不是 volatile。后台切歌线程直接调 setModel() 会在播放
	 * 线程读 wavmap 的中途换掉数组并释放旧 PCM(Oboe 是 native 对象),轻则数组越界丢音,
	 * 重则 native use-after-free 直接崩进程。这里把所有结构性操作收敛到单线程,
	 * 并在切换前后与播放线程做交接(先停播放线程,再换模型),彻底消除该竞态。
	 */
	private ExecutorService worker;
	/**
	 * 1x1 纯白纹理 —— 所有纯色矩形都靠它 + batch.setColor() 染出来。
	 *
	 * 不能做成 static:Android 切后台会销毁并重建 GL 上下文,static 字段里的 Texture
	 * 句柄会变成失效引用,而 static 又不会随实例重来 —— 回到前台再画它就是野句柄
	 * (GL error / 渲染错乱 / 上下文丢失时直接崩)。改成实例字段后由 resume()/dispose()
	 * 统一释放重建。
	 */
	private Texture whiteTexture;
	private BitmapFont font;
	/**
	 * font 是否是本类自己 new 出来的。来自 main.getSystemFont18() 的字体由 MainController
	 * 统一回收,本类 dispose() 绝不能碰 —— resume() 之后 MainController 换了新的实例,
	 * 旧的已经被 dispose 了,再 dispose 一次就是 double free。
	 */
	private boolean fontOwned = false;
	private int skinW;
	private int skinH;

	// 控制按钮布局(屏幕底部)
	private static final float BTN_SIZE = 96f;
	private static final float BTN_MARGIN_BOTTOM = 48f;
	private static final float BTN_GAP = 32f;

	// 频谱显示区域 (基于原始比例换算到当前分辨率)
	private static final float SPEC_W_RATIO = 0.28125f; // 540/1920 - 保持原始比例
	private static final float SPEC_H_RATIO = 0.185f;   // 200/1080 - 高度比例
	private static final float SPEC_Y_OFFSET = 0.80f;   // Y 放到上方，向下 5%
	private static final int SPEC_BANDS = 32;
	// 运行时计算的频谱坐标
	private float specX, specY, specW, specH;

	// 歌曲列表区域
	private static final int LIST_VISIBLE = 10; // 上下各 4 条,中间 1 条
	private static final float LIST_LINE_H = 56f;
	private static final float LIST_TOP_Y = 0f; // 从屏幕顶部 0 起(屏幕坐标)
	private static final float LIST_LEFT_X = 36f;
	private static final float LIST_RIGHT_X = 336f; // 频谱从 X=360 开始,留 24px 间距
	private static final int LIST_TAP_THRESHOLD = 20; // 像素:低于此值视为 tap,否则视为 drag

	// 列表触摸状态
	private boolean listDragging = false;
	private int listDragStartY = 0;
	private float listDragOffset = 0f;
	private int listTouchedBarIndex = -1;

	// 后台切换歌曲时的并发守卫,防止 AutoAdvanceThread 重复进入
	private volatile boolean isTransitioning = false;

	// 后台过渡时旧 stagefile 无法立即 dispose(需要 GL 线程),先暂存,等 render() 再清理
	// 跨线程:transitionToNextInBackground (后台) 写,render (GL) 读+dispose —— volatile
	private volatile Texture stagefileToDispose = null;

	// 播放模式 (顺序 / 随机 / 单曲循环)
	private enum PlayMode { SEQUENCE, RANDOM, LOOP_ONE }
	private volatile PlayMode playMode = PlayMode.SEQUENCE;

	// 频谱渲染
	private ShapeRenderer shapeRenderer;
	private final float[] specBands = new float[SPEC_BANDS];
	private final float[] specTopValues = new float[SPEC_BANDS];
	private static final float SPEC_FALL_SPEED = 0.02f;

	// 舞台图(放在屏幕正中央,4:3 横向)
	private static final float STAGEFILE_W = 480f;
	private static final float STAGEFILE_H = 320f;

	public MusicPlayer(MainController main) {
		super(main);
	}

	@Override
	public void create() {
		// 使用 Config 的分辨率（4:3 模式下会返回实际屏幕分辨率）
		this.skinW = resource.getConfig().getResolution().width;
		this.skinH = resource.getConfig().getResolution().height;

		// 确保 audio 状态干净:闪退后 dispose() 可能未执行,
		// 残留 note 会导致新 setModel 与旧音频状态竞争。
		AudioDriver audioDriver = main.getAudioProcessor();
		if (audioDriver != null) {
			audioDriver.stop((Note) null);
		}

		// 复位上一次离开 MusicPlayer 时 shutdown()/dispose() 设过的退出标志。
		// 不复位的话,本次进入后 worker 监视任务会立刻退出、自动切歌也永远被
		// disposed 守卫拦下 —— 表现为"能播当前曲但永远不自动切歌"。
		this.disposed = false;
		this.isTransitioning = false;

		// 获取所有歌曲（不再依赖 MusicSelector 的 BarManager）
		this.allSongs = main.getSongDatabase().getSongDatas();
		if (allSongs == null || allSongs.length == 0) {
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}

		// 如果 MusicSelector 当前选中的是 SongBar，保持同步
		BarManager selectorBarManager = main.getMusicSelector().getBarManager();
		Bar selectorBar = selectorBarManager.getSelected();
		if (selectorBar instanceof SongBar) {
			SongData selectorSong = ((SongBar) selectorBar).getSongData();
			for (int i = 0; i < allSongs.length; i++) {
				if (allSongs[i].getSha256().equals(selectorSong.getSha256())) {
					selectedIndex = i;
					break;
				}
			}
		}

		this.currentSong = allSongs[selectedIndex];
		this.currentModel = resource.loadBMSModel(
				Gdx.files.absolute(currentSong.getPath()),
				resource.getPlayerConfig().getLnmode());
		if (this.currentModel == null) {
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}
		// 把模型的 WAV 列表灌进 AudioDriver,否则 audio.play(note) 会数组越界
		main.getAudioProcessor().setModel(currentModel);

		// 资源状态对齐 autoplay
		resource.setPlayMode(BMSPlayerMode.AUTOPLAY);

		// 舞台图
		loadStagefile();

		// 字体
		this.font = main.getSystemFont18();
		if (this.font == null) {
			this.font = new BitmapFont();
			this.fontOwned = true;
		}
		this.font.setColor(Color.WHITE);

		// 总时长:对齐 BMSPlayer 公式 Math.max(lastEventTime + 1000, lastNoteTime + tail)
		final int lastEventTime = currentModel.getLastTime();
		final int lastNoteTime = currentModel.getLastNoteTime();
		int tail = currentSong.getTail();
		if (tail <= 0) {
			// 数据库无记录或为0，执行扫描逻辑以修复
			tail = calculateMaxTailMs(currentModel, lastNoteTime);
			currentSong.setTail(tail);
			main.getSongDatabase().updateSongTail(currentModel.getSHA256(), tail);
		}
		this.totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + tail);
		Gdx.app.log("MusicPlayer", "Loaded totalDurationMs: " + totalDurationMs + " ms, tail: " + tail + ", lastNoteTime: " + lastNoteTime + ", lastEventTime: " + lastEventTime);

		// 启动 BG 自动播放线程(单调时钟,不受系统改表 / NTP 校时影响)
		this.playBaseNanos = System.nanoTime();
		startBgThread(currentModel);

		// 自动切歌:常驻监视任务跑在 worker 单线程上,不再每首歌 new 一个 Thread
		startAdvanceWatcher();

		// 启动 ShapeRenderer
		if (this.shapeRenderer == null) {
			this.shapeRenderer = new ShapeRenderer();
		}

		// 计算频谱显示区域坐标
		specW = skinW * SPEC_W_RATIO;
		specH = skinH * SPEC_H_RATIO;
		specX = (skinW - specW) / 2;  // X 居中
		specY = (int)(skinH * SPEC_Y_OFFSET);  // Y 放到上方

		// 启动后开启持续渲染(选曲界面默认是关的)
		Gdx.graphics.setContinuousRendering(true);
	}

	private void loadStagefile() {
		String path = currentSong.getStagefile();
		if (path == null || path.isEmpty()) {
			path = currentSong.getBanner();
		}
		if (path == null || path.isEmpty()) return;
		File bmsFile = new File(currentSong.getPath());
		File coverFile = new File(bmsFile.getParentFile(), path);
		String resolved = PixmapResourcePool.findImagePath(coverFile.getAbsolutePath());
		if (resolved == null) return;
		this.stagefilePixmap = PixmapResourcePool.loadPicture(resolved);
		if (this.stagefilePixmap != null) {
			this.stagefile = new Texture(stagefilePixmap);
		}
	}

	// ------------------------------------------------------------------
	// 线程 / GL 资源管理
	// ------------------------------------------------------------------

	/**
	 * 启动 BG 自动播放线程。必须在 {@code setModel()} 之后调用 —— 播放线程读的
	 * wavmap 必须是新模型灌好的那份。
	 */
	private void startBgThread(BMSModel model) {
		BGAutoplayThread t = new BGAutoplayThread(model, main, playBaseNanos);
		this.bgThread = t;
		t.start();
	}

	/**
	 * 停掉 BG 自动播放线程并等它真正退出。
	 *
	 * 这一步是修复的核心之一:AudioDriver.setModel() 会整体换掉 wavmap 并 disposeOld()
	 * 释放旧 PCM。Oboe 的 PCM 是 native 对象,如果播放线程正好拿着旧引用在播,
	 * 释放后就是 use-after-free —— 随机 SIGSEGV,切回前台时最容易撞上。
	 * 所以换模型前必须先把播放线程 join 掉,再 audio.stop(null) 停掉混音器里还在
	 * 排队的 sample,最后才 setModel()。
	 */
	private void stopBgThread() {
		BGAutoplayThread t = bgThread;
		bgThread = null;
		if (t == null) return;
		t.stop = true;
		t.interrupt();
		try {
			t.join(1000);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/**
	 * 停掉所有在飞的音符。必须在 setModel() 之前调用,理由同 {@link #stopBgThread()}。
	 */
	private void stopAllNotes() {
		if (main == null) return;
		AudioDriver audio = main.getAudioProcessor();
		if (audio != null) {
			audio.stop((Note) null);
		}
	}

	/**
	 * 在 worker 单线程上启动常驻的"自动切歌"监视任务。
	 *
	 * 为什么是常驻任务而不是每首歌 new 一个 Thread:
	 *  - 每首歌 new Thread 时,线程创建/销毁的窗口里会和 GL 线程的手动切歌抢同一把锁,
	 *    旧实现还经常漏掉 stop 旧线程;
	 *  - 常驻任务只有一个线程,和手动切歌共用 {@code synchronized(this)},天然串行。
	 */
	private void startAdvanceWatcher() {
		if (worker == null || worker.isShutdown()) {
			worker = Executors.newSingleThreadExecutor(r -> {
				Thread t = new Thread(r, "MusicPlayer-Worker");
				t.setDaemon(true);
				return t;
			});
		}
		worker.submit(() -> {
			while (!disposed && !Thread.currentThread().isInterrupted()) {
				try {
					long total = totalDurationMs;
					if (total > 0 && !isTransitioning && getCurrentPlaybackMs() >= total) {
						transitionToNextInBackground();
					}
					Thread.sleep(200);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				} catch (Throwable e) {
					// 兜底:任何异常都不能让监视线程静默死掉 ——
					// 那样自动切歌会永久失效,表现就是"声音播完就断了"
					if (Gdx.app != null) {
						Gdx.app.error("MusicPlayer", "advance watcher error", e);
					}
				}
			}
		});
	}

	/** 见 {@link #whiteTexture} 字段注释:必须是实例级,GL 上下文重建后要重来 */
	private Texture getWhiteTexture() {
		if (whiteTexture == null) {
			try {
				Pixmap pm = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
				pm.setColor(1f, 1f, 1f, 1f);
				pm.fill();
				whiteTexture = new Texture(pm);
				pm.dispose();
			} catch (Throwable e) {
				if (Gdx.app != null) {
					Gdx.app.error("MusicPlayer", "failed to create white texture", e);
				}
				return null;
			}
		}
		return whiteTexture;
	}

	/**
	 * 释放本类持有的 GL 资源。在 resume() 里也要调一次 —— 切后台时 GL 上下文会被销毁
	 * 重建,旧 Texture 句柄全部失效,必须丢掉重来。
	 */
	private void disposeGlResources() {
		if (whiteTexture != null) {
			whiteTexture.dispose();
			whiteTexture = null;
		}
		if (stagefile != null) {
			stagefile.dispose();
			stagefile = null;
		}
		stagefileToDispose = null;
	}

	@Override
	public void render() {
		SpriteBatch batch = main.getSpriteBatch();
		if (batch == null) return;

		// 后台过渡时跳过了 GL 操作,stagefile 被标记为 null 且旧纹理暂存在
		// stagefileToDispose;这里的 render() 肯定在 GL 线程上,所以一次清理 + 重新加载。
		if (stagefileToDispose != null) {
			stagefileToDispose.dispose();
			stagefileToDispose = null;
		}
		if (stagefile == null && currentSong != null) {
			loadStagefile();
		}

		// 1. 背景:深色
		drawBackground(batch);
		// 2. 歌曲列表(顶部)
		drawSongList(batch);
		// 3. 舞台图(中央)
		drawStagefile(batch);
		// 4. 频谱(中部)
		drawSpectrum(batch);
		// 5. 进度条 + 时间文字
		drawProgressBar(batch);
		// 6. 播放控制按钮(底部)
		drawControlButtons(batch);
	}

	private void drawBackground(SpriteBatch batch) {
		Texture tex = getWhiteTexture();
		if (tex == null) return;
		batch.begin();
		batch.setColor(0.05f, 0.06f, 0.10f, 1f);
		batch.draw(tex, 0, 0, skinW, skinH);
		batch.end();
	}

	private void drawSongList(SpriteBatch batch) {
		if (font == null) return;
		if (allSongs == null || allSongs.length == 0) return;
		int half = LIST_VISIBLE / 2;
		Texture blank = getWhiteTexture();

		// 第一行的基线 y(libGDX 坐标,自下而上)
		float baseX = LIST_LEFT_X;
		float topRowBaselineY = skinH - LIST_TOP_Y - LIST_LINE_H * 0.5f;

		batch.begin();
		for (int row = 0; row < LIST_VISIBLE; row++) {
			int idx = (selectedIndex + row - half + allSongs.length) % allSongs.length;
			SongData song = allSongs[idx];
			String title = song == null ? "" : (song.getFullTitle() == null ? song.getTitle() : song.getFullTitle());

			// 随拖拽偏移整体平移:手指下滑(gdx_y 减小)→ listDragOffset > 0 → 行向下移
			float y = topRowBaselineY - row * LIST_LINE_H - listDragOffset;

			if (row == half) {
				// 当前曲目:高亮背景
				if (blank != null) {
					batch.setColor(0.20f, 0.30f, 0.55f, 0.9f);
					batch.draw(blank,
							LIST_LEFT_X - 12f,
							y - LIST_LINE_H * 0.5f + 4f,
							LIST_RIGHT_X - LIST_LEFT_X + 24f,
							LIST_LINE_H - 8f);
				}
				font.setColor(1f, 0.95f, 0.55f, 1f);
			} else {
				float fade = 1f - Math.abs(row - half) * 0.12f;
				if (fade < 0.3f) fade = 0.3f;
				font.setColor(fade, fade, fade, 1f);
			}
			font.draw(batch, title, baseX, y);
		}
		batch.end();
	}

	private void drawStagefile(SpriteBatch batch) {
		if (stagefile == null) return;
		float x = (skinW - STAGEFILE_W) / 2f;
		float y = (skinH - STAGEFILE_H) / 2f;
		batch.begin();
		batch.setColor(1, 1, 1, 1);
		batch.draw(stagefile, x, y, STAGEFILE_W, STAGEFILE_H);
		batch.end();
	}

	private void drawSpectrum(SpriteBatch batch) {
		// MusicPlayer 强制把全局设置里的"线条"(MODE_WAVEFORM)和"频谱"(MODE_SPECTRUM)
		// 都按频谱条渲染 —— 本类没有 waveform 绘制路径,只画 bars。
		// 若用户在 Settings 里选了"关闭"(MODE_OFF),这里直接 return,空出中部频谱区。
		// 这样既覆盖了"线条模式下进入 MusicPlayer 闪退"的历史问题,又允许用户主动关掉频谱。
		if (resource.getConfig().getAudioVisualizationMode() == bms.player.beatoraja.Config.MODE_OFF) {
			return;
		}
		// 1. 取频谱(64 段 = 32 左 + 32 右,合并成 32 段单声道)
		AudioSpectrumProvider provider = AudioSpectrumManager.getGlobalProvider();
		float[] raw = provider == null ? null : provider.getSpectrumMagnitudes();
		if (raw == null || raw.length < 64) {
			// 退化:全 0
			for (int i = 0; i < SPEC_BANDS; i++) {
				specBands[i] = 0f;
			}
		} else {
			for (int i = 0; i < SPEC_BANDS; i++) {
				float left = raw[i];
				float right = raw[32 + i];
				float v = (left + right) * 0.5f;
				if (v < 0f) v = 0f;
				if (v > 1f) v = 1f;
				specBands[i] = v;
			}
		}

		// 2. 顶部 peak 衰减
		for (int i = 0; i < SPEC_BANDS; i++) {
			if (specBands[i] > specTopValues[i]) {
				specTopValues[i] = specBands[i];
			} else {
				specTopValues[i] -= SPEC_FALL_SPEED;
				if (specTopValues[i] < 0) specTopValues[i] = 0;
			}
		}

		// 3. 边框
		if (shapeRenderer == null) {
			shapeRenderer = new ShapeRenderer();
		}
		Gdx.gl.glEnable(Gdx.gl.GL_BLEND);
		Gdx.gl.glBlendFunc(Gdx.gl.GL_SRC_ALPHA, Gdx.gl.GL_ONE_MINUS_SRC_ALPHA);
		shapeRenderer.setProjectionMatrix(batch.getProjectionMatrix());
		shapeRenderer.begin(ShapeRenderer.ShapeType.Filled);

		// 背景框
		shapeRenderer.setColor(0f, 0f, 0f, 0.6f);
		shapeRenderer.rect(specX, specY, specW, specH);

		// 频谱条
		float bandW = specW / SPEC_BANDS;
		float barThickness = bandW * 0.7f;
		for (int i = 0; i < SPEC_BANDS; i++) {
			float x = specX + i * bandW;
			float v = specBands[i];
			float top = specTopValues[i];
			float barHeight = v * (specH - 4f);
			float topY = top * (specH - 4f);
			barHeight = Math.min(barHeight, specH - 4f);

			// 主条:蓝绿渐变(按频率从低到高,颜色从青到紫)
			float hue = (float) i / SPEC_BANDS;
			shapeRenderer.setColor(0.3f + hue * 0.4f, 0.7f, 1f - hue * 0.5f, 0.85f);
			shapeRenderer.rect(x + (bandW - barThickness) / 2f, specY + 2f,
					barThickness, barHeight);

			// 顶部峰值
			if (topY > 2f) {
				shapeRenderer.setColor(1f, 1f, 1f, 0.9f);
				shapeRenderer.rect(x + (bandW - barThickness) / 2f, specY + 2f + topY - 2f,
						barThickness, 2f);
			}
		}
		shapeRenderer.end();
	}

	private void drawProgressBar(SpriteBatch batch) {
		long currentMs = getCurrentPlaybackMs();
		float progress = totalDurationMs > 0
				? Math.min(1f, (float) currentMs / totalDurationMs)
				: 0f;
		float barX = 48f;
		float barY = BTN_MARGIN_BOTTOM + BTN_SIZE + 32f;
		float barW = skinW - 96f;
		float barH = 8f;

		Texture blank = getWhiteTexture();
		batch.begin();
		if (blank != null) {
			// 底色
			batch.setColor(0.20f, 0.20f, 0.25f, 1f);
			batch.draw(blank, barX, barY, barW, barH);
			// 进度
			batch.setColor(0.95f, 0.90f, 0.40f, 1f);
			batch.draw(blank, barX, barY, barW * progress, barH);
		}
		batch.end();

		// 时间文字
		if (font != null) {
			batch.begin();
			font.setColor(0.8f, 0.8f, 0.8f, 1f);
			String timeText = formatTime(currentMs) + " / " + formatTime(totalDurationMs);
			font.draw(batch, timeText, barX, barY - 8f);
			batch.end();
		}
	}

	private void drawControlButtons(SpriteBatch batch) {
		// 4 个按钮:上一首 / 下一首 / 模式 / 退出
		float totalW = BTN_SIZE * 4 + BTN_GAP * 3;
		float startX = (skinW - totalW) / 2f;
		float y = BTN_MARGIN_BOTTOM;

		Texture tex = getWhiteTexture();
		batch.begin();
		if (tex != null) {
			batch.setColor(0.20f, 0.22f, 0.30f, 0.9f);
			for (int i = 0; i < 4; i++) {
				float x = startX + i * (BTN_SIZE + BTN_GAP);
				batch.draw(tex, x, y, BTN_SIZE, BTN_SIZE);
			}
		}
		batch.end();

		// 按钮文字
		if (font != null) {
			batch.begin();
			font.setColor(1, 1, 1, 1);
			String[] labels = {"PREV", "NEXT", modeLabel(playMode), "EXIT"};
			for (int i = 0; i < 4; i++) {
				float x = startX + i * (BTN_SIZE + BTN_GAP);
				font.draw(batch, labels[i], x + 12f, y + BTN_SIZE / 2f);
			}
			batch.end();
		}
	}

	private static String modeLabel(PlayMode m) {
		switch (m) {
			case SEQUENCE: return "SEQ";
			case RANDOM:   return "RND";
			case LOOP_ONE: return "LOOP";
			default: return "?";
		}
	}

	@Override
	public void input() {
		// 物理键盘 / Android BACK 键
		if (Gdx.input.isKeyJustPressed(Input.Keys.LEFT)) {
			playPrev();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.RIGHT)) {
			playNext();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.M)) {
			cyclePlayMode();
		} else if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
			// libGDX Android 后端会把 BACK 重映射成 ESCAPE 派发 (见 logcat "BACK detected, remapping to ESCAPE"),
			// 所以这里 catch ESC 就同时 catch 了 Android BACK。
			// 行为:等价于点击 EXIT 按钮(切回选曲界面)
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}

		// 列表触屏:滑动 / 点击
		handleListTouch();

		// 触屏:4 个按钮区域(列表已开始拖拽时不响应按钮,避免和列表冲突)
		if (!listDragging && Gdx.input.justTouched()) {
			int gx = main.getInputProcessor().getMouseX();
			int gy = main.getInputProcessor().getMouseY();
			int idx = hitTestControlButton(gx, gy);
			if (idx >= 0) {
				switch (idx) {
					case 0: playPrev(); break;
					case 1: playNext(); break;
					case 2: cyclePlayMode(); break;
					case 3: main.changeState(MainStateType.MUSICSELECT); break;
				}
			}
		}
	}

	private void handleListTouch() {
		int gx = Gdx.input.getX();
		int gy = skinH - Gdx.input.getY(); // 转 libGDX Y
		boolean touched = Gdx.input.isTouched() || Gdx.input.justTouched();

		if (touched) {
			if (!listDragging) {
				if (isInListArea(gx, gy)) {
					listDragging = true;
					listDragStartY = gy;
					listTouchedBarIndex = computeBarIndexAtTouch(gy);
				}
			} else {
				// 手指向下滑(gdx_y 减小) -> listDragOffset > 0
				listDragOffset = listDragStartY - gy;
			}
		} else {
			if (listDragging) {
				if (Math.abs(listDragOffset) < LIST_TAP_THRESHOLD) {
					// 视为 tap:选中并开始播放
					if (listTouchedBarIndex >= 0) {
						selectedIndex = listTouchedBarIndex;
						loadAndPlaySelected();
					}
				} else {
					// 视为 drag:按行换 selectedindex
					// 渲染方向(手指下滑 → 行下移)走的是 convention 1(content follows finger),
					// 手指下滑时进入视野的是上方(LOWER 索引)的曲子,snap 必须与之一致:
					// listDragOffset > 0 → newSel 减小。
					if (allSongs != null && allSongs.length > 0) {
						int deltaSel = -Math.round(listDragOffset / LIST_LINE_H);
						int n = allSongs.length;
						selectedIndex = ((selectedIndex + deltaSel) % n + n) % n;
					}
				}
				listDragging = false;
				listDragOffset = 0f;
			}
		}
	}

	private boolean isInListArea(int gx, int gy) {
		if (gx < LIST_LEFT_X - 24f || gx > LIST_RIGHT_X + 24f) return false;
		float listTopY = skinH - LIST_TOP_Y;
		float listBottomY = listTopY - LIST_VISIBLE * LIST_LINE_H;
		return gy >= listBottomY && gy <= listTopY;
	}

	private int computeBarIndexAtTouch(int gy) {
		if (allSongs == null || allSongs.length == 0) return -1;
		int half = LIST_VISIBLE / 2;
		// 绘制公式:row r 中心 y = baseRow0Y - r * LIST_LINE_H - listDragOffset
		// (见 drawSongList) —— 反推 row 应该用 (baseRow0Y - gy) / LIST_LINE_H + listDragOffset / LIST_LINE_H
		// 注意:滚动后内容向下移动(listDragOffset > 0)，所以要用 + 往回推算实际在那个位置的 row
		float baseRow0Y = skinH - LIST_TOP_Y - LIST_LINE_H * 0.5f;
		int row = Math.round((baseRow0Y - gy) / LIST_LINE_H + listDragOffset / LIST_LINE_H);
		if (row < 0 || row >= LIST_VISIBLE) return -1;
		int idx = (selectedIndex + row - half + allSongs.length) % allSongs.length;
		return idx;
	}

	// synchronized(this) —— 跟 transitionToNextInBackground() 同一把锁,
	// 防止"手动 NEXT 按钮"和"曲尾自动 transition"竞争导致重复起线程 / 音频重叠。
	private synchronized void loadAndPlaySelected() {
		if (allSongs == null || selectedIndex < 0 || selectedIndex >= allSongs.length) return;
		SongData next = allSongs[selectedIndex];
		if (next == null) return;

		shutdownResources();

		this.currentSong = next;
		this.currentModel = resource.loadBMSModel(
				Gdx.files.absolute(currentSong.getPath()),
				resource.getPlayerConfig().getLnmode());
		if (this.currentModel == null) {
			main.changeState(MainStateType.MUSICSELECT);
			return;
		}

		// 换 wavmap 前先把所有在飞的 note 停掉(见 stopBgThread() 注释)
		stopAllNotes();
		main.getAudioProcessor().setModel(currentModel);
		resource.setPlayMode(BMSPlayerMode.AUTOPLAY);

		loadStagefile();

		final int lastEventTime = currentModel.getLastTime();
		final int lastNoteTime = currentModel.getLastNoteTime();
		int tail = currentSong.getTail();
		if (tail <= 0) {
			tail = calculateMaxTailMs(currentModel, lastNoteTime);
			currentSong.setTail(tail);
			main.getSongDatabase().updateSongTail(currentModel.getSHA256(), tail);
		}
		this.totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + tail);
		Gdx.app.log("MusicPlayer", "loadAndPlaySelected totalDurationMs: " + totalDurationMs + " ms, tail: " + tail + ", lastNoteTime: " + lastNoteTime + ", lastEventTime: " + lastEventTime);

		this.playBaseNanos = System.nanoTime();
		startBgThread(currentModel);
	}

	private int hitTestControlButton(int gx, int gy) {
		float totalW = BTN_SIZE * 4 + BTN_GAP * 3;
		float startX = (skinW - totalW) / 2f;
		float y = BTN_MARGIN_BOTTOM;
		for (int i = 0; i < 4; i++) {
			float x = startX + i * (BTN_SIZE + BTN_GAP);
			if (gx >= x && gx <= x + BTN_SIZE && gy >= y && gy <= y + BTN_SIZE) {
				return i;
			}
		}
		return -1;
	}

	private void playNext() {
		advanceByMode(true);
	}

	private void playPrev() {
		advanceByMode(false);
	}

	/**
	 * 按当前 playMode 推进到下一首/上一首(用于手动 PREV/NEXT 按钮和歌曲结束的自动切歌)。
	 *  - LOOP_ONE:不切歌,直接重新播放当前曲目
	 *  - RANDOM:随机选一首跟当前不同的;若曲目数 <= 1 则保持当前
	 *  - SEQUENCE:在所有歌曲列表中顺序/逆序移动
	 */
	private void advanceByMode(boolean forward) {
		if (allSongs == null || allSongs.length == 0) return;

		switch (playMode) {
			case LOOP_ONE:
				loadAndPlaySelected();
				return;
			case RANDOM: {
				if (allSongs.length <= 1) {
					loadAndPlaySelected();
					return;
				}
				java.util.Random rng = new java.util.Random();
				int newIdx = selectedIndex;
				int safety = 16;
				while (newIdx == selectedIndex && safety-- > 0) {
					newIdx = rng.nextInt(allSongs.length);
				}
				selectedIndex = newIdx;
				loadAndPlaySelected();
				return;
			}
			case SEQUENCE:
			default:
				int n = allSongs.length;
				selectedIndex = forward ? (selectedIndex + 1) % n : (selectedIndex - 1 + n) % n;
				loadAndPlaySelected();
				return;
		}
	}

	private void cyclePlayMode() {
		switch (playMode) {
			case SEQUENCE: playMode = PlayMode.RANDOM; break;
			case RANDOM:   playMode = PlayMode.LOOP_ONE; break;
			case LOOP_ONE: playMode = PlayMode.SEQUENCE; break;
		}
	}

	/**
	 * 当前播放进度(ms)。
	 *
	 * 用 {@link System#nanoTime()}(单调时钟)而不是 {@link System#currentTimeMillis()}:
	 * wall clock 会被 NTP 校时 / 用户改表往前往后跳,一跳就是几百毫秒甚至几分钟,
	 * 进度条会瞬移,自动切歌也会在错误的时刻触发。
	 */
	private long getCurrentPlaybackMs() {
		if (playBaseNanos == 0) return 0;
		return (System.nanoTime() - playBaseNanos) / 1000000L;
	}

	private static String formatTime(long ms) {
		if (ms < 0) ms = 0;
		long sec = ms / 1000;
		long min = sec / 60;
		sec = sec % 60;
		return String.format("%d:%02d", min, sec);
	}

	@Override
	public void shutdown() {
		// 状态切换(EXIT → MUSICSELECT)走这里。必须和 dispose() 一样彻底停掉音频与线程,
		// 否则 worker 监视任务仍存活,曲尾会触发自动切歌重新起播
		// —— 表现为"退出 MusicPlayer 后音乐还在播放"。
		// 注意:不 dispose font/shapeRenderer(留待下次进入复用),也不调 super.dispose()。
		terminatePlayback();
	}

	/**
	 * Android 屏幕关掉 (Activity.onPause/onStop) 时 libGDX 会调到这里。
	 * 父类 MainState.pause()/resume() 是空实现,默认会让 render() 停止被调用。
	 * 这里保持 BG 自动播放线程不受影响 —— 它跑在自己线程上,基于单调时钟
	 * (System.nanoTime() - baseNanos) 推进,Activity 生命周期无关。
	 * Oboe 音频流也由 AAudio 单独驱动,只要进程不被打死就会继续播。
	 * 这样锁屏 / 屏幕关 / 应用切到其他 activity 短暂遮挡时音乐不会中断。
	 *
	 * 注意:真正的"应用彻底后台" (Android 进程被杀) 需要 ForegroundService + mediaPlayback
	 * 才能继续播 —— 那需要改 AndroidManifest 启动前台服务和 Oboe Usage::Game → Media。
	 * 单纯 override pause/resume 只能保证 standby / 短时切换应用 不断音。
	 */
	@Override
	public void pause() {
	}

	@Override
	public void resume() {
		// 从 standby / 切回前台 时,libGDX 会关掉持续渲染,这里重新打开
		if (Gdx.graphics != null) {
			Gdx.graphics.setContinuousRendering(true);
		}
		// GL 上下文在后台被销毁重建过,本类持有的所有 Texture 句柄都已失效 ——
		// 注意:失效的 Texture 对象不是 null,所以"== null 才重载"的老判断是错的,
		// 必须无条件丢弃重来,否则回到前台第一帧就拿着野句柄去 draw。
		disposeGlResources();
		if (currentSong != null) {
			loadStagefile();
		}
		// MainController.resume() 重新生成了 systemfont18,旧引用指向已 dispose 的对象;
		// 重新拿一次,否则 font.draw() 引用失效纹理会导致渲染缺失。
		// 注意:旧引用绝不能在这里 dispose —— 它可能已经被 MainController 释放过了。
		if (main != null) {
			BitmapFont fresh = main.getSystemFont18();
			if (fresh != null) {
				this.font = fresh;
				this.fontOwned = false;
			}
		}
		if (this.font != null) {
			this.font.setColor(Color.WHITE);
		}
	}

	// synchronized(this) —— 跟 transitionToNextInBackground() 同一把锁,
	// 防止"用户关闭播放器"和"后台自动切歌"竞争导致孤儿线程泄漏。
	// 注意:本方法也会被 loadAndPlaySelected()(手动切歌)调用,不能在这里设 disposed=true,
	// 否则手动 NEXT 一次之后自动 transition 会永远看到 disposed=true 而不再起新线程。
	private synchronized void shutdownResources() {
		// 先彻底停掉播放线程(join),再停掉所有在飞的 note —— 顺序不能反,
		// 否则播放线程还会在 stop 之后继续往已经要被换掉的 wavmap 里塞 note。
		stopBgThread();
		// 强制停止上一首所有 K/BG 音轨 —— 已经在 Oboe 缓冲里排队的 note 不停的话,
		// 切歌后会跟新歌重叠,而且 setModel() 释放旧 PCM 后它们就是悬空引用。
		// 必须在 setModel(newModel) 之前调用,否则 wavmap 已替换。
		stopAllNotes();
		if (stagefile != null) {
			stagefile.dispose();
			stagefile = null;
		}
		// stagefilePixmap 已经转 Texture,不需要单独 dispose
		stagefilePixmap = null;
		// 复位频谱
		for (int i = 0; i < SPEC_BANDS; i++) {
			specBands[i] = 0f;
			specTopValues[i] = 0f;
		}
		// 退出时恢复无限速(0 表示不限制),由下一状态自行决定
		Gdx.graphics.setForegroundFPS(0);
	}

	/**
	 * 彻底停止播放相关的一切:后台切换监视任务、BG 自动播放线程、在飞音符、GL 资源。
	 *
	 * <p>必须在第一件事就设 {@code disposed = true}:这样正在 worker 线程上跑的
	 * {@link #transitionToNextInBackground()} 会在它的多处 disposed 守卫点尽快 return,
	 * 不会在 transition 中途 startBgThread 起出一个没人管的孤儿播放线程。</p>
	 *
	 * shutdown()(状态切到 MUSICSELECT)和 dispose()(整体退出)共用本方法;
	 * 区别只是 dispose 额外释放 font/shapeRenderer 并调用 super.dispose()。
	 */
	private void terminatePlayback() {
		// 第一件事:置退出标志。worker 监视任务和 transition 都靠它早退。
		disposed = true;
		// 打断 worker 上的常驻监视任务(它在 sleep(200),shutdownNow 负责 interrupt)。
		// 用 shutdownNow 而不是 shutdown:监视任务是死循环,shutdown 不会主动踢它。
		if (worker != null) {
			worker.shutdownNow();
			worker = null;
		}
		shutdownResources();
		disposeGlResources();
	}

	@Override
	public void dispose() {
		terminatePlayback();
		// 只 dispose 自己 new 的字体。systemfont18 归 MainController 管,
		// 而且 resume() 之后 MainController 已经换过实例、旧的已释放,再动就是 double free。
		if (fontOwned && font != null) {
			font.dispose();
		}
		font = null;
		fontOwned = false;
		if (shapeRenderer != null) {
			shapeRenderer.dispose();
			shapeRenderer = null;
		}
		super.dispose();
	}

	/**
	 * 扫描所有音频文件以计算真正的音频尾部时长(tail)。
	 * 逻辑移植自 BMSPlayer.java 以确保 MusicPlayer 在数据库记录缺失时也能获得正确时长。
	 */
	private int calculateMaxTailMs(BMSModel model, int lastNoteTime) {
		int maxTailMs = 0;
		final String[] wavlist = model.getWavList();
		final File bmsDir = new File(model.getPath()).getParentFile();
		final int[] lastOccurrenceArray = new int[wavlist.length];
		java.util.Arrays.fill(lastOccurrenceArray, -1);

		for (TimeLine tl : model.getAllTimeLines()) {
			final int time = tl.getTime();
			for (int lane = 0; lane < model.getMode().key; lane++) {
				Note n = tl.getNote(lane);
				if (n == null) n = tl.getHiddenNote(lane);
				if (n != null && n.getWav() >= 0 && n.getWav() < lastOccurrenceArray.length) {
					lastOccurrenceArray[n.getWav()] = time;
				}
			}
			for (Note n : tl.getBackGroundNotes()) {
				if (n != null && n.getWav() >= 0 && n.getWav() < lastOccurrenceArray.length) {
					lastOccurrenceArray[n.getWav()] = time;
				}
			}
		}

		// 一次性建立目录索引，避免数千次 File.exists() 系统调用导致 I/O 阻塞
		// 音频位于子目录（如 audio/bgm.ogg）时才惰性补一次有界深度递归扫描
		final PCM.AudioFileIndex audioFileIndex = new PCM.AudioFileIndex(bmsDir);

		final List<int[]> sortedWavs = new ArrayList<>();
		for (int wavid = 0; wavid < lastOccurrenceArray.length; wavid++) {
			if (lastOccurrenceArray[wavid] != -1 && wavlist[wavid] != null) {
				sortedWavs.add(new int[]{wavid, lastOccurrenceArray[wavid]});
			}
		}
		sortedWavs.sort((a, b) -> Integer.compare(b[1], a[1]));

		for (int[] entry : sortedWavs) {
			final int wavid = entry[0];
			final int lastTime = entry[1];

			File audioFile = audioFileIndex.resolve(wavlist[wavid]);

			if (audioFile != null) {
				long fileSize = audioFile.length();
				if (lastTime + (fileSize / 8) <= lastNoteTime + maxTailMs) {
					continue;
				}
				int dur = bms.player.beatoraja.audio.PCM.getWavDurationMs(audioFile.getPath());
				if (dur > 0) {
					final int tailEnd = lastTime + dur;
					if (tailEnd > lastNoteTime) {
						maxTailMs = Math.max(maxTailMs, tailEnd - lastNoteTime);
					}
				}
			}
		}
		return maxTailMs;
	}

	/**
	 * BG 音轨自动播放线程 —— 仿 {@link KeySoundProcessor.AutoplayThread},但持有自己的时钟。
	 * 播完所有 timeline 后自然退出,不触发任何回调 —— 切歌由 worker 上常驻的监视任务
	 * 在 totalDurationMs 触发,中间 tail 静音期留给最后一条 note 自然播完。
	 *
	 * 与旧实现的两点关键差异:
	 * <ol>
	 *   <li><b>时间源换成 {@link System#nanoTime()}</b>。wall clock 会被 NTP 校时 / 用户改表
	 *       往前往后跳,一跳就是几百毫秒,整条 timeline 的调度会整体错位。</li>
	 *   <li><b>加了"追赶保护"</b>。应用切后台后系统可能长时间不给这个线程 CPU
	 *       (Doze / 后台 CPU 配额 / 大核下线),唤醒时 elapsed 会一下前进好几秒。
	 *       旧实现会把这几秒内积压的上千个 note 一次性全灌进 AudioDriver ——
	 *       soundpool 瞬间打满、Oboe 回调线程堆积,表现就是爆音、声音断掉、
	 *       严重时整个音频层卡死。这里检测到"时间跳跃"就把跳过的 note 丢弃(不补播),
	 *       直接从当前时间点的 timeline 继续。</li>
	 * </ol>
	 */
	private static class BGAutoplayThread extends Thread {
		private final BMSModel model;
		private final MainController main;
		volatile boolean stop = false;
		private final long baseNanos;
		/** 两次唤醒间隔超过这个毫秒数就认为被系统饿过一次,丢弃积压的 note */
		private static final long STARVE_THRESHOLD_MS = 500L;

		BGAutoplayThread(BMSModel model, MainController main, long baseNanos) {
			this.model = model;
			this.main = main;
			this.baseNanos = baseNanos;
			setName("MusicPlayer-BGAutoplay");
			setDaemon(true);
		}

		@Override
		public void run() {
			try {
				AudioDriver audio = main.getAudioProcessor();
				if (audio == null) return;
				float vol = main.getPlayerResource().getConfig().getAudioConfig().getBgvolume();

				Array<TimeLine> tls = new Array<>();
				for (TimeLine tl : model.getAllTimeLines()) {
					if (tl.getBackGroundNotes().length > 0 || hasKeyNote(tl)) {
						tls.add(tl);
					}
				}
				TimeLine[] timelines = tls.toArray(TimeLine.class);

				int p = 0;
				long lastElapsedMs = 0;
				while (!stop) {
					long elapsedMs = (System.nanoTime() - baseNanos) / 1000000L;
					long timeMicros = elapsedMs * 1000L;

					final boolean starved = (elapsedMs - lastElapsedMs) > STARVE_THRESHOLD_MS;
					if (starved && Gdx.app != null) {
						Gdx.app.log("MusicPlayer", "BGAutoplay starved for "
								+ (elapsedMs - lastElapsedMs) + "ms, skipping backlog");
					}
					lastElapsedMs = elapsedMs;

					while (p < timelines.length && timelines[p].getMicroTime() <= timeMicros) {
						if (!starved) {
							TimeLine tl = timelines[p];
							for (Note n : tl.getBackGroundNotes()) {
								audio.play(n, vol, 0);
							}
							for (int lane = 0; lane < tl.getLaneCount(); lane++) {
								Note n = tl.getNote(lane);
								if (n != null) {
									audio.play(n, vol, 0);
								}
							}
						}
						p++;
					}
					if (p >= timelines.length) {
						// 所有 note 已播完 → 线程自然退出,切歌等 worker 监视任务触发
						break;
					}
					long sleepMs = (timelines[p].getMicroTime() - timeMicros) / 1000L;
					if (sleepMs < 1) sleepMs = 1;
					// 上限从 5ms 放宽到 10ms:后台时系统定时器本来就没那么准,
					// 5ms 的空转唤醒只是白烧 CPU,还给"被系统判定为异常耗电"加筹码
					if (sleepMs > 10) sleepMs = 10;
					try {
						sleep(sleepMs);
					} catch (InterruptedException e) {
						if (stop) break;
					}
				}
			} catch (Throwable e) {
				// 兜底:任何异常都不能让播放线程"静默消失" —— 那正是"音频断开"的现象之一
				if (Gdx.app != null) {
					Gdx.app.error("MusicPlayer", "BGAutoplayThread aborted", e);
				}
			}
		}

		private static boolean hasKeyNote(TimeLine tl) {
			for (int i = 0; i < tl.getLaneCount(); i++) {
				if (tl.getNote(i) != null) {
					return true;
				}
			}
			return false;
		}
	}

	/**
	 * 曲终自动切歌入口 —— 由 worker 上常驻的监视任务在 totalDurationMs 触发。
	 *
	 * 与 {@link #loadAndPlaySelected()} 的区别:loadAndPlaySelected 在 GL 线程上调用,
	 * 包含完整的 shutdownResources + create(含 stagefile Texture 上传等);
	 * 而本方法跑在 worker 线程,跳过所有 GL 依赖,只做:
	 * 停播放线程 → stop 所有 note → 推进曲目 → 加载 BMSModel → 设新音频模型 → 启新线程。
	 * GL 相关的 stagefile 清理/加载推迟到下一个 render() 统一处理。
	 *
	 * <b>注意:这里必须 stop 播放线程 + stop 所有 note,再 setModel()。</b>
	 * 旧实现直接 setModel(),而 setModel() 会换掉 wavmap 并 disposeOld() 释放旧 PCM ——
	 * Oboe 的 PCM 是 native 对象,播放线程/混音器还拿着旧引用就是 use-after-free,
	 * 直接 SIGSEGV。这正是"后台播着播着音频断开、切回前台闪退"的主因。
	 *
	 * @return true 表示成功切换;false 表示被并发守卫拦截(重复调用 / 已 dispose)
	 */
	private synchronized boolean transitionToNextInBackground() {
		if (isTransitioning) return false;
		// 早期 disposed 检查 —— dispose() 已设 disposed=true 并在等锁,
		// 本方法一拿到锁就该立刻放弃,不要白白加载 BMSModel / 算 tail / 设音频模型。
		if (disposed) return false;
		isTransitioning = true;
		try {
			// 1. 先停播放线程 + 停掉所有在飞的音符,然后才能安全地换 wavmap。
			//    (旧实现漏了这一步,是崩溃的根源)
			stopBgThread();
			stopAllNotes();

			// 2. 按 playMode 推进到下一首(与 advanceByMode 同逻辑)
			// 注意:先推进歌曲再清理 stagefile,确保 render() 如果在此期间运行
			// 看到的是已更新的 currentSong,从而 loadStagefile() 加载正确的封面。
			if (allSongs == null || allSongs.length == 0) {
				// 置成一个永远到不了的值:否则 worker 监视任务会每 200ms 重新触发一次切歌,
				// 变成死循环(加载失败 → 立刻再触发 → 再失败 …)
				totalDurationMs = Long.MAX_VALUE;
				Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
				return true;
			}

			switch (playMode) {
				case LOOP_ONE:
					// 保持当前曲目,直接重启
					break;
				case RANDOM: {
					if (allSongs.length > 1) {
						java.util.Random rng = new java.util.Random();
						int cur = selectedIndex;
						int newIdx = cur;
						for (int safety = 16; safety > 0 && newIdx == cur; safety--) {
							newIdx = rng.nextInt(allSongs.length);
						}
						selectedIndex = newIdx;
					}
					break;
				}
				case SEQUENCE:
				default:
					selectedIndex = (selectedIndex + 1) % allSongs.length;
					break;
			}

			// 5. currentSong 已更新,在此之后清理 stagefile,保证 render() 若同时运行
			// 看到的是新 currentSong,loadStagefile() 会加载正确封面。
			this.currentSong = allSongs[selectedIndex];
			if (currentSong == null) {
				// 置成一个永远到不了的值:否则 worker 监视任务会每 200ms 重新触发一次切歌,
				// 变成死循环(加载失败 → 立刻再触发 → 再失败 …)
				totalDurationMs = Long.MAX_VALUE;
				Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
				return true;
			}

			if (this.stagefile != null) {
				this.stagefileToDispose = this.stagefile;
				this.stagefile = null;
				this.stagefilePixmap = null;
			}

			// 6. 加载新 BMSModel(纯文件 I/O + 解析,不需要 GL)
			this.currentModel = resource.loadBMSModel(
					Gdx.files.absolute(currentSong.getPath()),
					resource.getPlayerConfig().getLnmode());
			if (currentModel == null) {
				// 置成一个永远到不了的值:否则 worker 监视任务会每 200ms 重新触发一次切歌,
				// 变成死循环(加载失败 → 立刻再触发 → 再失败 …)
				totalDurationMs = Long.MAX_VALUE;
				Gdx.app.postRunnable(() -> main.changeState(MainStateType.MUSICSELECT));
				return true;
			}

			// 7. 设置新音频模型(在飞的 note 已在第 1 步停掉,可以安全换 wavmap)
			//    再查一次 disposed:setModel() 会占住 AudioDriver 的锁做完整解码(可能几百毫秒
			//    ~数秒),期间 GL 线程再碰 audio 就会被挡住,没必要在退出时还锁一把。
			if (disposed) return false;
			main.getAudioProcessor().setModel(currentModel);
			resource.setPlayMode(BMSPlayerMode.AUTOPLAY);

			// 8. 计算新时长:对齐 BMSPlayer 公式 Math.max(lastEventTime + 1000, lastNoteTime + tail)
			final int lastEventTime = currentModel.getLastTime();
			final int lastNoteTime = currentModel.getLastNoteTime();
			int tail = currentSong.getTail();
			if (tail <= 0) {
				// 这一段会读几千个音频文件的头 + 写 SQLite,包一层容错:
				// 后台数据库被别的线程占用时抛异常不能把整个切歌流程带崩
				try {
					tail = calculateMaxTailMs(currentModel, lastNoteTime);
					currentSong.setTail(tail);
					main.getSongDatabase().updateSongTail(currentModel.getSHA256(), tail);
				} catch (Throwable e) {
					if (Gdx.app != null) {
						Gdx.app.error("MusicPlayer", "tail calculation failed", e);
					}
					if (tail <= 0) tail = 1000;
				}
			}
			this.totalDurationMs = Math.max(lastEventTime + 1000, lastNoteTime + tail);
			Gdx.app.log("MusicPlayer", "transitionToNext totalDurationMs: " + totalDurationMs + " ms, tail: " + tail + ", lastNoteTime: " + lastNoteTime + ", lastEventTime: " + lastEventTime);

			// 9. 启动新线程。dispose() 可能在第 6~8 步期间被调用过,这里再确认一次,
			//    否则会起出一个没人管的孤儿播放线程。
			if (disposed) return false;
			this.playBaseNanos = System.nanoTime();
			startBgThread(currentModel);

			// 10. 复位频谱(纯内存,无 GL)
			for (int i = 0; i < SPEC_BANDS; i++) {
				specBands[i] = 0f;
				specTopValues[i] = 0f;
			}
			return true;
		} finally {
			isTransitioning = false;
		}
	}
}
