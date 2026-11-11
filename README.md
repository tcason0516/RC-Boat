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
  right stick = steering. **One stick:** up/down for throttle, left/right to steer.
- Stay between the **red** and **yellow** buoys. If you leave the course for too long
  you are put back on it.
- Tap the view button at the top (**SHORE / BOAT / CHASE**) to switch camera any time:
  - **Shore** – you stand with the other drivers on a 5 ft drivers' platform on the
    shore and turn to follow your boat (green arrow). The island is removed in this
    view so nothing blocks your line of sight. Steering is from the boat's point of
    view, so it reverses when the boat comes towards you — just like real RC.
  - **Boat** – stabilised first person from the deck.
  - **Chase** – third person, behind and above your boat.
- **Boats:** V-Hull (balanced), Hydroplane (fastest, slides in turns),
  Outrigger (grippy, sharpest turning). Opponents race the same type of boat you pick.
- **Tracks:** Island Loop, Grand Oval, Kidney Bean, Figure Eight, Triangle,
  Serpentine, Dog Bone, Clover, Speedway, Heart.
- **Announcer:** a real voice actor counts down "Ready... 3, 2, 1, Go" and calls the
  race out loud — who's in the lead (you are Player 1), places gained and lost, buoy hits,
  going off course, and the winner. Voice only; turn it off with *Announcer* in the menu.
- Menu options also include opponents (0–5), laps, difficulty, controls and sound.

## Credits

Ground, rock, bark, plank and sky photographs are public-domain (CC0) assets from
[Poly Haven](https://polyhaven.com) and [ambientCG](https://ambientcg.com).
See `tools/textures/README.md` for the list.

Announcer voice: "Announcer Voice Pack" by Dan Bosley (@daniconshow),
<https://daniconshow.itch.io/basic-announcer-voice-pack>. See `tools/announcer/README.md`.

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
