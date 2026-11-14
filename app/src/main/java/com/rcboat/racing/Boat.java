package com.rcboat.racing;

import java.util.Random;

/** One RC boat: arcade physics, AI driver and race progress. */
final class Boat {
    static final float RADIUS = 0.6f;
    static final float ACCEL = 7.5f;
    static final float BASE_MAX_SPEED = 12.5f;  // m/s (~28 mph)
    static final float REVERSE_MAX = 3.5f;
    static final float TURN_RATE = 2.3f;        // rad/s at full lock

    // Hull types: name, top speed (m/s), acceleration, turn rate, side grip
    static final int V_HULL = 0, HYDROPLANE = 1, OUTRIGGER = 2, CATAMARAN = 3, UNLIMITED = 4;
    static final String[] HULL_NAMES = {"V-Hull", "Hydroplane", "Outrigger", "Catamaran", "Unlimited"};
    static final String[] HULL_INFO = {"Mono: balanced all-rounder", "Fastest, slides in turns",
            "Grippy, sharp turns", "Tunnel hull: fast, stable, wide turns",
            "Life-size turbine Unlimited hydroplane"};
    /** The driver's eyes in the life-size boat (boat coordinates, forward = -z). */
    static final float[] LIFE_EYE = {0f, 1.36f, -2.0f};
    /** RC hulls the menus offer (the Unlimited is life-size only). */
    static final int RC_HULLS = 4;
    private static final float[][] HULL_STATS = {
            {12.8f, 7.6f, 2.30f, 3.2f},
            {13.2f, 8.0f, 1.85f, 1.6f},
            {13.0f, 7.1f, 2.60f, 4.8f},
            {13.3f, 7.4f, 2.05f, 3.8f},
            // Unlimited: ~175 mph flat out, 0-100 mph in about 5 s, 600 ft turns at speed
            {78f, 9.5f, 0.62f, 1.3f},
    };

    final int id;
    final String name;
    final float r, g, b;
    final boolean isPlayer;
    final int hull;
    float topSpeed, accel;
    final float turnRate, grip;
    /** Throttle response time (s): gas engines take a moment to rev up, electric is instant. */
    float throttleLag = 0.05f;
    float throttleNow;         // throttle after the engine's response

    // physics
    float x, z, heading;
    float vx, vz;
    float yawRate;
    float throttle, steer;     // inputs, -1..1
    float maxSpeed;
    float speedMul = 1f;       // AI skill / rubber band

    // visuals
    float pitch, roll, y;
    float wakeTimer;
    float sprayTimer;
    float bobPhase;

    // AI
    boolean autopilot;
    float skill = 1f;
    float lane, laneTarget, laneTimer;
    float stuckTimer;

    // race progress
    int trackIdx;
    int lapWrap;
    float progress;
    boolean finished;
    float finishTime;
    float offCourseTime;
    float wrongWayTime;
    float lastAlong;

    // rules
    int penaltyLaps;           // laps added by penalties
    float penaltyTime;         // seconds added to the finish time by penalties
    int infractions;           // buoys hit or cut, jump starts
    String lastInfraction = "";
    float infractionCooldown;
    float lastCrossClock = -1; // clock-start: clock reading when the start line was last crossed
    /** Clock-start pacing for the AI: average speed to hold (m/s), or -1 for none. */
    float paceSpeed = -1;
    float startDelay;          // AI: how late after zero it aims to hit the line

    // lap timing (for records)
    int lapsDone = -1;         // completed laps; -1 until the start line is first crossed
    float lapStartTime;
    float bestLap;             // 0 = none yet
    /** Timed races: the score (distance) at which this boat takes the checkered flag. */
    float finishTarget = -1;

    // speed runs: time entering the trap, and the best speed (mph)
    float trapEnterTime = -1;
    float bestSpeedMph, lastSpeedMph;

