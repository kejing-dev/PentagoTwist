"""Pentago Twist icon (design-package appIcon): walnut #6E4A2B disc; four maple #EBD6B8 rounded blocks 2 x 2 (the top-right
one turned 15° clockwise with a deep-walnut clockwise arrow on it); five pearl #F2EEE6 marbles on a diagonal from lower left to
upper right, each with a deep-walnut rim so it reads on the maple. No text.

  python3 tools/build_icon.py [--icon512 out.png] [--preview out.png]

Writes res/drawable/icon_3d_layer_{0,1,2}.png + icon_3d_sdf_{0,1,2}.png (PICO layered icon), mipmap-xxxhdpi/
ic_spatial_launcher.png and the adaptive foreground vector res/drawable/ic_launcher_fg.xml (same polygons).
"""
import math
import os
import sys
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app", "src", "main", "res")
S, SS = 1024, 4
WALNUT = (0x6E, 0x4A, 0x2B)
MAPLE = (0xEB, 0xD6, 0xB8)
PEARL = (0xF2, 0xEE, 0xE6)
DEEP = (0x3E, 0x28, 0x16)


def circle(cx, cy, r, n=48):
    return [(cx + r * math.cos(2 * math.pi * i / n), cy + r * math.sin(2 * math.pi * i / n)) for i in range(n)]


def rounded_square(cx, cy, half, r, rot_deg=0.0, n=8):
    pts = []
    for k, (sx, sy) in enumerate(((1, 1), (-1, 1), (-1, -1), (1, -1))):
        ccx, ccy = sx * (half - r), sy * (half - r)
        a0 = 90 * k
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((ccx + r * math.cos(a), ccy + r * math.sin(a)))
    c, s = math.cos(math.radians(rot_deg)), math.sin(math.radians(rot_deg))
    return [(cx + x * c - y * s, cy + x * s + y * c) for x, y in pts]


def arc(cx, cy, r, a0, a1, w, n=24):
    outer = [(cx + (r + w / 2) * math.cos(math.radians(a0 + (a1 - a0) * i / n)), cy + (r + w / 2) * math.sin(math.radians(a0 + (a1 - a0) * i / n))) for i in range(n + 1)]
    inner = [(cx + (r - w / 2) * math.cos(math.radians(a1 - (a1 - a0) * i / n)), cy + (r - w / 2) * math.sin(math.radians(a1 - (a1 - a0) * i / n))) for i in range(n + 1)]
    return outer + inner


H, G = 12.5, 2.5          # block half size, half gap
BL = [(54 - H - G, 54 - H - G), (54 + H + G, 54 - H - G), (54 - H - G, 54 + H + G), (54 + H + G, 54 + H + G)]
BLOCKS = [rounded_square(x, y, H, 3.5, 15 if i == 1 else 0) for i, (x, y) in enumerate(BL)]
MARBLES = [(31 + 9 * i, 79 - 9 * i) for i in range(5)]
RIMS = [circle(x, y, 5.2) for x, y in MARBLES]
PEARLS = [circle(x, y, 4.0) for x, y in MARBLES]
# clockwise arrow on the turned block (screen y down: clockwise = increasing angle), head at the end
TR = BL[1]
ARROW = arc(TR[0], TR[1], 8.0, 215, 345, 2.6)
end = math.radians(345)
tip = (TR[0] + 8.0 * math.cos(end + 0.5), TR[1] + 8.0 * math.sin(end + 0.5))
HEAD = [(TR[0] + 5.0 * math.cos(end), TR[1] + 5.0 * math.sin(end)), (TR[0] + 11.0 * math.cos(end), TR[1] + 11.0 * math.sin(end)), tip]


def big(draw_fn):
    im = Image.new("RGBA", (S * SS, S * SS), (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(im), S * SS / 108.0)
    return im.resize((S, S), Image.LANCZOS)


def sc(pts, k):
    return [(x * k, y * k) for x, y in pts]


def layer_bg():
    return big(lambda dr, k: dr.ellipse((0, 0, 108 * k - 1, 108 * k - 1), fill=WALNUT + (255,)))


def layer_board():
    def d(dr, k):
        for b in BLOCKS:
            dr.polygon(sc(b, k), fill=MAPLE + (255,))
    return big(d)


def layer_marbles():
    def d(dr, k):
        dr.polygon(sc(ARROW, k), fill=DEEP + (255,))
        dr.polygon(sc(HEAD, k), fill=DEEP + (255,))
        for r, p in zip(RIMS, PEARLS):
            dr.polygon(sc(r, k), fill=DEEP + (255,))
            dr.polygon(sc(p, k), fill=PEARL + (255,))
    return big(d)


def sdf(layer):
    inside = layer.getchannel("A").point(lambda v: 255 if v >= 128 else 0)
    dist = Image.new("L", inside.size, 0)
    cur = inside
    for i in range(1, 33):
        dist.paste(min(255, i * 8), mask=cur)
        cur = cur.filter(ImageFilter.MinFilter(3) if i % 2 else ImageFilter.Kernel((3, 3), (0, 1, 0, 1, 1, 1, 0, 1, 0), 5))
        cur = cur.point(lambda v: 255 if v >= 255 else 0)
    dist = Image.composite(dist, Image.new("L", inside.size, 0), inside)
    return Image.merge("RGBA", (dist, dist, dist, dist))


def path(pts):
    return "M" + "L".join("%.2f,%.2f" % p for p in pts) + "Z"


def vector():
    hexc = lambda c: "#FF%02X%02X%02X" % c
    lines = [
        '<?xml version="1.0" encoding="utf-8"?>',
        '<!-- Pentago Twist launcher foreground (tools/build_icon.py draws the same artwork for the PICO layered icon). -->',
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">',
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(MAPLE), "".join(path(b) for b in BLOCKS)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(DEEP), path(ARROW) + path(HEAD)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(DEEP), "".join(path(r) for r in RIMS)),
        '    <path android:fillColor="%s" android:pathData="%s" />' % (hexc(PEARL), "".join(path(p) for p in PEARLS)),
        '</vector>',
    ]
    open(os.path.join(RES, "drawable", "ic_launcher_fg.xml"), "w").write("\n".join(lines) + "\n")


def main():
    layers = [layer_bg(), layer_board(), layer_marbles()]
    for i, layer in enumerate(layers):
        layer.save(os.path.join(RES, "drawable", f"icon_3d_layer_{i}.png"))
        sdf(layer).save(os.path.join(RES, "drawable", f"icon_3d_sdf_{i}.png"))
    flat = Image.new("RGBA", layers[0].size, (0, 0, 0, 0))
    for l in layers:
        flat.alpha_composite(l)
    flat.save(os.path.join(RES, "mipmap-xxxhdpi", "ic_spatial_launcher.png"))
    vector()
    if "--icon512" in sys.argv:
        flat.resize((512, 512), Image.LANCZOS).save(sys.argv[sys.argv.index("--icon512") + 1])
    if "--preview" in sys.argv:
        w = 256
        sheet = Image.new("RGB", (w * 4, w), (60, 64, 70))
        for i, t in enumerate(layers + [flat]):
            r = t.resize((w, w), Image.LANCZOS)
            sheet.paste(r, (i * w, 0), r)
        sheet.save(sys.argv[sys.argv.index("--preview") + 1])
    print("ICON ok")


main()
