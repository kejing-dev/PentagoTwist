package io.embodit.pentagotwist.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design acceptance.mustPass M1 / M2: rules and rotation. */
class RulesTest {
    private fun c(n: String) = Pentago.parse(n)!!
    private fun p(s: String) = Pos.parse(s)
    private val B = Pentago.BLACK
    private val W = Pentago.WHITE

    @Test fun thirtyTwoWindowsOfFive() {
        assertEquals(32, Pentago.WINDOWS.size)
        assertEquals(32, Pentago.WINDOWS.toSet().size)
        Pentago.WINDOW_CELLS.forEach { w -> assertEquals(5, w.toSet().size) }
    }

    @Test fun fiveInEveryDirectionIncludingAcrossBlocks() {
        // horizontal across Q3 / Q4 (row 1), vertical across Q1 / Q3 (column c), both diagonals, a short diagonal
        for ((cells, name) in listOf(
            listOf("a1", "b1", "c1", "d1", "e1") to "row 1 a-e", listOf("b1", "c1", "d1", "e1", "f1") to "row 1 b-f",
            listOf("c1", "c2", "c3", "c4", "c5") to "column c", listOf("f2", "f3", "f4", "f5", "f6") to "column f",
            listOf("a1", "b2", "c3", "d4", "e5") to "long diagonal", listOf("b2", "c3", "d4", "e5", "f6") to "long diagonal 2",
            listOf("f1", "e2", "d3", "c4", "b5") to "anti diagonal", listOf("b1", "c2", "d3", "e4", "f5") to "short diagonal",
            listOf("a2", "b3", "c4", "d5", "e6") to "short diagonal 2", listOf("e1", "d2", "c3", "b4", "a5") to "short anti",
        )) {
            var pos = Pos()
            cells.forEach { pos = pos.place(c(it), B) }
            assertTrue(name, pos.five(B))
            assertFalse(name, pos.five(W))
        }
        var four = Pos(); listOf("a1", "b1", "c1", "d1").forEach { four = four.place(c(it), B) }
        assertFalse(four.five(B))
        var broken = Pos(); listOf("a1", "b1", "c1", "e1", "f1").forEach { broken = broken.place(c(it), B) }
        assertFalse(broken.five(B))
    }

    @Test fun winOnPlaceNeedsNoTwist() {
        for ((s, cell) in listOf(
            "....../....../....../....../....../xxxx.." to "e1",
            "....../x...../x...../....../x...../x....." to "a3",
            "....../....../...x../..x.../.x..../x....." to "e5",
        )) {
            val pos = p(s)
            val m = Move(B, c(cell), -1, 0)
            assertTrue(s, Rules.legal(pos, m))
            assertEquals(s, Outcome.WIN_PLACE to B, Rules.outcome(pos, m))
            assertFalse("a twist is not allowed after a five", Rules.legal(pos, Move(B, c(cell), 0, 1)))
        }
    }

    @Test fun winAfterTheTwist() {
        // design sampleFirstFrame: black a1 a2 a3 b4, black places c4 and turns Q1 clockwise: b4 -> a5, c4 -> a4
        val pos = p("....../....o./.x..../x....o/x..oo./x.....")
        val m = Move(B, c("c4"), 0, Pentago.CW)
        assertTrue(Rules.legal(pos, m))
        val after = Rules.apply(pos, m)
        assertEquals(B, after.owner(c("a5"))); assertEquals(B, after.owner(c("a4")))
        assertEquals(Outcome.WIN_TWIST to B, Rules.outcome(pos, m))
        // the same place with the other turn: no five
        assertNull(Rules.outcome(pos, Move(B, c("c4"), 0, Pentago.CCW)))
        // a twist can hand the five to the other side: then the other side wins (found by a seeded random search)
        val rnd = java.util.Random(3)
        var checked = 0
        repeat(3000) {
            if (checked > 0) return@repeat
            var q = Pos()
            repeat(13) { k -> val empty = (0 until 36).filter { q.empty(it) }; q = q.place(empty[rnd.nextInt(empty.size)], k % 2) }
            if (q.five(B) || q.five(W)) return@repeat
            for ((mv, af) in Engine.allMoves(q, B)) if (mv.quad >= 0 && af.five(W) && !af.five(B)) { assertEquals(Outcome.WIN_TWIST to W, Rules.outcome(q, mv)); checked++ }
        }
        assertTrue(checked > 0)
    }

