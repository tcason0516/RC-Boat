package com.rcboat.racing;

/**
 * RC Boat Combat: meshes for the weapons, what they fire and the crates. Built at RC scale
 * (metres, forward = -z, base of the mount at the origin) and scaled up for the life-size
 * boats.
 */
final class CombatArt {
    /** Per weapon: the fixed base and the part that turns (turrets) or spins (drill). */
    final Mesh[] base = new Mesh[Weapons.COUNT], turret = new Mesh[Weapons.COUNT];
    final Mesh torpedo, rocket, harpoon, mine, shield, cable;
    final Mesh[] crate = new Mesh[Weapons.PU_COUNT];

    private static final float[] GUN = {0.16f, 0.17f, 0.19f}, DARK = {0.07f, 0.07f, 0.08f},
            STEEL = {0.55f, 0.56f, 0.6f}, BRASS = {0.75f, 0.6f, 0.25f}, RED = {0.8f, 0.12f, 0.08f},
            ORANGE = {1f, 0.45f, 0.08f}, OLIVE = {0.28f, 0.33f, 0.2f}, BLUE = {0.15f, 0.4f, 0.8f},
            WHITE = {0.95f, 0.95f, 0.95f}, YELLOW = {0.95f, 0.8f, 0.15f};

    CombatArt() {
        for (int w = 0; w < Weapons.COUNT; w++) {
            MeshBuilder b = new MeshBuilder(), t = new MeshBuilder();
            buildWeapon(w, b, t);
            base[w] = b.vertices > 0 ? b.build() : null;
            turret[w] = t.vertices > 0 ? t.build() : null;
        }
        MeshBuilder mb = new MeshBuilder();
        // torpedo: grey cylinder, red warhead, cross fins and a prop
        tubeZ(mb, 0, 0, -0.16f, 0.16f, 0.028f, 0.028f, 10, STEEL);
        coneZ(mb, 0, 0, -0.16f, -0.21f, 0.028f, 10, RED);
        for (int i = 0; i < 4; i++) {
            float a = (float) (i * Math.PI / 2);
            mb.box((float) Math.cos(a) * 0.035f, (float) Math.sin(a) * 0.035f, 0.14f,
                    i % 2 == 0 ? 0.03f : 0.006f, i % 2 == 0 ? 0.006f : 0.03f, 0.05f, 0, DARK[0], DARK[1], DARK[2]);
        }
        mb.box(0, 0, 0.17f, 0.05f, 0.008f, 0.01f, 0, BRASS[0], BRASS[1], BRASS[2]);
        torpedo = mb.build();
        mb = new MeshBuilder();
        tubeZ(mb, 0, 0, -0.07f, 0.08f, 0.014f, 0.014f, 8, WHITE);
        coneZ(mb, 0, 0, -0.07f, -0.11f, 0.014f, 8, RED);
        for (int i = 0; i < 4; i++) {
            float a = (float) (i * Math.PI / 2 + Math.PI / 4);
            mb.box((float) Math.cos(a) * 0.02f, (float) Math.sin(a) * 0.02f, 0.065f, 0.012f, 0.012f, 0.03f, 0,
                    DARK[0], DARK[1], DARK[2]);
        }
        rocket = mb.build();
        mb = new MeshBuilder();
        tubeZ(mb, 0, 0, -0.12f, 0.16f, 0.006f, 0.006f, 6, STEEL);
        coneZ(mb, 0, 0, -0.12f, -0.17f, 0.016f, 6, DARK);
        for (int s = -1; s <= 1; s += 2) mb.box(s * 0.012f, 0, -0.1f, 0.018f, 0.004f, 0.03f, 0, DARK[0], DARK[1], DARK[2]);
        mb.box(0, 0, 0.15f, 0.03f, 0.03f, 0.02f, 0, RED[0], RED[1], RED[2]);
        harpoon = mb.build();
        mb = new MeshBuilder();
        // floating mine: dark sphere, spikes, a red light on top
        mb.sphere(0, 0.03f, 0, 0.11f, 0.1f, 0.11f, 12, 7, 0.12f, 0.12f, 0.13f);
        for (int i = 0; i < 8; i++) {
            double a = i * Math.PI / 4;
            float cx = (float) Math.cos(a) * 0.11f, cz = (float) Math.sin(a) * 0.11f;
            mb.box(cx, 0.05f, cz, 0.022f, 0.022f, 0.022f, (float) a, 0.35f, 0.35f, 0.37f);
        }
        mb.box(0, 0.14f, 0, 0.02f, 0.03f, 0.02f, 0, 0.3f, 0.3f, 0.3f);
        mb.sphere(0, 0.165f, 0, 0.018f, 0.018f, 0.018f, 6, 4, 1f, 0.1f, 0.05f);
        mine = mb.build();
        mb = new MeshBuilder();
        mb.sphere(0, 0, 0, 1f, 1f, 1f, 24, 12, 0.45f, 0.8f, 1f);
        shield = mb.build();
        mb = new MeshBuilder();
        // unit cable segment along -z (scaled to length)
        mb.box(0, 0, -0.5f, 0.012f, 0.012f, 1f, 0, 0.1f, 0.1f, 0.1f);
        cable = mb.build();
        for (int k = 0; k < Weapons.PU_COUNT; k++) {
            mb = new MeshBuilder();
            float[] c = Weapons.PU_COLOR[k];
            // floating crate: wooden box with a coloured band and a white marking on each side
            mb.box(0, 0.09f, 0, 0.26f, 0.2f, 0.26f, 0, 0.55f, 0.42f, 0.25f);
            mb.box(0, 0.09f, 0, 0.27f, 0.07f, 0.27f, 0, c[0], c[1], c[2]);
            mb.box(0, 0.195f, 0, 0.2f, 0.012f, 0.2f, 0, c[0], c[1], c[2]);
            mb.box(0, 0.203f, 0, 0.12f, 0.01f, 0.035f, 0, WHITE[0], WHITE[1], WHITE[2]);
            if (k == Weapons.PU_REPAIR) mb.box(0, 0.203f, 0, 0.035f, 0.01f, 0.12f, 0, WHITE[0], WHITE[1], WHITE[2]);
            // a beacon pole and light so it can be seen from a distance
            mb.box(0.1f, 0.3f, 0.1f, 0.012f, 0.22f, 0.012f, 0, 0.2f, 0.2f, 0.2f);
            mb.sphere(0.1f, 0.42f, 0.1f, 0.03f, 0.03f, 0.03f, 8, 5, Math.min(1f, c[0] + 0.3f),
                    Math.min(1f, c[1] + 0.3f), Math.min(1f, c[2] + 0.3f));
            crate[k] = mb.build();
        }
    }

