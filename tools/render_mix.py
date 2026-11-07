"""Renders the app's sound timing offline (profile D51 / build.md: 快速连点 5 次录响度, peak <= -1 dBFS) from the shipped
assets, the way SoundPlayer plays them: volume 100 %, one instance per event (a new play cuts the previous one of the same
event), the timings of GameFlow (place lands at 220 ms; a twist: clicks at 80 / 196 / 312 ms, the land at 510 ms).

  python3 tools/render_mix.py OUTDIR      writes taps5.wav, twist.wav, place-twist-win.wav, illegal5-after-place.wav, prints peak / RMS
"""
import math, os, sys, wave
import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SND = os.path.join(ROOT, "app", "src", "main", "assets", "sounds")
RATE = 22050


def load(n):
    with wave.open(os.path.join(SND, n + ".wav")) as w:
        return np.frombuffer(w.readframes(w.getnframes()), dtype="<i2").astype(float) / 32768


def render(events, total_s):
    """events: (t_ms, name). Same event name prefix = same channel: the next one cuts the previous (SoundPlayer.playing)."""
    out = np.zeros(int(total_s * RATE))
    last = {}
    for t, n in sorted(events):
        ch = n.split("_")[0]
        o = int(t / 1000 * RATE)
        if ch in last:  # cut the previous instance of this event where the new one starts
            po, plen = last[ch]
            cut = max(0, o - po)
            if cut < plen:
                out[o:po + plen] -= load_cache[last_name[ch]][cut:plen][: len(out) - o]
        x = load_cache.setdefault(n, load(n))
        out[o:o + len(x)] += x[: len(out) - o]
        last[ch] = (o, len(x)); last_name[ch] = n
    return out


load_cache, last_name = {}, {}


def report(name, x, outdir):
    peak = 20 * math.log10(max(1e-9, np.abs(x).max()))
    act = x[np.abs(x) > 10 ** (-50 / 20)]
    rms = 20 * math.log10(math.sqrt(np.mean(act ** 2))) if len(act) else -99
    with wave.open(os.path.join(outdir, name), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(RATE)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
    print("%-20s peak %6.1f dBFS   RMS (sounding) %6.1f dBFS   %.2f s" % (name, peak, rms, len(x) / RATE))
    return peak


out = sys.argv[1] if len(sys.argv) > 1 else "."
os.makedirs(out, exist_ok=True)
# five fast pinches on empty dimples (the panel can fire that fast): five plays of the place sound, 100 ms apart
p1 = report("taps5.wav", render([(i * 100, ["place", "place_2", "place_3"][i % 3]) for i in range(5)], 1.0), out)
twist = [(80, "click"), (196, "click_2"), (312, "click_3"), (510, "land")]
p2 = report("twist.wav", render(twist, 1.0), out)
# place, twist, and the win right as the block lands (the loudest pile-up the game makes)
# (the earliest a twist can follow is the landing + the AI's 250 ms look; the result sound waits 200 ms after the land)
seq = [(0, "place")] + [(470 + t, n) for t, n in twist] + [(470 + 510 + 200, "win")]
p3 = report("place-twist-win.wav", render(seq, 2.0), out)
# five fast taps on taken dimples (S03; during a twist taps are silent: GameFlow BUSY has no sound) right after a place
p4 = report("illegal5-after-place.wav", render([(0, "place")] + [(100 + i * 100, "illegal") for i in range(5)], 1.2), out)
worst = max(p1, p2, p3, p4)
print("WORST peak %.1f dBFS -> %s" % (worst, "OK (<= -1)" if worst <= -1.0 else "TOO LOUD"))
