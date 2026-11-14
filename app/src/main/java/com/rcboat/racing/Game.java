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

    // ---- power: electric, or one of the IMPBA Large Scale Gas engine classes (rule book I-IV) ----
    static final int ELECTRIC = 0, GAS_TWIN = 4;
    static final String[] POWER_NAMES = {"Electric", "Gas 25.5cc", "Gas 27cc", "Gas 36cc", "Gas Twin 64"};
    static final String[] POWER_INFO = {
            "Electric: instant throttle, quiet whine",
            "Gas Super Sport: spec Zenoah G260, 25.5 cc",
            "Gas LSG27: up to 27 cc",
            "Gas LSG36: up to 36 cc",
            "Gas TLSG64: twin cylinders, up to 64 cc"};
    /** Top speed and acceleration multipliers, throttle response time (s). */
    private static final float[][] POWER_STATS = {
            {1.08f, 1.30f, 0.05f},
            {1.00f, 0.92f, 0.24f},
            {1.05f, 0.97f, 0.24f},
            {1.12f, 1.03f, 0.26f},
            {1.20f, 1.10f, 0.28f},
    };

    // ---- start: standing start from the line, or the IMPBA clock start (Technical Standards VII) ----
    static final int START_STANDING = 0, START_CLOCK_SHORT = 1, START_CLOCK_FULL = 2,
            START_LE_MANS = 3, START_ENDURO = 4;
    static final String[] START_NAMES = {"Standing", "Clock, short", "Clock, full", "Le Mans", "Clock, 5 s"};
    /** The last 30 s of the clock is the no-launch period (Technical Standards VII-B-4). */
    static final float NO_LAUNCH = 30f;

    // ---- events ----
    static final int EVENT_RACE = 0, EVENT_TRIAL = 1, EVENT_SPEED = 2;
    static final String[] EVENT_NAMES = {"Race", "Time trial", "Speed run"};
    static final String[] EVENT_INFO = {"", "Solo record trial", "1/16 mile (330 ft) straightaway speed trap"};

    // ---- race length: 1-9 laps, or a timed race on the 5:00 race clock ----
    static final int TIMED = 10;
    static final float RACE_SECONDS = 300f;

    // ---- noise rule: 92 dB(A) at the shore meter (Technical Standards II-F) ----
    static final float NOISE_LIMIT = 92f;
    /** Typical reading per power class at full chat in front of the meter (electric: none). */
    private static final float[] NOISE_BASE = {0f, 85.5f, 86.5f, 87.5f, 88.5f};
    /** Each boat's engine tune for the session (dB): the player's is always legal. */
    private final float[] noiseTune = new float[COLORS.length];
    /** No crossing the start line in the last 5 s (the clock face's coloured pie section). */
    static final float JUMP_WINDOW = 5f;

    // ---- buoy rules ----
    static final int RULES_FUN = 0, RULES_TIME = 1, RULES_LAP = 2;
    static final String[] RULE_NAMES = {"Just for fun", "Time penalty", "Lap penalty"};
    static final String[] RULE_INFO = {"No penalties", "+3 s per buoy hit or cut, or jump start",
            "+1 lap per buoy hit or cut, or jump start"};
    static final float PENALTY_SECONDS = 3f;

    // ---- settings (changed from the menu) ----
    int opponents = 5;
    int laps = 3;
    int difficulty = 1;
    int view = VIEW_SHORE;
    int trackId = 0;
    int hull = Boat.V_HULL;
    boolean dualStick = true;
    boolean soundOn = true;
    boolean announcerOn = true;
    int power = ELECTRIC;
    int startMode = START_STANDING;
    int rules = RULES_FUN;
    int event = EVENT_RACE;
    int raceClass = RaceClass.OPEN;
    /** Life-size Unlimited hydroplane racing instead of RC boats. */
    boolean lifeSize;
    /** Menu page: the main menu (RC or life size), or one of the two settings menus. */
    static final int MENU_MAIN = 0, MENU_RC = 1, MENU_LIFE = 2;
    int menuScreen = MENU_MAIN;
    /** The course last used in the other mode. */
    private int otherTrack = Track.FIRST_LIFE + 1;
    boolean radiusBuoys;
    /** Heat number of this session (shown on the clock after each race). */
    int heat;
    /** Noise rule: boats warned in an earlier heat this session, by id. */
    final int[] noiseWarned = new int[COLORS.length];   // heat of the warning, 0 = none
    final Records records = new Records();
    /** Results-screen lines about records and speed runs. */
    String resultNote = "";
    boolean timed() { return laps == TIMED; }

    // ---- race announcers (voice only) ----
    final Announcer announcer = new Announcer();
    volatile boolean talking;          // a voice clip is playing (engine sound ducks)
    private final Random annRnd = new Random();
    private boolean evBuoy, evShore, evBump;
    private float annStartDelay, annFinishDelay;
    private int annLeader, annPrevPos, annLeaderLap;
    private boolean annWinSaid, annFinishSaid;
    private float annPrevWrong, annPrevOff;

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
    boolean clockStart;  // this race uses the clock start (boats run before zero)
    private float clockCallAt;
    float raceTime;
    float finishedFor;   // seconds since the player finished
    String message = "";
    float messageTime;
    final Particles particles = new Particles(1500);
    /** Rooster tails: water thrown up behind the props. */
    final Spray spray = new Spray(3000);
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
            // in a race every boat uses the hull the player picked; the menu demo shows a mix
            int h = lifeSize ? Boat.UNLIMITED : withPlayer ? hull : rnd.nextInt(Boat.RC_HULLS);
            Boat b = new Boat(i, NAMES[i], COLORS[i], isPlayer, h, rnd);
            // in a race everyone runs the player's power class; the demo mixes them
            int pw = withPlayer ? power : rnd.nextInt(POWER_STATS.length);
            if (!lifeSize) b.setPower(POWER_STATS[pw][0], POWER_STATS[pw][1], POWER_STATS[pw][2]);
            b.sizeScale = withPlayer && !lifeSize ? RaceClass.sizeScale(raceClass) : 1f;
            if (b.sizeScale != 1f) { b.radius *= b.sizeScale; b.fx = b.sizeScale; }
            // engine tune: the player's is legal; some opponents run loud (noise rule)
            b.noiseTune = noiseTune[i];
            if (!isPlayer) {
                b.autopilot = true;
                b.skill = withPlayer ? skillRange[difficulty] + rnd.nextFloat() * 0.06f
                        : 0.9f + rnd.nextFloat() * 0.08f;
                b.maxSpeed = b.topSpeed * b.skill;
                b.lane = b.laneTarget = 0;
                b.startDelay = 0.25f + (1f - b.skill) * 3.5f + rnd.nextFloat() * 0.4f;
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
            float gk = lifeSize ? 7f : 1f;   // grid spacing for life-size boats
            float back = (4f + row * 3.6f + (col == 1 ? 1.2f : 0f)) * gk;
            int idx = track.wrap(-Math.round(back / track.ds));
            Boat b = boats[i];
            float lat = col == 0 ? 2.4f : -2.4f;
            // ovals: keep the grid clear of the start / finish buoy on the inside
            if (track.impba) lat = col == 0 ? -track.insideSign * 2.6f : track.insideSign * 0.9f;
            lat *= gk;
            b.placeAt(track, idx, lat);
            b.lapWrap = -1;
            b.progress = -track.length + track.cum[idx];
        }
        player = withPlayer ? boats[0] : null;
        order = boats.clone();
        particles.clear();
        spray.clear();
    }

    /**
     * Le Mans start: boats sit side by side near the shore in front of the drivers, noses
     * pointing out, behind the start line, and run out to the course at the signal.
     */
    private void lineUpLeMans() {
        int n = boats.length;
        // the side of the start line nearer the shore
        float side = track.nz(0) > 0 ? 1f : -1f;   // lateral sign that points south
        for (int i = 0; i < n; i++) {
            Boat b = boats[i];
            float back = 3f + i * 3.2f;
            int idx = track.wrap(-Math.round(back / track.ds));
            float lat = side * 9.5f;
            while (lat * side > 4f && !World.isWater(track.px[idx] + track.nx(idx) * lat,
                    track.pz[idx] + track.nz(idx) * lat, false)) lat -= side * 0.5f;
            b.placeAt(track, idx, lat);
            // facing out over the water, angled towards the course
            float tx = track.tx[idx], tz = track.tz[idx];
            float ox = -track.nx(idx) * side, oz = -track.nz(idx) * side;   // towards the course
            float dx = ox * 0.8f + tx * 0.6f, dz = oz * 0.8f + tz * 0.6f;
            b.heading = (float) Math.atan2(-dx, -dz);
            b.lapWrap = -1;
            b.progress = -track.length + track.cum[idx];
        }
    }

    synchronized void startDemo() {
        state = State.MENU;
        heat = 0;
        java.util.Arrays.fill(noiseWarned, 0);
        // a new session: about one opponent in four brings a loud engine
        for (int i = 0; i < noiseTune.length; i++)
            noiseTune[i] = i == 0 ? -2.5f : rnd.nextFloat() < 0.25f ? 2.5f + rnd.nextFloat() * 2f : -3f + rnd.nextFloat() * 2f;
        paused = false;
        createBoats(6, false);
        raceTime = 0;
        camInit = false;
        message = "";
        engineOn = false;
    }

    /** Clock length: 2.5 min (gas) or 60 s (electric) as in the rules, or a short version. */
    float clockLength() {
        boolean gas = power != ELECTRIC || lifeSize;
        if (startMode == START_CLOCK_FULL) return gas ? 150f : 60f;
        if (startMode == START_ENDURO) return 5f;    // Fast Electric Enduro
        return gas ? 30f : 15f;
    }

    /**
     * Lines that only suit one kind of power: the battery jokes are for electric boats only.
     * (No gas-only lines exist yet; add them here with the opposite test.)
     */
    static final String[] ELECTRIC_ONLY = {"go_1", "white_2"};
    static final String[] GAS_ONLY = {};

    private void setAnnouncerBans() {
        announcer.banned.clear();
        boolean electric = power == ELECTRIC && !lifeSize;
        boolean gas = power != ELECTRIC && !lifeSize;
        if (!electric) for (String k : ELECTRIC_ONLY) announcer.banned.add(k);
        if (!gas) for (String k : GAS_ONLY) announcer.banned.add(k);
    }

    /** Whether a start option is available with the current power (the 5 s clock is electric). */
    boolean startOk(int mode) {
        if (lifeSize) return mode == START_STANDING || mode == START_CLOCK_SHORT || mode == START_CLOCK_FULL;
        return mode != START_ENDURO || power == ELECTRIC;
    }

    /** Keeps every setting legal for the class, event and power chosen. */
    /** Switches between RC racing and life-size Unlimited hydroplanes (and their worlds). */
    synchronized void setMode(boolean life) {
        if (life == lifeSize) return;
        int keep = trackId;
        lifeSize = life;
        World.scale = life ? World.LIFE_SCALE : 1f;
        int next = otherTrack;
        otherTrack = keep;
        if (life && view == VIEW_SHORE) view = VIEW_CHASE;
        if (!life && view != VIEW_SHORE && view != VIEW_FPV && view != VIEW_CHASE) view = VIEW_SHORE;
        setTrack(next);
        enforceRules();
    }

    synchronized void enforceRules() {
        if (lifeSize) {
            event = EVENT_RACE;
            if (!startOk(startMode)) startMode = START_STANDING;
            if (!Track.inMode(trackId, true)) setTrack(Track.FIRST_LIFE + 1);
            if (view == VIEW_SHORE) view = VIEW_CHASE;
            return;
        }
        if (!Track.inMode(trackId, false)) setTrack(0);
        if (!RaceClass.hullOk(raceClass, hull))
            hull = RaceClass.firstLegal(Boat.HULL_NAMES.length, h -> RaceClass.hullOk(raceClass, h));
        if (!RaceClass.powerOk(raceClass, power))
            power = RaceClass.firstLegal(POWER_NAMES.length, pw -> RaceClass.powerOk(raceClass, pw));
        if (!startOk(startMode)) startMode = START_CLOCK_SHORT;
        int want = trackId;
        if (event == EVENT_SPEED) want = Track.OVAL_THIRD;
        else if (!RaceClass.trackOk(raceClass, want)) want = Track.OFFSHORE_A;
        if (want != trackId || track.radiusBuoys != (radiusBuoys && track.oval)) setTrack(want);
    }

    synchronized void startRace() {
        enforceRules();
        heat++;
        resultNote = "";
        boolean solo = event != EVENT_RACE;
        createBoats(1 + (solo ? 0 : opponents), true);
        state = State.COUNTDOWN;
        boolean clockMode = startMode == START_CLOCK_SHORT || startMode == START_CLOCK_FULL
                || startMode == START_ENDURO;
        clockStart = clockMode && event != EVENT_SPEED;
        countdown = clockStart ? clockLength() : 4.8f;   // standing: a moment for the opening line
        clockCallAt = countdown;
        if (clockStart) spreadForClock();
        else if (startMode == START_LE_MANS && event != EVENT_SPEED && !lifeSize) lineUpLeMans();
        announcer.reset();
        setAnnouncerBans();
        // the opening line plays in full before the countdown starts (skipped on a clock
        // too short for it, like the 5 s Enduro clock)
        announcer.enabled = announcerOn && soundOn;
        boolean opened = (!clockStart || countdown > 10f)
                && announcer.say("start", 5, "start", 0f, Announcer.IMPORTANT);
        if (opened && !clockStart) countdown = Math.max(4.8f, 3f + announcer.remaining() + 0.3f);
        evBuoy = evShore = evBump = false;
        annStartDelay = 0f;
        annFinishDelay = 0;
        annLeader = -1;
        annPrevPos = -1;
        annLeaderLap = 1;
        annWinSaid = annFinishSaid = false;
        annPrevWrong = annPrevOff = 0;
        raceTime = 0;
        finishedFor = 0;
        paused = false;
        message = "";
        camInit = false;
        beepRequest = 520;
    }

    /**
     * Clock start: boats are launched and spread around the back half of the course,
     * moving off at their own pace, and must time their run to the start line.
     */
    private void spreadForClock() {
        int n = boats.length;
        for (int i = 0; i < n; i++) {
            Boat b = boats[i];
            float frac = 0.42f + 0.35f * i / Math.max(1, n - 1);
            int idx = track.wrap(Math.round(frac * Track.N));
            float lat = track.laneMin + (track.laneMax - track.laneMin) * (i % 2 == 0 ? 0.25f : 0.75f);
            b.placeAt(track, idx, lat);
            b.lapWrap = -1;
            b.progress = -track.length + track.cum[idx];
            b.lastCrossClock = -1;
        }
    }

    /**
     * The start clock's digits: three characters (minutes, tens, units) with leading
     * zeros blanked, or "" when the clock is blank (it goes blank at the start).
     */
    synchronized String clockDigits() {
        if (state == State.FINISHED && finishedFor > 2f) {
            // after the event the clock shows the heat number
            String h = String.valueOf(heat % 1000);
            return "   ".substring(h.length()) + h;
        }
        if (state == State.RACING && timed()) {
            int left = (int) Math.ceil(Math.max(0f, RACE_SECONDS - raceTime));
            if (left <= 0) return "";
            return (left / 60) + String.format(java.util.Locale.US, "%02d", left % 60);
        }
        if (state != State.COUNTDOWN) return "";
        int secs = (int) Math.ceil(countdown);
        if (!clockStart && secs > 3) return "";
        if (secs >= 60) return (secs / 60) + String.format(java.util.Locale.US, "%02d", secs % 60);
        String t = String.valueOf(secs);
        return t.length() == 1 ? "  " + t : " " + t;
    }

    /** Race distance covered, less any penalty laps. */
    float score(Boat b) {
        return b.progress - b.penaltyLaps * track.length;
    }

    /** Elapsed time plus time penalties. */
    float totalTime(Boat b) {
        return b.finishTime + b.penaltyTime;
    }

    /** A boat broke a rule: buoy hit or cut, or a jump start. */
    private void infraction(Boat b, String what, String call) {
        if (b.infractionCooldown > 0) return;
        b.infractionCooldown = 2.5f;
        b.infractions++;
        b.lastInfraction = what;
        String pen = "";
        if (rules == RULES_TIME) { b.penaltyTime += PENALTY_SECONDS; pen = "  +" + (int) PENALTY_SECONDS + " SEC"; }
        else if (rules == RULES_LAP) { b.penaltyLaps++; pen = "  +1 LAP"; }
        if (b.isPlayer) {
            setMessage(what + pen, 2.2f);
            if (call != null) announcer.say(call, 0, "penalty", 6f, Announcer.IMPORTANT);
        }
    }

    synchronized void toggleView() {
        view = (view + 1) % VIEW_NAMES.length;
        if (lifeSize && view == VIEW_SHORE) view = VIEW_FPV;   // life size: cockpit or chase
        camInit = false;
        setMessage(VIEW_SHORT[view] + " VIEW", 1.2f);
    }

    synchronized void setTrack(int id) {
        trackId = (id + Track.COUNT) % Track.COUNT;
        track = Track.get(trackId, radiusBuoys);
        buoyWobble = new float[track.buoys.size()];
        startDemo();
    }

    synchronized void setPaused(boolean p) {
        if (state == State.MENU) return;
        paused = p;
    }

    /** Handles the Android back button. Returns false to let the app close. */
    synchronized boolean onBack() {
        if (state == State.MENU) {
            if (menuScreen == MENU_MAIN) return false;
            menuScreen = MENU_MAIN;
            return true;
        }
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
                boolean voice = announcerOn && soundOn;
                if (clockStart) {
                    // boats run during the clock; the beeps cover the last 5 seconds
                    paceAi();
                    simulate(dt, false);
                    if (after != before && after <= 5 && after > 3 && countdown > 0) beepRequest = 520;
                    clockCalls();
                }
                if (countdown <= 0) {
                    state = State.RACING;
                    if (!voice) beepRequest = 1040;
                    setMessage("GO!", 1.0f);
                    announcer.enabled = announcerOn;
                    // the clock's "Race! Race! Race!" call, or the standing start's "Go!"
                    if (!(clockStart && announcer.say("race", 0, "count", 0f, Announcer.CUT)))
                        announcer.say("go", 2, "count", 0f, Announcer.CUT);
                    // after the start call, so a jump-start call queues behind it
                    if (clockStart) startFromClock();
                } else if (after != before && after <= 3) {
                    if (!voice) beepRequest = 520;
                    announcer.enabled = announcerOn;
                    announcer.say("count_" + after, 0, "count", 0f, Announcer.CUT);
                }
                if (!clockStart) for (Boat b : boats) { b.throttle = 0; b.steer = 0; }
                break;
            }
            case RACING:
            case FINISHED:
                raceTime += dt;
                if (state == State.FINISHED) finishedFor += dt;
                simulate(dt, false);
                break;
        }

        if (player != null && state != State.MENU) announce(dt);

        if (player != null && state != State.MENU) {
            engineOn = true;
            engineLevel = Boat.clamp(player.speed() / (lifeSize ? 75f : Boat.BASE_MAX_SPEED), 0f, 1.2f);
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
        if (player != null && !player.finished && state != State.COUNTDOWN) {
            for (Boat b : boats) {
                if (b == player) continue;
                float gap = score(b) - score(player);
                float target = 1f;
                if (gap < -25f * track.scaleK) target = 1.07f;
                else if (gap > 35f * track.scaleK) target = 0.95f;
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
            if (b.infractionCooldown > 0) b.infractionCooldown -= dt;
            updateProgress(b, dt, demo);
            emitWake(b, dt);
        }
        particles.update(dt);
        spray.update(dt);
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
                float min = a.radius + c.radius;
                if (d2 < min * min && d2 > 1e-6f) {
                    float d = (float) Math.sqrt(d2);
                    float nx = dx / d, nz = dz / d;
                    float push = (min - d) * 0.5f;
                    a.x -= nx * push; a.z -= nz * push;
                    c.x += nx * push; c.z += nz * push;
                    float rel = (c.vx - a.vx) * nx + (c.vz - a.vz) * nz;
                    if (rel < -1.2f && (a.isPlayer || c.isPlayer)) evBump = true;
                    if (rel < 0) {
                        float imp = -rel * 0.7f;
                        a.vx -= nx * imp; a.vz -= nz * imp;
                        c.vx += nx * imp; c.vz += nz * imp;
                    }
                }
            }
        }
        // boat vs buoys
        for (Boat b : boats) {
            float min = b.radius + track.buoyRadius;
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
                        if (b.isPlayer && vn < -1.5f) evBuoy = true;
                        if (state == State.RACING && rules != RULES_FUN && !b.finished && vn < -0.4f) {
                            int num = (int) bu[3];
                            infraction(b, num > 0 ? "HIT BUOY " + num : "HIT BUOY", null);
                        }
                    }
                }
            }
            // the start clock floats in the infield of the IMPBA ovals
            if (track.impba && !track.life) {
                float cmin = b.radius + 1.1f;
                float dx = b.x - track.clockX, dz = b.z - track.clockZ;
                float d2 = dx * dx + dz * dz;
                if (d2 < cmin * cmin && d2 > 1e-6f) {
                    float d = (float) Math.sqrt(d2);
                    b.x = track.clockX + dx / d * cmin;
                    b.z = track.clockZ + dz / d * cmin;
                    float vn = (b.vx * dx + b.vz * dz) / d;
                    if (vn < 0) { b.vx -= dx / d * vn * 1.4f; b.vz -= dz / d * vn * 1.4f; }
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
                if (b.isPlayer && vn < -2.5f) evShore = true;
                // Gas Scale Unlimited: parts knocked off (not in a collision) cost a lap
                if (raceClass == RaceClass.SCALE && vn < -4f && state == State.RACING && !b.finished
                        && rules != RULES_FUN && b.infractionCooldown <= 0) {
                    b.infractionCooldown = 2.5f;
                    b.infractions++;
                    b.penaltyLaps++;
                    b.lastInfraction = "COWLING OFF";
                    if (b.isPlayer) setMessage("COWLING FELL OFF  +1 LAP", 2.2f);
                }
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
        if (dist < track.offCourse) {
            int diff = idx - b.trackIdx;
            if (diff < -Track.N / 2) {
                b.lapWrap++;
                if (state == State.COUNTDOWN && clockStart) b.lastCrossClock = countdown;
            } else if (diff > Track.N / 2) b.lapWrap--;
            if (track.impba && state == State.RACING && !demo && !b.finished) checkCuts(b, b.trackIdx, idx);
            b.trackIdx = idx;
            b.offCourseTime = 0;
        } else {
            b.offCourseTime += dt;
        }
        float along = Boat.clamp(track.along(b.trackIdx, b.x, b.z), -track.ds, track.ds);
        b.progress = b.lapWrap * track.length + track.cum[b.trackIdx] + along;
        if (state == State.COUNTDOWN) return;   // clock start: nothing counts until zero
        if (demo || b.disqualified) return;
        lapTiming(b);
        if (track.oval) speedTrap(b, dt);
        noiseCheck(b);

        // wrong way?
        float vt = b.vx * track.tx[b.trackIdx] + b.vz * track.tz[b.trackIdx];
        b.wrongWayTime = vt < -1.5f ? b.wrongWayTime + dt : 0f;

        // stuck (AI)
        if (b.autopilot) {
            b.stuckTimer = b.speed() < 1.0f ? b.stuckTimer + dt : 0f;
        }

        boolean lost = b.offCourseTime > (b.autopilot ? 3f : 6f)
                || (b.autopilot && (b.stuckTimer > 2.5f || b.wrongWayTime > 2f));
        // putting a boat back on the course is a re-launch: not allowed in the last 30 s
        boolean noLaunch = state == State.COUNTDOWN && clockStart && countdown <= NO_LAUNCH;
        if (lost && b.disqualified) lost = false;
        if (lost && noLaunch) {
            if (b.isPlayer && messageTime <= 0) setMessage("NO LAUNCH IN THE LAST 30 S", 1.5f);
            lost = false;
        }
        if (lost) {
            b.placeAt(track, b.trackIdx, 0f);
            if (b.isPlayer) setMessage("BACK ON COURSE", 1.5f);
        }

        if (timed() && b.finishTarget < 0 && raceTime >= RACE_SECONDS) {
            // time is up: each boat takes the flag the next time it crosses the line
            b.finishTarget = (float) (Math.floor(score(b) / track.length) + 1) * track.length;
            if (b.isPlayer) { setMessage("TIME! FINISH THIS LAP", 2f); announcer.say("white", 3, "laps", 0f, Announcer.URGENT); }
        }
        float target = timed() ? b.finishTarget : laps * track.length;
        if (!b.finished && target > 0 && score(b) >= target) {
            b.finished = true;
            b.finishTime = raceTime;
            if (timed()) b.finishTarget = target;
            recordFinish(b);
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
        roosterTail(b, dt, spd);
        float w = b.fx;          // 1 for RC boats; life-size wakes are ~7.5x bigger
        float rel = spd / b.topSpeed * 13f;   // speed as if an RC boat (m/s)
        if (rel < 1.2f) return;
        b.wakeTimer -= dt;
        if (b.wakeTimer > 0) return;
        b.wakeTimer = 0.05f;
        float fx = b.fwdX(), fz = b.fwdZ(), rx = b.rightX(), rz = b.rightZ();
        float sx = b.x - fx * 0.6f * w, sz = b.z - fz * 0.6f * w;
        float k = Math.min(1f, rel / 9f);
        float out = (0.5f + rel * 0.09f) * w;
        float lw = w > 1.5f ? 2.6f : 1f;     // life-size foam lingers longer
        float la = w > 1.5f ? 0.55f : 1f;    // ...but is a thinner carpet
        particles.emit(sx + rx * 0.2f * w, sz + rz * 0.2f * w, rx * out + b.vx * 0.1f, rz * out + b.vz * 0.1f,
                2.0f * lw, 0.2f * w, 1.1f * w, (0.28f * k + 0.06f) * la);
        particles.emit(sx - rx * 0.2f * w, sz - rz * 0.2f * w, -rx * out + b.vx * 0.1f, -rz * out + b.vz * 0.1f,
                2.0f * lw, 0.2f * w, 1.1f * w, (0.28f * k + 0.06f) * la);
        particles.emit(sx, sz, b.vx * 0.15f, b.vz * 0.15f, 0.9f * lw, 0.28f * w, 0.65f * w, (0.45f * k + 0.08f) * la);
        if (rel > 7f) {
            float bx = b.x + fx * 0.45f * w, bz = b.z + fz * 0.45f * w;
            float side = rnd.nextBoolean() ? 1f : -1f;
            particles.emit(bx + rx * 0.2f * side * w, bz + rz * 0.2f * side * w,
                    rx * side * 1.6f * w + b.vx * 0.3f, rz * side * 1.6f * w + b.vz * 0.3f, 0.5f * lw,
                    0.12f * w, 0.4f * w, 0.5f);
        }
    }

    /**
     * The rooster tail: the propeller throws a plume of water up behind the transom. It
     * grows with speed and throttle; surface-piercing props on hydros and outriggers throw
     * the tallest tails.
     */
    private void roosterTail(Boat b, float dt, float spd) {
        float fwdSpd = b.forwardSpeed();
        float rel = fwdSpd / b.topSpeed;
        if (rel < 0.3f) { b.sprayTimer = 0; return; }
        float k = Boat.clamp((rel - 0.3f) / 0.7f, 0f, 1.3f) * (0.55f + 0.45f * Math.max(0f, b.throttleNow));
        float big = b.fx;                         // life-size: everything ~7.5x larger
        float tall = (float) Math.sqrt(big) * (big > 1.5f ? 1.15f : 1f);
        float hullK = b.hull == Boat.HYDROPLANE || b.hull == Boat.OUTRIGGER || b.hull == Boat.UNLIMITED ? 1.2f
                : b.hull == Boat.CATAMARAN ? 1.0f : 0.85f;
        b.sprayTimer += dt * (70f + 110f * k) * (big > 1.5f ? 0.42f : 1f);   // clumps per second
        float fx = b.fwdX(), fz = b.fwdZ(), rx = b.rightX(), rz = b.rightZ();
        float sc = b.sizeScale;
        float stern = big > 1.5f ? 4.7f : 0.66f * sc;
        float sx = b.x - fx * stern, sz = b.z - fz * stern;
        // in the chase view the player's own tail would block the view: keep it small
        float own = b.isPlayer && view == VIEW_CHASE && state != State.MENU ? 0.45f : 1f;
        while (b.sprayTimer >= 1f) {
            b.sprayTimer -= 1f;
            float side = (rnd.nextFloat() - 0.5f) * 2f;
            float up = (1.3f + 2.8f * k) * hullK * (0.75f + 0.5f * rnd.nextFloat()) * (float) Math.sqrt(sc) * own * tall;
            // the prop throws water back at most of the boat's speed, so the plume hangs
            // in a short, narrow fan behind the transom as the boat runs away from it
            float back = fwdSpd * (0.55f + 0.2f * rnd.nextFloat());
            float vx = b.vx - fx * back + rx * side * 0.45f * tall;
            float vz = b.vz - fz * back + rz * side * 0.45f * tall;
            spray.emit(sx + rx * side * 0.03f * big, b.y + 0.04f, sz + rz * side * 0.03f * big, vx, up, vz,
                    (0.32f + 0.4f * k) * tall, (0.06f + 0.03f * k) * own * big,
                    (0.2f + 0.24f * k * hullK) * own * big,
                    (0.2f + 0.18f * k) * (0.5f + 0.5f * own) * (big > 1.5f ? 0.75f : 1f));
        }
    }

    // ------------------------------------------------------------------ announcers

    /** Decides what the broadcast booth says about what just happened. */
    private void announce(float dt) {
        Announcer a = announcer;
        a.enabled = announcerOn;
        a.tick(dt);
        Boat p = player;

        if (annStartDelay > 0) {
            annStartDelay -= dt;
            if (annStartDelay <= 0) a.say("start", 5, "start", 0f, Announcer.IMPORTANT);
        }
        if (state == State.COUNTDOWN) return;

        // ---- the finish ----
        if (!annWinSaid && order.length > 0 && order[0].finished) {
            annWinSaid = true;
            Boat w = order[0];
            if (w.isPlayer) {
                a.say("winme", 3, "win", 0f, Announcer.URGENT);
                annFinishSaid = true;
            } else {
                a.say("winopp_" + w.id, 0, "win", 0f, Announcer.URGENT);
            }
        }
        if (p.finished) {
            if (!annFinishSaid) {
                if (annFinishDelay == 0) annFinishDelay = 0.8f;
                annFinishDelay -= dt;
                if (annFinishDelay <= 0) {
                    annFinishSaid = true;
                    int fp = positionOf(p);
                    // last place gets its own joke half the time
                    String key = fp == boats.length && fp > 2 && Math.random() < 0.5 ? "fin_last" : "fin_" + fp;
                    a.say(key, 0, "finish", 0f, Announcer.URGENT);
                }
            }
            evBuoy = evShore = evBump = false;
            return;
        }

        // ---- laps to go, counted on the race leader ----
        int leadLap = 1;
        for (Boat b : boats) {
            leadLap = Math.max(leadLap, Math.min(timed() ? 99 : laps, (int) Math.floor(score(b) / track.length) + 1));
        }
        if (leadLap > annLeaderLap && !timed() && event != EVENT_SPEED) {
            annLeaderLap = leadLap;
            int toGo = laps - leadLap + 1;
            if (toGo == 1) a.say("white", 3, "laps", 0f, Announcer.URGENT);
            else if (toGo == 2) a.say("togo_2", 2, "laps", 0f, Announcer.IMPORTANT);
            else if (toGo <= 8) a.say("togo_" + toGo, 0, "laps", 0f, Announcer.IMPORTANT);
        }

        // ---- lead changes and the player's position ----
        boolean settled = raceTime > 3f;
        int leader = order.length > 0 ? order[0].id : -1;
        if (leader != annLeader) {
            if (settled && annLeader >= 0 && boats.length > 1) {
                if (leader == p.id) a.say("leadme", 5, "lead", 4f, Announcer.IMPORTANT);
                else if (annLeader == p.id) a.say("leadlost", 4, "lead", 4f, Announcer.IMPORTANT);
                else a.say("lead_" + leader, leader == 5 ? 3 : 2, "lead", 4f, Announcer.IMPORTANT);
            }
            annLeader = leader;
        }
        int pos = positionOf(p);
        if (pos != annPrevPos) {
            if (settled && annPrevPos > 0) {
                if (pos < annPrevPos) {
                    if (pos > 1) {
                        a.say("upgen", 4, "position", 5f, Announcer.NORMAL);
                    }
                } else if (annPrevPos != 1) {   // losing the lead has its own call
                    a.say("down", 3, "position", 5f, Announcer.NORMAL);
                }
            }
            annPrevPos = pos;
        }

        // ---- incidents ----
        if (evBump) a.say("bump", 5, "bump", 9f, Announcer.NORMAL);
        if (evBuoy) a.say("buoy", 3, "buoy", 9f, Announcer.NORMAL);
        if (p.wrongWayTime > 1.5f && annPrevWrong <= 1.5f) a.say("wrong", 4, "wrong", 10f, Announcer.IMPORTANT);
        if ((p.offCourseTime > 2f && annPrevOff <= 2f) || evShore) a.say("off", 3, "off", 12f, Announcer.NORMAL);
        annPrevWrong = p.wrongWayTime;
        annPrevOff = p.offCourseTime;
        evBuoy = evShore = evBump = false;

        // ---- color commentary when it has been quiet for a while ----
        if (raceTime > 8f && a.quietFor() > 20f && boats.length > 1) {
            if (pos == boats.length) a.say("last", 3, "color", 25f, Announcer.NORMAL);
            else if (pos == 1) a.say("front", 4, "color", 25f, Announcer.NORMAL);
        }
    }

    /**
     * IMPBA ovals: a boat must keep every buoy on the inside of the turn (its right).
     * Passing a buoy's position on the inside of it is a cut.
     */
    private void checkCuts(Boat b, int from, int to) {
        int step = to - from;
        if (step < -Track.N / 2) step += Track.N;
        if (step <= 0 || step > 40) return;   // only forward progress counts
        for (float[] bu : track.buoys) {
            int bi = (int) bu[4];
            int rel = bi - from;
            if (rel <= 0) rel += Track.N;
            if (rel > step) continue;
            float lat = (b.x - track.px[bi]) * track.nx(bi) + (b.z - track.pz[bi]) * track.nz(bi);
            if ((lat - bu[5]) * track.insideSign > 0) {
                int num = (int) bu[3];
                infraction(b, num > 0 ? "CUT BUOY " + num : "CUT A BUOY", "cut");
            }
        }
    }

    /** Lap times: laps start when the boat crosses the start / finish line. */
    private void lapTiming(Boat b) {
        int done = (int) Math.floor(score(b) / track.length);
        if (done > b.lapsDone) {
            if (b.lapsDone >= 0) {
                float lap = raceTime - b.lapStartTime;
                if (b.bestLap == 0 || lap < b.bestLap) b.bestLap = lap;
                if (b.isPlayer && RaceClass.recordsAllowed(raceClass)
                        && records.offer(recordKey("lap"), lap, false)) {
                    setMessage("LAP RECORD  " + formatTime(lap), 2.5f);
                }
            }
            b.lapsDone = done;
            b.lapStartTime = raceTime;
        }
    }

    /**
     * Speed trap on an oval's front chute: the beams on the buoy 6 and buoy 1 lines (330 ft
     * apart on the 1/3-mile oval: the 1/16-mile straightaway). Boats run it westbound.
     */
    private void speedTrap(Boat b, float dt) {
        if (Math.abs(b.z - track.frontBuoyZ) > 12f) { b.trapEnterTime = -1; return; }
        float px = b.x - b.vx * dt;   // position a frame ago
        if (px >= track.trapEast && b.x < track.trapEast) b.trapEnterTime = raceTime;
        if (b.trapEnterTime >= 0 && px >= track.trapWest && b.x < track.trapWest) {
            float t = raceTime - b.trapEnterTime;
            b.trapEnterTime = -1;
            if (t <= 0.2f) return;
            float mph = track.chuteFt / t * 3600f / 5280f;
            b.lastSpeedMph = mph;
            if (mph > b.bestSpeedMph) b.bestSpeedMph = mph;
            if (b.isPlayer && event == EVENT_SPEED) {
                String msg = String.format(java.util.Locale.US, "%.1f MPH", mph);
                if (RaceClass.recordsAllowed(raceClass) && records.offer(recordKey("speed"), mph, true))
                    msg += "  RECORD!";
                setMessage(msg, 2.5f);
            }
        }
    }

    /**
     * Noise rule: gas boats are read by the meter on the shore between buoy 6 and the start
     * line as they pass on the front straight, well under way. Over 92 dB: a warning; over
     * again in a later heat: disqualified.
     */
    private void noiseCheck(Boat b) {
        if (power == ELECTRIC || !track.oval && !track.offshore) return;
        if (b.lastNoiseDb > 0 && Math.abs(b.x - track.meterX) > 15f) b.lastNoiseDb = 0;   // re-arm
        if (b.lastNoiseDb > 0 || Math.abs(b.x - track.meterX) > 1.5f) return;
        if (b.speed() < b.topSpeed * 0.6f || Math.abs(b.z - track.meterZ) > 40f) return;
        float dist = Math.abs(b.z - track.meterZ);
        float db = NOISE_BASE[power] + b.noiseTune + 1.5f * b.throttleNow
                - 20f * (float) Math.log10(Math.max(dist, 5f) / 30f);
        b.lastNoiseDb = db;
        if (db <= NOISE_LIMIT || rules == RULES_FUN) return;
        String what = String.format(java.util.Locale.US, "%s %.0f dB", b.name, db);
        if (noiseWarned[b.id] == 0) {
            noiseWarned[b.id] = heat;
            if (!b.isPlayer) setMessage("NOISE WARNING: " + what, 2.5f);
        } else if (noiseWarned[b.id] < heat) {
            b.disqualified = true;
            b.dqReason = "Noise";
            b.placeAt(track, b.trackIdx, track.insideSign * -12f);
            b.autopilot = true;
            b.maxSpeed = 0.01f;
            setMessage("DISQUALIFIED: " + what, 2.5f);
        }
    }

    /** Record key: event, course, laps, class, engine and hull. */
    String recordKey(String kind) {
        String course = kind.equals("speed") ? "s" : trackId + (radiusBuoys && track.oval ? "r" : "");
        String len = kind.equals("race") ? "|" + (timed() ? "t" : String.valueOf(laps)) : "";
        return kind + "|" + course + len + "|" + raceClass + "|" + power + "|" + hull;
    }

    /** At the player's finish: race-time records and the results-screen note. */
    private void recordFinish(Boat b) {
        if (!b.isPlayer) return;
        StringBuilder n = new StringBuilder();
        boolean allowed = RaceClass.recordsAllowed(raceClass);
        if (event == EVENT_SPEED) {
            n.append(String.format(java.util.Locale.US, "Best speed %.1f mph", b.bestSpeedMph));
            Float r = records.get(recordKey("speed"));
            if (allowed && r != null) n.append(String.format(java.util.Locale.US, "   Record %.1f mph", r));
        } else {
            if (b.bestLap > 0) n.append("Best lap ").append(formatTime(b.bestLap));
            if (allowed && !timed() && b.infractions == 0) {
                boolean rec = records.offer(recordKey("race"), totalTime(b), false);
                if (rec) n.append("   NEW RACE RECORD!");
            }
            Float r = records.get(recordKey("lap"));
            if (allowed && r != null) n.append("   Lap record ").append(formatTime(r));
        }
        if (!allowed) n.append("   (no records in LSG36 Offshore)");
        resultNote = n.toString();
    }

    /** Clock start, AI: hold the speed that reaches the start line just after zero. */
    private void paceAi() {
        for (Boat b : boats) {
            if (!b.autopilot || b.isPlayer) continue;
            float pos = track.cum[b.trackIdx] + Boat.clamp(track.along(b.trackIdx, b.x, b.z), -track.ds, track.ds);
            float d = track.length - pos;
            if (d < 0) d += track.length;
            float t = countdown + b.startDelay;
            float avg = b.maxSpeed * 0.82f;     // average lap speed, turns included
            float v = (d + 0f) / t;
            if (v > avg) { b.paceSpeed = -1; continue; }   // late: go flat out
            int k = 0;
            while ((d + (k + 1) * track.length) / t <= avg) k++;
            v = (d + k * track.length) / t;
            b.paceSpeed = Math.max(1.5f, v);
        }
    }

    /** Clock start: at zero the race is on; laps count from each boat's next line crossing. */
    private void startFromClock() {
        for (Boat b : boats) {
            b.paceSpeed = -1;
            boolean jumped = b.lastCrossClock > 0 && b.lastCrossClock <= JUMP_WINDOW;
            // a boat that crossed in the last seconds is counted as started, but jumped
            b.lapWrap = jumped ? 0 : -1;
            float along = Boat.clamp(track.along(b.trackIdx, b.x, b.z), -track.ds, track.ds);
            b.progress = b.lapWrap * track.length + track.cum[b.trackIdx] + along;
            if (jumped && rules != RULES_FUN) infraction(b, "JUMP START", "jump");
            else if (jumped && b.isPlayer) setMessage("EARLY!", 1.5f);
        }
    }

    /** Clock start: the announcer marks the time left. */
    private void clockCalls() {
        float[] marks = {120f, 60f, 30f, 10f};
        for (float m : marks) {
            if (clockCallAt > m && countdown <= m) {
                announcer.enabled = announcerOn;
                announcer.say("clock_" + (int) m, 0, "clock", 0f, Announcer.IMPORTANT);
            }
        }
        clockCallAt = countdown;
    }

    private void rank() {
        Arrays.sort(order, new Comparator<Boat>() {
            @Override
            public int compare(Boat a, Boat b) {
                if (a.disqualified != b.disqualified) return a.disqualified ? 1 : -1;
                if (a.finished && b.finished) {
                    // timed races: more distance first, then the quicker
                    if (a.finishTarget != b.finishTarget) return Float.compare(b.finishTarget, a.finishTarget);
                    return Float.compare(totalTime(a), totalTime(b));
                }
                if (a.finished) return -1;
                if (b.finished) return 1;
                return Float.compare(score(b), score(a));
            }
        });
    }

    int positionOf(Boat b) {
        for (int i = 0; i < order.length; i++) if (order[i] == b) return i + 1;
        return order.length;
    }

    int currentLap(Boat b) {
        int lap = (int) Math.floor(score(b) / track.length) + 1;
        if (timed()) return Math.max(1, lap);
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
            float k = lifeSize ? 11f : 1f;
            out[0] = (float) Math.sin(a) * 88f * k;
            out[1] = 30f * k;
            out[2] = (float) Math.cos(a) * 70f * k;
            out[3] = 0; out[4] = -2f; out[5] = lifeSize ? 250f : 0;
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
            // life size: the driver sits in the boat, so the view moves with the hull
            if (lifeSize) { tp = b.pitch + att[0] * 0.25f; tr = b.roll + att[1] * 0.25f; }
            if (!camInit) {
                fpvPitch = tp; fpvRoll = tr; fpvY = b.y;
                camInit = true;
            } else {
                float k = Math.min(1f, dt * 3f);
                fpvPitch += (tp - fpvPitch) * k;
                fpvRoll += (tr - fpvRoll) * k;
                fpvY += (b.y - fpvY) * Math.min(1f, dt * 4f);
            }
            if (lifeSize) { fpvPitch = tp; fpvRoll = tr; fpvY = b.y; }
            android.opengl.Matrix.setIdentityM(tmpM, 0);
            android.opengl.Matrix.translateM(tmpM, 0, b.x, fpvY, b.z);
            android.opengl.Matrix.rotateM(tmpM, 0, (float) Math.toDegrees(b.heading), 0, 1, 0);
            android.opengl.Matrix.rotateM(tmpM, 0, (float) Math.toDegrees(fpvPitch), 1, 0, 0);
            android.opengl.Matrix.rotateM(tmpM, 0, (float) Math.toDegrees(fpvRoll), 0, 0, 1);
            if (lifeSize) {
                // the driver's eyes in the cockpit, looking out over the bow
                transform(tmpM, Boat.LIFE_EYE[0], Boat.LIFE_EYE[1], Boat.LIFE_EYE[2], 1f, out, 0);
                transform(tmpM, 0f, Boat.LIFE_EYE[1] - 0.55f, -30f, 1f, out, 3);
            } else {
                transform(tmpM, 0f, 0.40f, -0.20f, 1f, out, 0);
                transform(tmpM, 0f, 0.24f, -6f, 1f, out, 3);
            }
            transform(tmpM, 0f, 1f, 0f, 0f, tmpV, 0);
            out[6] = tmpV[0]; out[7] = tmpV[1]; out[8] = tmpV[2];
            out[9] = lifeSize ? 72f : 74f;
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
            float ck = lifeSize ? 8f : 1f;
            float ex = b.x - fx * 3.6f * ck, ez = b.z - fz * 3.6f * ck;
            float ey = Math.max(b.y + 1.45f * ck, World.height(ex, ez) + 0.7f);
            out[0] = ex; out[1] = ey; out[2] = ez;
            out[3] = b.x + b.fwdX() * 2.0f * ck; out[4] = b.y + 0.35f * ck; out[5] = b.z + b.fwdZ() * 2.0f * ck;
            out[6] = 0; out[7] = 1; out[8] = 0;
            out[9] = 62f;
            out[10] = lifeSize ? 0.5f : 0.1f;
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
