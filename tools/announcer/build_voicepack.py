"""Builds the announcer's race calls from Dan Bosley's (@daniconshow) "Announcer Voice Pack".

Usage: python3 build_voicepack.py PACK_DIR OUT_DIR
PACK_DIR is the pack's "Dry" folder (the "Effect" versions trail a 5 s echo). Each call
below is a list of clips; they are trimmed, levelled to the same loudness and joined with
short pauses (no other processing), then written as OUT_DIR/<key>.ogg (Ogg Opus, mono).
"""
import os, sys, subprocess, tempfile
import numpy as np, soundfile as sf

CALLS = {
    # start and countdown
    "start_0": ["Ready"],
    "count_3": ["3"], "count_2": ["2"], "count_1": ["1"], "go": ["Go"],
    # lead changes. The player is "Player 1"; opponents 1-3 are "Player 2"-"Player 4"
    "leadme_0": ["Player 1", "is in the lead"], "leadme_1": ["1st"],
    "lead_1": ["Player 2", "is in the lead"], "lead_2": ["Player 3", "is in the lead"],
    "lead_3": ["Player 4", "is in the lead"],
    "leadlost_0": ["Player 1", "is trailing behind"],
    # the player gains / loses a place, and incidents
    "upgen_0": ["Good"], "upgen_1": ["Amazing"],
    "down_0": ["Bad"],
    "buoy_0": ["Bad"],
    "off_0": ["Failure"],
    # quiet stretches
    "front_0": ["Amazing"], "front_1": ["Outstanding"], "front_2": ["Incredible"],
    "last_0": ["Player 1", "is trailing behind"],
    # the finish
    "winme_0": ["Player 1", "Wins!"], "winme_1": ["Player 1", "Wins!", "Outstanding"],
    "winopp_1": ["Player 2", "Wins!"], "winopp_2": ["Player 3", "Wins!"], "winopp_3": ["Player 4", "Wins!"],
    "winoppgen": ["Defeat"],
    "fin_2": ["Good Game"], "fin_3": ["Good Game"], "fin_4": ["Player 1", "Loses"],
    "fin_5": ["Player 1", "Loses"], "fin_6": ["Defeated"],
}

SR = 48000
GAP = 0.1
TARGET_RMS = 0.12

def load(pack, name):
    with tempfile.NamedTemporaryFile(suffix=".wav") as t:
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", os.path.join(pack, name + ".wav"),
                        "-ac", "1", "-ar", str(SR), "-c:a", "pcm_f32le", t.name], check=True)
        a, _ = sf.read(t.name, dtype="float32")
    loud = np.where(np.abs(a) > 0.02 * np.abs(a).max())[0]
    a = a[max(0, loud[0] - int(0.02 * SR)): loud[-1] + int(0.06 * SR)]
    rms = float(np.sqrt(np.mean(a[np.abs(a) > 0.05 * np.abs(a).max()] ** 2)))
    return a * (TARGET_RMS / max(rms, 1e-6))

def main():
    pack, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for key, names in CALLS.items():
        parts = []
        for i, n in enumerate(names):
            if i: parts.append(np.zeros(int(GAP * SR), np.float32))
            parts.append(load(pack, n))
        a = np.concatenate(parts)
        peak = float(np.abs(a).max())
        if peak > 0.97: a *= 0.97 / peak
        with tempfile.NamedTemporaryFile(suffix=".wav") as t:
            sf.write(t.name, a, SR)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", t.name, "-c:a", "libopus", "-b:a", "40k",
                            os.path.join(out, key + ".ogg")], check=True)
    print(len(CALLS), "calls written")

if __name__ == "__main__":
    main()
