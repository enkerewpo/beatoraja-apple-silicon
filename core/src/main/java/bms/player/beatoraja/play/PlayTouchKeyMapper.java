package bms.player.beatoraja.play;

import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.InputProcessor;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.Pixmap;
import com.badlogic.gdx.graphics.Texture;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.GlyphLayout;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.math.Matrix4;
import com.badlogic.gdx.math.Rectangle;
import com.badlogic.gdx.math.Vector2;
import com.badlogic.gdx.scenes.scene2d.Actor;
import com.badlogic.gdx.scenes.scene2d.Stage;
import com.badlogic.gdx.utils.Disposable;
import com.badlogic.gdx.utils.viewport.FitViewport;

import bms.player.beatoraja.Resolution;
import bms.player.beatoraja.input.BMSPlayerInputProcessor;
import bms.player.beatoraja.skin.SkinObject;
import bms.player.beatoraja.skin.SkinProperty;

/**
 * 触摸按键映射器 - 基于 Skin 的 laneregion 动态创建触摸区域
 *
 * 支持所有键数的模式（5key/7key/9key/14key等），自动适配
 */
public class PlayTouchKeyMapper implements InputProcessor, Disposable {

    private Stage stage;
    private TouchKeyButton[] keyButtons;
    private BMSPlayerInputProcessor inputProcessor;
    private BMSPlayer player;
    private LaneProperty laneProperty;
    private boolean enabled = false;
    private boolean regionsInitialized = false;
    private Texture whitePixel;

    private int logicW;
    private int logicH;

    // pointer ID -> 按下的按键索引 (-1 表示未按在任何键上)
    private int[] pointerMap = new int[64];
    // 复用的临时 Vector2，避免 touchDown 频繁分配
    private final Vector2 tmpCoords = new Vector2();

    private final Matrix4 oldProj = new Matrix4();

    // ─── 逐帧复用的触摸区域缓存（禁止在 render 里分配对象）───
    /** 本帧每条轨道在 Stage 坐标系下的矩形 */
    private Rectangle[] laneRects = new Rectangle[0];
    /** 轨道沿"堆叠轴"排序后的 lane 下标 */
    private int[] laneOrder = new int[0];
    /** lane 下标 → 它在堆叠轴上的次序（laneOrder 的反查表） */
    private int[] lanePos = new int[0];

    // ─── SkinNote 缓存（避免逐帧遍历全部皮肤对象）───
    private SkinNote skinNoteCache;
    private PlaySkin skinForNoteCache;

    private static final Color SCRATCH_COLOR = new Color(0.8f, 0.2f, 0.2f, 0.0f);
    private static final Color WHITE_KEY_COLOR = new Color(0.9f, 0.9f, 0.9f, 0.0f);
    private static final Color BLACK_KEY_COLOR = new Color(0.3f, 0.3f, 0.3f, 0.0f);
    private static final Color LABEL_COLOR = new Color(1.0f, 1.0f, 1.0f, 0.0f);

    /**
     * 触摸区域扩展上限（非堆叠轴，即轨道长度方向）：竖屏 5%、横屏 15% 的逻辑高度。
     * 该轴上没有相邻轨道，多做扩展只是给玩家容错，不会误触别的键。
     */
    private static final float ALONG_EXTENSION_PORTRAIT = 0.05f;
    private static final float ALONG_EXTENSION_LANDSCAPE = 0.15f;

    public PlayTouchKeyMapper(BMSPlayer player, Resolution resolution, BMSPlayerInputProcessor inputProcessor, LaneProperty laneProperty) {
        this.player = player;
        this.inputProcessor = inputProcessor;
        this.laneProperty = laneProperty;
        this.logicW = resolution.width;
        this.logicH = resolution.height;

        stage = new Stage(new FitViewport(logicW, logicH));
        Pixmap pixmap = new Pixmap(1, 1, Pixmap.Format.RGBA8888);
        pixmap.setColor(Color.WHITE);
        pixmap.fill();
        whitePixel = new Texture(pixmap);
        pixmap.dispose();

        // 初始化空的按钮数组，将在 updateRegionsFromSkin 中填充
        keyButtons = new TouchKeyButton[0];

        for (int i = 0; i < pointerMap.length; i++) { pointerMap[i] = -1; }
    }

