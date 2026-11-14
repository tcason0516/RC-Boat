package com.rcboat.racing;

/**
 * Airborne water: the rooster tail thrown up behind each boat's propeller. Droplet clumps
 * fly ballistically, spread and fade, and vanish when they fall back to the water.
 */
final class Spray {
    static final float GRAVITY = 9.8f;
    final int max;
    final float[] x, y, z, vx, vy, vz, age, life, size0, size1, alpha0;
    int count;

    Spray(int max) {
        this.max = max;
        x = new float[max]; y = new float[max]; z = new float[max];
        vx = new float[max]; vy = new float[max]; vz = new float[max];
        age = new float[max]; life = new float[max];
        size0 = new float[max]; size1 = new float[max]; alpha0 = new float[max];
    }

    void clear() { count = 0; }

    void emit(float px, float py, float pz, float pvx, float pvy, float pvz,
              float lifeS, float s0, float s1, float a) {
        if (count >= max) return;   // a full pool just skips: the tail stays continuous anyway
        int i = count++;
        x[i] = px; y[i] = py; z[i] = pz;
        vx[i] = pvx; vy[i] = pvy; vz[i] = pvz;
        age[i] = 0; life[i] = lifeS;
        size0[i] = s0; size1[i] = s1; alpha0[i] = a;
    }

    void update(float dt) {
        float drag = (float) Math.exp(-0.9f * dt);
        for (int i = 0; i < count; i++) {
            age[i] += dt;
            vy[i] -= GRAVITY * dt;
            vx[i] *= drag; vz[i] *= drag;
            x[i] += vx[i] * dt; y[i] += vy[i] * dt; z[i] += vz[i] * dt;
            if (age[i] >= life[i] || (y[i] < 0f && vy[i] < 0f)) {
                int last = --count;
                x[i] = x[last]; y[i] = y[last]; z[i] = z[last];
                vx[i] = vx[last]; vy[i] = vy[last]; vz[i] = vz[last];
                age[i] = age[last]; life[i] = life[last];
                size0[i] = size0[last]; size1[i] = size1[last]; alpha0[i] = alpha0[last];
                i--;
            }
        }
    }

    float sizeOf(int i) {
        float f = age[i] / life[i];
        return size0[i] + (size1[i] - size0[i]) * f;
    }

    float alphaOf(int i) {
        float f = age[i] / life[i];
        return alpha0[i] * Math.min(1f, age[i] / 0.05f) * (1f - f * f);
    }
}
