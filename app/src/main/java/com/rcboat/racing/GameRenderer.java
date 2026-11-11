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

/**
 * OpenGL ES 2.0 renderer: per-pixel lit scene with filmic tone mapping, procedural cloudy
 * sky, water with planar reflections, depth-based colour, shore foam and sun glints, blob
 * shadows and wake foam.
 */
final class GameRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "RCBoat";

    // ------------------------------------------------------------------ shaders

    /** Shared fragment header: precision and filmic (ACES-style) tone mapping. */
    private static final String FS_HEADER = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            vec3 grade(vec3 c) {
              c *= 1.0;
              c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
              return clamp(c, 0.0, 1.0);
            }
            """;

    private static final String LIT_VS = """
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform vec3 uTint;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
              vWorld = (uModel * vec4(aPos, 1.0)).xyz;
              vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
              vColor = aColor * uTint;
              gl_Position = uMVP * vec4(aPos, 1.0);
            }
            """;

    private static final String LIT_FS = FS_HEADER + """
            uniform vec3 uLight;
            uniform vec3 uCam;
            uniform vec3 uFogColor;
            uniform float uFogDensity;
            uniform float uSpec;
            uniform float uShine;
            uniform float uDetail;
            uniform float uClip;
            uniform float uAlpha;
            uniform sampler2D uNoise;
            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
              if (uClip > 0.5 && vWorld.y < -0.03) discard;
              vec3 base = vColor;
              if (uDetail > 1.5) {
                float d3 = texture2D(uNoise, vWorld.xz * 0.9 + vWorld.y * 0.6).r;
                base *= 0.72 + 0.5 * d3;
              } else if (uDetail > 0.5) {
                float d1 = texture2D(uNoise, vWorld.xz * 0.37).r;
                float d2 = texture2D(uNoise, vWorld.xz * 0.043).r;
                base *= 0.74 + 0.32 * d1 + 0.3 * (d2 - 0.5);
              }
              vec3 v = normalize(uCam - vWorld);
              vec3 n = normalize(vNormal);
              if ((uDetail < 0.5 || uDetail > 1.5) && dot(n, v) < 0.0) n = -n;
              float ndl = max(dot(n, uLight), 0.0);
              vec3 amb = mix(vec3(0.28, 0.26, 0.20), vec3(0.44, 0.56, 0.78), n.y * 0.5 + 0.5) * 0.5;
              vec3 sun = vec3(1.0, 0.95, 0.85) * 1.0 * ndl;
              vec3 h = normalize(uLight + v);
              float spec = uSpec * pow(max(dot(n, h), 0.0), uShine) * (0.25 + 0.75 * ndl);
              float rim = pow(1.0 - max(dot(n, v), 0.0), 5.0) * uSpec * 0.4;
              vec3 col = base * (amb + sun) + vec3(1.0, 0.97, 0.9) * spec + amb * rim;
              float f = length(uCam - vWorld) * uFogDensity;
              float fog = 1.0 - exp(-f * f);
              vec3 fogc = mix(uFogColor, vec3(1.0, 0.92, 0.78), pow(max(dot(-v, uLight), 0.0), 6.0) * 0.35);
              gl_FragColor = vec4(grade(mix(col, fogc, fog)), uAlpha);
            }
            """;

    private static final String SKY_VS = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            varying vec3 vDir;
            void main() {
              vDir = aPos;
              gl_Position = uMVP * vec4(aPos, 1.0);
            }
            """;

    private static final String SKY_FS = FS_HEADER + """
            uniform vec3 uLight;
            uniform vec3 uFogColor;
            uniform float uTimeS;
            uniform sampler2D uNoise;
            varying vec3 vDir;
            void main() {
              vec3 d = normalize(vDir);
              float up = max(d.y, 0.0);
              vec3 col = mix(uFogColor, vec3(0.15, 0.34, 0.70), pow(up, 0.45));
              float sd = max(dot(d, uLight), 0.0);
              col += vec3(1.0, 0.86, 0.62) * (pow(sd, 6.0) * 0.2 + pow(sd, 64.0) * 0.35);
              col += vec3(1.8, 1.7, 1.5) * smoothstep(0.9994, 0.9997, sd);
              if (d.y > 0.0) {
                vec2 uv = d.xz / (d.y + 0.12) * 0.2 + vec2(uTimeS * 0.004, uTimeS * 0.0015);
                float c = texture2D(uNoise, uv).r * 0.6 + texture2D(uNoise, uv * 2.7 + 0.3).r * 0.3
                        + texture2D(uNoise, uv * 6.1 + 0.7).r * 0.1;
                float cover = smoothstep(0.52, 0.78, c) * smoothstep(0.0, 0.2, d.y);
                float lit = texture2D(uNoise, uv - uLight.xz * 0.015).r;
                vec3 cloud = mix(vec3(0.60, 0.64, 0.70), vec3(1.08, 1.06, 1.02), clamp(0.55 + (c - lit) * 3.0, 0.0, 1.0));
                cloud += vec3(1.0, 0.9, 0.7) * pow(sd, 8.0) * 0.4;
                col = mix(col, cloud, cover * 0.92);
              } else {
                col = uFogColor * 0.96;
              }
              gl_FragColor = vec4(grade(col), 1.0);
            }
            """;

    private static final String WATER_VS = """
            uniform mat4 uVP;
            uniform float uTime;
            attribute vec3 aPos;
            varying vec3 vWorld;
            void main() {
              vec3 p = aPos;
              p.y = 0.035 * sin(p.x * 0.9 + uTime * 1.6) + 0.025 * sin(p.z * 1.3 - uTime * 1.9)
                  + 0.015 * sin((p.x + p.z) * 2.1 + uTime * 2.7);
              vWorld = p;
              gl_Position = uVP * vec4(p, 1.0);
            }
            """;

    private static final String WATER_FS = FS_HEADER + """
            uniform float uTimeF;
            uniform vec3 uCam;
            uniform vec3 uLight;
            uniform vec3 uFogColor;
            uniform float uFogDensity;
            uniform vec2 uScreen;
            uniform sampler2D uReflect;
            uniform sampler2D uNormalMap;
            uniform sampler2D uDepth;
            uniform sampler2D uNoise;
            varying vec3 vWorld;
            void main() {
              float x = vWorld.x, z = vWorld.z, t = uTimeF;
              float dist = length(uCam - vWorld);
              vec3 v = (uCam - vWorld) / dist;
              float dx = 0.0315 * cos(x * 0.9 + t * 1.6) + 0.0315 * cos((x + z) * 2.1 + t * 2.7);
              float dz = 0.0325 * cos(z * 1.3 - t * 1.9) + 0.0315 * cos((x + z) * 2.1 + t * 2.7);
              vec4 a = texture2D(uNormalMap, vWorld.xz * 0.045 + vec2(t * 0.010, t * 0.006));
              vec4 b = texture2D(uNormalMap, vWorld.xz * 0.11 + vec2(-t * 0.008, t * 0.013));
              vec2 ripple = (a.rg * 2.0 - 1.0) + (b.rg * 2.0 - 1.0);
              float detail = 0.28 * clamp(1.0 - dist / 140.0, 0.35, 1.0);
              vec3 n = normalize(vec3(-dx + ripple.x * detail, 1.0, -dz + ripple.y * detail));
              float ndv = max(dot(n, v), 0.0);
              float fres = 0.02 + 0.98 * pow(1.0 - ndv, 5.0);
              // ripples break up and soften the mirror image (4-tap blur)
              vec2 suv = gl_FragCoord.xy / uScreen + n.xz * (0.03 + 0.03 * clamp(5.0 / dist, 0.0, 1.0));
              vec2 bl = vec2(0.006, 0.010);
              vec3 refl = 0.25 * (texture2D(uReflect, clamp(suv + bl * vec2(1.0, 0.4), 0.002, 0.998)).rgb
                                + texture2D(uReflect, clamp(suv - bl * vec2(1.0, 0.4), 0.002, 0.998)).rgb
                                + texture2D(uReflect, clamp(suv + bl * vec2(-0.4, 1.0), 0.002, 0.998)).rgb
                                + texture2D(uReflect, clamp(suv - bl * vec2(-0.4, 1.0), 0.002, 0.998)).rgb);
              float depth = texture2D(uDepth, vWorld.xz / 360.0 + 0.5).r * 4.0;
              float sunN = max(dot(n, uLight), 0.0);
              vec3 body = mix(vec3(0.17, 0.43, 0.37), vec3(0.025, 0.11, 0.15), smoothstep(0.0, 2.6, depth));
              body *= 0.55 + 0.55 * sunN;
              vec3 h = normalize(uLight + v);
              float nh = max(dot(n, h), 0.0);
              float spec = pow(nh, 600.0) * 3.0 + pow(nh, 80.0) * 0.12;
              float foamN = texture2D(uNoise, vWorld.xz * 0.35 + vec2(t * 0.02, -t * 0.015)).r;
              float shore = 1.0 - smoothstep(0.0, 0.42, depth);
              float foam = shore * smoothstep(0.5, 0.78, foamN + 0.22 * sin(t * 1.3 - depth * 14.0) + shore * 0.3);
              vec3 col = mix(grade(body), refl, fres);
              col += vec3(1.0, 0.95, 0.85) * spec;
              col = mix(col, vec3(0.92, 0.95, 0.95), foam * 0.85);
              float alpha = mix(0.38, 0.96, smoothstep(0.05, 1.8, depth));
              alpha = max(max(alpha, min(1.0, fres * 1.3)), foam);
              alpha = clamp(alpha + spec, 0.0, 1.0);
              float f = dist * uFogDensity;
              float fog = 1.0 - exp(-f * f);
              col = mix(col, grade(uFogColor), fog);
              gl_FragColor = vec4(min(col, vec3(1.0)), mix(alpha, 1.0, fog));
            }
            """;

    /** Flat quads lying on the water: wake foam (noisy white) and blob shadows (dark). */
    private static final String PART_VS = """
            uniform mat4 uVP;
            attribute vec3 aPos;
            attribute vec3 aExtra;
            varying vec3 vExtra;
            varying vec2 vXZ;
            void main() {
              vExtra = aExtra;
              vXZ = aPos.xz;
              gl_Position = uVP * vec4(aPos, 1.0);
            }
            """;

    private static final String PART_FS = FS_HEADER + """
            uniform vec3 uPColor;
            uniform float uNoiseAmt;
            uniform sampler2D uNoise;
            varying vec3 vExtra;
            varying vec2 vXZ;
            void main() {
              float d = length(vExtra.xy);
              float shape = 1.0 - smoothstep(0.3, 1.0, d);
              float nz = texture2D(uNoise, vXZ * 0.9).r;
              float a = shape * vExtra.z * mix(1.0, smoothstep(0.25, 0.7, nz) * 2.6, uNoiseAmt);
              gl_FragColor = vec4(uPColor, clamp(a, 0.0, 1.0));
            }
            """;

    // ------------------------------------------------------------------ state

    private final Game game;
    private Scenery scene;
    private int litProg, skyProg, waterProg, partProg;
    private int lPos, lNormal, lColor, uMVP, uModel, uTint, uLight, uCam, uFogColor, uFogDensity,
            uSpec, uShine, uDetail, uClip, uAlpha, uNoiseL;
    private int sPos, sMVP, sLight, sFogColor, sTime, sNoise;
    private int wPos, wVP, wTime, wTimeF, wCam, wLight, wFogColor, wFogDensity, wScreen,
            wReflect, wNormalMap, wDepth, wNoise;
    private int pPos, pExtra, pVP, pColor, pNoiseAmt, pNoise;

    private int texNoise, texWaterNormals, texDepthIsland, texDepthNoIsland, texBlack;
    private int reflFbo, reflTex, reflDepth, reflW, reflH;
    private boolean reflOk;
    private int screenW = 1, screenH = 1;

    private final float[] proj = new float[16], view = new float[16], vp = new float[16];
    private final float[] viewRefl = new float[16], vpRefl = new float[16];
    private float[] curVP = vp;
    private final float[] model = new float[16], mvp = new float[16];
    private final float[] cam = new float[11];
    private float aspect = 1.6f;
    private long lastNanos;

    private static final float FOG_DENSITY = 0.0030f;

    // per-frame copies made while holding the game lock
    private final float[][] boatM = new float[Game.COLORS.length][16];
    private final int[] boatColor = new int[Game.COLORS.length];
    private final int[] boatHull = new int[Game.COLORS.length];
    private final float[][] boatPose = new float[Game.COLORS.length][3];  // x, z, heading
    private final float[][] figure = new float[Game.COLORS.length][4];   // x, y, z, yaw (deg)
    private final boolean[] figureVisible = new boolean[Game.COLORS.length];
    private Track track;
    private boolean islandOn;
    private final Mesh[] gates = new Mesh[Track.COUNT];
    private int boatCount;
    private final float[] markerPos = new float[3];
    private boolean showMarker;
    private float time;
    private float[] wobble = new float[0];
    private final FloatBuffer partBuf, shadowBuf;
    private int partVerts, shadowVerts;

    GameRenderer(Game game) {
        this.game = game;
        partBuf = ByteBuffer.allocateDirect(game.particles.max * 6 * 6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        shadowBuf = ByteBuffer.allocateDirect((Game.COLORS.length + 200) * 6 * 6 * 4)
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
        uTint = GLES20.glGetUniformLocation(litProg, "uTint");
        uLight = GLES20.glGetUniformLocation(litProg, "uLight");
        uCam = GLES20.glGetUniformLocation(litProg, "uCam");
        uFogColor = GLES20.glGetUniformLocation(litProg, "uFogColor");
        uFogDensity = GLES20.glGetUniformLocation(litProg, "uFogDensity");
        uSpec = GLES20.glGetUniformLocation(litProg, "uSpec");
        uShine = GLES20.glGetUniformLocation(litProg, "uShine");
        uDetail = GLES20.glGetUniformLocation(litProg, "uDetail");
        uClip = GLES20.glGetUniformLocation(litProg, "uClip");
        uAlpha = GLES20.glGetUniformLocation(litProg, "uAlpha");
        uNoiseL = GLES20.glGetUniformLocation(litProg, "uNoise");

        skyProg = program(SKY_VS, SKY_FS);
        sPos = GLES20.glGetAttribLocation(skyProg, "aPos");
        sMVP = GLES20.glGetUniformLocation(skyProg, "uMVP");
        sLight = GLES20.glGetUniformLocation(skyProg, "uLight");
        sFogColor = GLES20.glGetUniformLocation(skyProg, "uFogColor");
        sTime = GLES20.glGetUniformLocation(skyProg, "uTimeS");
        sNoise = GLES20.glGetUniformLocation(skyProg, "uNoise");

        waterProg = program(WATER_VS, WATER_FS);
        wPos = GLES20.glGetAttribLocation(waterProg, "aPos");
        wVP = GLES20.glGetUniformLocation(waterProg, "uVP");
        wTime = GLES20.glGetUniformLocation(waterProg, "uTime");
        wTimeF = GLES20.glGetUniformLocation(waterProg, "uTimeF");
        wCam = GLES20.glGetUniformLocation(waterProg, "uCam");
        wLight = GLES20.glGetUniformLocation(waterProg, "uLight");
        wFogColor = GLES20.glGetUniformLocation(waterProg, "uFogColor");
        wFogDensity = GLES20.glGetUniformLocation(waterProg, "uFogDensity");
        wScreen = GLES20.glGetUniformLocation(waterProg, "uScreen");
        wReflect = GLES20.glGetUniformLocation(waterProg, "uReflect");
        wNormalMap = GLES20.glGetUniformLocation(waterProg, "uNormalMap");
        wDepth = GLES20.glGetUniformLocation(waterProg, "uDepth");
        wNoise = GLES20.glGetUniformLocation(waterProg, "uNoise");

        partProg = program(PART_VS, PART_FS);
        pPos = GLES20.glGetAttribLocation(partProg, "aPos");
        pExtra = GLES20.glGetAttribLocation(partProg, "aExtra");
        pVP = GLES20.glGetUniformLocation(partProg, "uVP");
        pColor = GLES20.glGetUniformLocation(partProg, "uPColor");
        pNoiseAmt = GLES20.glGetUniformLocation(partProg, "uNoiseAmt");
        pNoise = GLES20.glGetUniformLocation(partProg, "uNoise");

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        texNoise = texture(TextureGen.noise(), TextureGen.NOISE_N, GLES20.GL_LUMINANCE, true);
        texWaterNormals = texture(TextureGen.waterNormals(), TextureGen.WATER_N, GLES20.GL_RGB, true);
        texDepthIsland = texture(TextureGen.depth(true), TextureGen.DEPTH_N, GLES20.GL_LUMINANCE, false);
        texDepthNoIsland = texture(TextureGen.depth(false), TextureGen.DEPTH_N, GLES20.GL_LUMINANCE, false);
        texBlack = texture(new byte[]{(byte) 190, (byte) 205, (byte) 225}, 1, GLES20.GL_RGB, false);
        reflFbo = 0;

        scene = new Scenery(game);
        java.util.Arrays.fill(gates, null);

        GLES20.glClearColor(Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2], 1f);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        lastNanos = System.nanoTime();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        screenW = Math.max(1, width);
        screenH = Math.max(1, height);
        GLES20.glViewport(0, 0, screenW, screenH);
        aspect = (float) screenW / screenH;
        createReflectionTarget(Math.max(1, screenW / 2), Math.max(1, screenH / 2));
    }

    private void createReflectionTarget(int w, int h) {
        if (reflFbo != 0) {
            GLES20.glDeleteFramebuffers(1, new int[]{reflFbo}, 0);
            GLES20.glDeleteTextures(1, new int[]{reflTex}, 0);
            GLES20.glDeleteRenderbuffers(1, new int[]{reflDepth}, 0);
        }
        reflW = w;
        reflH = h;
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        reflTex = id[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, reflTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        setParams(false, false);
        GLES20.glGenRenderbuffers(1, id, 0);
        reflDepth = id[0];
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, reflDepth);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, w, h);
        GLES20.glGenFramebuffers(1, id, 0);
        reflFbo = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reflFbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, reflTex, 0);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, reflDepth);
        reflOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (!reflOk) Log.w(TAG, "Reflection framebuffer unsupported; reflections disabled");
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
            track = game.track;
            islandOn = game.islandVisible();
            boolean shore = game.state != Game.State.MENU && game.view == Game.VIEW_SHORE;
            for (int i = 0; i < boatCount; i++) {
                Boat b = game.boats[i];
                b.modelMatrix(boatM[i], time);
                boatColor[i] = b.id;
                boatHull[i] = b.hull;
                boatPose[i][0] = b.x;
                boatPose[i][1] = b.z;
                boatPose[i][2] = b.heading;
                // the RC drivers on the platform turn to watch their boats
                float fx = game.driverX[i], fz = game.driverZ;
                figure[i][0] = fx;
                figure[i][1] = game.deckY;
                figure[i][2] = fz;
                figure[i][3] = (float) Math.toDegrees(Math.atan2(-(b.x - fx), -(b.z - fz)));
                figureVisible[i] = !(shore && b.isPlayer);
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
        if (gates[track.id] == null) gates[track.id] = Scenery.buildGate(track);
        fillShadows();

        Matrix.perspectiveM(proj, 0, cam[9], aspect, cam[10], 700f);
        Matrix.setLookAtM(view, 0, cam[0], cam[1], cam[2], cam[3], cam[4], cam[5], cam[6], cam[7], cam[8]);
        Matrix.multiplyMM(vp, 0, proj, 0, view, 0);

        // ---- pass 1: the scene mirrored in the water plane, into the reflection texture ----
        if (reflOk) {
            Matrix.scaleM(viewRefl, 0, view, 0, 1f, -1f, 1f);
            Matrix.multiplyMM(vpRefl, 0, proj, 0, viewRefl, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, reflFbo);
            GLES20.glViewport(0, 0, reflW, reflH);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            curVP = vpRefl;
            drawScene(cam[0], -cam[1], cam[2], true);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, screenW, screenH);
        }

        // ---- pass 2: the real view ----
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
        curVP = vp;
        drawScene(cam[0], cam[1], cam[2], false);
        drawWater();
        drawFlat(shadowBuf, shadowVerts, 0.0f, 0.03f, 0.05f, 0f);
        drawFlat(partBuf, partVerts, 0.94f, 0.97f, 1.0f, 1f);
        GLES20.glDepthMask(true);
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    /** Sky and all solid geometry. When reflecting, geometry below the water is clipped. */
    private void drawScene(float cx, float cy, float cz, boolean reflection) {
        // sky dome follows the camera
        GLES20.glUseProgram(skyProg);
        GLES20.glDepthMask(false);
        Matrix.setIdentityM(model, 0);
        Matrix.translateM(model, 0, cam[0], cam[1] * 0.2f, cam[2]);
        Matrix.multiplyMM(mvp, 0, curVP, 0, model, 0);
        GLES20.glUniformMatrix4fv(sMVP, 1, false, mvp, 0);
        GLES20.glUniform3f(sLight, Scenery.LIGHT[0], Scenery.LIGHT[1], Scenery.LIGHT[2]);
        GLES20.glUniform3f(sFogColor, Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2]);
        GLES20.glUniform1f(sTime, time);
        bindTex(0, texNoise, sNoise);
        scene.sky.draw(sPos, -1, -1);
        GLES20.glDisableVertexAttribArray(sPos);
        GLES20.glDepthMask(true);

        GLES20.glUseProgram(litProg);
        GLES20.glUniform3f(uLight, Scenery.LIGHT[0], Scenery.LIGHT[1], Scenery.LIGHT[2]);
        GLES20.glUniform3f(uCam, cx, cy, cz);
        GLES20.glUniform3f(uFogColor, Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2]);
        GLES20.glUniform1f(uFogDensity, FOG_DENSITY);
        GLES20.glUniform1f(uClip, reflection ? 1f : 0f);
        GLES20.glUniform1f(uAlpha, 1f);
        bindTex(0, texNoise, uNoiseL);

        Matrix.setIdentityM(model, 0);
        material(0.05f, 8f, true);
        drawLit(scene.terrain);
        if (islandOn) drawLit(scene.island);
        material(0.1f, 16f, false);
        GLES20.glUniform1f(uDetail, 2f);
        drawLit(scene.props);
        if (islandOn) drawLit(scene.islandProps);
        material(0.35f, 40f, false);
        drawLit(gates[track.id]);

        // RC drivers on the platform
        material(0.15f, 12f, false);
        for (int i = 0; i < boatCount; i++) {
            if (!figureVisible[i]) continue;
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, figure[i][0], figure[i][1], figure[i][2]);
            Matrix.rotateM(model, 0, figure[i][3], 0, 1, 0);
            drawLit(scene.figures[boatColor[i]]);
        }

        // buoys
        material(0.7f, 60f, false);
        java.util.ArrayList<float[]> buoys = track.buoys;
        for (int i = 0; i < buoys.size(); i++) {
            float[] bu = buoys.get(i);
            float wy = World.wave(bu[0], bu[1], time);
            float w = i < wobble.length ? wobble[i] : 0f;
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, bu[0], wy, bu[1]);
            float tilt = (float) Math.sin(time * 1.3f + i) * 4f + w * 25f * (float) Math.sin(time * 9f + i);
            Matrix.rotateM(model, 0, tilt, (float) Math.cos(i), 0, (float) Math.sin(i));
            drawLit(bu[2] == 0 ? scene.buoyRed : scene.buoyYellow);
        }

        // boats: glossy gelcoat
        material(0.9f, 90f, false);
        for (int i = 0; i < boatCount; i++) {
            System.arraycopy(boatM[i], 0, model, 0, 16);
            drawLit(scene.boats[boatHull[i]][boatColor[i]]);
        }

        // "this is you" marker above the player's boat (shore view only)
        if (showMarker && !reflection) {
            float dx = markerPos[0] - cam[0], dz = markerPos[2] - cam[2];
            float d = (float) Math.sqrt(dx * dx + dz * dz);
            float s = Math.max(0.5f, d / 30f);
            Matrix.setIdentityM(model, 0);
            Matrix.translateM(model, 0, markerPos[0], markerPos[1] + 0.55f + s * 0.15f
                    + 0.08f * s * (float) Math.sin(time * 4f), markerPos[2]);
            Matrix.scaleM(model, 0, s, s, s);
            Matrix.rotateM(model, 0, time * 90f, 0, 1, 0);
            material(0.5f, 30f, false);
            drawLit(scene.marker);
        }
    }

    private void drawWater() {
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
        GLES20.glUniform2f(wScreen, screenW, screenH);
        bindTex(0, texNoise, wNoise);
        bindTex(1, texWaterNormals, wNormalMap);
        bindTex(2, islandOn ? texDepthIsland : texDepthNoIsland, wDepth);
        bindTex(3, reflOk ? reflTex : texBlack, wReflect);
        scene.water.draw(wPos, -1, -1);
        GLES20.glDisableVertexAttribArray(wPos);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
    }

    /** Flat quads on the water (shadows or foam); expects blending on and depth writes off. */
    private void drawFlat(FloatBuffer buf, int verts, float r, float g, float b, float noiseAmt) {
        if (verts <= 0) return;
        GLES20.glUseProgram(partProg);
        GLES20.glUniformMatrix4fv(pVP, 1, false, vp, 0);
        GLES20.glUniform3f(pColor, r, g, b);
        GLES20.glUniform1f(pNoiseAmt, noiseAmt);
        bindTex(0, texNoise, pNoise);
        buf.position(0);
        GLES20.glEnableVertexAttribArray(pPos);
        GLES20.glVertexAttribPointer(pPos, 3, GLES20.GL_FLOAT, false, 24, buf);
        buf.position(3);
        GLES20.glEnableVertexAttribArray(pExtra);
        GLES20.glVertexAttribPointer(pExtra, 3, GLES20.GL_FLOAT, false, 24, buf);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, verts);
        GLES20.glDisableVertexAttribArray(pPos);
        GLES20.glDisableVertexAttribArray(pExtra);
        buf.position(0);
    }

    private void material(float spec, float shine, boolean detail) {
        GLES20.glUniform1f(uSpec, spec);
        GLES20.glUniform1f(uShine, shine);
        GLES20.glUniform1f(uDetail, detail ? 1f : 0f);
    }

    private void drawLit(Mesh m) {
        Matrix.multiplyMM(mvp, 0, curVP, 0, model, 0);
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0);
        GLES20.glUniform3f(uTint, 1f, 1f, 1f);
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
            quad(partBuf, x, y, z, s, 0f, 0f, s, a);
            v += 6;
        }
        partVerts = v;
        partBuf.position(0);
    }

    /** Soft dark ellipses under the boats and buoys, nudged away from the sun. */
    private void fillShadows() {
        shadowBuf.position(0);
        int v = 0;
        float sx = -Scenery.LIGHT[0] / Scenery.LIGHT[1], sz = -Scenery.LIGHT[2] / Scenery.LIGHT[1];
        for (int i = 0; i < boatCount; i++) {
            float x = boatPose[i][0] + sx * 0.15f, z = boatPose[i][1] + sz * 0.15f, hd = boatPose[i][2];
            float fx = (float) -Math.sin(hd), fz = (float) -Math.cos(hd);
            float y = World.wave(x, z, time) + 0.04f;
            // half-length along the hull, half-width across it
            quad(shadowBuf, x, y, z, fx * 0.85f, fz * 0.85f, -fz * 0.42f, fx * 0.42f, 0.5f);
            v += 6;
        }
        java.util.ArrayList<float[]> buoys = track.buoys;
        for (int i = 0; i < buoys.size() && v / 6 < Game.COLORS.length + 200; i++) {
            float[] bu = buoys.get(i);
            float x = bu[0] + sx * 0.3f, z = bu[1] + sz * 0.3f;
            quad(shadowBuf, x, World.wave(x, z, time) + 0.04f, z, 0.6f, 0f, 0f, 0.6f, 0.38f);
            v += 6;
        }
        shadowVerts = v;
        shadowBuf.position(0);
    }

    /** A flat quad centred at (x, y, z) spanned by axes (ax, az) and (bx, bz). */
    private static void quad(FloatBuffer buf, float x, float y, float z, float ax, float az,
                             float bx, float bz, float a) {
        float[][] c = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (float[] k : c) {
            buf.put(x + ax * k[0] + bx * k[1]).put(y).put(z + az * k[0] + bz * k[1])
                    .put(k[0]).put(k[1]).put(a);
        }
    }

    // ------------------------------------------------------------------ GL helpers

    private static void bindTex(int unit, int tex, int uniform) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
        GLES20.glUniform1i(uniform, unit);
    }

    private static void setParams(boolean repeat, boolean mipmap) {
        int wrap = repeat ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE;
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, wrap);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER,
                mipmap ? GLES20.GL_LINEAR_MIPMAP_LINEAR : GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
    }

    /** Uploads a square texture; repeating textures get mipmaps. */
    private static int texture(byte[] data, int size, int format, boolean repeat) {
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length).order(ByteOrder.nativeOrder());
        bb.put(data).position(0);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, format, size, size, 0, format,
                GLES20.GL_UNSIGNED_BYTE, bb);
        setParams(repeat, repeat);
        if (repeat) GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        return id[0];
    }

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
