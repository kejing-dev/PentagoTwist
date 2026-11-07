package io.embodit.pentagotwist.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** design acceptance.mustPass M4 / M6 + the input.hitPriority table, through the same GameFlow calls the volume and panel use. */
@OptIn(ExperimentalCoroutinesApi::class)
class FlowTest {
    private class MemStore : GameStore {
        var text: String? = null
        override fun load() = text?.let { SaveCodec.decode(it) }
        override fun save(game: SavedGame) { text = SaveCodec.encode(game) }
        override fun clear() { text = null }
    }

    private class Harness(scope: TestScope, val store: MemStore = MemStore(), var settings: Settings = Settings(firstRunDone = true, coach = false)) {
        val sounds = ArrayList<Sound>()
        val flow = GameFlow(
            scope = scope.backgroundScope,
            engineDispatcher = StandardTestDispatcher(scope.testScheduler),
            clock = { scope.testScheduler.currentTime },
            store = store,
            settings = { settings },
            onSettings = { settings = it },
            onSound = { sounds += it },
        )
        val st get() = flow.state.value
    }

    private fun c(n: String) = Pentago.parse(n)!!
    private fun TestScope.settle(ms: Long = 3_000) { advanceTimeBy(ms); runCurrent() }
    private val TWO = GameConfig(Mode.TWO_PLAYER, Engine.MEDIUM, Pentago.BLACK)

    @Test fun firstRunOpensTheDefaultGameWithTheCoach() = runTest {
        val h = Harness(this, settings = Settings())
        h.flow.start(); runCurrent()
        assertEquals(Phase.PLACE, h.st.phase)
        assertTrue(h.st.welcome)
        assertEquals(Mode.AI, h.st.config!!.mode)
        assertEquals(StatusKey.COACH_1, h.st.status().key)
        h.flow.tapCell(c("c3"))
        assertFalse(h.st.welcome); assertTrue(h.settings.firstRunDone)
        assertEquals(Phase.PLACING, h.st.phase)
        settle(300)
        assertEquals(Phase.TWIST, h.st.phase)
        assertEquals(StatusKey.COACH_2, h.st.status().key)
    }

