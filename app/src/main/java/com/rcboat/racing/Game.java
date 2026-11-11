package com.rcboat.racing;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * Game state and rules. All public mutators are synchronized; the GL thread calls
 * {@link #update(float)} and reads state while holding the same lock, and the HUD
 * (UI thread) does the same when drawing.
 */
final class Game {
    enum State { MENU, COUNTDOWN, RACING, FINISHED }

    static final int VIEW_SHORE = 0, VIEW_FPV = 1, VIEW_CHASE = 2;
    static final String[] VIEW_NAMES = {"Shore", "Boat (FPV)", "Chase"};
    static final String[] VIEW_SHORT = {"SHORE", "BOAT", "CHASE"};
    static final int MAX_OPPONENTS = 5;

    static final float[][] COLORS = {
            {0.92f, 0.14f, 0.10f},   // player: red
            {0.12f, 0.38f, 0.95f},   // blue
            {0.10f, 0.72f, 0.28f},   // green
            {0.98f, 0.80f, 0.10f},   // yellow
            {0.62f, 0.22f, 0.88f},   // purple
            {1.00f, 0.50f, 0.06f},   // orange
    };
    static final String[] NAMES = {"YOU", "Blue Thunder", "Green Machine", "Yellow Jacket",
            "Purple Haze", "Orange Crush"};
    static final String[] DIFFICULTY = {"Easy", "Normal", "Hard"};

    // ---- settings (changed from the menu) ----
    int opponents = 5;
    int laps = 3;
    int difficulty = 1;
    int view = VIEW_SHORE;
    int trackId = 0;
    int hull = Boat.V_HULL;
    boolean dualStick = true;
    boolean soundOn = true;

    // ---- state ----
    State state = State.MENU;
    boolean paused;
    Track track = Track.get(0);
    final Random rnd = new Random(7);
    Boat[] boats = new Boat[0];
    Boat[] order = new Boat[0];
    Boat player;
    float time;          // free-running clock (waves, bobbing)
    float countdown;
    float raceTime;
    float finishedFor;   // seconds since the player finished
    String message = "";
    float messageTime;
    final Particles particles = new Particles(700);
    float[] buoyWobble;

    // ---- input (written by the UI thread) ----
    volatile float inThrottle, inSteer;

    // ---- sound (read by the audio thread) ----
    volatile float engineLevel;     // 0..1 speed
    volatile float engineThrottle;  // 0..1
    volatile boolean engineOn;
    volatile int beepRequest;       // Hz, consumed by the audio thread

    // ---- drivers' platform (5 ft / 1.52 m tall) on the south shore ----
    static final float PLATFORM_HEIGHT = 1.524f;
    static final float PLATFORM_W = 10f, PLATFORM_D = 3f;
    static final float DRIVER_SPACING = 1.5f;
    static final float EYE_HEIGHT = 1.62f;
    final float shoreZ;
    final float platformZ;      // centre of the platform
    final float groundY;        // ground level under the platform
    final float deckY;          // top of the platform deck
    final float driverZ;        // where the drivers stand
    final float[] driverX = new float[COLORS.length];

    // ---- camera ----
    final float[] shoreEye = new float[3];
    final float[] camTarget = new float[3];
    float camFov = 40f;
    private boolean camInit;
    private float fpvPitch, fpvRoll, fpvY, chaseHeading;
    private final float[] tmpM = new float[16];
    private final float[] tmpV = new float[8];
    private final float[] att = new float[2];

    Game() {
        shoreZ = World.southShoreZ();
        platformZ = shoreZ + 2.2f;
        float g = -10f;
        for (float x = -PLATFORM_W / 2; x <= PLATFORM_W / 2; x += 1f)
            for (float z = platformZ - PLATFORM_D / 2; z <= platformZ + PLATFORM_D / 2; z += 0.5f)
                g = Math.max(g, World.baseHeight(x, z));
        groundY = g;
        deckY = groundY + PLATFORM_HEIGHT;
        driverZ = platformZ - 0.9f;
        buoyWobble = new float[track.buoys.size()];
        startDemo();
    }

    /** Whether the island is shown / solid right now. */
    boolean islandVisible() {
        return track.hasIsland && !(state != State.MENU && view == VIEW_SHORE);
    }

    // ------------------------------------------------------------------ setup

    private void createBoats(int count, boolean withPlayer) {
        boats = new Boat[count];
        int playerSlot = withPlayer ? Math.min(count - 1, 3) : -1;
        float[] skillRange = {0.84f, 0.91f, 0.975f};
        for (int i = 0; i < count; i++) {
            boolean isPlayer = withPlayer && i == 0;
            int h = isPlayer ? hull : rnd.nextInt(Boat.HULL_NAMES.length);
            Boat b = new Boat(i, NAMES[i], COLORS[i], isPlayer, h, rnd);
            if (!isPlayer) {
                b.autopilot = true;
                b.skill = withPlayer ? skillRange[difficulty] + rnd.nextFloat() * 0.06f
                        : 0.9f + rnd.nextFloat() * 0.08f;
                b.maxSpeed = b.topSpeed * b.skill;
                b.lane = b.laneTarget = 0;
            }
            boats[i] = b;
        }
        // driver positions on the platform: the player stands in the middle
        int playerStand = withPlayer ? count / 2 : -1;
        int k = 0;
        for (int i = 0; i < count; i++) {
            int stand;
            if (withPlayer && i == 0) stand = playerStand;
            else {
                if (k == playerStand) k++;
                stand = k++;
            }
            driverX[i] = (stand - (count - 1) / 2f) * DRIVER_SPACING;
        }
        shoreEye[0] = withPlayer ? driverX[0] : 0f;
        shoreEye[1] = deckY + EYE_HEIGHT;
        shoreEye[2] = driverZ;
        // grid: two columns, rows 3.5 m apart, behind the start line
        int[] slotOf = new int[count];
        int s = 0;
        for (int i = 0; i < count; i++) {
            if (withPlayer && i == 0) continue;
            if (s == playerSlot) s++;
            slotOf[i] = s++;
        }
        if (withPlayer) slotOf[0] = playerSlot;
        for (int i = 0; i < count; i++) {
            int slot = slotOf[i];
            int row = slot / 2, col = slot % 2;
            float back = 4f + row * 3.6f + (col == 1 ? 1.2f : 0f);
            int idx = track.wrap(-Math.round(back / track.ds));
            Boat b = boats[i];
            b.placeAt(track, idx, col == 0 ? 2.4f : -2.4f);
            b.lapWrap = -1;
            b.progress = -track.length + track.cum[idx];
        }
        player = withPlayer ? boats[0] : null;
        order = boats.clone();
        particles.clear();
    }

    synchronized void startDemo() {
        state = State.MENU;
        paused = false;
        createBoats(6, false);
        raceTime = 0;
        camInit = false;
        message = "";
        engineOn = false;
    }

    synchronized void startRace() {
        createBoats(1 + opponents, true);
        state = State.COUNTDOWN;
        countdown = 3.999f;
        raceTime = 0;
        finishedFor = 0;
        paused = false;
        message = "";
        camInit = false;
        beepRequest = 520;
    }

    synchronized void toggleView() {
        view = (view + 1) % VIEW_NAMES.length;
        camInit = false;
        setMessage(VIEW_SHORT[view] + " VIEW", 1.2f);
    }

    synchronized void setTrack(int id) {
        trackId = (id + Track.COUNT) % Track.COUNT;
        track = Track.get(trackId);
        buoyWobble = new float[track.buoys.size()];
        startDemo();
    }

    synchronized void setPaused(boolean p) {
        if (state == State.MENU) return;
        paused = p;
    }

    /** Handles the Android back button. Returns false to let the app close. */
    synchronized boolean onBack() {
        if (state == State.MENU) return false;
        if (state == State.FINISHED && finishedFor > 2f) { startDemo(); return true; }
        paused = !paused;
        return true;
    }

    void setMessage(String m, float seconds) {
        message = m;
        messageTime = seconds;
    }

    // ------------------------------------------------------------------ update

    synchronized void update(float dt) {
        time += dt;
        World.islandEnabled = islandVisible();
        if (messageTime > 0) messageTime -= dt;
        for (int i = 0; i < buoyWobble.length; i++) buoyWobble[i] *= (float) Math.exp(-2.5f * dt);
        if (paused) {
            engineOn = false;
            return;
        }

        switch (state) {
            case MENU:
                simulate(dt, true);
                break;
            case COUNTDOWN: {
                int before = (int) Math.ceil(countdown);
                countdown -= dt;
                int after = (int) Math.ceil(countdown);
                if (countdown <= 0) {
                    state = State.RACING;
                    beepRequest = 1040;
                    setMessage("GO!", 1.0f);
                } else if (after != before) {
                    beepRequest = 520;
                }
                for (Boat b : boats) { b.throttle = 0; b.steer = 0; }
                break;
            }
            case RACING:
            case FINISHED:
                raceTime += dt;
                if (state == State.FINISHED) finishedFor += dt;
                simulate(dt, false);
                break;
        }

        if (player != null && state != State.MENU) {
            engineOn = true;
            engineLevel = Boat.clamp(player.speed() / Boat.BASE_MAX_SPEED, 0f, 1.2f);
            engineThrottle = Math.abs(player.throttle);
        } else {
            engineOn = false;
        }
    }

    private void simulate(float dt, boolean demo) {
        // inputs
        for (Boat b : boats) {
            if (b.isPlayer && !b.autopilot) {
                b.throttle = inThrottle;
                b.steer = inSteer;
            } else {
                b.think(track, boats, dt, rnd);
            }
        }
        // rubber banding: keep the pack close to the player
        if (player != null && !player.finished) {
            for (Boat b : boats) {
                if (b == player) continue;
                float gap = b.progress - player.progress;
                float target = 1f;
                if (gap < -25f) target = 1.07f;
                else if (gap > 35f) target = 0.95f;
                b.speedMul += (target - b.speedMul) * Math.min(1f, dt * 0.5f);
            }
        }

        int sub = 2;
        float h = dt / sub;
        for (int s = 0; s < sub; s++) {
            for (Boat b : boats) b.step(h);
            collide();
        }

        for (Boat b : boats) {
            updateProgress(b, dt, demo);
            emitWake(b, dt);
        }
        particles.update(dt);
        rank();
    }

    private void collide() {
        // boat vs boat
        for (int i = 0; i < boats.length; i++) {
            Boat a = boats[i];
            for (int j = i + 1; j < boats.length; j++) {
                Boat c = boats[j];
                float dx = c.x - a.x, dz = c.z - a.z;
                float d2 = dx * dx + dz * dz;
                float min = Boat.RADIUS * 2f;
                if (d2 < min * min && d2 > 1e-6f) {
                    float d = (float) Math.sqrt(d2);
                    float nx = dx / d, nz = dz / d;
                    float push = (min - d) * 0.5f;
                    a.x -= nx * push; a.z -= nz * push;
                    c.x += nx * push; c.z += nz * push;
                    float rel = (c.vx - a.vx) * nx + (c.vz - a.vz) * nz;
                    if (rel < 0) {
                        float imp = -rel * 0.7f;
                        a.vx -= nx * imp; a.vz -= nz * imp;
                        c.vx += nx * imp; c.vz += nz * imp;
                    }
                }
            }
        }
        // boat vs buoys
        float min = Boat.RADIUS + Track.BUOY_RADIUS;
        for (Boat b : boats) {
            for (int k = 0; k < track.buoys.size(); k++) {
                float[] bu = track.buoys.get(k);
                float dx = b.x - bu[0], dz = b.z - bu[1];
                float d2 = dx * dx + dz * dz;
                if (d2 < min * min && d2 > 1e-6f) {
                    float d = (float) Math.sqrt(d2);
                    float nx = dx / d, nz = dz / d;
                    b.x = bu[0] + nx * min;
                    b.z = bu[1] + nz * min;
                    float vn = b.vx * nx + b.vz * nz;
                    if (vn < 0) {
                        b.vx -= nx * vn * 1.4f;
                        b.vz -= nz * vn * 1.4f;
                        b.vx *= 0.8f;
                        b.vz *= 0.8f;
                        buoyWobble[k] = Math.min(1f, buoyWobble[k] + Math.abs(vn) * 0.12f);
                    }
                }
            }
            // boat vs shore / island
            if (World.height(b.x, b.z) > World.SHALLOW) {
                float e = 0.4f;
                float gx = (World.height(b.x + e, b.z) - World.height(b.x - e, b.z)) / (2 * e);
                float gz = (World.height(b.x, b.z + e) - World.height(b.x, b.z - e)) / (2 * e);
                float len = (float) Math.sqrt(gx * gx + gz * gz);
                if (len < 1e-4f) { gx = b.x; gz = b.z; len = (float) Math.hypot(gx, gz); }
                float nx = -gx / len, nz = -gz / len;   // downhill: back into the water
                for (int it = 0; it < 30 && World.height(b.x, b.z) > World.SHALLOW; it++) {
                    b.x += nx * 0.08f;
                    b.z += nz * 0.08f;
                }
                float vn = b.vx * nx + b.vz * nz;
                if (vn < 0) {
                    b.vx -= nx * vn * 1.3f;
                    b.vz -= nz * vn * 1.3f;
                }
                b.vx *= 0.85f;
                b.vz *= 0.85f;
            }
        }
    }

    private void updateProgress(Boat b, float dt, boolean demo) {
        int idx = track.nearest(b.x, b.z, b.trackIdx, 24);
        float dist = track.distTo(idx, b.x, b.z);
        if (dist < Track.HALF_WIDTH + 5f) {
            int diff = idx - b.trackIdx;
            if (diff < -Track.N / 2) b.lapWrap++;
            else if (diff > Track.N / 2) b.lapWrap--;
            b.trackIdx = idx;
            b.offCourseTime = 0;
        } else {
            b.offCourseTime += dt;
        }
        float along = Boat.clamp(track.along(b.trackIdx, b.x, b.z), -track.ds, track.ds);
        b.progress = b.lapWrap * track.length + track.cum[b.trackIdx] + along;

        // wrong way?
        float vt = b.vx * track.tx[b.trackIdx] + b.vz * track.tz[b.trackIdx];
        b.wrongWayTime = vt < -1.5f ? b.wrongWayTime + dt : 0f;

        // stuck (AI)
        if (b.autopilot) {
            b.stuckTimer = b.speed() < 1.0f ? b.stuckTimer + dt : 0f;
        }

        boolean lost = b.offCourseTime > (b.autopilot ? 3f : 6f)
                || (b.autopilot && (b.stuckTimer > 2.5f || b.wrongWayTime > 2f));
        if (lost) {
            b.placeAt(track, b.trackIdx, 0f);
            if (b.isPlayer) setMessage("BACK ON COURSE", 1.5f);
        }

        if (demo) {
            // demo boats just keep lapping
            return;
        }
        if (!b.finished && b.progress >= laps * track.length) {
            b.finished = true;
            b.finishTime = raceTime;
            if (b.isPlayer) {
                b.autopilot = true;
                b.skill = 0.8f;
                state = State.FINISHED;
                finishedFor = 0;
                int pos = positionOf(b);
                setMessage("FINISHED  " + ordinal(pos) + "!", 3f);
                beepRequest = 880;
            }
        }
    }

    private void emitWake(Boat b, float dt) {
        float spd = b.speed();
        if (spd < 1.2f) return;
        b.wakeTimer -= dt;
        if (b.wakeTimer > 0) return;
        b.wakeTimer = 0.05f;
        float fx = b.fwdX(), fz = b.fwdZ(), rx = b.rightX(), rz = b.rightZ();
        float sx = b.x - fx * 0.6f, sz = b.z - fz * 0.6f;
        float k = Math.min(1f, spd / 9f);
        float out = 0.5f + spd * 0.09f;
        particles.emit(sx + rx * 0.2f, sz + rz * 0.2f, rx * out + b.vx * 0.1f, rz * out + b.vz * 0.1f,
                2.0f, 0.2f, 1.1f, 0.28f * k + 0.06f);
        particles.emit(sx - rx * 0.2f, sz - rz * 0.2f, -rx * out + b.vx * 0.1f, -rz * out + b.vz * 0.1f,
                2.0f, 0.2f, 1.1f, 0.28f * k + 0.06f);
        particles.emit(sx, sz, b.vx * 0.15f, b.vz * 0.15f, 0.9f, 0.28f, 0.65f, 0.45f * k + 0.08f);
        if (spd > 7f) {
            float bx = b.x + fx * 0.45f, bz = b.z + fz * 0.45f;
            float side = rnd.nextBoolean() ? 1f : -1f;
            particles.emit(bx + rx * 0.2f * side, bz + rz * 0.2f * side,
                    rx * side * 1.6f + b.vx * 0.3f, rz * side * 1.6f + b.vz * 0.3f, 0.5f, 0.12f, 0.4f, 0.5f);
        }
    }

    private void rank() {
        Arrays.sort(order, new Comparator<Boat>() {
            @Override
            public int compare(Boat a, Boat b) {
                if (a.finished && b.finished) return Float.compare(a.finishTime, b.finishTime);
                if (a.finished) return -1;
                if (b.finished) return 1;
                return Float.compare(b.progress, a.progress);
            }
        });
    }

    int positionOf(Boat b) {
        for (int i = 0; i < order.length; i++) if (order[i] == b) return i + 1;
        return order.length;
    }

    int currentLap(Boat b) {
        int lap = (int) Math.floor(b.progress / track.length) + 1;
        return Math.max(1, Math.min(laps, lap));
    }

    static String ordinal(int n) {
        switch (n) {
            case 1: return "1st";
            case 2: return "2nd";
            case 3: return "3rd";
            default: return n + "th";
        }
    }

    static String formatTime(float t) {
        int ms = (int) (t * 1000);
        int m = ms / 60000;
        int s = (ms / 1000) % 60;
        int c = (ms / 10) % 100;
        return String.format(java.util.Locale.US, "%d:%02d.%02d", m, s, c);
    }

    // ------------------------------------------------------------------ camera

    /**
     * Computes the camera. out = eye(3), target(3), up(3), fov(1), near(1).
     */
    synchronized void camera(float dt, float[] out) {
        if (state == State.MENU) {
            float a = time * 0.06f;
            out[0] = (float) Math.sin(a) * 88f;
            out[1] = 30f;
            out[2] = (float) Math.cos(a) * 70f;
            out[3] = 0; out[4] = -2f; out[5] = 0;
            out[6] = 0; out[7] = 1; out[8] = 0;
            out[9] = 52f;
            out[10] = 0.5f;
            return;
        }
        Boat b = player;
        if (view == VIEW_FPV) {
            // Stabilised: only a fraction of the boat's pitch/roll reaches the camera,
            // and it is low-pass filtered so wave chop gives a gentle sway, not a shake.
            b.waveAttitude(time, att);
            float tp = (b.pitch + att[0]) * 0.35f, tr = (b.roll + att[1]) * 0.3f;
            if (!camInit) {
                fpvPitch = tp; fpvRoll = tr; fpvY = b.y;
                camInit = true;
            } else {
                float k = Math.min(1f, dt * 3f);
                fpvPitch += (tp - fpvPitch) * k;
                fpvRoll += (tr - fpvRoll) * k;
                fpvY += (b.y - fpvY) * Math.min(1f, dt * 4f);
            }
            android.opengl.Matrix.setIdentityM(tmpM, 0);
            android.opengl.Matrix.translateM(tmpM, 0, b.x, fpvY, b.z);
            android.opengl.Matrix.rotateM(tmpM, 0, (float) Math.toDegrees(b.heading), 0, 1, 0);
            android.opengl.Matrix.rotateM(tmpM, 0, (float) Math.toDegrees(fpvPitch), 1, 0, 0);
            android.opengl.Matrix.rotateM(tmpM, 0, (float) Math.toDegrees(fpvRoll), 0, 0, 1);
            transform(tmpM, 0f, 0.40f, -0.20f, 1f, out, 0);
            transform(tmpM, 0f, 0.24f, -6f, 1f, out, 3);
            transform(tmpM, 0f, 1f, 0f, 0f, tmpV, 0);
            out[6] = tmpV[0]; out[7] = tmpV[1]; out[8] = tmpV[2];
            out[9] = 74f;
            out[10] = 0.05f;
            return;
        }
        if (view == VIEW_CHASE) {
            if (!camInit) {
                chaseHeading = b.heading;
                camInit = true;
            } else {
                chaseHeading = Boat.wrapAngle(chaseHeading
                        + Boat.wrapAngle(b.heading - chaseHeading) * Math.min(1f, dt * 4f));
            }
            float fx = (float) -Math.sin(chaseHeading), fz = (float) -Math.cos(chaseHeading);
            float ex = b.x - fx * 3.6f, ez = b.z - fz * 3.6f;
            float ey = Math.max(b.y + 1.45f, World.height(ex, ez) + 0.7f);
            out[0] = ex; out[1] = ey; out[2] = ez;
            out[3] = b.x + b.fwdX() * 2.0f; out[4] = b.y + 0.35f; out[5] = b.z + b.fwdZ() * 2.0f;
            out[6] = 0; out[7] = 1; out[8] = 0;
            out[9] = 62f;
            out[10] = 0.1f;
            return;
        }
        // shore: fixed spectator on the dock who turns to follow the boat
        float tx = b.x, ty = b.y + 0.25f, tz = b.z;
        float dx = tx - shoreEye[0], dy = ty - shoreEye[1], dz = tz - shoreEye[2];
        float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float fovTarget = Boat.clamp((float) Math.toDegrees(2 * Math.atan(5.2f / Math.max(d, 1f))), 10f, 58f);
        if (!camInit) {
            camTarget[0] = tx; camTarget[1] = ty; camTarget[2] = tz;
            camFov = fovTarget;
            camInit = true;
        } else {
            float k = Math.min(1f, dt * 5f);
            camTarget[0] += (tx - camTarget[0]) * k;
            camTarget[1] += (ty - camTarget[1]) * k;
            camTarget[2] += (tz - camTarget[2]) * k;
            camFov += (fovTarget - camFov) * Math.min(1f, dt * 1.5f);
        }
        out[0] = shoreEye[0]; out[1] = shoreEye[1]; out[2] = shoreEye[2];
        out[3] = camTarget[0]; out[4] = camTarget[1]; out[5] = camTarget[2];
        out[6] = 0; out[7] = 1; out[8] = 0;
        out[9] = camFov;
        out[10] = 0.3f;
    }

    private static void transform(float[] m, float x, float y, float z, float w, float[] out, int o) {
        out[o] = m[0] * x + m[4] * y + m[8] * z + m[12] * w;
        out[o + 1] = m[1] * x + m[5] * y + m[9] * z + m[13] * w;
        out[o + 2] = m[2] * x + m[6] * y + m[10] * z + m[14] * w;
    }
}
