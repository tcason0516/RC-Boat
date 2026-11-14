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
            float unpackDepth(vec4 c) {
              return dot(c, vec4(1.0, 1.0 / 255.0, 1.0 / 65025.0, 1.0 / 16581375.0));
            }
            float shadowLookup(sampler2D map, vec4 sp, float bias, float texel) {
              vec3 p = sp.xyz / sp.w * 0.5 + 0.5;
              if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) return 1.0;
              float z = p.z - bias;
              // 8-tap soft shadow (rotated grid, ~1.5 texel radius)
              float s = step(z, unpackDepth(texture2D(map, p.xy + vec2(-1.5, -0.4) * texel)))
                      + step(z, unpackDepth(texture2D(map, p.xy + vec2(1.5, 0.4) * texel)))
                      + step(z, unpackDepth(texture2D(map, p.xy + vec2(-0.4, 1.5) * texel)))
                      + step(z, unpackDepth(texture2D(map, p.xy + vec2(0.4, -1.5) * texel)))
                      + step(z, unpackDepth(texture2D(map, p.xy + vec2(-0.6, -0.6) * texel)))
                      + step(z, unpackDepth(texture2D(map, p.xy + vec2(0.6, 0.6) * texel)))
                      + step(z, unpackDepth(texture2D(map, p.xy + vec2(-0.9, 0.9) * texel)))
                      + step(z, unpackDepth(texture2D(map, p.xy + vec2(0.9, -0.9) * texel)));
              return s * 0.125;
            }
            vec3 grade(vec3 c) {
              c *= 1.0;
              c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
              return clamp(c, 0.0, 1.0);
            }
            """;

    private static final String LIT_VS = """
            uniform mat4 uMVP;
            uniform mat4 uModel;
            uniform mat4 uLightVP;
            uniform vec3 uTint;
            uniform float uSway;
            uniform float uTimeV;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorld;
            varying vec4 vShadow;
            varying float vBright;
            void main() {
              vec3 p = aPos;
              if (uSway > 0.0) {
                // foliage: colour slot holds (u, v, flex*9 + brightness/2); sway by flex
                float flex = floor(aColor.b) / 9.0;
                float ph = uTimeV * 1.7 + p.x * 0.35 + p.z * 0.27;
                p.x += sin(ph) * uSway * flex;
                p.z += cos(ph * 0.83) * uSway * flex * 0.6;
              }
              vBright = fract(aColor.b) * 2.0;
              vWorld = (uModel * vec4(p, 1.0)).xyz;
              vNormal = (uModel * vec4(aNormal, 0.0)).xyz;
              vShadow = uLightVP * vec4(vWorld, 1.0);
              vColor = aColor * uTint;
              gl_Position = uMVP * vec4(p, 1.0);
            }
            """;

    /** Shared sun/sky lighting, shadow and haze for solid surfaces. */
    private static final String LIGHT_FN = """
            uniform vec3 uLight;
            uniform vec3 uCam;
            uniform vec3 uFogColor;
            uniform float uFogDensity;
            uniform float uShadowOn;
            uniform float uTexel;
            uniform sampler2D uShadow;
            varying vec4 vShadow;
            float sunShadow(vec3 n) {
              float ndl = max(dot(n, uLight), 0.0);
              return uShadowOn > 0.5 ? shadowLookup(uShadow, vShadow, 0.0008 + 0.002 * (1.0 - ndl), uTexel) : 1.0;
            }
            vec3 lightAndFog(vec3 base, vec3 n, vec3 world, float sh, float specAmt, float shine, float glow) {
              vec3 v = normalize(uCam - world);
              float ndl = max(dot(n, uLight), 0.0);
              vec3 amb = mix(vec3(0.28, 0.26, 0.20), vec3(0.44, 0.56, 0.78), n.y * 0.5 + 0.5) * 0.5;
              vec3 sun = vec3(1.0, 0.95, 0.85) * ndl * sh;
              vec3 h = normalize(uLight + v);
              float spec = specAmt * pow(max(dot(n, h), 0.0), shine) * (0.25 + 0.75 * ndl) * sh;
              float rim = pow(1.0 - max(dot(n, v), 0.0), 5.0) * specAmt * 0.4;
              vec3 col = base * (amb + sun) + vec3(1.0, 0.97, 0.9) * spec + amb * rim;
              // sunlight glowing through leaves when looking towards the sun
              col += base * vec3(0.9, 1.0, 0.55) * glow * pow(max(dot(-v, uLight), 0.0), 2.0) * sh;
              float f = length(uCam - world) * uFogDensity;
              float fog = 1.0 - exp(-f * f);
              vec3 fogc = mix(uFogColor, vec3(1.0, 0.92, 0.78), pow(max(dot(-v, uLight), 0.0), 6.0) * 0.35);
              return grade(mix(col, fogc, fog));
            }
            """;

    /**
     * Solid objects. uDetail: 0 = plain vertex colour, 2 = weathered (noise), 3 = foliage
     * cards from the atlas, 4 = photo texture mapped triplanar in world space (uTex).
     */
    private static final String LIT_FS = FS_HEADER + LIGHT_FN + """
            uniform float uSpec;
            uniform float uShine;
            uniform float uDetail;
            uniform float uClip;
            uniform float uAlpha;
            uniform float uTexScale;
            uniform sampler2D uNoise;
            uniform sampler2D uAtlas;
            uniform sampler2D uTex;
            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorld;
            varying float vBright;
            void main() {
              if (uClip > 0.5 && vWorld.y < -0.03) discard;
              vec3 base = vColor;
              float outA = uAlpha;
              vec3 n = normalize(vNormal);
              bool leafy = uDetail > 2.5 && uDetail < 3.5;
              if (uDetail > 3.5) {
                vec3 w = abs(n);
                w = w * w;
                w /= (w.x + w.y + w.z);
                vec3 p = vWorld * uTexScale;
                vec3 tx = texture2D(uTex, p.zy).rgb * w.x + texture2D(uTex, p.xz).rgb * w.y
                        + texture2D(uTex, p.xy).rgb * w.z;
                base = tx * vColor;
              } else if (leafy) {
                vec4 tx = texture2D(uAtlas, vColor.rg);
                if (tx.a < 0.35) discard;
                base = tx.rgb * vBright * 0.82;
                outA = tx.a;
              } else if (uDetail > 1.5) {
                float d3 = texture2D(uNoise, vWorld.xz * 0.9 + vWorld.y * 0.6).r;
                base *= 0.72 + 0.5 * d3;
              }
              if (!leafy && dot(n, uCam - vWorld) < 0.0) n = -n;
              float sh = sunShadow(n);
              gl_FragColor = vec4(lightAndFog(base, n, vWorld, sh, uSpec, uShine, leafy ? 0.3 : 0.0), outA);
            }
            """;

    /**
     * Terrain: photographed grass, sand, rock and pebble textures blended by per-vertex
     * weights (vColor = sand, rock, lush-grass), with a wet band at the waterline.
     */
    private static final String TERRAIN_FS = FS_HEADER + LIGHT_FN + """
            uniform float uClip;
            uniform sampler2D uNoise;
            uniform sampler2D uGrassA;
            uniform sampler2D uGrassB;
            uniform sampler2D uSand;
            uniform sampler2D uRock;
            uniform sampler2D uPebble;
            varying vec3 vColor;
            varying vec3 vNormal;
            varying vec3 vWorld;
            void main() {
              if (uClip > 0.5 && vWorld.y < -0.03) discard;
              vec2 uv = vWorld.xz;
              float macro = texture2D(uNoise, uv * 0.011).r;
              float fine = texture2D(uNoise, uv * 0.37).r;
              // two scales of the meadow texture hide the repetition
              vec3 gA = mix(texture2D(uGrassA, uv * 0.23).rgb, texture2D(uGrassA, uv * 0.041).rgb, 0.45);
              vec3 gB = texture2D(uGrassB, uv * 0.19).rgb;
              vec3 col = mix(gA, gB, smoothstep(0.3, 0.7, vColor.b + (macro - 0.5) * 0.7));
              col *= 0.8 + 0.4 * macro;
              float sandW = smoothstep(0.3, 0.7, vColor.r + (fine - 0.5) * 0.4);
              float rockW = smoothstep(0.3, 0.7, vColor.g + (fine - 0.5) * 0.4);
              col = mix(col, texture2D(uSand, uv * 0.17).rgb, sandW);
              col = mix(col, texture2D(uRock, uv * 0.13).rgb * 0.85, rockW);
              // damp sand at the waterline, pebbly bed under water
              col *= mix(0.6, 1.0, smoothstep(-0.02, 0.24, vWorld.y + (fine - 0.5) * 0.08));
              float bed = 1.0 - smoothstep(-0.8, -0.12, vWorld.y);
              col = mix(col, texture2D(uPebble, uv * 0.31).rgb * vec3(0.62, 0.68, 0.6), bed);
              vec3 n = normalize(vNormal);
              float sh = sunShadow(n);
              gl_FragColor = vec4(lightAndFog(col, n, vWorld, sh, 0.03, 8.0, 0.0), 1.0);
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

    /** Photographed sky panorama (upper hemisphere), blended into the haze at the horizon. */
    private static final String SKY_FS = FS_HEADER + """
            uniform vec3 uFogColor;
            uniform sampler2D uSky;
            varying vec3 vDir;
            void main() {
              vec3 d = normalize(vDir);
              float el = asin(clamp(d.y, -1.0, 1.0)) / 1.5708;
              vec2 uv = vec2(atan(d.z, d.x) * 0.159155 + 0.5, 1.0 - clamp(el, 0.004, 1.0));
              vec3 haze = grade(uFogColor);
              vec3 col = mix(haze, texture2D(uSky, uv).rgb, smoothstep(0.0, 0.05, el));
              gl_FragColor = vec4(col, 1.0);
            }
            """;

    private static final String WATER_VS = """
            uniform mat4 uVP;
            uniform float uTime;
            uniform float uWaveAmp;
            attribute vec3 aPos;
            varying vec3 vWorld;
            void main() {
              vec3 p = aPos;
              p.y = uWaveAmp * (0.035 * sin(p.x * 0.9 + uTime * 1.6) + 0.025 * sin(p.z * 1.3 - uTime * 1.9)
                  + 0.015 * sin((p.x + p.z) * 2.1 + uTime * 2.7));
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
            uniform sampler2D uShadow;
            uniform mat4 uLightVPF;
            uniform float uTexelF;
            uniform float uDepthSpan;
            uniform float uDetailDist;
            varying vec3 vWorld;
            void main() {
              float x = vWorld.x, z = vWorld.z, t = uTimeF;
              float sh = shadowLookup(uShadow, uLightVPF * vec4(vWorld, 1.0), 0.001, uTexelF);
              float dist = length(uCam - vWorld);
              vec3 v = (uCam - vWorld) / dist;
              float dx = 0.0315 * cos(x * 0.9 + t * 1.6) + 0.0315 * cos((x + z) * 2.1 + t * 2.7);
              float dz = 0.0325 * cos(z * 1.3 - t * 1.9) + 0.0315 * cos((x + z) * 2.1 + t * 2.7);
              vec4 a = texture2D(uNormalMap, vWorld.xz * 0.045 + vec2(t * 0.010, t * 0.006));
              vec4 b = texture2D(uNormalMap, vWorld.xz * 0.11 + vec2(-t * 0.008, t * 0.013));
              vec2 ripple = (a.rg * 2.0 - 1.0) + (b.rg * 2.0 - 1.0);
              float detail = 0.28 * clamp(1.0 - dist / uDetailDist, 0.35, 1.0);
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
              float depth = texture2D(uDepth, vWorld.xz / uDepthSpan + 0.5).r * 4.0;
              float sunN = max(dot(n, uLight), 0.0);
              vec3 body = mix(vec3(0.17, 0.43, 0.37), vec3(0.025, 0.11, 0.15), smoothstep(0.0, 2.6, depth));
              body *= 0.55 + 0.55 * sunN * sh;
              vec3 h = normalize(uLight + v);
              float nh = max(dot(n, h), 0.0);
              float spec = (pow(nh, 600.0) * 3.0 + pow(nh, 80.0) * 0.12) * sh;
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

    /** Sun depth pass, packed into RGBA (no depth-texture extension needed). */
    private static final String SHADOW_VS = """
            uniform mat4 uMVP;
            attribute vec3 aPos;
            attribute vec3 aColor;
            varying vec2 vUV;
            varying float vDepth;
            void main() {
              gl_Position = uMVP * vec4(aPos, 1.0);
              vDepth = gl_Position.z * 0.5 + 0.5;
              vUV = aColor.rg;
            }
            """;

    private static final String SHADOW_FS = FS_HEADER + """
            uniform float uFoliage;
            uniform sampler2D uAtlas;
            varying vec2 vUV;
            varying float vDepth;
            void main() {
              if (uFoliage > 0.5 && texture2D(uAtlas, vUV).a < 0.5) discard;
              vec4 enc = fract(min(vDepth, 0.9999) * vec4(1.0, 255.0, 65025.0, 16581375.0));
              enc -= enc.yzww * vec4(1.0 / 255.0, 1.0 / 255.0, 1.0 / 255.0, 0.0);
              gl_FragColor = enc;
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
            uniform float uNoiseScale;
            uniform sampler2D uNoise;
            varying vec3 vExtra;
            varying vec2 vXZ;
            void main() {
              float d = length(vExtra.xy);
              float shape = 1.0 - smoothstep(0.3, 1.0, d);
              float nz = texture2D(uNoise, vXZ * 0.9 * uNoiseScale + vExtra.xy * 0.15).r;
              float a = shape * vExtra.z * mix(1.0, smoothstep(0.25, 0.7, nz) * 2.6, uNoiseAmt);
              gl_FragColor = vec4(uPColor, clamp(a, 0.0, 1.0));
            }
            """;

    // ------------------------------------------------------------------ state

    private final Game game;
    private final android.content.Context context;
    private Scenery scene, rcScene, lifeScene;
    private int texDepthLife;
    /** This frame: life-size world, and the player's cockpit view in it. */
    private boolean lifeNow, cockpitView;
    private int playerIdx = -1;
    private float wheelAngle;
    private final float[] boatFx = new float[Game.COLORS.length];
    private int wWaveAmp, wDepthSpan, wDetailDist, pNoiseScale;
    // terrain program
    private int terProg, tPos, tNormal, tColor, tMVP, tModel, tLightVP, tTint, tLight, tCam, tFogColor,
            tFogDensity, tShadowOn, tTexel, tClip, tNoise, tGrassA, tGrassB, tSand, tRock, tPebble, tShadow;
    private int texGrassA, texGrassB, texSand, texRock, texPebble, texBoulder, texBark, texPlanks, texSky;
    private int uTexL, uTexScale, sSky;
    private int litProg, skyProg, waterProg, partProg;
    private int lPos, lNormal, lColor, uMVP, uModel, uTint, uLight, uCam, uFogColor, uFogDensity,
            uSpec, uShine, uDetail, uClip, uAlpha, uNoiseL;
    private int sPos, sMVP, sFogColor;
    private int wPos, wVP, wTime, wTimeF, wCam, wLight, wFogColor, wFogDensity, wScreen,
            wReflect, wNormalMap, wDepth, wNoise;
    private int pPos, pExtra, pVP, pColor, pNoiseAmt, pNoise;

    private int texNoise, texWaterNormals, texDepthIsland, texDepthNoIsland, texBlack, texAtlas, texWhite;
    private int shadowProg, shPos, shColor, shMVP, shFoliage, shAtlas;
    private int uLightVP, uSway, uTimeV, uShadowOn, uTexel, uAtlasL, uShadowL;
    private int wShadow, wLightVP, wTexel;
    private int shadowTex, shadowSize;
    private boolean shadowOk;
    private final float[] lightVP = Scenery.lightViewProj();
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
    private final Mesh[] kits = new Mesh[Track.COUNT];
    private int boatCount;
    private String clockText = "";
    private boolean clockColon;
    private final float[] markerPos = new float[3];
    private boolean showMarker;
    private float time;
    private float[] wobble = new float[0];
    private final FloatBuffer partBuf, shadowBuf, sprayBuf;
    private int partVerts, shadowVerts, sprayVerts;
    /** Snapshot of the spray: x, y, z, size, alpha per clump. */
    private final float[] sprayData;
    private int sprayCount;

    GameRenderer(android.content.Context context, Game game) {
        this.context = context.getApplicationContext();
        this.game = game;
        partBuf = ByteBuffer.allocateDirect(game.particles.max * 6 * 6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        shadowBuf = ByteBuffer.allocateDirect((Game.COLORS.length + 200) * 6 * 6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        sprayBuf = ByteBuffer.allocateDirect(game.spray.max * 6 * 6 * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        sprayData = new float[game.spray.max * 5];
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
        uLightVP = GLES20.glGetUniformLocation(litProg, "uLightVP");
        uSway = GLES20.glGetUniformLocation(litProg, "uSway");
        uTimeV = GLES20.glGetUniformLocation(litProg, "uTimeV");
        uShadowOn = GLES20.glGetUniformLocation(litProg, "uShadowOn");
        uTexel = GLES20.glGetUniformLocation(litProg, "uTexel");
        uAtlasL = GLES20.glGetUniformLocation(litProg, "uAtlas");
        uShadowL = GLES20.glGetUniformLocation(litProg, "uShadow");
        uTexL = GLES20.glGetUniformLocation(litProg, "uTex");
        uTexScale = GLES20.glGetUniformLocation(litProg, "uTexScale");

        terProg = program(LIT_VS, TERRAIN_FS);
        tPos = GLES20.glGetAttribLocation(terProg, "aPos");
        tNormal = GLES20.glGetAttribLocation(terProg, "aNormal");
        tColor = GLES20.glGetAttribLocation(terProg, "aColor");
        tMVP = GLES20.glGetUniformLocation(terProg, "uMVP");
        tModel = GLES20.glGetUniformLocation(terProg, "uModel");
        tLightVP = GLES20.glGetUniformLocation(terProg, "uLightVP");
        tTint = GLES20.glGetUniformLocation(terProg, "uTint");
        tLight = GLES20.glGetUniformLocation(terProg, "uLight");
        tCam = GLES20.glGetUniformLocation(terProg, "uCam");
        tFogColor = GLES20.glGetUniformLocation(terProg, "uFogColor");
        tFogDensity = GLES20.glGetUniformLocation(terProg, "uFogDensity");
        tShadowOn = GLES20.glGetUniformLocation(terProg, "uShadowOn");
        tTexel = GLES20.glGetUniformLocation(terProg, "uTexel");
        tClip = GLES20.glGetUniformLocation(terProg, "uClip");
        tNoise = GLES20.glGetUniformLocation(terProg, "uNoise");
        tGrassA = GLES20.glGetUniformLocation(terProg, "uGrassA");
        tGrassB = GLES20.glGetUniformLocation(terProg, "uGrassB");
        tSand = GLES20.glGetUniformLocation(terProg, "uSand");
        tRock = GLES20.glGetUniformLocation(terProg, "uRock");
        tPebble = GLES20.glGetUniformLocation(terProg, "uPebble");
        tShadow = GLES20.glGetUniformLocation(terProg, "uShadow");

        shadowProg = program(SHADOW_VS, SHADOW_FS);
        shPos = GLES20.glGetAttribLocation(shadowProg, "aPos");
        shColor = GLES20.glGetAttribLocation(shadowProg, "aColor");
        shMVP = GLES20.glGetUniformLocation(shadowProg, "uMVP");
        shFoliage = GLES20.glGetUniformLocation(shadowProg, "uFoliage");
        shAtlas = GLES20.glGetUniformLocation(shadowProg, "uAtlas");

        skyProg = program(SKY_VS, SKY_FS);
        sPos = GLES20.glGetAttribLocation(skyProg, "aPos");
        sMVP = GLES20.glGetUniformLocation(skyProg, "uMVP");
        sFogColor = GLES20.glGetUniformLocation(skyProg, "uFogColor");
        sSky = GLES20.glGetUniformLocation(skyProg, "uSky");

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
        wWaveAmp = GLES20.glGetUniformLocation(waterProg, "uWaveAmp");
        wDepthSpan = GLES20.glGetUniformLocation(waterProg, "uDepthSpan");
        wDetailDist = GLES20.glGetUniformLocation(waterProg, "uDetailDist");
        wNoise = GLES20.glGetUniformLocation(waterProg, "uNoise");
        wShadow = GLES20.glGetUniformLocation(waterProg, "uShadow");
        wLightVP = GLES20.glGetUniformLocation(waterProg, "uLightVPF");
        wTexel = GLES20.glGetUniformLocation(waterProg, "uTexelF");

        partProg = program(PART_VS, PART_FS);
        pPos = GLES20.glGetAttribLocation(partProg, "aPos");
        pExtra = GLES20.glGetAttribLocation(partProg, "aExtra");
        pVP = GLES20.glGetUniformLocation(partProg, "uVP");
        pColor = GLES20.glGetUniformLocation(partProg, "uPColor");
        pNoiseAmt = GLES20.glGetUniformLocation(partProg, "uNoiseAmt");
        pNoise = GLES20.glGetUniformLocation(partProg, "uNoise");
        pNoiseScale = GLES20.glGetUniformLocation(partProg, "uNoiseScale");

        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        texNoise = texture(TextureGen.noise(), TextureGen.NOISE_N, GLES20.GL_LUMINANCE, true);
        texWaterNormals = texture(TextureGen.waterNormals(), TextureGen.WATER_N, GLES20.GL_RGB, true);
        texDepthIsland = texture(TextureGen.depth(true), TextureGen.DEPTH_N, GLES20.GL_LUMINANCE, false);
        texDepthNoIsland = texture(TextureGen.depth(false), TextureGen.DEPTH_N, GLES20.GL_LUMINANCE, false);
        texBlack = texture(new byte[]{(byte) 190, (byte) 205, (byte) 225}, 1, GLES20.GL_RGB, false);
        texWhite = texture(new byte[]{-1, -1, -1, -1}, 1, GLES20.GL_RGBA, false);
        texAtlas = texture(TextureGen.foliageAtlas(), TextureGen.ATLAS_N, GLES20.GL_RGBA, true);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAtlas);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // photographed textures (CC0, from Poly Haven and ambientCG; see tools/textures/README.md)
        texGrassA = loadTexture("tex/grass_a.jpg", true, 0x5A, 0x6B, 0x30);
        texGrassB = loadTexture("tex/grass_b.jpg", true, 0x45, 0x5C, 0x29);
        texSand = loadTexture("tex/sand.jpg", true, 0xA6, 0x96, 0x70);
        texRock = loadTexture("tex/rock.jpg", true, 0x8C, 0x8C, 0x8A);
        texPebble = loadTexture("tex/pebbles.jpg", true, 0x78, 0x6B, 0x5C);
        texBoulder = loadTexture("tex/boulder.jpg", true, 0x6B, 0x6B, 0x57);
        texBark = loadTexture("tex/bark.jpg", true, 0x66, 0x52, 0x40);
        texPlanks = loadTexture("tex/planks.jpg", true, 0x4F, 0x42, 0x3B);
        texSky = loadTexture("tex/sky.jpg", false, 0x8A, 0xA8, 0xD6);
        reflFbo = 0;

        rcScene = new Scenery(game, false);
        lifeScene = null;
        texDepthLife = 0;
        scene = rcScene;
        java.util.Arrays.fill(gates, null);
        java.util.Arrays.fill(kits, null);
        bakeShadows();

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

    /**
     * Renders the static scenery (hills, trees, platform, houses) once from the sun into a
     * depth map. Boats and buoys keep their soft blob shadows.
     */
    private void bakeShadows() {
        int[] max = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, max, 0);
        int[] maxRb = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_RENDERBUFFER_SIZE, maxRb, 0);
        shadowSize = Math.min(2048, Math.min(max[0], maxRb[0]));
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        shadowTex = id[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, shadowTex);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, shadowSize, shadowSize, 0, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glGenRenderbuffers(1, id, 0);
        int depth = id[0];
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, depth);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_DEPTH_COMPONENT16, shadowSize, shadowSize);
        GLES20.glGenFramebuffers(1, id, 0);
        int fbo = id[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, shadowTex, 0);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT,
                GLES20.GL_RENDERBUFFER, depth);
        shadowOk = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE;
        if (shadowOk) {
            GLES20.glViewport(0, 0, shadowSize, shadowSize);
            GLES20.glClearColor(1f, 1f, 1f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glUseProgram(shadowProg);
            GLES20.glUniformMatrix4fv(shMVP, 1, false, lightVP, 0);
            bindTex(1, texAtlas, shAtlas);
            GLES20.glUniform1f(shFoliage, 0f);
            scene.terrain.draw(shPos, -1, shColor);
            scene.props.draw(shPos, -1, shColor);
            scene.bark.draw(shPos, -1, shColor);
            scene.wood.draw(shPos, -1, shColor);
            scene.rocks.draw(shPos, -1, shColor);
            GLES20.glUniform1f(shFoliage, 1f);
            scene.foliage.draw(shPos, -1, shColor);
            GLES20.glDisableVertexAttribArray(shPos);
            GLES20.glDisableVertexAttribArray(shColor);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        } else {
            Log.w(TAG, "Shadow framebuffer unsupported; shadows disabled");
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        GLES20.glDeleteRenderbuffers(1, new int[]{depth}, 0);
        GLES20.glClearColor(Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2], 1f);
        GLES20.glViewport(0, 0, screenW, screenH);
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
            lifeNow = game.lifeSize;
            playerIdx = -1;
            for (int i = 0; i < boatCount; i++) if (game.boats[i].isPlayer) playerIdx = i;
            cockpitView = lifeNow && game.state != Game.State.MENU && game.view == Game.VIEW_FPV && playerIdx >= 0;
            if (playerIdx >= 0) {
                // the wheel follows the player's steering, smoothed like real hands
                float target = -game.boats[playerIdx].steer * 100f;
                wheelAngle += (target - wheelAngle) * Math.min(1f, dt * 8f);
            }
            islandOn = game.islandVisible();
            boolean shore = game.state != Game.State.MENU && game.view == Game.VIEW_SHORE;
            for (int i = 0; i < boatCount; i++) {
                Boat b = game.boats[i];
                b.modelMatrix(boatM[i], time);
                boatColor[i] = b.id;
                boatHull[i] = b.hull;
                boatFx[i] = b.fx;
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
            clockText = game.clockDigits();
            clockColon = clockText.length() == 3 && clockText.charAt(0) != ' ';
            if (wobble.length != game.buoyWobble.length) wobble = new float[game.buoyWobble.length];
            System.arraycopy(game.buoyWobble, 0, wobble, 0, wobble.length);
            fillParticles(game.particles);
            Spray sp = game.spray;
            int n = 0;
            for (int i = 0; i < sp.count; i++) {
                float a = sp.alphaOf(i);
                if (a < 0.01f) continue;
                int o = n * 5;
                sprayData[o] = sp.x[i]; sprayData[o + 1] = sp.y[i]; sprayData[o + 2] = sp.z[i];
                sprayData[o + 3] = sp.sizeOf(i); sprayData[o + 4] = a;
                n++;
            }
            sprayCount = n;
        }
        if (lifeNow && lifeScene == null) {
            // first visit to the big lake: build it (and its depth map) once
            lifeScene = new Scenery(game, true);
            synchronized (World.class) {
                float old = World.scale;
                World.scale = World.LIFE_SCALE;
                try {
                    texDepthLife = texture(TextureGen.depth(false), TextureGen.DEPTH_N, GLES20.GL_LUMINANCE, false);
                } finally { World.scale = old; }
            }
        }
        scene = lifeNow ? lifeScene : rcScene;
        if (!track.life) {
            if (gates[track.id] == null) gates[track.id] = Scenery.buildGate(track);
            if (track.impba && kits[track.id] == null) kits[track.id] = Scenery.buildCourseKit(track);
        }
        fillShadows();

        Matrix.perspectiveM(proj, 0, cam[9], aspect, cam[10], lifeNow ? 6500f : 700f);
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
        fillSpray();
        drawFlat(sprayBuf, sprayVerts, 0.93f, 0.96f, 1.0f, 0.55f);
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
        GLES20.glUniform3f(sFogColor, Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2]);
        bindTex(0, texSky, sSky);
        scene.sky.draw(sPos, -1, -1);
        GLES20.glDisableVertexAttribArray(sPos);
        GLES20.glDepthMask(true);

        drawTerrain(cx, cy, cz, reflection);

        GLES20.glUseProgram(litProg);
        GLES20.glUniform3f(uLight, Scenery.LIGHT[0], Scenery.LIGHT[1], Scenery.LIGHT[2]);
        GLES20.glUniform3f(uCam, cx, cy, cz);
        GLES20.glUniform3f(uFogColor, Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2]);
        GLES20.glUniform1f(uFogDensity, fogDensity());
        GLES20.glUniform1f(uClip, reflection ? 1f : 0f);
        GLES20.glUniform1f(uAlpha, 1f);
        GLES20.glUniformMatrix4fv(uLightVP, 1, false, lightVP, 0);
        GLES20.glUniform1f(uShadowOn, shadowsOn() ? 1f : 0f);
        GLES20.glUniform1f(uTexel, 1f / Math.max(1, shadowSize));
        GLES20.glUniform1f(uTimeV, time);
        GLES20.glUniform1f(uSway, 0f);
        bindTex(0, texNoise, uNoiseL);
        bindTex(1, texAtlas, uAtlasL);
        bindTex(2, shadowsOn() ? shadowTex : texWhite, uShadowL);

        // photo-textured timber, bark and boulders (triplanar)
        Matrix.setIdentityM(model, 0);
        GLES20.glUniform1f(uDetail, 4f);
        GLES20.glUniform1f(uSpec, 0.06f);
        GLES20.glUniform1f(uShine, 12f);
        bindTex(3, texPlanks, uTexL);
        GLES20.glUniform1f(uTexScale, 0.5f);
        drawLit(scene.wood);
        bindTex(3, texBark, uTexL);
        GLES20.glUniform1f(uTexScale, 0.8f);
        drawLit(scene.bark);
        if (islandOn) drawLit(scene.islandBark);
        bindTex(3, texBoulder, uTexL);
        GLES20.glUniform1f(uTexScale, 0.45f);
        drawLit(scene.rocks);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        material(0.1f, 16f, false);
        GLES20.glUniform1f(uDetail, 2f);
        drawLit(scene.props);
        if (islandOn) drawLit(scene.islandProps);
        // alpha-tested foliage cards, softly anti-aliased via alpha-to-coverage. The blurred
        // water reflection only needs the trees' solid cores, which saves a lot of work.
        if (!reflection) {
            material(0.04f, 10f, false);
            GLES20.glUniform1f(uDetail, 3f);
            GLES20.glUniform1f(uSway, 0.22f);
            GLES20.glEnable(GLES20.GL_SAMPLE_ALPHA_TO_COVERAGE);
            drawLit(scene.foliage);
            if (islandOn) drawLit(scene.islandFoliage);
            GLES20.glDisable(GLES20.GL_SAMPLE_ALPHA_TO_COVERAGE);
            GLES20.glUniform1f(uSway, 0f);
        }
        material(0.35f, 40f, false);
        if (!track.impba) drawLit(gates[track.id]);
        else if (!track.life) {
            if (kits[track.id] != null) drawLit(kits[track.id]);
            drawClock();
        }

        // RC drivers on the platform
        material(0.15f, 12f, false);
        for (int i = 0; i < boatCount; i++) {
            if (!figureVisible[i] || lifeNow) continue;
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
            if (track.buoyScale != 1f) Matrix.scaleM(model, 0, track.buoyScale, track.buoyScale, track.buoyScale);
            int type = (int) bu[2];
            drawLit(type == Track.BUOY_LEFT ? scene.buoyRed : type == Track.BUOY_RIGHT ? scene.buoyYellow
                    : type == Track.BUOY_TURN ? scene.buoyTurn : scene.buoyPlain);
        }

        // boats: glossy gelcoat
        material(0.9f, 90f, false);
        for (int i = 0; i < boatCount; i++) {
            System.arraycopy(boatM[i], 0, model, 0, 16);
            Mesh m = scene.boats[boatHull[i]][boatColor[i]];
            if (m != null) drawLit(m);
            if (boatHull[i] == Boat.UNLIMITED && lifeScene != null) {
                boolean mine = cockpitView && i == playerIdx;
                if (!mine) {
                    material(1.0f, 120f, false);
                    drawLit(lifeScene.lifeCanopy);
                    material(0.9f, 90f, false);
                } else if (!reflection) {
                    drawCockpit(i);
                    material(0.9f, 90f, false);
                }
            }
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

    private void drawTerrain(float cx, float cy, float cz, boolean reflection) {
        GLES20.glUseProgram(terProg);
        GLES20.glUniform3f(tLight, Scenery.LIGHT[0], Scenery.LIGHT[1], Scenery.LIGHT[2]);
        GLES20.glUniform3f(tCam, cx, cy, cz);
        GLES20.glUniform3f(tFogColor, Scenery.FOG[0], Scenery.FOG[1], Scenery.FOG[2]);
        GLES20.glUniform1f(tFogDensity, fogDensity());
        GLES20.glUniform1f(tClip, reflection ? 1f : 0f);
        GLES20.glUniform1f(tShadowOn, shadowsOn() ? 1f : 0f);
        GLES20.glUniform1f(tTexel, 1f / Math.max(1, shadowSize));
        GLES20.glUniform3f(tTint, 1f, 1f, 1f);
        GLES20.glUniformMatrix4fv(tLightVP, 1, false, lightVP, 0);
        bindTex(0, texNoise, tNoise);
        bindTex(1, texGrassA, tGrassA);
        bindTex(2, texGrassB, tGrassB);
        bindTex(3, texSand, tSand);
        bindTex(4, texRock, tRock);
        bindTex(5, texPebble, tPebble);
        bindTex(6, shadowsOn() ? shadowTex : texWhite, tShadow);
        Matrix.setIdentityM(model, 0);
        GLES20.glUniformMatrix4fv(tMVP, 1, false, curVP, 0);
        GLES20.glUniformMatrix4fv(tModel, 1, false, model, 0);
        scene.terrain.draw(tPos, tNormal, tColor);
        if (islandOn) scene.island.draw(tPos, tNormal, tColor);
        GLES20.glDisableVertexAttribArray(tPos);
        GLES20.glDisableVertexAttribArray(tNormal);
        GLES20.glDisableVertexAttribArray(tColor);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
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
        GLES20.glUniform1f(wFogDensity, fogDensity());
        GLES20.glUniform1f(wWaveAmp, lifeNow ? 0f : 1f);
        GLES20.glUniform1f(wDepthSpan, World.TERRAIN_HALF * 2f * (lifeNow ? World.LIFE_SCALE : 1f));
        GLES20.glUniform1f(wDetailDist, lifeNow ? 700f : 140f);
        GLES20.glUniform2f(wScreen, screenW, screenH);
        bindTex(0, texNoise, wNoise);
        bindTex(1, texWaterNormals, wNormalMap);
        bindTex(2, lifeNow ? texDepthLife : islandOn ? texDepthIsland : texDepthNoIsland, wDepth);
        bindTex(3, reflOk ? reflTex : texBlack, wReflect);
        bindTex(4, shadowsOn() ? shadowTex : texWhite, wShadow);
        GLES20.glUniformMatrix4fv(wLightVP, 1, false, lightVP, 0);
        GLES20.glUniform1f(wTexel, 1f / Math.max(1, shadowSize));
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
        GLES20.glUniform1f(pNoiseScale, lifeNow ? 0.13f : 1f);
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

    /** Rooster-tail clumps as camera-facing quads (built from the view matrix's axes). */
    private void fillSpray() {
        float rxv = view[0], ryv = view[4], rzv = view[8];   // camera right
        float uxv = view[1], uyv = view[5], uzv = view[9];   // camera up
        sprayBuf.position(0);
        int v = 0;
        float[][] c = {{-1, -1}, {1, -1}, {1, 1}, {-1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < sprayCount; i++) {
            int o = i * 5;
            float x = sprayData[o], y = sprayData[o + 1], z = sprayData[o + 2], s = sprayData[o + 3],
                    a = sprayData[o + 4];
            for (float[] k : c) {
                sprayBuf.put(x + (rxv * k[0] + uxv * k[1]) * s).put(y + (ryv * k[0] + uyv * k[1]) * s)
                        .put(z + (rzv * k[0] + uzv * k[1]) * s).put(k[0]).put(k[1]).put(a);
            }
            v += 6;
        }
        sprayVerts = v;
        sprayBuf.position(0);
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

    // 7-segment layout: segments a-g as x, y (centre, in digit heights) and horizontal or not
    private static final float[][] SEG = {
            {0, 1f, 1}, {0.28f, 0.75f, 0}, {0.28f, 0.25f, 0}, {0, 0, 1}, {-0.28f, 0.25f, 0},
            {-0.28f, 0.75f, 0}, {0, 0.5f, 1}};
    private static final int[] DIGIT_SEGS = {0x3F, 0x06, 0x5B, 0x4F, 0x66, 0x6D, 0x7D, 0x07, 0x7F, 0x6F};

    /** The floating start clock in the infield and its digits (IMPBA ovals). */
    private void drawClock() {
        Matrix.setIdentityM(model, 0);
        float wy = World.wave(track.clockX, track.clockZ, time) * 0.5f;
        Matrix.translateM(model, 0, track.clockX, wy, track.clockZ);
        drawLit(scene.clockBody);
        float[] base = new float[16];
        System.arraycopy(model, 0, base, 0, 16);
        String txt = clockText;
        float h = Scenery.DIGIT_H, w = h * 2f / 3f;
        float y0 = Scenery.CLOCK_Y0 + (Scenery.CLOCK_H - h) / 2f;
        material(0.2f, 20f, false);
        for (int d = 0; d < 3; d++) {
            char ch = d < txt.length() ? txt.charAt(d) : ' ';
            if (ch < '0' || ch > '9') continue;
            int bits = DIGIT_SEGS[ch - '0'];
            float cx = (d - 1) * (w + 0.12f) + (d == 0 ? -0.08f : 0f);
            for (int sgi = 0; sgi < 7; sgi++) {
                if ((bits & (1 << sgi)) == 0) continue;
                float[] sg = SEG[sgi];
                System.arraycopy(base, 0, model, 0, 16);
                Matrix.translateM(model, 0, cx + sg[0] * w * 1.4f, y0 + sg[1] * h, 0.08f);
                if (sg[2] > 0) Matrix.scaleM(model, 0, w * 0.62f, h * 0.09f, 0.02f);
                else Matrix.scaleM(model, 0, w * 0.13f, h * 0.42f, 0.02f);
                drawLit(scene.segment);
            }
        }
        if (clockColon) {
            for (int k = 0; k < 2; k++) {
                System.arraycopy(base, 0, model, 0, 16);
                Matrix.translateM(model, 0, -(w + 0.12f) / 2f - 0.04f, y0 + h * (0.3f + 0.4f * k), 0.08f);
                Matrix.scaleM(model, 0, 0.06f, 0.06f, 0.02f);
                drawLit(scene.segment);
            }
        }
        Matrix.setIdentityM(model, 0);
        material(0.35f, 40f, false);
    }

    private float fogDensity() { return lifeNow ? FOG_DENSITY * 0.17f : FOG_DENSITY; }

    /** The baked sun shadows only cover the RC lake. */
    private boolean shadowsOn() { return shadowOk && !lifeNow; }

    private final float[] tmpA = new float[16], tmpB = new float[16];
    private final float[] hand = new float[4], handW = new float[4];

    /**
     * Life-size cockpit view: dash, canopy frame and seat, the steering wheel turning with
     * the player's steering, and the driver's arms reaching to gloved hands on the rim.
     */
    private void drawCockpit(int i) {
        float[] bm = boatM[i];
        material(0.25f, 20f, false);
        System.arraycopy(bm, 0, model, 0, 16);
        drawLit(lifeScene.cockpit);
        // the wheel: at its hub, tilted back towards the driver, turned by the steering
        float[] W = Scenery.WHEEL;
        Matrix.setIdentityM(tmpA, 0);
        Matrix.translateM(tmpA, 0, W[0], W[1], W[2]);
        Matrix.rotateM(tmpA, 0, -(90f - Scenery.WHEEL_TILT), 1, 0, 0);
        Matrix.rotateM(tmpA, 0, wheelAngle, 0, 0, 1);
        Matrix.multiplyMM(model, 0, bm, 0, tmpA, 0);
        material(0.4f, 40f, false);
        drawLit(lifeScene.wheel);
        // hands at "ten to two" on the rim, turning with it
        material(0.15f, 10f, false);
        for (int s = -1; s <= 1; s += 2) {
            double a = Math.toRadians(s < 0 ? 160 : 20);
            hand[0] = Scenery.WHEEL_R * (float) Math.cos(a);
            hand[1] = Scenery.WHEEL_R * (float) Math.sin(a);
            hand[2] = 0.02f;
            hand[3] = 1f;
            Matrix.multiplyMV(handW, 0, tmpA, 0, hand, 0);   // boat coordinates
            float[] sh = s < 0 ? Scenery.SHOULDER_L : Scenery.SHOULDER_R;
            // elbow: between shoulder and hand, dropped and pushed outwards
            float ex = (sh[0] + handW[0]) * 0.5f + s * 0.1f;
            float ey = (sh[1] + handW[1]) * 0.5f - 0.1f;
            float ez = (sh[2] + handW[2]) * 0.5f + 0.02f;
            limb(bm, sh[0], sh[1], sh[2], ex, ey, ez, 0.075f);
            limb(bm, ex, ey, ez, handW[0], handW[1], handW[2], 0.06f);
            Matrix.setIdentityM(tmpB, 0);
            Matrix.translateM(tmpB, 0, handW[0], handW[1], handW[2]);
            Matrix.scaleM(tmpB, 0, 0.045f, 0.04f, 0.055f);
            Matrix.multiplyMM(model, 0, bm, 0, tmpB, 0);
            drawLit(lifeScene.glove);
        }
    }

    /** A box of square cross-section th from point a to point b (boat coordinates). */
    private void limb(float[] bm, float ax, float ay, float az, float bx, float by, float bz, float th) {
        float dx = bx - ax, dy = by - ay, dz = bz - az;
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        float ux = dx / len, uy = dy / len, uz = dz / len;
        // two unit vectors perpendicular to the limb
        float px = uy * 0 - uz * 1, py = uz * 0 - ux * 0, pz = ux * 1 - uy * 0;   // u x (0,1,0)... fallback below
        float pl = (float) Math.sqrt(px * px + py * py + pz * pz);
        if (pl < 1e-3f) { px = 1; py = 0; pz = 0; pl = 1; }
        px /= pl; py /= pl; pz /= pl;
        float qx = uy * pz - uz * py, qy = uz * px - ux * pz, qz = ux * py - uy * px;
        float[] m = tmpB;
        m[0] = px * th; m[1] = py * th; m[2] = pz * th; m[3] = 0;
        m[4] = ux * len; m[5] = uy * len; m[6] = uz * len; m[7] = 0;
        m[8] = qx * th; m[9] = qy * th; m[10] = qz * th; m[11] = 0;
        m[12] = (ax + bx) / 2; m[13] = (ay + by) / 2; m[14] = (az + bz) / 2; m[15] = 1;
        Matrix.multiplyMM(model, 0, bm, 0, m, 0);
        drawLit(lifeScene.unitBox);
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
            float k = boatHull[i] == Boat.UNLIMITED ? 5.4f : boatFx[i];
            quad(shadowBuf, x, y, z, fx * 0.85f * k, fz * 0.85f * k, -fz * 0.42f * k * 1.5f, fx * 0.42f * k * 1.5f, 0.5f);
            v += 6;
        }
        java.util.ArrayList<float[]> buoys = track.buoys;
        for (int i = 0; i < buoys.size() && v / 6 < Game.COLORS.length + 200; i++) {
            float[] bu = buoys.get(i);
            float x = bu[0] + sx * 0.3f, z = bu[1] + sz * 0.3f;
            float bs = track.buoyScale;
            quad(shadowBuf, x, World.wave(x, z, time) + 0.04f, z, 0.6f * bs, 0f, 0f, 0.6f * bs, 0.38f);
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

    /**
     * Loads an image from the app's assets. Tiling textures repeat and get mipmaps (plus
     * anisotropic filtering where available, which keeps the ground sharp at shallow angles);
     * otherwise it wraps horizontally only (the sky panorama). Falls back to a flat colour.
     */
    private int loadTexture(String asset, boolean tiling, int r, int g, int b) {
        android.graphics.Bitmap bmp = null;
        try (java.io.InputStream in = context.getAssets().open(asset)) {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inScaled = false;
            bmp = android.graphics.BitmapFactory.decodeStream(in, null, o);
        } catch (java.io.IOException e) {
            Log.w(TAG, "Missing texture " + asset);
        }
        if (bmp == null) return texture(new byte[]{(byte) r, (byte) g, (byte) b}, 1, GLES20.GL_RGB, false);
        int[] id = new int[1];
        GLES20.glGenTextures(1, id, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id[0]);
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        bmp.recycle();
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T,
                tiling ? GLES20.GL_REPEAT : GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        if (tiling) {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
            GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
            String ext = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            if (ext != null && ext.contains("texture_filter_anisotropic")) {
                GLES20.glTexParameterf(GLES20.GL_TEXTURE_2D, 0x84FE /* TEXTURE_MAX_ANISOTROPY_EXT */, 4f);
            }
        } else {
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        }
        return id[0];
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
