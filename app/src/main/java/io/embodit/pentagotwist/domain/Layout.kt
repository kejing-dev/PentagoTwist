package io.embodit.pentagotwist.domain

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A small vector for layout maths (metres). Kept free of SDK types so it can be unit tested. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun len(): Float = sqrt(x * x + y * y + z * z)
}

/** An axis-aligned box. */
data class Box3(val min: V3, val max: V3) {
    fun contains(p: V3, eps: Float = 0f) = p.x > min.x - eps && p.x < max.x + eps && p.y > min.y - eps && p.y < max.y + eps && p.z > min.z - eps && p.z < max.z + eps

    /** Does the segment a-b pass through the box (slab test)? */
    fun hitBySegment(a: V3, b: V3): Boolean {
        var t0 = 0f; var t1 = 1f
        val d = b - a
        val ad = floatArrayOf(a.x, a.y, a.z); val dd = floatArrayOf(d.x, d.y, d.z)
        val lo = floatArrayOf(min.x, min.y, min.z); val hi = floatArrayOf(max.x, max.y, max.z)
        for (i in 0..2) {
            if (abs(dd[i]) < 1e-9f) { if (ad[i] < lo[i] || ad[i] > hi[i]) return false; continue }
            var ta = (lo[i] - ad[i]) / dd[i]; var tb = (hi[i] - ad[i]) / dd[i]
            if (ta > tb) { val t = ta; ta = tb; tb = t }
            t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            if (t0 > t1) return false
        }
        return true
    }

    companion object {
        fun around(c: V3, size: V3) = Box3(c - size * 0.5f, c + size * 0.5f)
        fun of(points: List<V3>) = Box3(V3(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }), V3(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
    }
}

/** Does segment a-b come within [r] of point c? */
fun segmentNearPoint(a: V3, b: V3, c: V3, r: Float): Boolean {
    val ab = b - a
    val t = ((c - a).dot(ab) / ab.dot(ab)).coerceIn(0f, 1f)
    return (a + ab * t - c).len() < r
}

/**
 * Where everything sits in the board volume (design spatialLayout; container 0.70 x 0.74 x 0.90 m, see [VOLUME]).
 * Scene space: origin on the volume floor, centred in x, z = 0 at the volume centre, +Z toward the user, +Y up.
 *
 *   sceneRoot (volume floor)
 *   ├ tiltRoot   at the board's near-bottom edge (z = [PIVOT_Z]), tipped [tilt] about X (far edge up, the face toward the user)
 *   │ └ boardRoot  the frame's top centre, scaled [size] / 100 (board-local: +X right, +Z toward the user, +Y the board normal)
 *   │   └ quadRoot q at (±0.105, 0.008, ±0.105): yaw turns the block; its 9 pads + markers ride on it
 *   ├ the two trays (flat on the floor, in front of the near edge) and all 36 marbles (scene space, placed every frame)
 *   └ move plate (AttachmentPanel, upright above the far rim)
 * All board-local numbers below are at 100 %; scene positions multiply them by s = size / 100.
 */
object Layout {
    const val PITCH = 0.06f
    const val FRAME = 0.50f
    const val FRAME_T = 0.035f
    const val QUAD = 0.20f
    const val QUAD_T = 0.02f
    /** The blocks stand 8 mm proud of the maple frame. */
    const val PROUD = 0.008f
    const val QUAD_OFF = 0.105f
    const val DIMPLE_A = 0.022f
    const val DIMPLE_D = 0.010f
    const val MARBLE_R = 0.020f
    /** Marble centre above the block top when resting in its dimple (sphere-cap radius (a² + h²) / 2h): shows 3.0 cm (design). */
    val MARBLE_UP: Float = run {
        val rs = (DIMPLE_A * DIMPLE_A + DIMPLE_D * DIMPLE_D) / (2 * DIMPLE_D)
        -DIMPLE_D + rs - (rs - MARBLE_R)
    }
    /**
     * A twist lifts the block first (design C6 said 3 cm: enough to clear the neighbour BLOCKS, but at 45° the rounded corner
     * (0.133 m from the centre) passes over the neighbours' edge marbles, whose tops there reach 2.85 cm over the frame;
     * 4.5 cm puts the block's bottom at 3.3 cm. Deviation dv-lift, LayoutTest.liftedBlockClearsNeighbourMarbles).
     */
    const val TWIST_LIFT = 0.045f
    /** Gaze marker (board-input template): a 5 mm dot floating at the dimple mouth, 2 mm under the block top. */
    const val MARKER_D = 0.005f
    const val MARKER_Y = -0.002f
    /** Pad collider: 5.4 x 0.3 x 5.4 cm (0.9 x pitch) on the block top. */
    const val PAD_HIT = 0.054f
    const val PAD_T = 0.003f
    const val ARC = 0.08f
    const val WIN_LIFT = 0.02f

