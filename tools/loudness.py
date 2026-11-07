"""Loudness evidence (B20 / ui_gates loudness, 2026-11-05 rule for apps with short feedback sounds only): renders 30 s of a
typical game at the default volume (100 %, the SoundPlayer top step) with the app's timings, one instance per event and
the ±5 % rate jitter: place (fly 220 ms) -> twist (clicks at 80 / 196 / 312 ms, land at 510 ms) -> the AI's place + twist,
a taken-dimple cue now and then, a take-back, and the win at the end (200 ms after the land). Rendered, not recorded:
kj-mbp-m5 has no audio loopback from the emulator. Prints the gate's measure (sounding windows) and the plain RMS / peak.

  python3 tools/loudness.py OUT.wav
"""
import math, os, random, sys, wave
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 22050


def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


rnd = random.Random(5)
events = []
t = 600
var = {"place": ["place", "place_2", "place_3"], "click": ["click", "click_2", "click_3"], "land": ["land", "land_2"], "retract": ["retract", "retract_2"]}
k = 0
while t < 28_000:
    who = k % 2
    events.append((t + 220, rnd.choice(var["place"])))
    if who == 0 and k % 5 == 2:
        events.append((t + 220 + 900, "illegal"))
    if who == 0 and k % 7 == 4:
        events.append((t + 220 + 1500, rnd.choice(var["retract"])))
        t += 1200
        events.append((t + 220 + 1500, rnd.choice(var["place"])))
    tw = t + 220 + (1800 if who == 0 else 250)
    for i, off in enumerate((80, 196, 312)):
        events.append((tw + off, var["click"][i]))
    events.append((tw + 510, rnd.choice(var["land"])))
    t = tw + 510 + (2500 if who == 0 else 1200)
    k += 1
events.append((t + 200, "win"))
total = (t + 1500) / 1000
out = np.zeros(int(total * RATE))
cache = {}
last = {}
for at, n in sorted(events):
    ch = n.split("_")[0]
    x = cache.setdefault(n, load(n))
    rate = 1.0 if ch in ("win", "illegal") else 0.95 + rnd.random() * 0.10
    x = np.interp(np.arange(int(len(x) / rate)) * rate, np.arange(len(x)), x)
    o = int(at / 1000 * RATE)
    if ch in last:
        po, px = last[ch]
        if o < po + len(px):
            out[o:po + len(px)] -= px[o - po:][: len(out) - o]
    out[o:o + len(x)] += x[: len(out) - o]
    last[ch] = (o, x)
with wave.open(sys.argv[1], "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes((np.clip(out, -1, 1) * 32767).astype("<i2").tobytes())
peak = 20 * math.log10(np.abs(out).max())
win = int(0.02 * RATE)
e = [np.mean(out[i:i + win] ** 2) for i in range(0, len(out) - win, win)]
thr = (np.abs(out).max() * 0.0316) ** 2
v = [x for x in e if x >= thr]
print("%.1f s, %d events; sounding RMS %.1f dBFS (20 ms windows >= peak - 30 dB), peak %.1f dBFS, whole-file RMS %.1f dBFS" % (
    total, len(events), 10 * math.log10(np.mean(v)), peak, 20 * math.log10(math.sqrt(np.mean(out ** 2)))))
