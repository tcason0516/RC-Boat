"""Torch-free numpy port of SNAC (hubertsiuzdak/snac) for the 24 kHz model."""
import json, math, numpy as np
from loadpt import load

class SNAC:
    def __init__(self, d):
        cfg = json.load(open(d + '/config.json'))
        sd = load(d + '/pytorch_model.bin')
        self.sd = {}
        for k, v in sd.items():
            if k.endswith('original0'):
                base = k[:-len('.parametrizations.weight.original0')]
                vv = sd[base + '.parametrizations.weight.original1']
                n = np.sqrt((vv.astype(np.float64) ** 2).sum(axis=tuple(range(1, vv.ndim)), keepdims=True))
                self.sd[base + '.weight'] = (v * vv / n).astype(np.float32)
            elif not k.endswith('original1'):
                self.sd[k] = v.astype(np.float32)
        self.cfg = cfg
        self.rng = np.random.default_rng(0)

    # --- primitives (x: C x T) ---
    def conv(self, p, x, stride=1, pad=0, dil=1):
        w = self.sd[p + '.weight']; b = self.sd.get(p + '.bias')
        co, ci, k = w.shape
        if pad: x = np.pad(x, ((0, 0), (pad, pad)))
        T = x.shape[1]
        To = (T - dil * (k - 1) - 1) // stride + 1
        if ci == 1 and x.shape[0] == co and co > 1:  # depthwise
            y = np.zeros((co, To), np.float32)
            for j in range(k):
                y += w[:, 0, j, None] * x[:, j * dil: j * dil + stride * (To - 1) + 1: stride]
        else:
            y = np.zeros((co, To), np.float32)
            for j in range(k):
                y += w[:, :, j] @ x[:, j * dil: j * dil + stride * (To - 1) + 1: stride]
        if b is not None: y += b[:, None]
        return y

    def convT(self, p, x, stride, pad, opad):
        w = self.sd[p + '.weight']; b = self.sd[p + '.bias']
        ci, co, k = w.shape
        T = x.shape[1]
        full = np.zeros((co, (T - 1) * stride + k + opad), np.float32)
        for j in range(k):
            full[:, j: j + stride * (T - 1) + 1: stride] += w[:, :, j].T @ x
        L = (T - 1) * stride - 2 * pad + k + opad
        y = full[:, pad: pad + L]
        return y + b[:, None]

    def snake(self, p, x):
        a = self.sd[p + '.alpha'][0]
        return x + (1.0 / (a + 1e-9)) * np.sin(a * x) ** 2

    def resunit(self, p, x, dil):
        y = self.snake(p + '.block.0', x)
        y = self.conv(p + '.block.1', y, pad=3 * dil, dil=dil)
        y = self.snake(p + '.block.2', y)
        y = self.conv(p + '.block.3', y)
        return x + y

    # --- decoder ---
    def decode(self, codes):
        """codes: list of 3 int arrays (lengths T/4, T/2, T)."""
        z = 0
        for i, c in enumerate(codes):
            q = f'quantizer.quantizers.{i}'
            e = self.sd[q + '.codebook.weight'][np.asarray(c)].T
            zi = self.conv(q + '.out_proj', e)
            z = z + np.repeat(zi, self.cfg['vq_strides'][i], axis=1)
        return self.decode_latent(z)

    def decode_latent(self, z):
        x = self.conv('decoder.model.0', z, pad=3)
        x = self.conv('decoder.model.1', x)
        for i, s in enumerate(self.cfg['decoder_rates']):
            p = f'decoder.model.{2 + i}.block'
            x = self.snake(p + '.0', x)
            x = self.convT(p + '.1', x, s, math.ceil(s / 2), s % 2)
            h = self.conv(p + '.2.linear', x)
            x = x + self.rng.standard_normal((1, x.shape[1])).astype(np.float32) * h
            for r, d in zip((3, 4, 5), (1, 3, 9)):
                x = self.resunit(f'{p}.{r}', x, d)
        n = 2 + len(self.cfg['decoder_rates'])
        x = self.snake(f'decoder.model.{n}', x)
        x = self.conv(f'decoder.model.{n + 1}', x, pad=3)
        return np.tanh(x[0])

    # --- encoder (for round-trip testing) ---
    def encode(self, audio):
        hop = int(np.prod(self.cfg['encoder_rates'])); pad_to = hop * self.cfg['vq_strides'][0]
        audio = np.pad(audio, (0, -len(audio) % pad_to)).astype(np.float32)[None]
        x = self.conv('encoder.block.0', audio, pad=3)
        for i, s in enumerate(self.cfg['encoder_rates']):
            p = f'encoder.block.{1 + i}.block'
            for r, d in zip((0, 1, 2), (1, 3, 9)):
                x = self.resunit(f'{p}.{r}', x, d)
            x = self.snake(p + '.3', x)
            x = self.conv(p + '.4', x, stride=s, pad=math.ceil(s / 2))
        x = self.conv(f'encoder.block.{1 + len(self.cfg["encoder_rates"])}', x, pad=3)
        codes, res = [], x
        for i, s in enumerate(self.cfg['vq_strides']):
            q = f'quantizer.quantizers.{i}'
            r = res[:, :res.shape[1] // s * s].reshape(res.shape[0], -1, s).mean(2) if s > 1 else res
            ze = self.conv(q + '.in_proj', r)
            cb = self.sd[q + '.codebook.weight']
            en = ze.T / np.maximum(np.linalg.norm(ze.T, axis=1, keepdims=True), 1e-12)
            cn = cb / np.maximum(np.linalg.norm(cb, axis=1, keepdims=True), 1e-12)
            idx = np.argmax(en @ cn.T, axis=1)
            codes.append(idx)
            zq = np.repeat(self.conv(q + '.out_proj', cb[idx].T), s, axis=1)
            res = res - zq
        return codes
