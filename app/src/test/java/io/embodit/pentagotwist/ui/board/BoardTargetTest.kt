package io.embodit.pentagotwist.ui.board

import io.embodit.pentagotwist.domain.Engine
import io.embodit.pentagotwist.domain.GameConfig
import io.embodit.pentagotwist.domain.GameFlow
import io.embodit.pentagotwist.domain.GameStore
import io.embodit.pentagotwist.domain.Mode
import io.embodit.pentagotwist.domain.Pentago
import io.embodit.pentagotwist.domain.Phase
import io.embodit.pentagotwist.domain.SavedGame
import io.embodit.pentagotwist.domain.Settings
import io.embodit.pentagotwist.ui.board.input.parseLoggedPath
import io.embodit.pentagotwist.ui.board.input.resolvePath
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * build.md「命中目标 -> 格子必须有回放单测」(斗兽棋 v0.1.3: every piece tap resolved to square=-). Real hit chains (the hit
 * entity's name first, then its parents: the `path=` of a BoardInput TAP line) for every kind of target -- a marble's GLB mesh
 * and root, a pad holder, its 5 mm marker, a block body and its model, a handle, the frame, a tray -- go through the template's
 * pure resolver and the same dispatch entry the volume uses, and must reach the right cell / block / GameFlow call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BoardTargetTest {
    private val up = listOf("boardRoot", "tiltRoot", "sceneRoot", "root")
    private fun target(chain: List<String>) = resolvePath(chain, BoardNames::resolveName)
    private fun c(n: String) = Pentago.parse(n)!!

    @Test fun everyKindOfHitResolvesToItsCell() {
        for (cell in 0 until Pentago.N) {
            val q = Pentago.quadOf(cell)
            val qr = "quadRoot:$q"
            // marble: GLB mesh node < GLB root (named after its cell) < sceneRoot
            assertEquals(BoardNames.CellHit(cell), target(listOf("marble_black", BoardNames.cell(cell), "sceneRoot", "root")))
            assertEquals(BoardNames.CellHit(cell), target(listOf("marble_white", BoardNames.cell(cell), "sceneRoot", "root")))
            // pad holder and its marker (the marker mesh node < marker root "mk:cell:i" < holder)
            assertEquals(BoardNames.CellHit(cell), target(listOf(BoardNames.cell(cell), qr) + up))
            assertEquals(BoardNames.CellHit(cell), target(listOf("marker", "mk:" + BoardNames.cell(cell), BoardNames.cell(cell), qr) + up))
            assertEquals(q, BoardNames.quadOf(target(listOf(BoardNames.cell(cell), qr) + up)))
        }
        for (q in 0..3) {
            assertEquals(BoardNames.QuadHit(q), target(listOf("quad", "quadModel", BoardNames.quad(q), "quadRoot:$q") + up))
            for (d in listOf(Pentago.CW, Pentago.CCW)) {
                assertEquals(BoardNames.HandleHit(q, d), target(listOf("handle_l_cw", BoardNames.handle(q, d)) + up))
            }
        }
        assertEquals(BoardNames.EmptyHit, target(listOf("frame", "frame") + up))
        assertEquals(BoardNames.EmptyHit, target(listOf("marble_white", "tray", "sceneRoot", "root")))
        assertEquals(BoardNames.EmptyHit, target(listOf("marble_black", "flying", "sceneRoot")))
        assertNull(target(listOf("rim", "rim:2", "quadRoot:2") + up))
    }

    @Test fun outOfRangeNamesAreNotMappedSilently() {
        // 斗兽棋 v0.1.3: a wrong id must show up as "not ours", never as a wrong square
        assertNull(BoardNames.resolveName("cell:36"))
        assertNull(BoardNames.resolveName("cell:-1"))
        assertNull(BoardNames.resolveName("quad:4"))
        assertNull(BoardNames.resolveName("handle:1:up"))
        assertNull(BoardNames.resolveName("mk:cell:3"))
        assertEquals("-", BoardNames.squareText(null))
    }

    /** Paths as BoardInput logs them (`path=a < b < c`); paste the headset's input-log paths here after each device run. */
    @Test fun replayLoggedPaths() {
        val logged = mapOf(
            "marble_black < cell:14 < sceneRoot < root" to "c3",
            "marker < mk:cell:21 < cell:21 < quadRoot:2 < boardRoot < tiltRoot < sceneRoot < root" to "d4",
            "cell:0 < quadRoot:2 < boardRoot < tiltRoot < sceneRoot < root" to "a1",
            "quad < quadModel < quad:1 < quadRoot:1 < boardRoot" to "Q2",
            "handle_r_cw < handle:3:cw < boardRoot" to "Q4cw",
        )
        for ((path, square) in logged) assertEquals(path, square, BoardNames.squareText(resolvePath(parseLoggedPath(path), BoardNames::resolveName)))
    }

    private class NoStore : GameStore {
        override fun load(): SavedGame? = null
        override fun save(game: SavedGame) {}
        override fun clear() {}
    }

    @Test fun dispatchReachesTheGame() = runTest {
        val flow = GameFlow(backgroundScope, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, NoStore(), { Settings(firstRunDone = true, coach = false) })
        flow.newGame(GameConfig(Mode.TWO_PLAYER, Engine.MEDIUM, Pentago.BLACK)); runCurrent()
        // a pinch on the pad's marker places the marble
        BoardNames.dispatch(flow, target(parseLoggedPath("marker < mk:cell:0 < cell:0 < quadRoot:2 < boardRoot")))
        advanceTimeBy(300); runCurrent()
        assertEquals(Phase.TWIST, flow.state.value.phase)
        // a pinch on another block's body picks it, its handle turns it
        BoardNames.dispatch(flow, target(parseLoggedPath("quad < quadModel < quad:1 < quadRoot:1")))
        assertEquals(1, flow.state.value.picked)
        BoardNames.dispatch(flow, target(parseLoggedPath("handle_r_cw < handle:1:cw < boardRoot")))
        assertEquals(Phase.TWISTING, flow.state.value.phase)
        advanceTimeBy(700); runCurrent()
        // a pinch on the marble's mesh of a taken cell = "这儿有珠了"
        BoardNames.dispatch(flow, target(parseLoggedPath("marble_black < cell:0 < sceneRoot")))
        assertTrue(flow.state.value.alert != null)
        assertEquals(c("a1"), flow.state.value.alert!!.cell)
    }
}
