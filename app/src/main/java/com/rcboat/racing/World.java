package com.rcboat.racing;

/**
 * Static description of the lake: terrain height field (lake basin, central island,
 * beaches and surrounding hills) and the small surface waves.
 * World axes: x = east, y = up, z = south (towards the spectator on the shore).
 */
final class World {
    static final float LAKE_A = 78f;   // lake semi-axis along x
    static final float LAKE_B = 54f;   // lake semi-axis along z
    static final float ISLAND_X = 0f, ISLAND_Z = -2f, ISLAND_RX = 24f, ISLAND_RZ = 12f;
    static final float TERRAIN_HALF = 180f;
    /**
     * Life-size racing uses the same landscape scaled up 14 times: a lake about 2.2 x 1.5 km
     * in hills, big enough for a 2.5 mile Unlimited hydroplane course. The RC lake is 1.
     */
    static final float LIFE_SCALE = 14f;
    static volatile float scale = 1f;

    /** Half the size of the terrain square at the current scale. */
    static float terrainHalf() { return TERRAIN_HALF * scale; }
    /** Boats cannot enter places where the lake bed is higher than this. */
    static final float SHALLOW = -0.15f;

    private World() {}

    /** Normalised lake radius: < 1 inside the lake basin, > 1 on land. */
    static float lakeQ(float x, float z) {
        double nx = x / (LAKE_A * scale), nz = z / (LAKE_B * scale);
        double ang = Math.atan2(nz, nx);
        double wob = 1.0 + 0.05 * Math.sin(3 * ang + 0.7) + 0.035 * Math.sin(5 * ang + 2.1)
                + 0.02 * Math.sin(7 * ang);
        return (float) (Math.sqrt(nx * nx + nz * nz) / wob);
    }

    /** Whether the central island exists right now (off in shore view / on tracks that cross the middle). */
    static volatile boolean islandEnabled = true;

    /** Lake basin, beaches and hills, without the island. */
    static float baseHeight(float x, float z) {
        float s = scale;
        if (s != 1f) return s * baseHeight1(x / s, z / s);
        return baseHeight1(x, z);
    }

    /** The landscape at RC scale (1). */
    private static float baseHeight1(float x, float z) {
        double nx0 = x / LAKE_A, nz0 = z / LAKE_B;
        double ang0 = Math.atan2(nz0, nx0);
        double wob0 = 1.0 + 0.05 * Math.sin(3 * ang0 + 0.7) + 0.035 * Math.sin(5 * ang0 + 2.1)
                + 0.02 * Math.sin(7 * ang0);
        float q = (float) (Math.sqrt(nx0 * nx0 + nz0 * nz0) / wob0);
        float h;
        if (q < 1f) {
            h = -0.4f - 2.2f * (float) Math.sqrt(1f - q);
        } else {
            float d = (q - 1f) * 64f; // metres beyond the lake edge (approx.)
            if (d < 4f) {
                h = -0.4f + d * 0.25f;
            } else {
                h = 0.6f + (d - 4f) * 0.12f;
                float hills = (float) (Math.sin(x * 0.05) * Math.cos(z * 0.07) * 3.0
                        + Math.sin(x * 0.13 + z * 0.09) * 1.2 + Math.cos(z * 0.031 - x * 0.02) * 2.0);
                h += hills * Math.min(1f, Math.max(0f, (d - 6f) / 20f));
                if (d > 30f) h += (d - 30f) * 0.25f;
            }
        }
        return h;
    }

    /** Height of the island surface, or -100 outside its footprint. */
    static float islandHeight(float x, float z) {
        if (scale != 1f) return -100f;   // no island on the big lake
        float ix = (x - ISLAND_X) / ISLAND_RX, iz = (z - ISLAND_Z) / ISLAND_RZ;
        float ri = (float) Math.sqrt(ix * ix + iz * iz);
        if (ri >= 1f) return -100f;
        return -1.6f + 4.2f * (float) Math.pow(1f - ri, 0.8)
                + 0.25f * (float) (Math.sin(x * 0.7) * Math.cos(z * 0.9)) * (1f - ri);
    }

    static float height(float x, float z, boolean island) {
        float h = baseHeight(x, z);
        return island ? Math.max(h, islandHeight(x, z)) : h;
    }

    static float height(float x, float z) {
        return height(x, z, islandEnabled);
    }

    static boolean isWater(float x, float z) {
        return height(x, z) < SHALLOW;
    }

    static boolean isWater(float x, float z, boolean island) {
        return height(x, z, island) < SHALLOW;
    }

    // ---- surface waves (must match the water vertex shader) ----
    static float wave(float x, float z, float t) {
        return 0.035f * (float) Math.sin(x * 0.9f + t * 1.6f)
                + 0.025f * (float) Math.sin(z * 1.3f - t * 1.9f)
                + 0.015f * (float) Math.sin((x + z) * 2.1f + t * 2.7f);
    }

    static float waveDx(float x, float z, float t) {
        return 0.035f * 0.9f * (float) Math.cos(x * 0.9f + t * 1.6f)
                + 0.015f * 2.1f * (float) Math.cos((x + z) * 2.1f + t * 2.7f);
    }

    static float waveDz(float x, float z, float t) {
        return 0.025f * 1.3f * (float) Math.cos(z * 1.3f - t * 1.9f)
                + 0.015f * 2.1f * (float) Math.cos((x + z) * 2.1f + t * 2.7f);
    }

    /** z coordinate of the south shoreline at x = 0 (where the spectator's dock is). */
    static float southShoreZ() {
        float s = scale;
        float z = 30f * s;
        while (z < 120f * s && baseHeight(0f, z) < 0f) z += 0.1f * s;
        return z;
    }
}
