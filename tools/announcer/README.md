# Race announcer

The announcer clips in `app/src/main/assets/announcer/<key>.ogg` are synthetic speech made with
[Maya1](https://huggingface.co/maya-research/maya1) (Apache 2.0), a text-to-speech model whose
voice is chosen by a written description rather than copied from a person, and decoded with
[SNAC](https://github.com/hubertsiuzdak/snac) 24 kHz (MIT). The script and voice description are in
`maya/lines.py`.

## Rebuilding

1. Get `maya1-q4_k_m.gguf` from <https://huggingface.co/Mungert/maya1-GGUF> (or run the
   "Fetch voice model" GitHub workflow, which puts it in a `voice-model` release), and
   `config.json` + `pytorch_model.bin` from <https://huggingface.co/hubertsiuzdak/snac_24khz>
   into `/tmp/maya/snac`.
2. Build [llama.cpp](https://github.com/ggml-org/llama.cpp) and start
   `llama-server -m maya1-q4_k_m.gguf --port 8099`.
3. `pip install sherpa-onnx` and unpack sherpa-onnx's `whisper-base.en` model in `/tmp/asr`
   (used to check every line was spoken correctly).
4. Render, encode, and regenerate the duration table:

```
python3 maya/render_all.py /tmp/maya/raw          # skips lines already rendered
python3 maya/encode.py /tmp/maya/raw ../../app/src/main/assets/announcer
python3 make_table.py ../../app/src/main/assets/announcer ../../app/src/main/java/com/rcboat/racing/AnnouncerClips.java
```

`maya/snacnp.py` is a small NumPy port of the SNAC decoder, so no PyTorch is needed.
