# 转盘五连 Pentago Twist: project guidance

Pentago (6 x 6, four 3 x 3 blocks that turn) for PICO OS 6 Shared Space, PICO Spatial SDK BOM 6.1.9, package
`io.embodit.pentagotwist`, v0.1.0. Design contract: spatial-app-factory `apps/pentago-twist/design-package.json` (v1.1, gate B
2026-11-05; profile A12 one main loop, `v2[]` not built: no XL 8 x 8, game codes, stats, 4th / 5th AI level, hand-over pause,
puzzles, skins, timed play, twist preview, drag pre-turn / spring-back).
Skeleton from abalone-push v0.1.1 (itself royal-ur <- five-stones <- kejing-dev/CloudAir: two containers, theme, sound pool,
level gate, the factory board-input template); rules, AI, flow, scene, models and pages are new.

## Surfaces
- **Board volume** (default container `PentagoBoard`, Volumetric 0.70 x 0.74 x 0.90 m, fixed scale, gravity aligned, uniform
  resize). `frame.glb` (maple 0.50 m) on `tiltRoot` (tilt 0 / 30 / 45 / 60 / 90°, default 30°, pivot on the near-bottom edge),
  four `quadRoot:q` (walnut `quad.glb` with nine dimples, a body collider `quad:q`, nine pad holders `cell:<i>` with collider +
  Interactable + system HoverEffect and a 5 mm `marker.glb` dot, the amber `rim.glb`), 36 marbles (`marble_black/white.glb`,
  each a collider holder named after its cell), two trays (`tray.glb`, 3 x 6 marbles) in front, the arrow handles
  (`handle_{l,r}_{cw,ccw}.glb`, holders `handle:q:cw|ccw`), rings / win band / ghost marble. Move plate (AttachmentPanel
  380 x 66 dp ~ 0.26 x 0.045 m) upright above the far rim: "第 9 手 · 黑：转盘中".
- **Control window** (`PentagoControl`, Planar 560 x 640 dp, `Placement.right(PentagoBoard)`): native SpatialUI `TabBar`, four
  pages 对局 / 新局 / 规则 / 设置; [静音] in every header. Game page: whose turn + mode · move · segment, the status card, a
  mini board (36 cell keys + 8 twist keys, D43: the same `GameFlow.tapCell` / `twist` as a pinch in 3D), [悔一回合 / 撤回这颗]
  (grey with the reason), [给个提示], [暂停], [认输], [重开]. Confirmations and the pause card are whole pages and lock the volume.
  Settings: language, sound, volume (3 stops), reduce motion, tilt (5), size (100 / 120 %), [重开控制窗], about, [退出应用].

## Code
| Path | What |
|---|---|
| `domain/Pentago.kt` | bitboards, cells / blocks, `rotateCell` (CW = far edge goes right, seen from the user), 32 windows, `Rules` (five on placing, twist result, double five, full board, moot twist) |
| `domain/Engine.kt` | 入门 / 中级 / 高手 (win now > never hand over a win > negamax alpha-beta, iterative deepening, 12 / 8 candidate cells x 8 twists) |
| `domain/GameFlow.kt` | the single `PlayState`, phases S00..S17, hit priority of S07, drag release, AI timing, undo (a round, reverse animation) / redo, take-back, pause, hint, save |
| `domain/Game.kt` | config, settings (tilt / size), `SaveCodec` (`files/game.txt`: config + moves + the pending marble) |
| `domain/Layout.kt` | metres for everything at every tilt / size, drag -> twist, eyes, D35 checks (LayoutTest) |
| `ui/board/*` | `BoardScene` (entities, per-frame placement in `onFrame` via `BoardFrameSystem`), `BoardScreen` (input listener, plate), `BoardNames` (names <-> targets), `input/BoardInput.kt` (factory template) |
| `ui/control/*` | the window pages; every Text logs `OVERFLOW <tag>` when cut |
| `tools/` | `build_models.py` (all GLBs), `import_cc0_sounds.py`, `build_icon.py`, `strings.py`, `shots.py`, `blank.py`, `realtap.py`, `loudness.py`, `render_mix.py`, `contrast.py`, `ap.sh` |

Rules - state - render: the scene only renders `PlayState`. Volume input goes through the board-input template (D50: short
drags count as taps, targets resolved up the parent chain); a pinch reaches `BoardNames.dispatch` -> `GameFlow.tapCell / tapQuad /
twist / tapEmpty`, the same calls the panel keys and the debug broadcast make. A press that travels > 2.5 cm on a block is a
twist drag: on release `Layout.twistOfDrag` gives the sense from (r x d) in board-local space and the travel along the edge; >= 5
cm turns it one step.

