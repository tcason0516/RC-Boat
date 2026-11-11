package com.rcboat.racing;

import android.content.Context;
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

    HudView(Context ctx, Game game) {
        super(ctx);
        this.game = game;
        stroke.setStyle(Paint.Style.STROKE);
        text.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        u = Math.min(w, h) / 100f;
        buttons.clear();
        synchronized (game) {
            switch (game.state) {
                case MENU:
                    drawMenu(c, w, h);
                    break;
                default:
                    drawRace(c, w, h);
                    break;
            }
        }
        postInvalidateOnAnimation();
    }

    private void drawMenu(Canvas c, int w, int h) {
        releaseSticks();
        float pw = Math.min(w * 0.94f, 190 * u), ph = 90 * u;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);

        textC(c, "RC BOAT RACING", w / 2f, py + 10 * u, 8.5f * u, Color.WHITE);
        final Game g = game;
        textC(c, Track.NAMES[g.trackId] + "  \u2022  " + Boat.HULL_NAMES[g.hull] + ": "
                + Boat.HULL_INFO[g.hull], w / 2f, py + 16.5f * u, 3.8f * u, 0xFF9FD8FF);

        float colW = (pw - 6 * u) / 2f;
        float rowH = 13 * u;
        float x0 = px + 3 * u, x1 = px + 3 * u + colW;
        float y0 = py + 20 * u;

        row(c, x0, y0, colW, rowH, "Track", Track.NAMES[g.trackId],
                () -> g.setTrack(g.trackId - 1), () -> g.setTrack(g.trackId + 1));
        row(c, x0, y0 + rowH, colW, rowH, "Boat", Boat.HULL_NAMES[g.hull],
                () -> g.hull = (g.hull + 2) % 3, () -> g.hull = (g.hull + 1) % 3);
        row(c, x0, y0 + 2 * rowH, colW, rowH, "Opponents", String.valueOf(g.opponents),
                () -> g.opponents = Math.max(0, g.opponents - 1),
                () -> g.opponents = Math.min(Game.MAX_OPPONENTS, g.opponents + 1));
        row(c, x0, y0 + 3 * rowH, colW, rowH, "Laps", String.valueOf(g.laps),
                () -> g.laps = Math.max(1, g.laps - 1),
                () -> g.laps = Math.min(9, g.laps + 1));
        row(c, x1, y0, colW, rowH, "Difficulty", Game.DIFFICULTY[g.difficulty],
                () -> g.difficulty = Math.max(0, g.difficulty - 1),
                () -> g.difficulty = Math.min(2, g.difficulty + 1));
        row(c, x1, y0 + rowH, colW, rowH, "Camera", Game.VIEW_NAMES[g.view],
                () -> g.view = (g.view + 2) % 3, () -> g.view = (g.view + 1) % 3);
        row(c, x1, y0 + 2 * rowH, colW, rowH, "Controls", g.dualStick ? "Two sticks" : "One stick",
                () -> g.dualStick = !g.dualStick, () -> g.dualStick = !g.dualStick);
        row(c, x1, y0 + 3 * rowH, colW, rowH, "Sound", g.soundOn ? "On" : "Off",
                () -> g.soundOn = !g.soundOn, () -> g.soundOn = !g.soundOn);

        float bw = 56 * u, bh = 12 * u;
        button(c, (w - bw) / 2f, py + ph - bh - 3 * u, bw, bh, "START RACE", 0xFF2E9E4F, 5.5f * u,
                game::startRace);
    }

    private void row(Canvas c, float x, float y, float cw, float rh, String label, String value,
                     Action dec, Action inc) {
        float pad = 3 * u;
        text.setTextAlign(Paint.Align.LEFT);
        text.setTextSize(4.0f * u);
        text.setColor(0xFFDDE8F0);
        c.drawText(label, x + pad, y + rh * 0.6f, text);

        float bs = rh * 0.72f;
        float rightEdge = x + cw - pad;
        float valueW = Math.min(34 * u, cw * 0.42f);
        float incX = rightEdge - bs;
        float decX = incX - valueW - bs;
        float by = y + (rh - bs) / 2f;
        button(c, decX, by, bs, bs, "<", 0xFF35506A, 5 * u, dec);
        button(c, incX, by, bs, bs, ">", 0xFF35506A, 5 * u, inc);
        textC(c, value, decX + bs + valueW / 2f, y + rh * 0.6f, 4.2f * u, Color.WHITE);
    }

    private void drawRace(Canvas c, int w, int h) {
        Boat p = game.player;
        if (p == null) return;
        int n = game.boats.length;

        // ---- info box ----
        panel(c, 2 * u, 2 * u, 36 * u, 24 * u);
        text.setTextAlign(Paint.Align.LEFT);
        text.setColor(Color.WHITE);
        text.setTextSize(9 * u);
        c.drawText(Game.ordinal(game.positionOf(p)), 5 * u, 11.5f * u, text);
        text.setTextSize(4.5f * u);
        text.setColor(0xFFB8D4E8);
        c.drawText("/ " + n, 22 * u, 11.5f * u, text);
        text.setColor(Color.WHITE);
        text.setTextSize(4.6f * u);
        c.drawText("LAP " + game.currentLap(p) + "/" + game.laps, 5 * u, 18 * u, text);
        text.setTextSize(4.2f * u);
        text.setColor(0xFFFFE082);
        float shown = p.finished ? p.finishTime : game.raceTime;
        c.drawText(Game.formatTime(shown), 5 * u, 23.5f * u, text);

        // ---- minimap ----
        drawMinimap(c, w - 2 * u - 40 * u, 2 * u, 40 * u, 28 * u);

        // ---- top buttons ----
        float bw = 15 * u, bh = 9 * u, gap = 2 * u;
        float bx = w / 2f - (bw * 3 + gap * 2) / 2f;
        button(c, bx, 2 * u, bw, bh, Game.VIEW_SHORT[game.view], 0xCC24435E, 3.6f * u,
                game::toggleView);
        button(c, bx + bw + gap, 2 * u, bw, bh, "PAUSE", 0xCC24435E, 3.6f * u, () -> game.setPaused(true));
        final Game g = game;
        button(c, bx + 2 * (bw + gap), 2 * u, bw, bh, g.soundOn ? "SOUND" : "MUTED", 0xCC24435E, 3.6f * u,
                () -> g.soundOn = !g.soundOn);

        // ---- center messages ----
        if (game.state == Game.State.COUNTDOWN) {
            int num = (int) Math.ceil(game.countdown);
            textShadow(c, String.valueOf(num), w / 2f, h * 0.45f, 26 * u, 0xFFFFD54F);
            textShadow(c, "GET READY", w / 2f, h * 0.45f + 10 * u, 5 * u, Color.WHITE);
        } else if (game.messageTime > 0 && game.message.length() > 0) {
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

    private void layoutSticks(int w, int h) {
        float r = 16 * u;
        float inset = Math.max(w * 0.11f, r + 8 * u);
        left.radius = r;
        right.radius = r;
        left.cx = inset;
        left.cy = h - r - 9 * u;
        right.cx = w - inset;
        right.cy = h - r - 9 * u;
        if (game.dualStick) {
            left.useX = false; left.useY = true;     // throttle
            right.useX = true; right.useY = false;   // steering
        } else {
            left.useX = true; left.useY = true;
        }
    }

    private void drawSticks(Canvas c) {
        if (game.dualStick) {
            drawStick(c, left, "THROTTLE");
            drawStick(c, right, "STEER");
        } else {
            drawStick(c, left, "DRIVE");
        }
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
        float minX = t.minX - 8, maxX = t.maxX + 8, minZ = t.minZ - 8, maxZ = t.maxZ + 8;
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
        stroke.setStrokeWidth(Track.HALF_WIDTH * 2 * sc);
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
        float gx = t.nx(0) * Track.HALF_WIDTH * sc, gz = t.nz(0) * Track.HALF_WIDTH * sc;
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
        float pw = Math.min(w * 0.9f, 110 * u), ph = 34 * u + n * rowH;
        float px = (w - pw) / 2f, py = (h - ph) / 2f;
        panel(c, px, py, pw, ph);
        textC(c, "RACE RESULTS", w / 2f, py + 10 * u, 6.5f * u, Color.WHITE);
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
            text.setTextAlign(Paint.Align.RIGHT);
            String tm;
            if (b.finished) tm = Game.formatTime(b.finishTime);
            else tm = "Lap " + game.currentLap(b) + "...";
            text.setColor(b.finished ? 0xFFFFE082 : 0xFF9FB4C4);
            c.drawText(tm, px + pw - 6 * u, ty, text);
            y += rowH;
        }
        float bw = 40 * u, bh = 10 * u;
        float by = py + ph - bh - 4 * u;
        button(c, w / 2f - bw - 2 * u, by, bw, bh, "RACE AGAIN", 0xFF2E9E4F, 4.2f * u, game::startRace);
        button(c, w / 2f + 2 * u, by, bw, bh, "MAIN MENU", 0xFF35506A, 4.2f * u, game::startDemo);
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
                if (pressButton(x, y)) break;
                if (!sticksActive()) break;
                if (game.dualStick) {
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
                if (id == left.pointer) left.release();
                if (id == right.pointer) right.release();
                break;
            }
            case MotionEvent.ACTION_CANCEL:
                left.release();
                right.release();
                break;
        }
        pushInput();
        return true;
    }

    private boolean sticksActive() {
        synchronized (game) {
            return (game.state == Game.State.RACING || game.state == Game.State.COUNTDOWN)
                    && !game.paused && game.player != null && !game.player.finished;
        }
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
        if (game.dualStick) {
            game.inThrottle = shape(-left.ky);
            game.inSteer = shape(right.kx);
        } else {
            game.inThrottle = shape(-left.ky);
            game.inSteer = shape(left.kx);
        }
    }
}
