package com.rcboat.racing;

import java.util.Random;

/**
 * The opponents' trash talk aimed at the player, Dan. Lines are grouped by the race moment
 * that triggers them; each opponent has a voice (pre-rendered clips in assets/voice/).
 */
final class TrashTalk {
    // categories: first and last line index (inclusive)
    static final int START = 0, PASS = 1, BUOY = 2, BUMP = 3, WRONG_WAY = 4, OFF_COURSE = 5,
            LAST_PLACE = 6, LAPPED = 7, DAN_PASSES = 8, FINAL_LAP = 9, OPP_FINISHED = 10,
            DAN_FINISHED = 11, DAN_WINS = 12;
    private static final int[][] RANGE = {
            {0, 14}, {15, 29}, {30, 39}, {40, 47}, {48, 55}, {56, 63}, {64, 74}, {75, 79},
            {80, 87}, {88, 89}, {90, 90}, {91, 97}, {98, 99}
    };
    /** Minimum seconds between two lines of the same category. */
    private static final float[] CATEGORY_GAP = {0, 10, 12, 12, 15, 15, 25, 10, 10, 0, 0, 0, 0};
    /** Chance that an event actually produces a line (keeps it from getting repetitive). */
    private static final float[] CHANCE = {1f, 0.75f, 0.7f, 0.8f, 1f, 1f, 1f, 1f, 0.75f, 1f, 1f, 1f, 1f};

    /** Driver names of the opponents, by boat id (0 is Dan). */
    static final String[] DRIVERS = {"Dan", "Mike", "Travis", "Cody", "Hank", "Duke"};

    static final String[] LINES = {
            "Hey Dan, the start is the part where you go forward.",
            "Dan, your boat's already apologizing to mine.",
            "Good luck, Dan. Actually, take all of it. You need it more.",
            "I've seen faster boats in a bathtub, Dan. Rubber ducks, mostly.",
            "Dan, did you charge the battery or just say nice things to it?",
            "Somebody tell Dan this isn't a pedal boat rental.",
            "Dan's boat has \"Student Driver\" on the back. In crayon.",
            "Ready, set, Dan's already confused.",
            "Dan, you're gripping that transmitter like it owes you money.",
            "Hey Dan, the lake called. It's nervous.",
            "Dan brought a boat to a race. Bold strategy.",
            "Dan, I'll wave when I lap you. It's only polite.",
            "Is that a race boat, Dan, or a floating apology?",
            "Dan, blink twice if your boat is being held hostage.",
            "Last time Dan raced, the buoys filed a restraining order.",
            "Beep beep, Dan! Oh wait, boats don't beep. Bye, Dan!",
            "On your left, Dan. Also on your right. Also everywhere.",
            "Dan, I'd say \"see you at the finish,\" but I won't.",
            "Pardon me, Dan, coming through. Forever.",
            "That breeze, Dan? That was me.",
            "Dan, you're the reason my rearview mirror is so boring.",
            "Passing Dan is my warm-up lap.",
            "Don't worry, Dan, second-to-last is still a number.",
            "Dan, you missed a spot. The front. Of the race.",
            "Enjoy my wake, Dan. It's the closest you'll get to me.",
            "Dan's boat is so slow it gets passed by the weather.",
            "I just passed Dan and didn't even need to try. Lunch break, anyone?",
            "See ya, Dan! Wouldn't wanna be ya!",
            "Dan, tell your boat mine says hi. And goodbye.",
            "That wasn't a pass, Dan. That was a donation.",
            "Dan, the buoys are the things you go AROUND.",
            "Dan just high-fived a buoy. Wholesome. Slow, but wholesome.",
            "That buoy didn't do anything to you, Dan!",
            "Dan's collecting buoys like they're Pokémon.",
            "BONK! Nice one, Dan. Very aerodynamic.",
            "Dan, the buoy is red because it's embarrassed for you.",
            "Dan treats the buoys like bumper cars.",
            "Somebody check on the buoy. Dan's fine. The buoy's traumatized.",
            "Dan, steering is optional, but it's recommended.",
            "That's the third buoy, Dan. They're unionizing.",
            "Hey! Dan! This isn't bumper boats, buddy!",
            "Dan, if you wanted a hug you could've just asked.",
            "Dan's driving like his insurance is paid by someone else.",
            "Watch it, Dan, I just waxed this hull!",
            "Dan's racing strategy: chaos and vibes.",
            "Was that a pass attempt, Dan, or a cry for help?",
            "Dan, I've filed a report with the Lake Authority.",
            "You touch my boat again, Dan, and I'm telling your mom.",
            "Dan! DAN! The race is THAT way!",
            "Dan's doing a victory lap. Backwards. Before winning anything.",
            "Dan found a shortcut to last place.",
            "Bold move, Dan. Racing everyone head-on.",
            "Dan, are you lost or just exploring your options?",
            "Dan's GPS is set to \"Somewhere Else.\"",
            "Somebody turn Dan around. Gently. He's fragile.",
            "Dan thinks the finish line is a suggestion.",
            "Dan's trying to drive his boat home. Across the grass.",
            "Dan, that's land. Boats hate land.",
            "Dan has discovered the beach. Congrats, Dan.",
            "Dan's going for the amphibious class. Ambitious.",
            "Dan, the water's over here! We miss you! (We don't.)",
            "Dan's boat is sightseeing again.",
            "Dan just parked. Nobody told him the race isn't over.",
            "If Dan drives off course any farther, he'll need a passport.",
            "Dan's not last, he's just… thoroughly behind everyone.",
            "Dan's holding the field together. From the back.",
            "Dan, you're so far back you're in a different time zone.",
            "Hey Dan! Can you hear me? You're kind of far away.",
            "Dan's guarding the rear. Nobody's attacking, but thanks, Dan.",
            "Is Dan racing or just keeping the lake company?",
            "Dan's boat is in no rush. Very zen. Very slow.",
            "Last place is a great place to think about your choices, Dan.",
            "Dan, at this rate you'll finish before winter. Probably.",
            "Dan's lap time is measured in calendars.",
            "Dan's boat has a top speed of \"eventually.\"",
            "Welcome to Lapped Town, Dan. Population: you.",
            "I lapped Dan. Twice would be rude. So I'll do it slowly.",
            "Dan, I've seen you more times than my own family today.",
            "Oh hey Dan, again! Fancy meeting you here. Again.",
            "Dan, if you're gonna get lapped, at least make it interesting.",
            "Whatever, Dan. I let you have that one.",
            "Enjoy it, Dan. It's the highlight of your career.",
            "Dan passed me?! My battery's low. That's the only explanation.",
            "Lucky wave, Dan. Lucky wave.",
            "Okay Dan, now you've made it personal.",
            "I sneezed, Dan. That's the only reason.",
            "Dan's in front of me? Somebody check the rules.",
            "That's cute, Dan. Real cute. I'm coming for you.",
            "Last lap, Dan! Try to finish before the sun sets.",
            "Dan, the checkered flag is the finish, not a picnic blanket.",
            "I'm finished. Dan's still deciding.",
            "Dan's crossing the line! Somebody get a calendar to record the time.",
            "GG, Dan. Mostly G for me, though.",
            "Dan, you finished! That counts as a personal best, right?",
            "Don't worry Dan, the podium has a basement.",
            "Dan came, Dan saw, Dan got lapped.",
            "Rematch, Dan? Please say yes. I need the easy win.",
            "Dan, you raced like a champion. A champion of something else.",
            "Dan won?! I demand a recount. And a buoy inspection.",
            "Fine, Dan. You win this time. Enjoy it, it won't happen again."
    };

