package com.rcboat.racing;

/** Foam particles for boat wakes and spray. Flat discs lying on the water. */
final class Particles {
    final int max;
    final float[] x, z, vx, vz, age, life, size0, size1, alpha0;
    int count;

    Particles(int max) {
        this.max = max;
        x = new float[max];
        z = new float[max];
        vx = new float[max];
        vz = new float[max];
        age = new float[max];
        life = new float[max];
        size0 = new float[max];
        size1 = new float[max];
        alpha0 = new float[max];
    }

    void clear() { count = 0; }

    void emit(float px, float pz, float pvx, float pvz, float lifeS, float s0, float s1, float a) {
        int i;
        if (count < max) {
            i = count++;
        } else {
            // replace the oldest-looking particle
            i = 0;
            float best = -1;
            for (int k = 0; k < count; k++) {
                float f = age[k] / life[k];
                if (f > best) { best = f; i = k; }
            }
        }
        x[i] = px;
        z[i] = pz;
        vx[i] = pvx;
        vz[i] = pvz;
        age[i] = 0;
        life[i] = lifeS;
        size0[i] = s0;
        size1[i] = s1;
        alpha0[i] = a;
    }

    void update(float dt) {
        for (int i = 0; i < count; i++) {
            age[i] += dt;
            if (age[i] >= life[i]) {
                int last = --count;
                x[i] = x[last]; z[i] = z[last]; vx[i] = vx[last]; vz[i] = vz[last];
                age[i] = age[last]; life[i] = life[last]; size0[i] = size0[last];
                size1[i] = size1[last]; alpha0[i] = alpha0[last];
                i--;
                continue;
            }
            float damp = (float) Math.exp(-1.5f * dt);
            vx[i] *= damp;
            vz[i] *= damp;
            x[i] += vx[i] * dt;
            z[i] += vz[i] * dt;
        }
    }

    float sizeOf(int i) {
        float f = age[i] / life[i];
        return size0[i] + (size1[i] - size0[i]) * (float) Math.sqrt(f);
    }

    float alphaOf(int i) {
        float f = age[i] / life[i];
        float fadeIn = Math.min(1f, age[i] / 0.08f);
        return alpha0[i] * (1f - f) * fadeIn;
    }
}
