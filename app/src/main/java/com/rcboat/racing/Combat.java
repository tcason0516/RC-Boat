package com.rcboat.racing;

import java.util.ArrayList;
import java.util.Random;

/**
 * RC Boat Combat: armed boats battle on the lake until one is left afloat.
 *
 * Damage: each boat has hull points (shots, flames, rams and blasts take them off) and can be
 * holed below the waterline (drill, torpedoes, mines, some rockets): every hole lets water in
 * faster than the bilge pump can clear it, the boat gets slower as it fills, and a swamped
 * boat sinks. Fire burns on for a few seconds. Crates floating on the lake repair, pump out,
 * or give a special (nitro, shield, EMP, oil slick, smoke screen).
 *
 * Longer games: matches can be best of 3 or 5 rounds, the battle length setting makes hulls
 * tougher, Survival sends wave after wave of stronger boats, crates keep repairs coming, and a
 * holed boat can limp off to find a pump instead of dying at once.
 */
final class Combat {
    static final int MODE_LAST = 0, MODE_SURVIVAL = 1;
    static final String[] MODE_NAMES = {"Last one standing", "Survival waves"};
    static final String[] LENGTH_NAMES = {"Quick", "Normal", "Epic"};
    static final float[] LENGTH_HP = {1f, 2f, 3.5f};
    /** Crates turn up more often in longer battles. */
    static final float[] LENGTH_CRATES = {1.2f, 1f, 0.75f};
    /** After this long, every boat springs a leak (Sudden Death), so rounds always end. */
    static final float[] LENGTH_SUDDEN = {150f, 300f, 540f};
    static final int[] ROUNDS = {1, 3, 5};
    static final String[] RC_ARENAS = {"Island Lake", "Open Lake", "Buoy Maze"};
    static final String[] LIFE_ARENAS = {"Big Lake", "Buoy Maze"};

    // ---- sound events for the audio side (see Sfx) ----
    static final int S_GUN = 0, S_FLAME = 1, S_DRILL = 2, S_TORPEDO = 3, S_HARPOON = 4, S_MINE = 5,
            S_CANNON = 6, S_ROCKET = 7, S_BOOM = 8, S_BIG_BOOM = 9, S_HIT = 10, S_PICKUP = 11,
            S_SHIELD = 12, S_EMP = 13, S_SPLASH = 14, S_SINK = 15, S_RAM = 16, S_CLANG = 17;
    static final int S_COUNT = 18;
    final int[] sfxId = new int[64];
    final float[] sfxVol = new float[64];
    volatile int sfxHead;
    int sfxTail;   // consumer side

    // ---- AI modes ----
    private static final int AI_ATTACK = 0, AI_PICKUP = 1, AI_FLEE = 2;

    // ---- kinds of shot ----
    static final int SHOT_BULLET = 0, SHOT_TORPEDO = 1, SHOT_HARPOON = 2, SHOT_ROCKET = 3;

    static final class Shot {
        int kind;
        float x, y, z, vx, vy, vz, life, dmg, heading;
        Boat owner, target;
        boolean tracer;
    }

    static final class Mine {
        float x, z, arm, life;
        Boat owner;
    }

    static final class Crate {
        float x, z, life, spin;
        int kind;
    }

    /** Oil slicks (x, z, radius, time left, burning time left) and smoke clouds. */
    static final class Area {
        float x, z, r, life, burn;
        Boat owner;
    }

    private final Game g;
    final Random rnd = new Random();
    /** Weapons dealt to the computer boats: each one different until all have been used. */
    private final int[] deck = new int[Weapons.COUNT];
    private int deckPos = Weapons.COUNT;

