package com.rcboat.racing;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

public class MainActivity extends Activity implements SensorEventListener {
    /** Full steering lock when the phone is turned this far (degrees). */
    private static final float TILT_LOCK = 28f;
    private SensorManager sensors;
    private Sensor tiltSensor;
    private float tiltLp;
    private GLSurfaceView glView;
    private Game game;
    private EngineSound sound;
    private HudView hud;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Game.combatBuild = BuildConfig.COMBAT;
        game = new Game();
        game.records.load(getSharedPreferences("rcboat", MODE_PRIVATE).getString("records", ""));
        game.records.dirty = false;
        sound = new EngineSound(game);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(new GameRenderer.ConfigChooser());
        glView.setPreserveEGLContextOnPause(true);
        glView.setRenderer(new GameRenderer(this, game));

        hud = new HudView(this, game);

        // tilt steering: the gravity direction in the plane of the screen
        sensors = (SensorManager) getSystemService(Context.SENSOR_SERVICE);
        if (sensors != null) {
            tiltSensor = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY);
            if (tiltSensor == null) tiltSensor = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        }
        game.tiltAvailable = tiltSensor != null;

        FrameLayout root = new FrameLayout(this);
        root.addView(glView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        root.addView(hud, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);
        hideSystemUi();
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        sound.start();
        if (tiltSensor != null) sensors.registerListener(this, tiltSensor, SensorManager.SENSOR_DELAY_GAME);
        hideSystemUi();
    }

    @Override
    protected void onPause() {
        super.onPause();
        game.setPaused(true);
        if (tiltSensor != null) sensors.unregisterListener(this);
        saveRecords();
        hud.stopVoice();
        sound.stop();
        glView.onPause();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onSensorChanged(SensorEvent e) {
        float gx = e.values[0], gy = e.values[1], gz = e.values[2];
        // into screen coordinates (x right, y up) for the way the phone is held
        float sx, sy;
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_90: sx = -gy; sy = gx; break;
            case Surface.ROTATION_180: sx = -gx; sy = -gy; break;
            case Surface.ROTATION_270: sx = gy; sy = -gx; break;
            default: sx = gx; sy = gy; break;
        }
        // turning the phone like a steering wheel; leaning it back or forward doesn't steer
        float ang = (float) Math.toDegrees(Math.atan2(-sx, Math.hypot(sy, gz)));
        float k = tiltSensor.getType() == Sensor.TYPE_GRAVITY ? 0.5f : 0.25f;
        tiltLp += (ang - tiltLp) * k;
        game.tiltSteer = Math.max(-1f, Math.min(1f, tiltLp / TILT_LOCK));
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /** Saves best times and speeds when they changed. */
    void saveRecords() {
        if (!game.records.dirty) return;
        game.records.dirty = false;
        getSharedPreferences("rcboat", MODE_PRIVATE).edit()
                .putString("records", game.records.serialize()).apply();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!game.onBack()) super.onBackPressed();
    }
}
