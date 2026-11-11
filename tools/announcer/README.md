# Race announcer

The announcer voice is the **Announcer Voice Pack** by **Dan Bosley** (@daniconshow),
<https://daniconshow.itch.io/basic-announcer-voice-pack>. Its licence allows use in
commercial games with credit, and asks that the pack itself not be redistributed or resold.

`build_voicepack.py` turns the pack's "Dry" clips into the race calls in
`app/src/main/assets/announcer/<key>.ogg` (e.g. `lead_1` = "Player 2 ... is in the lead").
Clips are only trimmed, levelled to the same loudness and joined with short pauses. Only the
clips the game uses are included here, not the pack. To rebuild after editing `CALLS`:

```
python3 build_voicepack.py "path/to/Announcer Voice Pack/Dry" ../../app/src/main/assets/announcer
python3 make_table.py ../../app/src/main/assets/announcer ../../app/src/main/java/com/rcboat/racing/AnnouncerClips.java
```