    private static void buildWeapon(int w, MeshBuilder b, MeshBuilder t) {
        switch (w) {
            case Weapons.MACHINE_GUN:
                b.cylinder(0, 0, 0, 0.055f, 0.05f, 0.025f, 12, GUN[0], GUN[1], GUN[2], true);
                t.box(0, 0.05f, 0.005f, 0.075f, 0.05f, 0.085f, 0, OLIVE[0], OLIVE[1], OLIVE[2]);
                t.box(0, 0.08f, 0.02f, 0.05f, 0.012f, 0.05f, 0, OLIVE[0], OLIVE[1], OLIVE[2]);
                for (int s = -1; s <= 1; s += 2) {
                    tubeZ(t, s * 0.018f, 0.055f, -0.16f, -0.03f, 0.007f, 0.007f, 6, DARK);
                    tubeZ(t, s * 0.018f, 0.055f, -0.175f, -0.16f, 0.01f, 0.01f, 6, GUN);
                }
                t.box(0.045f, 0.05f, 0.01f, 0.02f, 0.03f, 0.04f, 0, BRASS[0], BRASS[1], BRASS[2]);
                break;
            case Weapons.FLAMETHROWER:
                // fuel tanks on the deck, nozzle on a short swivel
                for (int s = -1; s <= 1; s += 2)
                    tubeZ(b, s * 0.04f, 0.035f, 0.02f, 0.16f, 0.03f, 0.03f, 10, RED);
                b.box(0, 0.035f, 0.17f, 0.11f, 0.012f, 0.012f, 0, DARK[0], DARK[1], DARK[2]);
                t.cylinder(0, 0, 0, 0.03f, 0.025f, 0.05f, 10, GUN[0], GUN[1], GUN[2], true);
                tubeZ(t, 0, 0.045f, -0.16f, 0.0f, 0.012f, 0.016f, 8, STEEL);
                tubeZ(t, 0, 0.045f, -0.18f, -0.16f, 0.02f, 0.015f, 8, DARK);
                t.sphere(0, 0.03f, -0.175f, 0.008f, 0.008f, 0.008f, 6, 4, 0.4f, 0.7f, 1f);
                break;
            case Weapons.DRILL:
                // braced mount on the bow; the spinning bit points ahead
                b.box(0, 0.02f, 0.02f, 0.07f, 0.04f, 0.1f, 0, GUN[0], GUN[1], GUN[2]);
                for (int s = -1; s <= 1; s += 2)
                    b.box(s * 0.035f, 0.0f, -0.06f, 0.01f, 0.02f, 0.13f, 0, YELLOW[0], YELLOW[1], YELLOW[2]);
                tubeZ(t, 0, 0, -0.06f, 0.0f, 0.03f, 0.03f, 10, YELLOW);
                // the bit: a cone with spiral flutes
                coneZ(t, 0, 0, -0.06f, -0.27f, 0.034f, 12, STEEL);
                for (int i = 0; i < 10; i++) {
                    float f = i / 10f, z = -0.07f - f * 0.18f, r = 0.034f * (1f - f) + 0.004f;
                    double a = f * Math.PI * 5;
                    t.box((float) Math.cos(a) * r, (float) Math.sin(a) * r, z, 0.012f, 0.012f, 0.02f, 0,
                            DARK[0], DARK[1], DARK[2]);
                }
                break;
            case Weapons.TORPEDO:
                b.box(0, 0.015f, 0.02f, 0.13f, 0.03f, 0.12f, 0, GUN[0], GUN[1], GUN[2]);
                for (int s = -1; s <= 1; s += 2) {
                    tubeZ(t, s * 0.04f, 0.055f, -0.16f, 0.08f, 0.026f, 0.026f, 10, OLIVE);
                    tubeZ(t, s * 0.04f, 0.055f, -0.165f, -0.16f, 0.019f, 0.019f, 10, DARK);
                }
                break;
            case Weapons.HARPOON:
                b.cylinder(0, 0, 0, 0.045f, 0.04f, 0.03f, 10, GUN[0], GUN[1], GUN[2], true);
                t.box(0, 0.05f, 0.02f, 0.03f, 0.045f, 0.12f, 0, STEEL[0], STEEL[1], STEEL[2]);
                tubeZ(t, 0, 0.07f, -0.17f, 0.04f, 0.009f, 0.009f, 8, DARK);
                // bow limbs and the loaded harpoon
                t.box(0, 0.07f, -0.12f, 0.16f, 0.008f, 0.01f, 0, DARK[0], DARK[1], DARK[2]);
                tubeZ(t, 0, 0.085f, -0.2f, 0.0f, 0.004f, 0.004f, 6, STEEL);
                coneZ(t, 0, 0.085f, -0.2f, -0.23f, 0.01f, 6, DARK);
                t.cylinder(0, 0.02f, 0.07f, 0.03f, 0.03f, 0.03f, 10, BRASS[0], BRASS[1], BRASS[2], true);
                break;
            case Weapons.MINES:
                // a rack at the stern with mines ready to roll off the back
                b.box(0, 0.01f, 0, 0.2f, 0.02f, 0.14f, 0, GUN[0], GUN[1], GUN[2]);
                for (int s = -1; s <= 1; s += 2) b.box(s * 0.1f, 0.05f, 0, 0.012f, 0.08f, 0.14f, 0, YELLOW[0], YELLOW[1], YELLOW[2]);
                for (int i = -1; i <= 1; i++) {
                    b.sphere(i * 0.062f, 0.06f, 0, 0.032f, 0.032f, 0.032f, 8, 5, 0.12f, 0.12f, 0.13f);
                    b.sphere(i * 0.062f, 0.095f, 0, 0.006f, 0.006f, 0.006f, 4, 3, 1f, 0.1f, 0.05f);
                }
                break;
            case Weapons.WATER_CANNON:
                b.cylinder(0, 0, 0, 0.05f, 0.045f, 0.03f, 10, GUN[0], GUN[1], GUN[2], true);
                b.sphere(0, 0.045f, 0.12f, 0.05f, 0.04f, 0.06f, 10, 6, BLUE[0], BLUE[1], BLUE[2]);
                t.box(0, 0.055f, 0.0f, 0.06f, 0.05f, 0.07f, 0, WHITE[0], WHITE[1], WHITE[2]);
                tubeZ(t, 0, 0.065f, -0.15f, -0.02f, 0.016f, 0.022f, 10, STEEL);
                tubeZ(t, 0, 0.065f, -0.17f, -0.15f, 0.024f, 0.016f, 10, BLUE);
                break;
            case Weapons.ROCKETS:
                b.cylinder(0, 0, 0, 0.045f, 0.04f, 0.035f, 10, GUN[0], GUN[1], GUN[2], true);
                t.box(0, 0.08f, -0.01f, 0.1f, 0.075f, 0.14f, 0, OLIVE[0], OLIVE[1], OLIVE[2]);
                for (int r = 0; r < 2; r++)
                    for (int c = 0; c < 2; c++)
                        tubeZ(t, (c - 0.5f) * 0.045f, 0.06f + r * 0.04f, -0.085f, -0.07f, 0.014f, 0.014f, 8, DARK);
                break;
        }
    }

