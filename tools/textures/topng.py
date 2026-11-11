import sys, struct
from PIL import Image
for src, dst in zip(sys.argv[1::2], sys.argv[2::2]):
    d = open(src, 'rb').read(); w, h = struct.unpack('<II', d[:8])
    Image.frombytes('RGBA', (w, h), d[8:8 + w * h * 4]).convert('RGB').save(dst)