## Build / test (kj-mbp-m5)
```bash
export JAVA_HOME=~/.pico/primer-cli/jdk/jdk-21.0.13+9/Contents/Home
./gradlew assembleDebug :app:testDebugUnitTest                    # rules, engine (12 games), flow, layout / D35, hit replay
./gradlew :app:testDebugUnitTest --tests '*EngineTest' -Dpt.games=20
python3 tools/build_models.py ; python3 tools/import_cc0_sounds.py ; python3 tools/build_icon.py --icon512 icon.png ; python3 tools/strings.py
DEV=emulator-5564 tools/ap.sh install
```
Emulators are shared: borrow with `python3 -I bin/emu_borrow.py borrow pentago-twist` in the factory, return after.

## Debug broadcast (QA screenshots)
`adb shell am broadcast -a io.embodit.pentagotwist.DEBUG -p io.embodit.pentagotwist --es cmd <cmd> --es a "<arg>"` (or
`tools/ap.sh cmd <cmd> [arg]`). Log tag `PentagoTwist`; input lines also in `files/input-log.txt` (`run-as`).

| State | Command | Notes |
|---|---|---|
| S00 准备中 | `s00hold on`, force-stop + launch, capture within 10 s, `s00hold off` | plain slab + four dark blocks until the GLBs are in |
| S01 第一次 | `goto S01` | default game (人机 · 中级 · 执黑) + rules page; the first pinch or [开始] ends it |
| S02 新局 | `goto S02` (`newmode 2p` first for the two-player page) | [继续上一盘] on top when a save exists |
| S03 待放 | `goto S03` (`coach on` for the first-game line, `hint on` for the hint ring) | |
| S04 预览 | `goto S04 d3` | ghost marble on d3 (hover; Shared Space does not deliver hover, so only debug shows it) |
| S05 放珠中 | `goto S05 [ms]` | the marble held mid-flight (default 120 ms) |
| S06 AI 在想 | `goto S06` | AI holds until `hold off` |
| S07 待转 | `goto S07` · `goto S07 Q1` (block picked, handles out) | rims lit; [撤回这颗] |
| S08 警示 | `goto S08` (e2 taken: "这儿有珠了") | held by freeze; `freeze off` |
| S09 终局 | `goto S09` | sample frame: c4 + Q1 clockwise, a1-a5 light one by one |
| S10 暂停 | `goto S10` | |
| S11 / S14 设置 · S12 规则 | `goto S11` / `goto S12` · `exitask` | tilt `tilt 0|30|45|60|90`, size `size 100|120` |
| S13 确认 | `goto S13` (restart) · `goto S13 resign` · `goto S13 undo2p` · `exitask` · `newask` · `confirm` / `no` | |
| S16 恢复 | make a move, force-stop, relaunch (or `goto S16`) | back to S03 / S07 / S06 |
| S17 转盘中 | `goto S17 [ms]` | the sample twist held at 260 ms (lifted, mid-turn) |
Other: `tap <a1..f6|Q1..Q4|Q1cw|Q1ccw|empty>`, `retract`, `drag <cell|Qn> <dx> <dz> [dy]`, `replay <cell> [hand-ray|eye-hand|controller]`
(real entities -> resolveUp -> listener.onTap, `REPLAY … -> square=`), `hover <cell|off>`, `new "ai|2p black|white|random 1-3"`,
`load "d3Q1+ c4Q2- b2"`, `setup "<rows far->near> side:b|w [2p]"`, `level`, `undo`, `redo`, `hint`, `pause`, `resign`, `restart`, `lang`,
`sound`, `volume 0-2`, `reduced`, `coach`, `seed`, `aitime`, `freeze`, `hold`, `autoplay on|off`, `reopen`, `closepanel`, `dump`, `evidence`.

## Known
- 3D taps / drags are not reachable by `pico-cli interact` on the Mac emulator (only the 2D window publishes nodes); the main
  loop with the real pointer runs through the window's mini board, 3D hits are replayed through the real entities (`replay`).
  Controller ray / hand ray / eye-hand and the 5 cm push: headset only.
- Sounds are SoundPool (not spatial). Physical sounds are CC0 recordings (freesound + Kenney); the "can't" cue is a UI tone.
- Hover: system HoverEffect on the 5 mm dimple markers and the marbles; the app's own ghost marble needs hover events, which
  Shared Space did not deliver on the Go-9 headset test, so it is a bonus only.
