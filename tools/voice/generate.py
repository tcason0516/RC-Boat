"""Renders the opponents' trash-talk lines with the Kokoro-82M TTS model (Apache-2.0).

Usage: python3 generate.py MODEL.onnx VOICES.npz OUT_DIR [worker_index worker_count]
Writes OUT_DIR/<voice_index>/<line_index>.ogg (Ogg Opus, mono). Existing files are skipped.
"""
import os, sys, subprocess, tempfile
import numpy as np, soundfile as sf
from kokoro_onnx import Kokoro

VOICES = ["am_michael", "am_fenrir", "am_puck", "af_bella", "af_nicole"]   # Mike, Travis, Cody, Bella, Nicole
SPEED = [1.05, 1.05, 1.08, 1.05, 1.08]
# A few lines are spelled differently so they are spoken naturally.
SPOKEN = {
    "GG, Dan. Mostly G for me, though.": "G G, Dan. Mostly G for me, though.",
    "Dan, the buoys are the things you go AROUND.": "Dan, the buoys are the things you go around!",
    "Dan! DAN! The race is THAT way!": "Dan! Dan!! The race is that way!",
    "Dan's GPS is set to \"Somewhere Else.\"": "Dan's G P S is set to: somewhere else.",
    "Dan, the water's over here! We miss you! (We don't.)": "Dan, the water's over here! We miss you! ... We don't.",
    "Dan's not last, he's just… thoroughly behind everyone.": "Dan's not last, he's just... thoroughly behind everyone.",
}

def main():
    model, voices, out = sys.argv[1:4]
    wi, wn = (int(sys.argv[4]), int(sys.argv[5])) if len(sys.argv) > 5 else (0, 1)
    here = os.path.dirname(os.path.abspath(__file__))
    lines = [l.rstrip("\n") for l in open(os.path.join(here, "lines.txt"), encoding="utf-8") if l.strip()]
    k = Kokoro(model, voices)
    jobs = [(v, i) for i in range(len(lines)) for v in range(len(VOICES))]
    for n, (v, i) in enumerate(jobs):
        if n % wn != wi:
            continue
        dst = os.path.join(out, str(v), "%03d.ogg" % i)
        if os.path.exists(dst):
            continue
        os.makedirs(os.path.dirname(dst), exist_ok=True)
        text = SPOKEN.get(lines[i], lines[i])
        audio, sr = k.create(text, voice=VOICES[v], speed=SPEED[v], lang="en-us")
        # trim leading/trailing silence, normalise peak
        idx = np.where(np.abs(audio) > 0.01)[0]
        if len(idx):
            audio = audio[max(0, idx[0] - 1200): idx[-1] + 2400]
        audio = audio / max(1e-6, float(np.abs(audio).max())) * 0.9
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            sf.write(tmp.name, audio, sr)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", tmp.name, "-ac", "1",
                            "-c:a", "libopus", "-b:a", "28k", "-application", "voip", dst + ".part.ogg"], check=True)
            os.replace(dst + ".part.ogg", dst)
            os.unlink(tmp.name)
        print("done", v, i, flush=True)

if __name__ == "__main__":
    main()
