package io.embodit.pentagotwist.domain

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** Named states of design-package states[] that live in the game (S01 / S04 / S08 / S10..S16 are overlays, pages or scene-only). */
enum class Phase(val id: String) {
    LOADING("S00"), SETUP("S02"), PLACE("S03"), PLACING("S05"), THINKING("S06"), TWIST("S07"), TWISTING("S17"), RESULT("S09"),
}

/** Sound events (design sound.list). Selecting a block / hover / the hint toggle are silent (profile D51 补 2026-11-06). */
enum class Sound { PLACE, RETRACT, CLICK, LAND, ILLEGAL, WIN, LOSE, DRAW }

enum class Reason { OCCUPIED, PLACE_FIRST, THINKING, BUSY, PAUSED }

/** S08: a one-liner + a red ring flash on [cell] (the marble already there) or the block rim [quad]. */
data class Alert(val reason: Reason, val cell: Int?, val quad: Int?, val at: Long)

/** One leg of an animation. [before] = the board shown under it (the flying marble is not in it). */
sealed interface Step
/** A marble flies dish -> [cell] (or back to the dish when [toDish]). */
data class Fly(val cell: Int, val side: Int, val toDish: Boolean) : Step
/** Block [quad] turns [dir]; [lift] = the full lift (3 cm) - turn - drop of a twist, false = a flat undo turn. */
data class Turn(val quad: Int, val dir: Int, val lift: Boolean) : Step

data class AnimStep(val step: Step, val before: Pos, val durMs: Long)

data class Anim(val steps: List<AnimStep>, val startedAt: Long) {
    val totalMs: Long get() = steps.sumOf { it.durMs }
}

enum class ConfirmKind { RESTART, RESIGN, UNDO_2P }

/** The one sentence of the window (long form) and the plate (short form), D38 / D45: one source. */
enum class StatusKey { NONE, PLACE, COACH_1, COACH_2, COACH_3, TWIST, TWIST_PICKED, PLACING, THINKING, TWISTING, SKIPPED, ALERT, RESTORED, PAUSED, OVER }

data class Status(val key: StatusKey, val side: Int = 0, val quad: Int = -1, val reason: Reason? = null, val winner: Int? = null, val outcome: Outcome? = null)

