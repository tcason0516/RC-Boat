# Opponent voices

The trash-talk clips in `app/src/main/assets/voice/<n>/<line>.ogg` were rendered with
[Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0), an open-weight
text-to-speech model, using these American English voices:

| Folder | Driver | Boat | Kokoro voice |
| --- | --- | --- | --- |
| 0 | Mike | Blue Thunder | am_michael |
| 1 | Travis | Green Machine | am_fenrir |
| 2 | Cody | Yellow Jacket | am_puck |
| 3 | Bella | Purple Haze | af_bella |
| 4 | Nicole | Orange Crush | af_nicole |

`lines.txt` holds the 100 lines (line N = file N-1). To re-render after editing lines:

```
pip install kokoro-onnx soundfile        # also needs ffmpeg with libopus
python3 generate.py kokoro-q8.onnx voices.npz ../../app/src/main/assets/voice
```

The model weights (`model_quantized.onnx` from onnx-community/Kokoro-82M-v1.0-ONNX) and voice
files are not stored in this repo; delete the clips you want regenerated first.
