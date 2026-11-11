"""Render every line in lines.py to RAW_DIR/<key>.wav (skips ones already there), with an ASR check."""
import os, sys, re, json, time
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import tts, lines
raw = sys.argv[1]; os.makedirs(raw, exist_ok=True)
import sherpa_onnx, numpy as np, subprocess
d = '/tmp/asr/sherpa-onnx-whisper-base.en/'
rec = sherpa_onnx.OfflineRecognizer.from_whisper(encoder=d+'base.en-encoder.int8.onnx', decoder=d+'base.en-decoder.int8.onnx', tokens=d+'base.en-tokens.txt', num_threads=1)
NUM = {"1": "one", "2": "two", "3": "three", "4": "four", "5": "five", "6": "six", "7": "seven", "8": "eight",
       "100": "hundred", "%": " percent", "1st": "first", "2nd": "second", "3rd": "third", "4th": "fourth",
       "5th": "fifth", "6th": "sixth", "checker": "checkered", "bye bye": "byebye", "bye-bye": "byebye"}
def words(s):
    s = re.sub(r"<[^>]*>", "", s.lower())
    for k in sorted(NUM, key=len, reverse=True):
        s = re.sub(r"(?<![a-z0-9])" + re.escape(k) + r"(?![a-z0-9])" if k[0].isalnum() else re.escape(k), " " + NUM[k] + " ", s)
    s = re.sub(r"[^a-z0-9' ]", " ", s.replace("-", " ")).replace("'", "")
    return [w for w in s.split() if w not in ("a", "an", "the", "oh", "ohh", "ooh", "uh")]
def score(ref, hyp):
    r, h = words(ref), words(hyp)
    import difflib
    return difflib.SequenceMatcher(None, r, h).ratio()
keys = sys.argv[2:] or list(lines.LINES)
log = open(os.path.join(raw, 'log.jsonl'), 'a')
for k in keys:
    out = os.path.join(raw, k + '.wav')
    if os.path.exists(out): continue
    best = None
    for seed in (7, 11, 23, 42):
        t = time.time()
        a, n = tts.render(lines.VOICE, lines.LINES[k], seed=seed)
        s = rec.create_stream(); s.accept_waveform(24000, a); rec.decode_stream(s)
        hyp = s.result.text.strip(); sc = score(lines.LINES[k], hyp)
        rec_ = dict(key=k, seed=seed, sec=round(len(a)/24000, 2), took=round(time.time()-t), asr=hyp, score=round(sc, 2))
        log.write(json.dumps(rec_) + '\n'); log.flush(); print(rec_, flush=True)
        if best is None or sc > best[0]: best = (sc, a)
        if sc >= 0.8 and len(a) / 24000 < 9: break
    tts.save(out, best[1])
print('done', flush=True)