    @Test fun placeFliesThenWaitsForTheTwist() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("a1"))
        assertEquals(Phase.PLACING, h.st.phase)
        assertEquals(c("a1"), h.st.pending)
        assertTrue("saved before the animation", h.store.load()!!.pending == c("a1"))
        settle(GameFlow.FLY_MS + 10)
        assertEquals(Phase.TWIST, h.st.phase)
        assertTrue(Sound.PLACE in h.sounds)
        // S03 rules in S07: a pad elsewhere picks that block, the handle turns it
        h.flow.tapCell(c("e5"))
        assertEquals(1, h.st.picked)
        assertEquals(StatusKey.TWIST_PICKED, h.st.status().key)
        h.flow.tapQuad(3)
        assertEquals(3, h.st.picked)
        h.flow.twist(2, Pentago.CW)
        assertEquals(Phase.TWISTING, h.st.phase)
        assertEquals(1, h.st.moves.size)
        assertEquals(Pentago.rotateCell(c("a1"), Pentago.CW), h.st.pos.black.countTrailingZeroBits())
        settle(GameFlow.TWIST_MS + 10)
        assertEquals(Phase.PLACE, h.st.phase)
        assertEquals(Pentago.WHITE, h.st.side)
        assertEquals(3, h.sounds.count { it == Sound.CLICK })
        assertTrue(Sound.LAND in h.sounds)
    }

    @Test fun takingBackTheMarbleIsNotAnUndo() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("a1")); settle(300)
        // hitPriority S07 ①: the marble just placed -> take it back
        h.flow.tapCell(c("a1"))
        assertEquals(Phase.PLACING, h.st.phase)
        assertNull(h.st.pending)
        settle(300)
        assertEquals(Phase.PLACE, h.st.phase)
        assertEquals(0, h.st.moves.size)
        assertTrue(h.st.redo.isEmpty())
        assertEquals(Pentago.BLACK, h.st.side)
        // [悔一回合] in S07 is the same take-back
        h.flow.tapCell(c("b1")); settle(300)
        assertTrue(h.st.canUndo)
        h.flow.undo(); settle(300)
        assertEquals(Phase.PLACE, h.st.phase); assertEquals(0, h.st.moves.size); assertTrue(h.st.board.empty(c("b1")))
    }

    @Test fun wrongOrderAndTakenDimplesSayWhy() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapQuad(0)
        assertEquals(Reason.PLACE_FIRST, h.st.alert?.reason)
        h.flow.twist(0, 1)
        assertEquals(Phase.PLACE, h.st.phase)
        // a drag in S03: refused ("先放一颗") and swallowed (no placement when it ends)
        assertTrue(h.flow.dragStart(0))
        h.flow.dragEnd(0, Pentago.CW, 0.08f)
        assertEquals(Phase.PLACE, h.st.phase); assertEquals(0, h.st.board.count)
        h.flow.tapCell(c("a1")); settle(300); h.flow.twist(3, 1); settle(700)
        h.flow.tapCell(c("a1"))
        assertEquals(Reason.OCCUPIED, h.st.alert?.reason)
        assertEquals(c("a1"), h.st.alert?.cell)
        assertEquals(Phase.PLACE, h.st.phase)
        assertTrue(Sound.ILLEGAL in h.sounds)
    }

    @Test fun dragPastFiveCentimetresTurnsTheBlock() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("a1")); settle(300)
        assertTrue(h.flow.dragStart(2))
        assertEquals(2, h.st.held)
        h.flow.dragEnd(2, Pentago.CCW, 0.03f)
        assertEquals("short drag: nothing turns", Phase.TWIST, h.st.phase)
        assertNull(h.st.held)
        assertTrue(h.flow.dragStart(2))
        h.flow.dragEnd(2, Pentago.CCW, 0.06f)
        assertEquals(Phase.TWISTING, h.st.phase)
        assertEquals(Move(Pentago.BLACK, c("a1"), 2, Pentago.CCW), h.st.moves.last())
    }

    @Test fun mootTwistIsSkipped() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("b2")); settle(GameFlow.FLY_MS + 10)
        assertEquals(Phase.PLACE, h.st.phase)
        assertEquals(Pentago.WHITE, h.st.side)
        assertEquals(Move(Pentago.BLACK, c("b2"), -1, 0), h.st.moves.single())
        assertEquals(StatusKey.SKIPPED, h.st.status().key)
        settle(GameFlow.SKIP_NOTICE_MS + 10)
        assertEquals(StatusKey.PLACE, h.st.status().key)
    }

    @Test fun fiveOnPlacingWinsWithoutATwist() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO, Pos.parse("....../....../....../....../oooo../xxxx.."), Pentago.BLACK); runCurrent()
        h.flow.tapCell(c("e1")); settle(300)
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(Outcome.WIN_PLACE, h.st.outcome)
        assertEquals(Pentago.BLACK, h.st.winner)
        assertEquals(1, h.st.winRuns.size); assertEquals(5, h.st.winRuns[0].second.size)
        assertTrue(Sound.WIN in h.sounds)
    }

    @Test fun twistFiveWinsAndTheAisWinIsNotAFanfare() = runTest {
        // design sampleFirstFrame (S17 / S09): black c4 + Q1 clockwise
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Engine.MEDIUM, Pentago.BLACK), Pos.parse("....../....o./.x..../x....o/x..oo./x....."), Pentago.BLACK); runCurrent()
        h.flow.tapCell(c("c4")); settle(300)
        h.flow.twist(0, Pentago.CW)
        settle(1_000)
        assertEquals(Phase.RESULT, h.st.phase)
        assertEquals(Outcome.WIN_TWIST, h.st.outcome)
        assertEquals(StatusKey.OVER, h.st.status().key)
        assertTrue(Sound.WIN in h.sounds)
        // the AI side wins: one low note, no win sound
        val h2 = Harness(this)
        h2.flow.newGame(GameConfig(Mode.AI, Engine.HARD, Pentago.WHITE), Pos.parse("....../....o./.x..../x....o/x..oo./x....."), Pentago.BLACK)
        settle(10_000)
        assertEquals(Phase.RESULT, h2.st.phase)
        assertEquals(Pentago.BLACK, h2.st.winner)
        assertTrue(Sound.LOSE in h2.sounds); assertFalse(Sound.WIN in h2.sounds)
    }

    @Test fun aiTakesItsTurnAndUndoTakesBackTheRound() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Engine.EASY, Pentago.BLACK)); runCurrent()
        assertFalse("nothing to undo", h.st.canUndo)
        h.flow.tapCell(c("a1")); settle(300); h.flow.twist(3, 1)
        runCurrent()
        assertFalse("grey during the twist", h.st.canUndo)
        settle(600)
        assertEquals(Phase.THINKING, h.st.phase)
        assertFalse("grey while the AI thinks", h.st.canUndo)
        h.flow.tapCell(c("f6"))
        assertEquals(Reason.THINKING, h.st.alert?.reason)
        settle(5_000)
        assertEquals(Phase.PLACE, h.st.phase)
        assertEquals(2, h.st.moves.size)
        assertTrue(h.st.canUndo)
        h.flow.undo()
        assertEquals(0, h.st.moves.size)
        assertNotNull("reverse animation", h.st.anim)
        assertEquals(4, h.st.anim!!.steps.size)
        settle(2_000)
        assertEquals(Phase.PLACE, h.st.phase); assertEquals(0, h.st.board.count); assertEquals(2, h.st.redo.size)
        h.flow.redo(); settle(3_000)
        assertEquals(2, h.st.moves.size)
    }

    @Test fun twoPlayerUndoAsksFirst() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("a1")); settle(300); h.flow.twist(3, 1); settle(700)
        h.flow.undo()
        assertEquals(ConfirmKind.UNDO_2P, h.st.confirm)
        h.flow.tapCell(c("f6"))
        assertTrue("locked by the confirm", h.st.board.empty(c("f6")))
        h.flow.confirm(); settle(2_000)
        assertEquals(0, h.st.moves.size)
    }

    @Test fun killedMidTwistRestoresTheTurnedBoard() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("a1")); settle(300); h.flow.twist(2, Pentago.CW)
        runCurrent()
        assertEquals(Phase.TWISTING, h.st.phase)
        val h2 = Harness(this, h.store)
        h2.flow.start(); runCurrent()
        assertTrue(h2.st.restored)
        assertEquals(1, h2.st.moves.size)
        assertEquals(Pentago.BLACK, h2.st.pos.owner(Pentago.rotateCell(c("a1"), Pentago.CW)))
        assertEquals(Phase.PLACE, h2.st.phase)
        assertEquals(Pentago.WHITE, h2.st.side)
    }

    @Test fun killedBeforeTheTwistRestoresTheMarbleAndS07() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("a1")); settle(300)
        val h2 = Harness(this, h.store)
        h2.flow.start(); settle(300)
        assertEquals(Phase.TWIST, h2.st.phase)
        assertEquals(c("a1"), h2.st.pending)
        h2.flow.tapCell(c("a1")); settle(300)
        assertEquals("撤回 still works after a restore", Phase.PLACE, h2.st.phase)
    }

    @Test fun pauseHoldsTheAiAndLocksTheBoard() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Engine.EASY, Pentago.WHITE)); runCurrent()
        h.flow.pause(true)
        assertEquals(StatusKey.PAUSED, h.st.status().key)
        settle(5_000)
        assertEquals(0, h.st.board.count)
        h.flow.pause(false); settle(5_000)
        assertTrue(h.st.board.count >= 1)
    }

    @Test fun backgroundLandsTheAnimationAndKeepsTheGame() = runTest {
        val h = Harness(this)
        h.flow.newGame(TWO); runCurrent()
        h.flow.tapCell(c("a1"))
        h.flow.onBackground(); runCurrent()
        assertEquals(Phase.TWIST, h.st.phase)
        assertNull(h.st.anim)
        assertEquals(c("a1"), h.store.load()!!.pending)
    }

    @Test fun resignAndRestart() = runTest {
        val h = Harness(this)
        h.flow.newGame(GameConfig(Mode.AI, Engine.MEDIUM, Pentago.BLACK)); runCurrent()
        h.flow.requestConfirm(ConfirmKind.RESIGN); h.flow.confirm()
        assertEquals(Phase.RESULT, h.st.phase); assertEquals(Outcome.RESIGN, h.st.outcome); assertEquals(Pentago.WHITE, h.st.winner)
        assertTrue(Sound.DRAW in h.sounds); assertFalse(Sound.WIN in h.sounds)
        h.flow.again(); runCurrent()
        assertEquals(Phase.PLACE, h.st.phase)
        h.flow.tapCell(c("a1")); settle(300)
        assertTrue(h.st.hasProgress)
        h.flow.requestConfirm(ConfirmKind.RESTART); h.flow.confirm()
        assertEquals(Phase.SETUP, h.st.phase)
        assertNull(h.store.load())
    }

    @Test fun settingsAreNotTouchedByAGame() = runTest {
        val before = Settings(firstRunDone = true, coach = false, tilt = 45, size = 120, volume = 1)
        val h = Harness(this, settings = before)
        h.flow.newGame(GameSetup(Mode.AI, Engine.EASY, SeatPick.BLACK)); runCurrent()
        h.flow.tapCell(c("a1")); settle(300); h.flow.twist(3, 1); settle(6_000)
        assertEquals(before.copy(lastSetup = GameSetup(Mode.AI, Engine.EASY, SeatPick.BLACK)), h.settings)
    }
}
