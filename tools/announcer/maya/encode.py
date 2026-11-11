"""Turn rendered Maya1 lines (RAW_DIR/<key>.wav) into the game's clips.

Usage: python3 encode.py RAW_DIR OUT_DIR   (OUT_DIR is cleared of .ogg files first)
Each line is trimmed, levelled to a common loudness and written as Ogg Opus.
Shared lines are copied to every key that uses them.
"""
import os, sys, subprocess, tempfile
import numpy as np, soundfile as sf
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from lines import LINES, OPP

TARGET_RMS = 0.12

def files():
    m = {k: k for k in LINES if k not in ("lead_any", "lead_5_soda")}
    for i in OPP:
        m["lead_%d_1" % i] = "lead_any"
    m["lead_5_2"] = "lead_5_soda"
    return m

def main():
    raw, out = sys.argv[1], sys.argv[2]
    for f in os.listdir(out):
        if f.endswith(".ogg"): os.remove(os.path.join(out, f))
    for key, src in sorted(files().items()):
        a, sr = sf.read(os.path.join(raw, src + ".wav"), dtype="float32")
        loud = np.where(np.abs(a) > 0.03 * np.abs(a).max())[0]
        a = a[max(0, loud[0] - int(0.02 * sr)): loud[-1] + int(0.08 * sr)]
        fade = int(0.02 * sr); a[-fade:] *= np.linspace(1, 0, fade)
        rms = float(np.sqrt(np.mean(a[np.abs(a) > 0.05 * np.abs(a).max()] ** 2)))
        a = a * (TARGET_RMS / max(rms, 1e-6))
        peak = float(np.abs(a).max())
        if peak > 0.97: a *= 0.97 / peak
        with tempfile.NamedTemporaryFile(suffix=".wav") as t:
            sf.write(t.name, a, sr)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", t.name, "-c:a", "libopus", "-b:a", "40k",
                            os.path.join(out, key + ".ogg")], check=True)
    print(len(files()), "clips written")

if __name__ == "__main__":
    main()
