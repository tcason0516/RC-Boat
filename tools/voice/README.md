# Opponent voices

The trash-talk clips in `app/src/main/assets/voice/<n>/<line>.ogg` were rendered with
[Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) (Apache-2.0), an open-weight
text-to-speech model, using these American English male voices, then pitched down and
given a gritty treatment (chest EQ, rasp presence, tanh saturation, compression) in
`generate.py`:

| Folder | Driver | Boat | Kokoro voice | Pitch ratio |
| --- | --- | --- | --- | --- |
| 0 | Mike | Blue Thunder | am_michael | 0.85 |
| 1 | Travis | Green Machine | am_echo | 0.88 |
| 2 | Cody | Yellow Jacket | am_puck | 0.88 |
| 3 | Hank | Purple Haze | am_onyx | 0.91 |
| 4 | Duke | Orange Crush | am_adam | 0.80 |

`lines.txt` holds the 100 lines (line N = file N-1). To re-render after editing lines:

```
pip install kokoro-onnx soundfile        # also needs ffmpeg with libopus and rubberband
python3 generate.py kokoro-q8.onnx voices.npz ../../app/src/main/assets/voice
```

The model weights (`model_quantized.onnx` from onnx-community/Kokoro-82M-v1.0-ONNX) and voice
files are not stored in this repo; delete the clips you want regenerated first.
