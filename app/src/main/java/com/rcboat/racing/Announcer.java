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
    static final int URGENT = 2;     // goes next, as soon as the current line ends
    static final int CUT = 3;        // the start signal and countdown: cuts in on the beat

    private static final float GAP = 0.5f;          // breather between lines
    private static final float PENDING_LIFE = 4f;   // how long an IMPORTANT line may wait
    private static final float URGENT_LIFE = 9f;    // how long an URGENT line may wait
    private static final float SAME_CLIP_GAP = 15f; // the same clip is not repeated sooner

    private final Random rnd = new Random();
    /** Per line set: shuffled order of its variants and how many have been used. */
    private final HashMap<String, int[]> bags = new HashMap<>();
    private final HashMap<String, Float> lastSaid = new HashMap<>();
    private final HashMap<String, Float> lastClipAt = new HashMap<>();

    // ---- current clip, read by the HUD (which plays it) ----
    int seq;
    String clip;

    boolean enabled = true;
    /** Clips that don't fit this race (e.g. battery jokes in a gas race). */
    final java.util.HashSet<String> banned = new java.util.HashSet<>();
    private float clock;
    private float busyUntil;
    private float lastSpoke;
    /** Lines waiting for a gap: key, group, level, expiry. At most one per group. */
    private final java.util.ArrayList<Object[]> queue = new java.util.ArrayList<>();

    void reset() {
        clock = 0;
        busyUntil = 0;
        lastSpoke = 0;
        queue.clear();
        lastSaid.clear();
        lastClipAt.clear();
    }

    void tick(float dt) {
        clock += dt;
        for (int i = queue.size() - 1; i >= 0; i--) if (clock > (float) queue.get(i)[3]) queue.remove(i);
        if (clock < busyUntil || queue.isEmpty()) return;
        // the most important waiting line goes first; among equals, the oldest
        Object[] best = queue.get(0);
        for (Object[] q : queue) if ((int) q[2] > (int) best[2]) best = q;
        queue.remove(best);
        play((String) best[0]);
    }

    /** Whether the booth is talking right now. */
    boolean busy() { return clock < busyUntil; }

    /** Seconds the current line still has to run. */
    float remaining() { return Math.max(0f, busyUntil - clock); }

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
        if (level < URGENT && last != null && clock - last < minGap) return false;
        String key = base;
        if (variants > 0) {
            // skip variants that don't fit this race
            for (int tries = 0; tries <= variants; tries++) {
                key = base + "_" + nextVariant(base, variants);
                if (!banned.contains(key)) break;
            }
        }
        if (banned.contains(key)) return false;
        if (!AnnouncerClips.DURATION.containsKey(key)) return false;
        Float lastClip = lastClipAt.get(key);
        if (level < URGENT && lastClip != null && clock - lastClip < SAME_CLIP_GAP) return false;
        if (level == CUT || (clock >= busyUntil && queue.isEmpty())) {
            lastSaid.put(group, clock);
            play(key);
            return true;
        }
        if (level >= IMPORTANT) {
            lastSaid.put(group, clock);
            for (int i = queue.size() - 1; i >= 0; i--) if (queue.get(i)[1].equals(group)) queue.remove(i);
            queue.add(new Object[]{key, group, level, clock + (level == URGENT ? URGENT_LIFE : PENDING_LIFE)});
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
        lastClipAt.put(key, clock);
        lastSpoke = clock;
        busyUntil = clock + AnnouncerClips.DURATION.get(key) + GAP;
    }

    static String assetPath(String key) {
        return "announcer/" + key + ".ogg";
    }
}
