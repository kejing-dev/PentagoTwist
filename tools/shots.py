"""QA / self-test screenshot driver (debug builds): debug-broadcast steps (the same GameFlow calls as pinches and panel
keys), then a capture, cropped to the control window or kept whole for the board.

  DEV=emulator-5564 python3 tools/shots.py OUTDIR [win|board|all] [zh|en|both]

Camera poses (pico-cli camera reset + move, emulator kj-mbp-m5 / PICO OS 6.2): "win" = turn right 22° and 0.6 m forward (the system
places the window anew at each launch, relative to the camera: launch with the camera reset; then the glass is WIN_GLASS); "board" = 1.2 m forward, look down 22°. Override with WIN_POSE / BOARD_POSE.
"""
import os, subprocess, sys, time
from PIL import Image

DEV = os.environ.get("DEV", "emulator-5564")
ADB = os.path.expanduser("~/Library/Android/sdk/platform-tools/adb")
PKG = "io.embodit.pentagotwist"
POSES = {"win": os.environ.get("WIN_POSE", "turn right 22 degrees then move forward 0.6 meters"),
         "board": os.environ.get("BOARD_POSE", "turn right 2 degrees then move forward 1.2 meters then look down 22 degrees")}


# the control window's glass in a 2560 x 2558 capture at the "win" pose, for a launch made with the camera reset
WIN_GLASS = (1130, 704, 2126, 1860)
WIN_CROP = (1090, 540, 2166, 1900)


WIN = [
    ("S01-welcome", [("goto", "S01"), ("sleep", 2.0)]),
    ("S02-new", [("newmode", "ai"), ("goto", "S02"), ("sleep", 1.5)]),
    ("S02-new-2p", [("newmode", "2p"), ("goto", "S02"), ("sleep", 1.2)]),
    ("S02-continue", [("newmode", "ai"), ("goto", "S03"), ("sleep", 0.6), ("tap", "a1"), ("sleep", 0.8), ("tap", "Q4cw"), ("sleep", 3.0), ("goto", "S02"), ("sleep", 1.5)]),
    ("S03-idle", [("coach", "off"), ("goto", "S03"), ("sleep", 1.5)]),
    ("S03-coach", [("coach", "on"), ("goto", "S03"), ("sleep", 1.5)]),
    ("S03-hint", [("coach", "off"), ("goto", "S03"), ("hint", "on"), ("sleep", 2.0)]),
    ("S05-placing", [("hint", "off"), ("goto", "S05 120"), ("sleep", 1.0)]),
    ("S06-thinking", [("freeze", "off"), ("goto", "S06"), ("sleep", 1.5)]),
    ("S07-twist", [("hold", "off"), ("goto", "S07"), ("sleep", 2.0)]),
    ("S07-picked", [("goto", "S07 Q1"), ("sleep", 2.0)]),
    ("S08-occupied", [("goto", "S08"), ("sleep", 1.2)]),
    ("S09-win-twist", [("freeze", "off"), ("goto", "S09"), ("sleep", 3.0)]),
    ("S10-pause", [("goto", "S10"), ("sleep", 3.0)]),
    ("S11-settings", [("pause", "off"), ("goto", "S11"), ("sleep", 1.5)]),
    ("S12-rules", [("goto", "S03"), ("sleep", 0.5), ("goto", "S12"), ("sleep", 1.5)]),
    ("S13-restart", [("goto", "S03"), ("sleep", 0.5), ("goto", "S13"), ("sleep", 1.5)]),
    ("S13-resign", [("goto", "S13 resign"), ("sleep", 1.5)]),
    ("S13-undo2p", [("no", ""), ("goto", "S13 undo2p"), ("sleep", 1.5)]),
    ("S13-exit", [("no", ""), ("exitask", ""), ("sleep", 1.5)]),
    ("S13-newgame", [("page", "play"), ("goto", "S03"), ("sleep", 0.6), ("tap", "a1"), ("sleep", 1.0), ("newask", ""), ("sleep", 1.5)]),
    ("S17-twisting", [("page", "play"), ("goto", "S17"), ("sleep", 2.5)]),
    ("S03-2p", [("freeze", "off"), ("new", "2p"), ("sleep", 1.5)]),
    ("S16-restored", [("new", "ai black 2"), ("sleep", 0.6), ("tap", "a1"), ("sleep", 0.8), ("tap", "Q4cw"), ("sleep", 3.5), ("goto", "S16"), ("sleep", 2.0)]),
]
BOARD = [
    ("board-S03", [("lang", "zh"), ("coach", "off"), ("tilt", "30"), ("size", "100"), ("goto", "S03"), ("sleep", 1.5)]),
    ("board-S04-ghost", [("goto", "S04 d3"), ("sleep", 1.0)]),
    ("board-S05", [("goto", "S05 120"), ("sleep", 1.0)]),
    ("board-S07", [("freeze", "off"), ("goto", "S07"), ("sleep", 2.0)]),
    ("board-S07-picked", [("goto", "S07 Q1"), ("sleep", 2.0)]),
    ("board-S08", [("goto", "S08"), ("sleep", 1.0)]),
    ("board-S17", [("freeze", "off"), ("goto", "S17"), ("sleep", 2.5)]),
    ("board-S09", [("freeze", "off"), ("goto", "S09"), ("sleep", 3.5)]),
    ("board-hint", [("goto", "S03"), ("hint", "on"), ("sleep", 2.5)]),
    ("board-tilt0", [("hint", "off"), ("tilt", "0"), ("sleep", 1.2)]),
    ("board-tilt45", [("tilt", "45"), ("sleep", 1.2)]),
    ("board-tilt60", [("tilt", "60"), ("sleep", 1.2)]),
    ("board-tilt90", [("tilt", "90"), ("sleep", 1.2)]),
    ("board-size120-tilt0", [("tilt", "0"), ("size", "120"), ("sleep", 1.2)]),
    ("board-size120-tilt90", [("tilt", "90"), ("sleep", 1.2)]),
    ("board-reset", [("tilt", "30"), ("size", "100"), ("sleep", 1.0)]),
]


