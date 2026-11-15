package com.rcboat.racing;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Random;

/**
 * RC Boat Combat sound effects. Each one is synthesised once (noise, sweeps and decaying
 * tones), saved as a small WAV in the app's cache and played through a SoundPool.
 */
final class Sfx {
    private static final int RATE = 22050;
    private SoundPool pool;
    private final int[] ids = new int[Combat.S_COUNT];
    private volatile boolean ready;

    Sfx(Context ctx) {
        final File dir = new File(ctx.getCacheDir(), "sfx2");
        new Thread(() -> {
            try {
                AudioAttributes attr = new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
                SoundPool sp = new SoundPool.Builder().setMaxStreams(14).setAudioAttributes(attr).build();
                if (!dir.exists() && !dir.mkdirs()) return;
                for (int i = 0; i < Combat.S_COUNT; i++) {
                    File f = new File(dir, "s" + i + ".wav");
                    if (!f.exists()) writeWav(f, make(i));
                    ids[i] = sp.load(f.getPath(), 1);
                }
                pool = sp;
                ready = true;
            } catch (Exception ignored) {
                ready = false;
            }
        }, "sfx-init").start();
    }

    void play(int id, float vol) {
        if (!ready || id < 0 || id >= ids.length) return;
        float v = Math.max(0f, Math.min(1f, vol));
        pool.play(ids[id], v, v, 1, 0, 0.94f + (float) Math.random() * 0.12f);
    }

    void release() {
        ready = false;
        if (pool != null) pool.release();
        pool = null;
    }

    // ------------------------------------------------------------------ synthesis

    static float[] make(int id) {
        Random r = new Random(100 + id);
        switch (id) {
            case Combat.S_GUN: return gun(r);
            case Combat.S_FLAME: return noise(r, 0.36f, 900f, 0.5f, 0.06f, 0.08f, 0f);
            case Combat.S_DRILL: return drill(r);
            case Combat.S_TORPEDO: return whoosh(r, 0.7f, 300f, 1500f, 0.6f, true);
            case Combat.S_HARPOON: return twang(r);
            case Combat.S_MINE: return plop();
            case Combat.S_CANNON: return noise(r, 0.36f, 5000f, 0.45f, 0.04f, 0.08f, 0.9f);
            case Combat.S_ROCKET: return whoosh(r, 0.55f, 700f, 3500f, 0.55f, false);
            case Combat.S_BOOM: return boom(r, 0.9f, 1f);
            case Combat.S_BIG_BOOM: return boom(r, 1.7f, 1.3f);
            case Combat.S_HIT: return clang(r, 0.18f, 0.5f);
            case Combat.S_PICKUP: return chime();
            case Combat.S_SHIELD: return hum();
            case Combat.S_EMP: return zap(r);
            case Combat.S_SPLASH: return noise(r, 0.45f, 1800f, 0.6f, 0.005f, 0.3f, 0.2f);
            case Combat.S_SINK: return gurgle(r);
            case Combat.S_RAM: return crunch(r);
            case Combat.S_CLANG: return clang(r, 0.3f, 0.7f);
        }
        return new float[RATE / 10];
    }

    private static float env(float t, float attack, float len) {
        if (t < attack) return t / attack;
        return (float) Math.exp(-4.5 * (t - attack) / Math.max(1e-3f, len - attack));
    }

