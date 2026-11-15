package com.rcboat.racing;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.MediaPlayer;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;

/** 2D overlay: menu, race HUD, minimap, on-screen joysticks and buttons. */
final class HudView extends View {

    private interface Action { void run(); }

    private static final class Btn {
        final RectF r;
        final Action a;
        Btn(RectF r, Action a) { this.r = r; this.a = a; }
    }

    /** A spring-centred on-screen joystick. */
    private static final class Stick {
        float cx, cy, radius;
        int pointer = -1;
        float kx, ky;              // knob offset, -1..1
        boolean useX = true, useY = true;

        void move(float x, float y) {
            float dx = useX ? (x - cx) / radius : 0f;
            float dy = useY ? (y - cy) / radius : 0f;
            float l = (float) Math.sqrt(dx * dx + dy * dy);
            if (l > 1f) { dx /= l; dy /= l; }
            kx = dx;
            ky = dy;
        }

        void release() {
            pointer = -1;
            kx = ky = 0;
        }
    }

    private final Game game;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();
    private final ArrayList<Btn> buttons = new ArrayList<>();
    private final Stick left = new Stick(), right = new Stick();
    private float u;  // 1% of the screen's short side

    // announcer voice playback
    private int annSeq;
    private MediaPlayer voice;

    // combat: sound effects, and the FIRE / SPECIAL buttons (FIRE is held down)
    private final Sfx sfx;
    private int firePointer = -1;
    private float fireX, fireY, fireR, specX, specY, specR, weapX, weapY, weapR;
    private boolean weaponMenu;
    private boolean combatControls;

    HudView(Context ctx, Game game) {
        super(ctx);
        this.game = game;
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        sfx = Game.combatBuild ? new Sfx(ctx) : null;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        u = Math.min(w, h) / 100f;
        buttons.clear();
        String newClip = null;
        boolean inMenu;
        synchronized (game) {
            inMenu = game.state == Game.State.MENU;
            if (game.announcer.seq != annSeq) {
                annSeq = game.announcer.seq;
                if (game.soundOn && game.announcerOn && !inMenu) newClip = game.announcer.clip;
            }
            switch (game.state) {
                case MENU:
                    if (Game.combatBuild) drawCombatMenu(c, w, h);
                    else drawMenu(c, w, h);
                    break;
                default:
                    if (Game.combatBuild) drawCombat(c, w, h);
                    else drawRace(c, w, h);
                    break;
            }
            if (sfx != null) {
                Combat cb = game.combat;
                boolean on = game.soundOn && !game.paused;
                while (cb.sfxTail != cb.sfxHead) {
                    int i = cb.sfxTail & 63;
                    if (on) sfx.play(cb.sfxId[i], cb.sfxVol[i]);
                    cb.sfxTail++;
                }
            }
        }
        if (newClip != null) playVoice(newClip);
        else if (voice != null && (inMenu || !game.soundOn || !game.announcerOn)) stopVoice();
        postInvalidateOnAnimation();
    }

    private void drawMenu(Canvas c, int w, int h) {
        releaseSticks();
        if (game.menuScreen == Game.MENU_MAIN) { drawMainMenu(c, w, h); return; }
        if (game.menuScreen == Game.MENU_LIFE) { drawLifeMenu(c, w, h); return; }
        float pw = Math.min(w * 0.94f, 190 * u), ph = 94 * u;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);

        textC(c, "RC BOAT RACING", w / 2f, py + 9 * u, 7 * u, Color.WHITE);
        final Game g = game;
        backButton(c, px, py);
        Track tr = g.track;
        String line1 = tr.name + (tr.impba && tr.info.length() > 0 ? " (" + tr.info + ")" : "")
                + (g.event != Game.EVENT_RACE ? "  \u2022  " + Game.EVENT_INFO[g.event] : "")
                + "  \u2022  " + Boat.HULL_NAMES[g.hull] + ": " + Boat.HULL_INFO[g.hull];
        String line2 = (g.raceClass != RaceClass.OPEN ? RaceClass.INFO[g.raceClass] : Game.POWER_INFO[g.power])
                + "  \u2022  " + Game.RULE_INFO[g.rules];
        textFit(c, line1, w / 2f, py + 14.5f * u, 3.2f * u, pw - 8 * u, 0xFF9FD8FF);
        textFit(c, line2, w / 2f, py + 18.5f * u, 3.2f * u, pw - 8 * u, 0xFF9FD8FF);

        // two clearly separated columns of setting cards, each with a heading
        float gap = 5 * u;
        float colW = (pw - 6 * u - gap) / 2f;
        float x0 = px + 3 * u, x1 = x0 + colW + gap;
        float rowH = 7.6f * u;
        float y0 = py + 27 * u;
        heading(c, x0, py + 24.2f * u, colW, "RACE");
        heading(c, x1, py + 24.2f * u, colW, "BOAT & OPTIONS");

        boolean solo = g.event != Game.EVENT_RACE;
        int nE = Game.EVENT_NAMES.length, nT = Track.COUNT, nH = Boat.HULL_NAMES.length,
                nP = Game.POWER_NAMES.length, nS = Game.START_NAMES.length, nC = RaceClass.COUNT;
        String len = g.timed() ? "5 min" : g.laps + (g.laps == 1 ? " lap" : " laps");
        if (g.event == Game.EVENT_SPEED && !g.timed()) len = g.laps + (g.laps == 1 ? " lap" : " laps");
        row(c, x0, y0, colW, rowH, "Event", Game.EVENT_NAMES[g.event],
                () -> { g.event = (g.event + nE - 1) % nE; g.enforceRules(); },
                () -> { g.event = (g.event + 1) % nE; g.enforceRules(); });
        row(c, x0, y0 + rowH, colW, rowH, "Track", shortTrack(tr),
                () -> stepTrack(g, -1), () -> stepTrack(g, 1));
        row(c, x0, y0 + 2 * rowH, colW, rowH, "Length", len,
                () -> g.laps = g.laps <= 1 ? Game.TIMED : (g.timed() ? 9 : g.laps - 1),
                () -> g.laps = g.timed() ? 1 : (g.laps >= 9 ? Game.TIMED : g.laps + 1));
        row(c, x0, y0 + 3 * rowH, colW, rowH, "Opponents", solo ? "Solo" : String.valueOf(g.opponents),
                () -> { if (!solo) g.opponents = Math.max(0, g.opponents - 1); },
                () -> { if (!solo) g.opponents = Math.min(Game.MAX_OPPONENTS, g.opponents + 1); });
        row(c, x0, y0 + 4 * rowH, colW, rowH, "Difficulty", Game.DIFFICULTY[g.difficulty],
                () -> g.difficulty = Math.max(0, g.difficulty - 1),
                () -> g.difficulty = Math.min(2, g.difficulty + 1));
        row(c, x0, y0 + 5 * rowH, colW, rowH, "Start",
                g.event == Game.EVENT_SPEED ? "Standing" : Game.START_NAMES[g.startMode],
                () -> g.startMode = RaceClass.step(g.startMode, -1, nS, g::startOk),
                () -> g.startMode = RaceClass.step(g.startMode, 1, nS, g::startOk));
        row(c, x0, y0 + 6 * rowH, colW, rowH, "Buoy rule", Game.RULE_NAMES[g.rules],
                () -> g.rules = (g.rules + 2) % 3, () -> g.rules = (g.rules + 1) % 3);

        row(c, x1, y0, colW, rowH, "Class", RaceClass.NAMES[g.raceClass],
                () -> { g.raceClass = (g.raceClass + nC - 1) % nC; g.enforceRules(); },
                () -> { g.raceClass = (g.raceClass + 1) % nC; g.enforceRules(); });
        row(c, x1, y0 + rowH, colW, rowH, "Boat", Boat.HULL_NAMES[g.hull],
                () -> g.hull = RaceClass.step(g.hull, -1, nH, hh -> RaceClass.hullOk(g.raceClass, hh)),
                () -> g.hull = RaceClass.step(g.hull, 1, nH, hh -> RaceClass.hullOk(g.raceClass, hh)));
        row(c, x1, y0 + 2 * rowH, colW, rowH, "Power", Game.POWER_NAMES[g.power],
                () -> { g.power = RaceClass.step(g.power, -1, nP, q -> RaceClass.powerOk(g.raceClass, q)); g.enforceRules(); },
                () -> { g.power = RaceClass.step(g.power, 1, nP, q -> RaceClass.powerOk(g.raceClass, q)); g.enforceRules(); });
        row(c, x1, y0 + 3 * rowH, colW, rowH, "Camera", Game.VIEW_NAMES[g.view],
                () -> g.view = (g.view + 2) % 3, () -> g.view = (g.view + 1) % 3);
        row(c, x1, y0 + 4 * rowH, colW, rowH, "Controls", Game.CONTROL_NAMES[g.controls],
                () -> g.controls = g.nextControls(-1), () -> g.controls = g.nextControls(1));
        String snd = !g.soundOn ? "Off" : g.announcerOn ? "On" : "No voice";
        row(c, x1, y0 + 5 * rowH, colW, rowH, "Sound", snd, () -> cycleSound(g, -1), () -> cycleSound(g, 1));
        row(c, x1, y0 + 6 * rowH, colW, rowH, "Radius buoys", g.radiusBuoys ? "On" : "Off",
                () -> { g.radiusBuoys = !g.radiusBuoys; g.enforceRules(); },
                () -> { g.radiusBuoys = !g.radiusBuoys; g.enforceRules(); });