    /**
     * Arrow handles (design spatialLayout.attachments: 6 x 4 cm, 1 cm off the block's outer edge). They lie flat on the maple
     * frame (top 6 mm over it, under the block top) instead of 2 cm over the block: at 0° tilt seen from ±30° the raised ones
     * hid the outer column's dimples (LayoutTest.nothingHidesADimple, deviation dv-handle).
     */
    const val HANDLE_LONG = 0.06f
    const val HANDLE_WIDE = 0.04f
    const val HANDLE_GAP = 0.01f
    const val HANDLE_Y = 0.003f

    /** Trays: 18 marbles each in 3 x 6 (design "3 × 6 排布"; a 12 cm round dish can't hold 18 Ø4 cm marbles, deviation dv-tray). */
    const val TRAY_W = 0.27f
    const val TRAY_D = 0.14f
    const val TRAY_H = 0.022f
    const val TRAY_X = 0.145f
    const val TRAY_GAP = 0.03f
    const val TRAY_PITCH = 0.042f
    /** Marble centre above the floor in a tray (resting on the 6 mm tray floor). */
    const val TRAY_MARBLE_Y = 0.006f + MARBLE_R

    val VOLUME = V3(0.70f, 0.74f, 0.90f)
    const val PIVOT_Z = 0.24f

    /**
     * Move plate (design AttachmentPanel 0.22 x 0.045 m; widened to 0.26 m: the longest English line "Move 36 · Hold a block and
     * push" overflowed 0.22 m at 17 sp, deviation dv-plate). dp at the measured ~0.00069 m / dp (royal-ur / abalone-push emulator).
     */
    const val PLATE_W_DP = 380
    const val PLATE_H_DP = 66
    const val DP_M = 0.00069f
    val PLATE_W: Float get() = PLATE_W_DP * DP_M
    val PLATE_H: Float get() = PLATE_H_DP * DP_M
    const val PLATE_GAP = 0.02f

    fun scale(size: Int) = size / 100f
    fun theta(tilt: Int) = Math.toRadians(tilt.toDouble()).toFloat()

