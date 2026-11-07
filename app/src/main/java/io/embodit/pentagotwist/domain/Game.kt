package io.embodit.pentagotwist.domain

enum class Mode { AI, TWO_PLAYER }

/** New-game seat choice: play black (moves first), white, or random. */
enum class SeatPick { BLACK, WHITE, RANDOM }

enum class Language { SYSTEM, ZH, EN }

/** One game's setup. In AI mode [humanSide] is the user's colour; black always moves first (official rules). */
data class GameConfig(
    val mode: Mode = Mode.AI,
    val aiLevel: Int = Engine.MEDIUM,
    val humanSide: Int = Pentago.BLACK,
) {
    fun isHuman(side: Int): Boolean = mode == Mode.TWO_PLAYER || side == humanSide
    val aiSide: Int get() = Pentago.other(humanSide)
}

/** The new-game page's choices (remembered; a random seat only goes into the game, D30). */
data class GameSetup(
    val mode: Mode = Mode.AI,
    val aiLevel: Int = Engine.MEDIUM,
    val seat: SeatPick = SeatPick.BLACK,
)

/**
 * Board tilt choices (build.md 2026-11-06 五子棋 rule: 0 / 30 / 45 / 60 / 90; the design's 0 / 20 / 28 is replaced, default 30,
 * deviation dv-tilt). Size 100 / 120 % (pitch 6.0 / 7.2 cm).
 */
val TILTS = intArrayOf(0, 30, 45, 60, 90)
const val DEFAULT_TILT = 30
val SIZES = intArrayOf(100, 120)

/** App settings (SharedPreferences). */
data class Settings(
    val language: Language = Language.SYSTEM,
    val sound: Boolean = true,
    val volume: Int = 2,
    val reducedMotion: Boolean = false,
    val coach: Boolean = true,
    val tilt: Int = DEFAULT_TILT,
    val size: Int = 100,
    val firstRunDone: Boolean = false,
    val lastSetup: GameSetup = GameSetup(),
)

/** What a game looks like on disk (files/game.txt): the config, the move list and the marble placed but not twisted yet. */
data class SavedGame(
    val config: GameConfig,
    val moves: List<Move>,
    val pending: Int? = null,
    val redo: List<Move> = emptyList(),
    val elapsedMs: Long = 0,
    val hintOn: Boolean = false,
    val hintsUsed: Int = 0,
) {
    /** Worth offering again: something was placed, or the AI moves first (killed while it was thinking, S06 -> S16 -> S06). */
    val resumable: Boolean get() = moves.isNotEmpty() || pending != null || (config.mode == Mode.AI && config.humanSide != Pentago.BLACK)
}

interface GameStore {
    fun load(): SavedGame?
    fun save(game: SavedGame)
    fun clear()
}

/**
 * Line codec `pt1|mode|level|humanSide|elapsed|hint|hintsUsed|pending|<moves>|<redo>`; a move is `cell.quad.dir` (side follows
 * from the order, black first). Replay stops at the first illegal move, so a bad file can't smuggle one in.
 */
object SaveCodec {
    fun move(m: Move): String = "${m.cell}.${m.quad}.${m.dir}"

    private fun parseMoves(s: String): List<Triple<Int, Int, Int>> =
        if (s.isBlank()) emptyList() else s.split(";").map { t -> val f = t.split("."); Triple(f[0].toInt(), f[1].toInt(), f[2].toInt()) }

    fun encode(g: SavedGame): String = listOf(
        "pt1", g.config.mode.name, g.config.aiLevel.toString(), g.config.humanSide.toString(), g.elapsedMs.toString(),
        if (g.hintOn) "1" else "0", g.hintsUsed.toString(), (g.pending ?: -1).toString(),
        g.moves.joinToString(";") { move(it) }, g.redo.joinToString(";") { move(it) },
    ).joinToString("|")

    /** Replays raw moves from [from] with [firstSide] to move; null if any is illegal. */
    fun replay(raw: List<Triple<Int, Int, Int>>, from: Pos = Pos(), firstSide: Int = Pentago.BLACK): Pair<List<Move>, Pos>? {
        var pos = from
        var side = firstSide
        val out = ArrayList<Move>()
        for ((cell, q, d) in raw) {
            val m = Move(side, cell, q, d)
            if (!Rules.legal(pos, m)) return null
            out += m
            pos = Rules.apply(pos, m)
            side = Pentago.other(side)
        }
        return out to pos
    }

    fun decode(text: String): SavedGame? = runCatching {
        val f = text.trim().split("|")
        if (f.size < 10 || f[0] != "pt1") return null
        val config = GameConfig(Mode.valueOf(f[1]), f[2].toInt().coerceIn(1, 3), f[3].toInt().coerceIn(0, 1))
        val (moves, pos) = replay(parseMoves(f[8])) ?: return null
        val pending = f[7].toInt().takeIf { it in 0 until Pentago.N && pos.empty(it) }
        val redo = if (pending == null) replay(parseMoves(f[9]), pos, if (moves.size % 2 == 0) Pentago.BLACK else Pentago.WHITE)?.first ?: emptyList() else emptyList()
        SavedGame(config, moves, pending, redo, f[4].toLong(), f[5] == "1", f[6].toInt())
    }.getOrNull()
}
