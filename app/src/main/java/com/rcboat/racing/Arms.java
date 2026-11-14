package com.rcboat.racing;

import android.content.res.AssetManager;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The driver's arms in the life-size cockpit: an anatomical arm and hand (MakeHuman base
 * mesh, CC0) posed gripping the wheel, baked by tools/arms/build_arms.py into
 * assets/arms.bin as one pose per wheel angle (-60..60 degrees). Each frame the two poses
 * either side of the wheel angle are blended and coloured: suit in the team colour, a dark
 * knitted cuff and gauntlet gloves with darker palms.
 */
final class Arms {
    private static final int SUIT = 0, CUFF = 2, GLOVE = 3, PALM = 4;

    private final float[] angles;
    final Mesh[] meshes;
    private final float[][][] poses;   // [arm][pose][vertex * 6] position, normal
    private final byte[][] mats;
    private final float[] buf;

    private Arms(float[] angles, short[][] idx, byte[][] mats, float[][][] poses) {
        this.angles = angles;
        this.mats = mats;
        this.poses = poses;
        int most = 0;
        for (byte[] m : mats) most = Math.max(most, m.length);
        buf = new float[most * Mesh.FLOATS];
        meshes = new Mesh[idx.length];
        for (int a = 0; a < idx.length; a++) {
            int nv = mats[a].length;
            fill(a, 0, 0, 0f, new float[]{0.5f, 0.1f, 0.1f});
            meshes[a] = new Mesh(buf, nv, idx[a], true);
        }
    }

    static Arms load(AssetManager assets) throws IOException {
        byte[] all;
        try (InputStream in = assets.open("arms.bin")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(1 << 21);
            byte[] b = new byte[1 << 16];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            all = out.toByteArray();
        }
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int nPose = bb.getInt(), nArm = bb.getInt();
        bb.getInt();
        bb.getInt();
        float[] angles = new float[nPose];
        for (int i = 0; i < nPose; i++) angles[i] = bb.getFloat();
        short[][] idx = new short[nArm][];
        byte[][] mats = new byte[nArm][];
        float[][][] poses = new float[nArm][nPose][];
        for (int a = 0; a < nArm; a++) {
            int nv = bb.getInt(), ni = bb.getInt();
            idx[a] = new short[ni];
            for (int i = 0; i < ni; i++) idx[a][i] = (short) bb.getChar();
            mats[a] = new byte[nv];
            bb.get(mats[a]);
            while (bb.position() % 4 != 0) bb.get();
            for (int p = 0; p < nPose; p++) {
                float[] v = new float[nv * 6];
                bb.asFloatBuffer().get(v);
                bb.position(bb.position() + v.length * 4);
                poses[a][p] = v;
            }
        }
        return new Arms(angles, idx, mats, poses);
    }

    /** Poses both arms for a wheel angle (degrees) and colours the suit. */
    void pose(float wheelAngle, float[] team) {
        int last = angles.length - 1;
        float w = Math.max(angles[0], Math.min(angles[last], wheelAngle));
        int p = 0;
        while (p < last - 1 && w > angles[p + 1]) p++;
        float t = (w - angles[p]) / (angles[p + 1] - angles[p]);
        for (int a = 0; a < meshes.length; a++) {
            fill(a, p, p + 1, t, team);
            meshes[a].update(buf, mats[a].length);
        }
    }

    private void fill(int a, int p0, int p1, float t, float[] team) {
        float[] A = poses[a][p0], B = poses[a][p1];
        byte[] m = mats[a];
        // fire suit: a deep shade of the team colour (Nomex isn't glossy plastic)
        float sr = 0.05f + team[0] * 0.55f, sg = 0.05f + team[1] * 0.55f, sb = 0.06f + team[2] * 0.55f;
        float u = 1f - t;
        for (int v = 0, o = 0, k = 0; v < m.length; v++, k += 6) {
            buf[o++] = A[k] * u + B[k] * t;
            buf[o++] = A[k + 1] * u + B[k + 1] * t;
            buf[o++] = A[k + 2] * u + B[k + 2] * t;
            buf[o++] = A[k + 3] * u + B[k + 3] * t;
            buf[o++] = A[k + 4] * u + B[k + 4] * t;
            buf[o++] = A[k + 5] * u + B[k + 5] * t;
            switch (m[v]) {
                case CUFF: buf[o++] = 0.12f; buf[o++] = 0.12f; buf[o++] = 0.13f; break;
                case GLOVE: buf[o++] = 0.24f; buf[o++] = 0.24f; buf[o++] = 0.26f; break;
                case PALM: buf[o++] = 0.07f; buf[o++] = 0.07f; buf[o++] = 0.08f; break;
                default: buf[o++] = sr; buf[o++] = sg; buf[o++] = sb; break;
            }
        }
    }
}