def cmd(c, a=""):
    subprocess.run([ADB, "-s", DEV, "shell", "am", "broadcast", "-a", PKG + ".DEBUG", "-p", PKG, "--es", "cmd", c, "--es", "a", '"%s"' % a], capture_output=True)


def pose(p):
    subprocess.run(["pico-cli", "camera", "reset", "-d", DEV], capture_output=True)
    if POSES.get(p):
        subprocess.run(["pico-cli", "camera", "move", "-d", DEV, "--text", POSES[p]], capture_output=True)
    time.sleep(1.0)


def shot(path):
    subprocess.run(["pico-cli", "capture", "screenshot", "-d", DEV, "-o", path, "--replace"], capture_output=True)


def run(out, group, items, lang=None):
    pose(group)
    if lang:
        cmd("lang", lang); time.sleep(0.8)
    for name, steps in items:
        for st in steps:
            if st[0] == "sleep":
                time.sleep(float(st[1]))
            else:
                cmd(st[0], st[1]); time.sleep(0.2)
        raw = os.path.join(out, "raw.png")
        shot(raw)
        im = Image.open(raw)
        tag = f"{name}-{lang}" if lang else name
        if group == "win":
            im.crop(WIN_CROP).save(os.path.join(out, tag + ".png"))
        else:
            im.save(os.path.join(out, tag + ".png"))
        print("shot", tag, flush=True)
    if os.path.exists(os.path.join(out, "raw.png")):
        os.remove(os.path.join(out, "raw.png"))


def main():
    out = sys.argv[1]
    which = sys.argv[2] if len(sys.argv) > 2 else "all"
    langs = {"zh": ["zh"], "en": ["en"], "both": ["zh", "en"]}[sys.argv[3] if len(sys.argv) > 3 else "both"]
    os.makedirs(out, exist_ok=True)
    if which in ("win", "all"):
        for lg in langs:
            run(out, "win", WIN, lg)
    if which in ("board", "all"):
        run(out, "board", BOARD)
    for c, a in (("freeze", "off"), ("hold", "off"), ("hint", "off"), ("pause", "off"), ("lang", "system"), ("tilt", "30"), ("size", "100")):
        cmd(c, a)


main()
