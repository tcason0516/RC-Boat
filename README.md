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
  Serpentine, Dog Bone, Clover, Speedway, Heart, plus the three official IMPBA courses at
  their real sizes: the **1/3 mile oval** (880 ft around, 35 ft turns), the **1/4 mile oval**
  (660 ft, 15 ft turns) and the **1/5 mile electric oval** (528.5 ft, 30 ft turns). On these
  the six numbered orange turn buoys mark the inside of the turns. As in the rule book, the
  course runs clockwise with right turns only: keep the buoys on your right. A floating digital start clock sits in the infield.
- **Power:** Electric (instant throttle) or one of the IMPBA Large Scale Gas engine classes:
  Super Sport (25.5 cc spec engine), LSG27, LSG36 or TLSG64 twin. Bigger engines are faster;
  gas engines take a moment to rev up and sound like a two-stroke. Opponents use your class.
- **Start:** *Standing* (3-2-1 from the line), or the IMPBA **clock start**: boats run while
  the clock counts down and must reach the start line just after zero. *Clock, full* uses the
  rule book's 2.5 minutes (gas) or 60 seconds (electric); *Clock, short* uses 30 s / 15 s.
  Crossing the line in the last 5 seconds is a jump start.
- **Buoy rule:** *Just for fun* (no penalties), *Time penalty* (+3 s) or *Lap penalty*
  (+1 lap) for each buoy hit, buoy cut (passing inside a turn buoy) or jump start.
- **Announcer:** calls the race out loud — who's in the lead, places gained and lost, buoy
  hits, going off course, laps to go and the winner. Voice only; turn it off with
  *Announcer* in the menu.
- Menu options also include opponents (0–5), laps, difficulty, controls and sound.

## Credits

Ground, rock, bark, plank and sky photographs are public-domain (CC0) assets from
[Poly Haven](https://polyhaven.com) and [ambientCG](https://ambientcg.com).
See `tools/textures/README.md` for the list.

Announcer voice: generated with the [Maya1](https://huggingface.co/maya-research/maya1) text-to-speech
model (Apache 2.0) and the [SNAC](https://github.com/hubertsiuzdak/snac) audio codec (MIT).
See `tools/announcer/README.md`.

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
