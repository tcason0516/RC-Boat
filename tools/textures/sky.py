import sys, math, numpy as np, cv2
from PIL import Image
src, out_dir = sys.argv[1], sys.argv[2]
hdr = cv2.imread(src, cv2.IMREAD_UNCHANGED)[:, :, ::-1].astype(np.float32)   # RGB, linear
H, W, _ = hdr.shape
lum = hdr @ np.array([0.2126, 0.7152, 0.0722], np.float32)
# sun = centroid of the very brightest pixels
thr = np.quantile(lum, 0.99995); ys, xs = np.where(lum >= thr)
sy, sx = ys.mean(), xs.mean()
el = (0.5 - (sy + 0.5) / H) * math.pi
print("size", W, H, "sun px", round(sx, 1), round(sy, 1), "elevation deg", round(math.degrees(el), 2), "max lum", float(lum.max()))
# game light azimuth (kept from the old LIGHT vector): u = atan2(z, x) / 2pi + 0.5
az = math.atan2(0.58, -0.42); u_sun = az / (2 * math.pi) + 0.5
shift = int(round(u_sun * W - sx)); hdr = np.roll(hdr, shift, axis=1)
L = (math.cos(el) * math.cos(az), math.sin(el), math.cos(el) * math.sin(az))
print("LIGHT = normalize(%.4ff, %.4ff, %.4ff)" % L)
# upper hemisphere only, tone-mapped to a display-ready image
top = hdr[: H // 2]
exposure = float(sys.argv[3]) if len(sys.argv) > 3 else 0.6
c = top * exposure
c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14)
c = np.clip(c, 0, 1) ** (1 / 2.2)
img = (c * 255 + 0.5).astype(np.uint8)
img = cv2.resize(img, (2048, 512), interpolation=cv2.INTER_AREA)
Image.fromarray(img).save(out_dir + "/sky.jpg", quality=90)
hz = img[-6:-1].reshape(-1, 3).mean(0) / 255.0
print("horizon display colour", [round(float(v), 3) for v in hz])
# fog colour before the game's grade(): invert c -> grade(c) numerically per channel
def grade(x): return min(1.0, max(0.0, (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14)))
fog = []
for v in hz:
    lo, hi = 0.0, 4.0
    for _ in range(50):
        mid = (lo + hi) / 2
        if grade(mid) < v: lo = mid
        else: hi = mid
    fog.append(lo)
print("FOG = {%.3ff, %.3ff, %.3ff}" % tuple(fog))
Image.fromarray(img).resize((1024, 256)).save(out_dir + "/sky_preview.png")
