package io.embodit.pentagotwist.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * design spatialLayout.panelGeometryCheck.unitTest (D35) for every tilt (0 / 30 / 45 / 60 / 90) x size (100 / 120 %) x eye
 * (emulator reset at 0.7-2.5 m and ±30°, sit low, sit, stand): ① eye -> every dimple never crosses the trays, the plate or
 * the handles; marble tops stay visible past the other marbles (full board); ② the plate clears the frame; ③ every flight
 * stays in the volume; ④ a turned block's dimples land on the cells ±1 mm; ⑤ a lifted block clears its neighbours and
 * their marbles; ⑥ everything sits inside the 0.70 x 0.74 x 0.90 volume. Plus the drag -> twist direction.
 */
class LayoutTest {
    private val vol = Layout.volume()

    private fun inVolume(b: Box3, what: String) {
        val v = vol
        assertTrue("$what $b outside $v", b.min.x >= v.min.x - 1e-4f && b.max.x <= v.max.x + 1e-4f && b.min.y >= v.min.y - 1e-4f &&
            b.max.y <= v.max.y + 1e-4f && b.min.z >= v.min.z - 1e-4f && b.max.z <= v.max.z + 1e-4f)
    }

    @Test fun turnedBlocksCarryMarblesOntoCells() {
        for (c in 0 until Pentago.N) for (dir in listOf(Pentago.CW, Pentago.CCW)) {
            var cell = c
            for (k in 1..4) {
                val turned = Layout.marbleLocal(c, Layout.yawOf(dir, k.toFloat()))
                cell = Pentago.rotateCell(cell, dir)
                val target = Layout.marbleLocal(cell)
                assertTrue("${Pentago.name(c)} dir=$dir x$k -> ${Pentago.name(cell)}", (turned - target).len() < 0.001f)
            }
            assertEquals(c, cell)
        }
    }

    @Test fun clockwiseIsClockwiseFromAbove() {
        // the far-left corner of Q1 (a6) goes to the far-right corner (c6) on a clockwise turn: +X in board-local space
        val a6 = Layout.cell(Pentago.parse("a6")!!)
        val q = Layout.quadCentre(0)
        val after = Layout.marbleLocal(Pentago.parse("a6")!!, Layout.yawOf(Pentago.CW, 1f))
        assertTrue(after.x > a6.x + 0.1f)
        assertTrue(abs(after.z - a6.z) < 0.001f)
        assertTrue(q.z < 0) // Q1 is far (-Z)
    }

    @Test fun liftedBlockClearsNeighbourMarbles() {
        val bottom = Layout.PROUD - Layout.QUAD_T + Layout.TWIST_LIFT
        assertTrue("clears the neighbour blocks", bottom > Layout.PROUD + 0.004f)
        // the corner (R 2 cm rounded) sweeps a circle of r = 0.08·√2 + 0.02; any neighbour marble surface inside it must be lower
        val sweep = 0.08f * sqrt(2f) + 0.02f
        for (q in 0..3) {
            val qc = Layout.quadCentre(q)
            for (c in 0 until Pentago.N) {
                if (Pentago.quadOf(c) == q) continue
                val m = Layout.marbleLocal(c)
                val d = sqrt((m.x - qc.x) * (m.x - qc.x) + (m.z - qc.z) * (m.z - qc.z))
                val off = sweep - d
                if (off <= -Layout.MARBLE_R) continue
                val h = if (off >= 0f) Layout.MARBLE_R else sqrt(Layout.MARBLE_R * Layout.MARBLE_R - off * off)
                assertTrue("Q${q + 1} corner over ${Pentago.name(c)}: marble top ${m.y + h} vs bottom $bottom", m.y + h < bottom - 0.002f)
            }
        }
    }

    @Test fun everythingInsideTheVolume() {
        for (tilt in TILTS) for (size in SIZES) {
            val tag = "tilt=$tilt size=$size"
            inVolume(Layout.sceneBox(Layout.frameBoxLocal(), tilt, size), "$tag frame")
            for (q in 0..3) {
                inVolume(Layout.sceneBox(Layout.quadSweepLocal(q, Layout.TWIST_LIFT), tilt, size), "$tag lifted Q${q + 1}")
                for (d in listOf(Pentago.CW, Pentago.CCW)) inVolume(Layout.sceneBox(Layout.handleBoxLocal(q, d), tilt, size), "$tag handle Q${q + 1}")
            }
            for (side in 0..1) inVolume(Layout.trayContentBox(side, size), "$tag tray $side")
            inVolume(Layout.plateBox(tilt, size), "$tag plate")
            // win lift: a marble raised 2 cm along the normal
            for (c in listOf(0, 5, 30, 35)) {
                val p = Layout.toScene(Layout.marbleLocal(c, 0f, Layout.WIN_LIFT), tilt, size)
                inVolume(Box3.around(p, V3(0.05f, 0.05f, 0.05f) * Layout.scale(size)), "$tag win lift ${Pentago.name(c)}")
            }
            // ③ every flight: tray slot -> dimple (arc 8 cm) and back
            val s = Layout.scale(size)
            for (side in 0..1) for (k in listOf(0, 8, 17)) for (c in 0 until Pentago.N) {
                val a = Layout.traySlot(side, k, size); val b = Layout.toScene(Layout.marbleLocal(c), tilt, size)
                for (i in 0..10) {
                    val p = Layout.arc(a, b, i / 10f, Layout.ARC * s)
                    assertTrue("$tag flight ${Pentago.name(c)} $p", vol.contains(p, Layout.MARBLE_R * s))
                }
            }
        }
    }