    /**
     * 从 Skin 的 laneregion 更新触摸区域
     * 需要在 skin 加载完成后调用
     */
    public void updateRegionsFromSkin() {
        PlaySkin skin = (PlaySkin) player.getSkin();
        if (skin == null) {
            Gdx.app.log("PlayTouchKeyMapper", "Skin not available yet, skip update");
            return;
        }

        // 皮肤在本局内不会变，把 SkinNote 引用缓存下来 —— render() 每帧都要读轨道矩形，
        // 逐帧遍历 getAllSkinObjects() 会白白产生迭代器分配与全表扫描。
        skinForNoteCache = skin;
        skinNoteCache = null;
        for (SkinObject obj : skin.getAllSkinObjects()) {
            if (obj instanceof SkinNote) {
                skinNoteCache = (SkinNote) obj;
                break;
            }
        }

        Rectangle[] laneRegions = skin.getLaneRegion();
        if (laneRegions == null || laneRegions.length == 0) {
            Gdx.app.log("PlayTouchKeyMapper", "Lane region not available, skip update");
            return;
        }

        // 根据 laneregion 数量创建新按钮
        keyButtons = new TouchKeyButton[laneRegions.length];

        for (int i = 0; i < laneRegions.length; i++) {
            String label = getKeyLabel(i, laneRegions.length);
            Color color = getKeyColor(i, laneRegions.length);
            keyButtons[i] = new TouchKeyButton(0, 0, 0, 0, label, color);
            stage.addActor(keyButtons[i]);
        }

        regionsInitialized = true;
    }

