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
 * 音频验证：这次移植的全部意义就是音频。
 *
 * Rosetta + libGDX 1.9.9 下，1572 个 Vorbis 切片解码要 2~3 分钟，而曲子本身只有 2 分 04 秒，
 * 结果是「歌放完了键音还没解出来」——表现为掉音和卡顿（上游 issue #851，至今未解）。
 *
 * 本测试在原生 arm64 + libGDX 1.14 + LWJGL3 OpenAL 下测量：
 *   1. 全部切片的解码加载耗时（对比曲长，判断能否跟上）
 *   2. 密集并发播放时是否有声部被丢弃
 *
 * 用法：./gradlew :desktop:audioBench -Poraja.songdir=<含 .ogg 的谱面目录>
 */
public final class AudioBenchmark {

    private AudioBenchmark() {
    }

    public static void main(String[] args) {
        final String dir = System.getProperty("oraja.songdir");
        if (dir == null) {
            System.err.println("需要 -Doraja.songdir=<谱面目录>");
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
                    + "  (Rosetta 下会是 x86_64)");
            System.out.println("[bench] 目录 = " + dir);

            final File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".ogg"));
            if (files == null || files.length == 0) {
                System.out.println("[bench] 目录下没有 .ogg");
                Gdx.app.exit();
                return;
            }

            System.out.println("[bench] 切片数 = " + files.length + "，开始解码加载…");
            final long t0 = System.nanoTime();
            for (File f : files) {
                try {
                    sounds.add(Gdx.audio.newSound(new FileHandle(f)));
                } catch (Exception e) {
                    failed++;
                }
            }
            loadMillis = (System.nanoTime() - t0) / 1_000_000L;

            System.out.printf("[bench] 加载完成：%d 个成功 / %d 个失败，耗时 %d ms（平均 %.1f ms/个）%n",
                    sounds.size(), failed, loadMillis, loadMillis / (double) Math.max(files.length, 1));
            System.out.println("[bench] 参考：Katakoi Echo 曲长 124 秒。加载耗时若远小于曲长，则不会掉音。");
        }

        @Override
        public void render() {
            // 前若干帧密集触发，模拟高密度谱面的并发发音
            if (frame < 240 && !sounds.isEmpty()) {
                for (int i = 0; i < 8; i++) {
                    final Sound s = sounds.get((frame * 8 + i) % sounds.size());
                    if (s.play(0.25f) == -1) {
                        failed++;
                    }
                }
            }
            if (frame == 240) {
                System.out.println("[bench] 并发播放 1920 次触发完成，被拒绝的发音数 = " + failed);
                System.out.println("[bench] 若为 0，说明声部数充足、没有丢声。");
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
