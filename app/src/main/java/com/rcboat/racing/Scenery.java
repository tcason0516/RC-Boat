package com.rcboat.racing;

import java.util.Random;

/** Builds all the meshes of the scene. Must be called on the GL thread. */
final class Scenery {
    static final float[] LIGHT = normalize(-0.42f, 0.70f, 0.58f);
    static final float[] FOG = {0.76f, 0.85f, 0.93f};

    Mesh sky, terrain, water, props, island, islandProps, buoyRed, buoyYellow, marker;
    Mesh[][] boats;     // [hull][color]
    Mesh[] figures;     // RC drivers, one per color

    Scenery(Game game) {
        sky = buildSky();
        terrain = buildTerrain();
        water = buildWater();
        props = buildProps(game);
        buoyRed = buildBuoy(0.95f, 0.18f, 0.12f);
        buoyYellow = buildBuoy(1.0f, 0.78f, 0.08f);
        marker = buildMarker();
        island = buildIsland();
        islandProps = buildIslandProps();
        boats = new Mesh[Boat.HULL_NAMES.length][Game.COLORS.length];
        figures = new Mesh[Game.COLORS.length];
        for (int i = 0; i < Game.COLORS.length; i++) {
            boats[Boat.V_HULL][i] = buildVHull(Game.COLORS[i]);
            boats[Boat.HYDROPLANE][i] = buildHydroplane(Game.COLORS[i]);
            boats[Boat.OUTRIGGER][i] = buildOutrigger(Game.COLORS[i]);
            figures[i] = buildFigure(Game.COLORS[i]);
        }
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
                float h = World.baseHeight(x, z);
                float e = step * 0.5f;
                float gx = (World.baseHeight(x + e, z) - World.baseHeight(x - e, z)) / (2 * e);
                float gz = (World.baseHeight(x, z + e) - World.baseHeight(x, z - e)) / (2 * e);
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
            float h = World.baseHeight(x, z);
            if (h < 1.0f || h > 22f) continue;
            if (World.lakeQ(x, z) < 1.1f) continue;
            if (Math.abs(x) < 12f && z > shoreZ - 4f && z < shoreZ + 20f) continue;
            tree(mb, rnd, x, h, z, 0.8f + rnd.nextFloat() * 0.9f);
            placed++;
        }

        // rocks along the shore
        for (int i = 0; i < 40; i++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float x = (float) Math.cos(a) * World.LAKE_A, z = (float) Math.sin(a) * World.LAKE_B;
            // walk outwards to the waterline
            for (int k = 0; k < 200 && World.baseHeight(x, z) < 0.1f; k++) { x *= 1.01f; z *= 1.01f; }
            for (int k = 0; k < 200 && World.baseHeight(x, z) > 0.1f; k++) { x *= 0.995f; z *= 0.995f; }
            if (Math.abs(x) < 10f && z > 0) continue;
            float s = 0.4f + rnd.nextFloat() * 0.9f;
            float g = 0.45f + rnd.nextFloat() * 0.15f;
            mb.sphere(x, World.baseHeight(x, z), z, s * 1.3f, s * 0.7f, s, 7, 4, g, g * 0.98f, g * 0.92f);
        }

        platform(mb, game);

