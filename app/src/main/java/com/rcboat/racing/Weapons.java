package com.rcboat.racing;

/**
 * RC Boat Combat: the weapons a boat can carry and the pickups floating on the lake.
 * Distances and speeds are for RC boats and are multiplied by the boat's scale (fx) for the
 * life-size hydroplanes.
 */
final class Weapons {
    private Weapons() {}

    static final int MACHINE_GUN = 0, FLAMETHROWER = 1, DRILL = 2, TORPEDO = 3, HARPOON = 4,
            MINES = 5, WATER_CANNON = 6, ROCKETS = 7;
    static final int COUNT = 8;

    static final String[] NAMES = {"Machine Gun", "Flamethrower", "Bow Drill", "Torpedoes", "Harpoon",
            "Mine Layer", "Water Cannon", "Rocket Pod"};
    static final String[] SHORT = {"GUN", "FLAME", "DRILL", "TORPEDO", "HARPOON", "MINES", "CANNON", "ROCKETS"};
    static final String[] INFO = {
            "Turret: auto-aims, fast and steady damage",
            "Short range: sets boats on fire",
            "Ram them: punches holes, they fill with water and sink",
            "Homing torpedoes: big hit and a hole below the waterline",
            "Spears a boat and tows it: drag it into mines and the shore",
            "Drops mines behind you: lead them into a trap",
            "Knocks boats away, swamps them and puts out fires",
            "Salvo of four homing rockets with splash damage",
    };

    /** Turret traverse each side of the bow (rad); 0 = fixed, fires straight ahead. */
    static final float[] ARC = {1.15f, 0.25f, 0f, 0.9f, 0.7f, 0f, 1.0f, 0.35f};
    /** Range (m, RC scale). */
    static final float[] RANGE = {24f, 7.5f, 2.2f, 30f, 20f, 14f, 15f, 30f};
    /** Magazine size (shots, or seconds of fuel/water for the flamethrower and cannon). */
    static final float[] MAG = {60f, 4.5f, 1f, 2f, 1f, 5f, 5f, 8f};
    /** Reload time for a full magazine (s). */
    static final float[] RELOAD = {2.2f, 3.0f, 0f, 3.2f, 2.4f, 5f, 3f, 4f};
    /** Time between shots (s). */
    static final float[] RATE = {0.085f, 0f, 0f, 0.9f, 0f, 0.6f, 0f, 0.14f};

    // ---- damage (hull points; a boat has 100 at normal battle length) ----
    static final float BULLET_DMG = 2.8f;
    static final float FLAME_DPS = 7.5f, BURN_DPS = 4.5f, BURN_TIME = 3.5f;
    static final float DRILL_DMG = 15f;
    static final float TORPEDO_DMG = 26f;
    static final float HARPOON_DMG = 20f, TETHER_TIME = 4.5f;
    static final float MINE_DMG = 40f, MINE_RADIUS = 1.4f, MINE_ARM = 0.6f;
    static final float CANNON_DPS = 9.5f, CANNON_PUSH = 22f, CANNON_SWAMP = 0.15f;
    static final float ROCKET_DMG = 8f, ROCKET_SPLASH = 2.6f;
    /** Each hole lets this much water in per second; the bilge pump clears PUMP_RATE. */
    static final float LEAK_RATE = 0.022f, PUMP_RATE = 0.009f;

    // ---- pickups: crates float on the lake ----
    static final int PU_REPAIR = 0, PU_PUMP = 1, PU_POWER = 2, PU_NITRO = 3, PU_SHIELD = 4, PU_EMP = 5,
            PU_OIL = 6, PU_SMOKE = 7;
    static final int PU_COUNT = 8;
    static final String[] PU_NAMES = {"REPAIR KIT", "BILGE PUMP", "DOUBLE DAMAGE", "NITRO", "SHIELD", "EMP BLAST",
            "OIL SLICK", "SMOKE SCREEN"};
    static final String[] PU_SHORT = {"REPAIR", "PUMP", "POWER", "NITRO", "SHIELD", "EMP", "OIL", "SMOKE"};
    /** Used straight away when picked up (the rest are kept for the SPECIAL button). */
    static final boolean[] PU_INSTANT = {true, true, true, false, false, false, false, false};
    /** Crate colours. */
    static final float[][] PU_COLOR = {
            {0.2f, 0.85f, 0.3f}, {0.2f, 0.6f, 1f}, {0.95f, 0.2f, 0.2f}, {1f, 0.55f, 0.05f},
            {0.35f, 0.9f, 1f}, {0.75f, 0.35f, 1f}, {0.15f, 0.12f, 0.1f}, {0.7f, 0.7f, 0.72f}};
    /** How often each kind turns up (relative). */
    static final float[] PU_WEIGHT = {3f, 2f, 1.2f, 1.5f, 1.3f, 1f, 1f, 1f};
    static final float NITRO_TIME = 3.5f, SHIELD_TIME = 6f, POWER_TIME = 10f, EMP_TIME = 2.6f,
            EMP_RADIUS = 12f, OIL_RADIUS = 5f, OIL_TIME = 16f, SMOKE_TIME = 7f, SMOKE_RADIUS = 6f;
    static final float REPAIR_HP = 35f;
}
