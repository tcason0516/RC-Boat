package com.rcboat.racing;

import java.util.Random;

/** Builds all the meshes of the scene. Must be called on the GL thread. */
final class Scenery {
    /** Sun direction; matches the sun in the photographed sky panorama (assets/tex/sky.jpg). */
    static final float[] LIGHT = normalize(-0.3941f, 0.7406f, 0.5442f);
    /** Haze colour (before tone mapping); matches the horizon of the sky panorama. */
    static final float[] FOG = {0.494f, 0.521f, 0.615f};

    Mesh sky, terrain, water, props, island, islandProps, buoyRed, buoyYellow, buoyTurn, buoyPlain, marker;
    /** Start clock body and one 7-segment bar (unit size, drawn per lit segment). */
    Mesh clockBody, segment;
    Mesh foliage, islandFoliage;   // alpha-tested cards (trees near the lake, grass, reeds)
    Mesh bark, wood, rocks, islandBark;   // photo-textured (triplanar): tree trunks, platform timber, boulders
    private MeshBuilder foliageB, islandFoliageB, barkB, woodB, rocksB, islandBarkB;
    Mesh[][] boats;     // [hull][color]
    Mesh[] figures;     // RC drivers, one per color

    Scenery(Game game) {
        sky = buildSky();
        terrain = buildTerrain();
        water = buildWater();
        foliageB = new MeshBuilder();
        islandFoliageB = new MeshBuilder();
        barkB = new MeshBuilder();
        woodB = new MeshBuilder();
        rocksB = new MeshBuilder();
        islandBarkB = new MeshBuilder();
        props = buildProps(game);
        buoyRed = buildBuoy(0.95f, 0.18f, 0.12f);
        buoyYellow = buildBuoy(1.0f, 0.78f, 0.08f);
        buoyTurn = buildTurnBuoy();
        buoyPlain = buildBuoy(0.96f, 0.96f, 0.94f);
        clockBody = buildClock();
        segment = buildSegment();
        marker = buildMarker();
        island = buildIsland();
        islandProps = buildIslandProps();
        foliage = foliageB.build();
        islandFoliage = islandFoliageB.build();
        bark = barkB.build();
        wood = woodB.build();
        rocks = rocksB.build();
        islandBark = islandBarkB.build();
        foliageB = islandFoliageB = barkB = woodB = rocksB = islandBarkB = null;
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

    private static float[] mix(float[] a, float[] b, float t) {
        t = Math.max(0f, Math.min(1f, t));
        return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /**
     * Terrain texture blend weights, stored in the vertex colour slot:
     * r = sand, g = rock, b = which grass (0 meadow .. 1 dark lush). The shader adds the
     * wet band at the waterline and the pebbly lake bed from the height.
     */
    private static float[] groundColor(float x, float z, float h, float ny) {
        float q = World.lakeQ(x, z);
        float ix = (x - World.ISLAND_X) / World.ISLAND_RX, iz = (z - World.ISLAND_Z) / World.ISLAND_RZ;
        boolean nearWater = q < 1.2f || (ix * ix + iz * iz) < 1f;
        float patch = noise(x * 0.23f + 40f, z * 0.23f - 17f);
        float sand = 0f;
        if (h < 0.6f) sand = (nearWater || h < 0f) ? 1f : 0f;
        else if (nearWater) sand = Math.max(0f, 1f - (h - 0.6f) / 0.6f);
        float rock = Math.max(0f, Math.min(1f, (0.9f - ny) * 7f));
        if (h > 26f) rock = Math.max(rock, Math.min(1f, (h - 26f) / 10f));
        float lush = Math.max(0f, Math.min(1f, 0.5f - patch * 0.9f));
        return new float[]{sand, rock, lush};
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
                float[] c = groundColor(x, z, h, nn[1]);
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
        for (int tries = 0; tries < 14000 && placed < 600; tries++) {
            float x = (rnd.nextFloat() * 2 - 1) * 165f;
            float z = (rnd.nextFloat() * 2 - 1) * 165f;
            float h = World.baseHeight(x, z);
            if (h < 1.0f || h > 22f) continue;
            if (World.lakeQ(x, z) < 1.1f) continue;
            if (Math.abs(x) < 12f && z > shoreZ - 4f && z < shoreZ + 20f) continue;
            float sz = 0.8f + rnd.nextFloat() * 0.9f;
            if (World.lakeQ(x, z) < 1.9f) nearTree(mb, barkB, foliageB, rnd, x, h, z, sz);
            else tree(mb, barkB, rnd, x, h, z, sz);
            placed++;
        }
        shorePlants(foliageB, rnd, game);

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
            rocksB.lumpySphere(x, World.baseHeight(x, z), z, s * 1.3f, s * 0.7f, s, 9, 6, rnd.nextInt(),
                    g * 2f, g * 2f, g * 1.95f);
        }

        platform(mb, woodB, game);

        // boathouses on the shore
        house(mb, 30f, shoreZ - 3f, 0.4f);
        house(mb, -38f, shoreZ - 6f, -0.5f);
        return mb.build();
    }

    /** The 5 ft (1.52 m) drivers' platform on the south shore. */
    private static void platform(MeshBuilder mb, MeshBuilder wood, Game game) {
        float w = Game.PLATFORM_W, d = Game.PLATFORM_D;
        float cz = game.platformZ, top = game.deckY, ground = game.groundY;
        float front = cz - d / 2f, back = cz + d / 2f;
        // timber goes into the photo-textured wood mesh; these are tints over the plank texture
        float wr = 1.75f, wg = 1.62f, wb = 1.5f;       // sun-bleached deck planks
        float pr = 1.2f, pg = 1.12f, pb = 1.05f;       // posts
        // deck
        wood.box(0, top - 0.08f, cz, w, 0.16f, d, 0, wr, wg, wb);
        // support posts and braces
        for (float x = -w / 2 + 0.15f; x <= w / 2; x += (w - 0.3f) / 4f) {
            for (float z : new float[]{front + 0.15f, back - 0.15f}) {
                float base = ground - 0.6f;
                wood.box(x, (base + top - 0.16f) / 2f, z, 0.18f, top - 0.16f - base, 0.18f, 0, pr, pg, pb);
            }
            wood.box(x, ground + 0.5f, cz, 0.1f, 0.1f, d - 0.3f, 0, pr, pg, pb);
        }
        wood.box(0, ground + 0.6f, front + 0.15f, w - 0.3f, 0.1f, 0.08f, 0, pr, pg, pb);
        wood.box(0, ground + 0.6f, back - 0.15f, w - 0.3f, 0.1f, 0.08f, 0, pr, pg, pb);
        // safety railing on the front and sides
        float rail = 1.0f;
        for (float x = -w / 2 + 0.05f; x <= w / 2; x += (w - 0.1f) / 8f) {
            wood.box(x, top + rail / 2f, front + 0.05f, 0.07f, rail, 0.07f, 0, pr, pg, pb);
        }
        mb.box(0, top + rail, front + 0.05f, w, 0.08f, 0.1f, 0, 0.92f, 0.92f, 0.9f);
        wood.box(0, top + rail * 0.5f, front + 0.05f, w, 0.05f, 0.05f, 0, pr, pg, pb);
        for (int s = -1; s <= 1; s += 2) {
            float x = s * (w / 2f - 0.05f);
            for (float z = front; z <= back; z += d / 3f) {
                wood.box(x, top + rail / 2f, z, 0.07f, rail, 0.07f, 0, pr, pg, pb);
            }
            mb.box(x, top + rail, cz, 0.1f, 0.08f, d, 0, 0.92f, 0.92f, 0.9f);
        }
        // stairs at the back
        int steps = 8;
        float rise = (top - ground) / steps, tread = 0.3f;
        for (int i = 0; i < steps; i++) {
            float y = top - rise * (i + 1);
            float z = back + tread * (i + 0.5f);
            wood.box(0, y + rise / 2f - 0.02f, z, 1.3f, 0.06f, tread, 0, wr, wg, wb);
            wood.box(0, (ground + y) / 2f, z + tread / 2f - 0.04f, 1.1f, Math.max(0.05f, y - ground), 0.05f, 0,
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
                float[] c = groundColor(x, z, h, nn[1]);
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
            if (h > 0.3f) nearTree(mb, islandBarkB, islandFoliageB, rnd, t[0], h, t[1], 0.6f + rnd.nextFloat() * 0.4f);
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

    /** A tree near the lake: solid trunk and dark inner core, textured branch / leaf cards outside. */
    private static void nearTree(MeshBuilder solid, MeshBuilder bk, MeshBuilder fol, Random rnd, float x, float h, float z, float s) {
        float bark = 0.85f + rnd.nextFloat() * 0.3f;
        int seed = rnd.nextInt();
        if (rnd.nextFloat() < 0.62f) {
            s *= 1.1f + rnd.nextFloat() * 0.25f;
            float H = 6.6f * s, y0 = h + 0.7f * s, R = 1.9f * s;
            bk.cylinder(x, h - 0.2f, z, 0.2f * s, 0.06f * s, H + 0.3f, 8, bark, bark * 0.95f, bark * 0.9f, false);
            solid.foliageCone(x, y0 + 0.3f * s, z, R * 0.55f, H * 0.82f, 8, seed, 0.035f, 0.08f, 0.04f);
            int tiers = 10;
            float spin = rnd.nextFloat() * 6.28f;
            for (int k = 0; k < tiers; k++) {
                float f = (float) k / tiers;
                float yk = y0 + (H - 0.7f * s) * f;
                float rk = R * (float) Math.pow(1 - f, 0.9) + 0.25f * s;
                int nb = k < tiers - 2 ? 7 : 5;
                for (int j = 0; j < nb; j++) {
                    float a = spin + k * 2.4f + j * 6.2832f / nb + (rnd.nextFloat() - 0.5f) * 0.4f;
                    float ca = (float) Math.cos(a), sa = (float) Math.sin(a);
                    float len = rk * (0.85f + rnd.nextFloat() * 0.3f);
                    float droop = len * (0.25f + rnd.nextFloat() * 0.2f);
                    float w = Math.max(0.45f * s, len * 0.6f) * 0.5f;
                    float[] base = {x, yk + 0.1f * s, z};
                    float[] tip = {x + ca * len, yk - droop, z + sa * len};
                    float bright = 0.85f + rnd.nextFloat() * 0.35f;
                    float[] flex = {0f, 0.45f, 0.45f, 0f};
                    // two sprays rolled about the branch axis (a shallow V), not flat fins
                    for (int roll = 0; roll < 2; roll++) {
                        float ang = roll == 0 ? 0.5f : -0.6f;
                        float cy = (float) Math.cos(ang), sy = (float) Math.sin(ang);
                        float wx = -sa * w * cy, wy = w * sy, wz = ca * w * cy;   // width axis rolled up/down
                        fol.card(new float[]{base[0] + wx * 0.3f, base[1] + wy * 0.3f, base[2] + wz * 0.3f},
                                new float[]{tip[0] + wx, tip[1] + wy, tip[2] + wz},
                                new float[]{tip[0] - wx, tip[1] - wy, tip[2] - wz},
                                new float[]{base[0] - wx * 0.3f, base[1] - wy * 0.3f, base[2] - wz * 0.3f},
                                MeshBuilder.CONIFER, x, yk - 0.4f * s, z, 0.6f, bright * (roll == 0 ? 1f : 0.9f), flex);
                    }
                }
            }
            // leader at the top
            float top = y0 + H - 0.4f * s;
            for (int j = 0; j < 2; j++) {
                float a = j * 1.5708f, ca = (float) Math.cos(a) * 0.35f * s, sa = (float) Math.sin(a) * 0.35f * s;
                fol.card(new float[]{x - ca * 0.7f, top + 0.6f * s, z - sa * 0.7f}, new float[]{x - ca * 0.7f, top - 0.5f * s, z - sa * 0.7f},
                        new float[]{x + ca * 0.7f, top - 0.5f * s, z + sa * 0.7f}, new float[]{x + ca * 0.7f, top + 0.6f * s, z + sa * 0.7f},
                        new float[]{0.496f, 0.004f, 0.004f, 0.496f}, x, top - 1f, z, 0.5f, 0.7f,
                        new float[]{0.5f, 0.2f, 0.2f, 0.5f});
            }
        } else {
            float g = 0.85f + rnd.nextFloat() * 0.3f;
            bk.cylinder(x, h - 0.2f, z, 0.24f * s, 0.14f * s, 2.6f * s, 8, bark * 1.1f, bark * 1.05f, bark, false);
            float[][] blobs = {
                    {0, 2.7f, 0, 1.8f, 1.45f, 34},
                    {0.8f, 3.4f, -0.3f, 1.2f, 1.05f, 18},
                    {-0.7f, 3.2f, 0.5f, 1.15f, 1.0f, 16}};
            for (int bI = 0; bI < blobs.length; bI++) {
                float[] bl = blobs[bI];
                float bx = x + bl[0] * s, by = h + bl[1] * s, bz = z + bl[2] * s;
                float rr = bl[3] * s, ry = bl[4] * s;
                solid.lumpySphere(bx, by, bz, rr * 0.72f, ry * 0.72f, rr * 0.72f, 8, 5, seed + bI,
                        0.05f * g, 0.11f * g, 0.035f * g);
                int cards = (int) bl[5];
                for (int k = 0; k < cards; k++) {
                    // a point on/near the canopy surface and a card facing outwards
                    double th = Math.acos(1 - 2 * rnd.nextDouble() * 0.85), ph = rnd.nextDouble() * Math.PI * 2;
                    float nx = (float) (Math.sin(th) * Math.cos(ph)), ny = (float) Math.cos(th), nz = (float) (Math.sin(th) * Math.sin(ph));
                    float d = 0.7f + rnd.nextFloat() * 0.3f;
                    float px = bx + nx * rr * d, py = by + ny * ry * d, pz = bz + nz * rr * d;
                    // tangent axes
                    float tx = -nz, tz = nx, tl = (float) Math.sqrt(tx * tx + tz * tz);
                    if (tl < 1e-3f) { tx = 1; tz = 0; tl = 1; }
                    tx /= tl; tz /= tl;
                    float bxv = ny * tz, byv = nz * tx - nx * tz, bzv = -ny * tx;   // n x t
                    float half = (0.75f + rnd.nextFloat() * 0.45f) * s;
                    float rot = rnd.nextFloat() * 6.28f, cr = (float) Math.cos(rot), sr = (float) Math.sin(rot);
                    float ux = tx * cr + bxv * sr, uy = byv * sr, uz = tz * cr + bzv * sr;
                    float vx = -tx * sr + bxv * cr, vy = byv * cr, vz = -tz * sr + bzv * cr;
                    float[][] c = new float[4][];
                    float[][] k4 = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
                    for (int q = 0; q < 4; q++) {
                        c[q] = new float[]{px + (ux * k4[q][0] + vx * k4[q][1]) * half,
                                py + (uy * k4[q][0] + vy * k4[q][1]) * half,
                                pz + (uz * k4[q][0] + vz * k4[q][1]) * half};
                    }
                    float bright = (0.85f + rnd.nextFloat() * 0.35f) * g;
                    fol.card(c[0], c[1], c[2], c[3], MeshBuilder.LEAVES, bx, by - ry * 0.3f, bz, 0.4f, bright,
                            new float[]{0.35f, 0.35f, 0.35f, 0.35f});
                }
            }
        }
    }

    /** Grass tufts on the banks and cattail reeds in the shallows. */
    private static void shorePlants(MeshBuilder fol, Random rnd, Game game) {
        int placed = 0;
        for (int tries = 0; tries < 9000 && placed < 520; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2;
            float q = 0.93f + rnd.nextFloat() * 0.3f;
            float x = (float) Math.cos(a) * World.LAKE_A * q * 1.03f, z = (float) Math.sin(a) * World.LAKE_B * q * 1.03f;
            float h = World.baseHeight(x, z);
            if (h < -0.35f || h > 1.2f) continue;
            // keep the view from the drivers' platform clear
            if (Math.abs(x) < 9f && z > game.shoreZ - 6f && z < game.shoreZ + 8f) continue;
            boolean reeds = h < 0.15f;
            float height = reeds ? 1.1f + rnd.nextFloat() * 0.7f : 0.45f + rnd.nextFloat() * 0.5f;
            float width = reeds ? 0.9f + rnd.nextFloat() * 0.5f : 0.7f + rnd.nextFloat() * 0.5f;
            float[] uv = reeds ? MeshBuilder.REEDS : MeshBuilder.GRASS;
            float bright = 0.85f + rnd.nextFloat() * 0.3f;
            float base = h - 0.05f;
            float rot = rnd.nextFloat() * 3.14f;
            for (int k = 0; k < 3; k++) {
                float an = rot + k * 1.047f;
                float dx = (float) Math.cos(an) * width * 0.5f, dz = (float) Math.sin(an) * width * 0.5f;
                // v = 0 is the tip (top), v = 1 the base; tips flex fully in the wind
                fol.card(new float[]{x - dx, base + height, z - dz}, new float[]{x + dx, base + height, z + dz},
                        new float[]{x + dx, base, z + dz}, new float[]{x - dx, base, z - dz},
                        uv, x, base - 2f, z, 1.5f, bright, new float[]{1f, 1f, 0f, 0f});
            }
            placed++;
        }
    }

    /** Orthographic sun view-projection (column-major) covering the lake area, for shadows. */
    static float[] lightViewProj() {
        float[] L = LIGHT;
        float[] e = {L[0] * 300f, L[1] * 300f, L[2] * 300f};
        float[] f = normalize(-L[0], -L[1], -L[2]);
        float[] sv = normalize(f[1] * 0 - f[2] * 1, f[2] * 0 - f[0] * 0, f[0] * 1 - f[1] * 0); // f x up
        float[] u = {sv[1] * f[2] - sv[2] * f[1], sv[2] * f[0] - sv[0] * f[2], sv[0] * f[1] - sv[1] * f[0]};
        float[] v = new float[16];
        v[0] = sv[0]; v[4] = sv[1]; v[8] = sv[2]; v[12] = -(sv[0] * e[0] + sv[1] * e[1] + sv[2] * e[2]);
        v[1] = u[0]; v[5] = u[1]; v[9] = u[2]; v[13] = -(u[0] * e[0] + u[1] * e[1] + u[2] * e[2]);
        v[2] = -f[0]; v[6] = -f[1]; v[10] = -f[2]; v[14] = f[0] * e[0] + f[1] * e[1] + f[2] * e[2];
        v[15] = 1;
        float minX = 1e9f, maxX = -1e9f, minY = 1e9f, maxY = -1e9f, minD = 1e9f, maxD = -1e9f;
        float ext = 125f;
        for (int i = 0; i < 8; i++) {
            float px = (i & 1) == 0 ? -ext : ext, py = (i & 2) == 0 ? -3f : 34f, pz = (i & 4) == 0 ? -ext : ext;
            float vx = v[0] * px + v[4] * py + v[8] * pz + v[12];
            float vy = v[1] * px + v[5] * py + v[9] * pz + v[13];
            float vz = v[2] * px + v[6] * py + v[10] * pz + v[14];
            minX = Math.min(minX, vx); maxX = Math.max(maxX, vx);
            minY = Math.min(minY, vy); maxY = Math.max(maxY, vy);
            minD = Math.min(minD, -vz); maxD = Math.max(maxD, -vz);
        }
        float n = minD - 5f, fa = maxD + 5f;
        float[] o = new float[16];
        o[0] = 2f / (maxX - minX); o[5] = 2f / (maxY - minY); o[10] = -2f / (fa - n);
        o[12] = -(maxX + minX) / (maxX - minX); o[13] = -(maxY + minY) / (maxY - minY);
        o[14] = -(fa + n) / (fa - n); o[15] = 1;
        float[] r = new float[16];
        for (int c = 0; c < 4; c++)
            for (int rI = 0; rI < 4; rI++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) sum += o[k * 4 + rI] * v[c * 4 + k];
                r[c * 4 + rI] = sum;
            }
        return r;
    }

    private static void tree(MeshBuilder mb, MeshBuilder bk, Random rnd, float x, float h, float z, float s) {
        float bark = 0.85f + rnd.nextFloat() * 0.3f;
        float g = 0.8f + rnd.nextFloat() * 0.35f;
        int seed = rnd.nextInt();
        if (rnd.nextFloat() < 0.62f) {
            // conifer: four irregular, drooping tiers
            s *= 1.1f + rnd.nextFloat() * 0.25f;
            bk.cylinder(x, h - 0.2f, z, 0.2f * s, 0.12f * s, 1.4f * s, 6, bark, bark * 0.95f, bark * 0.9f, false);
            float[] y0 = {0.7f, 1.9f, 3.0f, 4.0f}, r = {1.75f, 1.4f, 1.05f, 0.65f}, hh = {2.3f, 2.1f, 1.9f, 1.7f};
            for (int t = 0; t < 4; t++) {
                float k = 0.85f + t * 0.07f;
                mb.foliageCone(x, h + y0[t] * s, z, r[t] * s, hh[t] * s, 9, seed + t,
                        0.09f * g * k, 0.22f * g * k, 0.11f * g * k);
            }
        } else {
            // broadleaf: lumpy overlapping canopy blobs
            bk.cylinder(x, h - 0.2f, z, 0.24f * s, 0.16f * s, 2.2f * s, 6, bark * 1.1f, bark * 1.05f, bark, false);
            float hue = rnd.nextFloat() * 0.06f;
            float cr = (0.19f + hue) * g, cg = 0.37f * g, cb = 0.12f * g;
            mb.lumpySphere(x, h + 2.6f * s, z, 1.7f * s, 1.35f * s, 1.7f * s, 9, 5, seed, cr, cg, cb);
            mb.lumpySphere(x + 0.75f * s, h + 3.3f * s, z - 0.3f * s, 1.15f * s, 1.0f * s, 1.15f * s, 8, 4, seed + 1,
                    cr * 1.08f, cg * 1.08f, cb);
            mb.lumpySphere(x - 0.65f * s, h + 3.1f * s, z + 0.45f * s, 1.05f * s, 0.95f * s, 1.05f * s, 8, 4, seed + 2,
                    cr * 1.04f, cg * 1.04f, cb);
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

    /** IMPBA turn buoy: larger, Day-Glo orange with a black band (seen easily from shore). */
    private static Mesh buildTurnBuoy() {
        MeshBuilder mb = new MeshBuilder();
        mb.cylinder(0, -0.3f, 0, 0.42f, 0.38f, 0.75f, 14, 1.0f, 0.38f, 0.04f, true);
        mb.cylinder(0, 0.45f, 0, 0.38f, 0.36f, 0.16f, 14, 0.05f, 0.05f, 0.05f, false);
        mb.cylinder(0, 0.61f, 0, 0.36f, 0f, 0.55f, 14, 1.0f, 0.38f, 0.04f, true);
        return mb.build();
    }

    /** Clock face width and height, and where the digits sit on it (local, facing +z). */
    static final float CLOCK_W = 2.1f, CLOCK_H = 1.0f, CLOCK_Y0 = 0.35f;
    static final float DIGIT_H = 0.61f;   // 24-inch digits, the largest the rules allow

    /**
     * IMPBA digital start clock: black face, white enclosure, on styrofoam floats, its
     * bottom 6-18 in above the water. Local origin on the water, face towards +z.
     */
    private static Mesh buildClock() {
        MeshBuilder mb = new MeshBuilder();
        for (int s = -1; s <= 1; s += 2) {
            mb.box(s * 0.7f, 0.05f, 0, 0.6f, 0.3f, 0.9f, 0, 0.95f, 0.95f, 0.92f);     // floats
            mb.box(s * 0.7f, 0.25f, 0, 0.08f, 0.2f, 0.08f, 0, 0.8f, 0.8f, 0.82f);    // legs
        }
        mb.box(0, 0.2f, 0, 1.9f, 0.05f, 0.9f, 0, 0.7f, 0.7f, 0.72f);                 // deck
        mb.box(0, CLOCK_Y0 + CLOCK_H / 2, -0.06f, CLOCK_W, CLOCK_H, 0.24f, 0, 0.92f, 0.92f, 0.9f);
        mb.box(0, CLOCK_Y0 + CLOCK_H / 2, 0.065f, CLOCK_W - 0.1f, CLOCK_H - 0.1f, 0.01f, 0,
                0.03f, 0.03f, 0.035f);                                                // black face
        return mb.build();
    }

    /** A unit bar for one lit segment, bright Day-Glo orange; scaled when drawn. */
    private static Mesh buildSegment() {
        MeshBuilder mb = new MeshBuilder();
        mb.box(0, 0, 0, 1f, 1f, 1f, 0, 1.0f, 0.55f, 0.1f);
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
        // smooth cowling with a tinted glass canopy
        mb.sphere(0, 0.16f, 0.14f, 0.16f, 0.10f, 0.30f, 14, 7, col[0], col[1], col[2]);
        mb.sphere(0, 0.19f, -0.02f, 0.115f, 0.085f, 0.15f, 14, 7, 0.05f, 0.08f, 0.11f);
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
        // cowling and tinted glass canopy
        mb.sphere(0, 0.22f, 0.10f, 0.13f, 0.09f, 0.32f, 14, 7, col[0], col[1], col[2]);
        mb.sphere(0, 0.25f, -0.10f, 0.09f, 0.075f, 0.13f, 14, 7, 0.05f, 0.08f, 0.11f);
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
        // cowling + glass canopy, tail fin, rudder, antenna
        mb.sphere(0, 0.16f, 0.15f, 0.11f, 0.09f, 0.30f, 14, 7, col[0], col[1], col[2]);
        mb.sphere(0, 0.19f, 0.0f, 0.08f, 0.07f, 0.12f, 14, 7, 0.05f, 0.08f, 0.11f);
        mb.box(0, 0.3f, 0.52f, 0.02f, 0.26f, 0.18f, 0, col[0], col[1], col[2]);
        mb.box(0.09f, -0.05f, 0.62f, 0.02f, 0.15f, 0.07f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(0, -0.07f, 0.66f, 0.03f, 0.03f, 0.1f, 0, 0.75f, 0.75f, 0.78f);
        mb.box(-0.08f, 0.38f, 0.38f, 0.012f, 0.4f, 0.012f, 0, 0.08f, 0.08f, 0.08f);
        return mb.build();
    }
}