/** The one state every surface reads (board scene, control window, the move plate, debug dump). */
data class PlayState(
    val phase: Phase = Phase.LOADING,
    val config: GameConfig? = null,
    val startPos: Pos = Pos(),
    val startSide: Int = Pentago.BLACK,
    val moves: List<Move> = emptyList(),
    /** positions[i] = the board after i whole moves (positions[0] = startPos). */
    val positions: List<Pos> = listOf(Pos()),
    /** The marble placed this turn and not twisted yet (S07; the撤回 window is this phase, not a timer). */
    val pending: Int? = null,
    /** S07: the block picked by a tap (its two arrow handles are out). */
    val picked: Int? = null,
    /** S07: the block held by a drag (only its rim is lit). */
    val held: Int? = null,
    val anim: Anim? = null,
    val alert: Alert? = null,
    val winner: Int? = null,
    val outcome: Outcome? = null,
    val winRuns: List<Pair<Int, List<Int>>> = emptyList(),
    val hintOn: Boolean = false,
    val hint: Move? = null,
    val hintsUsed: Int = 0,
    val confirm: ConfirmKind? = null,
    val paused: Boolean = false,
    val redo: List<Move> = emptyList(),
    val elapsedMs: Long = 0,
    val resumedAt: Long = 0,
    val welcome: Boolean = false,
    val coachStep: Int = -1,
    val restored: Boolean = false,
    val skipped: Boolean = false,
    val savedAvailable: Boolean = false,
    val savedMoves: Int = 0,
    /** Debug hold: animations stop this many ms after their start. */
    val freezeMs: Long? = null,
    val freezeAt: Long = 0,
) {
    val pos: Pos get() = positions.last()
    /** The board with this turn's marble in it. */
    val board: Pos get() = pending?.let { pos.place(it, side) } ?: pos
    val side: Int get() = (startSide + moves.size) % 2
    val humansTurn: Boolean get() = config?.isHuman(side) == true
    val inGame: Boolean get() = config != null && phase in setOf(Phase.PLACE, Phase.PLACING, Phase.THINKING, Phase.TWIST, Phase.TWISTING)
    val hasProgress: Boolean get() = inGame && (moves.isNotEmpty() || pending != null)
    /** Whose turn the plate / header shows: the mover until its twist has landed. */
    val shownSide: Int get() = if (phase == Phase.TWISTING || (phase == Phase.RESULT && moves.isNotEmpty())) moves.last().side else side
    /** The move number in progress (plate "第 9 手"). */
    val moveNo: Int get() = (if (phase == Phase.PLACE || phase == Phase.THINKING) board.count + 1 else board.count).coerceIn(1, Pentago.N)
    val lastMove: Move? get() = moves.lastOrNull()
    val draw: Boolean get() = phase == Phase.RESULT && winner == null

    /** Whole moves taken back by one [悔一回合] (an AI game takes the AI's reply along), 0 when there is none. */
    fun undoCount(): Int {
        val cfg = config ?: return 0
        if (moves.isEmpty()) return 0
        if (cfg.mode == Mode.TWO_PLAYER) return 1
        // back through the human's last move (then it is the human's turn again)
        for (k in 1..moves.size) if (moves[moves.size - k].side == cfg.humanSide) return k
        return 0
    }

    /** S07: [悔一回合] is "撤回这颗" (takes the marble back, not an undo). S03: a whole round. Grey while the AI thinks / during a twist (D43). */
    val canUndo: Boolean get() = humansTurn && !paused && confirm == null &&
        ((phase == Phase.TWIST && pending != null) || (phase == Phase.PLACE && undoCount() > 0))
    val canRedo: Boolean get() = redo.isNotEmpty() && humansTurn && phase == Phase.PLACE && !paused

    fun status(): Status {
        val cfg = config ?: return Status(StatusKey.NONE)
        if (paused && inGame) return Status(StatusKey.PAUSED, side)
        alert?.let { return Status(StatusKey.ALERT, side, reason = it.reason) }
        return when (phase) {
            Phase.RESULT -> Status(StatusKey.OVER, shownSide, winner = winner, outcome = outcome)
            Phase.THINKING -> Status(StatusKey.THINKING, side)
            Phase.PLACING -> Status(StatusKey.PLACING, side)
            Phase.TWISTING -> Status(StatusKey.TWISTING, shownSide)
            Phase.TWIST -> when {
                !humansTurn -> Status(StatusKey.TWISTING, side)
                coachStep == 1 -> Status(StatusKey.COACH_2, side)
                picked != null -> Status(StatusKey.TWIST_PICKED, side, quad = picked)
                else -> Status(StatusKey.TWIST, side)
            }
            Phase.PLACE -> when {
                skipped -> Status(StatusKey.SKIPPED, side)
                coachStep == 0 -> Status(StatusKey.COACH_1, side)
                coachStep == 2 -> Status(StatusKey.COACH_3, side)
                restored -> Status(StatusKey.RESTORED, side)
                else -> Status(StatusKey.PLACE, side)
            }
            else -> Status(StatusKey.NONE)
        }
    }
}

/**
 * Turn sequencing (rules - state - render: the middle layer). Main-thread confined: every public call and every coroutine
 * it launches runs on [scope]'s dispatcher; the AI runs on [engineDispatcher]. Debug commands, 3D pinches and panel keys
 * call the same functions (profile D29).
 *
 * A turn: S03 place (tapCell) -> S05 the marble flies 220 ms -> five? S09 : moot twist? skip : S07 twist (tapHandle / a drag
 * past 5 cm / a panel key) -> S17 lift 80 + turn 350 + drop 80 ms -> result or the next turn. Results are committed and saved
 * before the animation plays (Cloud Air rule); the animation only decides when the next phase starts.
 */
