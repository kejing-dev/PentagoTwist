"""Sounds for Pentago (profile B20 / D51 + ui_gates sound-source). Physical sounds are CC0 recordings, only the "can't" cue is
a synthesised UI tone; selecting a block / hover / the hint toggle are silent (D51 补 2026-11-06).

  python3 tools/import_cc0_sounds.py [~/Downloads/cc0-sounds]

  place    a glass marble landing on wood: single first impacts cut out of freesound 628994 / 628995 (CC0, KaleidacousticsAudio,
           "Marbles dropped on a wooden floor one by one"), 3 variants
  retract  the marble back among the others: freesound 628997 ("Marbles hitting each other"), 2 variants
  click    the block's wooden teeth (three per twist): Kenney impact-sounds impactWood_light, cut to 70 ms, 3 variants
  land     the block dropping back: Kenney impactPlank_medium, 2 variants
  win      three glass taps climbing (Kenney impactGlass_light_001 re-pitched)   lose / draw: one low wood knock
  illegal  synthesised UI pluck (440 -> 330 Hz)

Chain (D51 补「录音要干净」): decode (afconvert, 22.05 kHz mono) -> onset-cut the first transient only -> spectral gate
(noise floor from the quietest 20 % of frames, -12 dB over it) -> high-pass 120 Hz + low-pass 6 kHz -> 3 ms fade in / 40 ms
fade out -> peak-normalised (no compression, D51) to the cue's level. No reverb, no thickening; a cue that is too quiet gets gain, not tail.
Writes app/src/main/assets/sounds/<name>[_n].wav, tools/qa-sample.wav (every cue, sounding segments only) and
tools/sound-sources.txt.
"""
import math, os, subprocess, sys, tempfile, wave
import numpy as np

SRC = os.path.expanduser(sys.argv[1] if len(sys.argv) > 1 else "~/Downloads/cc0-sounds")
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 22050
IMP = "kenney_impact-sounds/Audio/"
FS = "freesound-marbles/"
SOURCES = {}


def decode(path):
    with tempfile.TemporaryDirectory() as d:
        out = os.path.join(d, "x.wav")
        subprocess.run(["afconvert", "-f", "WAVE", "-d", "LEI16@%d" % RATE, "-c", "1", path, out], check=True)
        with wave.open(out) as w:
            return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def biquad(x, f, kind, q=0.707):
    w = 2 * math.pi * f / RATE; cw, sw = math.cos(w), math.sin(w); al = sw / (2 * q)
    if kind == "lp":
        b = [(1 - cw) / 2, 1 - cw, (1 - cw) / 2]
    else:
        b = [(1 + cw) / 2, -(1 + cw), (1 + cw) / 2]
    a = [1 + al, -2 * cw, 1 - al]
    b = [v / a[0] for v in b]; a1, a2 = a[1] / a[0], a[2] / a[0]
    y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = b[0] * v + b[1] * x1 + b[2] * x2 - a1 * y1 - a2 * y2
        x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
    return y


def band(x, lo=120, hi=6000):
    return biquad(biquad(biquad(x, lo, "hp"), hi, "lp"), hi, "lp")


def gate(x, n=256):
    """Spectral gate: frames' magnitudes below (noise floor + 12 dB) are cut (noise floor = 20th percentile per bin)."""
    hop = n // 2
    win = np.hanning(n)
    pad = np.concatenate([np.zeros(n), x, np.zeros(n)])
    frames = [pad[i:i + n] * win for i in range(0, len(pad) - n, hop)]
    spec = np.array([np.fft.rfft(f) for f in frames])
    mag = np.abs(spec)
    floor = np.percentile(mag, 20, axis=0) * 10 ** (12 / 20)
    mask = np.clip((mag - floor) / np.maximum(mag, 1e-9), 0, 1)
    spec *= mask
    out = np.zeros(len(pad))
    for k, s in enumerate(spec):
        out[k * hop:k * hop + n] += np.fft.irfft(s, n) * win
    out /= 0.75
    return out[n:n + len(x)]


def onsets(x, thr_db=-30, min_gap=0.25):
    env = np.convolve(np.abs(x), np.ones(64) / 64, mode="same")
    thr = 10 ** (thr_db / 20) * env.max() / 10 ** (-0 / 20)
    out, last = [], -1e9
    for i in range(1, len(env)):
        if env[i] > thr and env[i - 1] <= thr and i - last > min_gap * RATE:
            out.append(i); last = i
    return out


