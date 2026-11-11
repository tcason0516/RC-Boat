package com.rcboat.racing;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;

final class GameRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "RCBoat";

    // ------------------------------------------------------------------ shaders

    private static final String LIT_VS =
            "uniform mat4 uMVP;\n" +
            "uniform mat4 uModel;\n" +
            "uniform vec3 uLight;\n" +
            "uniform vec3 uCam;\n" +
            "uniform float uFogDensity;\n" +
            "uniform float uLit;\n" +
            "uniform vec4 uTint;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNormal;\n" +
            "attribute vec3 aColor;\n" +
            "varying vec3 vColor;\n" +
            "varying float vFog;\n" +
            "varying float vAlpha;\n" +
            "void main() {\n" +
            "  vAlpha = uTint.a;\n" +
            "  vec4 wp = uModel * vec4(aPos, 1.0);\n" +
            "  vec3 n = normalize((uModel * vec4(aNormal, 0.0)).xyz);\n" +
            "  float d = max(dot(n, uLight), 0.0);\n" +
            "  float sky = 0.5 + 0.5 * n.y;\n" +
            "  vec3 light = vec3(0.30, 0.34, 0.40) * sky + vec3(1.0, 0.96, 0.88) * d * 0.80 + 0.10;\n" +
            "  vColor = aColor * uTint.rgb * mix(vec3(1.0), light, uLit);\n" +
            "  float f = length(wp.xyz - uCam) * uFogDensity;\n" +
            "  vFog = 1.0 - exp(-f * f);\n" +
            "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
            "}\n";

    private static final String LIT_FS =
            "precision mediump float;\n" +
            "uniform vec3 uFogColor;\n" +
            "varying vec3 vColor;\n" +
            "varying float vFog;\n" +
            "varying float vAlpha;\n" +
            "void main() {\n" +
            "  gl_FragColor = vec4(mix(vColor, uFogColor, vFog), vAlpha);\n" +
            "}\n";

    private static final String WATER_VS =
            "uniform mat4 uVP;\n" +
            "uniform float uTime;\n" +
            "attribute vec3 aPos;\n" +
            "varying vec3 vWorld;\n" +
            "void main() {\n" +
            "  vec3 p = aPos;\n" +
            "  p.y = 0.035 * sin(p.x * 0.9 + uTime * 1.6) + 0.025 * sin(p.z * 1.3 - uTime * 1.9)\n" +
            "      + 0.015 * sin((p.x + p.z) * 2.1 + uTime * 2.7);\n" +
            "  vWorld = p;\n" +
            "  gl_Position = uVP * vec4(p, 1.0);\n" +
            "}\n";

    private static final String WATER_FS =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n" +
            "precision highp float;\n" +
            "#else\n" +
            "precision mediump float;\n" +
            "#endif\n" +
            "uniform float uTimeF;\n" +
            "uniform vec3 uCam;\n" +
            "uniform vec3 uLight;\n" +
            "uniform vec3 uFogColor;\n" +
            "uniform float uFogDensity;\n" +
            "varying vec3 vWorld;\n" +
            "void main() {\n" +
            "  float x = vWorld.x, z = vWorld.z, t = uTimeF;\n" +
            "  float dist = length(uCam - vWorld);\n" +
            "  float k = clamp(1.0 - dist / 70.0, 0.2, 1.0);\n" +
            "  float a1 = x * 4.1 + z * 1.3 + t * 3.0;\n" +
            "  float a2 = -x * 2.3 + z * 5.2 + t * 2.6;\n" +
            "  float a3 = x * 7.7 - z * 6.1 + t * 4.0;\n" +
            "  float dx = 0.0315 * cos(x * 0.9 + t * 1.6) + 0.0315 * cos((x + z) * 2.1 + t * 2.7)\n" +
            "     + k * (0.049 * cos(a1) - 0.023 * cos(a2) + 0.031 * cos(a3));\n" +
            "  float dz = 0.0325 * cos(z * 1.3 - t * 1.9) + 0.0315 * cos((x + z) * 2.1 + t * 2.7)\n" +
            "     + k * (0.016 * cos(a1) + 0.052 * cos(a2) - 0.024 * cos(a3));\n" +
            "  vec3 n = normalize(vec3(-dx, 1.0, -dz));\n" +
            "  vec3 v = normalize(uCam - vWorld);\n" +
            "  float fres = pow(1.0 - max(dot(n, v), 0.0), 4.0);\n" +
            "  vec3 deep = vec3(0.04, 0.24, 0.32);\n" +
            "  vec3 skyc = vec3(0.72, 0.84, 0.95);\n" +
            "  vec3 col = mix(deep, skyc, 0.10 + 0.78 * fres);\n" +
            "  vec3 h = normalize(uLight + v);\n" +
            "  float spec = pow(max(dot(n, h), 0.0), 140.0) * 1.3;\n" +
            "  col += vec3(1.0, 0.95, 0.85) * spec;\n" +
            "  float alpha = clamp(0.70 + 0.28 * fres + spec, 0.0, 1.0);\n" +
            "  float f = dist * uFogDensity;\n" +
            "  col = mix(col, uFogColor, 1.0 - exp(-f * f));\n" +
            "  gl_FragColor = vec4(col, alpha);\n" +
            "}\n";

    private static final String PART_VS =
            "uniform mat4 uVP;\n" +
            "attribute vec3 aPos;\n" +
            "attribute vec3 aExtra;\n" +
            "varying vec3 vExtra;\n" +
            "void main() {\n" +
            "  vExtra = aExtra;\n" +
            "  gl_Position = uVP * vec4(aPos, 1.0);\n" +
            "}\n";

    private static final String PART_FS =
            "precision mediump float;\n" +
            "varying vec3 vExtra;\n" +
            "void main() {\n" +
            "  float d = length(vExtra.xy);\n" +
            "  float a = (1.0 - smoothstep(0.35, 1.0, d)) * vExtra.z;\n" +
            "  gl_FragColor = vec4(0.94, 0.97, 1.0, a);\n" +
            "}\n";

    // ------------------------------------------------------------------ state

    private final Game game;
    private Scenery scene;
    private int litProg, waterProg, partProg;
    private int lPos, lNormal, lColor, uMVP, uModel, uLight, uCam, uFogDensity, uLit, uTint, uFogColor;
    private int wPos, wVP, wTime, wTimeF, wCam, wLight, wFogColor, wFogDensity;
    private int pPos, pExtra, pVP;

    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] model = new float[16], mvp = new float[16];
    private final float[] cam = new float[11];
    private float aspect = 1.6f;
    private long lastNanos;

    private static final float FOG_DENSITY = 0.0036f;

    // per-frame copies made while holding the game lock
    private final float[][] boatM = new float[Game.COLORS.length][16];
    private final int[] boatColor = new int[Game.COLORS.length];
    private int boatCount;
    private final float[] markerPos = new float[3];
    private boolean showMarker;
    private float time;
    private float[] wobble = new float[0];
    private final FloatBuffer partBuf;
    private int partVerts;

    GameRenderer(Game game) {
        this.game = game;
        partBuf = ByteBuffer.allocateDirect(game.particles.max * 6 * 6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        litProg = program(LIT_VS, LIT_FS);
        lPos = GLES20.glGetAttribLocation(litProg, "aPos");
        lNormal = GLES20.glGetAttribLocation(litProg, "aNormal");
        lColor = GLES20.glGetAttribLocation(litProg, "aColor");
        uMVP = GLES20.glGetUniformLocation(litProg, "uMVP");
        uModel = GLES20.glGetUniformLocation(litProg, "uModel");
        uLight = GLES20.glGetUniformLocation(litProg, "uLight");
        uCam = GLES20.glGetUniformLocation(litProg, "uCam");
        uFogDensity = GLES20.glGetUniformLocation(litProg, "uFogDensity");
        uLit = GLES20.glGetUniformLocation(litProg, "uLit");
        uTint = GLES20.glGetUniformLocation(litProg, "uTint");
        uFogColor = GLES20.glGetUniformLocation(litProg, "uFogColor");

        waterProg = program(WATER_VS, WATER_FS);
        wPos = GLES20.glGetAttribLocation(waterProg, "aPos");
        wVP = GLES20.glGetUniformLocation(waterProg, "uVP");
        wTime = GLES20.glGetUniformLocation(waterProg, "uTime");
        wTimeF = GLES20.glGetUniformLocation(waterProg, "uTimeF");
        wCam = GLES20.glGetUniformLocation(waterProg, "uCam");
        wLight = GLES20.glGetUniformLocation(waterProg, "uLight");
        wFogColor = GLES20.glGetUniformLocation(waterProg, "uFogColor");
        wFogDensity = GLES20.glGetUniformLocation(waterProg, "uFogDensity");

        partProg = program(PART_VS, PART_FS);
        pPos = GLES20.glGetAttribLocation(partProg, "aPos");
        pExtra = GLES20.glGetAttribLocation(partProg, "aExtra");
        pVP = GLES20.glGetUniformLocation(partProg, "uVP");

        scene = new Scenery(game);

        GLES20.glClearColor(Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2], 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        lastNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        aspect = height > 0 ? (float) width / height : 1f;
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        long now = System.nanoTime();
        float dt = Math.min(0.05f, Math.max(0f, (now - lastNanos) / 1e9f));
        lastNanos = now;

        synchronized (game) {
            game.update(dt);
            game.camera(dt, cam);
            time = game.time;
            boatCount = game.boats.length;
            for (int i = 0; i < boatCount; i++) {
                Boat b = game.boats[i];
                b.modelMatrix(boatM[i], time);
                boatColor[i] = b.id;
            }
            showMarker = game.state != Game.State.MENU && game.view == Game.VIEW_SHORE && game.player != null;
            if (showMarker) {
                markerPos[0] = game.player.x;
                markerPos[1] = game.player.y;
                markerPos[2] = game.player.z;
            }
            if (wobble.length != game.buoyWobble.length) wobble = new float[game.buoyWobble.length];
            System.arraycopy(game.buoyWobble, 0, wobble, 0, wobble.length);
            fillParticles(game.particles);
        }

        Matrix.perspectiveM(proj, 0, cam[9], aspect, cam[10], 700f);
        Matrix.setLookAtM(view, 0, cam[0], cam[1], cam[2], cam[3], cam[4], cam[5], cam[6], cam[7], cam[8]);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        // ---- lit geometry ----
        GLES20.glUseProgram(litProg);
        GLES20.glUniform3f(uLight, Scenery.LIGHT[0], Scenery.LIGHT[1], Scenery.LIGHT[2]);
        GLES20.glUniform3f(uCam, cam[0], cam[1], cam[2]);
        GLES20.glUniform3f(uFogColor, Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2]);

        // sky (follows the camera, no depth write, no fog, unlit)
        GLES20.glDepthMask(false);
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, cam[0], cam[1] * 0.2f, cam[2]);
        GLES20.glUniform1f(uFogDensity, 0f);
        GLES20.glUniform1f(uLit, 0f);
        drawLit(scene.sky, 1, 1, 1, 1);
        GLES20.glDepthMask(true);

        GLES20.glUniform1f(uFogDensity, FOG_DENSITY);
        GLES20.glUniform1f(uLit, 1f);
        Matrix.setIdentityM(model, 0);
        drawLit(scene.terrain, 1, 1, 1, 1);
        drawLit(scene.props, 1, 1, 1, 1);

        // buoys
        java.util.ArrayList<float[]> buoys = game.track.buoys;
        for (int i = 0; i < buoys.size(); i++) {
            float[] bu = buoys.get(i);
            float wy = World.wave(bu[0], bu[1], time);
            float w = i < wobble.length ? wobble[i] : 0f;
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, bu[0], wy, bu[1]);
            float tilt = (float) Math.sin(time * 1.3f + i) * 4f + w * 25f * (float) Math.sin(time * 9f + i);
            Matrix.rotateM(model, 0, tilt, (float) Math.cos(i), 0, (float) Math.sin(i));
            drawLit(bu[2] == 0 ? scene.buoyRed : scene.buoyYellow, 1, 1, 1, 1);
        }

        // boats
        for (int i = 0; i < boatCount; i++) {
            System.arraycopy(boatM[i], 0, model, 0, 16);
            drawLit(scene.boats[boatColor[i]], 1, 1, 1, 1);
        }

        // "this is you" marker above the player's boat (shore view only)
        if (showMarker) {
            float dx = markerPos[0] - cam[0], dz = markerPos[2] - cam[2];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float s = Math.max(0.6f, d / 22f);
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, markerPos[0], markerPos[1] + 1.0f + s * 0.4f
                    + 0.15f * s * (float) Math.sin(time * 4f), markerPos[2]);
            Matrix.scaleM(model, 0, s, s, s);
            Matrix.rotateM(model, 0, time * 90f, 0, 1, 0);
            drawLit(scene.marker, 1, 1, 1, 1);
        }

        // ---- water (transparent) ----
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glDepthMask(false);
        GLES20.glUseProgram(waterProg);
        GLES20.glUniformMatrix4fv(wVP, 1, false, vp, 0);
        GLES20.glUniform1f(wTime, time);
        GLES20.glUniform1f(wTimeF, time);
        GLES20.glUniform3f(wCam, cam[0], cam[1], cam[2]);
        GLES20.glUniform3f(wLight, Scenery.LIGHT[0], Scenery.LIGHT[1], Scenery.LIGHT[2]);
        GLES20.glUniform3f(wFogColor, Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2]);
        GLES20.glUniform1f(wFogDensity, FOG_DENSITY);
        scene.water.draw(wPos, -1, -1);
        GLES20.glDisableVertexAttribArray(wPos);

        // ---- wake foam ----
        if (partVerts > 0) {
            GLES20.glUseProgram(partProg);
            GLES20.glUniformMatrix4fv(pVP, 1, false, vp, 0);
            partBuf.position(0);
            GLES20.glEnableVertexAttribArray(pPos);
            GLES20.glVertexAttribPointer(pPos, 3, GLES20.GL_FLOAT, false, 24, partBuf);
            partBuf.position(3);
            GLES20.glEnableVertexAttribArray(pExtra);
            GLES20.glVertexAttribPointer(pExtra, 3, GLES20.GL_FLOAT, false, 24, partBuf);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, partVerts);
            GLES20.glDisableVertexAttribArray(pPos);
            GLES20.glDisableVertexAttribArray(pExtra);
        }
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private void drawLit(Mesh m, float r, float g, float b, float a) {
        Matrix.multiplyMM(mvp, 0, vp, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform4f(uTint, r, g, b, a);
        m.draw(lPos, lNormal, lColor);
        GLES20.glDisableVertexAttribArray(lPos);
        GLES20.glDisableVertexAttribArray(lNormal);
        GLES20.glDisableVertexAttribArray(lColor);
    }

    private void fillParticles(Particles p) {
        partBuf.position(0);
        int v = 0;
        for (int i = 0; i < p.count; i++) {
            float s = p.sizeOf(i), a = p.alphaOf(i);
            if (a < 0.01f) continue;
            float x = p.x[i], z = p.z[i];
            float y = World.wave(x, z, time) + 0.05f;
            put(x - s, y, z - s, -1, -1, a);
            put(x + s, y, z - s, 1, -1, a);
            put(x + s, y, z + s, 1, 1, a);
            put(x - s, y, z - s, -1, -1, a);
            put(x + s, y, z + s, 1, 1, a);
            put(x - s, y, z + s, -1, 1, a);
            v += 6;
        }
        partVerts = v;
        partBuf.position(0);
    }

    private void put(float x, float y, float z, float u, float w, float a) {
        partBuf.put(x).put(y).put(z).put(u).put(w).put(a);
    }

    // ------------------------------------------------------------------ GL helpers

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            Log.e(TAG, "Shader error: " + log + "\n" + src);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }

    private static int program(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            Log.e(TAG, "Link error: " + log);
            throw new RuntimeException("Program link failed: " + log);
        }
        return p;
    }

    /** Picks a config with 4x MSAA and a 24-bit depth buffer when available. */
    static final class ConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES2_BIT = 4;

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            int[][] attempts = {
                    attribs(24, 4), attribs(24, 0), attribs(16, 4), attribs(16, 0)
            };
            for (int[] a : attempts) {
                EGLConfig[] configs = new EGLConfig[1];
                int[] num = new int[1];
                if (egl.eglChooseConfig(display, a, configs, 1, num) && num[0] > 0) return configs[0];
            }
            throw new IllegalArgumentException("No suitable EGL config");
        }

        private static int[] attribs(int depth, int samples) {
            if (samples > 0) {
                return new int[]{
                        EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                        EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                        EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples, EGL10.EGL_NONE};
            }
            return new int[]{
                    EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8,
                    EGL10.EGL_DEPTH_SIZE, depth, EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                    EGL10.EGL_NONE};
        }
    }
}
