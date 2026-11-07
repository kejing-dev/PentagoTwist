package io.embodit.pentagotwist.domain

import kotlin.random.Random

/**
 * The AI (design gameSpec.ai): negamax alpha-beta with iterative deepening on whole moves (place + twist). A placement
 * candidate is an empty cell next to a marble (the centre four on an empty board), the best 12 by a quick score, times the
 * eight twists (twists that give the same board are merged). One ply = one whole move.
 *
 *  入门 EASY   depth 1: takes a win, blocks an immediate win (a four), otherwise any safe cell (5 in 10), a neighbouring one (3 in
 *              10) or one of the top 5 by its own shapes only (no defence of threes), with a random safe twist -> a newcomer can win.
 *  中级 MEDIUM depth 2, 300 ms.     高手 HARD up to depth 4 (design: 3; an even depth ends on the reply), 1.5 s cap, iterative
 *              deepening keeps the best move of the last finished depth.
 * Every level first takes a winning move and never leaves an immediate win to the other side when it can avoid it.
 * [cancelled] is polled during the search (undo / new game / background stop it at once).
 */
object Engine {
    const val EASY = 1
    const val MEDIUM = 2
    const val HARD = 3

    private const val WIN = 10_000_000
    private val W = intArrayOf(0, 1, 10, 100, 1000, 100_000)

    fun depthOf(level: Int) = when (level) { EASY -> 1; MEDIUM -> 2; else -> 4 }
    fun timeOf(level: Int): Long = when (level) { EASY -> 100L; MEDIUM -> 300L; else -> 1500L }

    /**
     * Static score of [pos] for [side], who has just moved (the other side moves next): shapes in every window of five, own
     * minus 1.1 x theirs (a little defensive); a four of theirs is about to be completed, so it weighs 30 x more.
     */
    fun evaluate(pos: Pos, side: Int, defend: Boolean = true): Int {
        val own = pos.bits(side); val opp = pos.bits(Pentago.other(side))
        var s = 0; var o = 0
        for (w in Pentago.WINDOWS) {
            val a = java.lang.Long.bitCount(own and w); val b = java.lang.Long.bitCount(opp and w)
            if (a > 0 && b > 0) continue
            if (a > 0) s += W[a] else if (b > 0) o += if (b == 4) 30_000 else W[b]
        }
        s += 5 * (java.lang.Long.bitCount(own and Pentago.CENTRE_MASK) + java.lang.Long.bitCount(own and Pentago.QUAD_CENTRE_MASK))
        o += 5 * (java.lang.Long.bitCount(opp and Pentago.CENTRE_MASK) + java.lang.Long.bitCount(opp and Pentago.QUAD_CENTRE_MASK))
        return if (defend) s - (o * 11) / 10 else s
    }

    /** Empty cells next to a marble (8-neighbourhood); the centre four on an empty board. */
    fun candidates(pos: Pos): List<Int> {
        if (pos.count == 0) return listOf(Pentago.at(2, 2), Pentago.at(3, 2), Pentago.at(2, 3), Pentago.at(3, 3))
        val out = ArrayList<Int>()
        for (c in 0 until Pentago.N) {
            if (!pos.empty(c)) continue
            val cc = Pentago.col(c); val rr = Pentago.row(c)
            var near = false
            for (dr in -1..1) for (dc in -1..1) {
                val nc = cc + dc; val nr = rr + dr
                if ((dr != 0 || dc != 0) && nc in 0..5 && nr in 0..5 && !pos.empty(Pentago.at(nc, nr))) near = true
            }
            if (near) out += c
        }
        return out.ifEmpty { (0 until Pentago.N).filter { pos.empty(it) } }
    }

    /** Every distinct whole move from [cells] (a five on placing or a moot twist = the one move without a twist). */
    fun movesFrom(pos: Pos, side: Int, cells: List<Int>): List<Pair<Move, Pos>> {
        val out = ArrayList<Pair<Move, Pos>>(cells.size * 8)
        for (c in cells) {
            val placed = pos.place(c, side)
            if (placed.five(side) || placed.twistIsMoot) { out += Move(side, c, -1, 0) to placed; continue }
            val seen = HashSet<Pos>(8)
            for (q in 0..3) for (d in intArrayOf(Pentago.CW, Pentago.CCW)) {
                val p = placed.twist(q, d)
                if (seen.add(p)) out += Move(side, c, q, d) to p
            }
        }
        return out
    }

    fun allMoves(pos: Pos, side: Int): List<Pair<Move, Pos>> = movesFrom(pos, side, (0 until Pentago.N).filter { pos.empty(it) })

    /** Score of a finished move for its mover: WIN / -WIN / 0 for a decided game, null to play on. */
    private fun terminal(after: Pos, m: Move): Int? {
        if (m.quad < 0 && after.five(m.side)) return WIN
        return when (Rules.afterTwist(after, m.side)?.second) {
            null -> if (after.full || (after.five(0) && after.five(1))) 0 else null
            m.side -> WIN
            else -> -WIN
        }
    }

    /** Moves that win at once (placing five, or a twist that makes our five and not theirs). */
    fun winningMoves(pos: Pos, side: Int): List<Move> = allMoves(pos, side).filter { (m, p) -> terminal(p, m) == WIN }.map { it.first }

    /** Can [side] win with its next move here? */
    fun canWinNow(pos: Pos, side: Int): Boolean {
        for (c in 0 until Pentago.N) {
            if (!pos.empty(c)) continue
            val placed = pos.place(c, side)
            if (placed.five(side)) return true
            if (placed.twistIsMoot) continue
            for (q in 0..3) for (d in intArrayOf(Pentago.CW, Pentago.CCW)) {
                val p = placed.twist(q, d)
                if (p.five(side) && !p.five(Pentago.other(side))) return true
            }
        }
        return false
    }