def cut(x, start, dur):
    s = max(0, start - int(0.004 * RATE))
    return x[s:s + int(dur * RATE)].copy()


def finish(x, peak_db, comp=0):
    """comp: gentle tanh compression (lifts the decay a little at the same peak; no added tail)."""
    x = x / max(1e-9, np.abs(x).max())
    if comp:
        x = np.tanh(comp * x) / math.tanh(comp)
    k = min(len(x), int(0.04 * RATE)); x[-k:] *= np.linspace(1, 0, k) ** 2
    k = min(len(x), int(0.003 * RATE)); x[:k] *= np.linspace(0, 1, k)
    return x / max(1e-9, np.abs(x).max()) * 10 ** (peak_db / 20)


def write(name, x):
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())


def resample(x, ratio):
    n = int(len(x) / ratio)
    return np.interp(np.arange(n) * ratio, np.arange(len(x)), x)


def best_hits(path, count, dur, skip=0):
    x = band(gate(decode(path)))
    hits = []
    for o in onsets(x):
        seg = cut(x, o, dur)
        if len(seg) < dur * RATE * 0.9:
            continue
        # clean = the first transient dominates (no second bounce inside the cut)
        head = np.abs(seg[:int(0.03 * RATE)]).max(); tail = np.abs(seg[int(0.05 * RATE):]).max() if len(seg) > 0.05 * RATE else 0
        hits.append((head / (tail + 1e-6), head, o, seg))
    hits.sort(key=lambda h: (-h[0] if h[1] > 0.02 else 1e9))
    return [h for h in hits[skip:skip + count]]


os.makedirs(OUT, exist_ok=True)
for f in os.listdir(OUT):
    if f.endswith(".wav"):
        os.remove(os.path.join(OUT, f))
made = {}

# place: three single landings (the first transient, 160 ms, no bounce)
hits = best_hits(os.path.join(SRC, FS + "marbles_628994.mp3"), 2, 0.16) + best_hits(os.path.join(SRC, FS + "marbles_628995.mp3"), 1, 0.16)
for i, (_, _, o, seg) in enumerate(hits):
    name = "place" if i == 0 else f"place_{i + 1}"
    src = "628994" if i < 2 else "628995"
    x = finish(seg, -1.5); write(name, x); made[name] = x
    SOURCES[name] = f"CC0 录音 freesound {src}（KaleidacousticsAudio，玻璃珠落木地板）https://freesound.org/people/KaleidacousticsAudio/sounds/{src}/ · 第 {o / RATE:.2f} s 起的第一下 160 ms；谱门限降噪 + 120 Hz–6 kHz 带通"
    print(f"{name}.wav <- freesound {src} @ {o / RATE:.2f}s")

# retract: a marble clicking among the others
for i, (_, _, o, seg) in enumerate(best_hits(os.path.join(SRC, FS + "marbles_628997.mp3"), 2, 0.14)):
    name = "retract" if i == 0 else f"retract_{i + 1}"
    x = finish(seg, -5); write(name, x); made[name] = x
    SOURCES[name] = f"CC0 录音 freesound 628997（KaleidacousticsAudio，珠子互碰）https://freesound.org/people/KaleidacousticsAudio/sounds/628997/ · 第 {o / RATE:.2f} s 起 140 ms；降噪 + 带通"
    print(f"{name}.wav <- freesound 628997 @ {o / RATE:.2f}s")

# click: wooden teeth, 70 ms, a little brighter (+8 % pitch) and quieter than the landing
for i in range(3):
    rel = IMP + f"impactWood_light_00{i}.ogg"
    x = band(gate(decode(os.path.join(SRC, rel))), 200, 6000)
    o = onsets(x)[0] if onsets(x) else 0
    seg = resample(cut(x, o, 0.08), 1.08)
    name = "click" if i == 0 else f"click_{i + 1}"
    seg = finish(seg, -7); write(name, seg); made[name] = seg
    SOURCES[name] = f"CC0 录音 Kenney kenney_impact-sounds / impactWood_light_00{i}.ogg（截 70 ms、升 8 %、降噪 + 带通）"
    print(f"{name}.wav <- Kenney impactWood_light_00{i}")