        float bw = 56 * u, bh = 10 * u;
        button(c, (w - bw) / 2f, py + ph - bh - 2.5f * u, bw, bh, "START RACE", 0xFF2E9E4F, 5.2f * u,
                game::startRace);
    }

    /** Top-left "back to the main menu" button of a settings menu. */
    private void backButton(Canvas c, float px, float py) {
        button(c, px + 3 * u, py + 3 * u, 20 * u, 8 * u, "\u2039 BACK", 0xFF35506A, 3.6f * u,
                () -> game.menuScreen = Game.MENU_MAIN);
    }

    /** The first screen: RC boats or life-size Unlimited hydroplanes. */
    private void drawMainMenu(Canvas c, int w, int h) {
        float pw = Math.min(w * 0.9f, 170 * u), ph = 80 * u;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        textC(c, "BOAT RACING", w / 2f, py + 12 * u, 8 * u, Color.WHITE);
        textC(c, "Choose your racing", w / 2f, py + 19 * u, 3.6f * u, 0xFF9FD8FF);
        float cw = (pw - 15 * u) / 2f, ch = 48 * u, cy = py + 25 * u;
        final Game g = game;
        modeCard(c, px + 5 * u, cy, cw, ch, "RC BOATS", "1/8 scale racing on the lake",
                "IMPBA courses, classes and rules", 0xFF2E7D9E, () -> { g.setMode(false); g.menuScreen = Game.MENU_RC; });
        modeCard(c, px + 10 * u + cw, cy, cw, ch, "LIFE SIZE", "Unlimited hydroplanes",
                "Turbine power, ~175 mph, 1.5-2.5 mile courses", 0xFFB5482A,
                () -> { g.setMode(true); g.menuScreen = Game.MENU_LIFE; });
    }

    private void modeCard(Canvas c, float x, float y, float cw, float ch, String title, String l1, String l2,
                          int color, Action a) {
        button(c, x, y, cw, ch, "", color, 4 * u, a);
        textC(c, title, x + cw / 2f, y + ch * 0.42f, 7 * u, Color.WHITE);
        textFit(c, l1, x + cw / 2f, y + ch * 0.62f, 3.6f * u, cw - 4 * u, 0xFFEAF4FA);
        textFit(c, l2, x + cw / 2f, y + ch * 0.76f, 3.2f * u, cw - 4 * u, 0xFFD0E4F0);
    }

    /** Settings for life-size racing. */
    private void drawLifeMenu(Canvas c, int w, int h) {
        float pw = Math.min(w * 0.94f, 190 * u), ph = 94 * u;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        textC(c, "LIFE SIZE HYDROPLANES", w / 2f, py + 9 * u, 6.5f * u, Color.WHITE);
        final Game g = game;
        backButton(c, px, py);
        Track tr = g.track;
        textFit(c, tr.name + " (" + tr.info + ")  \u2022  Unlimited hydroplane: turbine, ~175 mph",
                w / 2f, py + 15.5f * u, 3.2f * u, pw - 8 * u, 0xFF9FD8FF);
        textFit(c, Game.RULE_INFO[g.rules], w / 2f, py + 19.5f * u, 3.2f * u, pw - 8 * u, 0xFF9FD8FF);

        float gap = 5 * u;
        float colW = (pw - 6 * u - gap) / 2f;
        float x0 = px + 3 * u, x1 = x0 + colW + gap;
        float rowH = 8.8f * u;
        float y0 = py + 27 * u;
        heading(c, x0, py + 24.2f * u, colW, "RACE");
        heading(c, x1, py + 24.2f * u, colW, "OPTIONS");
        int nS = Game.START_NAMES.length;
        String len = g.timed() ? "5 min" : g.laps + (g.laps == 1 ? " lap" : " laps");
        row(c, x0, y0, colW, rowH, "Course", tr.name.replace(" Course", ""),
                () -> stepTrack(g, -1), () -> stepTrack(g, 1));
        row(c, x0, y0 + rowH, colW, rowH, "Length", len,
                () -> g.laps = g.laps <= 1 ? Game.TIMED : (g.timed() ? 9 : g.laps - 1),
                () -> g.laps = g.timed() ? 1 : (g.laps >= 9 ? Game.TIMED : g.laps + 1));
        row(c, x0, y0 + 2 * rowH, colW, rowH, "Opponents", String.valueOf(g.opponents),
                () -> g.opponents = Math.max(0, g.opponents - 1),
                () -> g.opponents = Math.min(Game.MAX_OPPONENTS, g.opponents + 1));
        row(c, x0, y0 + 3 * rowH, colW, rowH, "Difficulty", Game.DIFFICULTY[g.difficulty],
                () -> g.difficulty = Math.max(0, g.difficulty - 1),
                () -> g.difficulty = Math.min(2, g.difficulty + 1));
        row(c, x0, y0 + 4 * rowH, colW, rowH, "Start", Game.START_NAMES[g.startMode],
                () -> g.startMode = RaceClass.step(g.startMode, -1, nS, g::startOk),
                () -> g.startMode = RaceClass.step(g.startMode, 1, nS, g::startOk));
        row(c, x0, y0 + 5 * rowH, colW, rowH, "Buoy rule", Game.RULE_NAMES[g.rules],
                () -> g.rules = (g.rules + 2) % 3, () -> g.rules = (g.rules + 1) % 3);
        row(c, x1, y0, colW, rowH, "Camera", g.view == Game.VIEW_FPV ? "Cockpit" : "Chase",
                () -> g.view = g.view == Game.VIEW_FPV ? Game.VIEW_CHASE : Game.VIEW_FPV,
                () -> g.view = g.view == Game.VIEW_FPV ? Game.VIEW_CHASE : Game.VIEW_FPV);
        row(c, x1, y0 + rowH, colW, rowH, "Controls", Game.CONTROL_NAMES[g.controls],
                () -> g.controls = g.nextControls(-1), () -> g.controls = g.nextControls(1));
        String snd = !g.soundOn ? "Off" : g.announcerOn ? "On" : "No voice";
        row(c, x1, y0 + 2 * rowH, colW, rowH, "Sound", snd, () -> cycleSound(g, -1), () -> cycleSound(g, 1));

        float bw = 56 * u, bh = 10 * u;
        button(c, (w - bw) / 2f, py + ph - bh - 2.5f * u, bw, bh, "START RACE", 0xFF2E9E4F, 5.2f * u,
                game::startRace);
    }

    /** Next track the class and event allow. */
    private static void stepTrack(Game g, int dir) {
        if (g.event == Game.EVENT_SPEED) return;   // speed runs use the 1/3-mile oval's straightaway
        if (g.lifeSize) {
            g.setTrack(RaceClass.step(g.trackId, dir, Track.COUNT, t -> Track.inMode(t, true)));
            return;
        }
        g.setTrack(RaceClass.step(g.trackId, dir, Track.COUNT,
                t -> Track.inMode(t, false) && RaceClass.trackOk(g.raceClass, t)));
    }

    /** Sound: On -> No voice (no announcer) -> Off. */
    private static void cycleSound(Game g, int dir) {
        int st = !g.soundOn ? 2 : g.announcerOn ? 0 : 1;
        st = (st + dir + 3) % 3;
        g.soundOn = st != 2;
        g.announcerOn = st == 0;
    }

    /** Centred text, shrunk to fit maxW if needed. */
    private void textFit(Canvas c, String s, float x, float y, float size, float maxW, int color) {
        text.setTextSize(size);
        float tw = text.measureText(s);
        textC(c, s, x, y, tw > maxW ? size * maxW / tw : size, color);
    }

    /** Column heading: small spaced capitals over a thin rule. */
    private void heading(Canvas c, float x, float y, float cw, String title) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(2.9f * u);
        text.setColor(0xFF7FB6DA);
        text.setLetterSpacing(0.15f);
        c.drawText(title, x + 1.5f * u, y, text);
        text.setLetterSpacing(0f);
        stroke.setColor(0x447FB6DA);
        stroke.setStrokeWidth(0.25f * u);
        c.drawLine(x, y + 1.2f * u, x + cw, y + 1.2f * u, stroke);
    }

    /** Track name short enough for the menu's value box. */
    private static String shortTrack(Track t) {
        if (t.offshore) return t.name.replace(" Course", "");
        return t.impba ? t.name.replace("IMPBA ", "").replace(" Mile", " mi").replace(" Electric", " mi elec.")
                .replace(" mi mi", " mi") : t.name;
    }

    /**
     * One setting as a card: the name on the left, and its value in a dark box between
     * the arrows on the right, so each value sits clearly with its own label.
     */
    private void row(Canvas c, float x, float y, float cw, float rh, String label, String value,
                     Action dec, Action inc) {
        float ch = rh - 1.2f * u;               // card height; the rest is the gap between cards
        rect.set(x, y, x + cw, y + ch);
        fill.setColor(0x26FFFFFF);
        c.drawRoundRect(rect, 2 * u, 2 * u, fill);
        float mid = y + ch / 2f;

        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(3.4f * u);
        text.setColor(0xFFDDE8F0);
        Paint.FontMetrics fm = text.getFontMetrics();
        float base = mid - (fm.ascent + fm.descent) / 2f;
        c.drawText(label, x + 2.5f * u, base, text);

        float bs = ch - 1.2f * u;              // arrow button size
        float valueW = Math.min(30 * u, cw * 0.46f);
        float incX = x + cw - 0.8f * u - bs;
        float valX = incX - 0.6f * u - valueW;
        float decX = valX - 0.6f * u - bs;
        float by = mid - bs / 2f;
        rect.set(valX, by, valX + valueW, by + bs);
        fill.setColor(0x66061420);
        c.drawRoundRect(rect, 1.5f * u, 1.5f * u, fill);
        button(c, decX, by, bs, bs, "\u2039", 0xFF35506A, 4.6f * u, dec);
        button(c, incX, by, bs, bs, "\u203A", 0xFF35506A, 4.6f * u, inc);
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(3.4f * u);
        float vw = text.measureText(value);
        if (vw > valueW - 1.5f * u) text.setTextSize(3.4f * u * (valueW - 1.5f * u) / vw);
        text.setColor(Color.WHITE);
        c.drawText(value, valX + valueW / 2f, base, text);
        // tapping the value itself also steps forward
        buttons.add(new Btn(new RectF(valX, by, valX + valueW, by + bs), inc));
    }

    private void drawRace(Canvas c, int w, int h) {
        Boat p = game.player;
        if (p == null) return;
        int n = game.boats.length;

        // ---- info box ----
        boolean pen = p.penaltyLaps > 0 || p.penaltyTime > 0;
        panel(c, 2 * u, 2 * u, 36 * u, pen ? 30 * u : 24 * u);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.WHITE);
        text.setTextSize(9 * u);
        c.drawText(Game.ordinal(game.positionOf(p)), 5 * u, 11.5f * u, text);
        text.setTextSize(4.5f * u);
        text.setColor(0xFFB8D4E8);
        c.drawText("/ " + n, 22 * u, 11.5f * u, text);
        text.setColor(Color.WHITE);
        text.setTextSize(4.6f * u);
        String lapTxt = game.timed() ? "LAP " + game.currentLap(p) : "LAP " + game.currentLap(p) + "/" + game.laps;
        if (game.event == Game.EVENT_SPEED && p.lastSpeedMph > 0)
            lapTxt = String.format(java.util.Locale.US, "TRAP %.1f", p.lastSpeedMph);
        c.drawText(lapTxt, 5 * u, 18 * u, text);
        text.setTextSize(4.2f * u);
        text.setColor(0xFFFFE082);
        float shown = p.finished ? p.finishTime : game.raceTime;
        if (game.timed() && !p.finished && game.state == Game.State.RACING)
            shown = Math.max(0f, Game.RACE_SECONDS - game.raceTime);
        c.drawText(Game.formatTime(shown) + (game.timed() && !p.finished ? " LEFT" : ""), 5 * u, 23.5f * u, text);
        if (pen) {
            text.setColor(0xFFFF8A65);
            text.setTextSize(3.6f * u);
            c.drawText(p.penaltyLaps > 0 ? "PENALTY +" + p.penaltyLaps + " LAP" + (p.penaltyLaps > 1 ? "S" : "")
                    : "PENALTY +" + Math.round(p.penaltyTime) + " SEC", 5 * u, 29.5f * u, text);
        }

        // ---- minimap ----
        drawMinimap(c, w - 2 * u - 40 * u, 2 * u, 40 * u, 28 * u);
        // ---- noise meter reading (gas boats passing the shore meter) ----
        if (p.lastNoiseDb > 0) {
            boolean over = p.lastNoiseDb > Game.NOISE_LIMIT;
            textShadow(c, String.format(java.util.Locale.US, "NOISE %.0f dB", p.lastNoiseDb),
                    w - 22 * u, 34 * u, 3.4f * u, over ? 0xFFFF5252 : 0xFFB8D4E8);
        }

        // ---- top buttons ----
        float bw = 15 * u, bh = 9 * u, gap = 2 * u;
        float bx = w / 2f - (bw * 3 + gap * 2) / 2f;
        button(c, bx, 2 * u, bw, bh, game.lifeSize && game.view == Game.VIEW_FPV ? "COCKPIT"
                : Game.VIEW_SHORT[game.view], 0xCC24435E, 3.6f * u,
                game::toggleView);
        button(c, bx + bw + gap, 2 * u, bw, bh, "PAUSE", 0xCC24435E, 3.6f * u, () -> game.setPaused(true));
        final Game g = game;
        button(c, bx + 2 * (bw + gap), 2 * u, bw, bh, g.soundOn ? "SOUND" : "MUTED", 0xCC24435E, 3.6f * u,
                () -> g.soundOn = !g.soundOn);

        // ---- center messages ----
        if (game.state == Game.State.COUNTDOWN && game.clockStart) {
            drawClockHud(c, w, h, p);
        } else if (game.state == Game.State.COUNTDOWN) {
            int num = Math.min(3, (int) Math.ceil(game.countdown));
            textShadow(c, String.valueOf(num), w / 2f, h * 0.45f, 26 * u, 0xFFFFD54F);
            textShadow(c, "GET READY", w / 2f, h * 0.45f + 10 * u, 5 * u, Color.WHITE);
        }
        if (game.state != Game.State.COUNTDOWN && game.messageTime > 0 && game.message.length() > 0) {
            textShadow(c, game.message, w / 2f, h * 0.36f, 10 * u, Color.WHITE);
        }
        if (game.state == Game.State.RACING && !p.finished) {
            if (p.wrongWayTime > 1f) {
                textShadow(c, "WRONG WAY!", w / 2f, h * 0.5f, 8 * u, 0xFFFF5252);
            } else if (p.offCourseTime > 1f) {
                int left = (int) Math.ceil(6f - p.offCourseTime);
                textShadow(c, "OFF COURSE - returning in " + left, w / 2f, h * 0.5f, 5.5f * u, 0xFFFFAB40);
            }
        }

        // ---- speedometer ----
        float mph = p.speed() * 2.23694f;   // m/s -> mph
        textShadow(c, String.valueOf(Math.round(mph)), w / 2f, h - 8 * u, 10 * u, Color.WHITE);
        textShadow(c, "MPH", w / 2f, h - 3 * u, 3.6f * u, 0xFFB8D4E8);

        // ---- joysticks ----
        boolean sticks = (game.state == Game.State.RACING || game.state == Game.State.COUNTDOWN)
                && !game.paused && !p.finished;
        layoutSticks(w, h);
        if (sticks) {
            drawSticks(c);
        } else {
            releaseSticks();
        }

        // ---- overlays ----
        if (game.paused) {
            drawPause(c, w, h);
        } else if (game.state == Game.State.FINISHED && game.finishedFor > 2.5f) {
            drawResults(c, w, h);
        }
    }

    /** Clock start: the start clock, distance to the line and the jump-start warning. */
    private void drawClockHud(Canvas c, int w, int h, Boat p) {
        int secs = (int) Math.ceil(game.countdown);
        String t = secs >= 60 ? (secs / 60) + ":" + String.format(java.util.Locale.US, "%02d", secs % 60)
                : String.valueOf(secs);
        boolean last = game.countdown <= Game.JUMP_WINDOW;
        float cy = 24 * u;
        panel(c, w / 2f - 17 * u, 13 * u, 34 * u, 17 * u);
        textC(c, t, w / 2f, cy + 3 * u, 11 * u, last ? 0xFFFF5252 : 0xFFFFB74D);
        textC(c, "START CLOCK", w / 2f, 17 * u, 2.8f * u, 0xFFB8D4E8);
        Track tr = game.track;
        float pos = tr.cum[p.trackIdx] + Boat.clamp(tr.along(p.trackIdx, p.x, p.z), -tr.ds, tr.ds);
        float d = tr.length - pos;
        if (d < 0) d += tr.length;
        float ft = d / 0.3048f;
        String hint;
        int col = Color.WHITE;
        if (last) { hint = "DON'T CROSS THE LINE BEFORE ZERO"; col = 0xFFFF5252; }
        else hint = "Start line in " + Math.round(ft) + " ft  \u2022  hit it at zero";
        if (game.countdown <= Game.NO_LAUNCH && !last)
            textC(c, "NO-LAUNCH PERIOD", w / 2f, 32 * u, 2.8f * u, 0xFFFFB74D);
        textShadow(c, hint, w / 2f, 36 * u, 4 * u, col);
        if (game.messageTime > 0 && game.message.length() > 0)
            textShadow(c, game.message, w / 2f, h * 0.45f, 8 * u, Color.WHITE);
    }

    private void layoutSticks(int w, int h) {
        float r = 16 * u;
        float inset = Math.max(w * 0.11f, r + 8 * u);
        left.radius = r;
        right.radius = r;
        left.cx = inset;
        left.cy = h - r - 9 * u;
        right.cx = w - inset;
        right.cy = h - r - 9 * u;
        if (game.controls == Game.CTRL_TWO) {
            left.useX = false; left.useY = true;     // throttle
            right.useX = true; right.useY = false;   // steering
        } else if (game.controls == Game.CTRL_TILT) {
            left.useX = false; left.useY = true;     // throttle; steering by tilting the phone
        } else {
            left.useX = true; left.useY = true;
        }
    }

    private void drawSticks(Canvas c) {
        if (game.controls == Game.CTRL_TWO) {
            drawStick(c, left, "THROTTLE");
            drawStick(c, right, "STEER");
        } else if (game.controls == Game.CTRL_TILT) {
            drawStick(c, left, "THROTTLE");
            // steering comes from the phone's tilt every frame
            game.inSteer = shape(game.tiltSteer);
            drawTilt(c);
        } else {
            drawStick(c, left, "DRIVE");
        }
    }

    /** Tilt steering: a small steering wheel that turns with the phone. */
    private void drawTilt(Canvas c) {
        float cx = right.cx, cy = right.cy + 2 * u, r = 9 * u;
        float a = game.inSteer * 90f;
        stroke.setColor(0xAAFFFFFF);
        stroke.setStrokeWidth(1.4f * u);
        c.drawCircle(cx, cy, r, stroke);
        c.save();
        c.rotate(a, cx, cy);
        stroke.setStrokeWidth(1.1f * u);
        c.drawLine(cx - r, cy, cx + r, cy, stroke);
        c.drawLine(cx, cy, cx, cy + r, stroke);
        fill.setColor(0xFFFF7043);
        c.drawCircle(cx, cy - r, 1.4f * u, fill);
        c.restore();
        textC(c, "TILT TO STEER", cx, cy - r - 4 * u, 3.2f * u, 0xCCFFFFFF);
    }

    private void drawStick(Canvas c, Stick s, String label) {
        float r = s.radius;
        fill.setColor(0x40FFFFFF);
        stroke.setColor(0xAAFFFFFF);
        stroke.setStrokeWidth(0.5f * u);
        if (s.useX && s.useY) {
            c.drawCircle(s.cx, s.cy, r, fill);
            c.drawCircle(s.cx, s.cy, r, stroke);
        } else if (s.useY) {
            rect.set(s.cx - 6 * u, s.cy - r - 5 * u, s.cx + 6 * u, s.cy + r + 5 * u);
            c.drawRoundRect(rect, 6 * u, 6 * u, fill);
            c.drawRoundRect(rect, 6 * u, 6 * u, stroke);
            stroke.setStrokeWidth(0.3f * u);
            c.drawLine(s.cx - 4 * u, s.cy, s.cx + 4 * u, s.cy, stroke);
            textC(c, "FWD", s.cx + 11 * u, s.cy - r * 0.6f, 3 * u, 0xAAFFFFFF);
            textC(c, "REV", s.cx + 11 * u, s.cy + r * 0.75f, 3 * u, 0xAAFFFFFF);
        } else {
            rect.set(s.cx - r - 5 * u, s.cy - 6 * u, s.cx + r + 5 * u, s.cy + 6 * u);
            c.drawRoundRect(rect, 6 * u, 6 * u, fill);
            c.drawRoundRect(rect, 6 * u, 6 * u, stroke);
            stroke.setStrokeWidth(0.3f * u);
            c.drawLine(s.cx, s.cy - 4 * u, s.cx, s.cy + 4 * u, stroke);
        }
        float kx = s.cx + s.kx * r, ky = s.cy + s.ky * r;
        fill.setColor(s.pointer >= 0 ? 0xEEFFFFFF : 0xBBFFFFFF);
        c.drawCircle(kx, ky, 6.5f * u, fill);
        fill.setColor(0xFF2B5D86);
        c.drawCircle(kx, ky, 4.5f * u, fill);
        textC(c, label, s.cx, s.cy - r - 7.5f * u, 3.2f * u, 0xCCFFFFFF);
    }

    private void drawMinimap(Canvas c, float x, float y, float w, float h) {
        panel(c, x, y, w, h);
        Track t = game.track;
        float pad = 8f * t.scaleK;
        float minX = t.minX - pad, maxX = t.maxX + pad, minZ = t.minZ - pad, maxZ = t.maxZ + pad;
        float sc = Math.min((w - 4 * u) / (maxX - minX), (h - 4 * u) / (maxZ - minZ));
        float ox = x + w / 2f - (minX + maxX) / 2f * sc, oy = y + h / 2f - (minZ + maxZ) / 2f * sc;
        path.reset();
        for (int i = 0; i <= Track.N; i += 6) {
            int k = i % Track.N;
            float mx = ox + t.px[k] * sc, my = oy + t.pz[k] * sc;
            if (i == 0) path.moveTo(mx, my); else path.lineTo(mx, my);
        }
        path.close();
        stroke.setColor(0x88FFFFFF);
        stroke.setStrokeWidth(Math.max(1.5f * u, t.halfWidth * 2 * sc * (t.life ? 0.5f : 1f)));
        c.drawPath(path, stroke);
        // island
        if (game.islandVisible()) {
        fill.setColor(0xFF4E8F3A);
        rect.set(ox + (World.ISLAND_X - 16) * sc, oy + (World.ISLAND_Z - 8) * sc,
                ox + (World.ISLAND_X + 16) * sc, oy + (World.ISLAND_Z + 8) * sc);
        c.drawOval(rect, fill);
        }
        // start line
        stroke.setColor(Color.WHITE);
        stroke.setStrokeWidth(0.5f * u);
        float sx = ox + t.px[0] * sc, sy = oy + t.pz[0] * sc;
        float gw = Math.max(t.halfWidth * sc, 2f * u);
        float gx = t.nx(0) * gw, gz = t.nz(0) * gw;
        c.drawLine(sx - gx, sy - gz, sx + gx, sy + gz, stroke);
        // spectator
        if (game.view == Game.VIEW_SHORE) {
            fill.setColor(0xFFFFFFFF);
            float ex = ox + game.shoreEye[0] * sc, ey = Math.min(y + h - 1.5f * u, oy + game.shoreEye[2] * sc);
            c.drawRect(ex - 0.9f * u, ey - 0.9f * u, ex + 0.9f * u, ey + 0.9f * u, fill);
        }
        // boats (player last so it's on top)
        for (int i = game.boats.length - 1; i >= 0; i--) {
            Boat b = game.boats[i];
            float bx = ox + b.x * sc, by = oy + b.z * sc;
            fill.setColor(Color.rgb((int) (b.r * 255), (int) (b.g * 255), (int) (b.b * 255)));
            float rad = b.isPlayer ? 1.7f * u : 1.2f * u;
            c.drawCircle(bx, by, rad, fill);
            if (b.isPlayer) {
                stroke.setColor(Color.WHITE);
                stroke.setStrokeWidth(0.4f * u);
                c.drawCircle(bx, by, rad, stroke);
            }
        }
    }

    private void drawPause(Canvas c, int w, int h) {
        fill.setColor(0x88000000);
        c.drawRect(0, 0, w, h, fill);
        float pw = 64 * u, ph = 62 * u;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        textC(c, "PAUSED", w / 2f, py + 11 * u, 7 * u, Color.WHITE);
        float bw = 44 * u, bh = 10 * u;
        float bx = (w - bw) / 2f;
        button(c, bx, py + 17 * u, bw, bh, "RESUME", 0xFF2E9E4F, 4.5f * u, () -> game.setPaused(false));
        button(c, bx, py + 30 * u, bw, bh, "RESTART", 0xFF35506A, 4.5f * u, game::startRace);
        button(c, bx, py + 43 * u, bw, bh, "MAIN MENU", 0xFF35506A, 4.5f * u, game::startDemo);
    }

    private void drawResults(Canvas c, int w, int h) {
        fill.setColor(0x66000000);
        c.drawRect(0, 0, w, h, fill);
        int n = game.order.length;
        float rowH = 7.5f * u;
        boolean note = game.resultNote.length() > 0;
        float pw = Math.min(w * 0.9f, 110 * u), ph = 34 * u + n * rowH + (note ? 6 * u : 0);
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        String title = game.event == Game.EVENT_SPEED ? "SPEED RUN" : game.event == Game.EVENT_TRIAL
                ? "TIME TRIAL" : "HEAT " + game.heat + " RESULTS";
        textC(c, title, w / 2f, py + 10 * u, 6.5f * u, Color.WHITE);
        float y = py + 15 * u;
        for (int i = 0; i < n; i++) {
            Boat b = game.order[i];
            if (b.isPlayer) {
                fill.setColor(0x33FFFFFF);
                c.drawRect(px + 3 * u, y, px + pw - 3 * u, y + rowH, fill);
            }
            float ty = y + rowH * 0.7f;
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(4.4f * u);
            text.setColor(Color.WHITE);
            c.drawText(Game.ordinal(i + 1), px + 6 * u, ty, text);
            fill.setColor(Color.rgb((int) (b.r * 255), (int) (b.g * 255), (int) (b.b * 255)));
            c.drawCircle(px + 23 * u, y + rowH / 2f, 2 * u, fill);
            c.drawText(b.name, px + 28 * u, ty, text);
            if (b.infractions > 0 && game.rules != Game.RULES_FUN) {
                text.setTextSize(3.2f * u);
                text.setColor(0xFFFF8A65);
                String pen = game.rules == Game.RULES_LAP ? "+" + b.penaltyLaps + " lap" : "+" + Math.round(b.penaltyTime) + "s";
                c.drawText(pen, px + 62 * u, ty, text);
                text.setTextSize(4.4f * u);
                text.setColor(Color.WHITE);
            }
            text.setTextAlign(Paint.Align.RIGHT);
            String tm;
            if (b.disqualified) tm = "DQ (" + b.dqReason + ")";
            else if (game.event == Game.EVENT_SPEED)
                tm = String.format(java.util.Locale.US, "%.1f mph", b.bestSpeedMph);
            else if (b.finished && game.timed())
                tm = Math.round(b.finishTarget / game.track.length) + " laps  " + Game.formatTime(game.totalTime(b));
            else if (b.finished) tm = Game.formatTime(game.totalTime(b));
            else tm = "Lap " + game.currentLap(b) + "...";
            text.setColor(b.finished ? 0xFFFFE082 : 0xFF9FB4C4);
            c.drawText(tm, px + pw - 6 * u, ty, text);
            y += rowH;
        }
        if (note) textFit(c, game.resultNote, w / 2f, y + 4.5f * u, 3.4f * u, pw - 6 * u, 0xFFFFE082);
        float bw = 40 * u, bh = 10 * u;
        float by = py + ph - bh - 4 * u;
        button(c, w / 2f - bw - 2 * u, by, bw, bh, "RACE AGAIN", 0xFF2E9E4F, 4.2f * u, game::startRace);
        button(c, w / 2f + 2 * u, by, bw, bh, "MAIN MENU", 0xFF35506A, 4.2f * u, game::startDemo);
    }

    // ------------------------------------------------------------------ announcer voice

    private void playVoice(String key) {
        stopVoice();
        try {
            AssetFileDescriptor afd = getContext().getAssets().openFd(Announcer.assetPath(key));
            MediaPlayer mp = new MediaPlayer();
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setOnCompletionListener(m -> {
                if (voice == m) {
                    voice = null;
                    game.talking = false;
                }
                m.release();
            });
            mp.prepare();
            mp.setVolume(1f, 1f);
            mp.start();
            voice = mp;
            game.talking = true;
        } catch (Exception e) {
            game.talking = false;
        }
    }

    /** Stops any announcer clip that is playing. */
    void stopVoice() {
        MediaPlayer mp = voice;
        voice = null;
        if (mp != null) {
            try { mp.stop(); } catch (Exception ignored) { }
            mp.release();
        }
        game.talking = false;
    }

    // ------------------------------------------------------------------ primitives

    private void panel(Canvas c, float x, float y, float w, float h) {
        rect.set(x, y, x + w, y + h);
        fill.setColor(0xC0102230);
        c.drawRoundRect(rect, 3 * u, 3 * u, fill);
        stroke.setColor(0x55FFFFFF);
        stroke.setStrokeWidth(0.3f * u);
        c.drawRoundRect(rect, 3 * u, 3 * u, stroke);
    }

    private void button(Canvas c, float x, float y, float w, float h, String label, int color,
                        float size, Action a) {
        RectF r = new RectF(x, y, x + w, y + h);
        fill.setColor(color);
        c.drawRoundRect(r, 2.2f * u, 2.2f * u, fill);
        stroke.setColor(0x66FFFFFF);
        stroke.setStrokeWidth(0.3f * u);
        c.drawRoundRect(r, 2.2f * u, 2.2f * u, stroke);
        text.setTextSize(size);
        text.setTextAlign(Paint.Align.CENTER);
        text.setColor(Color.WHITE);
        Paint.FontMetrics fm = text.getFontMetrics();
        c.drawText(label, x + w / 2f, y + h / 2f - (fm.ascent + fm.descent) / 2f, text);
        buttons.add(new Btn(r, a));
    }

    private void textC(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    private void textShadow(Canvas c, String s, float x, float y, float size, int color) {
        text.setTextAlign(Paint.Align.CENTER);
        text.setTextSize(size);
        text.setColor(0x99000000);
        c.drawText(s, x + size * 0.05f, y + size * 0.05f, text);
        text.setColor(color);
        c.drawText(s, x, y, text);
    }

    // ------------------------------------------------------------------ input

    private void releaseSticks() {
        left.release();
        right.release();
        game.inThrottle = 0;
        game.inSteer = 0;
        firePointer = -1;
        game.inFire = false;
    }

    // ------------------------------------------------------------------ combat menus

    private void drawCombatMenu(Canvas c, int w, int h) {
        releaseSticks();
        if (game.menuScreen == Game.MENU_MAIN) { drawCombatMain(c, w, h); return; }
        drawCombatSetup(c, w, h);
    }

    /** The first screen of the combat app: RC boats or life-size hydroplanes. */
    private void drawCombatMain(Canvas c, int w, int h) {
        float pw = Math.min(w * 0.9f, 170 * u), ph = 80 * u;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        textC(c, "RC BOAT COMBAT", w / 2f, py + 12 * u, 8 * u, Color.WHITE);
        textC(c, "Arm your boat. Last one afloat wins.", w / 2f, py + 19 * u, 3.6f * u, 0xFFFFB74D);
        float cw = (pw - 15 * u) / 2f, ch = 48 * u, cy = py + 25 * u;
        final Game g = game;
        modeCard(c, px + 5 * u, cy, cw, ch, "RC BOATS", "1/8 scale battles on the lake",
                "Guns, torpedoes, drills, mines and more", 0xFF2E7D9E,
                () -> { g.setMode(false); g.combat.arena = 0; g.startDemo(); g.menuScreen = Game.MENU_RC; });
        modeCard(c, px + 10 * u + cw, cy, cw, ch, "LIFE SIZE", "Unlimited hydroplanes at war",
                "A ring of buoys on the big lake", 0xFFB5482A,
                () -> { g.setMode(true); g.combat.arena = 0; g.startDemo(); g.menuScreen = Game.MENU_LIFE; });
    }

    /** Battle settings: mode, arena, rounds, length; weapon, boat and options. */
    private void drawCombatSetup(Canvas c, int w, int h) {
        float pw = Math.min(w * 0.94f, 190 * u), ph = 94 * u;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        final Game g = game;
        final Combat cb = g.combat;
        textC(c, g.lifeSize ? "LIFE SIZE COMBAT" : "RC BOAT COMBAT", w / 2f, py + 9 * u, 6.5f * u, Color.WHITE);
        backButton(c, px, py);
        boolean surv = cb.mode == Combat.MODE_SURVIVAL;
        String l1 = Weapons.NAMES[cb.weapon] + ": " + Weapons.INFO[cb.weapon];
        String l2;
        if (surv) {
            Float best = g.records.get(cb.survivalKey());
            l2 = "Wave after wave of tougher boats" + (best != null ? "  \u2022  Your best: " + Math.round(best) + " waves" : "");
        } else {
            int r = Combat.ROUNDS[cb.roundsIdx];
            l2 = "Last boat afloat wins the round" + (r > 1 ? "  \u2022  First to " + cb.winsNeeded() + " wins the match" : "");
        }
        textFit(c, l1, w / 2f, py + 15.5f * u, 3.2f * u, pw - 8 * u, 0xFFFFCC80);
        textFit(c, l2, w / 2f, py + 19.5f * u, 3.2f * u, pw - 8 * u, 0xFF9FD8FF);

        float gap = 5 * u;
        float colW = (pw - 6 * u - gap) / 2f;
        float x0 = px + 3 * u, x1 = x0 + colW + gap;
        float rowH = 7.6f * u;
        float y0 = py + 27 * u;
        heading(c, x0, py + 24.2f * u, colW, "BATTLE");
        heading(c, x1, py + 24.2f * u, colW, "BOAT & WEAPON");
        String[] arenas = cb.arenas();
        int nA = arenas.length, nW = Weapons.COUNT, nH = Boat.RC_HULLS;
        row(c, x0, y0, colW, rowH, "Mode", Combat.MODE_NAMES[cb.mode],
                () -> cb.mode = 1 - cb.mode, () -> cb.mode = 1 - cb.mode);
        row(c, x0, y0 + rowH, colW, rowH, "Arena", arenas[Math.min(cb.arena, nA - 1)],
                () -> { cb.arena = (cb.arena + nA - 1) % nA; g.startDemo(); },
                () -> { cb.arena = (cb.arena + 1) % nA; g.startDemo(); });
        row(c, x0, y0 + 2 * rowH, colW, rowH, "Opponents", surv ? "Waves" : String.valueOf(g.opponents),
                () -> { if (!surv) g.opponents = Math.max(1, g.opponents - 1); },
                () -> { if (!surv) g.opponents = Math.min(Game.MAX_OPPONENTS, g.opponents + 1); });
        int rr = Combat.ROUNDS[cb.roundsIdx];
        row(c, x0, y0 + 3 * rowH, colW, rowH, "Rounds", surv ? "-" : rr == 1 ? "1 round" : "Best of " + rr,
                () -> { if (!surv) cb.roundsIdx = (cb.roundsIdx + 2) % 3; },
                () -> { if (!surv) cb.roundsIdx = (cb.roundsIdx + 1) % 3; });
        row(c, x0, y0 + 4 * rowH, colW, rowH, "Battle length", Combat.LENGTH_NAMES[cb.length],
                () -> cb.length = Math.max(0, cb.length - 1), () -> cb.length = Math.min(2, cb.length + 1));
        row(c, x0, y0 + 5 * rowH, colW, rowH, "Difficulty", Game.DIFFICULTY[g.difficulty],
                () -> g.difficulty = Math.max(0, g.difficulty - 1),
                () -> g.difficulty = Math.min(2, g.difficulty + 1));
        row(c, x0, y0 + 6 * rowH, colW, rowH, "Crates", cb.pickupsOn ? "On" : "Off",
                () -> cb.pickupsOn = !cb.pickupsOn, () -> cb.pickupsOn = !cb.pickupsOn);

        row(c, x1, y0, colW, rowH, "Weapon", Weapons.NAMES[cb.weapon],
                () -> cb.weapon = (cb.weapon + nW - 1) % nW, () -> cb.weapon = (cb.weapon + 1) % nW);
        if (g.lifeSize) row(c, x1, y0 + rowH, colW, rowH, "Boat", "Unlimited", () -> { }, () -> { });
        else row(c, x1, y0 + rowH, colW, rowH, "Boat", Boat.HULL_NAMES[g.hull],
                () -> g.hull = (g.hull + nH - 1) % nH, () -> g.hull = (g.hull + 1) % nH);
        row(c, x1, y0 + 2 * rowH, colW, rowH, "Auto fire", cb.autoFire ? "On" : "Off",
                () -> cb.autoFire = !cb.autoFire, () -> cb.autoFire = !cb.autoFire);
        String camName = g.view == Game.VIEW_FPV ? (g.lifeSize ? "Cockpit" : "Boat (FPV)")
                : g.view == Game.VIEW_CHASE ? "Chase" : "Shore";
        row(c, x1, y0 + 3 * rowH, colW, rowH, "Camera", camName,
                () -> g.view = cycleView(g, -1), () -> g.view = cycleView(g, 1));
        row(c, x1, y0 + 4 * rowH, colW, rowH, "Controls", Game.CONTROL_NAMES[g.controls],
                () -> g.controls = g.nextControls(-1), () -> g.controls = g.nextControls(1));
        String snd = !g.soundOn ? "Off" : g.announcerOn ? "On" : "No voice";
        row(c, x1, y0 + 5 * rowH, colW, rowH, "Sound", snd, () -> cycleSound(g, -1), () -> cycleSound(g, 1));

        float bw = 56 * u, bh = 10 * u;
        button(c, (w - bw) / 2f, py + ph - bh - 2.5f * u, bw, bh, "START BATTLE", 0xFFC0392B, 5.2f * u,
                game::startRace);
    }

    /** Combat camera choices: chase and the boat's own view (the shore view on the RC lake). */
    private static int cycleView(Game g, int dir) {
        int[] order = g.lifeSize ? new int[]{Game.VIEW_CHASE, Game.VIEW_FPV}
                : new int[]{Game.VIEW_CHASE, Game.VIEW_FPV, Game.VIEW_SHORE};
        int i = 0;
        for (int k = 0; k < order.length; k++) if (order[k] == g.view) i = k;
        return order[(i + dir + order.length) % order.length];
    }

    // ------------------------------------------------------------------ combat HUD

    private void drawCombat(Canvas c, int w, int h) {
        Boat p = game.player;
        if (p == null) return;
        Combat cb = game.combat;
        Boat[] boats = game.boats;

        nameTags(c, w, h, boats, p);
        statusPanel(c, p);

        // ---- top buttons ----
        float bw = 15 * u, bh = 9 * u, gap = 2 * u;
        float bx = w / 2f - (bw * 3 + gap * 2) / 2f;
        String vn = game.view == Game.VIEW_FPV ? (game.lifeSize ? "COCKPIT" : "BOAT")
                : Game.VIEW_SHORT[game.view];
        button(c, bx, 2 * u, bw, bh, vn, 0xCC24435E, 3.6f * u, this::combatView);
        button(c, bx + bw + gap, 2 * u, bw, bh, "PAUSE", 0xCC24435E, 3.6f * u, () -> game.setPaused(true));
        final Game g = game;
        button(c, bx + 2 * (bw + gap), 2 * u, bw, bh, g.soundOn ? "SOUND" : "MUTED", 0xCC24435E, 3.6f * u,
                () -> g.soundOn = !g.soundOn);
        String info;
        int left = cb.aliveCount();
        if (cb.mode == Combat.MODE_SURVIVAL) {
            info = "WAVE " + cb.wave + "  \u2022  " + Math.max(0, left - (p.out ? 0 : 1)) + " ENEMIES LEFT  \u2022  "
                    + p.kills + " KILLS";
        } else {
            int r = Combat.ROUNDS[cb.roundsIdx];
            info = (r > 1 ? "ROUND " + cb.round + "  \u2022  FIRST TO " + cb.winsNeeded() + "  \u2022  " : "")
                    + left + " BOATS AFLOAT";
        }
        textShadow(c, info, w / 2f, 15.5f * u, 3.4f * u, 0xFFFFE0B2);
        if (cb.suddenDeath && !cb.roundOver) textShadow(c, "SUDDEN DEATH", w / 2f, 20f * u, 3.4f * u, 0xFFFF5252);

        radar(c, w - 2 * u - 34 * u, 2 * u, 34 * u, boats, p);
        // ---- kill feed ----
        float fy = 41 * u;
        for (int i = 0; i < cb.feed.size(); i++) {
            float a = Math.min(1f, cb.feedT.get(i) / 1f);
            int col = ((int) (a * 255) << 24) | 0xFFFFFF;
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(3.1f * u);
            text.setColor((int) (a * 0x99) << 24);
            c.drawText(cb.feed.get(i), w - 2.6f * u, fy + 0.15f * u, text);
            text.setColor(col);
            c.drawText(cb.feed.get(i), w - 2.8f * u, fy, text);
            fy += 4.2f * u;
        }

        // ---- centre messages ----
        if (game.state == Game.State.COUNTDOWN) {
            int num = Math.min(3, (int) Math.ceil(game.countdown));
            textShadow(c, String.valueOf(Math.max(1, num)), w / 2f, h * 0.45f, 26 * u, 0xFFFFD54F);
            textShadow(c, cb.mode == Combat.MODE_SURVIVAL ? "SURVIVE!" : "ROUND " + cb.round,
                    w / 2f, h * 0.45f + 10 * u, 5 * u, Color.WHITE);
        } else if (game.messageTime > 0 && game.message.length() > 0) {
            textShadow(c, game.message, w / 2f, h * 0.34f, 8 * u, Color.WHITE);
        }

        // ---- speed ----
        if (!p.out) {
            float mph = p.speed() * 2.23694f;
            textShadow(c, String.valueOf(Math.round(mph)), w / 2f, h - 8 * u, 9 * u, Color.WHITE);
            textShadow(c, "MPH", w / 2f, h - 3 * u, 3.4f * u, 0xFFB8D4E8);
        }

        // ---- controls ----
        boolean active = (game.state == Game.State.RACING || game.state == Game.State.COUNTDOWN)
                && !game.paused && !p.out && !cb.roundOver;
        layoutSticks(w, h);
        layoutCombatButtons(w, h);
        combatControls = active;
        if (active) {
            drawSticks(c);
            drawCombatButtons(c, p);
        } else {
            releaseSticks();
            weaponMenu = false;
        }

        // ---- overlays ----
        if (game.paused) {
            drawPause(c, w, h);
        } else if (cb.roundOver && cb.roundOverT > 2.2f) {
            drawRoundResults(c, w, h);
        } else if (p.out && !cb.roundOver) {
            Boat sp = game.viewBoat();
            panel(c, w / 2f - 42 * u, h - 30 * u, 84 * u, 16 * u);
            textC(c, "YOU'RE OUT  \u2022  " + Game.ordinal(p.place) + " PLACE", w / 2f, h - 24 * u, 3.8f * u, 0xFFFF8A65);
            textC(c, sp != null && sp != p ? "Watching " + sp.name : "", w / 2f, h - 19 * u, 3.2f * u, 0xFFB8D4E8);
            if (cb.mode == Combat.MODE_LAST)
                button(c, w / 2f + 44 * u, h - 30 * u, 22 * u, 16 * u, "SKIP \u203A", 0xFF35506A, 4f * u, cb::skipRound);
        }
    }

    private void combatView() {
        game.view = cycleView(game, 1);
        game.setMessage((game.view == Game.VIEW_FPV ? (game.lifeSize ? "COCKPIT" : "BOAT")
                : Game.VIEW_SHORT[game.view]) + " VIEW", 1.2f);
    }

    /** Hull, water, weapon and specials for the player's boat. */
    private void statusPanel(Canvas c, Boat p) {
        float x = 2 * u, y = 2 * u, pw = 46 * u, ph = 34 * u;
        panel(c, x, y, pw, ph);
        float bx = x + 3 * u, bw = pw - 6 * u, bhh = 3.2f * u;
        // hull
        float hp = Math.max(0f, p.hp / p.maxHp);
        label(c, "HULL", bx, y + 5 * u);
        bar(c, bx, y + 6.2f * u, bw, bhh, hp, hp > 0.5f ? 0xFF4CAF50 : hp > 0.25f ? 0xFFFFC107 : 0xFFF44336);
        // water in the hull
        String wl = p.holes > 0 ? "WATER  \u2022  " + p.holes + (p.holes == 1 ? " HOLE" : " HOLES") : "WATER";
        label(c, wl, bx, y + 13.4f * u);
        bar(c, bx, y + 14.6f * u, bw, bhh, p.water, p.water > 0.6f ? 0xFFE53935 : 0xFF29B6F6);
        // weapon
        float am = p.reload > 0 ? 1f - p.reload / Math.max(0.01f, Weapons.RELOAD[p.weapon])
                : p.ammo / Weapons.MAG[p.weapon];
        String wn = Weapons.SHORT[p.weapon] + (p.switchT > 0 ? "  \u2022  SWITCHING" : p.reload > 0 ? "  \u2022  RELOADING" : "");
        if (p.weapon == Weapons.DRILL) { am = 1f; wn = "DRILL  \u2022  RAM THEM"; }
        label(c, wn, bx, y + 21.8f * u);
        bar(c, bx, y + 23f * u, bw, bhh, am, p.reload > 0 ? 0xFF8D6E63 : 0xFFFFA726);
        // effects
        StringBuilder fx = new StringBuilder();
        if (p.shield > 0) fx.append("SHIELD ").append((int) Math.ceil(p.shield)).append("  ");
        if (p.nitro > 0) fx.append("NITRO  ");
        if (p.power > 0) fx.append("2X DMG ").append((int) Math.ceil(p.power)).append("  ");
        if (p.burn > 0) fx.append("ON FIRE  ");
        if (p.emp > 0) fx.append("STALLED  ");
        if (p.oiled > 0) fx.append("OIL  ");
        if (p.tether != null) fx.append("TOWING  ");
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(2.9f * u);
        text.setColor(0xFFFFE082);
        c.drawText(fx.toString(), bx, y + 31f * u, text);
    }

    private void label(Canvas c, String s, float x, float y) {
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(2.8f * u);
        text.setColor(0xFFB8D4E8);
        c.drawText(s, x, y, text);
    }

    private void bar(Canvas c, float x, float y, float bw, float bh, float f, int color) {
        rect.set(x, y, x + bw, y + bh);
        fill.setColor(0x66000000);
        c.drawRoundRect(rect, bh / 2, bh / 2, fill);
        f = Math.max(0f, Math.min(1f, f));
        if (f > 0.01f) {
            rect.set(x, y, x + bw * f, y + bh);
            fill.setColor(color);
            c.drawRoundRect(rect, bh / 2, bh / 2, fill);
        }
    }

    /** Names and hull bars over the other boats. */
    private void nameTags(Canvas c, int w, int h, Boat[] boats, Boat p) {
        float[] sxy = game.screenXY;
        for (int i = 0; i < boats.length && i * 3 + 2 < sxy.length; i++) {
            Boat b = boats[i];
            if (b.out || b == p || sxy[i * 3 + 2] <= 0) continue;
            float x = sxy[i * 3] * w, y = sxy[i * 3 + 1] * h;
            float depth = sxy[i * 3 + 2];
            float k = game.lifeSize ? 7.5f : 1f;
            float sc = Math.max(0.6f, Math.min(1.2f, 14f * k / Math.max(1f, depth)));
            float bw = 13 * u * sc, bh = 1.4f * u * sc;
            bar(c, x - bw / 2, y, bw, bh, b.hp / b.maxHp, 0xFFE53935);
            if (b.water > 0.05f) bar(c, x - bw / 2, y + bh * 1.4f, bw, bh * 0.7f, b.water, 0xFF29B6F6);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTextSize(2.6f * u * sc);
            text.setColor(0xCC000000);
            c.drawText(b.name, x + 0.15f * u, y - 0.85f * u, text);
            fill.setColor(Color.rgb((int) (b.r * 255), (int) (b.g * 255), (int) (b.b * 255)));
            text.setColor(Color.WHITE);
            c.drawText(b.name, x, y - 1f * u, text);
        }
    }

    /** A small radar: boats (team colours), crates and mines around the player. */
    private void radar(Canvas c, float x, float y, float size, Boat[] boats, Boat p) {
        float cx = x + size / 2f, cy = y + size / 2f, r = size / 2f;
        fill.setColor(0xB0102230);
        c.drawCircle(cx, cy, r, fill);
        stroke.setColor(0x55FFFFFF);
        stroke.setStrokeWidth(0.3f * u);
        c.drawCircle(cx, cy, r, stroke);
        c.drawCircle(cx, cy, r * 0.5f, stroke);
        Boat me = game.viewBoat() != null ? game.viewBoat() : p;
        float k = game.lifeSize ? 7.5f : 1f;
        float range = 45f * k;
        float hd = me.heading;
        float cs = (float) Math.cos(hd), sn = (float) Math.sin(hd);
        Combat cb = game.combat;
        for (Combat.Crate cr : cb.crates) {
            float[] q = radarPos(cr.x - me.x, cr.z - me.z, cs, sn, range, r);
            if (q == null) continue;
            float[] col = Weapons.PU_COLOR[cr.kind];
            fill.setColor(Color.rgb((int) (col[0] * 255), (int) (col[1] * 255), (int) (col[2] * 255)));
            c.drawRect(cx + q[0] - 0.8f * u, cy + q[1] - 0.8f * u, cx + q[0] + 0.8f * u, cy + q[1] + 0.8f * u, fill);
        }
        fill.setColor(0xFFFF3D00);
        for (Combat.Mine m : cb.mines) {
            float[] q = radarPos(m.x - me.x, m.z - me.z, cs, sn, range, r);
            if (q != null) c.drawCircle(cx + q[0], cy + q[1], 0.5f * u, fill);
        }
        for (Boat b : boats) {
            if (b.out || b == me) continue;
            float[] q = radarPos(b.x - me.x, b.z - me.z, cs, sn, range, r);
            if (q == null) {
                // off the edge: a mark on the rim
                float dx = b.x - me.x, dz = b.z - me.z;
                float rx = dx * cs - dz * sn, rz = dx * sn + dz * cs;
                float l = (float) Math.sqrt(rx * rx + rz * rz);
                q = new float[]{rx / l * r * 0.95f, rz / l * r * 0.95f};
            }
            fill.setColor(Color.rgb((int) (b.r * 255), (int) (b.g * 255), (int) (b.b * 255)));
            c.drawCircle(cx + q[0], cy + q[1], 1.3f * u, fill);
        }
        // me: an arrow pointing up
        path.reset();
        path.moveTo(cx, cy - 2f * u);
        path.lineTo(cx + 1.4f * u, cy + 1.5f * u);
        path.lineTo(cx - 1.4f * u, cy + 1.5f * u);
        path.close();
        fill.setColor(Color.WHITE);
        c.drawPath(path, fill);
    }

    private static float[] radarPos(float dx, float dz, float cs, float sn, float range, float r) {
        // rotate so the boat's heading points up the radar
        float rx = dx * cs - dz * sn, rz = dx * sn + dz * cs;
        float d = (float) Math.sqrt(rx * rx + rz * rz);
        if (d > range) return null;
        return new float[]{rx / range * r, rz / range * r};
    }

    private void layoutCombatButtons(int w, int h) {
        fireR = 9.5f * u;
        specR = 7f * u;
        if (game.controls != Game.CTRL_ONE) {
            fireX = w - 13 * u;
            fireY = right.cy - right.radius - 17 * u;
        } else {
            fireX = w - 16 * u;
            fireY = h - 20 * u;
        }
        specX = fireX - fireR - specR - 4 * u;
        specY = fireY + 4 * u;
        weapR = 7f * u;
        weapX = fireX;
        weapY = fireY + fireR + weapR + 3 * u;
        if (game.controls != Game.CTRL_ONE || weapY + weapR > getHeight() - u) {
            // beside the SPECIAL button when there's no room underneath
            weapX = specX - specR - weapR - 3 * u;
            weapY = specY;
        }
    }

    private void drawCombatButtons(Canvas c, Boat p) {
        boolean held = firePointer >= 0;
        fill.setColor(held ? 0xEEFF5252 : 0xAAD32F2F);
        c.drawCircle(fireX, fireY, fireR, fill);
        stroke.setColor(0xCCFFFFFF);
        stroke.setStrokeWidth(0.5f * u);
        c.drawCircle(fireX, fireY, fireR, stroke);
        textC(c, "FIRE", fireX, fireY + 1.3f * u, 3.6f * u, Color.WHITE);
        if (game.combat.autoFire) textC(c, "AUTO", fireX, fireY + 4.6f * u, 2.3f * u, 0xCCFFFFFF);
        boolean has = p.special >= 0;
        float[] col = has ? Weapons.PU_COLOR[p.special] : new float[]{0.3f, 0.35f, 0.4f};
        fill.setColor(Color.argb(has ? 220 : 110, (int) (col[0] * 255), (int) (col[1] * 255), (int) (col[2] * 255)));
        c.drawCircle(specX, specY, specR, fill);
        c.drawCircle(specX, specY, specR, stroke);
        textC(c, has ? Weapons.PU_SHORT[p.special] : "SPECIAL", specX, specY + 1f * u, has ? 2.9f * u : 2.4f * u,
                has ? Color.WHITE : 0x99FFFFFF);
        // WEAPON: opens the weapon picker
        fill.setColor(weaponMenu ? 0xEE546E7A : 0xAA37474F);
        c.drawCircle(weapX, weapY, weapR, fill);
        c.drawCircle(weapX, weapY, weapR, stroke);
        textC(c, Weapons.SHORT[p.weapon], weapX, weapY + 0.2f * u, 2.5f * u, Color.WHITE);
        textC(c, "WEAPON", weapX, weapY + 3.4f * u, 1.9f * u, 0xCCFFFFFF);
        if (weaponMenu) weaponPicker(c, p);
    }

    /** Eight weapon buttons (two rows) above the fire controls, each with its ammunition. */
    private void weaponPicker(Canvas c, Boat p) {
        float bw = 17 * u, bh = 9.5f * u, gap = 1.5f * u;
        float x0 = getWidth() - 2 * u - 4 * bw - 3 * gap;
        float y0 = Math.max(24 * u, Math.min(fireY, specY) - fireR - 2 * bh - gap - 6 * u);
        panel(c, x0 - 1.5f * u, y0 - 1.5f * u, 4 * bw + 3 * gap + 3 * u, 2 * bh + gap + 3 * u);
        for (int w = 0; w < Weapons.COUNT; w++) {
            float x = x0 + (w % 4) * (bw + gap), y = y0 + (w / 4) * (bh + gap);
            final int pick = w;
            boolean cur = w == p.weapon;
            button(c, x, y, bw, bh, "", cur ? 0xFFB71C1C : 0xFF35506A, 3f * u, () -> {
                game.inWeapon = pick;
                weaponMenu = false;
            });
            textC(c, Weapons.SHORT[w], x + bw / 2f, y + bh * 0.48f, 2.8f * u, Color.WHITE);
            float am = cur ? (p.reload > 0 ? 0f : p.ammo) : p.ammoOf[w];
            float f = w == Weapons.DRILL ? 1f : am / Weapons.MAG[w];
            bar(c, x + 1.5f * u, y + bh - 2.6f * u, bw - 3 * u, 1.2f * u, f, f > 0.99f ? 0xFF66BB6A : 0xFFFFA726);
        }
    }

    /** End of a round, a match or a survival run. */
    private void drawRoundResults(Canvas c, int w, int h) {
        Combat cb = game.combat;
        fill.setColor(0x66000000);
        c.drawRect(0, 0, w, h, fill);
        Boat[] bs = game.boats.clone();
        boolean surv = cb.mode == Combat.MODE_SURVIVAL;
        java.util.Arrays.sort(bs, (a, b) -> {
            if (!surv && b.wins != a.wins) return b.wins - a.wins;
            if (a.place != b.place) return (a.place == 0 ? 99 : a.place) - (b.place == 0 ? 99 : b.place);
            return b.kills - a.kills;
        });
        int n = surv ? 1 : bs.length;
        float rowH = 7.5f * u;
        float pw = Math.min(w * 0.9f, 120 * u), ph = 40 * u + n * rowH;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        String title;
        if (surv) title = "GAME OVER  \u2022  WAVE " + cb.wave;
        else if (cb.matchOver) title = cb.matchWinner != null ? (cb.matchWinner.isPlayer ? "YOU WIN THE MATCH!"
                : cb.matchWinner.name.toUpperCase() + " WINS THE MATCH") : "MATCH OVER";
        else title = "ROUND " + cb.round + "  \u2022  " + (cb.roundWinner == null ? "NO SURVIVORS"
                : cb.roundWinner.isPlayer ? "YOU WIN!" : cb.roundWinner.name.toUpperCase() + " WINS");
        textC(c, title, w / 2f, py + 10 * u, 6 * u, Color.WHITE);
        textFit(c, cb.resultLine, w / 2f, py + 16 * u, 3.4f * u, pw - 8 * u, 0xFFFFE082);
        float y = py + 20 * u;
        if (!surv) {
            text.setTextSize(2.8f * u);
            text.setColor(0xFF7FB6DA);
            text.setTextAlign(Paint.Align.LEFT);
            c.drawText("BOAT", px + 12 * u, y + 2 * u, text);
            c.drawText("WEAPON", px + 52 * u, y + 2 * u, text);
            text.setTextAlign(Paint.Align.RIGHT);
            c.drawText("KILLS", px + pw - 26 * u, y + 2 * u, text);
            c.drawText("WINS", px + pw - 6 * u, y + 2 * u, text);
            y += 3 * u;
        }
        for (int i = 0; i < n; i++) {
            Boat b = surv ? game.player : bs[i];
            if (b.isPlayer) {
                fill.setColor(0x33FFFFFF);
                c.drawRect(px + 3 * u, y, px + pw - 3 * u, y + rowH, fill);
            }
            float ty = y + rowH * 0.7f;
            fill.setColor(Color.rgb((int) (b.r * 255), (int) (b.g * 255), (int) (b.b * 255)));
            c.drawCircle(px + 8 * u, y + rowH / 2f, 2 * u, fill);
            text.setTextAlign(Paint.Align.LEFT);
            text.setTextSize(4.2f * u);
            text.setColor(b.out && !cb.roundOver ? 0xFF9FB4C4 : Color.WHITE);
            c.drawText(b.name, px + 12 * u, ty, text);
            text.setTextSize(3.4f * u);
            text.setColor(0xFFFFCC80);
            c.drawText(Weapons.NAMES[b.weapon], px + 52 * u, ty, text);
            text.setTextAlign(Paint.Align.RIGHT);
            text.setTextSize(4.2f * u);
            text.setColor(Color.WHITE);
            c.drawText(String.valueOf(b.kills), px + pw - 26 * u, ty, text);
            if (!surv) {
                StringBuilder st = new StringBuilder();
                for (int k = 0; k < b.wins; k++) st.append('\u2605');
                text.setColor(0xFFFFD54F);
                c.drawText(st.length() > 0 ? st.toString() : "-", px + pw - 6 * u, ty, text);
            }
            y += rowH;
        }
        float bw = 44 * u, bh = 10 * u;
        float by = py + ph - bh - 4 * u;
        final Game g = game;
        if (cb.matchOver) {
            button(c, w / 2f - bw - 2 * u, by, bw, bh, surv ? "TRY AGAIN" : "PLAY AGAIN", 0xFFC0392B, 4.2f * u, g::startRace);
        } else {
            button(c, w / 2f - bw - 2 * u, by, bw, bh, "NEXT ROUND", 0xFF2E9E4F, 4.2f * u, g::nextRound);
        }
        button(c, w / 2f + 2 * u, by, bw, bh, "MAIN MENU", 0xFF35506A, 4.2f * u, g::startDemo);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        int idx = e.getActionIndex();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_POINTER_DOWN: {
                float x = e.getX(idx), y = e.getY(idx);
                int id = e.getPointerId(idx);
                if (combatControls && sticksActive()) {
                    if (hit(x, y, fireX, fireY, fireR)) {
                        firePointer = id;
                        game.inFire = true;
                        break;
                    }
                    if (hit(x, y, specX, specY, specR)) {
                        game.inSpecial = true;
                        break;
                    }
                    if (hit(x, y, weapX, weapY, weapR)) {
                        weaponMenu = !weaponMenu;
                        invalidate();
                        break;
                    }
                }
                if (pressButton(x, y)) break;
                if (!sticksActive()) break;
                if (game.controls == Game.CTRL_TILT) {
                    if (x < getWidth() / 2f && left.pointer < 0) { left.pointer = id; left.move(x, y); }
                } else if (game.controls == Game.CTRL_TWO) {
                    if (x < getWidth() / 2f) {
                        if (left.pointer < 0) { left.pointer = id; left.move(x, y); }
                    } else if (right.pointer < 0) {
                        right.pointer = id;
                        right.move(x, y);
                    }
                } else if (left.pointer < 0) {
                    left.pointer = id;
                    left.move(x, y);
                }
                break;
            }
            case MotionEvent.ACTION_MOVE:
                for (int i = 0; i < e.getPointerCount(); i++) {
                    int id = e.getPointerId(i);
                    if (id == left.pointer) left.move(e.getX(i), e.getY(i));
                    if (id == right.pointer) right.move(e.getX(i), e.getY(i));
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_POINTER_UP: {
                int id = e.getPointerId(idx);
                if (id == firePointer) { firePointer = -1; game.inFire = false; }
                if (id == left.pointer) left.release();
                if (id == right.pointer) right.release();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                left.release();
                right.release();
                firePointer = -1;
                game.inFire = false;
                break;
        }
        pushInput();
        return true;
    }

    private boolean sticksActive() {
        synchronized (game) {
            return (game.state == Game.State.RACING || game.state == Game.State.COUNTDOWN)
                    && !game.paused && game.player != null && !game.player.finished
                    && !(Game.combatBuild && (game.player.out || game.combat.roundOver));
        }
    }

    private boolean hit(float x, float y, float cx, float cy, float r) {
        float dx = x - cx, dy = y - cy;
        return dx * dx + dy * dy < (r + 2 * u) * (r + 2 * u);
    }

    private boolean pressButton(float x, float y) {
        for (int i = buttons.size() - 1; i >= 0; i--) {
            Btn b = buttons.get(i);
            // slightly enlarged hit area for fingers
            if (x >= b.r.left - u && x <= b.r.right + u && y >= b.r.top - u && y <= b.r.bottom + u) {
                synchronized (game) {
                    b.a.run();
                }
                invalidate();
                return true;
            }
        }
        return false;
    }

    private static float shape(float v) {
        float dead = 0.08f;
        float a = Math.abs(v);
        if (a < dead) return 0f;
        float s = (a - dead) / (1f - dead);
        return Math.signum(v) * s;
    }

    private void pushInput() {
        if (game.controls == Game.CTRL_TWO) {
            game.inThrottle = shape(-left.ky);
            game.inSteer = shape(right.kx);
        } else if (game.controls == Game.CTRL_TILT) {
            game.inThrottle = shape(-left.ky);
            game.inSteer = shape(game.tiltSteer);
        } else {
            game.inThrottle = shape(-left.ky);
            game.inSteer = shape(left.kx);
        }
    }
}
