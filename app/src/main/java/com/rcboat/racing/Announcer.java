package com.rcboat.racing;

import java.util.HashMap;
import java.util.Random;

/**
 * Picks which pre-recorded announcer clip to play (assets/announcer/&lt;key&gt;.ogg) and keeps
 * the booth from talking over itself. Voice only: nothing is shown on screen.
 */
final class Announcer {
    /** How a line competes with what is already being said. */
    static final int NORMAL = 0;     // dropped if the booth is busy
    static final int IMPORTANT = 1;  // waits a few seconds for a gap
    static final int URGENT = 2;     // cuts in immediately

    private static final float GAP = 0.7f;          // breather between lines
    private static final float PENDING_LIFE = 4f;   // how long an IMPORTANT line may wait

    private final Random rnd = new Random();
    /** Per line set: shuffled order of its variants and how many have been used. */
    private final HashMap<String, int[]> bags = new HashMap<>();
    private final HashMap<String, Float> lastSaid = new HashMap<>();

    // ---- current clip, read by the HUD (which plays it) ----
    int seq;
    String clip;

    boolean enabled = true;
    private float clock;
    private float busyUntil;
    private float lastSpoke;
    private String pending;
    private float pendingUntil;

    void reset() {
        clock = 0;
        busyUntil = 0;
        lastSpoke = 0;
        pending = null;
        lastSaid.clear();
    }

    void tick(float dt) {
        clock += dt;
        if (pending != null) {
            if (clock > pendingUntil) pending = null;
            else if (clock >= busyUntil) {
                String key = pending;
                pending = null;
                play(key);
            }
        }
    }

    /** Seconds since the booth last started a line. */
    float quietFor() { return clock - lastSpoke; }

    /**
     * Says clip {@code base} (or one of {@code base_0 .. base_(variants-1)}).
     *
     * @param group  lines of the same group are kept at least {@code minGap} seconds apart
     */
    boolean say(String base, int variants, String group, float minGap, int level) {
        if (!enabled) return false;
        Float last = lastSaid.get(group);
        if (level != URGENT && last != null && clock - last < minGap) return false;
        String key = base;
        if (variants > 0) {
            key = base + "_" + nextVariant(base, variants);
        }
        if (!AnnouncerClips.DURATION.containsKey(key)) return false;
        if (level == URGENT || clock >= busyUntil) {
            lastSaid.put(group, clock);
            play(key);
            return true;
        }
        if (level == IMPORTANT) {
            lastSaid.put(group, clock);
            pending = key;
            pendingUntil = clock + PENDING_LIFE;
            return true;
        }
        return false;
    }

    /** Every variant of a set is used once, in random order, before any repeats. */
    private int nextVariant(String base, int variants) {
        int[] bag = bags.get(base);
        if (bag == null || bag[variants] >= variants) {
            int last = bag == null ? -1 : bag[variants - 1];
            bag = new int[variants + 1];
            for (int i = 0; i < variants; i++) bag[i] = i;
            for (int i = variants - 1; i > 0; i--) {
                int j = rnd.nextInt(i + 1);
                int t = bag[i]; bag[i] = bag[j]; bag[j] = t;
            }
            if (variants > 1 && bag[0] == last) { bag[0] = bag[1]; bag[1] = last; }
            bags.put(base, bag);
        }
        return bag[bag[variants]++];
    }

    private void play(String key) {
        clip = key;
        seq++;
        lastSpoke = clock;
        busyUntil = clock + AnnouncerClips.DURATION.get(key) + GAP;
    }

    static String assetPath(String key) {
        return "announcer/" + key + ".ogg";
    }
}
