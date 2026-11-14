package com.rcboat.racing;

/**
 * IMPBA racing classes (rule book Section I, Large Scale Gas): which hulls and engines are
 * legal in each, and any special rules. "Open" allows every combination.
 */
final class RaceClass {
    // hull bits
    private static final int V = 1 << Boat.V_HULL, H = 1 << Boat.HYDROPLANE,
            O = 1 << Boat.OUTRIGGER, C = 1 << Boat.CATAMARAN, ANY_HULL = V | H | O | C;
    // power bits: 0 electric, 1 Super Sport 25.5 cc, 2 LSG27, 3 LSG36, 4 TLSG64
    private static final int E = 1, SS = 2, L27 = 4, L36 = 8, T64 = 16, ANY_POWER = 31;

    static final int OPEN = 0, OFFSHORE = 6, SUPER_BOAT = 7, SCALE = 12;

    /** Short name (menu), description (menu info line), legal hulls, legal engines. */
    static final String[] NAMES = {
            "Open", "LSG27", "LSG36", "Sport Hydro", "Twin Rigger", "Super Sport", "Offshore",
            "Super Boat", "Crackerbox", "Thunderboat", "Jersey Skiff", "OB Tunnel", "Scale Unl."};
    static final String[] INFO = {
            "Open: any boat and power",
            "LSG27: outrigger, catamaran, mono or sport hydro, up to 27 cc",
            "LSG36: outrigger, catamaran, mono or sport hydro, up to 36 cc",
            "Sport Hydro: 3-point hydroplane, LSG27 or LSG36",
            "Twin Gas Outrigger: outrigger with a twin (TLSG64) engine",
            "Super Sport: mono or catamaran, spec Zenoah G260 engine",
            "LSG36 Offshore: mono or catamaran, up to 36 cc (no records)",
            "Super Boat Offshore: 55-60 in mono or cat; Offshore Courses A or B only",
            "Crackerbox: scale runabout (mono), Super Sport engine",
            "Classic Thunderboat: 1950s-70s style hydroplane, Super Sport engine",
            "Jersey Speed Skiff: flat-bottom mono, any LSG27 engine",
            "Outboard Tunnel: tunnel hull, 15-31 cc two-stroke",
            "Gas Scale Unlimited: scale hydroplane, up to 31 cc; parts off = +1 lap"};
    private static final int[] HULLS = {ANY_HULL, ANY_HULL, ANY_HULL, H, O, V | C, V | C, V | C, V, H, V, C, H};
    private static final int[] POWERS = {ANY_POWER, L27, L36, L27 | L36, T64, SS, SS | L27 | L36,
            L27 | L36 | T64, SS, SS, L27, SS | L27, SS | L27};
    static final int COUNT = NAMES.length;

    private RaceClass() {}

    /** A legality test (own interface: java.util.function needs API 24, the app runs on 21). */
    interface Ok { boolean test(int v); }

    static boolean hullOk(int cls, int hull) { return (HULLS[cls] & (1 << hull)) != 0; }

    static boolean powerOk(int cls, int power) { return (POWERS[cls] & (1 << power)) != 0; }

    /** Super Boat Offshore runs on Offshore Course A or B only (no left turns). */
    static boolean trackOk(int cls, int track) {
        return cls != SUPER_BOAT || track == Track.OFFSHORE_A || track == Track.OFFSHORE_B;
    }

    /** Records are kept for every class except LSG36 Offshore (I-II-D). */
    static boolean recordsAllowed(int cls) { return cls != OFFSHORE; }

    /** Super Boats are 55-60 in long; everything else is drawn at the usual ~47 in. */
    static float sizeScale(int cls) { return cls == SUPER_BOAT ? 1.2f : 1f; }

    /** Next legal value after v in direction dir (wrapping), testing with ok. */
    static int step(int v, int dir, int count, Ok ok) {
        for (int k = 1; k <= count; k++) {
            int c = ((v + dir * k) % count + count) % count;
            if (ok.test(c)) return c;
        }
        return v;
    }

    static int firstLegal(int count, Ok ok) {
        for (int c = 0; c < count; c++) if (ok.test(c)) return c;
        return 0;
    }
}
