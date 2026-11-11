package com.rcboat.racing;

import java.util.ArrayList;

/**
 * A race course: a closed Catmull-Rom spline resampled at even arc-length steps, plus the
 * buoys that mark both edges of the course. Ten layouts are available.
 */
final class Track {
    static final int N = 600;
    static final float HALF_WIDTH = 6.5f;
    static final float BUOY_RADIUS = 0.45f;

    static final String[] NAMES = {
            "Island Loop", "Grand Oval", "Kidney Bean", "Figure Eight", "Triangle",
            "Serpentine", "Dog Bone", "Clover", "Speedway", "Heart"
    };
    static final int COUNT = NAMES.length;

    private static final Track[] CACHE = new Track[COUNT];

    static synchronized Track get(int id) {
        if (CACHE[id] == null) CACHE[id] = new Track(id);
        return CACHE[id];
    }

    final int id;
    final String name;
    final boolean hasIsland;     // the layout leaves room for the central island
    final boolean crosses;       // the layout crosses itself (figure eight)

    final float[] px = new float[N], pz = new float[N];
    final float[] tx = new float[N], tz = new float[N];   // unit tangent
    final float[] cum = new float[N];                    // arc length at sample
    float length;
    float ds;
    float minX, maxX, minZ, maxZ;

    /** Buoys: x, z, side (0 = left/red, 1 = right/yellow). */
    final ArrayList<float[]> buoys = new ArrayList<>();

