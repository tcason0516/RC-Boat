"""Renders the race announcers' lines with the Kokoro-82M TTS model (Apache-2.0).

Usage: python3 generate.py MODEL.onnx VOICES.npz OUT_DIR [worker_index worker_count]
Reads lines.txt (key|voice|text) and writes OUT_DIR/<key>.ogg (Ogg Opus, mono).
Voices are used unmodified: clips are only trimmed and volume-levelled.
"""
import os, sys, subprocess, tempfile
import numpy as np, soundfile as sf
from kokoro_onnx import Kokoro

VOICE = {"A": ("am_michael", 1.08),   # play-by-play
         "B": ("am_onyx", 1.0)}       # color commentator

def main():
    model, voices, out = sys.argv[1:4]
    wi, wn = (int(sys.argv[4]), int(sys.argv[5])) if len(sys.argv) > 5 else (0, 1)
    here = os.path.dirname(os.path.abspath(__file__))
    lines = [l.rstrip("\n").split("|", 2) for l in open(os.path.join(here, "lines.txt"), encoding="utf-8") if l.strip()]
    k = Kokoro(model, voices)
    os.makedirs(out, exist_ok=True)
    for n, (key, voice, text) in enumerate(lines):
        dst = os.path.join(out, key + ".ogg")
        if n % wn != wi or os.path.exists(dst):
            continue
        name, speed = VOICE[voice]
        audio, sr = k.create(text, voice=name, speed=speed, lang="en-us")
        idx = np.where(np.abs(audio) > 0.01)[0]
        if len(idx):
            audio = audio[max(0, idx[0] - 1200): idx[-1] + 2400]
        audio = audio / max(1e-6, float(np.abs(audio).max())) * 0.97
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            sf.write(tmp.name, audio, sr)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp.name, "-ac", "1",
                            "-c:a", "libopus", "-b:a", "32k", "-application", "voip", dst + ".part.ogg"], check=True)
            os.replace(dst + ".part.ogg", dst)
            os.unlink(tmp.name)
        print("done", key, round(len(audio) / sr, 2), flush=True)

if __name__ == "__main__":
    main()
