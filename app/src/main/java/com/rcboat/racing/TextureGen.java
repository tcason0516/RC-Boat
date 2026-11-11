package com.rcboat.racing;

import java.util.Random;

/**
 * Procedurally generated textures (plain Java, no Android dependencies):
 * a tileable water-ripple normal map, tileable detail noise, and lake depth maps.
 */
final class TextureGen {
    static final int WATER_N = 256;
    static final int NOISE_N = 256;
    static final int DEPTH_N = 512;
    /** Depth maps cover the terrain (+-TERRAIN_HALF) and store depth / DEPTH_SCALE metres. */
    static final float DEPTH_SCALE = 4f;

    private TextureGen() {}

    /** RGB normal map, r = nx, g = nz, b = ny (each 0..255 for -1..1). Tiles seamlessly. */
    static byte[] waterNormals() {
        int n = WATER_N;
        Random rnd = new Random(11);
        int waves = 48;
        float[] kx = new float[waves], kz = new float[waves], amp = new float[waves], ph = new float[waves];
        for (int i = 0; i < waves; i++) {
            int a, b;
            do {
                a = rnd.nextInt(17) - 8;
                b = rnd.nextInt(17) - 8;
            } while (a == 0 && b == 0);
            // bias wave directions roughly along one wind direction
            if (rnd.nextFloat() < 0.6f) b = Math.abs(b) + 1;
            kx[i] = a;
            kz[i] = b;
            float k = (float) Math.sqrt(a * a + b * b);
            amp[i] = (0.6f + rnd.nextFloat() * 0.8f) / (float) Math.pow(k, 1.6);
            ph[i] = rnd.nextFloat() * 6.2832f;
        }
        byte[] out = new byte[n * n * 3];
        float strength = 0.09f;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float u = (float) x / n, v = (float) y / n;
                float dx = 0, dz = 0;
                for (int i = 0; i < waves; i++) {
                    float arg = 6.2832f * (kx[i] * u + kz[i] * v) + ph[i];
                    float c = (float) Math.cos(arg) * amp[i] * 6.2832f;
                    dx += c * kx[i];
                    dz += c * kz[i];
                }
                float nx = -dx * strength, nz = -dz * strength, ny = 1f;
                float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                int o = (y * n + x) * 3;
                out[o] = toByte(nx / l);
                out[o + 1] = toByte(nz / l);
                out[o + 2] = toByte(ny / l);
            }
        }
        return out;
    }

    /** Single-channel tileable fractal value noise, 0..255. */
    static byte[] noise() {
        int n = NOISE_N;
        Random rnd = new Random(5);
        float[] acc = new float[n * n];
        float total = 0;
        float amp = 1f;
        for (int oct = 0; oct < 6; oct++) {
            int cells = 4 << oct;
            float[] grid = new float[cells * cells];
            for (int i = 0; i < grid.length; i++) grid[i] = rnd.nextFloat();
            for (int y = 0; y < n; y++) {
                float fy = (float) y / n * cells;
                int y0 = (int) fy;
                float ty = smooth(fy - y0);
                for (int x = 0; x < n; x++) {
                    float fx = (float) x / n * cells;
                    int x0 = (int) fx;
                    float tx = smooth(fx - x0);
                    int x1 = (x0 + 1) % cells, y1 = (y0 + 1) % cells;
                    float a = grid[y0 * cells + x0], b = grid[y0 * cells + x1];
                    float c = grid[y1 * cells + x0], d = grid[y1 * cells + x1];
                    float val = (a + (b - a) * tx) * (1 - ty) + (c + (d - c) * tx) * ty;
                    acc[y * n + x] += val * amp;
                }
            }
            total += amp;
            amp *= 0.55f;
        }
        // stretch contrast to the full range
        float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
        for (float f : acc) { lo = Math.min(lo, f); hi = Math.max(hi, f); }
        byte[] out = new byte[n * n];
        for (int i = 0; i < acc.length; i++) out[i] = (byte) Math.round((acc[i] - lo) / (hi - lo) * 255f);
        return out;
    }

    /** Single-channel lake depth (0 = dry/shoreline, 255 = DEPTH_SCALE metres or deeper). */
    static byte[] depth(boolean island) {
        int n = DEPTH_N;
        byte[] out = new byte[n * n];
        float size = World.TERRAIN_HALF * 2;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                float wx = -World.TERRAIN_HALF + (x + 0.5f) / n * size;
                float wz = -World.TERRAIN_HALF + (y + 0.5f) / n * size;
                float d = -World.height(wx, wz, island) / DEPTH_SCALE;
                out[y * n + x] = (byte) Math.round(Math.max(0f, Math.min(1f, d)) * 255f);
            }
        }
        return out;
    }

    private static float smooth(float t) { return t * t * (3 - 2 * t); }

    private static byte toByte(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v * 0.5f + 0.5f)) * 255f);
    }
}
