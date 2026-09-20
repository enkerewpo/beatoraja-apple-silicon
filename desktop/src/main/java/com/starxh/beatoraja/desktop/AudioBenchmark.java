package com.starxh.beatoraja.desktop;

import com.badlogic.gdx.ApplicationListener;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.audio.Sound;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3Application;
import com.badlogic.gdx.backends.lwjgl3.Lwjgl3ApplicationConfiguration;
import com.badlogic.gdx.files.FileHandle;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Audio verification - the whole point of this port.
 *
 * Under Rosetta with libGDX 1.9.9, decoding 1572 Vorbis slices takes 2-3 minutes while the
 * chart itself is only 124 seconds long, so the song ends before its keysounds finish
 * loading. That shows up as dropped notes and stutter (upstream issue #851, still open).
 *
 * This measures, on native arm64 with libGDX 1.14 and LWJGL3 OpenAL:
 *   1. how long decoding every slice takes, against the chart length
 *   2. whether voices get rejected under dense concurrent playback
 *
 * Usage: ./gradlew :desktop:audioBench -Poraja.songdir=<directory containing .ogg files>
 */
public final class AudioBenchmark {

    private AudioBenchmark() {
    }

    public static void main(String[] args) {
        final String dir = System.getProperty("oraja.songdir");
        if (dir == null) {
            System.err.println("need -Doraja.songdir=<chart directory>");
            System.exit(2);
        }

        final Lwjgl3ApplicationConfiguration cfg = new Lwjgl3ApplicationConfiguration();
        cfg.setTitle("beatoraja audio benchmark");
        cfg.setWindowedMode(480, 160);
        cfg.useVsync(false);

        new Lwjgl3Application(new Bench(new File(dir)), cfg);
    }

    private static final class Bench implements ApplicationListener {

        private final File dir;
        private final List<Sound> sounds = new ArrayList<>();
        private int frame;
        private long loadMillis;
        private int failed;

        Bench(File dir) {
            this.dir = dir;
        }

        @Override
        public void create() {
            System.out.println("[bench] os.arch = " + System.getProperty("os.arch")
                    + "  (would read x86_64 under Rosetta)");
            System.out.println("[bench] directory = " + dir);

            final File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".ogg"));
            if (files == null || files.length == 0) {
                System.out.println("[bench] no .ogg files in that directory");
                Gdx.app.exit();
                return;
            }

            System.out.println("[bench] slices = " + files.length + ", decoding...");
            final long t0 = System.nanoTime();
            for (File f : files) {
                try {
                    sounds.add(Gdx.audio.newSound(new FileHandle(f)));
                } catch (Exception e) {
                    failed++;
                }
            }
            loadMillis = (System.nanoTime() - t0) / 1_000_000L;

            System.out.printf("[bench] loaded %d ok / %d failed in %d ms (%.1f ms each)%n",
                    sounds.size(), failed, loadMillis, loadMillis / (double) Math.max(files.length, 1));
            System.out.println("[bench] reference: Katakoi Echo runs 124 s. Load time far below that means no dropouts.");
        }

        @Override
        public void render() {
            // Fire densely for the first frames, simulating a high-density chart
            if (frame < 240 && !sounds.isEmpty()) {
                for (int i = 0; i < 8; i++) {
                    final Sound s = sounds.get((frame * 8 + i) % sounds.size());
                    // volume 0: voice allocation is what matters, no need to make noise
                    if (s.play(0f) == -1) {
                        failed++;
                    }
                }
            }
            if (frame == 240) {
                System.out.println("[bench] 1920 concurrent triggers done, voices rejected = " + failed);
                System.out.println("[bench] zero means the voice count is sufficient and nothing was dropped.");
            }
            if (frame > 300) {
                Gdx.app.exit();
            }
            frame++;
        }

        @Override
        public void resize(int width, int height) {
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void dispose() {
            for (Sound s : sounds) {
                s.dispose();
            }
        }
    }
}