        // boathouses on the shore
        house(mb, 30f, shoreZ - 3f, 0.4f);
        house(mb, -38f, shoreZ - 6f, -0.5f);
        return mb.build();
    }

    /** The 5 ft (1.52 m) drivers' platform on the south shore. */
    private static void platform(MeshBuilder mb, Game game) {
        float w = Game.PLATFORM_W, d = Game.PLATFORM_D;
        float cz = game.platformZ, top = game.deckY, ground = game.groundY;
        float front = cz - d / 2f, back = cz + d / 2f;
        float wr = 0.58f, wg = 0.43f, wb = 0.28f;      // planks
        float pr = 0.45f, pg = 0.33f, pb = 0.22f;      // posts
        // deck
        mb.box(0, top - 0.08f, cz, w, 0.16f, d, 0, wr, wg, wb);
        // support posts and braces
        for (float x = -w / 2 + 0.15f; x <= w / 2; x += (w - 0.3f) / 4f) {
            for (float z : new float[]{front + 0.15f, back - 0.15f}) {
                float base = ground - 0.6f;
                mb.box(x, (base + top - 0.16f) / 2f, z, 0.18f, top - 0.16f - base, 0.18f, 0, pr, pg, pb);
            }
            mb.box(x, ground + 0.5f, cz, 0.1f, 0.1f, d - 0.3f, 0, pr, pg, pb);
        }
        mb.box(0, ground + 0.6f, front + 0.15f, w - 0.3f, 0.1f, 0.08f, 0, pr, pg, pb);
        mb.box(0, ground + 0.6f, back - 0.15f, w - 0.3f, 0.1f, 0.08f, 0, pr, pg, pb);
        // safety railing on the front and sides
        float rail = 1.0f;
        for (float x = -w / 2 + 0.05f; x <= w / 2; x += (w - 0.1f) / 8f) {
            mb.box(x, top + rail / 2f, front + 0.05f, 0.07f, rail, 0.07f, 0, pr, pg, pb);
        }
        mb.box(0, top + rail, front + 0.05f, w, 0.08f, 0.1f, 0, 0.92f, 0.92f, 0.9f);
        mb.box(0, top + rail * 0.5f, front + 0.05f, w, 0.05f, 0.05f, 0, pr, pg, pb);
        for (int s = -1; s <= 1; s += 2) {
            float x = s * (w / 2f - 0.05f);
            for (float z = front; z <= back; z += d / 3f) {
                mb.box(x, top + rail / 2f, z, 0.07f, rail, 0.07f, 0, pr, pg, pb);
            }
            mb.box(x, top + rail, cz, 0.1f, 0.08f, d, 0, 0.92f, 0.92f, 0.9f);
        }
        // stairs at the back
        int steps = 8;
        float rise = (top - ground) / steps, tread = 0.3f;
        for (int i = 0; i < steps; i++) {
            float y = top - rise * (i + 1);
            float z = back + tread * (i + 0.5f);
            mb.box(0, y + rise / 2f - 0.02f, z, 1.3f, 0.06f, tread, 0, wr, wg, wb);
            mb.box(0, (ground + y) / 2f, z + tread / 2f - 0.04f, 1.1f, Math.max(0.05f, y - ground), 0.05f, 0,
                    pr, pg, pb);
        }
        // a sign board below the front rail
        mb.box(0, top - 0.45f, front - 0.02f, 3.2f, 0.5f, 0.04f, 0, 0.12f, 0.32f, 0.6f);
    }

    private Mesh buildIsland() {
        float x0 = World.ISLAND_X - World.ISLAND_RX - 1, x1 = World.ISLAND_X + World.ISLAND_RX + 1;
        float z0 = World.ISLAND_Z - World.ISLAND_RZ - 1, z1 = World.ISLAND_Z + World.ISLAND_RZ + 1;
        float step = 1f;
        int nx = (int) ((x1 - x0) / step) + 1, nz = (int) ((z1 - z0) / step) + 1;
        float[] data = new float[nx * nz * Mesh.FLOATS];
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                float x = x0 + i * step, z = z0 + j * step;
                float h = islandSurface(x, z);
                float e = 0.5f;
                float gx = (islandSurface(x + e, z) - islandSurface(x - e, z)) / (2 * e);
                float gz = (islandSurface(x, z + e) - islandSurface(x, z - e)) / (2 * e);
                float[] nn = normalize(-gx, 1f, -gz);
                float[] c = groundColor(x, z, h);
                int o = (j * nx + i) * Mesh.FLOATS;
                data[o] = x; data[o + 1] = h; data[o + 2] = z;
                data[o + 3] = nn[0]; data[o + 4] = nn[1]; data[o + 5] = nn[2];
                data[o + 6] = c[0]; data[o + 7] = c[1]; data[o + 8] = c[2];
            }
        }
        short[] idx = new short[(nx - 1) * (nz - 1) * 6];
        int k = 0;
        for (int j = 0; j < nz - 1; j++) {
            for (int i = 0; i < nx - 1; i++) {
                int a = j * nx + i, b = a + 1, c = a + nx, dd = c + 1;
                idx[k++] = (short) a; idx[k++] = (short) c; idx[k++] = (short) b;
                idx[k++] = (short) b; idx[k++] = (short) c; idx[k++] = (short) dd;
            }
        }
        return new Mesh(data, nx * nz, idx);
    }

    /** Island surface; where there is no island it sinks just below the lake bed. */
    private static float islandSurface(float x, float z) {
        float base = World.baseHeight(x, z);
        float isl = World.islandHeight(x, z);
        return isl > base ? isl : base - 0.08f;
    }

    private Mesh buildIslandProps() {
        MeshBuilder mb = new MeshBuilder();
        Random rnd = new Random(7);
        float[][] islandTrees = {{-7, -3}, {-9, 0}, {6, -4}, {8, 0}, {-3, 2}, {4, 2.5f}, {-12, -2}, {11, -1}};
        for (float[] t : islandTrees) {
            float h = World.height(t[0], t[1], true);
            if (h > 0.3f) tree(mb, rnd, t[0], h, t[1], 0.6f + rnd.nextFloat() * 0.4f);
        }
        // lighthouse
        float lx = World.ISLAND_X, lz = World.ISLAND_Z;
        float lh = World.height(lx, lz, true) - 0.2f;
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
        return mb.build();
    }

    /** Start / finish gate for a track. */
    static Mesh buildGate(Track t) {
        MeshBuilder mb = new MeshBuilder();
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

    /** An RC driver holding a transmitter. Local forward is -z, feet at y = 0. */
    private static Mesh buildFigure(float[] col) {
        MeshBuilder mb = new MeshBuilder();
        float[] pants = {0.16f, 0.2f, 0.32f};
        float[] skin = {0.92f, 0.74f, 0.6f};
        for (int s = -1; s <= 1; s += 2) {
            mb.box(s * 0.1f, 0.43f, 0, 0.15f, 0.86f, 0.17f, 0, pants[0], pants[1], pants[2]);
            mb.box(s * 0.1f, 0.04f, -0.04f, 0.15f, 0.08f, 0.27f, 0, 0.1f, 0.1f, 0.1f);
            // upper arm, forearm, hand
            mb.box(s * 0.25f, 1.22f, -0.03f, 0.1f, 0.34f, 0.11f, 0, col[0], col[1], col[2]);
            mb.box(s * 0.2f, 1.07f, -0.2f, 0.09f, 0.09f, 0.34f, 0, col[0], col[1], col[2]);
            mb.box(s * 0.13f, 1.1f, -0.38f, 0.07f, 0.08f, 0.08f, 0, skin[0], skin[1], skin[2]);
        }
        mb.box(0, 0.93f, 0, 0.36f, 0.14f, 0.21f, 0, pants[0], pants[1], pants[2]);
        mb.box(0, 1.2f, 0, 0.42f, 0.5f, 0.24f, 0, col[0], col[1], col[2]);
        mb.box(0, 1.36f, -0.005f, 0.3f, 0.06f, 0.2f, 0, col[0] * 0.8f, col[1] * 0.8f, col[2] * 0.8f);
        mb.cylinder(0, 1.44f, 0, 0.06f, 0.06f, 0.08f, 8, skin[0], skin[1], skin[2], false);
        mb.sphere(0, 1.62f, 0, 0.11f, 0.13f, 0.12f, 10, 6, skin[0], skin[1], skin[2]);
        // cap
        mb.sphere(0, 1.69f, 0.005f, 0.12f, 0.07f, 0.125f, 10, 4, col[0], col[1], col[2]);
        mb.box(0, 1.69f, -0.13f, 0.18f, 0.02f, 0.1f, 0, col[0] * 0.7f, col[1] * 0.7f, col[2] * 0.7f);
        // transmitter + antenna
        mb.box(0, 1.12f, -0.43f, 0.26f, 0.15f, 0.09f, 0, 0.12f, 0.12f, 0.14f);
        mb.box(0.08f, 1.36f, -0.46f, 0.015f, 0.42f, 0.015f, 0, 0.8f, 0.8f, 0.82f);
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
        float h = World.baseHeight(x, z);
        for (int k = 0; k < 100 && h < 0.5f; k++) {
            z += 0.3f;
            h = World.baseHeight(x, z);
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

    /**
     * A tapered hull pod centred on x = cx: stations along z (bow first) with deck half
     * widths, deck / chine / keel heights. Sides get a dark stripe.
     */
    private static void pod(MeshBuilder mb, float cx, float[] st, float[] hw, float[] deckY,
                            float[] chineY, float[] keelY, float[] side, float[] bottom, float[] deck,
                            int coloredDeckStations) {
        float[] ref = {cx, (deckY[2] + keelY[2]) / 2f, (st[0] + st[st.length - 1]) / 2f};
        float[] stripe = {0.1f, 0.1f, 0.12f};
        int n = st.length;
        for (int i = 0; i < n - 1; i++) {
            for (int s = -1; s <= 1; s += 2) {
                float[] d0 = {cx + s * hw[i], deckY[i], st[i]}, d1 = {cx + s * hw[i + 1], deckY[i + 1], st[i + 1]};
                float[] c0 = {cx + s * hw[i] * 0.95f, chineY[i], st[i]};
                float[] c1 = {cx + s * hw[i + 1] * 0.95f, chineY[i + 1], st[i + 1]};
                float[] m0 = {cx + s * hw[i] * 0.975f, (deckY[i] + chineY[i]) * 0.5f - 0.012f, st[i]};
                float[] m1 = {cx + s * hw[i + 1] * 0.975f, (deckY[i + 1] + chineY[i + 1]) * 0.5f - 0.012f, st[i + 1]};
                float[] m0b = {m0[0], m0[1] + 0.022f, m0[2]}, m1b = {m1[0], m1[1] + 0.022f, m1[2]};
                float[] k0 = {cx, keelY[i], st[i]}, k1 = {cx, keelY[i + 1], st[i + 1]};
                mb.quad(d0, d1, m1b, m0b, ref, side);
                mb.quad(m0b, m1b, m1, m0, ref, stripe);
                mb.quad(m0, m1, c1, c0, ref, side);
                mb.quad(c0, c1, k1, k0, ref, bottom);
            }
            float[] dl0 = {cx - hw[i], deckY[i], st[i]}, dr0 = {cx + hw[i], deckY[i], st[i]};
            float[] dl1 = {cx - hw[i + 1], deckY[i + 1], st[i + 1]}, dr1 = {cx + hw[i + 1], deckY[i + 1], st[i + 1]};
            mb.quad(dl0, dr0, dr1, dl1, new float[]{cx, -1, 0}, i < coloredDeckStations ? side : deck);
        }
        int l = n - 1;
        float[] tdl = {cx - hw[l], deckY[l], st[l]}, tdr = {cx + hw[l], deckY[l], st[l]};
        float[] tcl = {cx - hw[l] * 0.95f, chineY[l], st[l]}, tcr = {cx + hw[l] * 0.95f, chineY[l], st[l]};
        float[] tk = {cx, keelY[l], st[l]};
        float[] tref = {cx, (deckY[l] + keelY[l]) / 2f, st[l] - 0.3f};
        mb.quad(tdl, tdr, tcr, tcl, tref, side);
        mb.tri(tcl, tcr, tk, tref, bottom);
    }

    private static float[] dark(float[] c, float k) {
        return new float[]{c[0] * k, c[1] * k, c[2] * k};
    }

    private static final float[] WHITE = {0.96f, 0.96f, 0.96f};

    /** Deep-V speed boat, ~1.3 m long. Local forward is -z. */
    private static Mesh buildVHull(float[] col) {
        MeshBuilder mb = new MeshBuilder();
        pod(mb, 0,
                new float[]{-0.70f, -0.52f, -0.32f, 0.0f, 0.35f, 0.62f},
                new float[]{0.0f, 0.13f, 0.20f, 0.25f, 0.26f, 0.25f},
                new float[]{0.20f, 0.18f, 0.17f, 0.16f, 0.16f, 0.16f},
                new float[]{0.12f, 0.05f, 0.02f, 0.0f, 0.0f, 0.0f},
                new float[]{0.12f, -0.03f, -0.08f, -0.10f, -0.10f, -0.09f},
                col, dark(col, 0.35f), WHITE, 2);
        // canopy / cockpit
        mb.box(0, 0.215f, 0.10f, 0.30f, 0.11f, 0.46f, 0, 0.08f, 0.10f, 0.13f);
        mb.box(0, 0.28f, 0.16f, 0.26f, 0.04f, 0.32f, 0, col[0], col[1], col[2]);
        float[] w0 = {-0.15f, 0.16f, -0.13f}, w1 = {0.15f, 0.16f, -0.13f};
        float[] w2 = {0.15f, 0.27f, -0.02f}, w3 = {-0.15f, 0.27f, -0.02f};
        mb.quad(w0, w1, w2, w3, new float[]{0, 0.1f, 0.2f}, new float[]{0.15f, 0.25f, 0.32f});
        // stern details: rudder, prop strut, antenna, number plate
        mb.box(0.12f, -0.05f, 0.66f, 0.02f, 0.16f, 0.07f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(0, -0.08f, 0.66f, 0.03f, 0.03f, 0.1f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(-0.15f, 0.36f, 0.42f, 0.012f, 0.4f, 0.012f, 0, 0.08f, 0.08f, 0.08f);
        mb.box(0, 0.165f, 0.48f, 0.3f, 0.01f, 0.14f, 0, 0.97f, 0.97f, 0.97f);
        return mb.build();
    }

    /** Three-point hydroplane: two front sponsons and a raised centre body with a rear wing. */
    private static Mesh buildHydroplane(float[] col) {
        MeshBuilder mb = new MeshBuilder();
        for (int s = -1; s <= 1; s += 2) {
            pod(mb, s * 0.27f,
                    new float[]{-0.70f, -0.58f, -0.38f, -0.10f},
                    new float[]{0.0f, 0.06f, 0.08f, 0.08f},
                    new float[]{0.16f, 0.15f, 0.15f, 0.15f},
                    new float[]{0.09f, 0.02f, 0.0f, 0.0f},
                    new float[]{0.08f, 0.01f, -0.01f, -0.01f},
                    col, dark(col, 0.35f), col, 0);
        }
        // tunnel deck joining the sponsons
        mb.box(0, 0.14f, -0.36f, 0.56f, 0.04f, 0.52f, 0, WHITE[0], WHITE[1], WHITE[2]);
        // centre body riding high, on the prop at the back
        pod(mb, 0,
                new float[]{-0.45f, -0.15f, 0.25f, 0.62f},
                new float[]{0.10f, 0.16f, 0.16f, 0.13f},
                new float[]{0.19f, 0.22f, 0.22f, 0.21f},
                new float[]{0.12f, 0.08f, 0.07f, 0.07f},
                new float[]{0.11f, 0.05f, 0.04f, 0.05f},
                col, dark(col, 0.35f), WHITE, 0);
        // cockpit canopy
        mb.box(0, 0.27f, 0.02f, 0.2f, 0.1f, 0.42f, 0, 0.08f, 0.1f, 0.13f);
        mb.sphere(0, 0.30f, -0.18f, 0.1f, 0.07f, 0.12f, 8, 4, 0.15f, 0.25f, 0.32f);
        // rear wing on two fins
        for (int s = -1; s <= 1; s += 2) {
            mb.box(s * 0.12f, 0.33f, 0.55f, 0.02f, 0.2f, 0.12f, 0, col[0], col[1], col[2]);
        }
        mb.box(0, 0.44f, 0.56f, 0.38f, 0.025f, 0.13f, 0, WHITE[0], WHITE[1], WHITE[2]);
        // prop strut, rudder, antenna
        mb.box(0, 0.0f, 0.66f, 0.03f, 0.12f, 0.08f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(0.1f, -0.01f, 0.6f, 0.02f, 0.14f, 0.07f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(-0.1f, 0.42f, 0.3f, 0.012f, 0.4f, 0.012f, 0, 0.08f, 0.08f, 0.08f);
        return mb.build();
    }

    /** Outrigger: a slim centre hull with two small sponsons on booms. */
    private static Mesh buildOutrigger(float[] col) {
        MeshBuilder mb = new MeshBuilder();
        pod(mb, 0,
                new float[]{-0.74f, -0.52f, -0.2f, 0.2f, 0.62f},
                new float[]{0.0f, 0.08f, 0.11f, 0.12f, 0.11f},
                new float[]{0.17f, 0.16f, 0.16f, 0.16f, 0.16f},
                new float[]{0.10f, 0.03f, 0.0f, 0.0f, 0.0f},
                new float[]{0.10f, -0.04f, -0.07f, -0.07f, -0.06f},
                col, dark(col, 0.35f), WHITE, 1);
        for (int s = -1; s <= 1; s += 2) {
            pod(mb, s * 0.37f,
                    new float[]{-0.74f, -0.6f, -0.36f, -0.14f},
                    new float[]{0.0f, 0.045f, 0.055f, 0.05f},
                    new float[]{0.12f, 0.12f, 0.12f, 0.12f},
                    new float[]{0.07f, 0.02f, 0.01f, 0.01f},
                    new float[]{0.06f, 0.0f, -0.01f, 0.0f},
                    WHITE, dark(col, 0.35f), col, 4);
        }
        // booms
        for (float z : new float[]{-0.56f, -0.24f}) {
            mb.box(0, 0.145f, z, 0.76f, 0.03f, 0.05f, 0, 0.7f, 0.7f, 0.72f);
        }
        // canopy, tail fin, rudder, antenna
        mb.box(0, 0.22f, 0.12f, 0.18f, 0.1f, 0.38f, 0, 0.08f, 0.1f, 0.13f);
        mb.box(0, 0.28f, 0.15f, 0.15f, 0.03f, 0.28f, 0, col[0], col[1], col[2]);
        mb.box(0, 0.3f, 0.52f, 0.02f, 0.26f, 0.18f, 0, col[0], col[1], col[2]);
        mb.box(0.09f, -0.05f, 0.62f, 0.02f, 0.15f, 0.07f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(0, -0.07f, 0.66f, 0.03f, 0.03f, 0.1f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(-0.08f, 0.38f, 0.38f, 0.012f, 0.4f, 0.012f, 0, 0.08f, 0.08f, 0.08f);
        return mb.build();
    }
}
