"""Blank of the control window per screenshot (build.md 2026-11-05 统一口径): (content top - window top + window bottom -
content bottom) / window height, content = the first to the last row that has any text / control edge in the inner 90 % of
the window width; also the largest blank band INSIDE the content (> 20 % of the height has to be tightened).

  python3 tools/blank.py SHOT.png [...]     (the "win" crops of tools/shots.py)
"""
import sys
import numpy as np
from PIL import Image

import os
# the glass inside a tools/shots.py "win" crop (WIN_GLASS - WIN_CROP origin)
RECT = (40, 164, 816, 1050)
for path in sys.argv[1:]:
    im = np.asarray(Image.open(path).convert("L")).astype(float)
    x0, y0, x1, y1 = RECT
    w = x1 - x0
    inner = im[y0 + 40:y1 - 40, x0 + int(0.05 * w):x1 - int(0.05 * w)]
    rows = (inner.max(axis=1) - inner.min(axis=1)) > 28
    idx = np.where(rows)[0]
    h = y1 - y0
    if len(idx) == 0:
        print("%s: no content" % path); continue
    top = idx[0] + 40; bot = (y1 - y0) - (idx[-1] + 40)
    gaps = np.diff(idx)
    band = (gaps.max() - 1) if len(gaps) else 0
    print("%-48s outside %4.1f %%   (top %3.0f %%, bottom %3.0f %%)   largest inside band %4.1f %%" % (
        path.split("/")[-1], 100.0 * (top + bot) / h, 100.0 * top / h, 100.0 * bot / h, 100.0 * band / h))