    private int dealWeapon(int avoid) {
        if (deckPos >= deck.length) {
            for (int i = 0; i < deck.length; i++) deck[i] = i;
            for (int i = deck.length - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1), t = deck[i]; deck[i] = deck[j]; deck[j] = t;
            }
            deckPos = 0;
        }
        int w = deck[deckPos++];
        if (w == avoid && deckPos < deck.length) {   // not the same as the player's, if possible
            int t = deck[deckPos]; deck[deckPos] = w; deck[deckPos - 1] = t; w = t;
        }
        return w;
    }
    final Fx fx = new Fx(2600);

    // ---- settings (menu) ----
    int mode = MODE_LAST;
    int length = 1;
    int roundsIdx = 1;
    int arena;
    int weapon = Weapons.MACHINE_GUN;
    boolean autoFire = true;
    boolean pickupsOn = true;

    // ---- state ----
    final ArrayList<Shot> shots = new ArrayList<>();
    final ArrayList<Mine> mines = new ArrayList<>();
    final ArrayList<Crate> crates = new ArrayList<>();
    final ArrayList<Area> slicks = new ArrayList<>();
    final ArrayList<Area> smokes = new ArrayList<>();
    /** Arena obstacles, drawn and solid like buoys: x, z, buoy type. */
    final ArrayList<float[]> obstacles = new ArrayList<>();
    final ArrayList<String> feed = new ArrayList<>();
    final ArrayList<Float> feedT = new ArrayList<>();
    int round, wave, bestWave;
    boolean roundOver, matchOver;
    float roundOverT, roundTime, waveBreak, crateTimer;
    boolean suddenDeath;
    private float suddenLeak;
    Boat roundWinner, matchWinner;
    Boat spectate;
    int alive;
    /** Life size: the battle is held inside a ring of buoys on the big lake. */
    float arenaRx, arenaRz;
    boolean demo;
    private final float[][] ramCool = new float[8][8];
    private float flameSfx, cannonSfx, drillSfx;
    // last result line for the results screen
    String resultLine = "";

    Combat(Game g) {
        this.g = g;
    }

    float k() { return g.lifeSize ? 7.5f : 1f; }

    boolean islandOn() { return !g.lifeSize && arena == 0; }

    String[] arenas() { return g.lifeSize ? LIFE_ARENAS : RC_ARENAS; }

    int winsNeeded() { return ROUNDS[roundsIdx] / 2 + 1; }

    // ------------------------------------------------------------------ setup

    /** A new match (or Survival run) for the player. */
    void startMatch() {
        demo = false;
        round = 0;
        wave = 0;
        matchOver = false;
        matchWinner = null;
        feed.clear();
        feedT.clear();
        int n = mode == MODE_SURVIVAL ? 1 : 1 + Math.max(1, g.opponents);
        createBoats(n, true);
        for (Boat b : g.boats) { b.wins = 0; b.kills = 0; }
        startRound();
    }

    /** The menu background: computer boats fighting it out. */
    void startDemo() {
        demo = true;
        round = 0;
        matchOver = false;
        feed.clear();
        feedT.clear();
        createBoats(6, false);
        startRound();
    }

    private void createBoats(int n, boolean withPlayer) {
        Boat[] boats = new Boat[n];
        for (int i = 0; i < n; i++) {
            boolean me = withPlayer && i == 0;
            int hull = g.lifeSize ? Boat.UNLIMITED : me ? g.hull : rnd.nextInt(Boat.RC_HULLS);
            Boat b = new Boat(i, Game.NAMES[i], Game.COLORS[i], me, hull, rnd);
            if (!g.lifeSize) b.setPower(Game.POWER_STATS[2][0], Game.POWER_STATS[2][1], Game.POWER_STATS[2][2]);
            b.autopilot = !me;
            b.weapon = me ? weapon : dealWeapon(withPlayer ? weapon : -1);
            b.favorite = b.weapon;
            setSkill(b, withPlayer ? g.difficulty : 1, 0);
            boats[i] = b;
        }
        g.boats = boats;
        g.order = boats.clone();
        g.player = withPlayer ? boats[0] : null;
        standDrivers();
        buildArena();
    }

    /** RC: the drivers stand in a row on the platform; the shore camera is the player's spot. */
    private void standDrivers() {
        int n = g.boats.length;
        for (int i = 0; i < Game.COLORS.length; i++) g.driverX[i] = (i - (n - 1) / 2f) * Game.DRIVER_SPACING;
        g.shoreEye[0] = g.driverX[0];
        g.shoreEye[1] = g.deckY + Game.EYE_HEIGHT;
        g.shoreEye[2] = g.driverZ;
    }

    private void setSkill(Boat b, int difficulty, int waveBoost) {
        float[] jitter = {0.14f, 0.07f, 0.03f};
        float[] speed = {0.86f, 0.94f, 1f};
        b.aimJitter = jitter[difficulty] * (float) Math.pow(0.85, waveBoost);
        b.skill = Math.min(1.04f, speed[difficulty] + waveBoost * 0.015f);
        b.maxSpeed = b.topSpeed * (b.isPlayer ? 1f : b.skill);
    }

    /** Obstacle buoys for the chosen arena (and the boundary ring on the big lake). */
    private void buildArena() {
        obstacles.clear();
        float s = World.scale;
        float k = k();
        arenaRx = World.LAKE_A * s * (g.lifeSize ? 0.42f : 0.95f);
        arenaRz = World.LAKE_B * s * (g.lifeSize ? 0.42f : 0.95f);
        Random r = new Random(31 + arena);
        boolean maze = arenas()[Math.min(arena, arenas().length - 1)].equals("Buoy Maze");
        int count = maze ? 46 : arena == 1 || g.lifeSize ? 14 : 8;
        int tries = 0;
        while (obstacles.size() < count && tries++ < 4000) {
            float x = (r.nextFloat() * 2f - 1f) * arenaRx * 0.85f;
            float z = (r.nextFloat() * 2f - 1f) * arenaRz * 0.85f;
            if (!inArena(x, z, 0.88f) || !World.isWater(x, z, islandOn())) continue;
            if (World.height(x, z, islandOn()) > -0.6f * s) continue;
            boolean near = false;
            for (float[] o : obstacles) {
                float dx = o[0] - x, dz = o[1] - z;
                if (dx * dx + dz * dz < (maze ? 36f : 100f) * k * k) { near = true; break; }
            }
            if (near) continue;
            int type = maze ? (r.nextBoolean() ? Track.BUOY_LEFT : Track.BUOY_RIGHT) : Track.BUOY_TURN;
            obstacles.add(new float[]{x, z, type});
        }
        if (g.lifeSize) {
            // the arena boundary: a ring of buoys
            int ring = 56;
            for (int i = 0; i < ring; i++) {
                double a = 2 * Math.PI * i / ring;
                float x = (float) Math.cos(a) * arenaRx, z = (float) Math.sin(a) * arenaRz;
                if (World.isWater(x, z, false)) obstacles.add(new float[]{x, z, Track.BUOY_PLAIN});
            }
        }
        g.buoyWobble = new float[obstacles.size()];
    }

    boolean inArena(float x, float z, float frac) {
        float qx = x / (arenaRx * frac), qz = z / (arenaRz * frac);
        return qx * qx + qz * qz < 1f;
    }

    /** Places every boat for a new round (or the survivors of a wave) and resets damage. */
    void startRound() {
        round++;
        roundOver = false;
        roundOverT = 0;
        roundTime = 0;
        roundWinner = null;
        suddenDeath = false;
        spectate = null;
        shots.clear();
        mines.clear();
        crates.clear();
        slicks.clear();
        smokes.clear();
        fx.clear();
        g.particles.clear();
        g.spray.clear();
        crateTimer = 3f;
        for (float[] row : ramCool) java.util.Arrays.fill(row, 0f);
        if (mode == MODE_SURVIVAL && !demo) {
            wave = 1;
            spawnWave();
        }
        Boat[] boats = g.boats;
        int n = boats.length;
        float a0 = rnd.nextFloat() * 6.283f;
        for (int i = 0; i < n; i++) {
            Boat b = boats[i];
            resetBoat(b);
            if (mode == MODE_SURVIVAL && !demo && !b.isPlayer) waveToughness(b);
            placeOnRing(b, a0 + 6.283f * i / n, 0.62f);
        }
        alive = n;
        if (!demo) {
            g.state = Game.State.COUNTDOWN;
            g.countdown = 3.9f;
        }
        g.raceTime = 0;
    }

    private void resetBoat(Boat b) {
        float hp = 100f * LENGTH_HP[length];
        b.maxHp = hp;
        b.hp = hp;
        b.holes = 0;
        b.water = 0;
        b.burn = 0;
        b.shield = b.nitro = b.emp = b.oiled = b.power = b.smokeT = 0;
        b.special = -1;
        b.weapon = b.favorite;
        for (int w = 0; w < Weapons.COUNT; w++) b.ammoOf[w] = Weapons.MAG[w];
        b.ammo = Weapons.MAG[b.weapon];
        b.reload = 0;
        b.switchT = 0;
        b.aiSwitch = 3f + rnd.nextFloat() * 4f;
        b.cool = 0;
        b.out = false;
        b.sinkT = 0;
        b.sinkY = b.sinkPitch = b.sinkRoll = 0;
        b.place = 0;
        b.lastHitBy = null;
        b.lastHitT = -99f;
        b.aimYaw = 0;
        b.aimAt = null;
        b.tether = null;
        b.target = null;
        b.aiTimer = 0;
        b.aiOrbit = rnd.nextBoolean() ? 1f : -1f;
        b.vx = b.vz = 0;
        b.yawRate = 0;
        b.throttle = b.steer = 0;
        b.speedMul = 1f;
        b.gripMul = 1f;
        b.turnMul = 1f;
    }

    /** Puts a boat on an ellipse round the middle of the arena, facing in. */
    private void placeOnRing(Boat b, float a, float frac) {
        for (int t = 0; t < 40; t++) {
            float f = frac - t * 0.012f;
            float x = (float) Math.cos(a) * arenaRx * f, z = (float) Math.sin(a) * arenaRz * f;
            if (World.isWater(x, z, islandOn()) && clearOfObstacles(x, z, 3f * k())) {
                b.x = x;
                b.z = z;
                break;
            }
            a += 0.07f;
        }
        b.heading = (float) Math.atan2(b.x, b.z);   // fwd = (-sin h, -cos h) points to the centre
        b.heading += (rnd.nextFloat() - 0.5f) * 0.4f;
    }

    /** Survival: the first waves are softer targets; from wave 5 they get tougher. */
    private void waveToughness(Boat b) {
        float k = Math.min(1.6f, 0.55f + (wave - 1) * 0.12f);
        b.maxHp *= k;
        b.hp = b.maxHp;
    }

    private void spawnWave() {
        // survival: the player plus a wave of computer boats, one more each wave (up to 5)
        Boat me = g.player;
        int enemies = Math.min(Game.MAX_OPPONENTS, wave);
        Boat[] boats = new Boat[1 + enemies];
        boats[0] = me;
        for (int i = 1; i <= enemies; i++) {
            int hull = g.lifeSize ? Boat.UNLIMITED : rnd.nextInt(Boat.RC_HULLS);
            Boat b = new Boat(i, Game.NAMES[i], Game.COLORS[i], false, hull, rnd);
            if (!g.lifeSize) b.setPower(Game.POWER_STATS[2][0], Game.POWER_STATS[2][1], Game.POWER_STATS[2][2]);
            b.autopilot = true;
            b.weapon = dealWeapon(-1);
            b.favorite = b.weapon;
            setSkill(b, g.difficulty, wave - 1);
            boats[i] = b;
        }
        g.boats = boats;
        g.order = boats.clone();
        standDrivers();
    }

    /** Survival: the next wave arrives at the edge of the arena; the player keeps fighting. */
    private void nextWave() {
        wave++;
        Boat me = g.player;
        // a breather: patch up a little
        me.hp = Math.min(me.maxHp, me.hp + me.maxHp * 0.3f);
        me.holes = Math.max(0, me.holes - 1);
        me.water = Math.max(0f, me.water - 0.3f);
        me.burn = 0;
        for (int w = 0; w < Weapons.COUNT; w++) me.ammoOf[w] = Weapons.MAG[w];
        me.ammo = Weapons.MAG[me.weapon];
        me.reload = 0;
        float mx = me.x, mz = me.z, mh = me.heading;
        float mvx = me.vx, mvz = me.vz;
        spawnWave();
        shots.clear();
        mines.clear();
        float far = (float) Math.atan2(-mz / arenaRz, -mx / arenaRx);
        for (int i = 1; i < g.boats.length; i++) {
            Boat b = g.boats[i];
            resetBoat(b);
            waveToughness(b);
            placeOnRing(b, far + (i - (g.boats.length) / 2f) * 0.5f, 0.85f);
        }
        me.x = mx; me.z = mz; me.heading = mh; me.vx = mvx; me.vz = mvz;
        alive = g.boats.length;
        addFeed("WAVE " + wave + ": " + (g.boats.length - 1) + (g.boats.length == 2 ? " boat" : " boats") + " incoming!");
        g.setMessage("WAVE " + wave, 2f);
        say("cwave", 3, Announcer.IMPORTANT);
        if (wave > bestWave) bestWave = wave;
    }

    // ------------------------------------------------------------------ per frame

    /** Inputs for every boat: the player's controls or the AI. */
    void control(float dt) {
        for (Boat b : g.boats) {
            if (b.out) {
                b.throttle = 0;
                b.steer = 0;
                b.fire = false;
                continue;
            }
            if (b.isPlayer && !b.autopilot) {
                b.throttle = g.inThrottle;
                b.steer = g.inSteer;
                b.fire = g.inFire || (autoFire && wantsFire(b));
                if (g.inSpecial) {
                    b.useSpecial = true;
                    g.inSpecial = false;
                }
                if (g.inWeapon >= 0) {
                    switchWeapon(b, g.inWeapon);
                    g.inWeapon = -1;
                }
            } else {
                think(b, dt);
            }
            // status effects on the controls and the hull's handling
            if (b.emp > 0) {
                b.throttle = 0;
                b.fire = false;
            }
            float waterSlow = 1f - 0.5f * b.water;
            b.speedMul = waterSlow * (b.nitro > 0 ? 1.45f : 1f);
            b.accel = b.nitro > 0 ? accelOf(b) * 1.8f : accelOf(b);
            b.gripMul = b.oiled > 0 ? 0.2f : 1f;
            // the big hydroplanes get arcade steering for close fighting
            b.turnMul = (b.oiled > 0 ? 0.55f : 1f) * (g.lifeSize ? 1.8f : 1f);
        }
    }

    private static float accelOf(Boat b) {
        if (b.baseAccel == 0f) b.baseAccel = b.accel;   // the hull's acceleration without nitro
        return b.baseAccel;
    }

    /** Everything after the boats have moved: weapons, shots, damage, pickups, the round. */
    void update(float dt) {
        roundTime += dt;
        float k = k();
        for (int i = feedT.size() - 1; i >= 0; i--) {
            feedT.set(i, feedT.get(i) - dt);
            if (feedT.get(i) <= 0) { feedT.remove(i); feed.remove(i); }
        }
        if (!suddenDeath && roundTime > LENGTH_SUDDEN[length] && mode == MODE_LAST && !roundOver) {
            suddenDeath = true;
            suddenLeak = 0f;
            addFeed("SUDDEN DEATH: every hull springs a leak!");
            if (!demo) g.setMessage("SUDDEN DEATH", 2.5f);
            crates.clear();
        }
        if (suddenDeath && !roundOver) {
            suddenLeak -= dt;
            if (suddenLeak <= 0) {
                suddenLeak = 20f;
                for (Boat b : g.boats) if (!b.out) b.holes++;
            }
        }
        for (float[] row : ramCool) for (int j = 0; j < row.length; j++) row[j] -= dt;
        Boat[] boats = g.boats;
        for (Boat b : boats) {
            if (b.out) { sinking(b, dt); continue; }
            timers(b, dt);
            weapon(b, dt);
            if (b.useSpecial) { b.useSpecial = false; useSpecial(b); }
            hullDamage(b, dt);
        }
        rams();
        tethers(dt);
        updateShots(dt);
        updateMines(dt);
        updateAreas(dt);
        updateCrates(dt);
        fx.update(dt);
        checkRound(dt);
    }

    private void timers(Boat b, float dt) {
        b.shield = Math.max(0f, b.shield - dt);
        b.nitro = Math.max(0f, b.nitro - dt);
        b.emp = Math.max(0f, b.emp - dt);
        b.oiled = Math.max(0f, b.oiled - dt);
        b.power = Math.max(0f, b.power - dt);
        b.cool = Math.max(0f, b.cool - dt);
        b.switchT = Math.max(0f, b.switchT - dt);
        // weapons not in use reload in the background
        for (int w = 0; w < Weapons.COUNT; w++)
            if (w != b.weapon && b.ammoOf[w] < Weapons.MAG[w] && Weapons.RELOAD[w] > 0)
                b.ammoOf[w] = Math.min(Weapons.MAG[w], b.ammoOf[w] + dt * Weapons.MAG[w] / (Weapons.RELOAD[w] * 1.5f));
        if (b.reload > 0) {
            b.reload -= dt;
            if (b.reload <= 0) { b.reload = 0; b.ammo = Weapons.MAG[b.weapon]; }
        }
        float k = k();
        if (b.nitro > 0 && rnd.nextFloat() < dt * 30f) {
            float sx = b.x - b.fwdX() * 0.7f * k, sz = b.z - b.fwdZ() * 0.7f * k;
            fx.emit(sx, b.y + 0.15f * k, sz, -b.fwdX() * 3f * k, 0.3f * k, -b.fwdZ() * 3f * k, 0.25f,
                    0.12f * k, 0.35f * k, 0.4f, 0.7f, 1f, 0.9f, 0f, true);
        }
    }

    /** Fire, leaks and the arena boundary. */
    private void hullDamage(Boat b, float dt) {
        float k = k();
        if (b.burn > 0) {
            b.burn -= dt * (b.forwardSpeed() > b.topSpeed * 0.7f ? 1.6f : 1f);
            damage(b, Weapons.BURN_DPS * dt, b.lastHitBy, "burned");
            if (rnd.nextFloat() < dt * 40f) {
                float ox = (rnd.nextFloat() - 0.5f) * 0.6f * k, oz = (rnd.nextFloat() - 0.5f) * 0.9f * k;
                fx.emit(b.x + ox, b.y + 0.2f * k, b.z + oz, b.vx * 0.5f, 0.6f * k, b.vz * 0.5f, 0.5f,
                        0.12f * k, 0.3f * k, 1f, 0.6f, 0.15f, 0.9f, 1.2f * k, true);
                fx.emit(b.x + ox, b.y + 0.4f * k, b.z + oz, b.vx * 0.3f, 0.8f * k, b.vz * 0.3f, 1.6f,
                        0.2f * k, 0.9f * k, 0.12f, 0.11f, 0.1f, 0.5f, 0.6f * k, false);
            }
        }
        float in = b.holes * Weapons.LEAK_RATE - Weapons.PUMP_RATE;
        b.water = Math.max(0f, b.water + in * dt);
        if (b.holes > 0 && rnd.nextFloat() < dt * 6f * b.holes) {
            // water gushing in: bubbles at the hull
            g.particles.emit(b.x + (rnd.nextFloat() - 0.5f) * 0.4f * k, b.z + (rnd.nextFloat() - 0.5f) * 0.4f * k,
                    0, 0, 0.8f, 0.05f * k, 0.3f * k, 0.6f);
        }
        if (b.water >= 1f) {
            b.water = 1f;
            eliminate(b, b.lastHitBy, "sank");
            return;
        }
        if (g.lifeSize && !inArena(b.x, b.z, 1.04f)) {
            damage(b, 5f * dt, null, "left the arena");
            if (b.isPlayer && g.messageTime <= 0) g.setMessage("BACK INTO THE ARENA!", 1f);
        }
        if (b.hp <= 0 && !b.out) eliminate(b, b.lastHitBy, "destroyed");
    }

    // ------------------------------------------------------------------ weapons

    /** The enemy best placed for this boat's weapon right now, or null. */
    Boat bestTarget(Boat b, float range, float arc) {
        Boat best = null;
        float bestScore = Float.MAX_VALUE;
        for (Boat o : g.boats) {
            if (o == b || o.out) continue;
            float dx = o.x - b.x, dz = o.z - b.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > range) continue;
            float ang = Math.abs(Boat.wrapAngle(bearing(dx, dz) - b.heading));
            if (b.weapon == Weapons.MINES) ang = Math.abs(Boat.wrapAngle(ang - (float) Math.PI));
            if (ang > arc + 0.12f) continue;
            if (hiddenBySmoke(b, o)) continue;
            float score = d + ang * range * 0.6f;
            if (score < bestScore) { bestScore = score; best = o; }
        }
        return best;
    }

    static float bearing(float dx, float dz) {
        return (float) Math.atan2(-dx, -dz);
    }

    private boolean hiddenBySmoke(Boat from, Boat to) {
        for (Area s : smokes) {
            // the target inside a cloud, or the line of sight through one
            float ax = from.x, az = from.z, bx = to.x, bz = to.z;
            float lx = bx - ax, lz = bz - az, l2 = lx * lx + lz * lz;
            float t = l2 > 0 ? Math.max(0f, Math.min(1f, ((s.x - ax) * lx + (s.z - az) * lz) / l2)) : 0f;
            float px = ax + lx * t - s.x, pz = az + lz * t - s.z;
            if (px * px + pz * pz < s.r * s.r) return true;
        }
        return false;
    }

    /** Whether the weapon has something worth shooting at (auto fire and the AI). */
    boolean wantsFire(Boat b) {
        if (b.out || b.emp > 0) return false;
        int w = b.weapon;
        float k = k();
        float range = Weapons.RANGE[w] * k;
        if (w == Weapons.DRILL) {
            Boat t = bestTarget(b, 4f * k, 0.5f);
            return t != null;
        }
        if (w == Weapons.MINES) {
            // an enemy close by: behind, or beside us as we circle
            Boat t = bestTarget(b, range, 1.9f);
            return t != null;
        }
        float arc = Weapons.ARC[w];
        Boat t = bestTarget(b, range * (w == Weapons.ROCKETS || w == Weapons.TORPEDO ? 0.85f : 1f),
                arc > 0 ? arc : 0.22f);
        if (t == null) return false;
        if (arc > 0) {
            float want = Boat.wrapAngle(bearing(t.x - b.x, t.z - b.z) - b.heading);
            return Math.abs(Boat.wrapAngle(want - b.aimYaw)) < 0.16f;
        }
        return true;
    }

    private void weapon(Boat b, float dt) {
        int w = b.weapon;
        float k = k();
        float arc = Weapons.ARC[w];
        // the turret tracks the best target in its arc
        if (arc > 0) {
            Boat t = bestTarget(b, Weapons.RANGE[w] * k * 1.25f, arc);
            b.aimAt = t;
            float want = 0f;
            if (t != null) {
                float lead = w == Weapons.MACHINE_GUN ? 0.25f : w == Weapons.HARPOON ? 0.3f : 0f;
                float d = dist(b, t);
                float tt = lead * d / (Weapons.RANGE[w] * k);
                want = Boat.wrapAngle(bearing(t.x + t.vx * tt - b.x, t.z + t.vz * tt - b.z) - b.heading);
                want = Math.max(-arc, Math.min(arc, want));
            }
            float step = 5f * dt;
            b.aimYaw += Math.max(-step, Math.min(step, Boat.wrapAngle(want - b.aimYaw)));
        } else {
            b.aimAt = bestTarget(b, Weapons.RANGE[w] * k * 1.25f, 0.4f);
        }
        boolean continuous = w == Weapons.FLAMETHROWER || w == Weapons.WATER_CANNON;
        if (continuous && !b.fire && b.reload <= 0)
            b.ammo = Math.min(Weapons.MAG[w], b.ammo + dt * Weapons.MAG[w] / Weapons.RELOAD[w] * 0.6f);
        if (w == Weapons.DRILL) return;   // the drill works on contact (see rams)
        if (!b.fire || b.reload > 0 || b.cool > 0 || b.switchT > 0) return;
        float dir = b.heading + b.aimYaw;
        float fxd = (float) -Math.sin(dir), fzd = (float) -Math.cos(dir);
        float mx = b.x + b.fwdX() * 0.35f * k, mz = b.z + b.fwdZ() * 0.35f * k, my = b.y + 0.32f * k;
        float pw = b.power > 0 ? 2f : 1f;
        switch (w) {
            case Weapons.MACHINE_GUN: {
                float sp = 75f * k;
                float jit = (rnd.nextFloat() - 0.5f) * (0.03f + b.aimJitter);
                float d2 = dir + jit;
                Shot s = shot(SHOT_BULLET, b, mx, my, mz, (float) -Math.sin(d2) * sp + b.vx,
                        (rnd.nextFloat() - 0.5f) * 0.6f * k, (float) -Math.cos(d2) * sp + b.vz,
                        Weapons.RANGE[w] * k / sp * 1.15f, Weapons.BULLET_DMG * pw);
                s.tracer = true;
                b.cool = Weapons.RATE[w];
                fx.emit(mx + fxd * 0.12f * k, my, mz + fzd * 0.12f * k, b.vx, 0, b.vz, 0.05f, 0.12f * k,
                        0.2f * k, 1f, 0.85f, 0.4f, 1f, 0f, true);
                sfx(S_GUN, b, 0.55f);
                useAmmo(b, 1f);
                break;
            }
            case Weapons.FLAMETHROWER: {
                for (int i = 0; i < 3; i++) {
                    float sp = (11f + rnd.nextFloat() * 4f) * k;
                    float d2 = dir + (rnd.nextFloat() - 0.5f) * 0.32f;
                    fx.emit(mx, my, mz, (float) -Math.sin(d2) * sp + b.vx, (rnd.nextFloat() * 0.8f) * k,
                            (float) -Math.cos(d2) * sp + b.vz, 0.42f + rnd.nextFloat() * 0.12f,
                            0.08f * k, 0.75f * k, 1f, 0.75f, 0.25f, 0.85f, 0.8f * k, true);
                }
                if (rnd.nextFloat() < 0.3f)
                    fx.emit(mx + fxd * 4f * k, my + 0.4f * k, mz + fzd * 4f * k, b.vx * 0.5f, 0.6f * k, b.vz * 0.5f,
                            1.3f, 0.4f * k, 1.2f * k, 0.15f, 0.13f, 0.12f, 0.35f, 0.5f * k, false);
                for (Boat o : g.boats) {
                    if (o == b || o.out) continue;
                    if (inCone(b, o, dir, Weapons.RANGE[w] * k, 0.3f)) {
                        damage(o, Weapons.FLAME_DPS * dt * pw, b, "torched");
                        o.burn = Math.max(o.burn, Weapons.BURN_TIME);
                    }
                }
                for (Area s : slicks) {
                    float dx = s.x - (mx + fxd * 4f * k), dz = s.z - (mz + fzd * 4f * k);
                    if (dx * dx + dz * dz < s.r * s.r && s.burn <= 0) igniteSlick(s, b);
                }
                flameSfx -= dt;
                if (flameSfx <= 0) { flameSfx = 0.28f; sfx(S_FLAME, b, 0.7f); }
                useAmmo(b, dt);
                break;
            }
            case Weapons.TORPEDO: {
                float sp = 19f * k;
                Shot s = shot(SHOT_TORPEDO, b, b.x + b.fwdX() * 0.7f * k, 0f, b.z + b.fwdZ() * 0.7f * k,
                        fxd * sp, 0, fzd * sp, 3.6f, Weapons.TORPEDO_DMG * pw);
                s.target = b.aimAt;
                s.heading = dir;
                b.cool = Weapons.RATE[w];
                g.spray.emit(s.x, 0.05f * k, s.z, fxd * 2f * k, 1.5f * k, fzd * 2f * k, 0.6f, 0.08f * k, 0.3f * k, 0.8f);
                sfx(S_TORPEDO, b, 0.9f);
                useAmmo(b, 1f);
                break;
            }
            case Weapons.HARPOON: {
                float sp = 42f * k;
                Shot s = shot(SHOT_HARPOON, b, mx, my, mz, fxd * sp + b.vx, 0.6f * k, fzd * sp + b.vz,
                        Weapons.RANGE[w] * k / sp * 1.2f, Weapons.HARPOON_DMG * pw);
                s.target = b.aimAt;
                s.heading = dir;
                sfx(S_HARPOON, b, 0.9f);
                if (b.tether != null) b.tether = null;   // a new shot lets go of the old line
                useAmmo(b, 1f);
                break;
            }
            case Weapons.MINES: {
                int own = 0;
                Mine oldest = null;
                for (Mine m : mines) if (m.owner == b) { own++; if (oldest == null) oldest = m; }
                if (own >= 8 && oldest != null) mines.remove(oldest);
                Mine m = new Mine();
                m.x = b.x - b.fwdX() * 0.85f * k;
                m.z = b.z - b.fwdZ() * 0.85f * k;
                m.arm = Weapons.MINE_ARM;
                m.life = 75f;
                m.owner = b;
                mines.add(m);
                b.cool = Weapons.RATE[w];
                g.spray.emit(m.x, 0.02f * k, m.z, 0, 1.2f * k, 0, 0.4f, 0.06f * k, 0.18f * k, 0.6f);
                sfx(S_MINE, b, 0.7f);
                useAmmo(b, 1f);
                break;
            }
            case Weapons.WATER_CANNON: {
                for (int i = 0; i < 4; i++) {
                    float sp = (19f + rnd.nextFloat() * 4f) * k;
                    float d2 = dir + (rnd.nextFloat() - 0.5f) * 0.07f;
                    g.spray.emit(mx, my, mz, (float) -Math.sin(d2) * sp + b.vx, (1.6f + rnd.nextFloat()) * k,
                            (float) -Math.cos(d2) * sp + b.vz, 0.9f, 0.03f * k, 0.17f * k, 0.7f);
                }
                for (Boat o : g.boats) {
                    if (o == b || o.out) continue;
                    float range = Weapons.RANGE[w] * k;
                    if (inCone(b, o, dir, range, 0.28f)) {
                        float d = dist(b, o);
                        float f = 1f - d / range * 0.6f;
                        float push = Weapons.CANNON_PUSH * k * dt * f;
                        if (o.shield <= 0) {
                            o.vx += fxd * push;
                            o.vz += fzd * push;
                            o.yawRate += (rnd.nextFloat() - 0.5f) * 6f * dt;
                            o.water = Math.min(0.97f, o.water + Weapons.CANNON_SWAMP * dt * f);
                        }
                        o.burn = 0;
                        damage(o, Weapons.CANNON_DPS * dt * pw, b, "swamped");
                    }
                }
                for (Area s : slicks) {
                    // washes burning oil away
                    float dx = s.x - (mx + fxd * 5f * k), dz = s.z - (mz + fzd * 5f * k);
                    if (dx * dx + dz * dz < s.r * s.r) s.burn = 0;
                }
                cannonSfx -= dt;
                if (cannonSfx <= 0) { cannonSfx = 0.3f; sfx(S_CANNON, b, 0.6f); }
                useAmmo(b, dt);
                break;
            }
            case Weapons.ROCKETS: {
                float sp = 30f * k;
                float d2 = dir + (b.ammo % 2 == 0 ? 0.08f : -0.08f);
                Shot s = shot(SHOT_ROCKET, b, mx, my + 0.1f * k, mz, (float) -Math.sin(d2) * sp + b.vx, 0f,
                        (float) -Math.cos(d2) * sp + b.vz, 1.3f, Weapons.ROCKET_DMG * pw);
                s.target = b.aimAt;
                b.cool = Weapons.RATE[w];
                sfx(S_ROCKET, b, 0.7f);
                useAmmo(b, 1f);
                // a salvo is four: pause after each four
                if (((int) b.ammo) % 4 == 0 && b.ammo > 0) b.cool = 1.1f;
                break;
            }
        }
    }

    static final float SWITCH_TIME = 0.9f;

    /** Changes to another weapon: the old one keeps its ammunition; a short pause to swap. */
    void switchWeapon(Boat b, int w) {
        if (b.out || w == b.weapon || w < 0 || w >= Weapons.COUNT) return;
        b.ammoOf[b.weapon] = b.reload > 0 ? Math.min(b.ammo, 0f) : b.ammo;
        b.weapon = w;
        b.ammo = b.ammoOf[w];
        b.reload = b.ammo <= 0 && Weapons.RELOAD[w] > 0 ? Weapons.RELOAD[w] * 0.7f : 0f;
        b.switchT = SWITCH_TIME;
        b.aimYaw = 0;
        b.aimAt = null;
        if (w != Weapons.HARPOON) b.tether = null;
        sfx(S_CLANG, b, 0.5f);
        if (b.isPlayer) g.setMessage(Weapons.NAMES[w].toUpperCase(), 1.0f);
    }

    private void useAmmo(Boat b, float n) {
        b.ammo -= n;
        if (b.ammo <= 0) {
            b.ammo = 0;
            b.reload = Weapons.RELOAD[b.weapon];
        }
    }

    private boolean inCone(Boat b, Boat o, float dir, float range, float half) {
        float dx = o.x - b.x, dz = o.z - b.z;
        float d = (float) Math.sqrt(dx * dx + dz * dz);
        if (d > range + o.circleR()) return false;
        float off = Math.abs(Boat.wrapAngle(bearing(dx, dz) - dir));
        // the boat's own width makes it easier to hit up close
        return off < half + (float) Math.atan2(o.circleR(), Math.max(d, 0.01f));
    }

    private Shot shot(int kind, Boat owner, float x, float y, float z, float vx, float vy, float vz,
                      float life, float dmg) {
        STAT_SHOTS[owner.weapon]++;
        Shot s = new Shot();
        s.kind = kind;
        s.owner = owner;
        s.x = x; s.y = y; s.z = z;
        s.vx = vx; s.vy = vy; s.vz = vz;
        s.life = life;
        s.dmg = dmg;
        shots.add(s);
        return s;
    }

    private void updateShots(float dt) {
        float k = k();
        for (int i = shots.size() - 1; i >= 0; i--) {
            Shot s = shots.get(i);
            s.life -= dt;
            boolean dead = s.life <= 0;
            if (s.kind == SHOT_TORPEDO || s.kind == SHOT_ROCKET) {
                // homing
                Boat t = s.target;
                float sp = (float) Math.sqrt(s.vx * s.vx + s.vz * s.vz);
                float h = bearing(s.vx, s.vz);
                if (t != null && !t.out && !hiddenBySmoke(s.owner, t)) {
                    float want = bearing(t.x - s.x, t.z - s.z);
                    float turn = (s.kind == SHOT_TORPEDO ? 1.1f : 2.0f) * dt;
                    h += Math.max(-turn, Math.min(turn, Boat.wrapAngle(want - h)));
                }
                s.vx = (float) -Math.sin(h) * sp;
                s.vz = (float) -Math.cos(h) * sp;
                s.heading = h;
            }
            if (s.kind == SHOT_ROCKET) {
                // skims the water at a steady height
                s.vy = (0.42f * k - s.y) * 3f;
                if (rnd.nextFloat() < 0.9f)
                    fx.emit(s.x, s.y, s.z, 0, 0.2f * k, 0, 0.7f, 0.06f * k, 0.35f * k, 0.6f, 0.6f, 0.62f, 0.45f,
                            0.2f * k, false);
                fx.emit(s.x, s.y, s.z, -s.vx * 0.05f, 0, -s.vz * 0.05f, 0.08f, 0.1f * k, 0.05f * k,
                        1f, 0.7f, 0.3f, 0.9f, 0f, true);
            } else if (s.kind == SHOT_HARPOON || s.kind == SHOT_BULLET) {
                s.vy -= (s.kind == SHOT_HARPOON ? 4f : 1.5f) * k * dt;
            } else if (s.kind == SHOT_TORPEDO && rnd.nextFloat() < 0.7f) {
                g.particles.emit(s.x, s.z, 0, 0, 1.2f, 0.06f * k, 0.28f * k, 0.55f);
            }
            float nx = s.x + s.vx * dt, ny = s.y + s.vy * dt, nz = s.z + s.vz * dt;
            // what did it hit along the way?
            Boat hit = null;
            float hitR = s.kind == SHOT_TORPEDO ? 0.35f * k : s.kind == SHOT_ROCKET ? 0.3f * k : 0.12f * k;
            for (Boat o : g.boats) {
                if (o == s.owner || o.out) continue;
                if (segmentHits(s.x, s.z, nx, nz, o, hitR) && ny < o.y + 0.7f * k) { hit = o; break; }
            }
            if (hit == null && s.kind != SHOT_TORPEDO && ny < World.wave(nx, nz, g.time) - 0.02f * k) {
                // into the water
                if (s.kind == SHOT_ROCKET) explode(nx, nz, s.owner, s.dmg * 0.6f, Weapons.ROCKET_SPLASH * k, false);
                else g.spray.emit(nx, 0.02f * k, nz, 0, 1.4f * k, 0, 0.35f, 0.04f * k, 0.12f * k, 0.6f);
                dead = true;
            }
            if (hit == null && !World.isWater(nx, nz, islandOn()) && World.height(nx, nz, islandOn()) > ny) {
                if (s.kind == SHOT_ROCKET || s.kind == SHOT_TORPEDO)
                    explode(nx, nz, s.owner, s.dmg * 0.5f, Weapons.ROCKET_SPLASH * k, false);
                dead = true;
            }
            if (hit != null) {
                STAT_HITS[s.owner.weapon]++;
                dead = true;
                switch (s.kind) {
                    case SHOT_BULLET:
                        damage(hit, s.dmg, s.owner, "shot");
                        sparks(nx, ny, nz, 3);
                        if (rnd.nextFloat() < 0.25f) sfx(S_CLANG, hit, 0.4f);
                        break;
                    case SHOT_TORPEDO:
                        damage(hit, s.dmg, s.owner, "torpedoed");
                        hole(hit, s.owner);
                        knock(hit, s.vx, s.vz, 0.35f);
                        explode(nx, nz, s.owner, 0f, 0f, true);
                        break;
                    case SHOT_HARPOON:
                        damage(hit, s.dmg, s.owner, "harpooned");
                        sparks(nx, ny, nz, 6);
                        if (!s.owner.out) {
                            s.owner.tether = hit;
                            s.owner.tetherLen = dist(s.owner, hit);
                            s.owner.tetherT = Weapons.TETHER_TIME;
                        }
                        sfx(S_CLANG, hit, 0.9f);
                        break;
                    case SHOT_ROCKET:
                        damage(hit, s.dmg, s.owner, "blasted");
                        if (rnd.nextFloat() < 0.18f) hole(hit, s.owner);
                        explode(nx, nz, s.owner, s.dmg * 0.5f, Weapons.ROCKET_SPLASH * k, false);
                        break;
                }
            }
            s.x = nx; s.y = ny; s.z = nz;
            if (dead) shots.remove(i);
        }
    }

    private static boolean segmentHits(float ax, float az, float bx, float bz, Boat o, float extra) {
        for (int c = 0; c < o.circles(); c++) {
            float cx = o.circleX(c), cz = o.circleZ(c), r = o.circleR() + extra;
            float lx = bx - ax, lz = bz - az, l2 = lx * lx + lz * lz;
            float t = l2 > 0 ? Math.max(0f, Math.min(1f, ((cx - ax) * lx + (cz - az) * lz) / l2)) : 0f;
            float px = ax + lx * t - cx, pz = az + lz * t - cz;
            if (px * px + pz * pz < r * r) return true;
        }
        return false;
    }

    private void updateMines(float dt) {
        float k = k();
        for (int i = mines.size() - 1; i >= 0; i--) {
            Mine m = mines.get(i);
            m.arm -= dt;
            m.life -= dt;
            if (m.life <= 0 || (m.owner.out && m.life > 8f)) {
                // unowned mines fizzle out after a while
                if (m.life <= 0) { mines.remove(i); continue; }
                m.life = Math.min(m.life, 8f);
            }
            if (m.arm > 0) continue;
            // magnetic: creeps towards an enemy hull close by
            Boat near = null;
            float nd = 7f * k;
            for (Boat o : g.boats) {
                if (o.out || o == m.owner) continue;
                float dd = (float) Math.hypot(o.x - m.x, o.z - m.z);
                if (dd < nd) { nd = dd; near = o; }
            }
            if (near != null && nd > 0.01f) {
                float sp = 3.5f * k * dt;
                m.x += (near.x - m.x) / nd * sp;
                m.z += (near.z - m.z) / nd * sp;
            }
            for (Boat o : g.boats) {
                if (o.out || (o == m.owner && m.arm > -2.5f)) continue;
                float dx = o.x - m.x, dz = o.z - m.z;
                float r = Weapons.MINE_RADIUS * k + o.circleR();
                if (dx * dx + dz * dz < r * r) {
                    mines.remove(i);
                    explode(m.x, m.z, m.owner, Weapons.MINE_DMG * (m.owner.power > 0 ? 2f : 1f),
                            2.6f * k, true);
                    hole(o, m.owner);
                    break;
                }
            }
        }
    }

    /** A blast on the water: damage falling off with distance, spray, fire and smoke. */
    private void explode(float x, float z, Boat by, float dmg, float radius, boolean big) {
        float k = k();
        for (Boat o : g.boats) {
            if (o.out || radius <= 0) continue;
            float dx = o.x - x, dz = o.z - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d < radius + o.circleR()) {
                float f = 1f - Math.max(0f, d - o.circleR()) / radius;
                damage(o, dmg * Math.max(0.2f, f), by, big ? "blew up" : "blasted");
                if (d > 0.01f) { o.vx += dx / d * 4f * k * f; o.vz += dz / d * 4f * k * f; }
            }
        }
        for (Area s : slicks) {
            float dx = s.x - x, dz = s.z - z;
            if (dx * dx + dz * dz < (s.r + radius) * (s.r + radius)) igniteSlick(s, by);
        }
        float n = big ? 1.6f : 1f;
        for (int i = 0; i < (int) (26 * n); i++) {
            float a = rnd.nextFloat() * 6.283f, sp = (1.5f + rnd.nextFloat() * 4f) * k * n;
            g.spray.emit(x, 0.05f * k, z, (float) Math.cos(a) * sp * 0.5f, (3f + rnd.nextFloat() * 5f) * k * n,
                    (float) Math.sin(a) * sp * 0.5f, 1.4f, 0.08f * k, 0.5f * k * n, 0.9f);
        }
        for (int i = 0; i < (int) (16 * n); i++) {
            float a = rnd.nextFloat() * 6.283f, sp = rnd.nextFloat() * 3f * k * n;
            fx.emit(x, 0.3f * k, z, (float) Math.cos(a) * sp, (1f + rnd.nextFloat() * 3f) * k, (float) Math.sin(a) * sp,
                    0.35f + rnd.nextFloat() * 0.3f, 0.3f * k * n, 1.2f * k * n, 1f, 0.7f, 0.25f, 1f, 1f * k, true);
            fx.emit(x, 0.5f * k, z, (float) Math.cos(a) * sp * 0.5f, (0.5f + rnd.nextFloat()) * k,
                    (float) Math.sin(a) * sp * 0.5f, 2.2f + rnd.nextFloat(), 0.3f * k * n, 1.6f * k * n,
                    0.16f, 0.15f, 0.14f, 0.4f, 0.5f * k, false);
        }
        fx.emit(x, 0.4f * k, z, 0, 0, 0, 0.18f, 1.5f * k * n, 3f * k * n, 1f, 0.95f, 0.8f, 1f, 0f, true);
        g.particles.emit(x, z, 0, 0, 2.5f, 0.5f * k * n, 3.5f * k * n, 0.8f);
        sfxAt(big ? S_BIG_BOOM : S_BOOM, x, z, 1f);
    }

    private void sparks(float x, float y, float z, int n) {
        float k = k();
        for (int i = 0; i < n; i++) {
            fx.emit(x, y, z, (rnd.nextFloat() - 0.5f) * 4f * k, (1f + rnd.nextFloat() * 2f) * k,
                    (rnd.nextFloat() - 0.5f) * 4f * k, 0.25f, 0.05f * k, 0.02f * k, 1f, 0.85f, 0.45f, 1f,
                    -9f * k, true);
        }
    }

    private void knock(Boat o, float vx, float vz, float f) {
        if (o.shield > 0) return;
        o.vx += vx * f * 0.3f;
        o.vz += vz * f * 0.3f;
        o.yawRate += (rnd.nextFloat() - 0.5f) * 5f;
        o.pitch -= 0.25f;
    }

    private void hole(Boat o, Boat by) {
        if (o.shield > 0 || o.out) return;
        o.holes++;
        o.lastHitBy = by;
        if (o.isPlayer) g.setMessage(o.holes == 1 ? "HOLED! TAKING ON WATER" : "ANOTHER HOLE!", 1.6f);
    }

    /** Ramming and the bow drill. */
    private void rams() {
        Boat[] boats = g.boats;
        float k = k();
        for (int i = 0; i < boats.length; i++) {
            Boat a = boats[i];
            if (a.out) continue;
            for (int j = i + 1; j < boats.length; j++) {
                Boat c = boats[j];
                if (c.out) continue;
                float dx = c.x - a.x, dz = c.z - a.z;
                float d = (float) Math.sqrt(dx * dx + dz * dz);
                float reach = a.circleR() + c.circleR() + (a.bodyOff + c.bodyOff) + 0.25f * k;
                if (d > reach || d < 1e-4f) continue;
                float slack = (a.weapon == Weapons.DRILL || c.weapon == Weapons.DRILL) ? 0.35f * k : 0.12f * k;
                if (!touching(a, c, slack)) continue;
                float nx = dx / d, nz = dz / d;
                float va = a.vx * nx + a.vz * nz, vc = c.vx * nx + c.vz * nz;
                float closing = va - vc;
                // the drill: bow first into the other hull
                drill(a, c, nx, nz, i, j);
                drill(c, a, -nx, -nz, j, i);
                if (closing > 2.5f * k && ramCool[i][j] <= 0) {
                    ramCool[i][j] = 0.6f;
                    float dmg = closing / k * 1.4f;
                    // whoever was driving into the other takes less
                    Boat hitter = va > -vc ? a : c, hit = hitter == a ? c : a;
                    float bonus = (hitter.nitro > 0 ? 2f : 1f) * (hitter.weapon == Weapons.DRILL ? 1.8f : 1f);
                    damage(hit, dmg * bonus, hitter, "rammed");
                    damage(hitter, dmg * 0.35f, hit, "rammed");
                    sparks((a.x + c.x) / 2f, a.y + 0.2f * k, (a.z + c.z) / 2f, 8);
                    sfxAt(S_RAM, (a.x + c.x) / 2f, (a.z + c.z) / 2f, Math.min(1f, closing / (6f * k)));
                    if (!demo && (a.isPlayer || c.isPlayer)) say("bump", 5, Announcer.NORMAL);
                }
            }
        }
    }

    private static boolean touching(Boat a, Boat c, float slack) {
        float min = a.circleR() + c.circleR() + slack;
        for (int ka = 0; ka < a.circles(); ka++)
            for (int kc = 0; kc < c.circles(); kc++) {
                float dx = c.circleX(kc) - a.circleX(ka), dz = c.circleZ(kc) - a.circleZ(ka);
                if (dx * dx + dz * dz < min * min) return true;
            }
        return false;
    }

    private void drill(Boat a, Boat c, float nx, float nz, int i, int j) {
        if (a.weapon != Weapons.DRILL || !a.fire || a.emp > 0 || a.switchT > 0) return;
        // c must be in front of a's bow
        float fwd = nx * a.fwdX() + nz * a.fwdZ();
        if (fwd < 0.6f) return;
        if (a.cool > 0) return;
        a.cool = 0.45f;
        float pw = a.power > 0 ? 2f : 1f;
        damage(c, Weapons.DRILL_DMG * pw, a, "drilled");
        hole(c, a);
        float k = k();
        float px = a.x + a.fwdX() * (0.7f * k + a.bodyOff), pz = a.z + a.fwdZ() * (0.7f * k + a.bodyOff);
        sparks(px, a.y + 0.15f * k, pz, 14);
        sfx(S_DRILL, a, 1f);
    }

    private void tethers(float dt) {
        float k = k();
        for (Boat b : g.boats) {
            Boat t = b.tether;
            if (t == null) continue;
            b.tetherT -= dt;
            if (b.tetherT <= 0 || t.out || b.out) { b.tether = null; continue; }
            // reel in, and haul the speared boat along behind
            b.tetherLen = Math.max(2.5f * k, b.tetherLen - 2.5f * k * dt);
            float dx = t.x - b.x, dz = t.z - b.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            if (d > b.tetherLen && d > 1e-3f) {
                float nx = dx / d, nz = dz / d;
                float over = d - b.tetherLen;
                float rel = (t.vx - b.vx) * nx + (t.vz - b.vz) * nz;
                float imp = over * 6f * dt + Math.max(0f, rel) * 0.9f;
                if (t.shield <= 0) { t.vx -= nx * imp * 0.8f; t.vz -= nz * imp * 0.8f; }
                b.vx += nx * imp * 0.2f;
                b.vz += nz * imp * 0.2f;
            }
            damage(t, 3f * dt, b, "harpooned");
        }
    }

    // ------------------------------------------------------------------ pickups and areas

    private void useSpecial(Boat b) {
        int s = b.special;
        if (s < 0) return;
        b.special = -1;
        float k = k();
        switch (s) {
            case Weapons.PU_NITRO:
                b.nitro = Weapons.NITRO_TIME;
                sfx(S_ROCKET, b, 0.8f);
                break;
            case Weapons.PU_SHIELD:
                b.shield = Weapons.SHIELD_TIME;
                b.burn = 0;
                sfx(S_SHIELD, b, 1f);
                break;
            case Weapons.PU_EMP: {
                float r = Weapons.EMP_RADIUS * k;
                int hitN = 0;
                for (Boat o : g.boats) {
                    if (o == b || o.out || o.shield > 0) continue;
                    if (dist(b, o) < r) { o.emp = Weapons.EMP_TIME; o.lastHitBy = b; hitN++; }
                }
                for (int i = 0; i < 70; i++) {
                    float a = 6.283f * i / 70;
                    fx.emit(b.x, b.y + 0.3f * k, b.z, (float) Math.cos(a) * r * 2f, 0, (float) Math.sin(a) * r * 2f,
                            0.45f, 0.3f * k, 0.6f * k, 0.6f, 0.4f, 1f, 1f, 0f, true);
                }
                sfx(S_EMP, b, 1f);
                if (b.isPlayer) g.setMessage(hitN > 0 ? "EMP: " + hitN + (hitN == 1 ? " BOAT" : " BOATS") + " STALLED" : "EMP MISSED", 1.5f);
                break;
            }
            case Weapons.PU_OIL: {
                Area a = new Area();
                a.x = b.x - b.fwdX() * 2f * k;
                a.z = b.z - b.fwdZ() * 2f * k;
                a.r = Weapons.OIL_RADIUS * k;
                a.life = Weapons.OIL_TIME;
                a.owner = b;
                slicks.add(a);
                sfx(S_SPLASH, b, 0.6f);
                break;
            }
            case Weapons.PU_SMOKE: {
                Area a = new Area();
                a.x = b.x;
                a.z = b.z;
                a.r = Weapons.SMOKE_RADIUS * k;
                a.life = Weapons.SMOKE_TIME;
                a.owner = b;
                smokes.add(a);
                b.smokeT = Weapons.SMOKE_TIME;
                sfx(S_SPLASH, b, 0.4f);
                break;
            }
        }
    }

    private void pickup(Boat b, Crate c) {
        int s = c.kind;
        float k = k();
        sfx(S_PICKUP, b, 1f);
        for (int i = 0; i < 12; i++)
            fx.emit(c.x, 0.3f * k, c.z, (rnd.nextFloat() - 0.5f) * 3f * k, (1f + rnd.nextFloat() * 2f) * k,
                    (rnd.nextFloat() - 0.5f) * 3f * k, 0.5f, 0.12f * k, 0.03f * k, Weapons.PU_COLOR[s][0],
                    Weapons.PU_COLOR[s][1], Weapons.PU_COLOR[s][2], 1f, -4f * k, true);
        if (Weapons.PU_INSTANT[s]) {
            switch (s) {
                case Weapons.PU_REPAIR:
                    b.hp = Math.min(b.maxHp, b.hp + Weapons.REPAIR_HP * LENGTH_HP[length]);
                    b.holes = Math.max(0, b.holes - 1);
                    b.burn = 0;
                    break;
                case Weapons.PU_PUMP:
                    b.holes = 0;
                    b.water = 0;
                    break;
                case Weapons.PU_POWER:
                    b.power = Weapons.POWER_TIME;
                    break;
            }
        } else {
            b.special = s;
        }
        if (b.isPlayer) g.setMessage(Weapons.PU_NAMES[s] + (Weapons.PU_INSTANT[s] ? "!" : "  (tap SPECIAL)"), 1.6f);
    }

    private void updateCrates(float dt) {
        float k = k();
        if (pickupsOn && !suddenDeath) {
            crateTimer -= dt;
            int maxCrates = 3 + g.boats.length / 2;
            if (crateTimer <= 0 && crates.size() < maxCrates && !roundOver) {
                crateTimer = (5f + rnd.nextFloat() * 5f) * LENGTH_CRATES[length];
                spawnCrate();
            }
        }
        for (int i = crates.size() - 1; i >= 0; i--) {
            Crate c = crates.get(i);
            c.life -= dt;
            c.spin += dt;
            if (c.life <= 0) { crates.remove(i); continue; }
            for (Boat b : g.boats) {
                if (b.out) continue;
                float dx = b.x - c.x, dz = b.z - c.z;
                float r = b.circleR() + 0.6f * k + b.bodyOff;
                if (dx * dx + dz * dz < r * r) {
                    // a held special can't be swapped by driving over another one
                    if (!Weapons.PU_INSTANT[c.kind] && b.special >= 0) continue;
                    pickup(b, c);
                    crates.remove(i);
                    break;
                }
            }
        }
    }

    private void spawnCrate() {
        float k = k();
        float total = 0;
        for (float w : Weapons.PU_WEIGHT) total += w;
        for (int t = 0; t < 60; t++) {
            float x = (rnd.nextFloat() * 2f - 1f) * arenaRx * 0.8f, z = (rnd.nextFloat() * 2f - 1f) * arenaRz * 0.8f;
            if (!inArena(x, z, 0.85f) || !World.isWater(x, z, islandOn()) || !clearOfObstacles(x, z, 2f * k)) continue;
            boolean near = false;
            for (Boat b : g.boats) {
                float dx = b.x - x, dz = b.z - z;
                if (!b.out && dx * dx + dz * dz < 64f * k * k) { near = true; break; }
            }
            if (near) continue;
            Crate c = new Crate();
            c.x = x;
            c.z = z;
            c.life = 45f;
            float r = rnd.nextFloat() * total;
            int kind = 0;
            for (int i = 0; i < Weapons.PU_COUNT; i++) {
                r -= Weapons.PU_WEIGHT[i];
                if (r <= 0) { kind = i; break; }
            }
            c.kind = kind;
            crates.add(c);
            return;
        }
    }

    private void igniteSlick(Area s, Boat by) {
        if (s.burn > 0) return;
        s.burn = 5f;
        s.owner = by;
        sfxAt(S_FLAME, s.x, s.z, 1f);
    }

    private void updateAreas(float dt) {
        float k = k();
        for (int i = slicks.size() - 1; i >= 0; i--) {
            Area s = slicks.get(i);
            s.life -= dt;
            if (s.burn > 0) {
                s.burn -= dt;
                if (s.burn <= 0) s.life = Math.min(s.life, 0.5f);   // burnt off
                for (int n = 0; n < 4; n++) {
                    float a = rnd.nextFloat() * 6.283f, r = (float) Math.sqrt(rnd.nextFloat()) * s.r;
                    fx.emit(s.x + (float) Math.cos(a) * r, 0.1f * k, s.z + (float) Math.sin(a) * r, 0, 0.5f * k, 0,
                            0.6f, 0.25f * k, 0.7f * k, 1f, 0.6f, 0.15f, 0.8f, 1.5f * k, true);
                }
                if (rnd.nextFloat() < 0.5f)
                    fx.emit(s.x + (rnd.nextFloat() - 0.5f) * s.r, 0.6f * k, s.z + (rnd.nextFloat() - 0.5f) * s.r,
                            0, 0.6f * k, 0, 3f, 0.6f * k, 2.5f * k, 0.1f, 0.09f, 0.08f, 0.5f, 0.4f * k, false);
            }
            if (s.life <= 0) { slicks.remove(i); continue; }
            for (Boat b : g.boats) {
                if (b.out) continue;
                float dx = b.x - s.x, dz = b.z - s.z;
                if (dx * dx + dz * dz < s.r * s.r) {
                    b.oiled = Math.max(b.oiled, 0.4f);
                    if (s.burn > 0) {
                        b.burn = Math.max(b.burn, Weapons.BURN_TIME);
                        if (b != s.owner) b.lastHitBy = s.owner;
                    }
                }
            }
        }
        for (int i = smokes.size() - 1; i >= 0; i--) {
            Area s = smokes.get(i);
            s.life -= dt;
            if (s.life <= 0) { smokes.remove(i); continue; }
            float grow = Math.min(1f, (Weapons.SMOKE_TIME - s.life) / 0.8f);
            for (int n = 0; n < 3; n++) {
                float a = rnd.nextFloat() * 6.283f, r = (float) Math.sqrt(rnd.nextFloat()) * s.r * grow;
                fx.emit(s.x + (float) Math.cos(a) * r, 0.3f * k, s.z + (float) Math.sin(a) * r,
                        0, 0.25f * k, 0, 2.4f, 1.0f * k, 2.4f * k, 0.78f, 0.79f, 0.8f, 0.42f, 0.05f * k, false);
            }
        }
    }

    boolean clearOfObstacles(float x, float z, float margin) {
        float br = g.track.buoyRadius;
        for (float[] o : obstacles) {
            float dx = o[0] - x, dz = o[1] - z, r = br + margin;
            if (dx * dx + dz * dz < r * r) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------ damage and the round

    /** Balance statistics (simulation only): damage dealt and shots fired, by weapon. */
    static final float[] STAT_DMG = new float[Weapons.COUNT + 1];
    static final int[] STAT_SHOTS = new int[Weapons.COUNT], STAT_HITS = new int[Weapons.COUNT];

    void damage(Boat o, float dmg, Boat by, String how) {
        if (o.out || dmg <= 0) return;
        if (o.shield > 0) return;
        STAT_DMG[by != null && by != o ? by.weapon : Weapons.COUNT] += Math.min(dmg, o.hp);
        o.hp -= dmg;
        if (by != null && by != o) {
            o.lastHitBy = by;
            o.lastHitT = roundTime;
            lastHow = how;
        }
        if (o.hp <= 0) eliminate(o, o.lastHitBy, how);
    }

    private String lastHow = "";

    private void eliminate(Boat o, Boat by, String how) {
        if (o.out) return;
        o.out = true;
        o.sinkT = 0.001f;
        o.place = alive;
        alive--;
        o.tether = null;
        for (Boat b : g.boats) if (b.tether == o) b.tether = null;
        boolean blownUp = o.hp <= 0;
        if (blownUp) explode(o.x, o.z, by, 0f, 0f, true);
        else sfx(S_SINK, o, 1f);
        String line;
        if (by != null && by != o && !by.out) {
            by.kills++;
            String verb = how.equals("sank") ? "sank" : how.equals("burned") ? "burned" : how.equals("destroyed")
                    ? "destroyed" : how;
            if (how.equals("sank") || how.equals("left the arena")) verb = "sank";
            line = by.name + " " + verb + " " + o.name;
        } else {
            line = o.name + (how.equals("sank") ? " went to the bottom" : " is out");
        }
        addFeed(line);
        if (!demo) {
            if (o.isPlayer) {
                g.setMessage(blownUp ? "DESTROYED!" : "YOU SANK!", 2.5f);
                say("cyouout", 3, Announcer.URGENT);
            } else if (by != null && by.isPlayer) {
                g.setMessage(o.name.toUpperCase() + " DOWN!", 1.6f);
                say("cyoukill", 5, Announcer.IMPORTANT);
            } else {
                say("ckill", 5, Announcer.NORMAL);
            }
        }
        if (o.isPlayer || o == spectate) {
            Boat next = by != null && !by.out ? by : leader();
            spectate = next;
        }
    }

    /** The boat in the best shape, for the camera. */
    Boat leader() {
        Boat best = null;
        for (Boat b : g.boats) if (!b.out && (best == null || b.hp > best.hp)) best = b;
        return best;
    }

    private void sinking(Boat b, float dt) {
        float k = k();
        b.sinkT += dt;
        boolean blown = b.hp <= 0;
        // a wreck settles and slips under; a swamped boat goes stern first
        float t = b.sinkT;
        b.sinkY = Math.min(2.5f * k, (blown ? 0.12f : 0.06f) * k * t * t + 0.05f * k * t);
        b.sinkPitch = Math.min(0.9f, (blown ? 0.25f : 0.18f) * t) * (b.id % 2 == 0 ? 1f : -1f);
        b.sinkRoll = Math.min(0.5f, 0.12f * t) * (b.id % 3 == 0 ? -1f : 1f);
        b.vx *= Math.exp(-1.5f * dt);
        b.vz *= Math.exp(-1.5f * dt);
        if (t < 4f && rnd.nextFloat() < dt * 20f) {
            g.particles.emit(b.x + (rnd.nextFloat() - 0.5f) * 0.8f * k, b.z + (rnd.nextFloat() - 0.5f) * 0.8f * k,
                    0, 0, 1.2f, 0.1f * k, 0.5f * k, 0.7f);
            if (blown)
                fx.emit(b.x, 0.3f * k, b.z, 0, 0.6f * k, 0, 2.5f, 0.3f * k, 1.6f * k, 0.1f, 0.1f, 0.1f,
                        0.6f * Math.max(0f, 1f - t / 4f), 0.5f * k, false);
        }
    }

    /** Whether a sunk boat is still visible. */
    static boolean visible(Boat b) {
        return !b.out || b.sinkT < 5f;
    }

    private void checkRound(float dt) {
        if (roundOver) {
            roundOverT += dt;
            if (demo && roundOverT > 4f) startRound();
            return;
        }
        if (mode == MODE_SURVIVAL && !demo) {
            Boat me = g.player;
            if (me.out) {
                endRound(null);
                return;
            }
            if (alive <= 1) {
                // wave cleared
                if (waveBreak <= 0) {
                    waveBreak = 4f;
                    addFeed("WAVE " + wave + " CLEARED!");
                    g.setMessage("WAVE " + wave + " CLEARED", 2f);
                    say("cclear", 3, Announcer.IMPORTANT);
                }
                waveBreak -= dt;
                if (waveBreak <= 0) nextWave();
            }
            return;
        }
        if (alive <= 1) {
            Boat w = null;
            for (Boat b : g.boats) if (!b.out) w = b;
            endRound(w);
        }
    }

    private void endRound(Boat winner) {
        roundOver = true;
        roundOverT = 0;
        roundWinner = winner;
        if (winner != null) {
            winner.place = 1;
            winner.wins++;
        }
        if (demo) return;
        if (mode == MODE_SURVIVAL) {
            matchOver = true;
            int cleared = Math.max(0, wave - 1);
            resultLine = "You survived " + cleared + (cleared == 1 ? " wave" : " waves") + " with "
                    + g.player.kills + (g.player.kills == 1 ? " kill" : " kills");
            Float best = g.records.get(survivalKey());
            if (best == null || cleared > best) {
                g.records.offer(survivalKey(), cleared, true);
                if (cleared > 0) resultLine += "  •  NEW BEST!";
            } else {
                resultLine += "  •  Best: " + Math.round(best) + " waves";
            }
            return;
        }
        if (winner != null && winner.wins >= winsNeeded()) {
            matchOver = true;
            matchWinner = winner;
        } else if (round >= ROUNDS[roundsIdx] + 6) {
            matchOver = true;   // ties going on forever: whoever has most wins
            for (Boat b : g.boats) if (matchWinner == null || b.wins > matchWinner.wins) matchWinner = b;
        }
        if (winner != null && winner.isPlayer) {
            g.setMessage(matchOver ? "YOU WIN THE MATCH!" : "YOU WIN THE ROUND!", 3f);
            say("cwin", 3, Announcer.URGENT);
        } else if (winner != null) {
            g.setMessage(winner.name.toUpperCase() + (matchOver ? " WINS THE MATCH" : " WINS THE ROUND"), 3f);
        } else {
            g.setMessage("NO SURVIVORS", 2.5f);
        }
        resultLine = winner == null ? "Everybody went down" : winner.name + " was the last boat afloat";
    }

    String survivalKey() {
        return "surv" + (g.lifeSize ? "L" : "R") + arena + "d" + g.difficulty + "l" + length;
    }

    /** Fast-forwards the rest of the round once the player is out. */
    void skipRound() {
        int guard = 0;
        skipping = true;
        try {
            while (!roundOver && guard++ < 30 * 400) g.combatStep(1f / 30f);
        } finally {
            skipping = false;
        }
        fx.clear();
        g.particles.clear();
        g.spray.clear();
    }

    private boolean skipping;

    void nextRound() {
        if (matchOver) return;
        startRound();
    }

    // ------------------------------------------------------------------ AI

    private void think(Boat b, float dt) {
        float k = k();
        b.aiTimer -= dt;
        if (b.aiTimer <= 0) {
            b.aiTimer = 0.25f + rnd.nextFloat() * 0.2f;
            chooseTarget(b);
            aiSpecial(b);
            aiWeapon(b, 0.25f);
            if (rnd.nextFloat() < 0.04f) b.aiOrbit = -b.aiOrbit;
        }
        Boat t = b.target;
        float px, pz;   // where to go
        boolean brake = false;
        if (b.aiMode == AI_PICKUP && pickTarget != null && crates.contains(pickTarget[b.id % 8])) {
            Crate c = pickTarget[b.id % 8];
            px = c.x; pz = c.z;
        } else if (t == null) {
            // nobody about: cruise round the middle
            float a = roundTime * 0.2f + b.id;
            px = (float) Math.cos(a) * arenaRx * 0.4f;
            pz = (float) Math.sin(a) * arenaRz * 0.4f;
        } else if (b.aiMode == AI_FLEE) {
            float dx = b.x - t.x, dz = b.z - t.z, d = Math.max(0.1f, (float) Math.sqrt(dx * dx + dz * dz));
            float ox = dx / d, oz = dz / d;
            float s = b.aiOrbit * 0.6f;
            px = b.x + (ox * (float) Math.cos(s) - oz * (float) Math.sin(s)) * 15f * k;
            pz = b.z + (ox * (float) Math.sin(s) + oz * (float) Math.cos(s)) * 15f * k;
        } else {
            float[] p = engage(b, t);
            px = p[0];
            pz = p[1];
            brake = p[2] > 0;
        }
        float want = bearing(px - b.x, pz - b.z);
        want = avoid(b, want);
        float diff = Boat.wrapAngle(want - b.heading);
        // reverse out when stuck against something
        if (b.aiReverse > 0) {
            b.aiReverse -= dt;
            b.throttle = -0.8f;
            b.steer = -Math.signum(diff);
        } else {
            b.steer = Math.max(-1f, Math.min(1f, -diff * 2.4f));
            float thr = 1f - Math.min(0.45f, Math.abs(diff) * 0.3f);
            if (brake) thr = Math.min(thr, 0.55f);
            b.throttle = thr;
            if (b.forwardSpeed() < 0.8f * k && roundTime > 1.5f) b.aiStuck += dt; else b.aiStuck = 0;
            if (b.aiStuck > 1.1f) { b.aiReverse = 0.9f; b.aiStuck = 0; }
        }
        // shoot when the weapon lines up (with a reaction lag by skill)
        b.fire = wantsFire(b) && rnd.nextFloat() < 0.6f + (1f - b.aimJitter * 5f) * 0.4f;
        if (b.weapon == Weapons.FLAMETHROWER || b.weapon == Weapons.WATER_CANNON) {
            if (b.ammo < Weapons.MAG[b.weapon] * 0.15f && !wantsFire(b)) b.fire = false;
        }
    }

    private Crate[] pickTarget = new Crate[8];

    /** The range (RC metres) each weapon fights best at: min, max. */
    private static final float[][] BEST_RANGE = {{5f, 20f}, {0f, 6.5f}, {0f, 4f}, {11f, 28f}, {5f, 16f},
            {0f, 11f}, {3f, 12f}, {9f, 28f}};

    /** AI: every few seconds, picks the weapon that suits the distance and the situation. */
    private void aiWeapon(Boat b, float dt) {
        b.aiSwitch -= dt;
        if (b.aiSwitch > 0 || b.target == null || b.aiMode == AI_PICKUP) return;
        float k = k();
        Boat t = b.target;
        float d = dist(b, t) / k;
        float bx = t.x - b.x, bz = t.z - b.z;
        boolean behind = (bx * b.fwdX() + bz * b.fwdZ()) < -0.4f * d * k;
        int best = b.weapon;
        float bestScore = -99f, cur = 0f;
        for (int w = 0; w < Weapons.COUNT; w++) {
            float sc = 0f;
            if (d >= BEST_RANGE[w][0] && d <= BEST_RANGE[w][1]) sc += 1f;
            else sc -= Math.min(1f, Math.min(Math.abs(d - BEST_RANGE[w][0]), Math.abs(d - BEST_RANGE[w][1])) / 10f);
            if (w == b.favorite) sc += 0.6f;
            float ammo = w == b.weapon ? (b.reload > 0 ? 0f : b.ammo) : b.ammoOf[w];
            if (Weapons.MAG[w] > 0) sc += ammo / Weapons.MAG[w] * 0.5f - (ammo <= 0 ? 0.8f : 0f);
            if (w == Weapons.MINES && behind) sc += 0.7f;
            if (w == Weapons.DRILL && b.nitro > 0) sc += 0.8f;
            sc += rnd.nextFloat() * 0.25f;
            if (w == b.weapon) cur = sc;
            if (sc > bestScore) { bestScore = sc; best = w; }
        }
        b.aiSwitch = 4f + rnd.nextFloat() * 4f;
        if (best != b.weapon && bestScore > cur + 0.45f) switchWeapon(b, best);
    }

    private void chooseTarget(Boat b) {
        float k = k();
        Boat best = null;
        float bestScore = Float.MAX_VALUE;
        for (Boat o : g.boats) {
            if (o == b || o.out) continue;
            float d = dist(b, o);
            float score = d + (o.hp / o.maxHp) * 10f * k;
            if (o.isPlayer && !demo) score -= 4f * k * g.difficulty;
            if (o == b.lastHitBy && roundTime - b.lastHitT < 4f) score -= 10f * k;   // revenge
            if (o == b.target) score *= 0.8f;   // stick with it
            if (score < bestScore) { bestScore = score; best = o; }
        }
        b.target = best;
        b.aiMode = AI_ATTACK;
        // patch up when hurt and there's something to grab
        boolean hurt = b.hp < b.maxHp * 0.45f || b.water > 0.4f || b.holes >= 2;
        Crate grab = null;
        float gd = Float.MAX_VALUE;
        for (Crate c : crates) {
            boolean fix = c.kind == Weapons.PU_REPAIR || c.kind == Weapons.PU_PUMP;
            if (!Weapons.PU_INSTANT[c.kind] && b.special >= 0) continue;
            float dx = c.x - b.x, dz = c.z - b.z;
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float limit = hurt && fix ? 60f * k : 14f * k;
            if (b.holes > 0 && c.kind == Weapons.PU_PUMP) d *= 0.6f;
            if (d < limit && d < gd) { gd = d; grab = c; }
        }
        if (grab != null && (hurt || best == null || dist(b, best) > 9f * k)) {
            b.aiMode = AI_PICKUP;
            pickTarget[b.id % 8] = grab;
        } else if (hurt && b.hp < b.maxHp * 0.22f && best != null && dist(b, best) < 15f * k
                && b.weapon != Weapons.MINES) {
            b.aiMode = AI_FLEE;
        }
    }

    /** Where to steer to fight target t with this boat's weapon: x, z, slow down (1/0). */
    private float[] engage(Boat b, Boat t) {
        float k = k();
        float dx = b.x - t.x, dz = b.z - t.z;
        float d = Math.max(0.1f, (float) Math.sqrt(dx * dx + dz * dz));
        float ox = dx / d, oz = dz / d;
        float lead = Math.min(1.2f, d / (20f * k));
        float tx = t.x + t.vx * lead, tz = t.z + t.vz * lead;
        switch (b.weapon) {
            case Weapons.DRILL:
            case Weapons.FLAMETHROWER: {
                // straight at them (aim a little ahead so we hit the side)
                float l = b.weapon == Weapons.DRILL ? 0.7f : 0.35f;
                return new float[]{t.x + t.vx * lead * l * 2f, t.z + t.vz * lead * l * 2f,
                        b.weapon == Weapons.FLAMETHROWER && d < 3f * k ? 1 : 0};
            }
            case Weapons.MINES: {
                // circle them, laying a ring of magnetic mines that creep in; cut across in
                // front of anyone chasing
                if (d > 14f * k) return new float[]{tx, tz, 0};
                float h = bearing(t.x - b.x, t.z - b.z) + (d > 7f * k ? 1.2f : 1.8f) * b.aiOrbit;
                return new float[]{b.x - (float) Math.sin(h) * 10f * k, b.z - (float) Math.cos(h) * 10f * k, 0};
            }
            case Weapons.TORPEDO:
            case Weapons.ROCKETS: {
                float ideal = (b.weapon == Weapons.TORPEDO ? 15f : 17f) * k;
                if (d > ideal * 1.3f) return new float[]{tx, tz, 0};
                if (d < ideal * 0.5f) return new float[]{b.x + ox * 10f * k, b.z + oz * 10f * k, 0};
                // turn to face them
                return new float[]{tx, tz, 1};
            }
            default: {
                // turrets: keep the target inside the turret's arc, off to one side of the
                // bow, at the weapon's best range (closing in, then veering off when close)
                float ideal = (b.weapon == Weapons.MACHINE_GUN ? 12f : b.weapon == Weapons.HARPOON ? 11f : 7f) * k;
                if (b.tether != null) {
                    // towing: haul them away behind us, swinging them about
                    return new float[]{b.x - ox * 10f * k + oz * b.aiOrbit * 6f * k, b.z - oz * 10f * k - ox * b.aiOrbit * 6f * k, 0};
                }
                float arc = Weapons.ARC[b.weapon];
                float off = d > ideal * 1.3f ? 0f : d > ideal * 0.7f ? arc * 0.65f : arc * 1.1f;
                float h = bearing(tx - b.x, tz - b.z) + off * b.aiOrbit;
                return new float[]{b.x - (float) Math.sin(h) * 10f * k, b.z - (float) Math.cos(h) * 10f * k, 0};
            }
        }
    }

    /** Steers round land, buoys, the arena edge and enemy mines: the clear heading nearest want. */
    private float avoid(Boat b, float want) {
        float k = k();
        float spd = Math.max(0f, b.forwardSpeed());
        float[] offs = {0f, 0.3f, -0.3f, 0.6f, -0.6f, 0.95f, -0.95f, 1.35f, -1.35f, 1.9f, -1.9f, 2.6f, -2.6f};
        float d1 = 2.2f * k + spd * 0.35f, d2 = 4.5f * k + spd * 0.8f;
        float side = b.aiOrbit;
        for (float o : offs) {
            float h = want + o * side;
            float fx = (float) -Math.sin(h), fz = (float) -Math.cos(h);
            if (clearAt(b, b.x + fx * d1, b.z + fz * d1) && clearAt(b, b.x + fx * d2, b.z + fz * d2)
                    && clearAt(b, b.x + fx * d1 * 0.5f, b.z + fz * d1 * 0.5f)) return h;
        }
        return want;
    }

    private boolean clearAt(Boat b, float x, float z) {
        float k = k();
        if (!World.isWater(x, z, islandOn())) return false;
        if (g.lifeSize && !inArena(x, z, 0.97f)) return false;
        if (!clearOfObstacles(x, z, 1.2f * k)) return false;
        for (Mine m : mines) {
            if (m.owner == b || m.arm > 0.5f) continue;
            float dx = m.x - x, dz = m.z - z, r = 2.6f * k;
            if (dx * dx + dz * dz < r * r) return false;
        }
        for (Area s : slicks) {
            if (s.burn <= 0 && s.owner == b) continue;
            float dx = s.x - x, dz = s.z - z;
            if (dx * dx + dz * dz < s.r * s.r) return false;
        }
        return true;
    }

    private void aiSpecial(Boat b) {
        if (b.special < 0) return;
        float k = k();
        Boat t = b.target;
        float d = t != null ? dist(b, t) : 1e9f;
        boolean underFire = roundTime - b.lastHitT < 1.5f;
        boolean use = false;
        switch (b.special) {
            case Weapons.PU_NITRO:
                use = (b.weapon == Weapons.DRILL && d < 14f * k) || b.aiMode == AI_FLEE || (b.aiMode == AI_PICKUP && b.hp < b.maxHp * 0.3f);
                break;
            case Weapons.PU_SHIELD:
                use = underFire && b.hp < b.maxHp * 0.55f;
                break;
            case Weapons.PU_EMP: {
                int n = 0;
                for (Boat o : g.boats) if (o != b && !o.out && dist(b, o) < Weapons.EMP_RADIUS * k * 0.8f) n++;
                use = n >= 1 && (n >= 2 || rnd.nextFloat() < 0.3f);
                break;
            }
            case Weapons.PU_OIL:
                for (Boat o : g.boats) {
                    if (o == b || o.out) continue;
                    float bx = o.x - b.x, bz = o.z - b.z;
                    float dd = (float) Math.sqrt(bx * bx + bz * bz);
                    if (dd < 9f * k && (bx * b.fwdX() + bz * b.fwdZ()) < -0.5f * dd) use = true;
                }
                break;
            case Weapons.PU_SMOKE:
                use = underFire && b.hp < b.maxHp * 0.5f;
                break;
        }
        if (use) b.useSpecial = true;
    }

    // ------------------------------------------------------------------ helpers

    static float dist(Boat a, Boat b) {
        float dx = a.x - b.x, dz = a.z - b.z;
        return (float) Math.sqrt(dx * dx + dz * dz);
    }

    void addFeed(String s) {
        feed.add(s);
        feedT.add(6f);
        while (feed.size() > 4) { feed.remove(0); feedT.remove(0); }
    }

    private void sfx(int id, Boat b, float vol) {
        sfxAt(id, b.x, b.z, vol);
    }

    void sfxAt(int id, float x, float z, float vol) {
        if (demo || skipping) return;
        // fainter with distance from the player (or the camera's boat)
        Boat me = g.viewBoat();
        float k = k();
        float att = 1f;
        if (me != null) {
            float dx = me.x - x, dz = me.z - z;
            float d = (float) Math.sqrt(dx * dx + dz * dz) / k;
            att = 1f / (1f + d * d / 300f);
        }
        float v = vol * att;
        if (v < 0.03f) return;
        int h = sfxHead;
        sfxId[h & 63] = id;
        sfxVol[h & 63] = v;
        sfxHead = h + 1;
    }

    private void say(String base, int variants, int level) {
        if (demo || skipping) return;
        g.announcer.enabled = g.announcerOn && g.soundOn;
        g.announcer.say(base, variants, base, 0f, level);
    }

    /** Boats still afloat. */
    int aliveCount() { return alive; }
}