    /** Filtered noise: cutoff (Hz), level, attack and release (s), hiss = high-pass mix. */
    private static float[] noise(Random r, float len, float cutoff, float level, float attack, float release, float hiss) {
        int n = (int) (len * RATE);
        float[] out = new float[n];
        float lp = 0, a = (float) (1 - Math.exp(-2 * Math.PI * cutoff / RATE));
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float w = r.nextFloat() * 2 - 1;
            lp += (w - lp) * a;
            float s = lp * (1 - hiss) + (w - lp) * hiss;
            float e = t < attack ? t / attack : t > len - release ? Math.max(0f, (len - t) / release) : 1f;
            out[i] = s * e * level * 2.2f;
        }
        return out;
    }

    private static float[] gun(Random r) {
        int n = (int) (0.11f * RATE);
        float[] out = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float w = r.nextFloat() * 2 - 1;
            lp += (w - lp) * 0.35f;
            float thump = (float) Math.sin(2 * Math.PI * 95 * t) * (float) Math.exp(-t * 45);
            out[i] = (lp * 1.4f * (float) Math.exp(-t * 55) + thump * 0.8f) * 0.8f;
        }
        return out;
    }

    private static float[] drill(Random r) {
        int n = (int) (0.4f * RATE);
        float[] out = new float[n];
        float ph = 0, lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            ph += 160f / RATE;
            float saw = (ph % 1f) * 2 - 1;
            float w = r.nextFloat() * 2 - 1;
            lp += (w - lp) * 0.2f;
            float am = 0.6f + 0.4f * (float) Math.sin(2 * Math.PI * 32 * t);
            out[i] = (saw * 0.5f + lp * 0.9f) * am * env(t, 0.01f, 0.4f) * 0.9f;
        }
        return out;
    }

    private static float[] whoosh(Random r, float len, float f0, float f1, float level, boolean splash) {
        int n = (int) (len * RATE);
        float[] out = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE, f = t / len;
            float cut = f0 + (f1 - f0) * (float) Math.sin(f * Math.PI * 0.5);
            float a = (float) (1 - Math.exp(-2 * Math.PI * cut / RATE));
            float w = r.nextFloat() * 2 - 1;
            lp += (w - lp) * a;
            float e = (float) Math.sin(Math.PI * Math.min(1f, f * 1.15f));
            out[i] = lp * e * level * 2f;
            if (splash && t < 0.18f) out[i] += (r.nextFloat() * 2 - 1) * (float) Math.exp(-t * 25) * 0.5f;
        }
        return out;
    }

    private static float[] twang(Random r) {
        int n = (int) (0.5f * RATE);
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float f = 210f - 40f * t;
            float s = (float) (Math.sin(2 * Math.PI * f * t) + 0.5 * Math.sin(2 * Math.PI * 2.01 * f * t)
                    + 0.25 * Math.sin(2 * Math.PI * 3.03 * f * t));
            float click = t < 0.02f ? (r.nextFloat() * 2 - 1) * (1 - t / 0.02f) : 0f;
            out[i] = (s * (float) Math.exp(-t * 7) * 0.4f + click * 0.8f);
        }
        return out;
    }

    private static float[] plop() {
        int n = (int) (0.22f * RATE);
        float[] out = new float[n];
        float ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float f = 380f * (float) Math.exp(-t * 14) + 70f;
            ph += f / RATE;
            out[i] = (float) Math.sin(2 * Math.PI * ph) * (float) Math.exp(-t * 16) * 0.9f;
        }
        return out;
    }

    private static float[] boom(Random r, float len, float level) {
        int n = (int) (len * RATE);
        float[] out = new float[n];
        float lp = 0, lp2 = 0, ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float w = r.nextFloat() * 2 - 1;
            float cut = 0.02f + 0.25f * (float) Math.exp(-t * 6);
            lp += (w - lp) * cut;
            lp2 += (lp - lp2) * 0.3f;
            ph += (55f * (float) Math.exp(-t * 1.5f) + 28f) / RATE;
            float sub = (float) Math.sin(2 * Math.PI * ph) * (float) Math.exp(-t * 3.5f);
            float e = t < 0.004f ? t / 0.004f : (float) Math.exp(-t * 3.2f / len);
            out[i] = (lp2 * 3.2f * e + sub * 0.9f) * level * 0.75f;
        }
        return out;
    }

    private static float[] clang(Random r, float len, float level) {
        int n = (int) (len * RATE);
        float[] out = new float[n];
        float[] fr = {820f, 1310f, 1780f, 2460f};
        float base = 0.9f + r.nextFloat() * 0.2f;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float s = 0;
            for (int k = 0; k < fr.length; k++) s += (float) Math.sin(2 * Math.PI * fr[k] * base * t) * (float) Math.exp(-t * (14 + k * 6)) / (k + 1);
            float tick = t < 0.006f ? (r.nextFloat() * 2 - 1) : 0f;
            out[i] = (s * 0.55f + tick * 0.6f) * level;
        }
        return out;
    }

    private static float[] chime() {
        int n = (int) (0.5f * RATE);
        float[] out = new float[n];
        float[] notes = {660f, 880f, 1320f};
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float s = 0;
            for (int k = 0; k < 3; k++) {
                float st = k * 0.07f;
                if (t < st) continue;
                float u = t - st;
                s += (float) Math.sin(2 * Math.PI * notes[k] * u) * (float) Math.exp(-u * 7) * 0.4f;
            }
            out[i] = s;
        }
        return out;
    }

    private static float[] hum() {
        int n = (int) (0.6f * RATE);
        float[] out = new float[n];
        float ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            ph += (180f + 260f * t / 0.6f) / RATE;
            float trem = 0.6f + 0.4f * (float) Math.sin(2 * Math.PI * 18 * t);
            out[i] = (float) (Math.sin(2 * Math.PI * ph) + 0.3 * Math.sin(4 * Math.PI * ph)) * trem
                    * (float) Math.sin(Math.PI * t / 0.6f) * 0.45f;
        }
        return out;
    }

    private static float[] zap(Random r) {
        int n = (int) (0.65f * RATE);
        float[] out = new float[n];
        float ph = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            ph += (2200f * (float) Math.exp(-t * 6) + 90f) / RATE;
            float sq = Math.sin(2 * Math.PI * ph) > 0 ? 1f : -1f;
            float crackle = r.nextFloat() < 0.08f ? r.nextFloat() * 2 - 1 : 0f;
            out[i] = (sq * 0.35f + crackle * 0.6f) * env(t, 0.005f, 0.65f) * 0.8f;
        }
        return out;
    }

    private static float[] gurgle(Random r) {
        int n = (int) (1.1f * RATE);
        float[] out = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float w = r.nextFloat() * 2 - 1;
            lp += (w - lp) * 0.04f;
            out[i] = lp * 2.2f * (float) Math.sin(Math.PI * t / 1.1f);
        }
        // bubbles: short rising blips
        for (int b = 0; b < 14; b++) {
            int st = (int) (r.nextFloat() * 0.95f * n);
            float f0 = 250f + r.nextFloat() * 500f;
            float ph = 0;
            for (int i = 0; i < RATE / 20 && st + i < n; i++) {
                float t = i / (float) RATE;
                ph += (f0 * (1 + t * 20)) / RATE;
                out[st + i] += (float) Math.sin(2 * Math.PI * ph) * (float) Math.exp(-t * 60) * 0.45f;
            }
        }
        return out;
    }

    private static float[] crunch(Random r) {
        int n = (int) (0.35f * RATE);
        float[] out = new float[n];
        float lp = 0;
        for (int i = 0; i < n; i++) {
            float t = i / (float) RATE;
            float w = r.nextFloat() * 2 - 1;
            lp += (w - lp) * 0.25f;
            float thud = (float) Math.sin(2 * Math.PI * 70 * t) * (float) Math.exp(-t * 18);
            float grit = r.nextFloat() < 0.3f ? lp : lp * 0.3f;
            out[i] = (grit * 1.3f * (float) Math.exp(-t * 12) + thud) * 0.85f;
        }
        return out;
    }

    private static void writeWav(File f, float[] pcm) throws IOException {
        int n = pcm.length;
        byte[] b = new byte[44 + n * 2];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.put("RIFF".getBytes()).putInt(36 + n * 2).put("WAVE".getBytes()).put("fmt ".getBytes())
                .putInt(16).putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16).put("data".getBytes()).putInt(n * 2);
        float peak = 0;
        for (float v : pcm) peak = Math.max(peak, Math.abs(v));
        float g = peak > 0.95f ? 0.95f / peak : 1f;
        for (float v : pcm) bb.putShort((short) Math.round(Math.max(-1f, Math.min(1f, v * g)) * 32000));
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(b); }
    }
}
