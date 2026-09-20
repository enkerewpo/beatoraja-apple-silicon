package com.starxh.beatoraja.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3WindowAdapter;
import com.badlogic.gdx.graphics.glutils.HdpiMode;
import com.starxh.beatoraja.BeatorajaGame;

import bms.player.beatoraja.BMSPlayerMode;
import bms.player.beatoraja.Config;
import bms.player.beatoraja.MainLoader;
import bms.player.beatoraja.PlayerConfig;

import java.io.File;

/**
 * Desktop entry point (LWJGL3).
 *
 * Upstream beatoraja boots through libGDX 1.9.9 with the LWJGL 2 backend, and LWJGL 2 has
 * never supported arm64 macOS and never will. On Apple Silicon that leaves only Rosetta and
 * x86_64, where Apple's OpenGL-over-Metal layer segfaults on the texture path and Vorbis
 * decoding cannot keep up with playback, dropping keysounds. The core in this repository is
 * already on libGDX 1.14 and backend-agnostic, so a LWJGL3 backend is all that is needed.
 */
public final class DesktopLauncher {

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        final File root = resolveRoot();
        // Once packaged as a .app and started from Finder the working directory is /, and
        // core resolves plenty of paths relatively (config.json, font/VL-Gothic-Regular.ttf,
        // skin/...). Java cannot chdir, so tell core where the root is and keep user.dir in
        // sync; the .app launch script additionally cd's before starting the JVM.
        System.setProperty("beatoraja.root", root.getAbsolutePath());
        System.setProperty("user.dir", root.getAbsolutePath());
        Config.updateConfigPath();

        System.out.println("[desktop] root = " + root);
        System.out.println("[desktop] os.arch = " + System.getProperty("os.arch")
                + "  java = " + System.getProperty("java.version"));

        final Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("beatoraja — Apple Silicon");
        cfg.setWindowedMode(1280, 720);
        // GLFW's swap interval is not reliably honoured in windowed mode on macOS: a 60Hz
        // display measured 93-106fps with uneven frame pacing, which reads as judder. Cap
        // the rate explicitly on top of vsync.
        cfg.useVsync(true);
        cfg.setForegroundFPS(Integer.getInteger("oraja.fps", 60));

        // libGDX defaults to 16 simultaneous OpenAL sources, which drops keysounds on dense
        // charts. beatoraja's own audio.deviceSimultaneousSources only affects its upper
        // layer; this is the hard limit and it has to be raised explicitly.
        final int[] audio = readAudioConfig(root);
        cfg.setAudioConfig(audio[0], audio[1], 9);
        System.out.println("[desktop] OpenAL sources=" + audio[0] + " buffer=" + audio[1]);

        // On Retina the LWJGL3 framebuffer is 2x the logical window while skins draw in
        // logical units; the default HdpiMode.Pixels leaves the UI in the bottom-left corner
        // (the GL origin). Logical makes Gdx.graphics report logical pixels.
        cfg.setHdpiMode(HdpiMode.Logical);

        // Stay on the default GL20 profile. A GL 3.2 core profile was tried, but both libGDX
        // and beatoraja shaders use GLSL 1.20 syntax (varying/attribute), which is illegal
        // there and fails SpriteBatch compilation outright.

        // Quit when the window close button is used. ESC is consumed by the Android back-key
        // logic inherited from the Android port and has no desktop equivalent, so without
        // this there is no way to quit at all.
        cfg.setWindowListener(new Lwjgl3WindowAdapter() {
            @Override
            public boolean closeRequested() {
                com.badlogic.gdx.Gdx.app.exit();
                return true;
            }
        });

        new Lwjgl3Application(new Bootstrap(root), cfg);

        // beatoraja leaves non-daemon threads behind (Java Sound Sequencer, decoder threads)
        // that keep the JVM alive after the main loop returns, so end the process explicitly.
        System.exit(0);
    }

    /**
     * Locate the beatoraja installation directory.
     *
     * Inside a .app the working directory is unpredictable (Finder usually gives /), so the
     * CWD alone is not enough. Order: -Dbeatoraja.root, then the CWD if it looks like an
     * installation, then the usual locations.
     */
    private static File resolveRoot() {
        final String explicit = System.getProperty("beatoraja.root");
        if (explicit != null && !explicit.isEmpty()) {
            return new File(explicit).getAbsoluteFile();
        }
        final File cwd = new File(".").getAbsoluteFile();
        if (new File(cwd, "skin").isDirectory()) {
            return cwd;
        }
        final String home = System.getProperty("user.home");
        final File[] guesses = {
            new File(home, "Games/beatoraja0.8.8-modernchic"),
            new File(home, "Games/beatoraja"),
            new File(home, "beatoraja"),
        };
        for (File g : guesses) {
            if (new File(g, "skin").isDirectory()) {
                return g;
            }
        }
        return cwd;
    }

    /**
     * Minimal read of the audio settings only. These have to be known before the
     * Lwjgl3Application is constructed, while core's Config.read() needs Gdx.files and
     * Gdx.app, neither of which exists yet. Returns {sources, bufferSize}.
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
                System.err.println("[desktop] failed to read audio config, using defaults: " + e);
            }
        }
        return new int[] { sources, buffer };
    }

    /** Pull one integer field out of JSON text, falling back if absent. Startup values only. */
    private static int readInt(String json, String key, int fallback) {
        final java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\"\\s*:\\s*(\\d+)")
                .matcher(json);
        return m.find() ? Integer.parseInt(m.group(1)) : fallback;
    }

    /**
     * Config has to be read inside the Application lifecycle: core's Config.read() reads
     * through Gdx.files and logs through Gdx.app, and both only exist once Lwjgl3Application
     * has been constructed. So start with a shell that reads the config, installs the
     * database implementations, and then delegates to the real BeatorajaGame.
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

            // core's MainLoader only keeps the injection points; each platform supplies the
            // implementation. SongUtils.crc32 now matches upstream, so a songdata.db written
            // by upstream beatoraja can be read directly with no migration.
            final File db = new File(root, "songdata.db");
            MainLoader.setSongDatabaseAccessor(
                    new JdbcSongDatabaseAccessor(db.getAbsolutePath(), config.getBmsroot()));
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
            // Frame rate sample every five seconds, to locate rendering bottlenecks.
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