    /** A tube along z from z0 to z1 (radius r0 at z0, r1 at z1), centred on (cx, cy). */
    static void tubeZ(MeshBuilder mb, float cx, float cy, float z0, float z1, float r0, float r1, int seg,
                      float[] col) {
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float[][] p = {{cx + c0 * r0, cy + s0 * r0, z0, c0, s0}, {cx + c1 * r0, cy + s1 * r0, z0, c1, s1},
                    {cx + c1 * r1, cy + s1 * r1, z1, c1, s1}, {cx + c0 * r1, cy + s0 * r1, z1, c0, s0}};
            int[] o = {0, 1, 2, 0, 2, 3};
            for (int k : o) mb.vertex(p[k][0], p[k][1], p[k][2], p[k][3], p[k][4], 0, col[0], col[1], col[2]);
            // end caps
            mb.vertex(cx, cy, z0, 0, 0, Math.signum(z0 - z1), col[0], col[1], col[2]);
            mb.vertex(p[1][0], p[1][1], z0, 0, 0, Math.signum(z0 - z1), col[0], col[1], col[2]);
            mb.vertex(p[0][0], p[0][1], z0, 0, 0, Math.signum(z0 - z1), col[0], col[1], col[2]);
            mb.vertex(cx, cy, z1, 0, 0, Math.signum(z1 - z0), col[0], col[1], col[2]);
            mb.vertex(p[3][0], p[3][1], z1, 0, 0, Math.signum(z1 - z0), col[0], col[1], col[2]);
            mb.vertex(p[2][0], p[2][1], z1, 0, 0, Math.signum(z1 - z0), col[0], col[1], col[2]);
        }
    }

    /** A cone along z: base radius r at z0, point at z1. */
    static void coneZ(MeshBuilder mb, float cx, float cy, float z0, float z1, float r, int seg, float[] col) {
        float len = Math.abs(z1 - z0);
        float nz = Math.signum(z1 - z0) * r / (float) Math.hypot(r, len), nr = len / (float) Math.hypot(r, len);
        for (int i = 0; i < seg; i++) {
            double a0 = 2 * Math.PI * i / seg, a1 = 2 * Math.PI * (i + 1) / seg;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            mb.vertex(cx + c0 * r, cy + s0 * r, z0, c0 * nr, s0 * nr, nz, col[0], col[1], col[2]);
            mb.vertex(cx + c1 * r, cy + s1 * r, z0, c1 * nr, s1 * nr, nz, col[0], col[1], col[2]);
            mb.vertex(cx, cy, z1, (c0 + c1) * 0.5f * nr, (s0 + s1) * 0.5f * nr, nz, col[0], col[1], col[2]);
        }
    }

    // ---- where the weapons sit on each hull (boat coordinates, metres) ----

    /** Mount point for hull h: x, y, z and the scale the RC-sized weapon is drawn at. */
    static float[] mount(int hull, int weapon) {
        boolean rear = weapon == Weapons.MINES;
        if (hull == Boat.UNLIMITED) {
            // on the right sponson's deck, clear of the driver's view; mines at the transom
            if (rear) return new float[]{0f, 0.72f, 4.45f, 6f};
            if (weapon == Weapons.DRILL) return new float[]{0f, 0.55f, -4.5f, 7f};
            return new float[]{1.6f, 0.86f, -3.1f, 6f};
        }
        float[] fy = {0.21f, 0.17f, 0.21f, 0.23f}, ry = {0.26f, 0.17f, 0.2f, 0.3f};
        int h = Math.max(0, Math.min(3, hull));
        // drawn a size up from true scale so they read clearly from the chase camera
        if (rear) return new float[]{0f, ry[h], 0.47f, 1.5f};
        if (weapon == Weapons.DRILL) return new float[]{0f, fy[h] - 0.08f, -0.6f, 1.4f};
        return new float[]{0f, fy[h], -0.34f, 1.6f};
    }
}