    @Test fun plateClearsTheFrame() {
        for (tilt in TILTS) for (size in SIZES) {
            val plate = Layout.plateBox(tilt, size)
            val frame = Layout.sceneBox(Layout.frameBoxLocal(), tilt, size)
            // the plate stands above the far rim: its bottom >= 2 cm over the rim's top point there
            val rimTop = Layout.toScene(V3(0f, 0f, -(Layout.FRAME / 2 - 0.01f)), tilt, size)
            assertTrue("tilt=$tilt size=$size plate bottom ${plate.min.y} vs rim ${rimTop.y}", plate.min.y >= rimTop.y + Layout.PLATE_GAP - 1e-4f)
            assertTrue(frame.max.y < plate.max.y)
        }
    }

    @Test fun nothingHidesADimple() {
        val fails = ArrayList<String>()
        var checks = 0
        for (tilt in TILTS) for (size in SIZES) for ((eyeName, eye) in Layout.eyes()) {
            val n = Layout.normal(tilt)
            val s = Layout.scale(size)
            val blockers = buildList {
                add("plate" to Layout.plateBox(tilt, size))
                for (side in 0..1) add("tray$side" to Layout.trayContentBox(side, size))
            }
            for (c in 0 until Pentago.N) {
                val pit = Layout.toScene(Layout.cell(c), tilt, size)
                // the eye must see the board's face (it is on the normal's side)
                if ((eye - pit).dot(n) <= 0f) continue
                val top = Layout.toScene(Layout.marbleLocal(c) + V3(0f, Layout.MARBLE_R, 0f), tilt, size)
                for ((name, box) in blockers) for (target in listOf(pit, top)) {
                    checks++
                    if (box.hitBySegment(eye, target)) fails += "tilt=$tilt size=$size $eyeName ${Pentago.name(c)} hidden by $name"
                }
                // handles (shown on one block at a time, never on a dimple)
                for (q in 0..3) for (d in listOf(Pentago.CW, Pentago.CCW)) {
                    val hb = Layout.sceneBox(Layout.handleBoxLocal(q, d), tilt, size)
                    checks++
                    if (hb.hitBySegment(eye, pit) && Pentago.quadOf(c) == q) fails += "tilt=$tilt $eyeName ${Pentago.name(c)} hidden by handle Q${q + 1}"
                }
                // a full board: the top of every marble is seen past the others
                for (o in 0 until Pentago.N) {
                    if (o == c) continue
                    val oc = Layout.toScene(Layout.marbleLocal(o), tilt, size)
                    checks++
                    if (segmentNearPoint(eye, top - (top - eye) * (0.004f / (top - eye).len()), oc, Layout.MARBLE_R * s * 0.98f))
                        fails += "tilt=$tilt size=$size $eyeName top of ${Pentago.name(c)} behind ${Pentago.name(o)}"
                }
            }
        }
        println("occlusion checks $checks, fails ${fails.size}")
        fails.groupBy { it.substringBefore(" size") }.forEach { (k, v) -> println("$k: ${v.size}  e.g. ${v.first()}") }
        assertTrue(fails.take(10).joinToString("\n"), fails.isEmpty())
    }

    @Test fun dragDirection() {
        // push the far edge of Q1 to the right = clockwise (seen from above), flat board: board-local = scene
        val q1 = 0
        val farCell = Pentago.parse("b6")!!
        val (d1, a1) = Layout.twistOfDrag(q1, farCell, V3(0.06f, 0f, 0f), 0)
        assertEquals(Pentago.CW, d1); assertTrue(a1 > 0.05f)
        val (d2, _) = Layout.twistOfDrag(q1, farCell, V3(-0.06f, 0f, 0f), 0)
        assertEquals(Pentago.CCW, d2)
        // the near edge pushed right turns the other way
        val (d3, _) = Layout.twistOfDrag(q1, Pentago.parse("b4")!!, V3(0.06f, 0f, 0f), 0)
        assertEquals(Pentago.CCW, d3)
        // tilted 45°: a push up the slope on the right edge of Q4 (cell f2) — scene (0, +y, -z) — is counter-clockwise
        val up = V3(0f, 0.0424f, -0.0424f)
        val (d4, a4) = Layout.twistOfDrag(3, Pentago.parse("f2")!!, up, 45)
        assertEquals(Pentago.CCW, d4); assertTrue(a4 > 0.055f)
        // a press on the block body uses the outer corner: Q2 (far-right), pushing right along its far side -> clockwise
        val (d5, _) = Layout.twistOfDrag(1, null, V3(0.06f, 0f, 0f), 0)
        assertEquals(Pentago.CW, d5)
        // a short drag reports a small travel (stays < 5 cm, no turn)
        val (_, a6) = Layout.twistOfDrag(q1, farCell, V3(0.02f, 0f, 0f), 0)
        assertTrue(a6 < GameFlow.DRAG_TURN_M)
    }
}