    /**
     * 动态同步轨道位置到触摸区域，实现跟随 LaneRenderer 走
     *
     * <p>皮肤里每条轨道矩形的相邻边之间留着一条"分割线"宽度的间隙（GenericTheme for
     * Touchscreen：竖屏 6px、横屏 3px，分割线画在间隙里而不是轨道矩形内）。触摸落在这条
     * 间隙上时任何轨道都不命中，手感上就是一条"真空带"。这里把间隙按 50/50 分给两侧轨道，
     * 使相邻触摸区域无缝衔接。</p>
     *
     * <p>横竖屏通用：轨道在哪根轴上铺开，就沿哪根轴分间隙 —— 竖屏沿 Y（轨道上下堆叠，
     * 间隙在 Y），横屏沿 X（轨道左右并排，间隙在 X）。另一根轴（轨道长度方向）保持宽松
     * 扩展，只做屏幕裁剪。</p>
     */
    private void updateRegionsFromLanes() {
        if (!regionsInitialized) return;
        PlaySkin skin = (PlaySkin) player.getSkin();
        if (skin == null) return;

        // SkinNote 正常由 updateRegionsFromSkin() 缓存；皮肤被替换时这里兜底重找一次
        if (skinNoteCache == null || skinForNoteCache != skin) {
            skinForNoteCache = skin;
            skinNoteCache = null;
            for (SkinObject obj : skin.getAllSkinObjects()) {
                if (obj instanceof SkinNote) {
                    skinNoteCache = (SkinNote) obj;
                    break;
                }
            }
        }
        SkinNote skinNote = skinNoteCache;
        if (skinNote == null) return;

        SkinNote.SkinLane[] lanes = skinNote.getLanes();
        if (lanes == null || lanes.length == 0 || lanes.length != keyButtons.length) return;

        final boolean portrait = player.getLanerender().isPortrait();
        // 轨道长度方向（非堆叠轴）的容错扩展：沿用旧实现的两个档位
        final float alongExtension = logicH * (portrait ? ALONG_EXTENSION_PORTRAIT : ALONG_EXTENSION_LANDSCAPE);

        if (laneRects.length != lanes.length) {
            laneRects = new Rectangle[lanes.length];
            for (int i = 0; i < laneRects.length; i++) {
                laneRects[i] = new Rectangle();
            }
            laneOrder = new int[lanes.length];
            lanePos = new int[lanes.length];
        }

        // 1) 先算出每条轨道本帧在 Stage 坐标系下的矩形（含 LIFT / LaneCover 等皮肤偏移）
        for (int i = 0; i < lanes.length; i++) {
            Rectangle r = lanes[i].region; // 当前帧的轨道基础区域
            float offsetX = 0;
            float offsetY = 0;
            for (SkinObject.SkinOffset o : lanes[i].getSkinOffsets()) {
                if (o != null) {
                    offsetX += o.x;
                    offsetY += o.y;
                }
            }
            if (portrait) {
                // 竖屏：Stage Y 与皮肤 Y 同向（屏幕坐标的翻转已在输入侧完成）
                laneRects[i].set(r.x + offsetX, r.y + offsetY, r.width, r.height);
            } else {
                // 横屏：Y 轴翻转之后再应用偏移
                laneRects[i].set(r.x + offsetX, logicH - (r.y + offsetY) - r.height, r.width, r.height);
            }
        }

        // 2) 判定堆叠轴：轨道中心在哪个轴上铺得开，就说明轨道在哪个轴上相邻
        float minCx = Float.MAX_VALUE, maxCx = -Float.MAX_VALUE;
        float minCy = Float.MAX_VALUE, maxCy = -Float.MAX_VALUE;
        for (int i = 0; i < laneRects.length; i++) {
            Rectangle r = laneRects[i];
            final float cx = r.x + r.width * 0.5f;
            final float cy = r.y + r.height * 0.5f;
            if (cx < minCx) minCx = cx;
            if (cx > maxCx) maxCx = cx;
            if (cy < minCy) minCy = cy;
            if (cy > maxCy) maxCy = cy;
        }
        final boolean stackAlongY = (maxCy - minCy) >= (maxCx - minCx);

        // 3) 沿堆叠轴排序：皮肤的可视次序与 lane 下标常常不同（7K 竖屏 order = {7,6,5,4,3,2,1,8}，
        //    相邻下标并不相邻），必须先按坐标排序才能找到真正的邻居。8 个元素插入排序，零分配。
        for (int i = 0; i < laneOrder.length; i++) {
            laneOrder[i] = i;
        }
        for (int i = 1; i < laneOrder.length; i++) {
            final int cur = laneOrder[i];
            final float key = stackAlongY ? laneRects[cur].y : laneRects[cur].x;
            int j = i - 1;
            while (j >= 0 && (stackAlongY ? laneRects[laneOrder[j]].y : laneRects[laneOrder[j]].x) > key) {
                laneOrder[j + 1] = laneOrder[j];
                j--;
            }
            laneOrder[j + 1] = cur;
        }
        for (int p = 0; p < laneOrder.length; p++) {
            lanePos[laneOrder[p]] = p;
        }

        // 4) 分割线间隙按 50/50 分给两侧，写入触摸区域
        for (int i = 0; i < lanes.length; i++) {
            Rectangle r = laneRects[i];
            final int p = lanePos[i];
            // 最外侧没有相邻轨道，沿用宽松扩展（超出屏幕的部分会被裁掉）
            float minus = alongExtension;
            float plus = alongExtension;
            if (p > 0) {
                minus = gapHalf(laneRects[laneOrder[p - 1]], r, stackAlongY);
            }
            if (p < laneOrder.length - 1) {
                plus = gapHalf(r, laneRects[laneOrder[p + 1]], stackAlongY);
            }

            float x, y, w, h;
            if (stackAlongY) {
                x = r.x - alongExtension;
                w = r.width + alongExtension * 2f;
                y = r.y - minus;
                h = r.height + minus + plus;
            } else {
                y = r.y - alongExtension;
                h = r.height + alongExtension * 2f;
                x = r.x - minus;
                w = r.width + minus + plus;
            }

            // 确保不超出屏幕边界
            if (x < 0) { w += x; x = 0; }
            if (y < 0) { h += y; y = 0; }
            if (x + w > logicW) w = logicW - x;
            if (y + h > logicH) h = logicH - y;
            if (w < 0) w = 0;
            if (h < 0) h = 0;

            keyButtons[i].updateBounds(x, y, w, h);
        }
    }

    /**
     * 相邻两轨之间"分割线间隙"的一半，用于 50/50 对分。
     *
     * @param a          堆叠轴上靠前的一条轨道
     * @param b          堆叠轴上靠后的一条轨道
     * @param stackAlongY 堆叠轴是否为 Y
     * @return 间隙的一半；两轨已相接或重叠时返回 0（本就无缝）。
     *         上限取较薄的一条轨道厚度，避免异常皮肤把触摸区域拉得过长而误触。
     */
    private float gapHalf(Rectangle a, Rectangle b, boolean stackAlongY) {
        final float gap = stackAlongY ? (b.y - (a.y + a.height)) : (b.x - (a.x + a.width));
        if (gap <= 0) {
            return 0;
        }
        final float cap = stackAlongY ? Math.min(a.height, b.height) : Math.min(a.width, b.width);
        return Math.min(gap * 0.5f, cap);
    }