# land: the block drops back into the frame
for i in range(2):
    rel = IMP + f"impactPlank_medium_00{i}.ogg"
    x = band(gate(decode(os.path.join(SRC, rel))))
    o = onsets(x)[0] if onsets(x) else 0
    seg = finish(cut(x, o, 0.2), -3)
    name = "land" if i == 0 else f"land_{i + 1}"
    write(name, seg); made[name] = seg
    SOURCES[name] = f"CC0 录音 Kenney kenney_impact-sounds / impactPlank_medium_00{i}.ogg（截 200 ms、降噪 + 带通）"
    print(f"{name}.wav <- Kenney impactPlank_medium_00{i}")

# win: three glass taps climbing, 140 ms apart
tap = band(gate(decode(os.path.join(SRC, IMP + "impactGlass_light_001.ogg"))))
o = onsets(tap)[0] if onsets(tap) else 0
tap = cut(tap, o, 0.25)
win = np.zeros(int(0.75 * RATE))
for k, ratio in enumerate((1.0, 1.26, 1.5)):
    s = resample(tap, ratio); off = int(k * 0.14 * RATE)
    win[off:off + len(s)] += s[:len(win) - off]
win = finish(win, -2); write("win", win); made["win"] = win
SOURCES["win"] = "CC0 录音 Kenney kenney_impact-sounds / impactGlass_light_001.ogg（三次升调拼接，你的五连）"
# lose / draw: one low wood knock
k = band(gate(decode(os.path.join(SRC, IMP + "impactWood_light_002.ogg"))))
o = onsets(k)[0] if onsets(k) else 0
lose = finish(resample(cut(k, o, 0.3), 0.85), -3); write("lose", lose); made["lose"] = lose
SOURCES["lose"] = "CC0 录音 Kenney kenney_impact-sounds / impactWood_light_002.ogg（降 15 %，对手五连只低一声，不放胜利音）"
draw = finish(resample(cut(k, o, 0.3), 0.95), -5); write("draw", draw); made["draw"] = draw
SOURCES["draw"] = "CC0 录音 Kenney kenney_impact-sounds / impactWood_light_002.ogg（降 5 %，和棋 / 认输一声低音）"


def illegal_tone():
    out = []
    for f0, dur in ((440.0, 0.09), (330.0, 0.14)):
        t = np.arange(int(dur * RATE)) / RATE
        env = np.exp(-t * 22) * (1 - np.exp(-t * 900))
        out.append((np.sin(2 * math.pi * f0 * t) + 0.25 * np.sin(4 * math.pi * f0 * t)) * env)
    return np.concatenate(out)


il = finish(illegal_tone(), -12, comp=0); write("illegal", il); made["illegal"] = il
SOURCES["illegal"] = "合成（UI）两声柔和正弦拨音 440→330 Hz、快衰减（这儿有珠了 / 先放一颗），非实物声"

order = ["place", "click", "click_2", "click_3", "land", "place_2", "click", "click_2", "click_3", "land_2", "place_3", "retract", "retract_2", "illegal", "win", "lose", "draw"]
seq = np.concatenate([np.concatenate([made[n], np.zeros(int(0.02 * RATE))]) for n in order])
with wave.open(os.path.join(ROOT, "tools", "qa-sample.wav"), "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
    w.writeframes((np.clip(seq, -1, 1) * 32767).astype("<i2").tobytes())
with open(os.path.join(ROOT, "tools", "sound-sources.txt"), "w", encoding="utf-8") as f:
    for n in sorted(SOURCES):
        f.write(f"- {n}.wav：{SOURCES[n]}\n")
for n in sorted(made):
    x = made[n]; v = x[np.abs(x) > 10 ** (-40 / 20)]
    print("%-10s %.2f s peak %.1f dBFS rms(voiced) %.1f" % (n, len(x) / RATE, 20 * math.log10(np.abs(x).max()), 20 * math.log10(math.sqrt(np.mean(v ** 2))) if len(v) else -99))
rms = 20 * math.log10(math.sqrt(np.mean(seq ** 2)))
print("sample: RMS %.1f dBFS, peak %.1f dBFS, %.1f s" % (rms, 20 * math.log10(np.abs(seq).max()), len(seq) / RATE))
