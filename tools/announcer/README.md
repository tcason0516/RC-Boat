# Race announcer

The announcer is **"AAron"**, a free, open voice pack of single recorded words by voice actor
**Aaron Batic** (AZZURRO), released "for use in the development of games ... for any purpose
by anybody" under a Creative Commons licence: <https://azzurrobatic.itch.io/aaron>.

`build_aaron.py` turns the pack's words into the race calls in
`app/src/main/assets/announcer/<key>.ogg` (e.g. `togo_2` = "2. Remaining."). Words are only
trimmed, levelled to the same loudness and joined with short pauses. The pack itself is not
stored in this repo. To rebuild after editing `CALLS`:

```
python3 build_aaron.py path/to/AARON-AustralianAnnouncer ../../app/src/main/assets/announcer
python3 make_table.py ../../app/src/main/assets/announcer ../../app/src/main/java/com/rcboat/racing/AnnouncerClips.java
```