    /**
     * 根据 lane 索引和总数获取键标签
     */
    private String getKeyLabel(int laneIdx, int totalLanes) {
        // 判断是否是 scratch 键（根据 Mode.scratchKey 规则：scratch 在特定索引位置）
        if (isScratchKey(laneIdx, totalLanes)) {
            return "SCR";
        }
        // 返回键号（从 1 开始）
        int keyNum = getLogicalKeyNumber(laneIdx, totalLanes);
        return String.valueOf(keyNum);
    }

    /**
     * 根据 lane 索引和总数判断是否是 scratch 键
     */
    private boolean isScratchKey(int laneIdx, int totalLanes) {
        // 根据 Mode 定义判断 scratch 位置
        // BEAT_5K: scratch at 5 (total 6)
        // BEAT_7K: scratch at 7 (total 8)
        // BEAT_14K: scratch at 7, 15 (total 16)
        // POPN_9K: no scratch (total 9)
        if (totalLanes == 6) {
            return laneIdx == 5; // BEAT_5K
        } else if (totalLanes == 8) {
            return laneIdx == 7; // BEAT_7K
        } else if (totalLanes == 16) {
            return laneIdx == 7 || laneIdx == 15; // BEAT_14K
        } else if (totalLanes == 9) {
            return false; // POPN_9K has no scratch
        }
        // 默认处理：假设 totalLanes - 1 是 scratch（适用于单 scratch 模式）
        if (totalLanes > 1 && laneIdx == totalLanes - 1) {
            return true;
        }
        return false;
    }

    /**
     * 获取逻辑键号（从 1 开始，scratch 返回特殊值）
     * lane index 直接对应 logical key index
     */
    private int getLogicalKeyNumber(int laneIdx, int totalLanes) {
        // 对于 14K (16 lanes with 2 SCR)
        if (totalLanes == 16) {
            if (laneIdx < 7) return laneIdx;
            if (laneIdx > 7 && laneIdx < 15) return laneIdx - 8;
        }
        // 对于 7K/5K 等，直接返回 0-indexed 序号以匹配皮肤视觉标签 (0, 1, 2...)
        return laneIdx;
    }

    /**
     * 根据 lane 索引和总数获取键背景颜色
     */
    private Color getKeyColor(int laneIdx, int totalLanes) {
        if (isScratchKey(laneIdx, totalLanes)) {
            return SCRATCH_COLOR;
        }
        // 交替颜色
        if (laneIdx % 2 == 0) {
            return WHITE_KEY_COLOR;
        } else {
            return BLACK_KEY_COLOR;
        }
    }

    /**
     * 同步按键状态到核心层
     */
    private void syncKeyState(int laneIdx, long timestamp, boolean pressed) {
        if (laneIdx < 0 || laneIdx >= keyButtons.length) return;

        // 通过 LaneProperty 获取该 lane 对应的真正 key index
        int keyIdx = laneIdx;
        if (laneProperty != null) {
            int[][] laneToKey = laneProperty.getLaneToKey();
            if (laneIdx < laneToKey.length && laneToKey[laneIdx].length > 0) {
                keyIdx = laneToKey[laneIdx][0];
            }
        }

        inputProcessor.setKeyChanged(keyIdx, pressed, timestamp);
    }

    public void render(SpriteBatch sprite, BitmapFont font) {
        if (!enabled) return;

        // 同步位置
        updateRegionsFromLanes();

        // 清理已断开的指针
        for (int p = 0; p < pointerMap.length; p++) {
            if (pointerMap[p] != -1 && !Gdx.input.isTouched(p)) {
                int oldKeyIdx = pointerMap[p];
                pointerMap[p] = -1;
                // 指针在 render 中被检测为断开，没有真实 touchUp 事件，用当前帧时间近似
                syncKeyState(oldKeyIdx, player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY), false);
            }
        }