    // noise rule (gas): engine noise offset (dB) for this boat's tune, and its status
    float noiseTune;
    float lastNoiseDb;
    boolean disqualified;
    String dqReason = "";
    /** Drawn larger for Super Boats. */
    float sizeScale = 1f;
    /** Collision radius (m). */
    float radius = RADIUS;
    /** Scale of wakes, spray and spacing: 1 for RC boats, ~7.5 for the life-size boat. */
    float fx = 1f;
    /** Banking into turns. */
    float rollK = 0.022f;
    /** Linear water drag (1/s); the planing Unlimited barely touches the water. */
    float waterDrag = 0.12f;
    /** How much the nose lifts with speed. */
    float pitchK = 1f;
    Boat(int id, String name, float[] color, boolean isPlayer, int hull, Random rnd) {
        this.id = id;
        this.hull = hull;
        float[] st = HULL_STATS[hull];
        this.topSpeed = st[0];
        this.accel = st[1];
        this.turnRate = st[2];
        this.grip = st[3];
        this.maxSpeed = topSpeed;
        if (hull == UNLIMITED) {
            radius = 3.0f;
            fx = 7.5f;
            rollK = 0.0035f;
            waterDrag = 0.012f;
            pitchK = 0.35f;
            throttleLag = 0.7f;   // turbines spool up slowly
        }
        this.name = name;
        this.r = color[0];
        this.g = color[1];
        this.b = color[2];
        this.isPlayer = isPlayer;
        this.bobPhase = rnd.nextFloat() * 6.28f;
    }

    float fwdX() { return (float) -Math.sin(heading); }
    float fwdZ() { return (float) -Math.cos(heading); }
    float rightX() { return (float) Math.cos(heading); }
    float rightZ() { return (float) -Math.sin(heading); }

    float forwardSpeed() { return vx * fwdX() + vz * fwdZ(); }

    float speed() { return (float) Math.sqrt(vx * vx + vz * vz); }

    void placeAt(Track t, int idx, float lateral) {
        trackIdx = idx;
        x = t.px[idx] + t.nx(idx) * lateral;
        z = t.pz[idx] + t.nz(idx) * lateral;
        heading = t.headingAt(idx);
        vx = vz = 0;
        yawRate = 0;
        throttle = steer = 0;
        throttleNow = 0;
        offCourseTime = 0;
        wrongWayTime = 0;
        stuckTimer = 0;
    }

    /** AI: compute throttle/steer to follow the course. */
    void think(Track t, Boat[] all, float dt, Random rnd) {
        laneTimer -= dt;
        if (laneTimer <= 0) {
            laneTimer = 2f + rnd.nextFloat() * 4f;
            laneTarget = t.laneMin + rnd.nextFloat() * (t.laneMax - t.laneMin);
        }
        lane += (laneTarget - lane) * Math.min(1f, dt * 0.8f);

        float spd = Math.max(0f, forwardSpeed());
        float look = t.impba ? 4f + spd * 0.4f : 6f + spd * 0.55f;
        int ti = t.wrap(trackIdx + Math.round(look / t.ds));
        float aim = lane;
        if (t.impba) {
            // aiming at a point ahead cuts the corner; on the ovals that would clip the
            // turn buoys, so aim wide by the chord's sag (look * angle / 8) and a margin
            float dot = clamp(t.tx[trackIdx] * t.tx[ti] + t.tz[trackIdx] * t.tz[ti], -1f, 1f);
            float ang = (float) Math.acos(dot);
            aim -= t.insideSign * (look * ang / 8f * 1.6f + ang * 1.7f * fx);
        }
        float tgx = t.px[ti] + t.nx(ti) * aim;
        float tgz = t.pz[ti] + t.nz(ti) * aim;

        // avoid boats directly ahead
        for (Boat o : all) {
            if (o == this) continue;
            float dx = o.x - x, dz = o.z - z;
            float fwd = dx * fwdX() + dz * fwdZ();
            float side = dx * rightX() + dz * rightZ();
            if (fwd > 0 && fwd < 7f * fx && Math.abs(side) < 1.6f * fx) {
                float push = (side >= 0 ? -1f : 1f) * 2.2f * fx;
                tgx += rightX() * push;
                tgz += rightZ() * push;
            }
        }

        if (t.impba) {
            // never steer for a point on the buoy side of the lane, even when dodging a boat
            float lat = (tgx - t.px[ti]) * t.nx(ti) + (tgz - t.pz[ti]) * t.nz(ti);
            float lim = lat;
            if (t.insideSign < 0) lim = Math.max(lat, t.laneMin - 0.3f);
            else lim = Math.min(lat, t.laneMax + 0.3f);
            tgx += t.nx(ti) * (lim - lat);
            tgz += t.nz(ti) * (lim - lat);
        }
        float desired = (float) Math.atan2(-(tgx - x), -(tgz - z));
        float diff = wrapAngle(desired - heading);
        steer = clamp(-diff * 2.6f, -1f, 1f);

        // slow down ahead of sharp corners
        int far = t.wrap(trackIdx + Math.round((10f + spd * 0.6f) / t.ds));
        float dot = t.tx[trackIdx] * t.tx[far] + t.tz[trackIdx] * t.tz[far];
        float corner = clamp((0.75f - dot) * 0.9f, 0f, 0.45f);
        throttle = clamp(1f - corner - Math.max(0f, Math.abs(diff) - 0.35f), 0.25f, 1f);
        if (paceSpeed >= 0) {
            // clock start: hold the speed that reaches the start line just after zero
            throttle = Math.min(throttle, clamp(paceSpeed / Math.max(1f, maxSpeed) + (paceSpeed - spd) * 0.35f, 0f, 1f));
        }
    }

