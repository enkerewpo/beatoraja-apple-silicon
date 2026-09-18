package bms.player.beatoraja;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input.Keys;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.*;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;

import java.util.ArrayList;

import bms.player.beatoraja.input.KeyBoardInputProcesseor;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.rating.PlayerRatingService;
import com.starxh.beatoraja.InGameSpectrumConfig;

/**
 * Android 用浮动快捷键菜单。
 * <p>
 * 右上角显示一个可点击展开的浮动图标，展开后显示一组快捷键按钮。
 * 所有坐标基于逻辑分辨率，使用 MainController 的 SpriteBatch 渲染。
 * 按键模拟通过 {@link KeyBoardInputProcesseor#simulateKeyPress(int)} 实现。
 * 实现 {@link InputProcessor} 以拦截触摸事件，阻止穿透到底层游戏。
 */
public class FloatingMenu implements InputProcessor {

    // ─── 逻辑分辨率（由 MainController 通过 setViewport 同步）───
    private int logicW = 1920;
    private int logicH = 1080;

    // ─── 视口参数（由 MainController 每帧更新，用于触摸坐标转换）───
    private int vpX, vpY, vpW, vpH;

    // ─── 浮动图标 ───
    private static final float ICON_SIZE = 77;           // 增大 10%
    private static final float ICON_MARGIN = 12;
    private float iconX, iconY;                          // 左下角坐标（逻辑坐标）

    // ─── 菜单面板 ───
    private static final float BTN_W = 209;              // 缩小 30%
    private static final float BTN_H = 80;               // 缩小 30%
    private static final float BTN_GAP = 10;             // 缩小 30%
    private static final float PANEL_PAD = 24;           // 缩小 30%

    private boolean expanded = false;
    private boolean visible = true;                     // PLAY 状态时隐藏
    private boolean selectMode = false; // 是否为 MusicSelect 界面
    private boolean keyConfigMode = false; // 是否为 KeyConfig 界面
    private boolean skinSelectMode = false; // 是否为 SkinSelect 界面（皮肤选择/配置）
    private boolean isPlayMode = false; // 是否为 Play 界面
    /** 是否为 Practice 模式（PLAY 界面且 resource.getPlayMode()==PRACTICE）。
     *  Practice 下浮动图标<b>常驻显示</b>（不参与 PLAY 的自动隐藏），
     *  并以 {@link #PRACTICE_ICON_ALPHA} 的不透明度绘制 —— 仅对 practice 生效，
     *  普通游玩 / AUTOPLAY / REPLAY 不受影响。 */
    private boolean practiceMode = false;
    /** Practice 模式下浮动图标的绘制不透明度（30%，常驻但不抢视线） */
    private static final float PRACTICE_ICON_ALPHA = 0.3f;
    /** Play 模式时：距上次交互超过此时间则自动隐藏图标（秒） */
    private static final float HIDE_DELAY = 0f;
    /** Play 模式时：距上次交互已过时间（秒） */
    private float sinceLastInteraction = 0f;
    /** Play 模式时：图标是否因超时被隐藏（点击图标区域可重新显示） */
    private boolean playIconHidden = false;

    // ─── In-Game Spectrum 调整页（独立模态页，不参与通用分页）───
    /**
     * 调整页是否打开。<b>仅 PLAY 界面可进入</b>：入口项 keycode 为
     * {@link #SPECTRUM_ENTRY_KEYCODE}，被 {@link #isItemVisible} 限制为只有
     * isPlayMode 时才出现；离开 PLAY（setPlayMode(false)）时强制收起，
     * 避免面板残留在结果等界面。
     */
    private boolean spectrumAdjustOpen = false;
    /** 当前编辑的四个值 X/Y/W/H（进入页面时从"生效值"载入，编辑期间以这份为准） */
    private final int[] spectrumValues = new int[4];
    /** 正在长按的字段（-1 = 没有）与方向（-1 / +1） */
    private int spectrumHoldField = -1;
    private int spectrumHoldDir = 0;
    private long spectrumHoldStartNs = 0;
    private long spectrumHoldLastRepeatNs = 0;
    private static final long SPECTRUM_HOLD_DELAY_NS = 300_000_000L;   // 按住 300ms 后开始连发
    private static final long SPECTRUM_HOLD_REPEAT_NS = 60_000_000L;   // 连发间隔
    private static final long SPECTRUM_HOLD_ACCEL_NS = 400_000_000L;   // 每 400ms 加速一倍
    private static final int SPECTRUM_HOLD_MAX_MULT = 64;
    /** In-Game Spectrum 调整页入口项的 keycode（isItemVisible 用它做"仅 PLAY"判定） */
    private static final int SPECTRUM_ENTRY_KEYCODE = -135;
    /** Walkure（玩家实力表）按钮的 keycode —— 也作为 Show FPS 在 PLAY 界面的占位锚点 */
    private static final int WALKURE_KEYCODE = -140;
    /** 每个指针当前按下的频谱单元格编码（row*10+col，-1 = 无），仅用于按下高亮 */
    private final int[] pointerSpectrumCell = new int[20];

    // ─── 频谱调整页布局常量 ───
    private static final int SPECTRUM_COLS = 3;      // 值 / [-] / [+]
    private static final int SPECTRUM_ROWS = 4;      // X / Y / W / H
    private static final float SPECTRUM_TITLE_H = 44;
    /** 编码：row*10 + col，col: 0=值 1=[-] 2=[+]；特殊值见 SPECTRUM_HIT_* */
    private static final int SPECTRUM_HIT_NONE = -1;    // 面板内但没点到按钮
    private static final int SPECTRUM_HIT_OUTSIDE = -2; // 面板外
    private static final int SPECTRUM_HIT_BACK = -3;    // 标题栏返回

    // ─── 分页 ───
    private static final int ITEMS_PER_PAGE = 12;  // 每页12个：2列×6行普通，或3列×4行频谱调整页
    private int currentPage = 0;

    // ─── 纹理 ───
    private Texture iconTexture;
    private Texture whitePixel;
    private BitmapFont font; // 用于 hitTestPanel 计算文字宽度

