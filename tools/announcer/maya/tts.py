"""Render a line with Maya1 (llama-server) + numpy SNAC. Usage: tts.py out.wav "description" "text" [seed] [temp]"""
import sys, json, time, urllib.request, numpy as np, subprocess
sys.path.insert(0, '/tmp/maya')
from snacnp import SNAC

URL = 'http://localhost:8099'
SOH, EOH, SOA, BOS, EOT, CS, CE, OFF = 128259, 128260, 128261, 128000, 128009, 128257, 128258, 128266

def post(path, obj):
    return urllib.request.urlopen(urllib.request.Request(URL + path, json.dumps(obj).encode(),
                                  {'Content-Type': 'application/json'}))

_snac = None
def render(desc, text, seed=1, temp=0.4, max_tok=1400):
    global _snac
    ids = json.load(post('/tokenize', {'content': f'<description="{desc}"> {text}', 'add_special': False}))['tokens']
    prompt = [SOH, BOS] + ids + [EOT, EOH, SOA, CS]
    req = {'prompt': prompt, 'n_predict': max_tok, 'temperature': temp, 'top_p': 0.9,
           'repeat_penalty': 1.1, 'repeat_last_n': 64, 'seed': seed, 'stream': True,
           'return_tokens': True, 'cache_prompt': False}
    toks = []
    r = post('/completion', req)
    for line in r:
        line = line.decode().strip()
        if not line.startswith('data:'): continue
        d = json.loads(line[5:])
        t = d.get('tokens', [])
        toks += t
        if CE in t or d.get('stop'): break
    r.close()
    if CE in toks: toks = toks[:toks.index(CE)]
    codes = [t for t in toks if OFF <= t < OFF + 7 * 4096]
    codes = codes[:len(codes) // 7 * 7]
    l1, l2, l3 = [], [], []
    for i in range(0, len(codes), 7):
        s = [(c - OFF) % 4096 for c in codes[i:i + 7]]
        l1.append(s[0]); l2 += [s[1], s[4]]; l3 += [s[2], s[3], s[5], s[6]]
    if _snac is None: _snac = SNAC('/tmp/maya/snac')
    return _snac.decode([np.array(l1), np.array(l2), np.array(l3)]), len(toks)

def save(path, audio):
    p = subprocess.run(['ffmpeg', '-loglevel', 'error', '-y', '-f', 'f32le', '-ar', '24000', '-ac', '1',
                        '-i', '-', path], input=audio.astype(np.float32).tobytes())

if __name__ == '__main__':
    out, desc, text = sys.argv[1:4]
    seed = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    temp = float(sys.argv[5]) if len(sys.argv) > 5 else 0.4
    t = time.time(); a, n = render(desc, text, seed, temp)
    save(out, a); print(f'{out}: {n} tokens, {len(a)/24000:.1f}s audio, {time.time()-t:.0f}s')
