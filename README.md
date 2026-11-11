# RC Boat Racing 🚤

A 3D radio-controlled boat racing game for Android. Race up to 5 computer-controlled
opponents around a buoy-marked course on a lake, with a lighthouse island in the middle.

## Install on your phone

1. On your Android phone, open the **[latest release](../../releases/tag/latest)** page.
2. Tap **RCBoatRacing.apk** to download it.
3. Open the downloaded file. If Android asks, allow your browser / file manager to
   *install unknown apps*, then tap **Install**.

Every push to `main` rebuilds the APK automatically (see the *Actions* tab) and updates
that release. New builds install over the old one.

## How to play

- **Two sticks (default):** left stick = throttle (up forward, down reverse/brake),
  right stick = steering.
- **One stick:** a single stick on the left — up/down for throttle, left/right to steer.
- Stay between the **red** and **yellow** buoys. If you leave the course for too long
  you are put back on it.
- Tap **SHORE / BOAT** at the top to switch camera at any time:
  - **Shore view** – you stand on the dock and turn to follow your boat around the lake,
    like a real RC driver. A green arrow floats over your boat. Steering is from the
    boat's point of view, so it reverses when the boat comes towards you — just like
    the real thing.
  - **Boat view** – first person from the deck of your RC boat.
- Menu options: number of opponents (0–5), laps, difficulty, camera, controls, sound.

## Building yourself

Requires JDK 17 and the Android SDK (API 34).

```
./gradlew assembleRelease
```

The APK ends up in `app/build/outputs/apk/release/`. The game is written in plain Java
with OpenGL ES 2.0 and has no third-party dependencies.

| File | What it does |
| --- | --- |
| `World.java` | Lake/island/hills height field and water waves |
| `Track.java` | Course spline and buoy placement |
| `Boat.java` | Boat physics and AI driver |
| `Game.java` | Race rules, collisions, laps, positions, cameras |
| `Scenery.java` | Builds the 3D meshes (terrain, trees, dock, lighthouse, boats, buoys) |
| `GameRenderer.java` | OpenGL renderer and shaders |
| `HudView.java` | Menu, HUD, minimap and on-screen joysticks |
| `EngineSound.java` | Synthesised motor sound and countdown beeps |

The signing key in `keystore/` is a shared, non-secret key used only so that updated
builds can be installed over earlier ones.