    private fun rotX(v: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        return V3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    private fun rotY(v: V3, a: Float): V3 {
        val c = cos(a); val s = sin(a)
        return V3(v.x * c + v.z * s, v.y, -v.x * s + v.z * c)
    }

    val TILT_ORIGIN = V3(0f, 0f, PIVOT_Z)
    /** boardRoot in tiltRoot space: the frame top centre, half a frame behind the pivot. */
    fun boardOffset(s: Float) = V3(0f, FRAME_T * s, -FRAME / 2 * s)

    /** Board-local (100 % metres) -> scene. */
    fun toScene(local: V3, tilt: Int, size: Int): V3 {
        val s = scale(size)
        return TILT_ORIGIN + rotX(boardOffset(s) + local * s, theta(tilt))
    }

    /** Scene -> board-local direction (no translation, no scale): for drag vectors. */
    fun dirToLocal(scene: V3, tilt: Int): V3 = rotX(scene, -theta(tilt))

    /** The board normal in scene space. */
    fun normal(tilt: Int): V3 = rotX(V3(0f, 1f, 0f), theta(tilt))

    fun quadCentre(q: Int): V3 = V3(if (q % 2 == 0) -QUAD_OFF else QUAD_OFF, PROUD, if (q < 2) -QUAD_OFF else QUAD_OFF)

    /** Cell position relative to its block centre (block-local, unrotated): u right, v far (-Z). */
    fun cellInQuad(c: Int): V3 {
        val q = Pentago.quadOf(c)
        return V3((Pentago.col(c) - Pentago.quadCol(q)) * PITCH, 0f, -(Pentago.row(c) - Pentago.quadRow(q)) * PITCH)
    }

    /** Board-local top centre of cell c's dimple (on the block top). */
    fun cell(c: Int): V3 = quadCentre(Pentago.quadOf(c)) + cellInQuad(c)

    /**
     * Board-local centre of a marble in cell c of a block turned by [yaw] (radians, + = counter-clockwise from above) and
     * lifted by [lift]. A turned block carries its marbles round its own centre (design: the marbles ride the block).
     */
    fun marbleLocal(c: Int, yaw: Float = 0f, lift: Float = 0f): V3 =
        quadCentre(Pentago.quadOf(c)) + rotY(cellInQuad(c), yaw) + V3(0f, MARBLE_UP + lift, 0f)

    /** Yaw (radians) of a block turned by [dir] (CW = -90° about +Y: the far edge goes right). */
    fun yawOf(dir: Int, t: Float): Float = (if (dir == Pentago.CW) -1f else 1f) * (Math.PI.toFloat() / 2f) * t

    // ---------------------------------------------------------------- trays

    /** Scene centre of side's tray (black left, white right; design: 执黑的珠从左碟飞). */
    fun trayCentre(side: Int, size: Int): V3 {
        val s = scale(size)
        return V3((if (side == Pentago.BLACK) -TRAY_X else TRAY_X) * s, 0f, PIVOT_Z + (TRAY_GAP + TRAY_D / 2) * s)
    }

    /**
     * Scene centre of slot k (0..17) of side's tray. The tray shows n marbles in slots 0..n-1; the next marble to leave is slot
     * n-1: the order runs from the front outer corner to the back inner corner, so marbles leave from the corner nearest the board.
     */
    fun traySlot(side: Int, k: Int, size: Int): V3 {
        val s = scale(size)
        val row = k / 6            // 0 = front (user side)
        val colFromOuter = k % 6   // 0 = outer end
        val x = (2.5f - colFromOuter) * TRAY_PITCH * (if (side == Pentago.BLACK) -1f else 1f)
        val z = (1 - row) * TRAY_PITCH
        return trayCentre(side, size) + V3(x * s, TRAY_MARBLE_Y * s, z * s)
    }

    fun trayBox(side: Int, size: Int): Box3 {
        val s = scale(size); val c = trayCentre(side, size)
        return Box3(c - V3(TRAY_W / 2 * s, 0f, TRAY_D / 2 * s), c + V3(TRAY_W / 2 * s, TRAY_H * s, TRAY_D / 2 * s))
    }

    /** The marbles lying in a tray reach this high (they stand proud of its rim). */
    fun trayContentBox(side: Int, size: Int): Box3 {
        val s = scale(size); val b = trayBox(side, size)
        return Box3(b.min, V3(b.max.x, (TRAY_MARBLE_Y + MARBLE_R) * s, b.max.z))
    }

    // ---------------------------------------------------------------- flights, easing

    /** A parabola from a to b at t peaking [arc] above the chord (scene +Y). */
    fun arc(a: V3, b: V3, t: Float, arc: Float = ARC): V3 {
        val k = t.coerceIn(0f, 1f)
        return a.lerp(b, k) + V3(0f, 4f * arc * k * (1f - k), 0f)
    }

    /** Ease-out (decelerating) 0..1. */
    fun easeOut(t: Float): Float { val k = t.coerceIn(0f, 1f); return 1f - (1f - k) * (1f - k) * (1f - k) }

    /** Ease-in-out 0..1 (the 350 ms turn). */
    fun easeInOut(t: Float): Float { val k = t.coerceIn(0f, 1f); return k * k * (3f - 2f * k) }

    /**
     * Landing: the last 25 % of a flight settles with one small bounce (D51: lands, slows, a little hop). Returns the extra
     * height along the board normal (metres at 100 %).
     */
    fun settle(t: Float): Float = if (t < 0.75f) 0f else 0.003f * sin(((t - 0.75f) / 0.25f) * Math.PI.toFloat())

    // ---------------------------------------------------------------- handles

    /** Board-local centre of block q's handle for [dir]: on its outer side (Q1 / Q3 left, Q2 / Q4 right); ↺ far, ↻ near. */
    fun handle(q: Int, dir: Int): V3 {
        val c = quadCentre(q)
        val side = if (q % 2 == 0) -1f else 1f
        val x = c.x + side * (QUAD / 2 + HANDLE_GAP + HANDLE_WIDE / 2)
        val z = c.z + (if (dir == Pentago.CCW) -1f else 1f) * (HANDLE_LONG / 2 + 0.005f)
        return V3(x, HANDLE_Y, z)
    }

    fun handleBoxLocal(q: Int, dir: Int): Box3 = Box3.around(handle(q, dir), V3(HANDLE_WIDE, 0.006f, HANDLE_LONG))

    // ---------------------------------------------------------------- drag -> twist

    /**
     * Design input.twistDrag: the drag vector (scene metres, the template's volume axes) goes into board-local space; the
     * turn sense is the sign of (r × d).y with r = the press point from the block centre (+ = counter-clockwise from above),
     * the travel along the edge is |r × d| / |r|. A press on the block body or its centre dimple has no usable r: the outer
     * corner is taken (people push a block by its outer edge). Returns (dir, metres along the edge).
     */
    fun twistOfDrag(q: Int, pressCell: Int?, sceneDelta: V3, tilt: Int): Pair<Int, Float> {
        val d = dirToLocal(sceneDelta, tilt)
        var r = pressCell?.let { cellInQuad(it) } ?: V3(0f, 0f, 0f)
        if (sqrt(r.x * r.x + r.z * r.z) < 0.02f) { val c = quadCentre(q); r = V3(c.x, 0f, c.z) }
        val rl = sqrt(r.x * r.x + r.z * r.z)
        val cy = r.z * d.x - r.x * d.z
        val dir = if (cy > 0) Pentago.CCW else Pentago.CW
        return dir to abs(cy) / rl
    }

    // ---------------------------------------------------------------- plate + checks (D35)

    /** Scene centre of the move plate: upright above the far rim (1 cm inside the outer edge), bottom [PLATE_GAP] over it. */
    fun plateCentre(tilt: Int, size: Int): V3 {
        val foot = toScene(V3(0f, 0f, -(FRAME / 2 - 0.01f)), tilt, size)
        return foot + V3(0f, PLATE_GAP + PLATE_H / 2, 0f)
    }

    fun plateBox(tilt: Int, size: Int): Box3 = Box3.around(plateCentre(tilt, size), V3(PLATE_W, PLATE_H, 0.004f))

    /** Scene bounding box of a board-local box (8 corners transformed). */
    fun sceneBox(local: Box3, tilt: Int, size: Int): Box3 {
        val pts = ArrayList<V3>()
        for (x in listOf(local.min.x, local.max.x)) for (y in listOf(local.min.y, local.max.y)) for (z in listOf(local.min.z, local.max.z)) pts += toScene(V3(x, y, z), tilt, size)
        return Box3.of(pts)
    }

    fun frameBoxLocal(): Box3 = Box3(V3(-FRAME / 2, -FRAME_T, -FRAME / 2), V3(FRAME / 2, 0f, FRAME / 2))

    /** Block q's body (board-local), lifted by [lift] and swept round its centre (radius = the block's corner distance). */
    fun quadSweepLocal(q: Int, lift: Float): Box3 {
        val c = quadCentre(q); val r = QUAD / 2 * sqrt(2f)
        return Box3(V3(c.x - r, c.y - QUAD_T + lift, c.z - r), V3(c.x + r, c.y + lift + MARBLE_UP + MARBLE_R, c.z + r))
    }

    fun volume(): Box3 = Box3(V3(-VOLUME.x / 2, 0f, -VOLUME.z / 2), V3(VOLUME.x / 2, VOLUME.y, VOLUME.z / 2))

    /**
     * Eyes for the occlusion checks: the measured emulator reset (eye about level with the volume centre, 0.7 / 1.2 / 1.9 / 2.5 m
     * in front, azimuth 0 / ±30°) plus the design's sit low (0.35 m high, 0.45 m behind the near edge), sit and stand.
     */
    fun eyes(): Map<String, V3> {
        val out = LinkedHashMap<String, V3>()
        for (d in listOf(0.7f, 1.2f, 1.9f, 2.5f)) for (az in listOf(0, -30, 30)) {
            val a = Math.toRadians(az.toDouble())
            out["reset d=$d az=$az"] = V3((d * sin(a)).toFloat(), VOLUME.y / 2, (d * cos(a)).toFloat())
        }
        out["sit low"] = V3(0f, 0.35f, PIVOT_Z + 0.45f)
        out["sit"] = V3(0f, 0.50f, PIVOT_Z + 0.55f)
        out["stand"] = V3(0f, 0.90f, PIVOT_Z + 0.50f)
        return out
    }
}
