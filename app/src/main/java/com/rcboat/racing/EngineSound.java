package com.rcboat.racing;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/** Synthesised electric RC-boat motor whine, water hiss and countdown beeps. */
final class EngineSound implements Runnable {
    private static final int RATE = 22050;
    private final Game game;
    private volatile boolean running;
    private Thread thread;

    EngineSound(Game game) { this.game = game; }

    void start() {
        if (running) return;
        running = true;
        thread = new Thread(this, "engine-sound");
        thread.start();
    }

    void stop() {
        running = false;
        if (thread != null) {
            try { thread.join(500); } catch (InterruptedException ignored) { }
            thread = null;
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void run() {
        int minBuf = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, 2048);
        AudioTrack track;
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, RATE, AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, bufSize * 2, AudioTrack.MODE_STREAM);
            track.play();
        } catch (Exception e) {
            running = false;
            return;
        }
        short[] buf = new short[512];
        double phase = 0, phase2 = 0, beepPhase = 0;
        float vol = 0, freq = 120, hissLp = 0;
        int beepLeft = 0;
        float beepFreq = 0;
        java.util.Random rnd = new java.util.Random();
        while (running) {
            int req = game.beepRequest;
            if (req != 0) {
                game.beepRequest = 0;
                beepFreq = req;
                beepLeft = (int) (RATE * (req > 900 ? 0.45f : 0.18f));
                beepPhase = 0;
            }
            boolean on = game.soundOn && game.engineOn;
            float lvl = game.engineLevel, thr = game.engineThrottle;
            float targetVol = on ? 0.10f + 0.22f * thr + 0.08f * lvl : 0f;
            if (game.talking) targetVol *= 0.35f;   // duck under the trash talk
            float targetFreq = 110f + 520f * lvl + 90f * thr;
            boolean beepOn = game.soundOn;
            for (int i = 0; i < buf.length; i++) {
                vol += (targetVol - vol) * 0.0015f;
                freq += (targetFreq - freq) * 0.002f;
                phase += freq / RATE;
                if (phase > 1) phase -= 1;
                phase2 += freq * 2.01 / RATE;
                if (phase2 > 1) phase2 -= 1;
                float saw = (float) (phase * 2 - 1);
                float sq = phase2 < 0.5 ? 0.6f : -0.6f;
                float hiss = rnd.nextFloat() * 2 - 1;
                hissLp += (hiss - hissLp) * 0.25f;
                float s = (saw * 0.45f + sq * 0.25f) * vol + hissLp * vol * (0.25f + lvl * 0.5f);
                if (beepLeft > 0) {
                    beepPhase += beepFreq / RATE;
                    float env = Math.min(1f, beepLeft / 800f);
                    if (beepOn) s += (float) Math.sin(beepPhase * 2 * Math.PI) * 0.35f * env;
                    beepLeft--;
                }
                if (s > 1) s = 1;
                if (s < -1) s = -1;
                buf[i] = (short) (s * 30000);
            }
            track.write(buf, 0, buf.length);
        }
        try {
            track.stop();
        } catch (Exception ignored) { }
        track.release();
    }
}