    @Test fun doubleFiveAfterTheTwistIsADraw() {
        // black row 6 a-e except c6 (lies in Q1), white row 4 a-e except c4; black places nothing that helps... construct:
        // black: a6 b6 d6 e6 + c5 ; white: a4 b4 d4 e4 + ... a twist of Q1 clockwise moves c5 (u=1,v=0) -> (0,-1) = b4? use the search:
        var found = false
        val rnd = java.util.Random(7)
        repeat(4000) {
            if (found) return@repeat
            var pos = Pos()
            repeat(14) { k -> val empty = (0 until 36).filter { pos.empty(it) }; pos = pos.place(empty[rnd.nextInt(empty.size)], k % 2) }
            for ((m, after) in Engine.allMoves(pos, B)) if (m.quad >= 0 && after.five(B) && after.five(W)) {
                assertEquals(Outcome.DRAW_DOUBLE to null, Rules.outcome(pos, m)); found = true; break
            }
        }
        assertTrue("found a double five", found)
    }

    @Test fun fullBoardWithoutFiveIsADraw() {
        // a known 36-marble no-five pattern (2 x 2 checks shifted per row pair) placed as the last move + twist
        val rows = listOf("xxooxx", "ooxxoo", "xxooxx", "ooxxoo", "xxooxx", "ooxxoo")
        val full = Pos.parse(rows.joinToString("/"))
        assertTrue(full.full); assertFalse(full.five(B)); assertFalse(full.five(W))
        assertEquals(Outcome.DRAW_FULL to null, Rules.afterTwist(full, B))
        // as a real last move: remove f1 (row 1 = last row string) and place it back, then a twist that keeps no five
        val last = c("f1"); val side = full.owner(last)
        val pre = full.remove(last)
        val draws = (0..3).flatMap { q -> listOf(1, -1).map { d -> Move(side, last, q, d) } }.filter { m -> Rules.outcome(pre, m)?.first == Outcome.DRAW_FULL }
        assertTrue(draws.isNotEmpty())
    }

    @Test fun mootTwistIsSkipped() {
        assertTrue("empty board", Pos().twistIsMoot)
        for (centre in listOf("b2", "e2", "b5", "e5")) assertTrue(centre, Pos().place(c(centre), B).twistIsMoot)
        assertFalse(Pos().place(c("a1"), B).twistIsMoot)
        assertFalse(Pos().place(c("c3"), B).twistIsMoot)
        // a skipped move is legal only then
        assertTrue(Rules.legal(Pos(), Move(B, c("b2"), -1, 0)))
        assertFalse(Rules.legal(Pos(), Move(B, c("a1"), -1, 0)))
    }

    @Test fun mustTwistAndOnlyOnEmpty() {
        val pos = Pos().place(c("a1"), B)
        assertFalse("taken", Rules.legal(pos, Move(W, c("a1"), 0, 1)))
        assertFalse("no twist without a reason", Rules.legal(pos, Move(W, c("d4"), -1, 0)))
        assertTrue(Rules.legal(pos, Move(W, c("d4"), 2, -1)))
    }

    @Test fun rotationMapsEveryCellAndFourTurnsComeBack() {
        for (q in 0..3) for (dir in listOf(1, -1)) {
            val cells = Pentago.QUAD_CELLS[q]
            val images = cells.map { Pentago.rotateCell(it, dir) }
            assertEquals(cells.toSet(), images.toSet())
            for (k in cells) assertEquals(k, Pentago.rotateCell(Pentago.rotateCell(k, dir), -dir))
            var pos = Pos(); cells.forEachIndexed { i, cc -> if (i % 2 == 0) pos = pos.place(cc, B) else if (i == 1) pos = pos.place(cc, W) }
            var t = pos; repeat(4) { t = t.twist(q, dir) }
            assertEquals(pos, t)
            // other blocks never move
            val other = Pos().place(c(if (q == 0) "f1" else "a6"), W)
            assertEquals(other, other.twist(q, dir))
        }
        // clockwise from the user's view: the far-left corner goes far-right (Q1: a6 -> c6), the centre stays
        assertEquals(c("c6"), Pentago.rotateCell(c("a6"), Pentago.CW))
        assertEquals(c("a4"), Pentago.rotateCell(c("a6"), Pentago.CCW))
        assertEquals(c("b5"), Pentago.rotateCell(c("b5"), Pentago.CW))
    }

    @Test fun winRunsMergeASix() {
        val pos = Pos.parse("....../....../....../....../....../xxxxxx")
        val runs = Pentago.winRuns(pos.black)
        assertEquals(1, runs.size)
        assertEquals(6, runs[0].size)
    }

    @Test fun saveCodecRoundTripsWithPending() {
        val moves = listOf(Move(B, c("b2"), -1, 0), Move(W, c("d4"), 1, 1), Move(B, c("a1"), 3, -1))
        val g = SavedGame(GameConfig(Mode.AI, 3, W), moves, pending = c("f6"), elapsedMs = 1234, hintOn = true, hintsUsed = 2)
        val back = SaveCodec.decode(SaveCodec.encode(g))!!
        assertEquals(g, back)
        assertNull(SaveCodec.decode("pt1|AI|2|0|0|0|0|-1|0.9.1|"))
    }
}