class GameFlow(
    private val scope: CoroutineScope,
    private val engineDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val store: GameStore,
    private val settings: () -> Settings,
    private val onSettings: (Settings) -> Unit = {},
    private val onSound: (Sound) -> Unit = {},
    private val log: (String) -> Unit = {},
    seed: Int = 20261106,
) {
    companion object {
        const val FLY_MS = 220L
        const val RETRACT_MS = 200L
        const val LIFT_MS = 80L
        const val TURN_MS = 350L
        const val DROP_MS = 80L
        const val TWIST_MS = LIFT_MS + TURN_MS + DROP_MS
        const val UNDO_STEP_MS = 200L
        const val REDUCED_TWIST_MS = 120L
        const val REDUCED_FLY_MS = 120L
        const val ALERT_MS = 1500L
        const val FLASH_MS = 300L
        const val SKIP_NOTICE_MS = 1500L
        const val MIN_THINK_MS = 400L
        /** The AI's marble lands, a short look, then its twist (it has no take-back window). */
        const val AI_SHOW_MS = 250L
        const val RESULT_SOUND_DELAY_MS = 200L
        /** A drag must travel this far along the block's edge to turn it (design input.twistDrag, D18 one step). */
        const val DRAG_TURN_M = 0.05f
    }

    private val _state = MutableStateFlow(PlayState())
    val state: StateFlow<PlayState> = _state.asStateFlow()
    private val s: PlayState get() = _state.value

    private var rng = Random(seed)
    var aiSeed: Int = seed
    var holdThinking: Boolean = false
    /** A window-only confirmation (exit / new game) is open: the volume is locked too. */
    @Volatile var uiLocked: Boolean = false
    /** Debug / tests: the AI's think time cap (null = the level's own). */
    var aiTimeMs: Long? = null

    private var turnJob: Job? = null
    private var alertJob: Job? = null
    private var hintJob: Job? = null
    private var skipJob: Job? = null
    private var aiPlan: Move? = null
    /** A drag that started in the wrong phase: swallowed (no tap at its end). */
    private var dragRefused = false

    private fun set(next: PlayState) { _state.value = next }
    private val reduced: Boolean get() = settings().reducedMotion

    // ---- lifecycle ----

    /** S00 exits: first run -> the default game + rules page (S01); a save -> S16; otherwise S02. */
    fun start() {
        val saved = store.load()
        if (!settings().firstRunDone) {
            newGame(GameConfig(Mode.AI, Engine.MEDIUM, Pentago.BLACK))
            set(s.copy(welcome = true))
            log("STATE S01 (default game, rules page open)")
            return
        }
        if (saved != null && saved.resumable) restore(saved) else { set(PlayState(phase = Phase.SETUP, savedAvailable = false)); log("STATE S02") }
    }

    fun welcomeDone() {
        if (!s.welcome && settings().firstRunDone) return
        onSettings(settings().copy(firstRunDone = true))
        set(s.copy(welcome = false))
        log("WELCOME done")
    }

    fun continueSaved(): Boolean {
        val saved = store.load() ?: return false
        restore(saved)
        return true
    }

    private fun positionsOf(start: Pos, moves: List<Move>): List<Pos> {
        val out = ArrayList<Pos>(moves.size + 1)
        out += start
        for (m in moves) out += Rules.apply(out.last(), m)
        return out
    }

    private fun restore(saved: SavedGame) {
        cancelJobs()
        val positions = positionsOf(Pos(), saved.moves)
        set(PlayState(
            phase = Phase.PLACE, config = saved.config, moves = saved.moves, positions = positions, pending = saved.pending, redo = saved.redo,
            elapsedMs = saved.elapsedMs, resumedAt = clock(), hintOn = saved.hintOn, hintsUsed = saved.hintsUsed,
            restored = true, savedAvailable = true, savedMoves = saved.moves.size,
        ))
        log("GAME restored moves=${saved.moves.size} pending=${saved.pending?.let { Pentago.name(it) }} side=${s.side} S16")
        if (finishIfOver()) return
        if (s.pending != null) afterPlaced(resumed = true) else nextTurn()
    }

    fun newGame(setup: GameSetup) {
        val human = when (setup.seat) { SeatPick.BLACK -> Pentago.BLACK; SeatPick.WHITE -> Pentago.WHITE; SeatPick.RANDOM -> if (rng.nextBoolean()) Pentago.BLACK else Pentago.WHITE }
        onSettings(settings().copy(lastSetup = setup))
        newGame(GameConfig(setup.mode, setup.aiLevel.coerceIn(1, 3), human))
    }

    fun newGame(config: GameConfig, start: Pos = Pos(), startSide: Int = Pentago.BLACK) {
        cancelJobs()
        val firstGame = settings().coach && (!settings().firstRunDone || coachForced)
        set(PlayState(
            phase = Phase.PLACE, config = config, startPos = start, startSide = startSide, positions = listOf(start), resumedAt = clock(),
            hintOn = s.hintOn, welcome = s.welcome, coachStep = if (firstGame) 0 else -1,
            freezeMs = s.freezeMs, freezeAt = s.freezeAt,
        ))
        log("GAME new mode=${config.mode} level=${config.aiLevel} human=${config.humanSide} start=$start")
        persist()
        nextTurn()
    }

    /** Debug `coach on` forces the coach lines into the next game. */
    var coachForced = false

    fun again() { s.config?.let { newGame(it) } }

    fun toMenu() {
        cancelJobs()
        val saved = store.load()
        set(PlayState(phase = Phase.SETUP, savedAvailable = saved?.resumable == true, savedMoves = saved?.moves?.size ?: 0, hintOn = s.hintOn))
        log("STATE S02")
    }

    /** Going to the background: stop the AI, land an animation in flight (its result is already committed), keep the game. */
    fun onBackground() {
        if (s.anim != null) { turnJob?.cancel(); landAnim() }
        if (s.phase == Phase.THINKING) turnJob?.cancel()
        persist()
        log("BACKGROUND phase=${s.phase.id}")
    }

    fun onForeground() {
        if (s.config == null || turnJob?.isActive == true) return
        if (s.phase == Phase.THINKING && !s.humansTurn) aiTurn()
        if (s.phase == Phase.TWIST && !s.humansTurn) afterPlaced(resumed = true)
    }

    // ---- input (volume entities, panel keys, debug: one path) ----

    private fun locked(): Boolean {
        if (s.confirm != null || uiLocked) { log("LOCKED by confirm (S13)"); return true }
        if (s.paused) { alert(Reason.PAUSED, null, null, Sound.ILLEGAL); return true }
        return false
    }

    /** A pinch / trigger on a dimple pad or a marble (both name the cell). */
    fun tapCell(c: Int) {
        val st = s
        log("TAP cell ${Pentago.name(c)} owner=${st.board.owner(c)} phase=${st.phase.id} pending=${st.pending?.let { Pentago.name(it) }}")
        if (st.config == null || st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        if (locked()) return
        if (!busyCheck(st)) return
        if (st.welcome) welcomeDone()
        when (st.phase) {
            Phase.PLACE -> {
                if (!st.board.empty(c)) { alert(Reason.OCCUPIED, c, null); return }
                commitPlace(c)
            }
            Phase.TWIST -> {
                // design input.hitPriority S07: the marble just placed -> take it back; any other pad / marble -> pick its block
                if (c == st.pending) retract() else pickQuad(Pentago.quadOf(c))
            }
            else -> Unit
        }
    }

    /** A pinch on a block's body (its rim, between the dimples). */
    fun tapQuad(q: Int) {
        val st = s
        log("TAP quad ${Pentago.quadName(q)} phase=${st.phase.id}")
        if (st.config == null || st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        if (locked() || !busyCheck(st)) return
        when (st.phase) {
            Phase.PLACE -> alert(Reason.PLACE_FIRST, null, q)
            Phase.TWIST -> pickQuad(q)
            else -> Unit
        }
    }

    /** A pinch on an arrow handle, or a panel twist key (same call, D43). */
    fun twist(q: Int, dir: Int) {
        val st = s
        log("TWIST ask ${Pentago.quadName(q)} dir=$dir phase=${st.phase.id}")
        if (st.config == null || st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        if (locked() || !busyCheck(st)) return
        when (st.phase) {
            Phase.PLACE -> alert(Reason.PLACE_FIRST, null, q)
            Phase.TWIST -> commitTwist(q, dir)
            else -> Unit
        }
    }

    /** A pinch on nothing of ours (the frame, the dishes, air): drop a picked block. */
    fun tapEmpty() {
        log("TAP empty phase=${s.phase.id}")
        if (s.phase == Phase.TWIST && s.picked != null && s.humansTurn && !locked()) set(s.copy(picked = null))
    }

    /** S06 / S17 / S05: any tap only flashes the status line (D43), returns false. */
    private fun busyCheck(st: PlayState): Boolean {
        val busy = when {
            st.phase == Phase.TWISTING || st.phase == Phase.PLACING -> Reason.BUSY
            st.phase == Phase.THINKING || !st.humansTurn -> Reason.THINKING
            else -> null
        } ?: return true
        alert(busy, null, null, sound = null)
        return false
    }

    private fun pickQuad(q: Int) {
        set(s.copy(picked = q, alert = null))
        log("PICK ${Pentago.quadName(q)} (handles out) S07")
    }

    /** A press on [q]'s pad / marble / body started moving (board-input onDragStart). True = we take the drag. */
    fun dragStart(q: Int): Boolean {
        val st = s
        dragRefused = false
        if (st.config == null || st.phase == Phase.RESULT || st.phase == Phase.SETUP) return false
        if (locked() || !busyCheck(st)) { dragRefused = true; return true }
        if (st.phase == Phase.PLACE) { alert(Reason.PLACE_FIRST, null, q); dragRefused = true; return true }
        if (st.phase != Phase.TWIST) { dragRefused = true; return true }
        set(st.copy(held = q, picked = null))
        log("HOLD ${Pentago.quadName(q)} rim lit")
        return true
    }

    /** The drag let go: [along] = metres travelled along the block's edge, [dir] = which way (Layout.twistOfDrag). */
    fun dragEnd(q: Int, dir: Int, along: Float) {
        if (dragRefused) { dragRefused = false; log("DRAG swallowed"); return }
        val st = s
        if (st.phase != Phase.TWIST || st.held != q) { dragCancel(); return }
        log("RELEASE ${Pentago.quadName(q)} along=${"%.3f".format(along)} dir=$dir")
        if (along >= DRAG_TURN_M && (dir == Pentago.CW || dir == Pentago.CCW)) commitTwist(q, dir) else set(st.copy(held = null))
    }

    fun dragCancel() {
        dragRefused = false
        if (s.held != null) set(s.copy(held = null))
    }

    private fun alert(reason: Reason, cell: Int?, quad: Int?, sound: Sound? = Sound.ILLEGAL) {
        val at = clock()
        set(s.copy(alert = Alert(reason, cell, quad, at)))
        sound?.let(onSound)
        log("ALERT $reason cell=${cell?.let { Pentago.name(it) }} quad=$quad S08")
        alertJob?.cancel()
        alertJob = scope.launch {
            delay(ALERT_MS)
            awaitUnfrozen()
            if (s.alert?.at == at) set(s.copy(alert = null))
        }
    }

    // ---- placing / twisting ----

    private fun commitPlace(c: Int) {
        val st = s
        val human = st.humansTurn
        val dur = if (reduced) REDUCED_FLY_MS else FLY_MS
        set(st.copy(
            phase = Phase.PLACING, pending = c, picked = null, held = null, hint = null, alert = null, skipped = false, restored = false,
            anim = Anim(listOf(AnimStep(Fly(c, st.side, toDish = false), st.pos, dur)), clock()),
            redo = if (human) emptyList() else st.redo,
        ))
        log("PLACE ${Pentago.name(c)} by=${if (human) "human" else "ai"} side=${st.side} S05")
        persist()
        turnJob?.cancel()
        turnJob = scope.launch {
            playAnim()
            onSound(Sound.PLACE)
            set(s.copy(anim = null))
            afterPlaced(resumed = false)
        }
    }

    /** The marble is in: a five wins, a moot twist is skipped, otherwise S07 (or the AI's twist). */
    private fun afterPlaced(resumed: Boolean) {
        val st = s
        val c = st.pending ?: return nextTurn()
        val placed = st.board
        if (placed.five(st.side)) { appendMove(Move(st.side, c, -1, 0)); finish(st.side, Outcome.WIN_PLACE); return }
        if (placed.twistIsMoot) {
            appendMove(Move(st.side, c, -1, 0))
            log("SKIP twist (no twist changes the board)")
            if (finishIfOver()) return
            set(s.copy(skipped = true))
            skipJob?.cancel()
            skipJob = scope.launch { delay(SKIP_NOTICE_MS); if (s.skipped) set(s.copy(skipped = false)) }
            nextTurn(keepSkip = true)
            return
        }
        if (st.humansTurn) {
            set(st.copy(phase = Phase.TWIST, coachStep = if (st.coachStep == 0) 1 else st.coachStep))
            log("STATE S07 side=${st.side}")
            refreshHint()
            return
        }
        // the AI's second half
        set(st.copy(phase = Phase.TWIST))
        val plan = aiPlan?.takeIf { it.cell == c && !resumed && it.quad >= 0 } ?: Engine.chooseTwist(placed, st.side, c)
        turnJob = scope.launch {
            delay(if (reduced) 0 else AI_SHOW_MS)
            while (s.paused) delay(100)
            if (s.phase != Phase.TWIST || s.pending != c) return@launch
            if (plan.quad >= 0) commitTwist(plan.quad, plan.dir) else { appendMove(plan); if (!finishIfOver()) nextTurn() }
        }
    }

    private fun appendMove(m: Move) {
        val st = s
        set(st.copy(moves = st.moves + m, positions = st.positions + Rules.apply(st.pos, m), pending = null, picked = null, held = null))
        persist()
    }

    private fun commitTwist(q: Int, dir: Int) {
        val st = s
        val c = st.pending ?: return
        val before = st.board
        val m = Move(st.side, c, q, dir)
        val dur = if (reduced) REDUCED_TWIST_MS else TWIST_MS
        set(st.copy(
            phase = Phase.TWISTING, moves = st.moves + m, positions = st.positions + before.twist(q, dir), pending = null, picked = null, held = null,
            hint = null, alert = null, anim = Anim(listOf(AnimStep(Turn(q, dir, lift = !reduced), before, dur)), clock()),
            coachStep = when (st.coachStep) { 1 -> 2; 2 -> -1; else -> st.coachStep }.let { if (st.humansTurn) it else st.coachStep },
        ))
        log("TWIST ${m.text()} by=${if (st.config?.isHuman(st.side) == true) "human" else "ai"} S17")
        persist()
        turnJob?.cancel()
        turnJob = scope.launch {
            if (reduced) { playAnim(); onSound(Sound.LAND) } else {
                // wooden teeth: three clicks across the 350 ms turn, then the drop
                delay(LIFT_MS)
                repeat(3) { i -> onSound(Sound.CLICK); delay(if (i < 2) TURN_MS / 3 else TURN_MS - 2 * (TURN_MS / 3)) }
                delay(DROP_MS)
                awaitUnfrozen()
                onSound(Sound.LAND)
            }
            set(s.copy(anim = null))
            if (finishIfOver()) return@launch
            nextTurn()
        }
    }

    /** S07 take-back: the marble flies back to the dish (not an undo), S03 again. */
    fun retract() {
        val st = s
        val c = st.pending ?: return
        if (st.phase != Phase.TWIST || !st.humansTurn) return
        set(st.copy(phase = Phase.PLACING, pending = null, picked = null, held = null, hint = null,
            anim = Anim(listOf(AnimStep(Fly(c, st.side, toDish = true), st.pos, if (reduced) REDUCED_FLY_MS else RETRACT_MS)), clock())))
        log("RETRACT ${Pentago.name(c)}")
        persist()
        onSound(Sound.RETRACT)
        turnJob?.cancel()
        turnJob = scope.launch {
            playAnim()
            set(s.copy(anim = null, phase = Phase.PLACE))
            log("STATE S03 side=${s.side} (taken back)")
            refreshHint()
        }
    }

    /** Waits out the current animation (a debug freeze holds it). */
    private suspend fun playAnim() {
        val a = s.anim ?: return
        delay(a.totalMs)
        awaitUnfrozen()
    }

    /** Background during an animation: its result is already in the state; just drop the animation. */
    private fun landAnim() {
        val st = s
        set(st.copy(anim = null))
        when (st.phase) {
            Phase.PLACING -> if (st.pending != null) afterPlaced(resumed = true) else if (!finishIfOver()) nextTurn()
            Phase.TWISTING -> if (!finishIfOver()) nextTurn()
            else -> Unit
        }
    }

    private fun finishIfOver(): Boolean {
        val st = s
        val m = st.lastMove ?: return false
        val before = st.positions[st.positions.size - 2]
        val r = Rules.outcome(before, m) ?: return false
        finish(r.second, r.first)
        return true
    }

    private fun nextTurn(keepSkip: Boolean = false) {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT) return
        if (cfg.isHuman(st.side)) {
            set(st.copy(phase = Phase.PLACE, pending = null, picked = null, held = null, skipped = keepSkip && st.skipped))
            log("STATE S03 side=${st.side}")
            refreshHint()
        } else aiTurn()
    }

    // ---- AI ----

    private fun aiTurn() {
        val st = s
        val cfg = st.config ?: return
        set(st.copy(phase = Phase.THINKING, picked = null, held = null))
        log("THINK side=${st.side} level=${cfg.aiLevel} S06")
        val pos = st.pos
        val side = st.side
        val ply = st.moves.size
        turnJob?.cancel()
        turnJob = scope.launch {
            val t0 = clock()
            val job = coroutineContext[Job]
            val pick = withContext(engineDispatcher) {
                Engine.choose(pos, side, cfg.aiLevel, Random(aiSeed + ply * 31), aiTimeMs) { job?.isActive != true }
            } ?: return@launch
            val waited = clock() - t0
            log("AI ${pick.text()} level=${cfg.aiLevel} in $waited ms")
            if (waited < MIN_THINK_MS) delay(MIN_THINK_MS - waited)
            while (holdThinking || s.paused) delay(100)
            if (s.phase != Phase.THINKING || s.pos != pos) return@launch
            aiPlan = pick
            commitPlace(pick.cell)
        }
    }

    // ---- hint, undo / redo ----

    private fun refreshHint() {
        val st = s
        hintJob?.cancel()
        if (!st.hintOn || !st.humansTurn || st.phase !in setOf(Phase.PLACE, Phase.TWIST)) { if (st.hint != null) set(st.copy(hint = null)); return }
        val pos = st.pos; val side = st.side; val ply = st.moves.size; val pending = st.pending; val placed = st.board
        hintJob = scope.launch {
            val h = withContext(engineDispatcher) {
                if (pending != null) Engine.chooseTwist(placed, side, pending) else Engine.choose(pos, side, Engine.MEDIUM, Random(aiSeed + ply), 300L)
            }
            if (s.pos != pos || s.pending != pending || !s.hintOn) return@launch
            set(s.copy(hint = h, hintsUsed = s.hintsUsed + 1))
            log("HINT ${h?.text()}")
            persist()
        }
    }

    fun setHint(on: Boolean) {
        set(s.copy(hintOn = on, hint = if (on) s.hint else null))
        log("HINT ${if (on) "on" else "off"}")
        refreshHint()
        persist()
    }

    /** [悔一回合]: in S07 it takes the marble back; in S03 one whole round (2P asks "对方同意了？" first). */
    fun undo() {
        val st = s
        if (!st.canUndo) { log("UNDO not available"); return }
        if (st.phase == Phase.TWIST) { retract(); return }
        if (st.config?.mode == Mode.TWO_PLAYER && st.confirm == null) { requestConfirm(ConfirmKind.UNDO_2P); return }
        doUndo()
    }

    private fun doUndo() {
        val st = s
        val k = st.undoCount()
        if (k == 0) return
        cancelJobs()
        val keep = st.moves.size - k
        val taken = st.moves.drop(keep)
        // the reverse animation: newest first, each move untwists (200 ms) then its marble flies home (200 ms)
        val steps = ArrayList<AnimStep>()
        if (!reduced) for (i in st.moves.size - 1 downTo keep) {
            val m = st.moves[i]
            val after = st.positions[i + 1]
            if (m.quad >= 0) steps += AnimStep(Turn(m.quad, -m.dir, lift = false), after, UNDO_STEP_MS)
            steps += AnimStep(Fly(m.cell, m.side, toDish = true), st.positions[i], UNDO_STEP_MS)
        }
        set(st.copy(
            moves = st.moves.take(keep), positions = st.positions.take(keep + 1), redo = taken + st.redo, pending = null, picked = null, held = null,
            alert = null, hint = null, phase = if (steps.isEmpty()) Phase.PLACE else Phase.PLACING,
            anim = if (steps.isEmpty()) null else Anim(steps, clock()),
        ))
        log("UNDO $k -> moves=$keep redo=${s.redo.size}")
        persist()
        if (steps.isEmpty()) { nextTurn(); return }
        turnJob = scope.launch {
            playAnim()
            set(s.copy(anim = null, phase = Phase.PLACE))
            nextTurn()
        }
    }

    /** [重做]: replays the next round from the redo list (forward animation, 200 ms a leg). */
    fun redo() {
        val st = s
        if (!st.canRedo) return
        cancelJobs()
        var moves = st.moves; var positions = st.positions; var redo = st.redo
        val cfg = st.config!!
        val steps = ArrayList<AnimStep>()
        while (redo.isNotEmpty()) {
            val r = redo.first()
            val side = (st.startSide + moves.size) % 2
            val m = Move(side, r.cell, r.quad, r.dir)
            if (!Rules.legal(positions.last(), m)) { redo = emptyList(); break }
            val before = positions.last()
            steps += AnimStep(Fly(m.cell, side, toDish = false), before, UNDO_STEP_MS)
            if (m.quad >= 0) steps += AnimStep(Turn(m.quad, m.dir, lift = false), before.place(m.cell, side), UNDO_STEP_MS)
            moves = moves + m; positions = positions + Rules.apply(before, m); redo = redo.drop(1)
            if (Rules.outcome(before, m) != null) break
            if (cfg.isHuman((st.startSide + moves.size) % 2)) break
        }
        set(st.copy(moves = moves, positions = positions, redo = redo, pending = null, picked = null, alert = null, hint = null,
            phase = Phase.PLACING, anim = if (reduced || steps.isEmpty()) null else Anim(steps, clock())))
        log("REDO -> moves=${moves.size}")
        persist()
        turnJob = scope.launch {
            playAnim()
            set(s.copy(anim = null, phase = Phase.PLACE))
            if (finishIfOver()) return@launch
            nextTurn()
        }
    }

    // ---- pause, confirm dialogs ----

    fun pause(on: Boolean) {
        if (!s.inGame && on) return
        set(s.copy(paused = on, held = null))
        log(if (on) "PAUSE S10" else "RESUME")
    }

    fun requestConfirm(kind: ConfirmKind) {
        if (kind == ConfirmKind.RESIGN && !s.inGame) return
        set(s.copy(confirm = kind, held = null)); log("CONFIRM $kind S13")
    }

    fun cancelConfirm() { set(s.copy(confirm = null)); log("CONFIRM cancel") }

    fun confirm() {
        val kind = s.confirm ?: return
        set(s.copy(confirm = null, paused = false))
        log("CONFIRM yes $kind")
        when (kind) {
            ConfirmKind.RESIGN -> {
                val cfg = s.config ?: return
                val loser = if (cfg.mode == Mode.AI) cfg.humanSide else s.side
                finish(Pentago.other(loser), Outcome.RESIGN)
            }
            ConfirmKind.RESTART -> { cancelJobs(); store.clear(); toMenu() }
            ConfirmKind.UNDO_2P -> doUndo()
        }
    }

    private fun finish(winner: Int?, outcome: Outcome) {
        cancelJobs()
        val st = s
        val board = st.board
        val runs = if (outcome == Outcome.RESIGN) emptyList() else (0..1).flatMap { side -> Pentago.winRuns(board.bits(side)).map { side to it } }
        set(st.copy(phase = Phase.RESULT, winner = winner, outcome = outcome, winRuns = runs, elapsedMs = st.elapsedMs + (clock() - st.resumedAt),
            anim = null, picked = null, held = null, hint = null, confirm = null, alert = null, paused = false, coachStep = -1))
        val cfg = st.config
        // the opponent's win is not a fanfare (D44); a draw / resignation is one low note
        val aiWon = cfg != null && cfg.mode == Mode.AI && winner != null && winner != cfg.humanSide
        val result = when { outcome == Outcome.RESIGN -> Sound.DRAW; winner == null -> Sound.DRAW; aiWon -> Sound.LOSE; else -> Sound.WIN }
        // after a twist the result waits for the block's landing knock to fade (no pile-up over -1 dBFS, D51 / render_mix.py)
        if (outcome == Outcome.WIN_TWIST || outcome == Outcome.DRAW_DOUBLE || outcome == Outcome.DRAW_FULL) scope.launch { delay(RESULT_SOUND_DELAY_MS); onSound(result) } else onSound(result)
        log("RESULT winner=$winner outcome=$outcome moves=${st.moves.size} runs=${runs.map { (sd, r) -> "$sd:" + r.joinToString(",") { Pentago.name(it) } }} S09")
        store.clear()
    }

    // ---- debug ----

    fun freeze(ms: Long?) { set(s.copy(freezeMs = ms, freezeAt = clock())); log("FREEZE ${ms ?: "off"}") }

    private suspend fun awaitUnfrozen() { while (coroutineContext.isActive && s.freezeMs != null) delay(50) }

    fun debugState(mutator: (PlayState) -> PlayState) = set(mutator(s))

    fun reseed(seed: Int) { rng = Random(seed); aiSeed = seed; log("SEED $seed") }

    fun dump(): String {
        val st = s
        return "phase=${st.phase.id} cfg=${st.config} side=${st.side} moves=${st.moves.size} [${st.moves.joinToString(" ") { it.text() }}] pending=${st.pending?.let { Pentago.name(it) }} " +
            "picked=${st.picked} held=${st.held} hint=${st.hint?.text()} alert=${st.alert?.reason} confirm=${st.confirm} paused=${st.paused} redo=${st.redo.size} " +
            "welcome=${st.welcome} coach=${st.coachStep} winner=${st.winner} outcome=${st.outcome} freeze=${st.freezeMs} board=${st.board}"
    }

    private fun persist() {
        val st = s
        val cfg = st.config ?: return
        if (st.phase == Phase.RESULT || st.phase == Phase.SETUP) return
        if (st.startPos != Pos() || st.startSide != Pentago.BLACK) return // debug setups are not saved
        store.save(SavedGame(cfg, st.moves, st.pending, st.redo, st.elapsedMs + (clock() - st.resumedAt), st.hintOn, st.hintsUsed))
    }

    private fun cancelJobs() {
        turnJob?.cancel(); alertJob?.cancel(); hintJob?.cancel(); skipJob?.cancel()
    }
}