    private Track(int id) {
        this.id = id;
        this.name = NAMES[id];
        this.hasIsland = id != 3 && id != 6 && id != 7 && id != 9;
        this.crosses = id == 3;
        build(controlPoints(id));
        chooseStart();
        placeBuoys();
        minX = minZ = Float.MAX_VALUE;
        maxX = maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            minX = Math.min(minX, px[i]); maxX = Math.max(maxX, px[i]);
            minZ = Math.min(minZ, pz[i]); maxZ = Math.max(maxZ, pz[i]);
        }
    }

    // ------------------------------------------------------------------ layouts

    static float[][] controlPoints(int id) {
        switch (id) {
            default:
            case 0: // Island Loop
                return new float[][]{
                        {16, 31.5f}, {30, 28.5f}, {42, 22}, {50, 5}, {45, -14}, {30, -26}, {12, -22},
                        {-6, -29}, {-28, -29}, {-46, -16}, {-51, 3}, {-42, 20}, {-22, 30}, {0, 32}};
            case 1: // Grand Oval
                return ellipse(50, 30, 16);
            case 2: // Kidney Bean
                return new float[][]{
                        {0, 32}, {18, 31}, {36, 26}, {50, 10}, {49, -11}, {36, -27}, {17, -28},
                        {0, -23}, {-17, -28}, {-36, -27}, {-49, -11}, {-50, 10}, {-36, 26}, {-18, 31}};
            case 3: { // Figure Eight
                int n = 20;
                float[][] p = new float[n][];
                for (int i = 0; i < n; i++) {
                    double t = 2 * Math.PI * i / n;
                    p[i] = new float[]{(float) (48 * Math.sin(t)), (float) (27 * Math.sin(2 * t))};
                }
                return p;
            }
            case 4: // Triangle
                return new float[][]{
                        {0, 30}, {18, 30}, {38, 28}, {49, 20}, {45, 8}, {28, -12}, {12, -28},
                        {0, -32}, {-12, -28}, {-28, -12}, {-45, 8}, {-49, 20}, {-38, 28}, {-18, 30}};
            case 5: // Serpentine
                return new float[][]{
                        {0, 30}, {20, 23}, {36, 27}, {47, 20}, {51, 4}, {48, -14}, {36, -25}, {19, -21},
                        {0, -28}, {-19, -21}, {-36, -25}, {-48, -14}, {-51, 4}, {-47, 20}, {-36, 27},
                        {-20, 23}};
            case 6: // Dog Bone
                return new float[][]{
                        {0, 13}, {18, 18}, {36, 27}, {50, 18}, {54, -2}, {44, -22}, {24, -22},
                        {0, -13}, {-24, -22}, {-44, -22}, {-54, -2}, {-50, 18}, {-36, 27}, {-18, 18}};
            case 7: { // Clover
                int n = 24;
                float[][] p = new float[n][];
                for (int i = 0; i < n; i++) {
                    double t = 2 * Math.PI * i / n + Math.PI / 2;
                    double r = 1 + 0.26 * Math.cos(3 * (t - Math.PI / 2));
                    p[i] = new float[]{(float) (40 * r * Math.cos(t)), (float) (26 * r * Math.sin(t))};
                }
                return p;
            }
            case 8: // Speedway (D-shaped)
                return new float[][]{
                        {-36, 30}, {-12, 31}, {12, 31}, {36, 30}, {50, 18}, {54, 0}, {47, -18},
                        {28, -30}, {0, -33}, {-28, -30}, {-47, -18}, {-54, 0}, {-50, 18}};
            case 9: // Heart
                return new float[][]{
                        {0, 33}, {16, 29}, {32, 22}, {46, 10}, {50, -7}, {41, -23}, {26, -29},
                        {13, -24}, {5, -15}, {-5, -15}, {-13, -24}, {-26, -29}, {-41, -23}, {-50, -7},
                        {-46, 10}, {-32, 22}, {-16, 29}};
        }
    }

    private static float[][] ellipse(float a, float b, int n) {
        float[][] p = new float[n][];
        for (int i = 0; i < n; i++) {
            double t = 2 * Math.PI * i / n + Math.PI / 2;
            p[i] = new float[]{(float) (a * Math.cos(t)), (float) (b * Math.sin(t))};
        }
        return p;
    }

    // ------------------------------------------------------------------ construction

    private static float cr(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t, t3 = t2 * t;
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2
                + (-p0 + 3 * p1 - 3 * p2 + p3) * t3);
    }

    private void build(float[][] control) {
        int m = control.length;
        int fineN = m * 200;
        float[] fx = new float[fineN + 1], fz = new float[fineN + 1], fc = new float[fineN + 1];
        for (int i = 0; i <= fineN; i++) {
            float u = (float) i / fineN * m;
            int seg = Math.min((int) u, m - 1);
            float t = u - seg;
            float[] a = control[(seg - 1 + m) % m], b = control[seg],
                    c = control[(seg + 1) % m], d = control[(seg + 2) % m];
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
        }
        computeTangents();
    }

    private void computeTangents() {
        for (int i = 0; i < N; i++) {
            cum[i] = i * ds;
            int a = (i - 1 + N) % N, b = (i + 1) % N;
            float dx = px[b] - px[a], dz = pz[b] - pz[a];
            float l = (float) Math.sqrt(dx * dx + dz * dz);
            tx[i] = dx / l;
            tz[i] = dz / l;
        }
    }

    /**
     * Puts the start line on the southern (spectator) side, offset to one side so the
     * gate is not directly in front of the drivers' platform and the grid lines up in
     * front of them.
     */
    private void chooseStart() {
        int best = -1;
        float bestScore = Float.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            if (pz[i] < 12f || Math.abs(tx[i]) < 0.8f) continue;
            float targetX = 16f * Math.signum(tx[i]);
            float score = Math.abs(px[i] - targetX) - pz[i] * 0.5f;
            if (score < bestScore) { bestScore = score; best = i; }
        }
        if (best <= 0) return;
        float[] nx = new float[N], nz = new float[N];
        for (int i = 0; i < N; i++) {
            nx[i] = px[(i + best) % N];
            nz[i] = pz[(i + best) % N];
        }
        System.arraycopy(nx, 0, px, 0, N);
        System.arraycopy(nz, 0, pz, 0, N);
        computeTangents();
    }

    /** Left-hand normal (relative to direction of travel). */
    float nx(int i) { return tz[i]; }
    float nz(int i) { return -tx[i]; }

    private void placeBuoys() {
        float spacing = 9f;
        int step = Math.max(1, Math.round(spacing / ds));
        int gate = Math.round(4f / ds);
        int sep = Math.round(40f / ds);
        for (int i = step; i < N - gate; i += step) {
            for (int side = 0; side < 2; side++) {
                float s = side == 0 ? 1f : -1f;
                float bx = px[i] + nx(i) * HALF_WIDTH * s;
                float bz = pz[i] + nz(i) * HALF_WIDTH * s;
                if (!World.isWater(bx, bz, hasIsland)) continue;
                boolean ok = true;
                for (float[] b : buoys) {
                    float dx = b[0] - bx, dz = b[1] - bz;
                    if (dx * dx + dz * dz < 5.5f * 5.5f) { ok = false; break; }
                }
                // keep buoys out of the course: inside of tight corners, and where the
                // course passes close by or crosses itself
                for (int k = 0; ok && k < N; k++) {
                    int gap = Math.abs(k - i);
                    gap = Math.min(gap, N - gap);
                    float dx = px[k] - bx, dz = pz[k] - bz;
                    float d2 = dx * dx + dz * dz;
                    float lim = gap > sep ? HALF_WIDTH + 1.5f : HALF_WIDTH - 1f;
                    if (d2 < lim * lim) ok = false;
                }
                if (ok) buoys.add(new float[]{bx, bz, side});
            }
        }
    }

    // ------------------------------------------------------------------ queries

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
