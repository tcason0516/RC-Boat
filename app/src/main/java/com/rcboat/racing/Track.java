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
            "Serpentine", "Dog Bone", "Clover", "Speedway", "Heart",
            "IMPBA 1/3 Mile Oval", "IMPBA 1/4 Mile Oval", "IMPBA 1/5 Mile Electric",
            "Offshore Course A", "Offshore Course B", "Offshore Course C"
    };
    /** First of the official IMPBA courses (Technical Standards, section V). */
    static final int FIRST_IMPBA = 10;
    /** The three Offshore Courses (Large Scale Gas, V-C-6). */
    static final int OFFSHORE_A = 13, OFFSHORE_B = 14, OFFSHORE_C = 15;
    /** The 1/3-mile oval: its 330 ft front chute doubles as the 1/16-mile straightaway. */
    static final int OVAL_THIRD = 10;

    // Buoy types (float[2] of each buoy)
    static final int BUOY_LEFT = 0, BUOY_RIGHT = 1, BUOY_TURN = 2, BUOY_PLAIN = 3;

    private static final float FT = 0.3048f;
    /**
     * IMPBA approved ovals: straight (chute) length and turn radius in feet, and whether
     * entrance buoys are used (1/3-mile oval only). Lengths are from the rule book.
     */
    private static final float[][] IMPBA = {
            {330.05f, 35f, 1},   // 1/3-mile oval: 880 ft around
            {282.87f, 15f, 0},   // 1/4-mile oval: 660 ft around
            {170f, 30f, 0},      // 1/5-mile electric oval: 528.5 ft around
    };
    /** Front chute (buoys 6 to 1) distance from shore: the rules allow 50 to 150 ft. */
    private static final float FRONT_CHUTE_FT = 75f;
    /** How far outside the buoy line the racing line runs. */
    private static final float IMPBA_LINE_OFFSET = 3.8f;
    static final int COUNT = NAMES.length;

    private static final Track[][] CACHE = new Track[COUNT][2];

    static synchronized Track get(int id) {
        return get(id, false);
    }

    /** radiusBuoys: add the optional unnumbered radius buoys to an IMPBA oval. */
    static synchronized Track get(int id, boolean radiusBuoys) {
        int v = radiusBuoys && id >= FIRST_IMPBA && id < OFFSHORE_A ? 1 : 0;
        if (CACHE[id][v] == null) CACHE[id][v] = new Track(id, v == 1);
        return CACHE[id][v];
    }

    final int id;
    final String name;
    final boolean hasIsland;     // the layout leaves room for the central island
    final boolean crosses;       // the layout crosses itself (figure eight)
    final boolean impba;         // an official IMPBA course: buoys only mark the inside of the turns
    final boolean oval;          // one of the IMPBA approved ovals
    final boolean offshore;      // one of the Offshore Courses
    final boolean radiusBuoys;
    /** Ovals: the buoy lines through buoys 1 and 6 (x), where the timing beams cross, and
     *  the z of the front chute's buoy line and of the shore end / far end of the beams. */
    float trapWest, trapEast, frontBuoyZ, beamNearZ, beamFarZ;
    /** Front-straight length in feet (ovals), e.g. 330 ft on the 1/3-mile oval. */
    float chuteFt;
    /** The shore noise meter: between buoy 6 and the start line, 25 ft back, 4-6 ft up. */
    float meterX, meterZ;
    /** Which side the turn buoys are on: +1 left (inside of left turns), -1 right. */
    float insideSign = 1f;
    /** AI lane limits (metres from the centre line, positive = left). */
    float laneMin = -(HALF_WIDTH - 2.2f), laneMax = HALF_WIDTH - 2.2f;
    /** Official course figures for the menu, e.g. "880 ft around, 35 ft turns". */
    String info = "";
    /** Where the floating start clock stands (IMPBA ovals: inside the course, mid-straight). */
    float clockX, clockZ;

    final float[] px = new float[N], pz = new float[N];
    final float[] tx = new float[N], tz = new float[N];   // unit tangent
    final float[] cum = new float[N];                    // arc length at sample
    float length;
    float ds;
    float minX, maxX, minZ, maxZ;

    /**
     * Buoys: x, z, type (BUOY_*), number (IMPBA turn buoys 1-6, else 0), course sample
     * index level with the buoy, and its offset from the centre line (positive = left).
     */
    final ArrayList<float[]> buoys = new ArrayList<>();

    private Track(int id, boolean radiusBuoys) {
        this.id = id;
        this.name = NAMES[id];
        this.impba = id >= FIRST_IMPBA;
        this.oval = impba && id < OFFSHORE_A;
        this.offshore = id >= OFFSHORE_A;
        this.radiusBuoys = radiusBuoys;
        this.hasIsland = !impba && id != 3 && id != 6 && id != 7 && id != 9;
        this.crosses = id == 3;
        if (oval) {
            buildOval(IMPBA[id - FIRST_IMPBA]);
        } else if (offshore) {
            buildOffshore(id);
        } else {
            build(controlPoints(id));
            chooseStart();
            placeBuoys();
        }
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

    /**
     * An IMPBA oval laid out parallel to the south shore and run clockwise: right turns
     * only. Seen from the drivers' stand, boats cross the front chute right to left; buoy 1
     * is the first turn buoy left of the start / finish line and the numbers run with the
     * course to buoy 6, right of the line (Technical Standards V-C). Boats keep the buoys
     * on their right.
     */
    private void buildOval(float[] spec) {
        float L = spec[0] * FT, R = spec[1] * FT;
        boolean entrance = spec[2] > 0;
        float shore = World.southShoreZ();
        float zFront = shore - FRONT_CHUTE_FT * FT;   // buoy line of the front chute
        float cz = zFront - R;                         // centre of the course
        float rc = R + IMPBA_LINE_OFFSET;              // racing line radius in the turns
        info = Math.round(2 * spec[0] + 2 * Math.PI * spec[1]) + " ft around, "
                + Math.round(spec[1]) + " ft turns";

        // centre line: start in the middle of the front chute heading west, then the
        // west turn, the back chute heading east, the east turn and back to the start
        int fine = 8000;
        float total = 2 * L + 2 * (float) Math.PI * rc;
        float[] fx = new float[fine + 1], fz = new float[fine + 1], fc = new float[fine + 1];
        for (int i = 0; i <= fine; i++) {
            float s = total * i / fine;
            float x, z;
            if (s < L / 2) { x = -s; z = cz + rc; }
            else if ((s -= L / 2) < Math.PI * rc) {
                double a = s / rc;   // west turn, from south round to north
                x = -L / 2 - rc * (float) Math.sin(a); z = cz + rc * (float) Math.cos(a);
            } else if ((s -= (float) Math.PI * rc) < L) { x = -L / 2 + s; z = cz - rc; }
            else if ((s -= L) < Math.PI * rc) {
                double a = s / rc;   // east turn, from north round to south
                x = L / 2 + rc * (float) Math.sin(a); z = cz - rc * (float) Math.cos(a);
            } else { s -= (float) Math.PI * rc; x = L / 2 - s; z = cz + rc; }
            fx[i] = x; fz[i] = z;
            fc[i] = total * i / fine;
        }
        resample(fx, fz, fc, fine);

        // the six numbered turn buoys
        float[][] turn = {
                {-L / 2, cz + R}, {-L / 2 - R, cz}, {-L / 2, cz - R},
                {L / 2, cz - R}, {L / 2 + R, cz}, {L / 2, cz + R}};
        for (int k = 0; k < 6; k++) addBuoy(turn[k][0], turn[k][1], BUOY_TURN, k + 1);
        // unnumbered: start / finish buoy, and entrance buoys on the 1/3-mile course
        addBuoy(0, cz + R, BUOY_PLAIN, 0);
        if (entrance) {
            addBuoy(-L / 4, cz + R, BUOY_PLAIN, 0);    // halfway from start / finish to buoy 1
            addBuoy(L / 4, cz - R, BUOY_PLAIN, 0);     // halfway from the centre to buoy 4
        }
        if (radiusBuoys) {
            // optional, unnumbered: between buoys 1&2, 2&3, 4&5 and 5&6
            float k = (float) Math.sqrt(0.5);
            for (int sx = -1; sx <= 1; sx += 2)
                for (int sz = -1; sz <= 1; sz += 2)
                    addBuoy(sx * (L / 2 + R * k), cz + sz * R * k, BUOY_PLAIN, 0);
        }
        clockX = 0;
        clockZ = cz;
        chuteFt = spec[0];
        // timing beams run 18 in (+/- 1 in) to the right of the buoy lines A-C and B-D
        float beam = 18f * 0.0254f;
        trapWest = -L / 2 + beam;
        trapEast = L / 2 + beam;
        frontBuoyZ = cz + R;
        beamNearZ = cz + rc + 9f;
        beamFarZ = cz - rc - 7f;
        meterX = L / 4;
        meterZ = shore + 25f * FT;
        insideSign = -1f;
        laneMin = -(IMPBA_LINE_OFFSET - 2.0f);
        laneMax = 3.5f;
    }

    /**
     * The Offshore Courses as drawn on page I-7, scaled to the lake (the rule book gives no
     * sizes): turns are circles traversed clockwise (right turns) except the dip in Course C,
     * a left turn. Diagram coordinates are in points, y downward = towards the shore.
     * Each circle: x, y, racing-line radius (m), side (+1 right turn, -1 left turn).
     */
    private void buildOffshore(int id) {
        float f = 0.44f;   // metres per diagram point
        float[][] circles, marks;
        float r3 = 25f * f + IMPBA_LINE_OFFSET;   // three-buoy turns, like an oval's end
        float r1 = 4.2f;                          // round a single corner buoy
        if (id == OFFSHORE_A) {
            marks = new float[][]{{420, 199}, {394, 224}, {420, 246}, {420, 123},
                    {596, 199}, {622, 224}, {596, 246}};
            circles = new float[][]{{420, 222, r3, 1}, {420, 123, r1, 1}, {596, 222, r3, 1}};
        } else if (id == OFFSHORE_B) {
            marks = new float[][]{{248, 426}, {221, 451}, {248, 473}, {222, 350}, {451, 350},
                    {426, 426}, {452, 451}, {426, 473}};
            circles = new float[][]{{248, 450, r3, 1}, {222, 350, r1, 1}, {451, 350, r1, 1},
                    {426, 450, r3, 1}};
        } else {
            marks = new float[][]{{588, 426}, {562, 451}, {588, 473}, {562, 350}, {670, 382},
                    {786, 350}, {760, 426}, {786, 451}, {760, 473}};
            // the dip: a left turn on a circle above buoy (670, 382), passing 3.8 m over it
            float rd = 9f;
            float cy = 382 - (IMPBA_LINE_OFFSET + rd) / f;
            circles = new float[][]{{588, 450, r3, 1}, {562, 350, r1, 1}, {670, cy, rd, -1},
                    {786, 350, r1, 1}, {760, 450, r3, 1}};
        }
        float minX = 1e9f, maxX = -1e9f, maxY = -1e9f;
        for (float[] m : marks) { minX = Math.min(minX, m[0]); maxX = Math.max(maxX, m[0]); maxY = Math.max(maxY, m[1]); }
        float shore = World.southShoreZ();
        float ox = (minX + maxX) / 2f, oy = maxY;
        float zFront = shore - FRONT_CHUTE_FT * FT;
        for (float[] c : circles) { c[0] = (c[0] - ox) * f; c[1] = zFront + (c[1] - oy) * f; }
        for (float[] m : marks) { m[0] = (m[0] - ox) * f; m[1] = zFront + (m[1] - oy) * f; }

        // belt round the circles: tangent lines and arcs, sampled finely
        int n = circles.length;
        float[] dirX = new float[n], dirZ = new float[n];
        for (int i = 0; i < n; i++) {
            float[] a = circles[i], b = circles[(i + 1) % n];
            float dx = b[0] - a[0], dz = b[1] - a[1];
            float L = (float) Math.sqrt(dx * dx + dz * dz);
            float k = b[3] * b[2] - a[3] * a[2];
            double phi = Math.atan2(dz, dx) - Math.asin(k / L);
            dirX[i] = (float) Math.cos(phi); dirZ[i] = (float) Math.sin(phi);
        }
        ArrayList<float[]> pts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            float[] c = circles[i];
            int prev = (i - 1 + n) % n;
            // arrive on circle i along the tangent from prev, leave along dir i
            float[] pin = touch(c, dirX[prev], dirZ[prev]);
            float[] pout = touch(c, dirX[i], dirZ[i]);
            double ta = Math.atan2(pin[1] - c[1], pin[0] - c[0]);
            double tb = Math.atan2(pout[1] - c[1], pout[0] - c[0]);
            double sweep = c[3] > 0 ? tb - ta : ta - tb;
            while (sweep < 0) sweep += 2 * Math.PI;
            while (sweep >= 2 * Math.PI) sweep -= 2 * Math.PI;
            int steps = Math.max(2, (int) (sweep * c[2] / 0.15));
            for (int k = 0; k < steps; k++) {
                double t = ta + c[3] * sweep * k / steps;
                pts.add(new float[]{c[0] + c[2] * (float) Math.cos(t), c[1] + c[2] * (float) Math.sin(t)});
            }
            float[] next = touch(circles[(i + 1) % n], dirX[i], dirZ[i]);
            float lx = next[0] - pout[0], lz = next[1] - pout[1];
            int ls = Math.max(1, (int) (Math.sqrt(lx * lx + lz * lz) / 0.15));
            for (int k = 0; k < ls; k++) pts.add(new float[]{pout[0] + lx * k / ls, pout[1] + lz * k / ls});
        }
        // start / finish: the middle of the front chute (heading west), in front of the drivers
        int start = 0;
        float best = Float.MAX_VALUE;
        for (int i = 0; i < pts.size(); i++) {
            float[] p = pts.get(i), q = pts.get((i + 1) % pts.size());
            if (p[1] < zFront - 1f || q[0] >= p[0]) continue;
            float sc = Math.abs(p[0]);
            if (sc < best) { best = sc; start = i; }
        }
        int m = pts.size();
        float[] fx = new float[m + 1], fz = new float[m + 1], fc = new float[m + 1];
        for (int i = 0; i <= m; i++) {
            float[] p = pts.get((start + i) % m);
            fx[i] = p[0]; fz[i] = p[1];
            if (i > 0) fc[i] = fc[i - 1] + (float) Math.hypot(fx[i] - fx[i - 1], fz[i] - fz[i - 1]);
        }
        resample(fx, fz, fc, m);

        // buoys, numbered in the direction of the course from the first one after the start
        ArrayList<float[]> order = new ArrayList<>();
        for (float[] mk : marks) order.add(new float[]{mk[0], mk[1], nearestGlobal(mk[0], mk[1])});
        java.util.Collections.sort(order, (a, b) -> Float.compare(a[2], b[2]));
        for (int i = 0; i < order.size(); i++) addBuoy(order.get(i)[0], order.get(i)[1], BUOY_TURN, i + 1);
        addBuoy(0, zFront, BUOY_PLAIN, 0);     // unnumbered start / finish buoy
        info = "right turns" + (id == OFFSHORE_C ? ", one left kink" : " only");
        insideSign = -1f;
        laneMin = -(IMPBA_LINE_OFFSET - 2.0f);
        laneMax = 3.5f;
        // start clock on the infield, behind the start buoy
        clockX = 0;
        clockZ = zFront - 14f;
        meterX = 18f;
        meterZ = shore + 25f * FT;
    }

    /** Point where a line with direction (dx, dz) touches circle c (side c[3]). */
    private static float[] touch(float[] c, float dx, float dz) {
        // right-hand normal of the direction of travel is (-dz, dx); the centre lies at
        // p + side * r * normal, so p = c - side * r * normal
        return new float[]{c[0] - c[3] * c[2] * -dz, c[1] - c[3] * c[2] * dx};
    }

    private void addBuoy(float x, float z, int type, int number) {
        int idx = nearestGlobal(x, z);
        float lat = (x - px[idx]) * nx(idx) + (z - pz[idx]) * nz(idx);
        buoys.add(new float[]{x, z, type, number, idx, lat});
    }

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
        resample(fx, fz, fc, fineN);
    }

    /** Resamples a fine polyline (with cumulative lengths) into N evenly spaced points. */
    private void resample(float[] fx, float[] fz, float[] fc, int fineN) {
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
                if (ok) {
                    float lat = HALF_WIDTH * s;
                    buoys.add(new float[]{bx, bz, side, 0, i, lat});
                }
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
