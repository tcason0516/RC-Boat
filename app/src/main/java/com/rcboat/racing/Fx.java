package com.rcboat.racing;

/**
 * RC Boat Combat: coloured glowing and smoky particles (fire, smoke, sparks, explosions,
 * muzzle flashes), drawn as camera-facing soft discs. Glowing ones are added to the scene's
 * light; smoke is blended normally.
 */
final class Fx {
    final int max;
    final float[] x, y, z, vx, vy, vz, age, life, s0, s1, r, g, b, a0, lift;
    final boolean[] glow;
    int count;

    Fx(int max) {
        this.max = max;
        x = new float[max]; y = new float[max]; z = new float[max];
        vx = new float[max]; vy = new float[max]; vz = new float[max];
        age = new float[max]; life = new float[max]; s0 = new float[max]; s1 = new float[max];
        r = new float[max]; g = new float[max]; b = new float[max]; a0 = new float[max];
        lift = new float[max];
        glow = new boolean[max];
    }

    void clear() { count = 0; }

    /**
     * lift: vertical acceleration (+ rises like hot smoke, - falls like sparks).
     */
    void emit(float px, float py, float pz, float pvx, float pvy, float pvz, float lifeS,
              float size0, float size1, float cr, float cg, float cb, float a, float lf, boolean isGlow) {
        if (count >= max) return;
        int i = count++;
        x[i] = px; y[i] = py; z[i] = pz;
        vx[i] = pvx; vy[i] = pvy; vz[i] = pvz;
        age[i] = 0; life[i] = lifeS;
        s0[i] = size0; s1[i] = size1;
        r[i] = cr; g[i] = cg; b[i] = cb; a0[i] = a;
        lift[i] = lf;
        glow[i] = isGlow;
    }

    void update(float dt) {
        float drag = (float) Math.exp(-1.8f * dt);
        for (int i = 0; i < count; i++) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                int l = --count;
                x[i] = x[l]; y[i] = y[l]; z[i] = z[l]; vx[i] = vx[l]; vy[i] = vy[l]; vz[i] = vz[l];
                age[i] = age[l]; life[i] = life[l]; s0[i] = s0[l]; s1[i] = s1[l];
                r[i] = r[l]; g[i] = g[l]; b[i] = b[l]; a0[i] = a0[l]; lift[i] = lift[l]; glow[i] = glow[l];
                i--;
                continue;
            }
            vx[i] *= drag; vz[i] *= drag;
            vy[i] = vy[i] * drag + lift[i] * dt;
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt;
        }
    }

    float sizeOf(int i) {
        float f = age[i] / life[i];
        return s0[i] + (s1[i] - s0[i]) * (float) Math.sqrt(f);
    }

    float alphaOf(int i) {
        float f = age[i] / life[i];
        return a0[i] * Math.min(1f, age[i] / 0.04f) * (1f - f) * (1f - f * 0.3f);
    }

    /** Fire fades from yellow-white through orange to dark red as it ages. */
    float[] colorOf(int i, float[] out) {
        float f = age[i] / life[i];
        if (glow[i] && r[i] > 0.9f && g[i] > 0.4f && b[i] < 0.5f) {   // flame colours
            out[0] = r[i];
            out[1] = g[i] * (1f - f * 0.75f);
            out[2] = b[i] * (1f - f);
        } else {
            out[0] = r[i]; out[1] = g[i]; out[2] = b[i];
        }
        return out;
    }
}