    /** Applies an IMPBA power class: speed and acceleration multipliers and throttle lag. */
    void setPower(float speedMul, float accelMul, float lag) {
        topSpeed *= speedMul;
        maxSpeed = topSpeed;
        accel *= accelMul;
        throttleLag = lag;
    }

    void step(float dt) {
        float fx = fwdX(), fz = fwdZ(), rx = rightX(), rz = rightZ();
        float vf = vx * fx + vz * fz;
        float vs = vx * rx + vz * rz;

        float top = maxSpeed * speedMul;
        float drag = accel / (top * top);
        throttleNow += (throttle - throttleNow) * Math.min(1f, dt / throttleLag);
        float thr = throttleNow;
        float acc;
        if (thr >= 0) acc = thr * accel;
        else acc = vf > 0.5f ? thr * accel * 1.4f : thr * accel * 0.6f;
        vf += acc * dt;
        vf -= drag * vf * Math.abs(vf) * dt;
        vf -= vf * waterDrag * dt;             // water friction
        if (vf < -REVERSE_MAX) vf = -REVERSE_MAX;
        vs *= (float) Math.exp(-grip * dt);    // side grip (lets boats slide a little)

        float authority = clamp(vf / 3.5f, -1f, 1f);
        float rate = turnRate * (1f - 0.25f * clamp(vf / top, 0f, 1f));
        float targetYaw = -steer * rate * authority;
        yawRate += (targetYaw - yawRate) * Math.min(1f, dt * 7f);
        heading = wrapAngle(heading + yawRate * dt);

        fx = fwdX();
        fz = fwdZ();
        rx = rightX();
        rz = rightZ();
        vx = fx * vf + rx * vs;
        vz = fz * vf + rz * vs;
        x += vx * dt;
        z += vz * dt;

        // visual attitude
        float targetPitch = pitchK * (0.07f * clamp(vf / top, -0.3f, 1f) + 0.05f * throttle * clamp(vf / 4f, 0f, 1f));
        pitch += (targetPitch - pitch) * Math.min(1f, dt * 3f);
        float targetRoll = clamp(yawRate * vf * rollK, -0.3f, 0.3f);
        roll += (targetRoll - roll) * Math.min(1f, dt * 4f);
    }

    /** Wave-induced extra pitch and roll: out[0] = pitch, out[1] = roll; also updates y. */
    void waveAttitude(float time, float[] out) {
        y = World.wave(x, z, time);
        float wdx = World.waveDx(x, z, time), wdz = World.waveDz(x, z, time);
        out[0] = -(wdx * fwdX() + wdz * fwdZ()) * 2f;
        out[1] = (wdx * rightX() + wdz * rightZ()) * 2f;
    }

    private final float[] att = new float[2];

    /** Model matrix (column-major, like android.opengl.Matrix) of the boat. */
    void modelMatrix(float[] m, float time) {
        waveAttitude(time, att);
        float p = pitch + att[0];
        float rl = roll + att[1];
        if (hull == HYDROPLANE) p += 0.02f * clamp(forwardSpeed() / topSpeed, 0f, 1f);
        if (hull == UNLIMITED) { p = pitch + att[0] * 0.25f; rl = roll + att[1] * 0.25f; }   // heavy: rides over the chop
        android.opengl.Matrix.setIdentityM(m, 0);
        android.opengl.Matrix.translateM(m, 0, x, y, z);
        android.opengl.Matrix.rotateM(m, 0, (float) Math.toDegrees(heading), 0, 1, 0);
        android.opengl.Matrix.rotateM(m, 0, (float) Math.toDegrees(p), 1, 0, 0);
        android.opengl.Matrix.rotateM(m, 0, (float) Math.toDegrees(rl), 0, 0, 1);
        if (sizeScale != 1f) android.opengl.Matrix.scaleM(m, 0, sizeScale, sizeScale, sizeScale);
    }

    static float wrapAngle(float a) {
        while (a > Math.PI) a -= (float) (2 * Math.PI);
        while (a < -Math.PI) a += (float) (2 * Math.PI);
        return a;
    }

    static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
