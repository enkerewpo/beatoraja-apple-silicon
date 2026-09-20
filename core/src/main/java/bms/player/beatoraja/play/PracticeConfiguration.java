package bms.player.beatoraja.play;

import java.io.*;
import java.util.function.*;
import java.util.logging.Logger;

import bms.model.BMSModel;
import bms.model.Mode;
import bms.model.TimeLine;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainController;
import bms.player.beatoraja.MainState;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.input.KeyBoardInputProcesseor.ControlKeys;
import bms.player.beatoraja.skin.SkinNoteDistributionGraph;
import bms.player.beatoraja.skin.Skin;
import bms.player.beatoraja.skin.SkinHeader;
import bms.player.beatoraja.skin.Skin.SkinObjectRenderer;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator;
import com.badlogic.gdx.graphics.g2d.freetype.FreeTypeFontGenerator.FreeTypeFontParameter;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.utils.GdxRuntimeException;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.SerializationException;

/**
 * プラクティスモードの設定表示/編集用クラス
 *
 * @author exch
 */
public final class PracticeConfiguration {


	private BitmapFont titlefont;

	private int cursorpos = 0;
	private long presscount = 0;

	private BMSModel model;

	private static final String[] GAUGE = { "ASSIST EASY", "EASY", "NORMAL", "HARD", "EX-HARD", "HAZARD", "GRADE",
			"EX GRADE", "EXHARD GRADE"};
	private static final String[] RANDOM = { "NORMAL", "MIRROR", "RANDOM", "R-RANDOM", "S-RANDOM", "SPIRAL", "H-RANDOM",
			"ALL-SCR", "RANDOM-EX", "S-RANDOM-EX" };
	private static final String[] DPRANDOM = { "NORMAL", "FLIP" };

	private PracticeProperty property = new PracticeProperty();

	public PracticeConfiguration() {
		// TODO 描画位置、使用テキスト等をスキン定義できるように
		// TODO スキン定義がない場合のデフォルト配置の定義
	}

	private SkinNoteDistributionGraph[] graph = {
			new SkinNoteDistributionGraph(SkinNoteDistributionGraph.TYPE_NORMAL, 500, 0, 0, 0, 0),
			new SkinNoteDistributionGraph(SkinNoteDistributionGraph.TYPE_JUDGE, 500, 0, 0, 0, 0),
			new SkinNoteDistributionGraph(SkinNoteDistributionGraph.TYPE_EARLYLATE, 500, 0, 0, 0, 0),
	};

	private static final String[] GRAPHTYPESTR = {"NOTETYPE", "JUDGE", "EARLYLATE"};

	public static final PracticeElement[] elements = PracticeElement.values();

	public void create(BMSModel model, MainController main) {
		property.judgerank = model.getJudgerank();
		property.endtime = model.getLastTime() + 1000;
		File p = new File("practice/" + model.getSHA256() + ".json");
		if (p.exists()) {
			Json json = new Json();
			try {
				property = json.fromJson(PracticeProperty.class, new FileReader(p));
			} catch (FileNotFoundException | SerializationException e) {
				e.printStackTrace();
			}
		}

		if(property.gaugecategory == null) {
			property.gaugecategory = BMSPlayerRule.getBMSPlayerRule(model.getMode()).gauge;
		}
		this.model = model;
		if(property.total == 0) {
			property.total = model.getTotal();
		}
		// 注意：这里不再缓存字体引用 —— titlefont 改为 draw() 每帧从全局缓存重取，
		// 否则 MainController.resume()（切后台→切回）重建 systemfont18 后，
		// 旧引用指向已 dispose 的纹理，参数文字会整体消失

		for(int i = 0; i < graph.length; i++) {
			graph[i].setDestination(0, 0, 0, 0, 0, 0, 255, 255, 255, 255, 0, 0, 0, 0, 0, 0, new int[0]);
		}
	}

