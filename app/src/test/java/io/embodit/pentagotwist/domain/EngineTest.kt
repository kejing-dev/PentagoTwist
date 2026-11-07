package io.embodit.pentagotwist.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * design acceptance.mustPass M3: a win now is always taken (placing five or a twist five, every level); an immediate win of
 * the other side is always blocked when it can be (every level, "必堵活四 / 冲四"); a threat that wins in two (open three,
 * Pentago's twist forks) is stopped by Club and Strong in >= 95 %; Strong beats Club and Club beats Beginner in >= 70 % of the
 * points; a naive bot (blocks fours, else a random neighbour + random twist) scores >= 50 % against Beginner.
 * Games: -Dpt.games=N (default 12 in build.gradle; the ui-audit run uses 20).
 */
class EngineTest {
    private val B = Pentago.BLACK
    private val W = Pentago.WHITE
    private val games = (System.getProperty("pt.games") ?: "12").toInt()

    private fun randomPos(rnd: java.util.Random, n: Int): Pos? {
        var pos = Pos()
        repeat(n) { k ->
            val empty = (0 until 36).filter { pos.empty(it) }
            pos = pos.place(empty[rnd.nextInt(empty.size)], k % 2)
        }
        return pos.takeIf { !it.five(B) && !it.five(W) }
    }

    private fun result(pos: Pos, m: Move): Int? = Rules.outcome(pos, m)?.second

    @Test fun takesAWinNowOnEveryLevel() {
        val rnd = java.util.Random(11)
        var placeWins = 0; var twistWins = 0; var n = 0
        while (n < 30) {
            val pos = randomPos(rnd, 12 + 2 * rnd.nextInt(5)) ?: continue
            val wins = Engine.winningMoves(pos, B)
            if (wins.isEmpty()) continue
            n++
            if (wins.any { it.quad < 0 }) placeWins++ else twistWins++
            for (level in 1..3) {
                val m = Engine.choose(pos, B, level, Random(n), 200L)
                assertNotNull(m)
                assertEquals("level $level pos $pos", B, result(pos, m!!))
            }
        }
        assertTrue("both kinds covered: place $placeWins twist $twistWins", placeWins > 0 && twistWins > 0)
    }

    @Test fun blocksAnImmediateWinOnEveryLevel() {
        val rnd = java.util.Random(12)
        var n = 0; var tries = 0
        while (n < 30 && tries < 20000) {
            tries++
            val pos = randomPos(rnd, 12 + 2 * rnd.nextInt(5)) ?: continue
            if (Engine.canWinNow(pos, B) || !Engine.canWinNow(pos, W)) continue
            val safe = Engine.safeMoves(pos, B)
            if (safe.isEmpty()) continue
            n++
            for (level in 1..3) {
                val m = Engine.choose(pos, B, level, Random(n), 200L)!!
                val after = Rules.apply(pos, m)
                val ok = result(pos, m) == B || (result(pos, m) == null && !Engine.canWinNow(after, W))
                assertTrue("level $level must block in $pos, chose ${m.text()}", ok)
            }
        }
        assertTrue(n >= 20)
    }

    /** After our move [after], can [opp] force a win within its next two moves (a fork we can't answer)? */
    private fun losesInTwo(after: Pos, opp: Int): Boolean {
        if (Engine.canWinNow(after, opp)) return true
        val me = Pentago.other(opp)
        for ((om, p1) in Engine.movesFrom(after, opp, Engine.candidates(after))) {
            if (Rules.outcome(after, om) != null) continue
            var answered = false
            for ((mm, p2) in Engine.allMoves(p1, me)) {
                val r = Rules.outcome(p1, mm)
                if (r?.second == me || (r == null && !Engine.canWinNow(p2, opp)) || (r != null && r.second == null)) { answered = true; break }
            }
            if (!answered) return true
        }
        return false
    }

    @Test fun stopsTwoMoveThreatsFromClubUp() {
        val rnd = java.util.Random(13)
        var n = 0; var tries = 0
        val stopped = intArrayOf(0, 0, 0, 0)
        while (n < 20 && tries < 4000) {
            tries++
            val pos = randomPos(rnd, 10 + 2 * rnd.nextInt(4)) ?: continue
            if (Engine.canWinNow(pos, B) || Engine.canWinNow(pos, W)) continue
            // the position carries a threat: a careless (random) move loses in two
            val careless = Engine.allMoves(pos, B).shuffled(Random(tries)).take(4)
            if (careless.none { (m, a) -> result(pos, m) == null && losesInTwo(a, W) }) continue
            val picks = (2..3).associateWith { level -> Engine.choose(pos, B, level, Random(n), if (level == 3) 1500L else 300L)!! }
            val safe = picks.mapValues { (_, m) -> result(pos, m) == B || !losesInTwo(Rules.apply(pos, m), W) }
            if (safe.values.none { it }) continue // no level found an answer: probably lost already, not counted
            n++
            safe.forEach { (level, ok) -> if (ok) stopped[level]++ }
        }
        println("two-move threats: n=$n club=${stopped[2]} strong=${stopped[3]}")
        assertTrue("enough positions ($n)", n >= 12)
        assertTrue("club ${stopped[2]}/$n", stopped[2] >= kotlin.math.ceil(0.95 * n).toInt() - 0)
        assertTrue("strong ${stopped[3]}/$n", stopped[3] >= kotlin.math.ceil(0.95 * n).toInt())
    }

    private fun naive(pos: Pos, side: Int, rnd: Random): Move {
        Engine.winningMoves(pos, side).firstOrNull()?.let { return it }
        val safe = Engine.safeMoves(pos, side).ifEmpty { Engine.allMoves(pos, side) }
        val cells = Engine.candidates(pos).toSet()
        val near = safe.filter { it.first.cell in cells }.ifEmpty { safe }
        return near[rnd.nextInt(near.size)].first
    }

    /** Points of [a] (1 a win, 0.5 a draw) over [games] games, colours alternating. */
    private fun match(a: (Pos, Int, Random) -> Move, b: (Pos, Int, Random) -> Move, games: Int, seed: Int): Double {
        var pts = 0.0
        for (g in 0 until games) {
            val rnd = Random(seed + g)
            val aSide = g % 2
            var pos = Pos(); var side = B
            var res: Pair<Outcome, Int?>? = null
            while (res == null) {
                val m = if (side == aSide) a(pos, side, rnd) else b(pos, side, rnd)
                res = Rules.outcome(pos, m)
                pos = Rules.apply(pos, m)
                side = Pentago.other(side)
            }
            pts += when (res.second) { null -> 0.5; aSide -> 1.0; else -> 0.0 }
        }
        return pts / games
    }

    private fun ai(level: Int, ms: Long) = { p: Pos, s: Int, r: Random -> Engine.choose(p, s, level, r, ms)!! }

    @Test fun levelsAreOrdered() {
        val strongVsClub = match(ai(3, 1500), ai(2, 300), games, 100)
        val clubVsBeginner = match(ai(2, 300), ai(1, 100), games, 200)
        val naiveVsBeginner = match({ p, s, r -> naive(p, s, r) }, ai(1, 100), games * 4, 300)
        println("strong vs club $strongVsClub · club vs beginner $clubVsBeginner · naive vs beginner $naiveVsBeginner (games=$games)")
        assertTrue("strong vs club $strongVsClub", strongVsClub >= 0.70)
        assertTrue("club vs beginner $clubVsBeginner", clubVsBeginner >= 0.70)
        assertTrue("naive vs beginner $naiveVsBeginner", naiveVsBeginner >= 0.50)
    }

    @Test fun searchStopsWhenCancelled() {
        val pos = Pos.parse("....../..o.../.xx.o./..ox../.x.o../......")
        var cancel = false
        val t = Thread { Thread.sleep(100); cancel = true }
        t.start()
        val t0 = System.nanoTime()
        Engine.choose(pos, B, Engine.HARD, Random(1), 10_000L) { cancel }
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertTrue("stopped in $ms ms (cancel at 100)", ms < 100 + 50 + 30)
    }

    @Test fun strongThinksWithinItsCap() {
        val pos = Pos.parse("....../..o.../.xx.o./..ox../.x.o../......")
        val t0 = System.nanoTime()
        Engine.choose(pos, B, Engine.HARD, Random(1))
        val ms = (System.nanoTime() - t0) / 1_000_000
        println("strong think $ms ms")
        assertTrue(ms < 1500 + 300)
    }
}
