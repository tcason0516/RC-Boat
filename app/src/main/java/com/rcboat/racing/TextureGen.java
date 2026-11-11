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

    static final int ATLAS_N = 512;

    /**
     * RGBA foliage atlas, four 256x256 regions:
     * (0,0) conifer branch (u along the branch from trunk to tip, v across),
     * (0.5,0) leaf cluster, (0,0.5) grass tuft, (0.5,0.5) reeds with cattails.
     * For grass and reeds v = 0 is the tip and v = 1 the base.
     */
    static byte[] foliageAtlas() {
        int n = ATLAS_N, h = n / 2;
        float[] r = new float[n * n], g = new float[n * n], b = new float[n * n], a = new float[n * n];
        Random rnd = new Random(23);

        // --- conifer branch ---
        for (int k = 0; k < 900; k++) {
            float t = rnd.nextFloat();                     // position along the branch
            float x0 = 6 + t * 240, y0 = 128;
            float env = 105 * (1 - t * 0.75f);             // sprig envelope narrows to the tip
            float side = rnd.nextBoolean() ? 1 : -1;
            float len = env * (0.35f + 0.65f * rnd.nextFloat());
            float ang = (float) Math.toRadians(35 + rnd.nextFloat() * 40) * side;
            float x1 = x0 + (float) Math.cos(ang) * len * 0.55f, y1 = y0 + (float) Math.sin(ang) * len;
            float shade = 0.75f + rnd.nextFloat() * 0.45f;
            line(r, g, b, a, n, 0, 0, x0, y0, x1, y1, 1.6f, 0.07f * shade, 0.20f * shade, 0.10f * shade);
            // needles along the sprig
            for (int m = 0; m < 6; m++) {
                float f = rnd.nextFloat();
                float px = x0 + (x1 - x0) * f, py = y0 + (y1 - y0) * f;
                float na = ang + (rnd.nextFloat() - 0.5f) * 1.6f;
                float nl = 6 + rnd.nextFloat() * 9;
                float s2 = 0.8f + rnd.nextFloat() * 0.5f;
                line(r, g, b, a, n, 0, 0, px, py, px + (float) Math.cos(na) * nl, py + (float) Math.sin(na) * nl,
                        1.1f, 0.09f * s2, 0.25f * s2, 0.12f * s2);
            }
        }
        line(r, g, b, a, n, 0, 0, 2, 128, 250, 128, 3.2f, 0.26f, 0.18f, 0.11f);

        // --- leaf cluster ---
        for (int k = 0; k < 420; k++) {
            double ang = rnd.nextDouble() * Math.PI * 2;
            float rad = (float) Math.sqrt(rnd.nextFloat()) * 108;
            float cx = 128 + (float) Math.cos(ang) * rad, cy = 128 + (float) Math.sin(ang) * rad;
            float lw = 5 + rnd.nextFloat() * 4, ll = 10 + rnd.nextFloat() * 9;
            float rot = rnd.nextFloat() * 6.28f;
            float shade = 0.7f + rnd.nextFloat() * 0.55f;
            float hue = rnd.nextFloat() * 0.08f;
            leaf(r, g, b, a, n, h, 0, cx, cy, lw, ll, rot, (0.17f + hue) * shade, 0.36f * shade, 0.10f * shade);
        }

        // --- grass tuft ---
        for (int k = 0; k < 70; k++) {
            float bx = 30 + rnd.nextFloat() * 196, tipY = 10 + rnd.nextFloat() * 120;
            float lean = (rnd.nextFloat() - 0.5f) * 70;
            float shade = 0.75f + rnd.nextFloat() * 0.45f, dry = rnd.nextFloat() * 0.35f;
            blade(r, g, b, a, n, 0, h, bx, 255, bx + lean, tipY, 3.5f,
                    (0.24f + dry * 0.5f) * shade, (0.42f + dry * 0.1f) * shade, 0.14f * shade);
        }

        // --- reeds with cattails ---
        for (int k = 0; k < 34; k++) {
            float bx = 40 + rnd.nextFloat() * 176, tipY = 4 + rnd.nextFloat() * 60;
            float lean = (rnd.nextFloat() - 0.5f) * 40;
            float shade = 0.8f + rnd.nextFloat() * 0.35f;
            blade(r, g, b, a, n, h, h, bx, 255, bx + lean, tipY, 3.0f, 0.30f * shade, 0.44f * shade, 0.18f * shade);
        }
        for (int k = 0; k < 9; k++) {
            float bx = 50 + rnd.nextFloat() * 156, topY = 20 + rnd.nextFloat() * 50;
            blade(r, g, b, a, n, h, h, bx, 255, bx, topY, 1.8f, 0.36f, 0.42f, 0.2f);
            line(r, g, b, a, n, h, h, bx, topY + 8, bx, topY + 34, 6.5f, 0.32f, 0.20f, 0.11f);
        }

        // bleed colour into transparent texels so mipmaps don't get dark fringes
        byte[] out = new byte[n * n * 4];
        for (int i = 0; i < n * n; i++) {
            float al = Math.min(1f, a[i]);
            float cr = al > 0 ? r[i] / a[i] : 0.12f, cg = al > 0 ? g[i] / a[i] : 0.24f, cb = al > 0 ? b[i] / a[i] : 0.10f;
            out[i * 4] = (byte) Math.round(Math.min(1f, cr) * 255);
            out[i * 4 + 1] = (byte) Math.round(Math.min(1f, cg) * 255);
            out[i * 4 + 2] = (byte) Math.round(Math.min(1f, cb) * 255);
            out[i * 4 + 3] = (byte) Math.round(al * 255);
        }
        return out;
    }

    /** Anti-aliased thick line into a region at (ox, oy); colours accumulate weighted by coverage. */
    private static void line(float[] r, float[] g, float[] b, float[] a, int n, int ox, int oy,
                             float x0, float y0, float x1, float y1, float w, float cr, float cg, float cb) {
        int minX = (int) Math.max(0, Math.floor(Math.min(x0, x1) - w - 1));
        int maxX = (int) Math.min(255, Math.ceil(Math.max(x0, x1) + w + 1));
        int minY = (int) Math.max(0, Math.floor(Math.min(y0, y1) - w - 1));
        int maxY = (int) Math.min(255, Math.ceil(Math.max(y0, y1) + w + 1));
        float dx = x1 - x0, dy = y1 - y0, l2 = Math.max(1e-4f, dx * dx + dy * dy);
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                float t = Math.max(0, Math.min(1, ((x - x0) * dx + (y - y0) * dy) / l2));
                float px = x0 + dx * t - x, py = y0 + dy * t - y;
                float d = (float) Math.sqrt(px * px + py * py);
                float cov = Math.max(0, Math.min(1, w * 0.5f + 0.5f - d));
                if (cov > 0) put(r, g, b, a, n, ox + x, oy + y, cov, cr, cg, cb);
            }
        }
    }

    /** Tapering grass blade from base (x0,y0) to tip (x1,y1). */
    private static void blade(float[] r, float[] g, float[] b, float[] a, int n, int ox, int oy,
                              float x0, float y0, float x1, float y1, float w, float cr, float cg, float cb) {
        int steps = 24;
        for (int i = 0; i < steps; i++) {
            float t0 = (float) i / steps, t1 = (float) (i + 1) / steps;
            float bend = (float) Math.sin(t0 * 1.6f) * (x1 - x0) * 0.25f;
            float ax = x0 + (x1 - x0) * t0 * t0 + bend * 0.2f, ay = y0 + (y1 - y0) * t0;
            float bx = x0 + (x1 - x0) * t1 * t1 + bend * 0.2f, by = y0 + (y1 - y0) * t1;
            float k = 0.8f + 0.35f * t0;
            line(r, g, b, a, n, ox, oy, ax, ay, bx, by, w * (1 - t0 * 0.85f), cr * k, cg * k, cb * k);
        }
    }

    private static void leaf(float[] r, float[] g, float[] b, float[] a, int n, int ox, int oy,
                             float cx, float cy, float lw, float ll, float rot, float cr, float cg, float cb) {
        float cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
        int ext = (int) Math.ceil(ll) + 2;
        for (int y = (int) cy - ext; y <= (int) cy + ext; y++) {
            for (int x = (int) cx - ext; x <= (int) cx + ext; x++) {
                if (x < 0 || y < 0 || x > 255 || y > 255) continue;
                float lx = (x - cx) * cs + (y - cy) * sn, ly = -(x - cx) * sn + (y - cy) * cs;
                float e = (lx * lx) / (ll * ll) + (ly * ly) / (lw * lw);
                float cov = Math.max(0, Math.min(1, (1 - e) * 4));
                if (cov <= 0) continue;
                float vein = Math.abs(ly) < 0.7f ? 0.85f : 1f;
                float grad = 0.85f + 0.3f * (lx / ll * 0.5f + 0.5f);
                put(r, g, b, a, n, ox + x, oy + y, cov, cr * vein * grad, cg * vein * grad, cb * vein * grad);
            }
        }
    }

    private static void put(float[] r, float[] g, float[] b, float[] a, int n, int x, int y, float cov,
                            float cr, float cg, float cb) {
        int i = y * n + x;
        // "over" compositing in premultiplied form
        float keep = 1 - cov;
        r[i] = r[i] * keep + cr * cov;
        g[i] = g[i] * keep + cg * cov;
        b[i] = b[i] * keep + cb * cov;
        a[i] = a[i] * keep + cov;
    }

    private static float smooth(float t) { return t * t * (3 - 2 * t); }

    private static byte toByte(float v) {
        return (byte) Math.round(Math.max(0f, Math.min(1f, v * 0.5f + 0.5f)) * 255f);
    }
}
