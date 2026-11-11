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

    private static float[] unit(double theta, double phi) {
        return new float[]{(float) (Math.sin(theta) * Math.cos(phi)), (float) Math.cos(theta),
                (float) (Math.sin(theta) * Math.sin(phi))};
    }

    Mesh build() {
        return new Mesh(data, vertices, null);
    }
}
