package io.embodit.pentagotwist.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import io.embodit.pentagotwist.domain.ConfirmKind
import io.embodit.pentagotwist.domain.Engine
import io.embodit.pentagotwist.domain.GameConfig
import io.embodit.pentagotwist.domain.GameFlow
import io.embodit.pentagotwist.domain.GameSetup
import io.embodit.pentagotwist.domain.Language
import io.embodit.pentagotwist.domain.Layout
import io.embodit.pentagotwist.domain.Mode
import io.embodit.pentagotwist.domain.Pentago
import io.embodit.pentagotwist.domain.Phase
import io.embodit.pentagotwist.domain.Pos
import io.embodit.pentagotwist.domain.SIZES
import io.embodit.pentagotwist.domain.SeatPick
import io.embodit.pentagotwist.domain.Settings
import io.embodit.pentagotwist.domain.TILTS
import io.embodit.pentagotwist.domain.V3
import io.embodit.pentagotwist.ui.Page
import io.embodit.pentagotwist.ui.Ui
import io.embodit.pentagotwist.ui.board.BoardNames
import io.embodit.pentagotwist.ui.board.SceneHolder
import io.embodit.pentagotwist.ui.board.route
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Debug builds only. Every command goes through the same GameFlow calls the volume and the window use (D29); `replay` even
 * goes through the scene's real entities and the board-input listener (build.md 命中回放).
 *
 *   adb shell am broadcast -a io.embodit.pentagotwist.DEBUG -p io.embodit.pentagotwist --es cmd <cmd> [--es a "<arg>"]
 *
 *   goto S00..S17 [variant]            a named state (AGENTS.md lists them)
 *   tap <a1..f6 | Q1..Q4 | Q1cw | Q1ccw | empty>   a pinch on that target      retract
 *   drag <Q1..Q4|cell> <dx> <dz> [dy]  a drag of the volume vector (m) released at once (the listener's twistOfDrag)
 *   replay <cell> [hand-ray|eye-hand|controller]   resolveUp on the cell's real entities, then listener.onTap (input-replay.txt)
 *   hover <cell|off>  new "<ai|2p> <black|white|random> <1-3>"  load "<d3Q1+ c4Q2- ...>"  setup "<6 rows far->near> side:b|w [2p]"
 *   undo · redo · hint on|off · pause on|off · resign · restart · confirm · no · exitask · newask · page play|new|rules|settings
 *   level 1..3 · lang zh|en|system · tilt 0|30|45|60|90 · size 100|120 · sound on|off · volume 0-2 · reduced on|off · coach on|off
 *   seed N · aitime MS|off · freeze MS|off · hold on|off · s00hold on|off · welcome · autoplay on|off · reopen · closepanel
 *   dump | state · evidence
 */
class DebugCommandReceiver : BroadcastReceiver() {
    companion object {
        private var autoJob: Job? = null
        /** design sampleFirstFrame: black a1 a2 a3 b4, white d2 e2 f3 e5; black places c4 and twists Q1 clockwise -> a1..a5. */
        const val SAMPLE = "....../....o./.x..../x....o/x..oo./x....."
    }

    override fun onReceive(context: Context, intent: Intent) {
        val graph = Graph.from(context)
        val flow = graph.flow
        val cmd = intent.getStringExtra("cmd").orEmpty().trim()
        val a = intent.getStringExtra("a").orEmpty().trim().trim('"')
        val on = a == "on" || a == "true" || a == "1"
        Log.i(TAG, "CMD $cmd $a")
        fun settings(change: (Settings) -> Settings) = graph.settings.update(change)
        fun later(ms: Long, block: () -> Unit) { graph.scope.launch { delay(ms); block() } }
        fun defaultGame(human: Int = Pentago.BLACK, mode: Mode = Mode.AI) = flow.newGame(GameConfig(mode, flow.state.value.config?.aiLevel ?: Engine.MEDIUM, human))
        fun quad(s: String): Int? = s.uppercase().takeIf { it.length >= 2 && it[0] == 'Q' }?.get(1)?.digitToIntOrNull()?.minus(1)?.takeIf { it in 0..3 }
        fun setup(spec: String) {
            val f = spec.split(" ").filter { it.isNotBlank() }
            val pos = Pos.parse(f.first { it.contains("/") })
            val side = if (f.any { it == "side:w" }) Pentago.WHITE else Pentago.BLACK
            val mode = if ("2p" in f) Mode.TWO_PLAYER else Mode.AI
            flow.newGame(GameConfig(mode, flow.state.value.config?.aiLevel ?: Engine.MEDIUM, if (mode == Mode.AI) side else Pentago.BLACK), pos, side)
        }
        fun tap(t: String) {
            val c = Pentago.parse(t)
            val q = quad(t)
            when {
                c != null -> route(graph, BoardNames.CellHit(c))
                q != null && t.lowercase().endsWith("ccw") -> route(graph, BoardNames.HandleHit(q, Pentago.CCW))
                q != null && t.lowercase().endsWith("cw") -> route(graph, BoardNames.HandleHit(q, Pentago.CW))
                q != null -> route(graph, BoardNames.QuadHit(q))
                else -> route(graph, BoardNames.EmptyHit)
            }
        }
        when (cmd) {
            "goto" -> {
                val f = a.split(" ")
                flow.freeze(null); flow.holdThinking = false; flow.pause(false)
                when (f[0].uppercase()) {
                    "S00" -> Log.i(TAG, "S00 is the first second after launch: s00hold on, force-stop and start, then capture")
                    "S01" -> { settings { it.copy(firstRunDone = false) }; flow.start() }
                    "S02" -> { flow.toMenu(); Ui.page = Page.NEW }
                    "S03" -> { defaultGame(); Ui.page = Page.PLAY }
                    "S04" -> { defaultGame(); Ui.page = Page.PLAY; SceneHolder.scene?.setHover(BoardNames.cell(Pentago.parse(f.getOrNull(1) ?: "d3")!!)) }
                    "S05" -> { defaultGame(); Ui.page = Page.PLAY; flow.freeze(f.getOrNull(1)?.toLongOrNull() ?: 120L); tap("d3") }
                    "S06" -> { flow.holdThinking = true; defaultGame(Pentago.WHITE); Ui.page = Page.PLAY }
                    "S07" -> { defaultGame(); Ui.page = Page.PLAY; tap("d3"); f.getOrNull(1)?.let { v -> later(600) { tap(v) } } }
                    "S08" -> { setup(SAMPLE); Ui.page = Page.PLAY; flow.freeze(60_000); tap(f.getOrNull(1) ?: "e2") }
                    "S09" -> { setup(SAMPLE); Ui.page = Page.PLAY; tap("c4"); later(600) { tap("Q1cw") } }
                    "S10" -> { defaultGame(); Ui.page = Page.PLAY; tap("d3"); later(600) { tap("Q2cw") }; later(1700) { flow.pause(true) } }
                    "S11", "S14" -> Ui.page = Page.SETTINGS
                    "S12" -> Ui.page = Page.RULES
                    "S13" -> { Ui.page = Page.PLAY; flow.requestConfirm(when (f.getOrNull(1)) { "resign" -> ConfirmKind.RESIGN; "undo2p" -> ConfirmKind.UNDO_2P; else -> ConfirmKind.RESTART }) }
                    "S16" -> { Log.i(TAG, "restore=${flow.continueSaved()}"); Ui.page = Page.PLAY }
                    "S17" -> { setup(SAMPLE); Ui.page = Page.PLAY; tap("c4"); later(600) { flow.freeze(f.getOrNull(1)?.toLongOrNull() ?: 260L); tap("Q1cw") } }
                    else -> Log.w(TAG, "unknown state $a")
                }
            }
            "tap" -> tap(a)
            "retract" -> flow.retract()
            "drag" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                val c = Pentago.parse(f[0]); val q = c?.let { Pentago.quadOf(it) } ?: quad(f[0]) ?: return
                val d = V3(f.getOrNull(1)?.toFloatOrNull() ?: 0f, f.getOrNull(3)?.toFloatOrNull() ?: 0f, f.getOrNull(2)?.toFloatOrNull() ?: 0f)
                if (flow.dragStart(q)) {
                    val (dir, along) = Layout.twistOfDrag(q, c, d, graph.settings.value.tilt)
                    Log.i(TAG, "DRAG debug ${Pentago.quadName(q)} d=$d -> dir=$dir along=$along")
                    flow.dragEnd(q, dir, along)
                }
            }
            "replay" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                val c = Pentago.parse(f.getOrNull(0) ?: "") ?: return
                SceneHolder.replay(c, f.getOrNull(1) ?: "hand-ray")
            }
            "hover" -> SceneHolder.scene?.setHover(Pentago.parse(a)?.let { BoardNames.cell(it) })
            "new" -> {
                val f = a.split(" ").filter { it.isNotBlank() }
                flow.newGame(GameSetup(
                    mode = if ("2p" in f) Mode.TWO_PLAYER else Mode.AI,
                    aiLevel = f.firstNotNullOfOrNull { it.toIntOrNull() }?.coerceIn(1, 3) ?: Engine.MEDIUM,
                    seat = when { "white" in f -> SeatPick.WHITE; "random" in f -> SeatPick.RANDOM; else -> SeatPick.BLACK },
                ))
                Ui.page = Page.PLAY
            }
            "load" -> {
                // whole moves in order, black first: "d3Q1+ c4Q2- b2" (no block = a skipped / not needed twist), as a 2P game
                flow.newGame(GameConfig(Mode.TWO_PLAYER, Engine.MEDIUM, Pentago.BLACK))
                Ui.page = Page.PLAY
                val moves = a.split(" ").filter { it.isNotBlank() }
                graph.scope.launch {
                    for (m in moves) {
                        while (flow.state.value.phase != Phase.PLACE) { if (flow.state.value.phase == Phase.RESULT) return@launch; delay(50) }
                        tap(m.substring(0, 2))
                        if (m.length >= 4) {
                            while (flow.state.value.phase != Phase.TWIST) { if (flow.state.value.phase in setOf(Phase.RESULT, Phase.PLACE)) break; delay(50) }
                            if (flow.state.value.phase == Phase.TWIST) tap(m.substring(2, 4) + if (m.endsWith("-")) "ccw" else "cw")
                        }
                    }
                }
            }
            "setup" -> { setup(a); Ui.page = Page.PLAY }
            "newmode" -> settings { it.copy(lastSetup = it.lastSetup.copy(mode = if (a == "2p") Mode.TWO_PLAYER else Mode.AI)) }
            "level" -> a.toIntOrNull()?.let { l -> flow.debugState { it.copy(config = it.config?.copy(aiLevel = l.coerceIn(1, 3))) } }
            "undo" -> flow.undo()
            "redo" -> flow.redo()
            "hint" -> flow.setHint(on)
            "pause" -> flow.pause(on)
            "resign" -> flow.requestConfirm(ConfirmKind.RESIGN)
            "restart" -> flow.requestConfirm(ConfirmKind.RESTART)
            "confirm" -> flow.confirm()
            "no" -> flow.cancelConfirm()
            "exitask" -> { Ui.page = Page.SETTINGS; Ui.exitConfirm = true }
            "newask" -> { Ui.page = Page.NEW; if (flow.state.value.hasProgress) Ui.newConfirm = true }
            "page" -> Ui.page = when (a) { "new" -> Page.NEW; "rules" -> Page.RULES; "settings" -> Page.SETTINGS; else -> Page.PLAY }
            "lang" -> settings { it.copy(language = when (a) { "zh" -> Language.ZH; "en" -> Language.EN; else -> Language.SYSTEM }) }
            "tilt" -> a.toIntOrNull()?.takeIf { it in TILTS }?.let { v -> settings { it.copy(tilt = v) } }
            "size" -> a.toIntOrNull()?.takeIf { it in SIZES }?.let { v -> settings { it.copy(size = v) } }
            "sound" -> settings { it.copy(sound = on) }
            "volume" -> a.toIntOrNull()?.let { v -> settings { it.copy(volume = v.coerceIn(0, 2)) } }
            "reduced" -> settings { it.copy(reducedMotion = on) }
            "coach" -> { settings { it.copy(coach = on) }; flow.coachForced = on; flow.debugState { it.copy(coachStep = if (on) 0 else -1) } }
            "seed" -> a.toIntOrNull()?.let { flow.reseed(it) }
            "aitime" -> flow.aiTimeMs = a.toLongOrNull()
            "freeze" -> flow.freeze(if (a == "off") null else a.toLongOrNull())
            "hold" -> flow.holdThinking = on
            "s00hold" -> context.getSharedPreferences("debug", Context.MODE_PRIVATE).edit().putBoolean("s00hold", on).apply()
            "welcome" -> flow.welcomeDone()
            "autoplay" -> {
                autoJob?.cancel()
                if (on) {
                    flow.newGame(GameConfig(Mode.TWO_PLAYER, Engine.HARD, Pentago.BLACK))
                    // both sides are "human" in 2P: each turn is played with the medium AI through tapCell / twist (the real path)
                    autoJob = graph.scope.launch {
                        var n = 0
                        while (isActive) {
                            val st = flow.state.value
                            if (st.phase == Phase.RESULT) { Log.i(TAG, "AUTOPLAY done moves=${st.moves.size} winner=${st.winner} outcome=${st.outcome}"); break }
                            if (st.phase == Phase.PLACE) {
                                val m = Engine.choose(st.pos, st.side, if (n % 2 == 0) Engine.HARD else Engine.EASY, kotlin.random.Random(n++), 200L) ?: break
                                flow.tapCell(m.cell)
                                delay(GameFlow.FLY_MS + 150)
                                if (m.quad >= 0 && flow.state.value.phase == Phase.TWIST) flow.twist(m.quad, m.dir)
                            }
                            delay(150)
                        }
                    }
                }
            }
            "reopen" -> graph.window.reopen()
            "closepanel" -> graph.window.closeControlOnly()
            "dump", "state" -> Log.i(TAG, "DUMP ${flow.dump()} page=${Ui.page} window=${graph.window.panelState} tilt=${graph.settings.value.tilt} size=${graph.settings.value.size}")
            "evidence" -> Log.i(TAG, "EVIDENCE ${SceneHolder.scene?.evidence() ?: "no scene"}")
            else -> Log.w(TAG, "unknown debug command: $cmd")
        }
    }
}