    private final Random rnd = new Random();
    private final int[][] bags = new int[RANGE.length][];
    private final int[] bagPos = new int[RANGE.length];
    private final float[] lastAt = new float[RANGE.length];

    // ---- current line, read by the HUD ----
    int seq;            // increments for every new line
    int speaker;        // boat id of the speaker (1..5)
    int line = -1;      // index into LINES
    private float busyUntil;
    private float clock;

    TrashTalk() {
        for (int c = 0; c < RANGE.length; c++) {
            int n = RANGE[c][1] - RANGE[c][0] + 1;
            bags[c] = new int[n];
            for (int i = 0; i < n; i++) bags[c][i] = RANGE[c][0] + i;
            shuffle(bags[c]);
            lastAt[c] = -999f;
        }
    }

    private void shuffle(int[] a) {
        for (int i = a.length - 1; i > 0; i--) {
            int j = rnd.nextInt(i + 1);
            int t = a[i]; a[i] = a[j]; a[j] = t;
        }
    }

    void reset() {
        clock = 0;
        busyUntil = 0;
        line = -1;
        for (int c = 0; c < RANGE.length; c++) lastAt[c] = -999f;
    }

    void tick(float dt) { clock += dt; }

    boolean busy() { return clock < busyUntil; }

    /**
     * Tries to say a line of the given category. Priority lines interrupt and ignore
     * cooldowns. Returns true if a line was chosen.
     */
    boolean say(int category, int speakerId, boolean priority) {
        if (speakerId <= 0) return false;
        if (!priority) {
            if (busy()) return false;
            if (clock - lastAt[category] < CATEGORY_GAP[category]) return false;
            if (rnd.nextFloat() > CHANCE[category]) return false;
        }
        int[] bag = bags[category];
        if (bagPos[category] >= bag.length) {
            shuffle(bag);
            bagPos[category] = 0;
        }
        line = bag[bagPos[category]++];
        speaker = speakerId;
        seq++;
        lastAt[category] = clock;
        // rough speaking time, plus a breather before the next line
        busyUntil = clock + 1.6f + LINES[line].length() * 0.065f + 3.5f;
        return true;
    }

    static String assetPath(int speakerId, int line) {
        return String.format(java.util.Locale.US, "voice/%d/%03d.ogg", speakerId - 1, line);
    }
}