	public void saveProperty() {
		new File("practice").mkdirs();
		try (FileWriter fw = new FileWriter("practice/" + model.getSHA256() + ".json")) {
			Json json = new Json();
			fw.write(json.prettyPrint(property));
			fw.flush();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public PracticeProperty getPracticeProperty() {
		return property;
	}

	public GrooveGauge getGauge(BMSModel model) {
		GrooveGauge gauge = GrooveGauge.create(model, property.gaugetype, property.gaugecategory);
		gauge.setValue(property.startgauge);
		return gauge;
	}

	public void processInput(BMSPlayerInputProcessor input) {
		if (input.isControlKeyPressed(ControlKeys.UP)) {
			do {
				cursorpos = (cursorpos + elements.length - 1) % elements.length;
			} while(!elements[cursorpos].predicate.test(this));
		}
		if (input.isControlKeyPressed(ControlKeys.DOWN)) {
			do {
				cursorpos = (cursorpos + 1) % elements.length;
			} while(!elements[cursorpos].predicate.test(this));
		}
		if (input.getControlKeyState(ControlKeys.LEFT) && (presscount == 0 || presscount + 10 < System.currentTimeMillis())) {
			if (presscount == 0) {
				presscount = System.currentTimeMillis() + 500;
			} else {
				presscount = System.currentTimeMillis();
			}
			elements[cursorpos].action.accept(this, false);
		} else if (input.getControlKeyState(ControlKeys.RIGHT) && (presscount == 0 || presscount + 10 < System.currentTimeMillis())) {
			if (presscount == 0) {
				presscount = System.currentTimeMillis() + 500;
			} else {
				presscount = System.currentTimeMillis();
			}
			elements[cursorpos].action.accept(this, true);
		} else if (!(input.getControlKeyState(ControlKeys.LEFT) || input.getControlKeyState(ControlKeys.RIGHT))) {
			presscount = 0;
		}
	}

	public void draw(Rectangle r, SkinObjectRenderer sprite, long time, MainState state) {
		// 每帧重取字体：切后台再切回时 MainController.resume() 会重建 systemfont18
		// （旧 BitmapFont 被 dispose、生成新对象），缓存的旧引用会画不出任何文字
		titlefont = state.main.getSystemFont18();
		updateLayoutMode(state.getSkin());
		updateTextAlpha(state);
		float x = r.x + r.width / 8;
		float y = r.y + r.height * 7 / 8;
		if (textAngle != 0f) {
			// 竖屏：整块文字跟着转 270°。旋转后"换行方向"变成皮肤 -x
			// （皮肤坐标 x = 设备水平方向，note 也是沿 x 下落的），
			// 所以锚点就是文字块的最右侧 —— 取到靠近右边缘，左边留出 13 行行宽。
			x = r.x + r.width - 40;
		}
		if(titlefont != null) {
			for(int i = 0;i < elements.length;i++) {
				if(elements[i].predicate.test(this)) {
					drawText(sprite, elements[i].text.apply(property), x, y, 0, 22 * i,
							cursorpos == i ? Color.YELLOW : Color.CYAN);
				}
			}

			if (state.resource.mediaLoadFinished()) {
				drawText(sprite, "PRESS 1KEY TO PLAY", x, y, 0, 276, Color.ORANGE);
			}

			String[] judge = {"PGREAT :","GREAT  :","GOOD   :", "BAD    :", "POOR   :", "KPOOR  :"};
			for(int i = 0; i < 6; i++) {
				drawText(sprite, String.format("%s %d %d %d",judge[i], state.getJudgeCount(i, true) + state.getJudgeCount(i, false), state.getJudgeCount(i, true), state.getJudgeCount(i, false)),
						x, y, 250, i * 22, Color.WHITE);
			}
		}

		// 触摸版皮肤不画 note 密度图：它会整块压在轨道上，而参数文字已经够用
		if (drawGraph) {
			graph[property.graphtype].draw(sprite, time, state, new Rectangle(r.x, r.y, r.width, r.height / 4), property.starttime,
					property.endtime, property.freq / 100f);
		}
	}

	// ─────────────────── 呈现方式（按皮肤决定）───────────────────

	/** 判定结果对应的皮肤，避免每帧重复解析皮肤选项 */
	private Skin layoutSkin;
	/** 触摸版皮肤（GenericTheme for Touchscreen 等）：叠加在最上层 + 半透明 + 不画密度图 */
	private boolean touchScreen;
	/**
	 * 文字透明度：触摸皮肤演奏中 0.2，其他皮肤 1。
	 * 每帧由 {@link #updateTextAlpha(MainState)} 决定 —— 刚进练习模式时是全不透明的。
	 */
	private float textAlpha = 1f;
	/** 是否绘制 note 分布图 */
	private boolean drawGraph = true;
	/** 文字旋转角（度）：0 = 不旋转，270 = 竖屏 */
	private float textAngle = 0f;
	private final GlyphLayout textLayout = new GlyphLayout();
	private final Color tempColor = new Color();

	/**
	 * 面板是否必须画在皮肤<b>之后</b>（屏幕最上层），而不是待在 BGA 层里。
	 *
	 * <p>触摸版皮肤的轨道背景是一整块<b>不透明</b>矩形（竖屏时铺满全屏），
	 * 参数面板画在 BGA 层会被它整块盖住 —— 这就是"练习模式看不到参数调整"的原因。
	 * 配合演奏开始后的 20% 透明度（调参数阶段仍是 100%）与去掉密度图，
	 * 叠加在最上层也不会太挡 note。</p>
	 */
	public boolean isOverlayOnTop(Skin skin) {
		updateLayoutMode(skin);
		return touchScreen;
	}

	private void updateLayoutMode(Skin skin) {
		if (skin == layoutSkin) {
			return;
		}
		layoutSkin = skin;
		touchScreen = isTouchscreenSkin(skin);
		drawGraph = !touchScreen;
		textAngle = touchScreen && isPortraitLayout(skin) ? 270f : 0f;
	}

	/**
	 * 更新参数文字透明度：触摸版皮肤演奏中 20%（面板叠加在屏幕最上层，太亮会挡住 note），
	 * 但<b>刚进入练习模式、还在调参数</b>时必须给足 100%，否则参数看不清。
	 *
	 * <p>判据取演奏状态：{@code BMSPlayer.getState() >= STATE_READY}（GET READY 起）才降回
	 * 20%；之前的 {@code STATE_PRELOAD} / {@code STATE_PRACTICE}（调参数）都保持 100%。
	 * 练习结束（FAILED / FINISHED）后 BMSPlayer 会回到 {@code STATE_PRACTICE} 重新调参，
	 * 那时自动恢复 100%。非触摸皮肤恒为 100%。</p>
	 */
	private void updateTextAlpha(MainState state) {
		textAlpha = touchScreen && isPlayStarted(state) ? 0.20f : 1f;
	}

	private static boolean isPlayStarted(MainState state) {
		return (state instanceof BMSPlayer) && ((BMSPlayer) state).getState() >= BMSPlayer.STATE_READY;
	}

	/** 皮肤名里带 Touchscreen 的按触摸版处理 */
	private static boolean isTouchscreenSkin(Skin skin) {
		if (skin == null || skin.header == null || skin.header.getName() == null) {
			return false;
		}
		return skin.header.getName().toLowerCase().contains("touchscreen");
	}

	/** 皮肤自己的 Layout 选项选了 Portrait → 画面相对设备旋转了 270° */
	private static boolean isPortraitLayout(Skin skin) {
		if (skin == null || skin.header == null) {
			return false;
		}
		SkinHeader.CustomOption[] options = skin.header.getCustomOptions();
		if (options == null) {
			return false;
		}
		for (SkinHeader.CustomOption option : options) {
			if (option == null || !"layout".equalsIgnoreCase(option.name) || option.contents == null) {
				continue;
			}
			for (int i = 0; i < option.contents.length && i < option.option.length; i++) {
				if ("portrait".equalsIgnoreCase(option.contents[i])) {
					return option.option[i] == option.getSelectedOption();
				}
			}
		}
		return false;
	}

	/**
	 * 绘制一段文字。{(u, v)} 是未旋转时的"阅读方向 / 换行方向"偏移量，
	 * 会按 {@link #textAngle} 一起旋转，所以旋转后行列关系仍然正确。
	 */
	private void drawText(SkinObjectRenderer sprite, String text, float ox, float oy, float u, float v, Color color) {
		final Color c = tint(color);
		if (textAngle == 0f) {
			sprite.draw(titlefont, text, ox + u, oy - v, c);
			return;
		}
		final double rad = Math.toRadians(textAngle);
		final float cr = (float) Math.cos(rad);
		final float sr = (float) Math.sin(rad);
		// 阅读方向 (1,0) → (cr, sr)；换行方向 (0,-1) → (sr, -cr)
		final float px = ox + u * cr + v * sr;
		final float py = oy + u * sr - v * cr;
		// 旋转重载本身不设置颜色，颜色取自 font：先设色再排版（GlyphLayout 在 setText 时取色）
		titlefont.setColor(c);
		textLayout.setText(titlefont, text);
		sprite.draw(titlefont, textLayout, px, py, px, py, textAngle);
	}

	/** 按 textAlpha 调暗（触摸皮肤演奏中 20% 不透明度） */
	private Color tint(Color base) {
		if (textAlpha >= 1f) {
			return base;
		}
		return tempColor.set(base.r, base.g, base.b, base.a * textAlpha);
	}

	public void dispose() {
		// Font is now globally cached in MainController - don't dispose it here
		// if(titlefont != null) {
		// 	titlefont.dispose();
		// 	titlefont = null;
		// }
	}

	enum PracticeElement {

		STARTTIME((practice, inc) -> {
			final TimeLine[] tl = practice.model.getAllTimeLines();
			final PracticeProperty property = practice.property;
			if(inc) {
				if (property.starttime + 2000 <= tl[tl.length - 1].getTime()) {
					property.starttime += 100;
				}
				if (property.starttime + 900 >= property.endtime) {
					property.endtime += 100;
				}
			} else {
				if (property.starttime >= 100) {
					property.starttime -= 100;
				}
			}
		}, property -> String.format("START TIME : %2d:%02d.%1d", property.starttime / 60000,
				(property.starttime / 1000) % 60, (property.starttime / 100) % 10)),
		ENDTIME((practice, inc) -> {
			final TimeLine[] tl = practice.model.getAllTimeLines();
			final PracticeProperty property = practice.property;
			if(inc) {
				if (property.endtime <= tl[tl.length - 1].getTime() + 1000) {
					property.endtime += 100;
				}
			} else {
				if (property.endtime > property.starttime + 1000) {
					property.endtime -= 100;
				}
			}
		}, property -> String.format("END TIME : %2d:%02d.%1d", property.endtime / 60000,
				(property.endtime / 1000) % 60, (property.endtime / 100) % 10)),
		GAUGETYPE((practice, inc) -> {
			final PracticeProperty property = practice.property;
			property.gaugetype = (property.gaugetype + (inc ? 1 : 8)) % 9;
			if ((practice.model.getMode() == Mode.POPN_5K || practice.model.getMode() == Mode.POPN_9K) && property.gaugetype >= 3 && property.startgauge > 100) {
				property.startgauge = 100;
			}
		}, property -> "GAUGE TYPE : " + GAUGE[property.gaugetype]),
		GAUGECATEGORY((practice, inc) -> {
			final PracticeProperty property = practice.property;
			GaugeProperty[] cateories = GaugeProperty.values();
			for(int i = 0;i < cateories.length;i++) {
				if(property.gaugecategory == cateories[i]) {
					property.gaugecategory = cateories[(i + (inc ? 1 : (cateories.length - 1))) % cateories.length];
					break;
				}
			}
			property.startgauge = (int) property.gaugecategory.values[property.gaugetype].init;
		}, property -> "GAUGE CATEGORY : " + property.gaugecategory.name()),
		GAUGEVALUE((practice, inc) -> {
			final PracticeProperty property = practice.property;
			property.startgauge = MathUtils.clamp(property.startgauge + (inc ? 1 : -1), 1, (int)property.gaugecategory.values[property.gaugetype].max);
		}, property -> "GAUGE VALUE : " + property.startgauge),
		JUDGERANK((practice, inc) -> {
			practice.property.judgerank = MathUtils.clamp(practice.property.judgerank + (inc ? 1 : -1), 1, 400);
		}, property -> "JUDGERANK : " + property.judgerank),
		TOTAL((practice, inc) -> {
			practice.property.total = MathUtils.clamp(practice.property.total + (inc ? 10 : -10), 20, 5000);
		}, property -> "TOTAL : " + (int)property.total),
		FREQ((practice, inc) -> {
			practice.property.freq = MathUtils.clamp(practice.property.freq + (inc ? 5 : -5), 50, 200);
		}, property -> "FREQUENCY : " + property.freq),
		GRAPHTYPE((practice, inc) -> {
			practice.property.graphtype = (practice.property.graphtype + (inc ? 1 : 2)) % 3;
		}, property -> "GRAPHTYPE : " + GRAPHTYPESTR[property.graphtype]),
		OPTION1P((practice, inc) -> {
			final int options = (practice.model.getMode() == Mode.POPN_5K || practice.model.getMode() == Mode.POPN_9K ? 7 : 10);
			practice.property.random = (practice.property.random + (inc ? 1 : (options -1))) % options;
		}, property -> "OPTION-1P : " + RANDOM[property.random]),
		OPTION2P((practice, inc) -> {
			practice.property.random2 = (practice.property.random2 + (inc ? 1 : 9)) % 10;
		}, property -> "OPTION-2P : " + RANDOM[property.random2], practice -> practice.model.getMode().player == 2),
		OPTIONDP((practice, inc) -> {
			practice.property.doubleop = (practice.property.doubleop + 1) % 2;
		}, property -> "OPTION-DP : " + DPRANDOM[property.doubleop], practice -> practice.model.getMode().player == 2);

		public final BiConsumer<PracticeConfiguration, Boolean> action;

		public final Function<PracticeProperty, String> text;

		public final Predicate<PracticeConfiguration> predicate;

		private PracticeElement(BiConsumer<PracticeConfiguration, Boolean> action, Function<PracticeProperty, String> text) {
			this(action, text, property -> true);
		}

		private PracticeElement(BiConsumer<PracticeConfiguration, Boolean> action, Function<PracticeProperty, String> text, Predicate<PracticeConfiguration> predicate) {
			this.action = action;
			this.text = text;
			this.predicate = predicate;
		}
	}
	/**
	 * プラクティスの各種設定値
	 *
	 * @author exch
	 */
	public static class PracticeProperty {

		/**
		 * 演奏開始時間
		 */
		public int starttime = 0;
		/**
		 * 演奏終了時間
		 */
		public int endtime = 10000;
		/**
		 * 選択ゲージカテゴリ
		 */
		public GaugeProperty gaugecategory;
		/**
		 * 選択ゲージタイプ
		 */
		public int gaugetype = 2;
		/**
		 * 開始ゲージ量
		 */
		public int startgauge = 20;
		/**
		 * 1P側オプション
		 */
		public int random = 0;
		/**
		 * 2P側オプション
		 */
		public int random2 = 0;
		/**
		 * DPオプション
		 */
		public int doubleop = 0;
		/**
		 * 判定幅
		 */
		public int judgerank = 100;
		/**
		 * 再生速度倍率
		 */
		public int freq = 100;
		/**
		 * TOTAL値
		 */
		public double total = 0;
		/**
		 *
		 */
		public int graphtype = 0;
	}
}