        // 绘制触摸按键区域
        for (int i = 0; i < keyButtons.length; i++) {
            if (keyButtons[i] != null) {
                keyButtons[i].drawCustom(sprite, whitePixel, font);
            }
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) resetAllKeys();
    }

    private void resetAllKeys() {
        for (int i = 0; i < pointerMap.length; i++) {
            if (pointerMap[i] != -1) {
                int keyIdx = pointerMap[i];
                pointerMap[i] = -1;
                // reset 时没有真实事件，用当前帧时间近似
                syncKeyState(keyIdx, player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY), false);
            }
        }
    }

    @Override
    public boolean touchDown(int screenX, int screenY, int pointer, int button) {
        if (!enabled) return false;
        if (!regionsInitialized) return false;
        if (pointer >= pointerMap.length) return false;

        // 必须使用 play timer（判定系统也以此为基准），不能使用 native event time（不同时间基）
        final long pressTime = player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY);

        // 不能直接用 stage.screenToStageCoordinates()：Stage 的 FitViewport 假设游戏画面
        // 等比居中渲染（pillarbox/letterbox），但拉伸至全屏时 MainController 视口铺满整个 surface，
        // 此时 FitViewport 的偏移/缩放与实际渲染区域不一致。统一走 MainController 的视口转换，
        // 与 select 界面 getMouseX/Y 走同一条路径，保证与渲染 1:1 对齐。
        int gameX = inputProcessor != null ? inputProcessor.convertScreenX(screenX) : screenX;
        int gameY = inputProcessor != null ? inputProcessor.convertScreenY(screenY) : screenY;
        // 与 KeyBoardInputProcesseor.touchDown 一致：Y 翻转
        tmpCoords.set(gameX, logicH - gameY);
        for (int i = 0; i < keyButtons.length; i++) {
            if (keyButtons[i] != null && keyButtons[i].getBounds().contains(tmpCoords.x, tmpCoords.y)) {
                pointerMap[pointer] = i;
                syncKeyState(i, pressTime, true);
                return true;
            }
        }
        return true;
    }

    @Override
    public boolean touchUp(int screenX, int screenY, int pointer, int button) {
        if (!enabled) return false;
        if (!regionsInitialized) return false;
        if (pointer < pointerMap.length && pointerMap[pointer] != -1) {
            int keyIdx = pointerMap[pointer];
            pointerMap[pointer] = -1;
            // 必须使用 play timer
            syncKeyState(keyIdx, player.timer.getNowMicroTime(SkinProperty.TIMER_PLAY), false);
            return true;
        }
        return true;
    }

    @Override
    public boolean touchDragged(int screenX, int screenY, int pointer) {
        if (!enabled) return false;
        if (!regionsInitialized) return false;
        // 消费所有触摸拖拽事件，防止传播到 Stage
        return pointer < pointerMap.length;
    }

    @Override public boolean touchCancelled(int screenX, int screenY, int pointer, int button) { return touchUp(screenX, screenY, pointer, button); }
    @Override public void dispose() { stage.dispose(); whitePixel.dispose(); }
    public boolean isEnabled() { return enabled; }
    public boolean isRegionsInitialized() { return regionsInitialized; }

    public void setLaneProperty(LaneProperty laneProperty) {
        this.laneProperty = laneProperty;
    }

    public boolean isConsumingTouch() {
        if (!enabled) return false;
        for (int p : pointerMap) {
            if (p != -1) return true;
        }
        return false;
    }

    private class TouchKeyButton extends Actor {
        private String label;
        private Color bgColor;
        private Rectangle bounds;

        public TouchKeyButton(float x, float y, float w, float h, String label, Color bgColor) {
            this.label = label;
            this.bgColor = bgColor;
            this.bounds = new Rectangle(x, y, w, h);
            setBounds(x, y, w, h);
        }

        public void updateBounds(float x, float y, float w, float h) {
            this.bounds.set(x, y, w, h);
            setBounds(x, y, w, h);
        }

        public void drawCustom(SpriteBatch batch, Texture white, BitmapFont font) {
            batch.setColor(bgColor.r, bgColor.g, bgColor.b, 0f);
            batch.draw(white, bounds.x, bounds.y, bounds.width, bounds.height);
        }

        public Rectangle getBounds() { return bounds; }
    }

    @Override public boolean keyDown(int k) { return false; }
    @Override public boolean keyUp(int k) { return false; }
    @Override public boolean keyTyped(char c) { return false; }
    @Override public boolean mouseMoved(int x, int y) { return false; }
    @Override public boolean scrolled(float x, float y) { return false; }
}
