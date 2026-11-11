package com.rcboat.racing;

import java.util.Random;

/** Builds all the meshes of the scene. Must be called on the GL thread. */
final class Scenery {
    static final float[] LIGHT = normalize(-0.42f, 0.70f, 0.58f);
    static final float[] FOG = {0.76f, 0.85f, 0.93f};

    Mesh sky, terrain, water, props, buoyRed, buoyYellow, marker;
    Mesh[] boats;

    Scenery(Game game) {
        sky = buildSky();
        terrain = buildTerrain();
        water = buildWater();
        props = buildProps(game);
        buoyRed = buildBuoy(0.95f, 0.18f, 0.12f);
        buoyYellow = buildBuoy(1.0f, 0.78f, 0.08f);
        marker = buildMarker();
        boats = new Mesh[Game.COLORS.length];
        for (int i = 0; i < boats.length; i++) boats[i] = buildBoat(Game.COLORS[i]);
    }

    static float[] normalize(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    // ------------------------------------------------------------------ sky

    private Mesh buildSky() {
        MeshBuilder mb = new MeshBuilder();
        int seg = 32, rings = 16;
        float rad = 480f;
        for (int j = 0; j < rings; j++) {
            double t0 = Math.PI * j / rings, t1 = Math.PI * (j + 1) / rings;
            for (int i = 0; i < seg; i++) {
                double p0 = 2 * Math.PI * i / seg, p1 = 2 * Math.PI * (i + 1) / seg;
                double[][] pts = {{t0, p0}, {t0, p1}, {t1, p1}, {t0, p0}, {t1, p1}, {t1, p0}};
                for (double[] tp : pts) {
                    float x = (float) (Math.sin(tp[0]) * Math.cos(tp[1]));
                    float y = (float) Math.cos(tp[0]);
                    float z = (float) (Math.sin(tp[0]) * Math.sin(tp[1]));
                    float[] c = skyColor(x, y, z);
                    mb.vertex(x * rad, y * rad, z * rad, 0, 1, 0, c[0], c[1], c[2]);
                }
            }
        }
        return mb.build();
    }

    private static float[] skyColor(float x, float y, float z) {
        float up = Math.max(0f, y);
        float t = (float) Math.pow(up, 0.55);
        float r = FOG[0] + (0.26f - FOG[0]) * t;
        float g = FOG[1] + (0.52f - FOG[1]) * t;
        float b = FOG[2] + (0.88f - FOG[2]) * t;
        float sd = Math.max(0f, x * LIGHT[0] + y * LIGHT[1] + z * LIGHT[2]);
        float glow = (float) (Math.pow(sd, 400) * 1.5 + Math.pow(sd, 12) * 0.25);
        if (y < 0) { r = FOG[0]; g = FOG[1]; b = FOG[2]; }
        return new float[]{Math.min(1f, r + glow), Math.min(1f, g + glow * 0.95f), Math.min(1f, b + glow * 0.8f)};
    }

    // ------------------------------------------------------------------ terrain

    private static float noise(float x, float z) {
        return (float) (Math.sin(x * 0.31 + Math.cos(z * 0.17) * 2.0) * 0.5
                + Math.sin(z * 0.27 - x * 0.11) * 0.3 + Math.sin(x * 1.3 + z * 1.1) * 0.2);
    }

    private static float[] groundColor(float x, float z, float h) {
        float q = World.lakeQ(x, z);
        float ix = (x - World.ISLAND_X) / World.ISLAND_RX, iz = (z - World.ISLAND_Z) / World.ISLAND_RZ;
        boolean nearWater = q < 1.2f || (ix * ix + iz * iz) < 1f;
        float n = noise(x, z);
        float[] sand = {0.84f, 0.77f, 0.57f};
        float[] grass = {0.32f + 0.05f * n, 0.55f + 0.07f * n, 0.22f + 0.03f * n};
        if (h > 12f) {
            float t = Math.min(1f, (h - 12f) / 8f);
            grass[0] += (0.46f - grass[0]) * t;
            grass[1] += (0.46f - grass[1]) * t;
            grass[2] += (0.40f - grass[2]) * t;
        }
        if (h < -0.05f) {
            float t = Math.min(1f, -h / 2.5f);
            return new float[]{0.70f + (0.20f - 0.70f) * t, 0.64f + (0.30f - 0.64f) * t, 0.46f + (0.27f - 0.46f) * t};
        }
        if (!nearWater) return grass;
        if (h < 0.6f) return sand;
        float t = Math.min(1f, (h - 0.6f) / 0.6f);
        return new float[]{sand[0] + (grass[0] - sand[0]) * t, sand[1] + (grass[1] - sand[1]) * t,
                sand[2] + (grass[2] - sand[2]) * t};
    }

    private Mesh buildTerrain() {
        int cells = 180;
        int n = cells + 1;
        float size = World.TERRAIN_HALF * 2;
        float step = size / cells;
        float[] data = new float[n * n * Mesh.FLOATS];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                float x = -World.TERRAIN_HALF + i * step;
                float z = -World.TERRAIN_HALF + j * step;
                float h = World.height(x, z);
                float e = step * 0.5f;
                float gx = (World.height(x + e, z) - World.height(x - e, z)) / (2 * e);
                float gz = (World.height(x, z + e) - World.height(x, z - e)) / (2 * e);
                float[] nn = normalize(-gx, 1f, -gz);
                float[] c = groundColor(x, z, h);
                int o = (j * n + i) * Mesh.FLOATS;
                data[o] = x; data[o + 1] = h; data[o + 2] = z;
                data[o + 3] = nn[0]; data[o + 4] = nn[1]; data[o + 5] = nn[2];
                data[o + 6] = c[0]; data[o + 7] = c[1]; data[o + 8] = c[2];
            }
        }
        return new Mesh(data, n * n, gridIndices(cells));
    }

    private static short[] gridIndices(int cells) {
        int n = cells + 1;
        short[] idx = new short[cells * cells * 6];
        int k = 0;
        for (int j = 0; j < cells; j++) {
            for (int i = 0; i < cells; i++) {
                int a = j * n + i, b = a + 1, c = a + n, d = c + 1;
                idx[k++] = (short) a; idx[k++] = (short) c; idx[k++] = (short) b;
                idx[k++] = (short) b; idx[k++] = (short) c; idx[k++] = (short) d;
            }
        }
        return idx;
    }

    private Mesh buildWater() {
        int cells = 144;
        int n = cells + 1;
        float size = World.TERRAIN_HALF * 2;
        float step = size / cells;
        float[] data = new float[n * n * Mesh.FLOATS];
        for (int j = 0; j < n; j++) {
            for (int i = 0; i < n; i++) {
                int o = (j * n + i) * Mesh.FLOATS;
                data[o] = -World.TERRAIN_HALF + i * step;
                data[o + 2] = -World.TERRAIN_HALF + j * step;
                data[o + 4] = 1f;
            }
        }
        return new Mesh(data, n * n, gridIndices(cells));
    }

    // ------------------------------------------------------------------ props

    private Mesh buildProps(Game game) {
        MeshBuilder mb = new MeshBuilder();
        Random rnd = new Random(42);
        float shoreZ = game.shoreZ;

        // trees around the lake
        int placed = 0;
        for (int tries = 0; tries < 4000 && placed < 260; tries++) {
            float x = (rnd.nextFloat() * 2 - 1) * 165f;
            float z = (rnd.nextFloat() * 2 - 1) * 165f;
            float h = World.height(x, z);
            if (h < 1.0f || h > 22f) continue;
            if (World.lakeQ(x, z) < 1.1f) continue;
            if (Math.abs(x) < 9f && z > shoreZ - 4f && z < shoreZ + 18f) continue;
            tree(mb, rnd, x, h, z, 0.8f + rnd.nextFloat() * 0.9f);
            placed++;
        }
        // island trees
        float[][] islandTrees = {{-7, -3}, {-9, 0}, {6, -4}, {8, 0}, {-3, 2}, {4, 2.5f}};
        for (float[] t : islandTrees) {
            float h = World.height(t[0], t[1]);
            if (h > 0.3f) tree(mb, rnd, t[0], h, t[1], 0.6f + rnd.nextFloat() * 0.4f);
        }
        // lighthouse on the island
        float lh = World.height(World.ISLAND_X, World.ISLAND_Z) - 0.2f;
        float lx = World.ISLAND_X, lz = World.ISLAND_Z;
        mb.cylinder(lx, lh, lz, 1.2f, 1.2f, 0.6f, 14, 0.55f, 0.55f, 0.55f, true);
        for (int i = 0; i < 6; i++) {
            float y0 = lh + 0.6f + i * 0.9f;
            float r0 = 1.0f - i * 0.06f, r1 = 1.0f - (i + 1) * 0.06f;
            boolean red = (i % 2) == 1;
            mb.cylinder(lx, y0, lz, r0, r1, 0.9f, 14, red ? 0.85f : 0.96f, red ? 0.15f : 0.96f,
                    red ? 0.12f : 0.94f, false);
        }
        float top = lh + 0.6f + 6 * 0.9f;
        mb.cylinder(lx, top, lz, 0.85f, 0.85f, 0.12f, 14, 0.2f, 0.2f, 0.2f, true);
        mb.cylinder(lx, top + 0.12f, lz, 0.5f, 0.5f, 0.7f, 10, 1.0f, 0.95f, 0.6f, false);
        mb.cylinder(lx, top + 0.82f, lz, 0.7f, 0f, 0.7f, 12, 0.8f, 0.12f, 0.1f, true);

        // rocks along the shore
        for (int i = 0; i < 40; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float x = (float) Math.cos(a) * World.LAKE_A, z = (float) Math.sin(a) * World.LAKE_B;
            // walk outwards to the waterline
            for (int k = 0; k < 200 && World.height(x, z) < 0.1f; k++) { x *= 1.01f; z *= 1.01f; }
            for (int k = 0; k < 200 && World.height(x, z) > 0.1f; k++) { x *= 0.995f; z *= 0.995f; }
            if (Math.abs(x) < 8f && z > 0) continue;
            float s = 0.4f + rnd.nextFloat() * 0.9f;
            float g = 0.45f + rnd.nextFloat() * 0.15f;
            mb.sphere(x, World.height(x, z), z, s * 1.3f, s * 0.7f, s, 7, 4, g, g * 0.98f, g * 0.92f);
        }

        // spectator dock
        float dy = game.dockY;
        float zFar = shoreZ - 7f, zNear = shoreZ + 4f;
        mb.box(0, dy - 0.08f, (zFar + zNear) / 2f, 2.6f, 0.16f, zNear - zFar, 0, 0.55f, 0.40f, 0.26f);
        for (float z = zFar + 0.3f; z <= zNear; z += 2.4f) {
            for (int s = -1; s <= 1; s += 2) {
                mb.cylinder(s * 1.2f, -2.0f, z, 0.11f, 0.11f, dy + 2.0f, 6, 0.42f, 0.30f, 0.2f, false);
            }
        }
        for (float z = zFar + 0.3f; z <= zNear; z += 1.2f) {
            for (int s = -1; s <= 1; s += 2) {
                mb.box(s * 1.25f, dy + 0.45f, z, 0.08f, 0.9f, 0.08f, 0, 0.5f, 0.36f, 0.24f);
            }
        }
        // rails (leave the far end open so the view over the lake is clear)
        mb.box(-1.25f, dy + 0.9f, (zFar + zNear) / 2f, 0.07f, 0.07f, zNear - zFar, 0, 0.5f, 0.36f, 0.24f);
        mb.box(1.25f, dy + 0.9f, (zFar + zNear) / 2f, 0.07f, 0.07f, zNear - zFar, 0, 0.5f, 0.36f, 0.24f);

        // boathouses on the shore
        house(mb, 30f, shoreZ - 3f, 0.4f);
        house(mb, -38f, shoreZ - 6f, -0.5f);

        // start / finish gate
        Track t = game.track;
        float nx = t.nx(0), nz = t.nz(0);
        float span = Track.HALF_WIDTH + 1.0f;
        float rot = (float) Math.atan2(-nz, nx);
        for (int s = -1; s <= 1; s += 2) {
            float px = t.px[0] + nx * span * s, pz = t.pz[0] + nz * span * s;
            mb.box(px, 0.05f, pz, 0.9f, 0.4f, 0.9f, rot, 0.95f, 0.95f, 0.95f);
            mb.cylinder(px, 0f, pz, 0.12f, 0.12f, 3.8f, 8, 0.85f, 0.85f, 0.88f, true);
        }
        int squares = (int) (span * 2 / 0.5f);
        float sq = span * 2 / squares;
        for (int i = 0; i < squares; i++) {
            float off = -span + (i + 0.5f) * sq;
            float cx = t.px[0] + nx * off, cz = t.pz[0] + nz * off;
            for (int row = 0; row < 2; row++) {
                boolean black = ((i + row) % 2) == 0;
                float c = black ? 0.05f : 0.97f;
                mb.box(cx, 3.15f + row * sq, cz, sq, sq, 0.06f, rot, c, c, c);
            }
        }
        return mb.build();
    }

    private static void tree(MeshBuilder mb, Random rnd, float x, float h, float z, float s) {
        mb.cylinder(x, h - 0.2f, z, 0.22f * s, 0.16f * s, 1.6f * s, 6, 0.40f, 0.28f, 0.18f, false);
        float g = 0.75f + rnd.nextFloat() * 0.35f;
        if (rnd.nextFloat() < 0.6f) {
            // conifer
            mb.cylinder(x, h + 1.0f * s, z, 1.7f * s, 0f, 3.0f * s, 7, 0.13f * g, 0.38f * g, 0.18f * g, true);
            mb.cylinder(x, h + 2.4f * s, z, 1.25f * s, 0f, 2.6f * s, 7, 0.15f * g, 0.43f * g, 0.2f * g, true);
        } else {
            // broadleaf
            mb.sphere(x, h + 2.4f * s, z, 1.6f * s, 1.4f * s, 1.6f * s, 8, 5, 0.25f * g, 0.52f * g, 0.18f * g);
            mb.sphere(x + 0.6f * s, h + 3.1f * s, z - 0.3f * s, 1.0f * s, 0.9f * s, 1.0f * s, 7, 4,
                    0.28f * g, 0.56f * g, 0.2f * g);
        }
    }

    private static void house(MeshBuilder mb, float x, float z, float rot) {
        // find ground just above the waterline nearby
        float h = World.height(x, z);
        for (int k = 0; k < 100 && h < 0.5f; k++) {
            z += 0.3f;
            h = World.height(x, z);
        }
        float w = 5f, d = 4f, wall = 2.6f;
        mb.box(x, h + wall / 2f - 0.3f, z, w, wall, d, rot, 0.86f, 0.80f, 0.70f);
        mb.box(x, h + 0.9f, z - (float) Math.cos(rot) * (d / 2f + 0.01f), 1.4f, 1.8f, 0.05f, rot, 0.35f, 0.22f, 0.15f);
        // roof prism
        float cs = (float) Math.cos(rot), sn = (float) Math.sin(rot);
        float y0 = h + wall - 0.3f, y1 = y0 + 1.6f;
        float hw = w / 2f + 0.3f, hd = d / 2f + 0.3f;
        float[][] lp = {{-hw, y0, -hd}, {hw, y0, -hd}, {hw, y0, hd}, {-hw, y0, hd}, {-hw, y1, 0}, {hw, y1, 0}};
        float[][] p = new float[6][];
        for (int i = 0; i < 6; i++) {
            float lx = lp[i][0], lz = lp[i][2];
            p[i] = new float[]{x + lx * cs + lz * sn, lp[i][1], z - lx * sn + lz * cs};
        }
        float[] ref = {x, y0 + 0.4f, z};
        float[] roof = {0.62f, 0.2f, 0.16f};
        mb.quad(p[0], p[1], p[5], p[4], ref, roof);
        mb.quad(p[3], p[2], p[5], p[4], ref, roof);
        float[] gable = {0.8f, 0.74f, 0.64f};
        mb.tri(p[0], p[3], p[4], ref, gable);
        mb.tri(p[1], p[2], p[5], ref, gable);
    }

    // ------------------------------------------------------------------ buoys, boats

    private static Mesh buildBuoy(float r, float g, float b) {
        MeshBuilder mb = new MeshBuilder();
        mb.cylinder(0, -0.3f, 0, 0.36f, 0.32f, 0.65f, 12, r, g, b, true);
        mb.cylinder(0, 0.35f, 0, 0.32f, 0.3f, 0.12f, 12, 0.97f, 0.97f, 0.97f, false);
        mb.cylinder(0, 0.47f, 0, 0.3f, 0f, 0.5f, 12, r, g, b, true);
        return mb.build();
    }

    private static Mesh buildMarker() {
        MeshBuilder mb = new MeshBuilder();
        mb.cylinder(0, 0, 0, 0f, 0.4f, 0.7f, 10, 0.35f, 1.0f, 0.35f, true);
        return mb.build();
    }

    /** RC speed boat, ~1.3 m long. Local forward is -z. */
    private static Mesh buildBoat(float[] col) {
        MeshBuilder mb = new MeshBuilder();
        float[] st = {-0.70f, -0.52f, -0.32f, 0.0f, 0.35f, 0.62f};     // stations along z
        float[] hw = {0.0f, 0.13f, 0.20f, 0.25f, 0.26f, 0.25f};         // deck half widths
        float[] deckY = {0.20f, 0.18f, 0.17f, 0.16f, 0.16f, 0.16f};
        float[] chineY = {0.12f, 0.05f, 0.02f, 0.0f, 0.0f, 0.0f};
        float[] keelY = {0.12f, -0.03f, -0.08f, -0.10f, -0.10f, -0.09f};
        float[] ref = {0, 0.04f, 0.05f};
        float[] side = col;
        float[] bottom = {col[0] * 0.35f, col[1] * 0.35f, col[2] * 0.35f};
        float[] deck = {0.96f, 0.96f, 0.96f};
        float[] stripe = {0.1f, 0.1f, 0.12f};
        int n = st.length;
        for (int i = 0; i < n - 1; i++) {
            for (int s = -1; s <= 1; s += 2) {
                float[] d0 = {s * hw[i], deckY[i], st[i]}, d1 = {s * hw[i + 1], deckY[i + 1], st[i + 1]};
                float[] c0 = {s * hw[i] * 0.95f, chineY[i], st[i]}, c1 = {s * hw[i + 1] * 0.95f, chineY[i + 1], st[i + 1]};
                float[] m0 = {s * hw[i] * 0.975f, (deckY[i] + chineY[i]) * 0.5f - 0.015f, st[i]};
                float[] m1 = {s * hw[i + 1] * 0.975f, (deckY[i + 1] + chineY[i + 1]) * 0.5f - 0.015f, st[i + 1]};
                float[] m0b = {m0[0], m0[1] + 0.025f, m0[2]}, m1b = {m1[0], m1[1] + 0.025f, m1[2]};
                float[] k0 = {0, keelY[i], st[i]}, k1 = {0, keelY[i + 1], st[i + 1]};
                mb.quad(d0, d1, m1b, m0b, ref, side);
                mb.quad(m0b, m1b, m1, m0, ref, stripe);
                mb.quad(m0, m1, c1, c0, ref, side);
                mb.quad(c0, c1, k1, k0, ref, bottom);
            }
            float[] dl0 = {-hw[i], deckY[i], st[i]}, dr0 = {hw[i], deckY[i], st[i]};
            float[] dl1 = {-hw[i + 1], deckY[i + 1], st[i + 1]}, dr1 = {hw[i + 1], deckY[i + 1], st[i + 1]};
            float[] deckCol = i < 2 ? col : deck;
            mb.quad(dl0, dr0, dr1, dl1, new float[]{0, -1, 0}, deckCol);
        }
        // transom
        int l = n - 1;
        float[] tdl = {-hw[l], deckY[l], st[l]}, tdr = {hw[l], deckY[l], st[l]};
        float[] tcl = {-hw[l] * 0.95f, chineY[l], st[l]}, tcr = {hw[l] * 0.95f, chineY[l], st[l]};
        float[] tk = {0, keelY[l], st[l]};
        float[] tref = {0, 0.05f, 0f};
        mb.quad(tdl, tdr, tcr, tcl, tref, side);
        mb.tri(tcl, tcr, tk, tref, bottom);

        // canopy / cockpit
        mb.box(0, 0.215f, 0.10f, 0.30f, 0.11f, 0.46f, 0, 0.08f, 0.10f, 0.13f);
        mb.box(0, 0.28f, 0.16f, 0.26f, 0.04f, 0.32f, 0, col[0], col[1], col[2]);
        // windshield wedge
        float[] w0 = {-0.15f, 0.16f, -0.13f}, w1 = {0.15f, 0.16f, -0.13f};
        float[] w2 = {0.15f, 0.27f, -0.02f}, w3 = {-0.15f, 0.27f, -0.02f};
        mb.quad(w0, w1, w2, w3, new float[]{0, 0.1f, 0.2f}, new float[]{0.15f, 0.25f, 0.32f});
        // stern details: rudder, prop strut, antenna
        mb.box(0.12f, -0.05f, 0.66f, 0.02f, 0.16f, 0.07f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(0, -0.08f, 0.66f, 0.03f, 0.03f, 0.1f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(-0.15f, 0.36f, 0.42f, 0.012f, 0.4f, 0.012f, 0, 0.08f, 0.08f, 0.08f);
        // racing number plates
        mb.box(0, 0.165f, 0.48f, 0.3f, 0.01f, 0.14f, 0, 0.97f, 0.97f, 0.97f);
        return mb.build();
    }
}
