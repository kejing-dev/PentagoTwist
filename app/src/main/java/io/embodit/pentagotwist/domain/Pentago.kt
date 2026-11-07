package io.embodit.pentagotwist.domain

/**
 * Pentago rules (design gameSpec, official rules + Wikipedia): a 6 x 6 board made of four 3 x 3 blocks. A turn = place one
 * marble in an empty dimple, then twist any one block a quarter turn either way. Five in a row wins; a five right after the
 * placement wins on the spot (no twist); both sides five after a twist = draw; 36 marbles down with no five = draw. When no
 * twist can change the board (every block empty or only its centre taken) the twist is skipped (design C2).
 *
 * Cells: index = row * 6 + col, col 0..5 = a..f (left -> right), row 0..5 = 1..6 (near -> far, seen from the user).
 * Blocks: 0 = Q1 far-left, 1 = Q2 far-right, 2 = Q3 near-left, 3 = Q4 near-right.
 * Twist direction: +1 = clockwise (↻) seen from above by the user at the near edge, -1 = counter-clockwise (↺).
 * Pure Kotlin, no Android: bitboards (black / white as 36-bit masks) so the engine can search fast.
 */
object Pentago {
    const val N = 36
    const val BLACK = 0
    const val WHITE = 1
    const val PER_SIDE = 18
    const val CW = 1
    const val CCW = -1

    fun other(side: Int) = 1 - side
    fun col(c: Int) = c % 6
    fun row(c: Int) = c / 6
    fun at(col: Int, row: Int) = row * 6 + col
    fun name(c: Int): String = "${'a' + col(c)}${row(c) + 1}"
    fun parse(s: String): Int? {
        val t = s.trim().lowercase()
        if (t.length != 2) return null
        val c = t[0] - 'a'; val r = t[1] - '1'
        return if (c in 0..5 && r in 0..5) at(c, r) else null
    }

    fun quadOf(c: Int): Int = (if (row(c) >= 3) 0 else 2) + (if (col(c) >= 3) 1 else 0)
    /** Centre column / row of block q. */
    fun quadCol(q: Int) = if (q % 2 == 0) 1 else 4
    fun quadRow(q: Int) = if (q < 2) 4 else 1
    fun quadName(q: Int) = "Q${q + 1}"

    /** The nine cells of block q (row-major from the near-left corner). */
    val QUAD_CELLS: Array<IntArray> = Array(4) { q ->
        IntArray(9) { k -> at(quadCol(q) - 1 + k % 3, quadRow(q) - 1 + k / 3) }
    }
    val QUAD_MASK: LongArray = LongArray(4) { q -> QUAD_CELLS[q].fold(0L) { m, c -> m or (1L shl c) } }

    /** Where cell c goes when its block turns [dir] (CW: (u, v) -> (v, -u) with u = right, v = far). */
    fun rotateCell(c: Int, dir: Int): Int {
        val q = quadOf(c)
        val u = col(c) - quadCol(q); val v = row(c) - quadRow(q)
        return if (dir == CW) at(quadCol(q) + v, quadRow(q) - u) else at(quadCol(q) - v, quadRow(q) + u)
    }

    /** ROT[q][dir == CW ? 0 : 1][k] = destination of QUAD_CELLS[q][k]. */
    private val ROT: Array<Array<IntArray>> = Array(4) { q -> Array(2) { d -> IntArray(9) { k -> rotateCell(QUAD_CELLS[q][k], if (d == 0) CW else CCW) } } }

    fun rotateBits(bb: Long, q: Int, dir: Int): Long {
        val src = QUAD_CELLS[q]
        val dst = ROT[q][if (dir == CW) 0 else 1]
        var out = bb and QUAD_MASK[q].inv()
        for (k in 0 until 9) if (bb and (1L shl src[k]) != 0L) out = out or (1L shl dst[k])
        return out
    }

    /** The 32 windows of five (6 rows x 2, 6 columns x 2, the two long diagonals x 2 each per direction, four short ones). */
    val WINDOW_CELLS: List<IntArray> = buildList {
        for (r in 0..5) for (s in 0..1) add(IntArray(5) { at(s + it, r) })
        for (c in 0..5) for (s in 0..1) add(IntArray(5) { at(c, s + it) })
        // diagonals up-right (col + 1, row + 1)
        for ((c0, r0) in listOf(0 to 0, 1 to 1, 1 to 0, 0 to 1)) add(IntArray(5) { at(c0 + it, r0 + it) })
        // diagonals up-left (col - 1, row + 1)
        for ((c0, r0) in listOf(5 to 0, 4 to 1, 4 to 0, 5 to 1)) add(IntArray(5) { at(c0 - it, r0 + it) })
    }
    val WINDOWS: LongArray = LongArray(WINDOW_CELLS.size) { i -> WINDOW_CELLS[i].fold(0L) { m, c -> m or (1L shl c) } }

    /** Centre cells of the whole board (c3 d3 c4 d4) and of each block (the cells no twist moves). */
    val CENTRE_MASK: Long = listOf(at(2, 2), at(3, 2), at(2, 3), at(3, 3)).fold(0L) { m, c -> m or (1L shl c) }
    val QUAD_CENTRE_MASK: Long = (0..3).fold(0L) { m, q -> m or (1L shl at(quadCol(q), quadRow(q))) }

    fun hasFive(bb: Long): Boolean = WINDOWS.any { (bb and it) == it }

    /** Every window of five the side holds (for the win line). */
    fun fives(bb: Long): List<IntArray> = WINDOWS.indices.filter { (bb and WINDOWS[it]) == WINDOWS[it] }.map { WINDOW_CELLS[it] }

