package com.rcboat.racing;

/** Accumulates triangles (pos, normal, color) and creates a {@link Mesh}. */
final class MeshBuilder {
    float[] data = new float[Mesh.FLOATS * 3 * 256];
    int vertices;

    private void ensure(int more) {
        int need = (vertices + more) * Mesh.FLOATS;
        if (need > data.length) {
            float[] n = new float[Math.max(need, data.length * 2)];
            System.arraycopy(data, 0, n, 0, vertices * Mesh.FLOATS);
            data = n;
        }
    }

    void vertex(float x, float y, float z, float nx, float ny, float nz, float r, float g, float b) {
        ensure(1);
        int o = vertices * Mesh.FLOATS;
        data[o] = x; data[o + 1] = y; data[o + 2] = z;
        data[o + 3] = nx; data[o + 4] = ny; data[o + 5] = nz;
        data[o + 6] = r; data[o + 7] = g; data[o + 8] = b;
        vertices++;
    }

    /** Flat shaded triangle whose normal is flipped to point away from (rx, ry, rz). */
    void tri(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
             float rx, float ry, float rz, float r, float g, float b) {
        float ux = bx - ax, uy = by - ay, uz = bz - az;
        float vx = cx - ax, vy = cy - ay, vz = cz - az;
        float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-9f) return;
        nx /= len; ny /= len; nz /= len;
        float mx = (ax + bx + cx) / 3f - rx, my = (ay + by + cy) / 3f - ry, mz = (az + bz + cz) / 3f - rz;
        if (nx * mx + ny * my + nz * mz < 0) { nx = -nx; ny = -ny; nz = -nz; }
        vertex(ax, ay, az, nx, ny, nz, r, g, b);
        vertex(bx, by, bz, nx, ny, nz, r, g, b);
        vertex(cx, cy, cz, nx, ny, nz, r, g, b);
    }

    void tri(float[] a, float[] b, float[] c, float[] ref, float[] col) {
        tri(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], ref[0], ref[1], ref[2], col[0], col[1], col[2]);
    }

    void quad(float[] a, float[] b, float[] c, float[] d, float[] ref, float[] col) {
        tri(a, b, c, ref, col);
        tri(a, c, d, ref, col);
    }

    void box(float cx, float cy, float cz, float sx, float sy, float sz, float rotY,
             float r, float g, float b) {
        float hx = sx / 2, hy = sy / 2, hz = sz / 2;
        float cs = (float) Math.cos(rotY), sn = (float) Math.sin(rotY);
        float[][] p = new float[8][];
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? -hx : hx;
            float y = (i & 2) == 0 ? -hy : hy;
            float z = (i & 4) == 0 ? -hz : hz;
            p[i] = new float[]{cx + x * cs + z * sn, cy + y, cz - x * sn + z * cs};
        }
        float[] ref = {cx, cy, cz};
        float[] col = {r, g, b};
        quad(p[0], p[1], p[3], p[2], ref, col); // -z
        quad(p[4], p[5], p[7], p[6], ref, col); // +z
        quad(p[0], p[2], p[6], p[4], ref, col); // -x
        quad(p[1], p[3], p[7], p[5], ref, col); // +x
        quad(p[0], p[1], p[5], p[4], ref, col); // -y
        quad(p[2], p[3], p[7], p[6], ref, col); // +y
    }

    /** Smooth-sided (possibly tapered) cylinder standing on y0. r1 = 0 makes a cone. */
    void cylinder(float cx, float y0, float cz, float r0, float r1, float h, int seg,
                  float r, float g, float b, boolean caps) {
        float slope = (r0 - r1) / h;
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0);
            float c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float nl = (float) Math.sqrt(1 + slope * slope);
            float n0x = c0 / nl, n0y = slope / nl, n0z = s0 / nl;
            float n1x = c1 / nl, n1y = slope / nl, n1z = s1 / nl;
            float x00 = cx + c0 * r0, z00 = cz + s0 * r0, x10 = cx + c1 * r0, z10 = cz + s1 * r0;
            float x01 = cx + c0 * r1, z01 = cz + s0 * r1, x11 = cx + c1 * r1, z11 = cz + s1 * r1;
            float y1 = y0 + h;
            vertex(x00, y0, z00, n0x, n0y, n0z, r, g, b);
            vertex(x10, y0, z10, n1x, n1y, n1z, r, g, b);
            vertex(x11, y1, z11, n1x, n1y, n1z, r, g, b);
            if (r1 > 0) {
                vertex(x00, y0, z00, n0x, n0y, n0z, r, g, b);
                vertex(x11, y1, z11, n1x, n1y, n1z, r, g, b);
                vertex(x01, y1, z01, n0x, n0y, n0z, r, g, b);
            }
            if (caps) {
                vertex(cx, y0, cz, 0, -1, 0, r, g, b);
                vertex(x10, y0, z10, 0, -1, 0, r, g, b);
                vertex(x00, y0, z00, 0, -1, 0, r, g, b);
                if (r1 > 0) {
                    vertex(cx, y1, cz, 0, 1, 0, r, g, b);
                    vertex(x01, y1, z01, 0, 1, 0, r, g, b);
                    vertex(x11, y1, z11, 0, 1, 0, r, g, b);
                }
            }
        }
    }

    /** Smooth ellipsoid. */
    void sphere(float cx, float cy, float cz, float rx, float ry, float rz, int seg, int rings,
                float r, float g, float b) {
        for (int j = 0; j < rings; j++) {
            double t0 = Math.PI * j / rings, t1 = Math.PI * (j + 1) / rings;
            for (int i = 0; i < seg; i++) {
                double p0 = 2 * Math.PI * i / seg, p1 = 2 * Math.PI * (i + 1) / seg;
                float[][] u = {
                        unit(t0, p0), unit(t0, p1), unit(t1, p1), unit(t1, p0)
                };
                int[] order = {0, 1, 2, 0, 2, 3};
                for (int k : order) {
                    float[] n = u[k];
                    float nx = n[0] / rx, ny = n[1] / ry, nz = n[2] / rz;
                    float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
                    vertex(cx + n[0] * rx, cy + n[1] * ry, cz + n[2] * rz, nx / l, ny / l, nz / l, r, g, b);
                }
            }
        }
    }

    private static float hash(int a, int b, int seed) {
        int h = a * 374761393 + b * 668265263 + seed * 1274126177;
        h = (h ^ (h >>> 13)) * 1274126177;
        h ^= h >>> 16;
        return (h & 0xffff) / 65535f;
    }

    /**
     * Irregular conifer tier: a cone whose rim radius and height jitter around the
     * circumference and droops at the edge. Smooth normals for soft foliage shading.
     */
    void foliageCone(float cx, float y0, float cz, float r, float h, int seg, int seed,
                     float cr, float cg, float cb) {
        float[] rx = new float[seg], ry = new float[seg], rz = new float[seg];
        for (int i = 0; i < seg; i++) {
            double a = 2 * Math.PI * (i + hash(i, 1, seed) * 0.35f) / seg;
            float rr = r * (0.78f + 0.4f * hash(i, 2, seed));
            rx[i] = cx + (float) Math.cos(a) * rr;
            rz[i] = cz + (float) Math.sin(a) * rr;
            ry[i] = y0 - h * 0.12f * hash(i, 3, seed);
        }
        float ax = cx + (hash(0, 4, seed) - 0.5f) * r * 0.15f, ay = y0 + h, az = cz + (hash(0, 5, seed) - 0.5f) * r * 0.15f;
        for (int i = 0; i < seg; i++) {
            int j = (i + 1) % seg;
            float[] n0 = coneNormal(rx[i] - cx, rz[i] - cz, r, h), n1 = coneNormal(rx[j] - cx, rz[j] - cz, r, h);
            float[] na = normalize3(n0[0] + n1[0], n0[1] + n1[1] + 0.6f, n0[2] + n1[2]);
            float dk = 0.8f;   // darker underneath / at the rim (cheap ambient occlusion)
            vertex(rx[i], ry[i], rz[i], n0[0], n0[1], n0[2], cr * dk, cg * dk, cb * dk);
            vertex(rx[j], ry[j], rz[j], n1[0], n1[1], n1[2], cr * dk, cg * dk, cb * dk);
            vertex(ax, ay, az, na[0], na[1], na[2], cr * 1.12f, cg * 1.12f, cb * 1.12f);
            // underside
            vertex(rx[i], ry[i], rz[i], 0, -1, 0, cr * 0.45f, cg * 0.45f, cb * 0.45f);
            vertex(cx, y0 + h * 0.18f, cz, 0, -1, 0, cr * 0.35f, cg * 0.35f, cb * 0.35f);
            vertex(rx[j], ry[j], rz[j], 0, -1, 0, cr * 0.45f, cg * 0.45f, cb * 0.45f);
        }
    }

    private static float[] coneNormal(float dx, float dz, float r, float h) {
        float l = (float) Math.sqrt(dx * dx + dz * dz);
        return normalize3(dx / l * h, r * 0.9f, dz / l * h);
    }

    private static float[] normalize3(float x, float y, float z) {
        float l = (float) Math.sqrt(x * x + y * y + z * z);
        return new float[]{x / l, y / l, z / l};
    }

    /** A lumpy ellipsoid (broadleaf canopy): radius jitters per vertex, darker underneath. */
    void lumpySphere(float cx, float cy, float cz, float rx, float ry, float rz, int seg, int rings,
                     int seed, float cr, float cg, float cb) {
        float[][][] p = new float[rings + 1][seg][];
        for (int j = 0; j <= rings; j++) {
            double t = Math.PI * j / rings;
            for (int i = 0; i < seg; i++) {
                double ph = 2 * Math.PI * i / seg;
                float k = (j == 0 || j == rings) ? 1f : 0.82f + 0.36f * hash(i, j, seed);
                float ux = (float) (Math.sin(t) * Math.cos(ph)), uy = (float) Math.cos(t), uz = (float) (Math.sin(t) * Math.sin(ph));
                p[j][i] = new float[]{cx + ux * rx * k, cy + uy * ry * k, cz + uz * rz * k, ux, uy, uz};
            }
        }
        for (int j = 0; j < rings; j++) {
            for (int i = 0; i < seg; i++) {
                int i1 = (i + 1) % seg;
                float[][] q = {p[j][i], p[j][i1], p[j + 1][i1], p[j][i], p[j + 1][i1], p[j + 1][i]};
                for (float[] v : q) {
                    float shade = 0.62f + 0.48f * (v[4] * 0.5f + 0.5f);
                    vertex(v[0], v[1], v[2], v[3], v[4], v[5], cr * shade, cg * shade, cb * shade);
                }
            }
        }
    }

    // ------------------------------------------------------------------ foliage cards
    // Foliage vertices store (u, v, flex*9 rounded + brightness/2) in the colour slot; the
    // shader samples the foliage atlas with (u, v) and sways vertices by their flex.

    static final float[] CONIFER = {0.004f, 0.004f, 0.496f, 0.496f};
    static final float[] LEAVES = {0.504f, 0.004f, 0.996f, 0.496f};
    static final float[] GRASS = {0.004f, 0.504f, 0.496f, 0.996f};
    static final float[] REEDS = {0.504f, 0.504f, 0.996f, 0.996f};

    void cardVertex(float x, float y, float z, float nx, float ny, float nz, float u, float v,
                    float bright, float flex) {
        float code = Math.round(Math.max(0f, Math.min(1f, flex)) * 9f) + Math.min(1.98f, bright) / 2f;
        vertex(x, y, z, nx, ny, nz, u, v, code);
    }

    /**
     * A textured quad. Corners p0..p3 map to (u0,v0) (u1,v0) (u1,v1) (u0,v1) of region uv.
     * Normals point away from (cx, cy, cz), tilted up by upBias, for soft rounded foliage light.
     */
    void card(float[] p0, float[] p1, float[] p2, float[] p3, float[] uv, float cx, float cy, float cz,
              float upBias, float bright, float[] flex) {
        float[][] p = {p0, p1, p2, p3};
        float[][] t = {{uv[0], uv[1]}, {uv[2], uv[1]}, {uv[2], uv[3]}, {uv[0], uv[3]}};
        int[] order = {0, 1, 2, 0, 2, 3};
        for (int k : order) {
            float nx = p[k][0] - cx, ny = p[k][1] - cy + upBias, nz = p[k][2] - cz;
            float l = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (l < 1e-4f) { nx = 0; ny = 1; nz = 0; l = 1; }
            cardVertex(p[k][0], p[k][1], p[k][2], nx / l, ny / l, nz / l, t[k][0], t[k][1], bright, flex[k]);
        }
    }

    private static float[] unit(double theta, double phi) {
        return new float[]{(float) (Math.sin(theta) * Math.cos(phi)), (float) Math.cos(theta),
                (float) (Math.sin(theta) * Math.sin(phi))};
    }

    Mesh build() {
        return new Mesh(data, vertices, null);
    }
}