    // ─── 按钮定义 ───
    private static class MenuItem {
        /** playInsertBefore 的默认值：不在 PLAY 界面改变顺序 */
        static final int NO_PLAY_ORDER = Integer.MIN_VALUE;
        String label;
        final int keycode;
        final boolean isToggle;
        final boolean showOnSelect;       // 是否在 MusicSelect 界面显示
        final boolean showOnKeyConfig;    // 是否在 KeyConfig 界面显示
        final boolean showOnPlay;         // 是否在 Play 界面显示
        final boolean showOnSkinSelect;   // 是否在 SkinSelect 界面显示（默认 false，避免误显示）
        /**
         * 仅 PLAY 界面的顺序覆盖：填某个 keycode 时，本项在 PLAY 界面被插到该项之前；
         * 其他界面一律保持 {@link #items} 数组里的原顺序。
         * 用于"某按钮在选曲界面位置不变、但在 PLAY 界面要挪到别处"这类需求。
         */
        final int playInsertBefore;
        MenuItem(String label, int keycode) { this(label, keycode, false, true, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle) { this(label, keycode, isToggle, true, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect) { this(label, keycode, isToggle, showOnSelect, true, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig) { this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, true, false); }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay) {
            this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, false);
        }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay, boolean showOnSkinSelect) {
            this(label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, showOnSkinSelect, NO_PLAY_ORDER);
        }
        MenuItem(String label, int keycode, boolean isToggle, boolean showOnSelect, boolean showOnKeyConfig, boolean showOnPlay, boolean showOnSkinSelect, int playInsertBefore) {
            this.label = label; this.keycode = keycode; this.isToggle = isToggle;
            this.showOnSelect = showOnSelect; this.showOnKeyConfig = showOnKeyConfig;
            this.showOnPlay = showOnPlay; this.showOnSkinSelect = showOnSkinSelect;
            this.playInsertBefore = playInsertBefore;
        }
    }

    // 频谱调整：In-Game Spectrum 入口(-135) 打开独立模态页（drawSpectrumPage /
    // hitTestSpectrumPage），页内按钮不与通用分页列表共享索引空间。
    private final MenuItem[] items = {
        // ── 通用按钮（第1页）───────────────────────
        // 构造参数: (label, keycode, isToggle, showOnSelect, showOnKeyConfig, showOnPlay, showOnSkinSelect)
        new MenuItem("Touch Key: ON",  -100, true,  true, false, true, false),
        // Walkure 在 PLAY 界面隐藏：那一格由 Show FPS 占用（见其 playInsertBefore）。
        new MenuItem("Walkure",   WALKURE_KEYCODE, false, true, false, false, false),
        new MenuItem("Update Song",   Keys.F2, false, true, false, true, false),
        new MenuItem("Music Player",   -130, false, true, false, true, false),
        new MenuItem("Skin Select",   Keys.F12, false, true, false, true, false),
        new MenuItem("Key Config", Keys.NUM_6, false, true, false, true, true),
        new MenuItem("PLAYOPTION 1", Keys.NUM_5, false, true, false, false, true),
        new MenuItem("Backspace",        Keys.BACKSPACE, false, false, false, false, false),
        new MenuItem("ESC",   Keys.ESCAPE, false, false, true, true, true),
        new MenuItem("Enter",            Keys.ENTER, false, false, true, true, true),
        new MenuItem("PLAYOPTION 2",    -141, false, true, false, false, true),
        new MenuItem("^ UP",        Keys.UP, false, true, true, true, true),
        new MenuItem("v DOWN",      Keys.DOWN, false, true, true, true, true),
        new MenuItem("< LEFT",      Keys.LEFT, false, true, true, true, true),
        new MenuItem("> RIGHT",     Keys.RIGHT, false, false, true, true, true),
        // Show FPS：数组里排在末尾（选曲等界面维持原位置不变）；
        // PLAY 界面用 playInsertBefore 占用 Walkure 那一格（Walkure 在 PLAY 已隐藏）。
        new MenuItem("Show FPS",      Keys.F1, false, true, false, true, false, WALKURE_KEYCODE),
        // ── In-Game Spectrum 调整入口（仅 PLAY 界面；见 isItemVisible）──
        // 调整界面是独立模态页（3列×4行，见 drawSpectrumPage/hitTestSpectrumPage），
        // 不再把 12 个 +/- 按钮塞进通用分页列表 —— 那套做法混用了"可见项索引"与
        // "数组索引"，导致列错位、按 Y 的 ± 会把 X 的 + 覆盖掉。
        // showOnSelect=false + isItemVisible 的"仅 PLAY"硬规则共同把它限制在游玩界面。
        new MenuItem("In-Game Spectrum", SPECTRUM_ENTRY_KEYCODE, false, false, false, true, false),

        // ── Controller Reset（仅KeyConfig模式）──
        new MenuItem("NUM 8", Keys.NUM_8, false, false, true, false, false),
        new MenuItem("NUM 2", Keys.NUM_2, false, false, true, false, false),
        new MenuItem("DELETE", Keys.FORWARD_DEL, false, false, true, false, false),

    };

    private final Matrix4 menuProj = new Matrix4();

    // ─── 触摸与反馈状态 ───
    private float lastTouchX = 0, lastTouchY = 0;
    /** 每个指针是否被菜单消费（阻止穿透到游戏层） */
    private boolean[] pointerConsuming = new boolean[20];
    /** 每个指针正按下的按钮索引（-1 表示未按下按钮） */
    private int[] pointerPressedIndex = new int[20];
    /** 每个指针在 7K 覆盖层中按下的键索引（-1 表示未按下任何 7K 键） */
    private int[] pointer7KKey = new int[20];
    /** 每个按钮点击后的临时高亮计时器 */
    private final float[] flashTimers = new float[items.length];
    private static final float FLASH_DURATION = 0f; // 亮起常驻
    /** 标记是否刚通过图标点击展开了菜单，用于在 touchUp 时忽略图标区域的抬起事件 */
    private boolean justExpandedByIcon = false;

    // ─── NUM5 长按模式（7K 覆盖层） ───
    /** 长按模式类型：NONE=未激活，NUM5=模拟 NUM5 长按，START=模拟 START 长按 */
    private enum HoldKeyType { NONE, NUM5, START }
    /** 当前正在长按模拟的按键类型 */
    private HoldKeyType holdKeyType = HoldKeyType.NONE;
    /** 是否处于长按状态（按下未释放），决定 7K 覆盖层是否显示 */
    private boolean holdKeyHeld = false;
    /** 7KEYS 默认标签（仅在无法读取 kbInput 配置时回退使用） */
    private static final int[] SEVEN_KEYS_KEYCODES_DEFAULT = {
        Keys.Z, Keys.S, Keys.X, Keys.D, Keys.C, Keys.F, Keys.V
    };
    /** 缓存 7K 各键显示名称，避免每帧 Keys.toString 分配 String 触发 GC 压力 */
    private final String[] cached7KKeyNames = new String[7];
    /** 缓存 7K 各键当前 keycode（-1 表示未初始化），用于检测配置变更以刷新名称 */
    private final int[] cached7KKeycodes = new int[] {-1, -1, -1, -1, -1, -1, -1};
    /** 覆盖层整体目标占比（屏幕短边），用于按屏幕尺寸自适应 */
    private static final float K7_TARGET_SCREEN_RATIO = 0.30f;
    private static final float K7_BTN_GAP = 8;
    /** 标题/关闭按钮固定高度 */
    private static final float K7_TITLE_H = 32;
    private static final float K7_CLOSE_H = 44;

    // ─── 引用 ───
    private KeyBoardInputProcesseor kbInput;

    public FloatingMenu() {
        updateIconPosition();
        createTextures();
        for (int i = 0; i < pointer7KKey.length; i++) {
            pointer7KKey[i] = -1;
        }
    }

    /** 提供键盘输入处理器引用（用于 simulateKeyPress） */
    public void setKeyboardInput(KeyBoardInputProcesseor kb) {
        this.kbInput = kb;
        // 初始化 Touch Key 按钮状态
        Object mc = kb.getMainController();
        if (mc instanceof MainController) {
            Config config = ((MainController) mc).getConfig();
            if (config != null) {
                items[0].label = "Touch Key: " + (config.isShowTouchKey() ? "ON" : "OFF");
            }
        }
    }

    /**
     * 每帧由 MainController 调用，同步视口参数。
     * 确保触摸坐标转换与游戏实际视口一致。
     */
    public void setViewport(int vpX, int vpY, int vpW, int vpH, int logicW, int logicH) {
        this.vpX = vpX;
        this.vpY = vpY;
        this.vpW = vpW;
        this.vpH = vpH;
        if (this.logicW != logicW || this.logicH != logicH) {
            this.logicW = logicW;
            this.logicH = logicH;
            updateIconPosition();
        }
    }

    private void updateIconPosition() {
        Config config = null;
        if (kbInput != null && kbInput.getMainController() instanceof MainController) {
            config = ((MainController) kbInput.getMainController()).getConfig();
        }

        int pos = (config != null) ? config.getFloatingMenuPosition() : 0;
        switch (pos) {
            case 1: // Top Right
                iconX = logicW - ICON_SIZE - ICON_MARGIN;
                iconY = logicH - ICON_SIZE - ICON_MARGIN;
                break;
            case 2: // Bottom Center
                iconX = (logicW - ICON_SIZE) / 2;
                iconY = ICON_MARGIN;
                break;
            case 3: // Bottom Right
                iconX = logicW - ICON_SIZE - ICON_MARGIN;
                iconY = ICON_MARGIN;
                break;
            default: // 0: Top Center
                iconX = (logicW - ICON_SIZE) / 2;
                iconY = logicH - ICON_SIZE - ICON_MARGIN;
                break;
        }
    }

    /** PLAY 状态时调用 setVisible(false) 隐藏 */
    public void setVisible(boolean v) {
        this.visible = v;
        // 移除 if (!v) expanded = false; 以保持展开状态
    }

    public boolean isVisible() { return visible; }

    /** 设置是否为 Select 界面（影响按钮过滤） */
    public void setSelectMode(boolean selectMode) {
        this.selectMode = selectMode;
        currentPage = 0; // 切换模式时重置页码
    }

    /** 设置是否为 KeyConfig 界面（影响按钮过滤） */
    public void setKeyConfigMode(boolean keyConfigMode) {
        this.keyConfigMode = keyConfigMode;
        currentPage = 0;
    }

    /** 设置是否为 SkinSelect 界面（影响按钮过滤） */
    public void setSkinSelectMode(boolean skinSelectMode) {
        this.skinSelectMode = skinSelectMode;
        currentPage = 0;
    }

    /** 设置是否为 Play 界面（复用一个通用的菜单，不单独处理） */
    public void setPlayMode(boolean playMode) {
        this.isPlayMode = playMode;
        // 不再单独处理：play 模式复用 selectMode 的菜单
        // 进入/退出 play 模式时重置超时状态
        if (playMode) {
            sinceLastInteraction = 0f;
            playIconHidden = false;
        } else if (spectrumAdjustOpen) {
            // 离开 PLAY（进结果、回选曲等）时强制收起调整页并落盘，
            // 否则模态页会残留在其他界面。MainController 只在状态切换时调用本方法。
            exitSpectrumAdjust();
        }
    }

    /** 设置是否为 Practice 模式（图标常驻显示 + 30% 不透明度，仅对 practice 生效） */
    public void setPracticeMode(boolean practiceMode) {
        this.practiceMode = practiceMode;
        if (practiceMode) {
            // 常驻显示：清掉 PLAY 模式的超时隐藏状态，避免进入 practice 时图标已被标记隐藏
            sinceLastInteraction = 0f;
            playIconHidden = false;
        }
    }

    /** 判断按钮是否在当前界面显示 */
    private boolean isItemVisible(MenuItem item) {
        if (selectMode && !item.showOnSelect) return false;
        if (keyConfigMode && !item.showOnKeyConfig) return false;
        if (skinSelectMode && !item.showOnSkinSelect) return false;
        if (isPlayMode && !item.showOnPlay) return false;
        // In-Game Spectrum 调整入口：只在 PLAY 界面出现。
        // 上面四条是"某模式生效时要求对应标记"，未设置任何模式的状态（RESULT 等）
        // 会全部放行，所以"仅 PLAY"必须是一条独立硬规则。
        if (item.keycode == SPECTRUM_ENTRY_KEYCODE && !isPlayMode) return false;
        return true;
    }

    /**
     * 组装当前界面"可见按钮"的原始索引序列。
     *
     * <p>顺序 = {@link #items} 数组顺序；仅 PLAY 界面额外应用
     * {@link MenuItem#playInsertBefore} 的顺序覆盖：声明该项的按钮<b>占用锚点项的位置</b>，
     * 这样"选曲等界面位置不变、PLAY 界面换个位置"的需求不必改数组顺序。</p>
     *
     * <p>锚点项本身在 PLAY 界面被隐藏时，它的<b>位置依然有效</b>
     * （例：Show FPS 占用已隐藏的 Walkure 那一格）。</p>
     *
     * <p>绘制（drawPanel）与命中判定（hitTestPanel）<b>必须</b>共用本方法，
     * 否则会出现"看到的按钮"和"点到的按钮"不一致。</p>
     */
    private int[] buildVisibleIndexOrder() {
        ArrayList<Integer> order = new ArrayList<>();
        for (int i = 0; i < items.length; i++) {
            MenuItem item = items[i];
            if (isPlayMode) {
                // 走到锚点位置：先把声明"占用这个位置"的可见项放进来。
                // 放在可见性判定之前，所以锚点自身被隐藏时其位置仍然可用。
                for (int k = 0; k < items.length; k++) {
                    if (items[k].playInsertBefore == item.keycode && isItemVisible(items[k])) {
                        order.add(k);
                    }
                }
                // 带顺序覆盖的项不在数组原位出现（已由锚点位置插入）
                if (item.playInsertBefore != MenuItem.NO_PLAY_ORDER) continue;
            }
            if (!isItemVisible(item)) continue;
            order.add(i);
        }

        int[] res = new int[order.size()];
        for (int j = 0; j < res.length; j++) res[j] = order.get(j);
        return res;
    }

    // ─────────────────── 纹理创建 ───────────────────

    private void createTextures() {
        int s = 96;
        Pixmap pm = new Pixmap(s, s, Pixmap.Format.RGBA8888);
        pm.setColor(0, 0, 0, 0);
        pm.fill();
        // 半透明深色圆角背景
        pm.setColor(0.15f, 0.15f, 0.2f, 0.65f);
        int cr = 14;
        pm.fillCircle(cr, cr, cr);
        pm.fillCircle(s - 1 - cr, cr, cr);
        pm.fillCircle(cr, s - 1 - cr, cr);
        pm.fillCircle(s - 1 - cr, s - 1 - cr, cr);
        pm.fillRectangle(cr, 0, s - 2 * cr, s);
        pm.fillRectangle(0, cr, s, s - 2 * cr);
        // 三条白色横杠
        pm.setColor(1, 1, 1, 0.9f);
        int barH = 7;
        int barW = s * 55 / 100;
        int barX = (s - barW) / 2;
        int gap = 16;
        int cy = s / 2;
        pm.fillRectangle(barX, cy - barH / 2, barW, barH);
        pm.fillRectangle(barX, cy - gap - barH - barH / 2, barW, barH);
        pm.fillRectangle(barX, cy + gap + barH / 2, barW, barH);
        iconTexture = new Texture(pm);
        pm.dispose();

        // 1×1 白色像素
        Pixmap wp = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        wp.setColor(1, 1, 1, 1);
        wp.fill();
        whitePixel = new Texture(wp);
        wp.dispose();
    }

    /** GL 上下文恢复后重建纹理 */
    public void rebuildTextures() {
        dispose();
        createTextures();
    }

    // ─────────────────── 渲染 ───────────────────

    /**
     * 每帧调用：绘制。
     * 触摸处理已通过 InputProcessor 事件驱动实现。
     *
     * @param sprite MainController 的 SpriteBatch
     * @param font   systemfont（24pt），用于按钮文字
     */
    public void render(SpriteBatch sprite, BitmapFont font) {
        this.font = font; // 保存 font 供 hitTestPanel 使用
        if (!visible) return;

        // 更新闪烁计时器
        float delta = Gdx.graphics.getDeltaTime();
        for (int i = 0; i < flashTimers.length; i++) {
            if (flashTimers[i] > 0) flashTimers[i] -= delta;
        }

        // 7K 覆盖层：检测已断开但未收到 touchUp 的指针（应用切后台等异常路径）
        if (holdKeyHeld) {
            for (int p = 0; p < pointer7KKey.length; p++) {
                int k7 = pointer7KKey[p];
                if (k7 >= 0 && (p >= pointerConsuming.length || !pointerConsuming[p] || !Gdx.input.isTouched(p))) {
                    send7KKey(k7, false);
                    pointer7KKey[p] = -1;
                }
            }
        }

        // Play 模式：无操作则自动隐藏图标（Practice 模式常驻显示，不参与自动隐藏）
        if (isPlayMode && !practiceMode && visible && !expanded) {
            sinceLastInteraction += delta;
            if (sinceLastInteraction >= HIDE_DELAY) {
                playIconHidden = true;
            }
        }

        // 频谱调整页：长按 [-] / [+] 连发（300ms 后开始，每 60ms 一次，按住越久步长越大）
        if (spectrumAdjustOpen && spectrumHoldField >= 0 && spectrumHoldDir != 0) {
            long now = System.nanoTime();
            long elapsed = now - spectrumHoldStartNs;
            if (elapsed >= SPECTRUM_HOLD_DELAY_NS
                    && now - spectrumHoldLastRepeatNs >= SPECTRUM_HOLD_REPEAT_NS) {
                spectrumHoldLastRepeatNs = now;
                long accelSteps = (elapsed - SPECTRUM_HOLD_DELAY_NS) / SPECTRUM_HOLD_ACCEL_NS;
                int mult = 1;
                for (long i = 0; i < accelSteps && mult < SPECTRUM_HOLD_MAX_MULT; i++) {
                    mult = Math.min(mult * 2, SPECTRUM_HOLD_MAX_MULT);
                }
                spectrumStep(spectrumHoldField, spectrumHoldDir * mult);
            }
        }
        // 指针异常丢失（切后台等）：停掉连发，避免一直改值
        if (spectrumHoldField >= 0 && !Gdx.input.isTouched()) {
            stopSpectrumHold(true);
        }

        // ─── 设置投影矩阵到逻辑坐标 ───
        sprite.setProjectionMatrix(menuProj.setToOrtho2D(0, 0, logicW, logicH));

        sprite.begin();
        // 确保使用正常的混合模式，防止皮肤（如 Note 爆发效果）残留的加算模式导致菜单发光
        sprite.setBlendFunction(GL20.GL_SRC_ALPHA, GL20.GL_ONE_MINUS_SRC_ALPHA);

        // Play 模式超时隐藏：图标不绘制，但仍响应触摸
        boolean showIcon = !(isPlayMode && playIconHidden);

        if (showIcon) {
            // 绘制浮动图标（Practice 模式用 30% 不透明度常驻显示）
            sprite.setColor(1, 1, 1, practiceMode ? PRACTICE_ICON_ALPHA : 0.55f);
            sprite.draw(iconTexture, iconX, iconY, ICON_SIZE, ICON_SIZE);
        }

        // 如果展开，绘制面板
        if (expanded && font != null) {
            drawPanel(sprite, font);
        }

        // 长按模式（NUM5/START）：绘制 7K 覆盖层
        if (holdKeyHeld && font != null) {
            draw7KOverlay(sprite, font);
        }

        sprite.setColor(1, 1, 1, 1);  // 重置颜色
        sprite.end();
    }

    private void drawPanel(SpriteBatch sprite, BitmapFont font) {
        // 0. 频谱调整页是独立模态页（自己的布局与命中判定），不参与通用分页列表
        if (spectrumAdjustOpen) {
            drawSpectrumPage(sprite, font);
            return;
        }

        // 1. 获取基础布局参数
        PanelLayout info = calculatePanelLayout();

        // 面板背景
        sprite.setColor(0.1f, 0.1f, 0.15f, 0.85f);
        sprite.draw(whitePixel, info.x, info.y, info.w, info.h);

        // 面板边框
        sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
        float border = 2;
        sprite.draw(whitePixel, info.x, info.y, info.w, border);
        sprite.draw(whitePixel, info.x, info.y + info.h - border, info.w, border);
        sprite.draw(whitePixel, info.x, info.y, border, info.h);
        sprite.draw(whitePixel, info.x + info.w - border, info.y, border, info.h);

        // 收集可见按钮索引（含 PLAY 界面的顺序覆盖；绘制与命中判定同一份顺序）
        int[] visibleIndices = buildVisibleIndexOrder();

        // GlyphLayout 在翻页和按钮中都要用
        GlyphLayout glyph = new GlyphLayout();

        // 绘制分页指示器和翻页按钮（面板顶部）
        if (info.totalPages > 1) {
            float pageY = info.y + info.h - PANEL_PAD - info.pageBarHeight;
            // 分页栏背景
            sprite.setColor(0.15f, 0.15f, 0.2f, 0.5f);
            sprite.draw(whitePixel, info.x + border, pageY, info.w - border * 2, info.pageBarHeight);

            // 左右翻页箭头
            sprite.setColor(0.4f, 0.6f, 1f, 0.9f);
            if (currentPage > 0) {
                font.setColor(0.5f, 0.8f, 1f, 0.9f);
                font.draw(sprite, "<", info.x + PANEL_PAD, pageY + info.pageBarHeight - 8);
            }
            if (currentPage < info.totalPages - 1) {
                font.setColor(0.5f, 0.8f, 1f, 0.9f);
                String rightArrow = ">";
                glyph.setText(font, rightArrow);
                float arrowX = info.x + info.w - PANEL_PAD - glyph.width;
                font.draw(sprite, rightArrow, arrowX, pageY + info.pageBarHeight - 8);
            }
            // 页码
            String pageText = (currentPage + 1) + "/" + info.totalPages;
            font.setColor(0.7f, 0.7f, 0.7f, 0.9f);
            glyph.setText(font, pageText);
            float pageTextX = info.x + (info.w - glyph.width) / 2;
            font.draw(sprite, pageText, pageTextX, pageY + info.pageBarHeight - 8);
        }

        // 按钮（从顶部开始布局）
        float contentTop = info.y + info.h - PANEL_PAD - (info.pageBarHeight > 0 ? info.pageBarHeight + 4 : 0);
        for (int j = info.startIdx; j < info.endIdx; j++) {
            int itemIdx = visibleIndices[j];
            MenuItem item = items[itemIdx];

            int localIdx = j - info.startIdx;
            int row = localIdx / info.cols;
            int col = localIdx % info.cols;

            float bx = info.x + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = contentTop - (row + 1) * BTN_H - row * BTN_GAP;

            // 绘制按钮背景
            boolean pressed = false;
            for (int p = 0; p < pointerPressedIndex.length; p++) {
                if (pointerPressedIndex[p] == itemIdx) { pressed = true; break; }
            }

            if (pressed) {
                sprite.setColor(0.3f, 0.5f, 0.8f, 0.95f);
            } else if (flashTimers[itemIdx] > 0) {
                sprite.setColor(0.5f, 0.7f, 1.0f, 0.9f);
            } else {
                sprite.setColor(0.2f, 0.2f, 0.3f, 0.7f);
            }
            sprite.draw(whitePixel, bx, by, BTN_W, BTN_H);

            // 按钮文字
            font.setColor(1, 1, 1, 0.9f);
            glyph.setText(font, item.label);
            font.draw(sprite, item.label, bx + (BTN_W - glyph.width) / 2, by + (BTN_H + glyph.height) / 2);

            // 如果是开关项，绘制状态指示
            if (item.isToggle) {
                boolean active = isToggleActive(item);
                sprite.setColor(active ? Color.CYAN : Color.GRAY);
                sprite.draw(whitePixel, bx + 4, by + 4, 8, BTN_H - 8);
            }
        }
    }

    private boolean isToggleActive(MenuItem item) {
        if (item.keycode == -100) { // Touch Key
            if (kbInput != null && kbInput.getMainController() instanceof MainController) {
                return ((MainController) kbInput.getMainController()).getConfig().isShowTouchKey();
            }
        }
        return false;
    }

    // ─────────────────── In-Game Spectrum 调整页 ───────────────────

    /** 调整页是否打开（渲染层用它决定是否在其他界面也预览频谱） */
    public boolean isSpectrumAdjustOpen() {
        return spectrumAdjustOpen;
    }

    private MainController mainControllerOrNull() {
        if (kbInput == null) return null;
        Object mc = kbInput.getMainController();
        return (mc instanceof MainController) ? (MainController) mc : null;
    }

    /**
     * 进入调整页：把<b>当前生效</b>的值载入编辑状态。
     * 关键：把生效值物化进 PlayerConfig —— PlayerConfig 里 0 表示"未设置"（回退到
     * 皮肤 json），若不在进入时落定，界面会一直显示 0 且加减后语义混乱。
     */
    private void enterSpectrumAdjust() {
        MainController mc = mainControllerOrNull();
        if (mc != null) {
            int[] v = InGameSpectrumConfig.resolve(mc);
            System.arraycopy(v, 0, spectrumValues, 0, 4);
            InGameSpectrumConfig.apply(mc, v[0], v[1], v[2], v[3]);
            InGameSpectrumConfig.save(mc);
        }
        spectrumAdjustOpen = true;
        stopSpectrumHold(false);
    }

    /** 离开调整页：停止连发并落盘 */
    private void exitSpectrumAdjust() {
        spectrumAdjustOpen = false;
        stopSpectrumHold(true);
    }

    /** 单步调整：X/Y 步长 1，W/H 步长 10；改完立即应用到渲染器（不落盘，退出时统一保存） */
    private void spectrumStep(int field, int steps) {
        if (field < 0 || field >= 4 || steps == 0) return;
        int delta = (field >= 2) ? 10 : 1;
        spectrumValues[field] += delta * steps;
        MainController mc = mainControllerOrNull();
        if (mc != null) {
            InGameSpectrumConfig.apply(mc, spectrumValues[0], spectrumValues[1],
                    spectrumValues[2], spectrumValues[3]);
        }
    }

    private void stopSpectrumHold(boolean save) {
        if (spectrumHoldField >= 0 && save) {
            MainController mc = mainControllerOrNull();
            if (mc != null) {
                InGameSpectrumConfig.save(mc);
            }
        }
        spectrumHoldField = -1;
        spectrumHoldDir = 0;
        spectrumHoldStartNs = 0;
        spectrumHoldLastRepeatNs = 0;
    }

    /**
     * 调整页命中判定。返回：SPECTRUM_HIT_BACK / SPECTRUM_HIT_OUTSIDE /
     * SPECTRUM_HIT_NONE（面板内空白）/ 或 row*10+col（col: 0 值, 1 [-], 2 [+]）。
     */
    private int hitTestSpectrumPage(float tx, float ty) {
        PanelLayout info = calculateSpectrumPanelLayout();
        if (tx < info.x || tx > info.x + info.w || ty < info.y || ty > info.y + info.h) {
            return SPECTRUM_HIT_OUTSIDE;
        }
        float[] r = new float[4];
        spectrumBackRect(info, r);
        if (tx >= r[0] && tx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3]) {
            return SPECTRUM_HIT_BACK;
        }
        for (int row = 0; row < SPECTRUM_ROWS; row++) {
            for (int col = 0; col < SPECTRUM_COLS; col++) {
                spectrumCellRect(info, row, col, r);
                if (tx >= r[0] && tx <= r[0] + r[2] && ty >= r[1] && ty <= r[1] + r[3]) {
                    return row * 10 + col;
                }
            }
        }
        return SPECTRUM_HIT_NONE;
    }

    private void drawSpectrumPage(SpriteBatch sprite, BitmapFont font) {
        final float border = 2;
        PanelLayout info = calculateSpectrumPanelLayout();
        float[] r = new float[4];
        GlyphLayout glyph = new GlyphLayout();

        // 面板背景 + 边框
        sprite.setColor(0.1f, 0.1f, 0.15f, 0.85f);
        sprite.draw(whitePixel, info.x, info.y, info.w, info.h);
        sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
        sprite.draw(whitePixel, info.x, info.y, info.w, border);
        sprite.draw(whitePixel, info.x, info.y + info.h - border, info.w, border);
        sprite.draw(whitePixel, info.x, info.y, border, info.h);
        sprite.draw(whitePixel, info.x + info.w - border, info.y, border, info.h);

        // 标题栏：左侧返回 + 居中标题
        spectrumBackRect(info, r);
        sprite.setColor(0.2f, 0.25f, 0.4f, 0.9f);
        sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);
        font.setColor(0.6f, 0.85f, 1f, 0.95f);
        glyph.setText(font, "<");
        font.draw(sprite, "<", r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);

        String title = "In-Game Spectrum Adjust";
        font.setColor(0.85f, 0.85f, 0.9f, 0.95f);
        glyph.setText(font, title);
        float barY = info.y + info.h - PANEL_PAD - SPECTRUM_TITLE_H;
        font.draw(sprite, title, info.x + (info.w - glyph.width) / 2,
                barY + (SPECTRUM_TITLE_H + glyph.height) / 2);

        // 4 行 × 3 列：值 / [-] / [+]
        final String[] fieldNames = { "X", "Y", "W", "H" };
        for (int row = 0; row < SPECTRUM_ROWS; row++) {
            for (int col = 0; col < SPECTRUM_COLS; col++) {
                spectrumCellRect(info, row, col, r);

                // 按下高亮
                boolean pressed = false;
                for (int p = 0; p < pointerSpectrumCell.length && !pressed; p++) {
                    if (pointerSpectrumCell[p] == row * 10 + col) pressed = true;
                }
                if (pressed) {
                    sprite.setColor(0.3f, 0.5f, 0.8f, 0.95f);
                } else if (col == 0) {
                    sprite.setColor(0.16f, 0.18f, 0.26f, 0.75f);   // 值不点，颜色略暗区分
                } else {
                    sprite.setColor(0.2f, 0.2f, 0.3f, 0.7f);
                }
                sprite.draw(whitePixel, r[0], r[1], r[2], r[3]);

                String label;
                if (col == 0) {
                    label = fieldNames[row] + ": " + spectrumValues[row];
                } else {
                    label = (col == 1) ? "[-]" : "[+]";
                }
                font.setColor(col == 0 ? 0.9f : 1f, col == 0 ? 0.95f : 1f, 1f, 0.95f);
                glyph.setText(font, label);
                font.draw(sprite, label, r[0] + (r[2] - glyph.width) / 2, r[1] + (r[3] + glyph.height) / 2);
            }
        }

        // 底部操作提示（放在最后一行下方）
        String hint = "Y = " + (spectrumValues[1]) + "px from BOTTOM   (long-press -/+ to repeat)";
        font.setColor(0.55f, 0.6f, 0.7f, 0.9f);
        glyph.setText(font, hint);
        font.draw(sprite, hint, info.x + (info.w - glyph.width) / 2, info.y + PANEL_PAD * 0.6f);
    }

    // ─────────────────── NUM5 长按模式：7K 覆盖层 ───────────────────

    /** 7K 覆盖层缓存的布局参数（每帧重算） */
    private float k7BtnW, k7BtnH;
    private float k7PanelX, k7PanelY;
    private float k7TitleY, k7CloseY;

    /** 计算 7K 覆盖层布局：整体高度约屏幕高度的 K7_TARGET_SCREEN_RATIO */
    private void calculate7KOverlayLayout() {
        // 总垂直空间 = 标题 + 间距 + 按钮 + 间距 + 关闭按钮
        float totalH = K7_TITLE_H + 12 + K7_CLOSE_H + 12;
        // 按钮高度 = 目标高度 - 其他元素占用
        float targetH = logicH * K7_TARGET_SCREEN_RATIO;
        k7BtnH = Math.max(60, targetH - totalH);

        // 按钮宽度：让 7 个按钮 + 6 个间隙居中后占屏幕宽度 ~50%
        float maxTotalW = logicW * 0.55f;
        k7BtnW = (maxTotalW - 6 * K7_BTN_GAP) / 7f;
        k7BtnW = Math.max(60, Math.min(k7BtnW, 140));

        float totalW = 7 * k7BtnW + 6 * K7_BTN_GAP;
        k7PanelX = (logicW - totalW) / 2f;
        // 让 title + buttons + close 三段整体居中
        float blockH = K7_TITLE_H + 12 + k7BtnH + 12 + K7_CLOSE_H;
        float blockTop = (logicH + blockH) / 2f;
        k7TitleY = blockTop - K7_TITLE_H;
        k7PanelY = k7TitleY - 12 - k7BtnH;
        k7CloseY = k7PanelY - 12 - K7_CLOSE_H;
    }

    /** 检测触摸是否在 7K 覆盖层的某个键上，返回 0~6 的键索引，否则返回 -1
     * @param stickyKey 当前已按下的键索引（-1 表示无），用于"粘住"避免手指抖动时释放按键 */
    private int hitTest7KKey(float tx, float ty, int stickyKey) {
        if (ty < k7PanelY || ty > k7PanelY + k7BtnH) return -1;

        // 先检查手指当前实际命中哪个键
        int actualHit = -1;
        for (int i = 0; i < 7; i++) {
            float bx = k7PanelX + i * (k7BtnW + K7_BTN_GAP);
            if (tx >= bx && tx <= bx + k7BtnW) {
                actualHit = i;
                break;
            }
        }

        // 已按下某键时采用"粘住"策略：
        // - 手指滑入间隙/边缘抖动 → 保持当前按键，避免长按时被反复 release/press
        // - 手指明确滑到另一键上 → 切换到新键
        // - 手指滑到面板 Y 范围外（hitTest 入口已拦截）→ 保持当前按键
        if (stickyKey >= 0) {
            if (actualHit == stickyKey) return stickyKey;
            if (actualHit >= 0) return actualHit; // 明确切到另一键
            return stickyKey; // 抖动/间隙：保持
        }

        // 未按下任何键：标准命中测试
        return actualHit;
    }

    /** 检测触摸是否在 7K 覆盖层的"关闭"按钮上 */
    private boolean hitTest7KClose(float tx, float ty) {
        float closeW = 180;
        float cx = (logicW - closeW) / 2f;
        return tx >= cx && tx <= cx + closeW && ty >= k7CloseY && ty <= k7CloseY + K7_CLOSE_H;
    }

    /**
     * 发送 7K 按键状态到核心层（绕过 setSimulatedKeyState 的 release bug）。
     * 使用逻辑 key index（0~6 对应 7K 第 1~7 个 lane），由 LaneProperty 决定映射。
     */
    private void send7KKey(int keyIdx, boolean pressed) {
        if (kbInput == null) return;
        Object mc = kbInput.getMainController();
        if (!(mc instanceof MainController)) return;
        BMSPlayerInputProcessor input = ((MainController) mc).getInputProcessor();
        if (input == null) return;

        long microtime;
        long startTime = input.getStartTime();
        if (startTime != 0) {
            microtime = System.nanoTime() / 1000 - startTime;
        } else {
            microtime = System.nanoTime() / 1000;
        }
        input.setKeyChanged(keyIdx, pressed, microtime);
    }

    /**
     * 发送 START 按键状态到核心层。
     * 通过 BMSPlayerInputProcessor.startChanged() 直接设置，与 KeyBoardInputProcessor.poll()
     * 中处理物理 START 的路径保持一致，绕过 keystate 中转以避免 toggle 的 release 失效。
     */
    private void sendStartKey(boolean pressed) {
        if (kbInput == null) return;
        Object mc = kbInput.getMainController();
        if (!(mc instanceof MainController)) return;
        BMSPlayerInputProcessor input = ((MainController) mc).getInputProcessor();
        if (input == null) return;
        input.startChanged(pressed);
    }

    /**
     * 释放当前长按模拟的按键（NUM5 或 START）。同时关闭 7K 覆盖层。
     * 用于关闭按钮、图标再次点击、按钮二次点击等所有收尾路径。
     */
    private void releaseHoldKey() {
        if (holdKeyType == HoldKeyType.NUM5 && kbInput != null) {
            kbInput.setSimulatedKeyState(Keys.NUM_5, false);
        } else if (holdKeyType == HoldKeyType.START) {
            sendStartKey(false);
        }
        holdKeyType = HoldKeyType.NONE;
        holdKeyHeld = false;
    }

    /** 绘制 7K 覆盖层：7 个键 + 标题 + 关闭按钮 */
    private void draw7KOverlay(SpriteBatch sprite, BitmapFont font) {
        calculate7KOverlayLayout();
        GlyphLayout glyph = new GlyphLayout();

        // 读取当前用户配置的 7 个 lane 键位（用户在 Key Config 中可改）
        int[] userKeys = (kbInput != null) ? kbInput.getKeys() : null;
        refresh7KKeyNamesCache(userKeys);

        // 标题（上方居中）
        font.setColor(0.5f, 0.8f, 1f, 0.95f);
        String heldName = (holdKeyType == HoldKeyType.START) ? "START" : "NUM5";
        String title = heldName + " Long-Press: 7KEYS (tap again to release)";
        glyph.setText(font, title);
        font.draw(sprite, title, (logicW - glyph.width) / 2f, k7TitleY + K7_TITLE_H * 0.7f);

        // 7 个键
        for (int i = 0; i < 7; i++) {
            float bx = k7PanelX + i * (k7BtnW + K7_BTN_GAP);

            boolean pressed = false;
            for (int p = 0; p < pointer7KKey.length; p++) {
                if (pointer7KKey[p] == i) { pressed = true; break; }
            }

            if (pressed) {
                sprite.setColor(0.3f, 0.5f, 0.8f, 0.95f);
            } else {
                sprite.setColor(0.2f, 0.2f, 0.3f, 0.6f);
            }
            sprite.draw(whitePixel, bx, k7PanelY, k7BtnW, k7BtnH);

            sprite.setColor(0.4f, 0.6f, 1f, 0.6f);
            float border = 2;
            sprite.draw(whitePixel, bx, k7PanelY, k7BtnW, border);
            sprite.draw(whitePixel, bx, k7PanelY + k7BtnH - border, k7BtnW, border);
            sprite.draw(whitePixel, bx, k7PanelY, border, k7BtnH);
            sprite.draw(whitePixel, bx + k7BtnW - border, k7PanelY, border, k7BtnH);

            // 键号（大字）
            font.setColor(1, 1, 1, 0.95f);
            String num = String.valueOf(i + 1);
            glyph.setText(font, num);
            font.draw(sprite, num, bx + (k7BtnW - glyph.width) / 2f, k7PanelY + k7BtnH * 0.66f);

            // 对应键盘按键（小字）：使用缓存的显示名称，避免每帧 Keys.toString 分配
            font.setColor(0.7f, 0.7f, 0.7f, 0.85f);
            String keyName = cached7KKeyNames[i];
            glyph.setText(font, keyName);
            font.draw(sprite, keyName, bx + (k7BtnW - glyph.width) / 2f, k7PanelY + k7BtnH * 0.28f);
        }

        // 关闭按钮（覆盖层下方居中）
        float closeW = 180;
        float cx = (logicW - closeW) / 2f;
        sprite.setColor(0.6f, 0.2f, 0.2f, 0.85f);
        sprite.draw(whitePixel, cx, k7CloseY, closeW, K7_CLOSE_H);
        sprite.setColor(1, 1, 1, 0.9f);
        sprite.draw(whitePixel, cx, k7CloseY + K7_CLOSE_H - 2, closeW, 2);
        sprite.draw(whitePixel, cx, k7CloseY, closeW, 2);
        sprite.draw(whitePixel, cx, k7CloseY, 2, K7_CLOSE_H);
        sprite.draw(whitePixel, cx + closeW - 2, k7CloseY, 2, K7_CLOSE_H);

        font.setColor(1, 1, 1, 0.95f);
        String closeLabel = (holdKeyType == HoldKeyType.START) ? "Release START" : "Release NUM5";
        glyph.setText(font, closeLabel);
        font.draw(sprite, closeLabel, cx + (closeW - glyph.width) / 2f, k7CloseY + (K7_CLOSE_H + glyph.height) / 2f);
    }

    /**
     * 刷新 7K 覆盖层各键显示名称的缓存。仅在 keycode 实际变化时（如 Key Config 修改后）
     * 重新调用 Keys.toString，避免每帧分配短 String 触发 GC 压力。
     */
    private void refresh7KKeyNamesCache(int[] userKeys) {
        for (int i = 0; i < 7; i++) {
            int keycode;
            if (userKeys != null && i < userKeys.length && userKeys[i] >= 0) {
                keycode = userKeys[i];
            } else {
                keycode = SEVEN_KEYS_KEYCODES_DEFAULT[i];
            }
            if (cached7KKeycodes[i] != keycode) {
                cached7KKeycodes[i] = keycode;
                cached7KKeyNames[i] = Keys.toString(keycode);
            }
        }
    }

    private static class PanelLayout {
        float x, y, w, h;
        float pageBarHeight;
        int totalPages;
        int startIdx, endIdx;
        int cols;
    }

    private PanelLayout calculatePanelLayout() {
        PanelLayout res = new PanelLayout();

        // 统计可见按钮
        int visibleCount = 0;
        for (MenuItem item : items) {
            if (isItemVisible(item)) visibleCount++;
        }

        res.totalPages = (visibleCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
        if (currentPage >= res.totalPages) currentPage = Math.max(0, res.totalPages - 1);

        res.pageBarHeight = (res.totalPages > 1) ? 36 : 0;
        res.startIdx = currentPage * ITEMS_PER_PAGE;
        res.endIdx = Math.min(res.startIdx + ITEMS_PER_PAGE, visibleCount);
        int itemCount = res.endIdx - res.startIdx;

        res.cols = 2;
        int rows = (itemCount + res.cols - 1) / res.cols;

        res.w = res.cols * BTN_W + (res.cols - 1) * BTN_GAP + PANEL_PAD * 2;
        res.h = rows * BTN_H + (rows - 1) * BTN_GAP + PANEL_PAD * 2 + (res.pageBarHeight > 0 ? res.pageBarHeight + 4 : 0);

        anchorPanel(res);
        return res;
    }

    /** 面板锚定：跟随浮动图标的位置，并做屏幕边界保护（通用列表页与频谱调整页共用） */
    private void anchorPanel(PanelLayout res) {
        Config config = null;
        if (kbInput != null && kbInput.getMainController() instanceof MainController) {
            config = ((MainController) kbInput.getMainController()).getConfig();
        }
        int pos = (config != null) ? config.getFloatingMenuPosition() : 0;

        // Y轴：底部图标向上弹出，顶部图标向下弹出
        if (pos >= 2) { // Bottom Center, Bottom Right
            res.y = iconY + ICON_SIZE + 8;
        } else { // Top Center, Top Right
            res.y = iconY - res.h - 8;
        }

        // X轴：居中或对齐右侧
        if (pos == 1 || pos == 3) { // Top Right, Bottom Right
            res.x = iconX + ICON_SIZE - res.w;
        } else { // Center
            res.x = iconX + (ICON_SIZE - res.w) / 2;
        }

        // 边界保护
        if (res.x < 10) res.x = 10;
        if (res.x + res.w > logicW - 10) res.x = logicW - res.w - 10;
        if (res.y < 10) res.y = 10;
        if (res.y + res.h > logicH - 10) res.y = logicH - res.h - 10;
    }

    // ─── In-Game Spectrum 调整页（独立模态页）───

    /** 频谱调整页布局：3 列（值 / [-] / [+]）× 4 行（X / Y / W / H）+ 标题栏 */
    private PanelLayout calculateSpectrumPanelLayout() {
        PanelLayout res = new PanelLayout();
        res.cols = SPECTRUM_COLS;
        res.w = SPECTRUM_COLS * BTN_W + (SPECTRUM_COLS - 1) * BTN_GAP + PANEL_PAD * 2;
        res.h = SPECTRUM_ROWS * BTN_H + (SPECTRUM_ROWS - 1) * BTN_GAP + PANEL_PAD * 2 + SPECTRUM_TITLE_H;
        anchorPanel(res);
        return res;
    }

    /**
     * 频谱调整页单个单元格的矩形（draw 与 hitTest 共用同一份计算，避免两者不一致）。
     *
     * @param row 0..3 = X/Y/W/H
     * @param col 0 = 值显示, 1 = [-], 2 = [+]
     */
    private void spectrumCellRect(PanelLayout info, int row, int col, float[] out) {
        float contentTop = info.y + info.h - PANEL_PAD - SPECTRUM_TITLE_H;
        out[0] = info.x + PANEL_PAD + col * (BTN_W + BTN_GAP);
        out[1] = contentTop - (row + 1) * BTN_H - row * BTN_GAP;
        out[2] = BTN_W;
        out[3] = BTN_H;
    }

    /** 标题栏返回按钮矩形 */
    private void spectrumBackRect(PanelLayout info, float[] out) {
        float barY = info.y + info.h - PANEL_PAD - SPECTRUM_TITLE_H;
        out[0] = info.x + PANEL_PAD;
        out[1] = barY;
        out[2] = BTN_H;
        out[3] = SPECTRUM_TITLE_H;
    }

    // ─────────────────── InputProcessor 事件驱动触摸处理 ───────────────────

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!visible || pointer >= pointerConsuming.length) return false;

        float tx = screenToLogicX(screenX);
        float ty = screenToLogicY(screenY);
        lastTouchX = screenX;
        lastTouchY = screenY;

        // 长按模式（NUM5/START）下的 7K 覆盖层：先于菜单面板处理
        if (holdKeyHeld) {
            // 关闭按钮
            if (hitTest7KClose(tx, ty)) {
                releaseHoldKey();
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                pointer7KKey[pointer] = -1;
                Gdx.app.log("FloatingMenu", "7K overlay: closed via close button");
                return true;
            }
            // 7K 键 1~7
            int k7Idx = hitTest7KKey(tx, ty, pointer7KKey[pointer]);
            if (k7Idx >= 0) {
                pointer7KKey[pointer] = k7Idx;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                send7KKey(k7Idx, true);
                return true;
            }
            // 点击浮动图标：关闭覆盖层并释放长按键（用户重开菜单后可再次点击释放）
            if (hitTestIcon(tx, ty)) {
                releaseHoldKey();
                expanded = true;
                justExpandedByIcon = true;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                pointer7KKey[pointer] = -1;
                return true;
            }
            // 覆盖层模态：消费其他触摸防止穿透到游戏
            pointerConsuming[pointer] = true;
            pointerPressedIndex[pointer] = -1;
            pointer7KKey[pointer] = -1;
            return true;
        }

        if (expanded) {
            // 展开状态：检查是否点击了图标（关闭菜单）
            if (hitTestIcon(tx, ty)) {
                if (spectrumAdjustOpen) exitSpectrumAdjust();
                expanded = false;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }

            // 频谱调整页：独立模态，命中判定与绘制共用同一套矩形
            if (spectrumAdjustOpen) {
                int hit = hitTestSpectrumPage(tx, ty);
                if (hit == SPECTRUM_HIT_OUTSIDE) {
                    // 点面板外：只退出调整页、不关整个菜单（避免误触把菜单一起收掉）
                    exitSpectrumAdjust();
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                if (hit == SPECTRUM_HIT_BACK) {
                    exitSpectrumAdjust();
                    pointerConsuming[pointer] = true;
                    pointerPressedIndex[pointer] = -1;
                    return true;
                }
                pointerSpectrumCell[pointer] = (hit >= 0) ? hit : -1;
                if (hit >= 0) {
                    int row = hit / 10;
                    int col = hit % 10;
                    if (col == 1 || col == 2) {
                        // [-] / [+]：立即走一步，并开启长按连发
                        spectrumHoldField = row;
                        spectrumHoldDir = (col == 1) ? -1 : 1;
                        spectrumHoldStartNs = System.nanoTime();
                        spectrumHoldLastRepeatNs = spectrumHoldStartNs;
                        spectrumStep(row, spectrumHoldDir);
                    }
                }
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }

            // 检查是否点到了按钮
            int itemHit = hitTestPanel(tx, ty);
            if (itemHit >= 0) {
                pointerPressedIndex[pointer] = itemHit;
                pressButton(itemHit);
                pointerConsuming[pointer] = true;
                return true;
            }
            // 处理翻页
            if (itemHit == -3) {
                if (currentPage > 0) currentPage--;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            if (itemHit == -4) {
                int visibleCount = 0;
                for (MenuItem item : items) {
                    if (isItemVisible(item)) visibleCount++;
                }
                int totalPages = (visibleCount + ITEMS_PER_PAGE - 1) / ITEMS_PER_PAGE;
                if (currentPage < totalPages - 1) currentPage++;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            // 面板区域内空白或点击面板外任何地方 → 关闭菜单并消费事件
            expanded = false;
            pointerConsuming[pointer] = true;
            pointerPressedIndex[pointer] = -1;
            return true;
        } else {
            // 收起状态：检查是否点击了图标
            if (hitTestIcon(tx, ty)) {
                if (isPlayMode) {
                    sinceLastInteraction = 0f;
                    playIconHidden = false;
                }
                expanded = true;
                justExpandedByIcon = true;
                pointerConsuming[pointer] = true;
                pointerPressedIndex[pointer] = -1;
                return true;
            }
            return false;
        }
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (!visible || pointer >= pointerConsuming.length) return false;

        // 如果这个指针正在消费事件
        if (pointerConsuming[pointer]) {
            // Play 模式：任何交互都重置超时计时器
            if (isPlayMode) {
                sinceLastInteraction = 0f;
            }
            // 7K 覆盖层：释放对应的 7KEYS 键
            int k7Idx = pointer7KKey[pointer];
            if (k7Idx >= 0) {
                send7KKey(k7Idx, false);
                pointer7KKey[pointer] = -1;
                justExpandedByIcon = false;
                pointerPressedIndex[pointer] = -1;
                pointerConsuming[pointer] = false;
                return true;
            }
            // 频谱调整页：抬手即停止连发并落盘
            if (spectrumAdjustOpen) {
                pointerSpectrumCell[pointer] = -1;
                stopSpectrumHold(true);
            }
            // 检查是否抬起了手指在按钮上
            int pressedIdx = pointerPressedIndex[pointer];
            if (expanded && pressedIdx >= 0) {
                releaseButton(pressedIdx);
                flashTimers[pressedIdx] = FLASH_DURATION;
            }
            justExpandedByIcon = false;
            pointerPressedIndex[pointer] = -1;
            pointerConsuming[pointer] = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!visible || pointer >= pointerConsuming.length) return false;
        if (pointerConsuming[pointer]) {
            // 7K 覆盖层：始终跟随手指，按下当前命中键、释放之前的键
            if (holdKeyHeld) {
                float tx = screenToLogicX(screenX);
                float ty = screenToLogicY(screenY);
                int newHit = hitTest7KKey(tx, ty, pointer7KKey[pointer]);
                int prevHit = pointer7KKey[pointer];
                if (newHit != prevHit) {
                    if (prevHit >= 0) {
                        send7KKey(prevHit, false);
                    }
                    pointer7KKey[pointer] = newHit;
                    if (newHit >= 0) {
                        send7KKey(newHit, true);
                    }
                }
                return true;
            }
            if (expanded) {
                float tx = screenToLogicX(screenX);
                float ty = screenToLogicY(screenY);
                int hit = hitTestPanel(tx, ty);
                int currentIdx = pointerPressedIndex[pointer];
                if (hit != currentIdx) {
                    if (currentIdx >= 0) releaseButton(currentIdx);
                    pointerPressedIndex[pointer] = hit;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseMoved(int screenX, int screenY) { return false; }

    @Override
    public boolean scrolled(float amountX, float amountY) { return false; }

    @Override
    public boolean keyDown(int keycode) {
        // 频谱调整页：ESC / BACK 返回
        if (spectrumAdjustOpen) {
            if (keycode == Keys.ESCAPE || keycode == Keys.BACK) {
                exitSpectrumAdjust();
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyUp(int keycode) { return false; }

    @Override
    public boolean keyTyped(char character) { return false; }

    @Override
    public boolean touchCancelled(int screenX, int screenY, int pointer, int button) {
        if (pointer < pointerConsuming.length) {
            // 释放可能按住的 7K 键
            int k7Idx = pointer7KKey[pointer];
            if (k7Idx >= 0) {
                send7KKey(k7Idx, false);
            }
            pointer7KKey[pointer] = -1;
            pointerPressedIndex[pointer] = -1;
            pointerConsuming[pointer] = false;
        }
        return false;
    }

    /** 返回当前触摸是否正被浮动菜单消费（用于 MainController 跳过触摸指针/皮肤事件） */
    public boolean isConsumingTouch() {
        for (boolean consuming : pointerConsuming) {
            if (consuming) return true;
        }
        return false;
    }

    // ─────────────────── 旧版轮询触摸处理（已废弃） ───────────────────

    @Deprecated
    private void handleTouch() {
        boolean touched = Gdx.input.isTouched();
        if (touched) {
            float tx = screenToLogicX(Gdx.input.getX());
            float ty = screenToLogicY(Gdx.input.getY());

            if (expanded) {
                int hit = hitTestButton(tx, ty);
                if (hit >= 0) {
                    fireButton(hit);
                } else if (hitTestIcon(tx, ty)) {
                    expanded = false;
                } else {
                    expanded = false;
                }
            } else {
                if (hitTestIcon(tx, ty)) {
                    expanded = true;
                }
            }
        }
    }

    private boolean hitTestIcon(float tx, float ty) {
        return tx >= iconX && tx <= iconX + ICON_SIZE
            && ty >= iconY && ty <= iconY + ICON_SIZE;
    }

    /** 检测触摸点是否在面板区域内，返回按钮索引（>=0）或 -1（在面板空白处）或 -2（不在面板内）或 -3/-4（翻页） */
    private int hitTestPanel(float tx, float ty) {
        if (font == null) return -2;

        PanelLayout info = calculatePanelLayout();

        if (tx < info.x || tx > info.x + info.w || ty < info.y || ty > info.y + info.h) {
            return -2;  // 不在面板区域内
        }

        // 检查是否点击了翻页箭头（顶部）
        if (info.totalPages > 1) {
            float pageY = info.y + info.h - PANEL_PAD - info.pageBarHeight;
            if (ty >= pageY && ty <= pageY + info.pageBarHeight) {
                // 检查左箭头
                if (currentPage > 0 && tx >= info.x + PANEL_PAD - 20 && tx <= info.x + PANEL_PAD + 30) {
                    return -3;  // 上一页
                }
                // 检查右箭头
                if (currentPage < info.totalPages - 1) {
                    GlyphLayout layout = new GlyphLayout();
                    layout.setText(font, ">");
                    float arrowX = info.x + info.w - PANEL_PAD - layout.width;
                    if (tx >= arrowX - 20 && tx <= arrowX + layout.width + 20) {
                        return -4;  // 下一页
                    }
                }
            }
        }

        // 收集可见按钮索引（含 PLAY 界面的顺序覆盖；绘制与命中判定同一份顺序）
        int[] visibleIndices = buildVisibleIndexOrder();

        float contentTop = info.y + info.h - PANEL_PAD - (info.pageBarHeight > 0 ? info.pageBarHeight + 4 : 0);
        for (int j = info.startIdx; j < info.endIdx; j++) {
            int itemIdx = visibleIndices[j];
            int localIdx = j - info.startIdx;
            int row = localIdx / info.cols;
            int col = localIdx % info.cols;

            float bx = info.x + PANEL_PAD + col * (BTN_W + BTN_GAP);
            float by = contentTop - (row + 1) * BTN_H - row * BTN_GAP;

            if (tx >= bx && tx <= bx + BTN_W && ty >= by && ty <= by + BTN_H) {
                return itemIdx;
            }
        }

        return -1;
    }

    private int hitTestButton(float tx, float ty) {
        return hitTestPanel(tx, ty);
    }

    private void pressButton(int index) {
        if (index < 0 || index >= items.length) return;
        MenuItem item = items[index];

        // 长按模式（NUM5/START）：第一次按下模拟 keydown 不释放（再按一次释放）
        if (item.keycode == Keys.NUM_5 || item.keycode == -141) {
            if (!holdKeyHeld) {
                holdKeyHeld = true;
                holdKeyType = (item.keycode == Keys.NUM_5) ? HoldKeyType.NUM5 : HoldKeyType.START;
                if (holdKeyType == HoldKeyType.NUM5) {
                    if (kbInput != null) {
                        kbInput.setSimulatedKeyState(Keys.NUM_5, true);
                    }
                } else {
                    sendStartKey(true);
                }
                expanded = false; // 关闭菜单面板，仅保留浮动图标
                Gdx.app.log("FloatingMenu", holdKeyType + " long-press: DOWN, overlay shown");
            } else {
                releaseHoldKey();
                Gdx.app.log("FloatingMenu", "long-press: UP, overlay hidden");
            }
            return;
        }

        if (item.keycode == -100 || item.keycode == -130 || item.keycode == WALKURE_KEYCODE
                || item.keycode == SPECTRUM_ENTRY_KEYCODE) {
            return; // Toggle/action 类型（含 In-Game Spectrum 入口）在 touchUp 处理
        }

        if (kbInput != null) {
            // 使用 setSimulatedKeyState 实现真正的长按（直到调用 false）
            kbInput.setSimulatedKeyState(item.keycode, true);
            Gdx.app.log("FloatingMenu", "pressButton: " + item.label);
        }
    }

    private void releaseButton(int index) {
        if (index < 0 || index >= items.length) return;
        MenuItem item = items[index];

        if (item.keycode == -100 || item.keycode == -130 || item.keycode == WALKURE_KEYCODE
                || item.keycode == SPECTRUM_ENTRY_KEYCODE) {
            handleToggle(item);
            return;
        }

        // 长按模式（NUM5/START）是切换式，不在 touchUp 时释放
        if (item.keycode == Keys.NUM_5 || item.keycode == -141) {
            return;
        }

        if (kbInput != null) {
            kbInput.setSimulatedKeyState(item.keycode, false);
            Gdx.app.log("FloatingMenu", "releaseButton: " + item.label);
        }
    }

    private void handleToggle(MenuItem item) {
        Object mainController = kbInput.getMainController();
        if (mainController instanceof MainController) {
            Config config = ((MainController) mainController).getConfig();
    if (config != null) {
                if (item.keycode == -100) {
                    // Touch Key toggle
                    boolean newState = !config.isShowTouchKey();
                    config.setShowTouchKey(newState);
                    item.label = "Touch Key: " + (newState ? "ON" : "OFF");
                    Config.write(config);
                    MainState current = ((MainController) mainController).getCurrentState();
                    if (current instanceof bms.player.beatoraja.play.BMSPlayer) {
                        try {
                            java.lang.reflect.Field field = bms.player.beatoraja.play.BMSPlayer.class.getDeclaredField("touchKeyMapper");
                            field.setAccessible(true);
                            Object mapper = field.get(current);
                            if (mapper != null) {
                                ((bms.player.beatoraja.play.PlayTouchKeyMapper) mapper).setEnabled(newState);
                            }
                        } catch (Exception e) {
                            Gdx.app.log("FloatingMenu", "Failed to update touchKeyMapper state: " + e.getMessage());
                        }
                    }
                } else if (item.keycode == -130) {
                    // Music Player entry
                    MainState current = ((MainController) mainController).getCurrentState();
                    if (current instanceof bms.player.beatoraja.select.MusicSelector) {
                        if (((bms.player.beatoraja.select.MusicSelector) current).getBarManager().getSelected() instanceof bms.player.beatoraja.select.bar.SongBar) {
                            ((MainController) mainController).changeState(MainState.MainStateType.MUSICPLAYER);
                        }
                    }
                } else if (item.keycode == WALKURE_KEYCODE) {
                    // Player Rating entry - show in WebView via AndroidLauncher
                    showPlayerRating();
                } else if (item.keycode == SPECTRUM_ENTRY_KEYCODE) {
                    // In-Game Spectrum 调整页（仅 PLAY 界面可见）
                    enterSpectrumAdjust();
                }
            }
        }
    }

    @Deprecated
    private void fireButton(int index) {
        // 已弃用，逻辑移至 pressButton/releaseButton
    }

    // ─────────────────── 坐标转换（使用 MainController 同步的视口参数）───────────────────

    private float screenToLogicX(int screenX) {
        if (vpW <= 0 || logicW <= 0) return screenX;
        return (screenX - vpX) * (float) logicW / vpW;
    }

    private float screenToLogicY(int screenY) {
        if (vpH <= 0 || logicH <= 0) return screenY;
        // 屏幕 Y 从上往下，逻辑 Y 从下往上
        return logicH - (screenY - vpY) * (float) logicH / vpH;
    }

    // ─────────────────── 玩家实力表 ───────────────────

    private PlayerRatingService ratingService;

    private void showPlayerRating() {
        if (kbInput == null || !(kbInput.getMainController() instanceof MainController)) {
            Gdx.app.log("FloatingMenu", "Cannot show rating: MainController not available");
            return;
        }
        MainController mc = (MainController) kbInput.getMainController();

        // Initialize service lazily
        if (ratingService == null) {
            ratingService = new PlayerRatingService();
        }

        try {
            String json = ratingService.computeRating(mc);

            Class<?> clazz = Class.forName("com.starxh.beatoraja.android.AndroidLauncher");
            java.lang.reflect.Method method = clazz.getMethod("showRatingWebView", String.class);
            method.invoke(null, json);
        } catch (Exception e) {
            Gdx.app.log("FloatingMenu", "Failed to show rating: " + e.getMessage());
        }
    }

    // ─────────────────── 资源释放 ───────────────────

    public void dispose() {
        if (iconTexture != null) { iconTexture.dispose(); iconTexture = null; }
        if (whitePixel != null)  { whitePixel.dispose();  whitePixel = null;  }
    }
}
