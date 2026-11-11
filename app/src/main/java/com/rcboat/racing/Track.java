package com.rcboat.racing;

import java.util.ArrayList;

/**
 * The race course: a closed Catmull-Rom spline around the island, resampled at even
 * arc-length steps, plus the buoys that mark both edges of the course.
 */
final class Track {
    static final int N = 600;
    static final float HALF_WIDTH = 6.5f;
    static final float BUOY_RADIUS = 0.45f;

    private static final float[][] CONTROL = {
            {0, 32}, {25, 30}, {42, 22}, {50, 5}, {45, -14}, {30, -26}, {12, -22},
            {-6, -29}, {-28, -29}, {-46, -16}, {-51, 3}, {-42, 20}, {-22, 30}
    };

    final float[] px = new float[N], pz = new float[N];
    final float[] tx = new float[N], tz = new float[N];   // unit tangent
    final float[] cum = new float[N];                    // arc length at sample
    float length;
    float ds;

    /** Buoys: x, z, side (0 = left/red, 1 = right/yellow). */
    final ArrayList<float[]> buoys = new ArrayList<>();

    Track() {
        build();
        placeBuoys();
    }

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private void build() {
        int m = CONTROL.length;
        int fineN = m * 200;
        float[] fx = new float[fineN + 1], fz = new float[fineN + 1], fc = new float[fineN + 1];
        for (int i = 0; i <= fineN; i++) {
            float u = (float) i / fineN * m;
            int seg = Math.min((int) u, m - 1);
            float t = u - seg;
            float[] a = CONTROL[(seg - 1 + m) % m], b = CONTROL[seg],
                    c = CONTROL[(seg + 1) % m], d = CONTROL[(seg + 2) % m];
            fx[i] = cr(a[0], b[0], c[0], d[0], t);
            fz[i] = cr(a[1], b[1], c[1], d[1], t);
            if (i > 0) {
                float dx = fx[i] - fx[i - 1], dz = fz[i] - fz[i - 1];
                fc[i] = fc[i - 1] + (float) Math.sqrt(dx * dx + dz * dz);
            }
        }
        length = fc[fineN];
        ds = length / N;
        int j = 0;
        for (int i = 0; i < N; i++) {
            float target = i * ds;
            while (j < fineN - 1 && fc[j + 1] < target) j++;
            float seg = fc[j + 1] - fc[j];
            float f = seg > 0 ? (target - fc[j]) / seg : 0;
            px[i] = fx[j] + (fx[j + 1] - fx[j]) * f;
            pz[i] = fz[j] + (fz[j + 1] - fz[j]) * f;
            cum[i] = target;
        }
        for (int i = 0; i < N; i++) {
            int a = (i - 1 + N) % N, b = (i + 1) % N;
            float dx = px[b] - px[a], dz = pz[b] - pz[a];
            float l = (float) Math.sqrt(dx * dx + dz * dz);
            tx[i] = dx / l;
            tz[i] = dz / l;
        }
    }

    /** Left-hand normal (relative to direction of travel). */
    float nx(int i) { return tz[i]; }
    float nz(int i) { return -tx[i]; }

    private void placeBuoys() {
        float spacing = 9f;
        int step = Math.max(1, Math.round(spacing / ds));
        for (int i = step; i < N - step / 2; i += step) {
            for (int side = 0; side < 2; side++) {
                float s = side == 0 ? 1f : -1f;
                float bx = px[i] + nx(i) * HALF_WIDTH * s;
                float bz = pz[i] + nz(i) * HALF_WIDTH * s;
                if (!World.isWater(bx, bz)) continue;
                boolean ok = true;
                for (float[] b : buoys) {
                    float dx = b[0] - bx, dz = b[1] - bz;
                    if (dx * dx + dz * dz < 5.5f * 5.5f) { ok = false; break; }
                }
                // keep buoys out of the course on tight inside corners
                int near = nearestGlobal(bx, bz);
                float dx = px[near] - bx, dz = pz[near] - bz;
                if (dx * dx + dz * dz < (HALF_WIDTH - 1f) * (HALF_WIDTH - 1f)) ok = false;
                if (ok) buoys.add(new float[]{bx, bz, side});
            }
        }
    }

    int wrap(int i) {
        i %= N;
        return i < 0 ? i + N : i;
    }

    int nearestGlobal(float x, float z) {
        int best = 0;
        float bd = Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            float dx = px[i] - x, dz = pz[i] - z;
            float d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    int nearest(float x, float z, int hint, int window) {
        int best = hint;
        float bd = Float.MAX_VALUE;
        for (int k = -window; k <= window; k++) {
            int i = wrap(hint + k);
            float dx = px[i] - x, dz = pz[i] - z;
            float d = dx * dx + dz * dz;
            if (d < bd) { bd = d; best = i; }
        }
        return best;
    }

    float distTo(int i, float x, float z) {
        float dx = px[i] - x, dz = pz[i] - z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    /** Signed distance along the tangent from sample i to (x, z). */
    float along(int i, float x, float z) {
        return (x - px[i]) * tx[i] + (z - pz[i]) * tz[i];
    }

    /** Heading (radians) that points along the tangent at sample i. */
    float headingAt(int i) {
        return (float) Math.atan2(-tx[i], -tz[i]);
    }
}
