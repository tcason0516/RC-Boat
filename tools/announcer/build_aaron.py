"""Builds the announcer's race calls from the "AAron" voice pack (single recorded words).

Usage: python3 build_aaron.py PACK_DIR OUT_DIR
PACK_DIR holds aa_<word>.wav files. Each call below is a list of words; they are trimmed,
levelled to the same loudness and joined with short pauses (no other processing), then
written as OUT_DIR/<key>.ogg (Ogg Opus, mono).
"""
import os, sys, subprocess, tempfile
import numpy as np, soundfile as sf

CALLS = {
    # before the countdown
    "start_0": ["prepare"], "start_1": ["ready"], "start_2": ["welcome", "challenger"],
    # countdown and start
    "count_3": ["3"], "count_2": ["2"], "count_1": ["1"], "go": ["go"],
    # the player takes / loses the lead
    "leadme_0": ["first", "position", "acquired"], "leadme_1": ["first", "position", "taken"],
    "leadlost_0": ["first", "position", "lost"], "leadlost_1": ["warning", "first", "position", "lost"],
    # the player gains / loses a place
    "up_2": ["second", "position"], "up_3": ["third", "position"], "up_4": ["position", "4"], "up_5": ["position", "5"],
    "upgen_0": ["position", "gained"], "upgen_1": ["good"],
    "down_0": ["position", "lost"], "down_1": ["caution", "position", "lost"],
    # incidents
    "buoy_0": ["caution", "damage"], "buoy_1": ["warning", "sustainingDamage"], "buoy_2": ["damaged"], "buoy_3": ["caution"],
    "bump_0": ["danger"], "bump_1": ["warning"], "bump_2": ["attention"],
    "off_0": ["warning", "outside", "perimeter"], "off_1": ["caution", "outside", "perimeter"], "off_2": ["goback"],
    "wrong_0": ["goback"], "wrong_1": ["negative", "goback"], "wrong_2": ["warning", "goback"],
    # laps
    "togo_2": ["2", "remaining"], "togo_3": ["3", "remaining"], "togo_4": ["4", "remaining"], "togo_5": ["5", "remaining"],
    "togo_6": ["6", "remaining"], "togo_7": ["7", "remaining"], "togo_8": ["8", "remaining"],
    "white_0": ["finish", "imminent"], "white_1": ["1", "remaining"],
    # the finish
    "winme_0": ["victory"], "winme_1": ["youWin", "congratulations"], "winme_2": ["winner", "perfect"],
    "winopp_0": ["challenger", "wins"], "winopp_1": ["finish", "challenger", "wins"],
    "fin_2": ["second", "position", "good"], "fin_3": ["third", "position"], "fin_4": ["position", "4", "youLose"],
    "fin_5": ["position", "5", "defeat"], "fin_6": ["position", "6", "gameOver"],
    # quiet stretches
    "front_0": ["good"], "front_1": ["perfect"],
    "last_0": ["critical"], "last_1": ["insufficient", "power"], "last_2": ["failure", "imminent"],
}

SR = 48000
GAP = 0.12          # pause between words (s)
TARGET_RMS = 0.12   # every word is levelled to this loudness

def load_word(pack, word):
    with tempfile.NamedTemporaryFile(suffix=".wav") as t:
        subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", os.path.join(pack, "aa_%s.wav" % word),
                        "-ac", "1", "-ar", str(SR), "-c:a", "pcm_f32le", t.name], check=True)
        a, _ = sf.read(t.name, dtype="float32")
    loud = np.where(np.abs(a) > 0.02 * np.abs(a).max())[0]
    a = a[max(0, loud[0] - int(0.02 * SR)): loud[-1] + int(0.06 * SR)]
    rms = float(np.sqrt(np.mean(a[np.abs(a) > 0.05 * np.abs(a).max()] ** 2)))
    return a * (TARGET_RMS / max(rms, 1e-6))

def main():
    pack, out = sys.argv[1], sys.argv[2]
    os.makedirs(out, exist_ok=True)
    for key, words in CALLS.items():
        parts = []
        for i, w in enumerate(words):
            if i: parts.append(np.zeros(int(GAP * SR), np.float32))
            parts.append(load_word(pack, w))
        a = np.concatenate(parts)
        peak = float(np.abs(a).max())
        if peak > 0.97: a *= 0.97 / peak   # never clip
        with tempfile.NamedTemporaryFile(suffix=".wav") as t:
            sf.write(t.name, a, SR)
            subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", t.name, "-c:a", "libopus", "-b:a", "40k",
                            os.path.join(out, key + ".ogg")], check=True)
    print(len(CALLS), "calls written")

if __name__ == "__main__":
    main()
