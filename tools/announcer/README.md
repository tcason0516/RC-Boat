# Race announcers

The clips in `app/src/main/assets/announcer/<key>.ogg` were rendered with the open-weight
[Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) text-to-speech model (Apache-2.0),
unmodified (clips are only trimmed and volume-levelled):

| Role | Kokoro voice |
| --- | --- |
| Play-by-play (`A` in `lines.txt`) | am_michael |
| Color commentator (`B`) | am_onyx |

`lines.txt` lists every line as `key|voice|text`. After editing it:

```
pip install kokoro-onnx soundfile        # also needs ffmpeg with libopus
python3 generate.py kokoro-q8.onnx voices.npz ../../app/src/main/assets/announcer
python3 make_table.py ../../app/src/main/assets/announcer ../../app/src/main/java/com/rcboat/racing/AnnouncerClips.java
```

Delete a clip to have it re-rendered. The model weights are not stored in this repo.