    /**
     * The winning line(s) as whole runs: overlapping windows of the same line merge into one run of 5 or 6 (design: a six
     * lights all six), ordered from one end.
     */
    fun winRuns(bb: Long): List<List<Int>> {
        val runs = ArrayList<MutableList<Int>>()
        for (w in fives(bb)) {
            val dir = w[1] - w[0]
            val hit = runs.firstOrNull { r -> r[1] - r[0] == dir && r.intersect(w.toSet()).size >= 4 }
            if (hit != null) { for (c in w) if (c !in hit) hit += c; hit.sort() } else runs += w.toMutableList()
        }
        return runs
    }
}

/** A full turn: [cell] placed by [side], then block [quad] twisted [dir]; quad = -1 when the twist was skipped or not needed (a five on placing). */
data class Move(val side: Int, val cell: Int, val quad: Int, val dir: Int) {
    val twisted: Boolean get() = quad >= 0
    fun text(): String = Pentago.name(cell) + if (quad >= 0) Pentago.quadName(quad) + (if (dir == Pentago.CW) "+" else "-") else ""
}

/** How the game ended (design gameSpec.end). */
enum class Outcome { WIN_PLACE, WIN_TWIST, DRAW_DOUBLE, DRAW_FULL, RESIGN }

/** A position: black and white bitboards. Immutable. */
data class Pos(val black: Long = 0L, val white: Long = 0L) {
    fun bits(side: Int): Long = if (side == Pentago.BLACK) black else white
    fun owner(c: Int): Int = when {
        black and (1L shl c) != 0L -> Pentago.BLACK
        white and (1L shl c) != 0L -> Pentago.WHITE
        else -> -1
    }
    fun empty(c: Int): Boolean = (black or white) and (1L shl c) == 0L
    val occupied: Long get() = black or white
    val count: Int get() = java.lang.Long.bitCount(occupied)
    fun count(side: Int): Int = java.lang.Long.bitCount(bits(side))
    val full: Boolean get() = count == Pentago.N

    fun place(c: Int, side: Int): Pos {
        require(empty(c)) { "cell ${Pentago.name(c)} is taken" }
        return if (side == Pentago.BLACK) copy(black = black or (1L shl c)) else copy(white = white or (1L shl c))
    }

    fun remove(c: Int): Pos = Pos(black and (1L shl c).inv(), white and (1L shl c).inv())

    fun twist(q: Int, dir: Int): Pos = Pos(Pentago.rotateBits(black, q, dir), Pentago.rotateBits(white, q, dir))

    fun five(side: Int): Boolean = Pentago.hasFive(bits(side))

    /** No twist of any block changes the board (each block empty or holding only its centre): the twist is skipped (C2). */
    val twistIsMoot: Boolean get() = (0..3).all { q -> twist(q, Pentago.CW) == this }

    override fun toString(): String = (5 downTo 0).joinToString("/") { r -> (0..5).joinToString("") { c -> when (owner(Pentago.at(c, r))) { 0 -> "x"; 1 -> "o"; else -> "." } } }

    companion object {
        /** Debug / test notation: rows far -> near ("x" black, "o" white, "." empty), separated by "/". */
        fun parse(s: String): Pos {
            val rows = s.split("/")
            require(rows.size == 6)
            var b = 0L; var w = 0L
            rows.forEachIndexed { i, line ->
                val r = 5 - i
                line.forEachIndexed { c, ch ->
                    when (ch) { 'x', 'X', 'b' -> b = b or (1L shl Pentago.at(c, r)); 'o', 'O', 'w' -> w = w or (1L shl Pentago.at(c, r)) }
                }
            }
            return Pos(b, w)
        }
    }
}

object Rules {
    /** After [side] placed: a five already = an immediate win (no twist). */
    fun winsOnPlace(after: Pos, side: Int): Boolean = after.five(side)

    /** The result once the twist is done (or skipped): null = play on. */
    fun afterTwist(pos: Pos, mover: Int): Pair<Outcome, Int?>? {
        val me = pos.five(mover); val them = pos.five(Pentago.other(mover))
        return when {
            me && them -> Outcome.DRAW_DOUBLE to null
            me -> Outcome.WIN_TWIST to mover
            them -> Outcome.WIN_TWIST to Pentago.other(mover)
            pos.full -> Outcome.DRAW_FULL to null
            else -> null
        }
    }

    /** Applies a whole move; throws on an illegal one. */
    fun apply(pos: Pos, m: Move): Pos {
        val placed = pos.place(m.cell, m.side)
        return if (m.quad >= 0) placed.twist(m.quad, m.dir) else placed
    }

    /** Is [m] a legal whole move here? (placing on an empty cell; skipping the twist only on a five or a moot twist.) */
    fun legal(pos: Pos, m: Move): Boolean {
        if (m.cell !in 0 until Pentago.N || !pos.empty(m.cell)) return false
        val placed = pos.place(m.cell, m.side)
        return if (m.quad < 0) placed.five(m.side) || placed.twistIsMoot else m.quad in 0..3 && (m.dir == Pentago.CW || m.dir == Pentago.CCW) && !placed.five(m.side)
    }

    /** The result of a finished move (placing five, or the twist result). */
    fun outcome(pos: Pos, m: Move): Pair<Outcome, Int?>? {
        val placed = pos.place(m.cell, m.side)
        if (placed.five(m.side)) return Outcome.WIN_PLACE to m.side
        return afterTwist(if (m.quad >= 0) placed.twist(m.quad, m.dir) else placed, m.side)
    }
}
