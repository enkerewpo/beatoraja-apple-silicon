package com.starxh.beatoraja.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.starxh.beatoraja.BeatorajaGame;

import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainLoader;
import bms.player.beatoraja.PlayerConfig;

import java.io.File;

/**
 * 桌面端启动入口（LWJGL3）。
 *
 * 为什么需要它：上游 beatoraja 用 libGDX 1.9.9 + LWJGL2 启动，而 LWJGL2 从来没有、
 * 也永远不会有 arm64 macOS 支持——在 Apple Silicon 上只能靠 Rosetta 跑 x86_64，
 * 结果是苹果 OpenGL-over-Metal 转译层在纹理路径上段错误，且 Vorbis 解码跟不上导致掉音。
 * 本 fork 的 core 已迁到 libGDX 1.14（后端无关），补一个 LWJGL3 后端即可原生运行。
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        final File root = new File(System.getProperty("beatoraja.root", ".")).getAbsoluteFile();
        System.out.println("[desktop] root = " + root);
        System.out.println("[desktop] os.arch = " + System.getProperty("os.arch")
                + "  java = " + System.getProperty("java.version"));

        final Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("beatoraja (desktop / native)");
        cfg.setWindowedMode(1280, 720);
        // macOS 窗口模式下 GLFW 的 swap interval 并不总是生效：60Hz 屏上实测跑出 93~106fps
        // 且帧间隔不均，观感就是"跑不满"的抖动。因此在 vsync 之外再显式限帧。
        cfg.useVsync(true);
        cfg.setForegroundFPS(Integer.getInteger("oraja.fps", 60));

        // libGDX 的 OpenAL 默认只有 16 个并发声部，高密度谱面必然丢键音。
        // beatoraja 自己的 audio.deviceSimultaneousSources 只作用于它的上层逻辑，
        // 真正的硬上限在这里，必须显式放开。
        final int[] audio = readAudioConfig(root);
        cfg.setAudioConfig(audio[0], audio[1], 9);
        System.out.println("[desktop] OpenAL 声部=" + audio[0] + " 缓冲=" + audio[1]);
        // Retina 上 LWJGL3 的帧缓冲是逻辑窗口的 2 倍，而皮肤按逻辑尺寸绘制，
        // 默认 HdpiMode.Pixels 会让整个界面缩在左下角（GL 原点在左下）。
        // 改用 Logical，让 Gdx.graphics 报告逻辑像素，界面铺满窗口。
        cfg.setHdpiMode(HdpiMode.Logical);
        // 用默认的 GL20（macOS 兼容 profile 2.1）。
        // 曾试过 GL32 core profile，但 libGDX 与 beatoraja 的着色器都是 GLSL 1.20 语法
        // （varying/attribute），在 core profile 下非法，SpriteBatch 直接编译失败。
        // 之前 Rosetta 下的 AppleMetalOpenGLRenderer 崩溃属于 LWJGL2 + 2018 年 libGDX 的组合，
        // 原生 arm64 + LWJGL3 是另一套代码路径，先验证 GL20 是否稳定。

        new Lwjgl3Application(new Bootstrap(root), cfg);
    }

    /**
     * 只为音频参数做一次极简读取：这些值必须在 Lwjgl3Application 构造前就确定，
     * 而 core 的 Config.read() 依赖 Gdx.files/Gdx.app，那时还不可用。
     * 返回 {声部数, 缓冲大小}。
     */
    private static int[] readAudioConfig(File root) {
        int sources = 256;
        int buffer = 1024;
        final File f = new File(root, "config.json");
        if (f.isFile()) {
            try {
                final String t = new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
                sources = readInt(t, "deviceSimultaneousSources", sources);
                buffer = readInt(t, "deviceBufferSize", buffer);
            } catch (Exception e) {
                System.err.println("[desktop] 读取音频配置失败，使用默认值: " + e);
            }
        }
        return new int[] { sources, buffer };
    }

    /** 从 JSON 文本里取一个整数字段，取不到就返回默认值。只用于启动前的少量参数。 */
    private static int readInt(String json, String key, int fallback) {
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /**
     * 配置读取必须发生在 Application 生命周期内：core 的 Config.read() 依赖 Gdx.files 取文件、
     * 依赖 Gdx.app 记日志，两者都要等 Lwjgl3Application 构造完才可用。
     * 因此这里先以壳启动，在 create() 里读配置、装数据库实现，再委托给真正的 BeatorajaGame。
     */
    private static final class Bootstrap implements ApplicationListener {

        private final File root;
        private BeatorajaGame game;
        private long lastFpsLog;

        Bootstrap(File root) {
            this.root = root;
        }

        @Override
        public void create() {
            final Config config = Config.read();
            final String name = config.getPlayername() == null ? "player1" : config.getPlayername();
            final PlayerConfig player = PlayerConfig.readPlayerConfig(name, null);

            // core 的 MainLoader 只保留注入点，具体实现由各平台提供。
            // 用独立的数据库文件：上游 beatoraja 0.8.8 与本 fork 的 SongUtils.crc32 约定不同
            // （同一目录算出的 CRC 不一致），共用一个库会导致目录层级对不上、曲目全部消失。
            // 这里读迁移过 CRC 的副本，原 songdata.db 保持不动，两者可共存。
            File db = new File(root, "songdata-desktop.db");
            if (!db.exists()) db = new File(root, "songdata.db");
            final String dbPath = db.getAbsolutePath();
            MainLoader.setSongDatabaseAccessor(
                    new JdbcSongDatabaseAccessor(dbPath, config.getBmsroot()));
            bms.player.beatoraja.ScoreDatabaseAccessor.setFactory(
                    path -> new StubScoreDatabaseAccessor());

            game = new BeatorajaGame(root, config, player, BMSPlayerMode.PLAY, false);
            game.create();
        }

        @Override
        public void render() {
            if (game != null) {
                game.render();
            }
            // 帧率采样：每 5 秒打一次，便于定位渲染瓶颈
            final long now = System.currentTimeMillis();
            if (now - lastFpsLog > 5000) {
                lastFpsLog = now;
                System.out.println("[desktop] fps=" + com.badlogic.gdx.Gdx.graphics.getFramesPerSecond()
                        + "  backbuffer=" + com.badlogic.gdx.Gdx.graphics.getBackBufferWidth()
                        + "x" + com.badlogic.gdx.Gdx.graphics.getBackBufferHeight()
                        + "  logical=" + com.badlogic.gdx.Gdx.graphics.getWidth()
                        + "x" + com.badlogic.gdx.Gdx.graphics.getHeight());
            }
        }

        @Override
        public void resize(int width, int height) {
            if (game != null) {
                game.resize(width, height);
            }
        }

        @Override
        public void pause() {
            if (game != null) {
                game.pause();
            }
        }

        @Override
        public void resume() {
            if (game != null) {
                game.resume();
            }
        }

        @Override
        public void dispose() {
            if (game != null) {
                game.dispose();
            }
        }
    }
}