    /** Our moves after which the other side has no immediate win (empty when every move loses at once). */
    fun safeMoves(pos: Pos, side: Int, moves: List<Pair<Move, Pos>> = allMoves(pos, side)): List<Pair<Move, Pos>> =
        moves.filter { (m, p) -> terminal(p, m)?.let { it != -WIN } ?: !canWinNow(p, Pentago.other(side)) }

    fun choose(pos: Pos, side: Int, level: Int, rnd: Random, timeCapMs: Long? = null, cancelled: () -> Boolean = { false }): Move? {
        val all = allMoves(pos, side)
        if (all.isEmpty()) return null
        // 1. a win now (all levels, D: "一步成五必走，含转盘成五")
        all.filter { (m, p) -> terminal(p, m) == WIN }.let { wins -> if (wins.isNotEmpty()) return wins[rnd.nextInt(wins.size)].first }
        // 2. never hand over an immediate win when something else exists (blocks fours on every level)
        val safe = safeMoves(pos, side, all).ifEmpty { all.filter { (m, p) -> terminal(p, m) != -WIN }.ifEmpty { all } }
        if (level <= EASY) return easy(pos, side, safe, rnd)
        val deadline = System.nanoTime() + (timeCapMs ?: timeOf(level)) * 1_000_000L
        val maxDepth = depthOf(level)
        // root order: quick score after the move
        var root = safe.sortedByDescending { (_, p) -> evaluate(p, side) }.take(if (level >= HARD) 48 else 64)
        var best = root.first().first
        for (depth in 1..maxDepth) {
            var alpha = -WIN - 1
            var bestHere: Move? = null
            val scored = ArrayList<Pair<Pair<Move, Pos>, Int>>()
            var aborted = false
            for (mp in root) {
                if (cancelled() || System.nanoTime() > deadline && depth > 1) { aborted = true; break }
                val (m, p) = mp
                val v = terminal(p, m) ?: if (depth == 1) evaluate(p, side) else -negamax(p, Pentago.other(side), depth - 1, -WIN - 1, -alpha, deadline, cancelled)
                scored += mp to v
                if (v > alpha || bestHere == null) { alpha = maxOf(alpha, v); bestHere = m }
            }
            if (aborted && depth > 1) break
            bestHere?.let { best = it }
            // the next depth searches the best first (alpha-beta cuts more)
            root = scored.sortedByDescending { it.second }.map { it.first } + root.drop(scored.size)
            if (alpha >= WIN / 2) break
        }
        // a small random choice between equal best moves keeps games from repeating
        return best
    }

    /**
     * The twist only, for a marble already placed at [cell] (S07 hint, an AI turn resumed after a kill between its two halves):
     * the twist whose worst reply (one ply) is best, never one that hands over an immediate win when another exists.
     */
    fun chooseTwist(placed: Pos, side: Int, cell: Int): Move {
        var best: Move? = null
        var bestV = Int.MIN_VALUE
        val seen = HashSet<Pos>()
        for (q in 0..3) for (d in intArrayOf(Pentago.CW, Pentago.CCW)) {
            val p = placed.twist(q, d)
            if (!seen.add(p)) continue
            val m = Move(side, cell, q, d)
            val v = terminal(p, m) ?: run {
                if (canWinNow(p, Pentago.other(side))) -WIN / 2 else evaluate(p, side)
            }
            if (v > bestV) { bestV = v; best = m }
        }
        return best ?: Move(side, cell, -1, 0)
    }

    private fun easy(pos: Pos, side: Int, safe: List<Pair<Move, Pos>>, rnd: Random): Move {
        // the placement: a top-5 cell by our own shapes (no defence); the twist: any safe one for that cell
        val byCell = safe.groupBy { it.first.cell }
        val score = byCell.keys.associateWith { c -> evaluate(pos.place(c, side), side, defend = false) + rnd.nextInt(6) }
        val ranked = byCell.keys.sortedByDescending { score.getValue(it) }
        // a newcomer must be able to win (B27): mostly a loose cell, now and then a shaped one
        val near = candidates(pos).toSet()
        val loose = byCell.keys.filter { it in near }.ifEmpty { byCell.keys.toList() }
        val any = byCell.keys.toList()
        val roll = rnd.nextInt(10)
        val cell = when {
            roll < 5 -> any[rnd.nextInt(any.size)]
            roll < 8 -> loose[rnd.nextInt(loose.size)]
            else -> ranked.take(5).let { it[rnd.nextInt(it.size)] }
        }
        val options = byCell.getValue(cell)
        return options[rnd.nextInt(options.size)].first
    }

    private fun negamax(pos: Pos, side: Int, depth: Int, alphaIn: Int, beta: Int, deadline: Long, cancelled: () -> Boolean): Int {
        var alpha = alphaIn
        val cells = candidates(pos).sortedByDescending { c -> evaluate(pos.place(c, side), side) }.take(if (depth >= 3) 8 else 12)
        val moves = movesFrom(pos, side, cells)
        if (moves.isEmpty()) return 0
        val ordered = if (depth > 1) moves.sortedByDescending { (_, p) -> evaluate(p, side) } else moves
        var best = -WIN - 1
        for ((m, p) in ordered) {
            val t = terminal(p, m)
            val v = when {
                t != null -> t
                depth == 1 -> evaluate(p, side)
                else -> -negamax(p, Pentago.other(side), depth - 1, -beta, -alpha, deadline, cancelled)
            }
            if (v > best) best = v
            if (v > alpha) alpha = v
            if (alpha >= beta) break
            if (depth > 1 && (cancelled() || System.nanoTime() > deadline)) break
        }
        return best
    }
}
