package io.embodit.pentagotwist.ui.board

import android.os.SystemClock
import android.util.Log
import com.pico.spatial.core.ecs.CollisionComponent
import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.InteractableComponent
import com.pico.spatial.core.ecs.ModelEntity
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import io.embodit.pentagotwist.domain.Fly
import io.embodit.pentagotwist.domain.GameFlow
import io.embodit.pentagotwist.domain.Layout
import io.embodit.pentagotwist.domain.Mode
import io.embodit.pentagotwist.domain.Outcome
import io.embodit.pentagotwist.domain.Pentago
import io.embodit.pentagotwist.domain.Phase
import io.embodit.pentagotwist.domain.PlayState
import io.embodit.pentagotwist.domain.Pos
import io.embodit.pentagotwist.domain.Settings
import io.embodit.pentagotwist.domain.Turn
import io.embodit.pentagotwist.domain.V3
import io.embodit.pentagotwist.platform.TAG
import io.embodit.pentagotwist.ui.board.input.GazeTargets
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** The GLB templates (tools/build_models.py). */
internal class Models(
    val frame: Entity, val quad: Entity, val rim: Entity, val marbles: List<Entity>, val tray: Entity,
    /** "l_cw", "l_ccw", "r_cw", "r_ccw" */
    val handles: Map<String, Entity>, val marker: Entity,
)

/**
 * Every entity of the board volume. Compose hands over [PlayState] + [Settings] through [apply]; everything that moves (the
 * flying marble, a turning block and the marbles riding it, rims, handles, rings, the win band) is placed in [onFrame], driven
 * by [BoardFrameSystem] (never by Compose). The tilt and the size are applied in the same frame to the board, the trays, the
 * marbles and the plate (D35 四补充).
 *
 * ```
 * root (volume centre)
 * └ sceneRoot (volume floor)
 *   ├ tiltRoot (near-bottom board edge, tipped by the tilt setting about X)
 *   │ └ boardRoot (frame top centre, scaled 100 / 120 %): frame.glb (no collider: 盘面不抢命中)
 *   │   ├ quadRoot:q × 4 (yaw / lift for a twist): "quad:q" body collider + quad.glb, 9 pad holders "cell:<i>" (collider +
 *   │   │   Interactable + system HoverEffect) each with its 5 mm marker, the amber rim
 *   │   └ arrow handles "handle:q:cw|ccw", rings, win bands, ghost marble
 *   ├ trays × 2, 36 marbles (GLB clone roots carry collider + Interactable + HoverEffect, named "cell:<i>" on the board)
 *   └ move plate (AttachmentPanel, upright above the far rim), key light
 * ```
 */
internal class BoardScene {
    val root: Entity = Entity().apply { setName("root") }
    private val sceneRoot = Entity().apply { setName("sceneRoot") }
    private val tiltRoot = Entity().apply { setName("tiltRoot") }
    private val boardRoot = Entity().apply { setName("boardRoot") }
    private val quadRoots = List(4) { q -> Entity().apply { setName("quadRoot:$q") } }

    private var res: SceneResources? = null
    private var fallback: List<Entity> = emptyList()
    private var built = false
    private var plate: Entity? = null

    private var state = PlayState()
    private var settings = Settings()
    private var regionHalfY = -1f

    private val marbles = List(2) { ArrayList<Entity>() }
    private val marbleNames = HashMap<Entity, String>()
    private val trays = ArrayList<Entity>()
    private val rims = ArrayList<Entity>()
    private val handles = HashMap<String, Entity>()
    private val handleNames = HashMap<Entity, String>()
    private val padHolders = HashMap<Int, Entity>()
    private val markers = HashMap<Int, Entity>()
    private var lastRing: ModelEntity? = null
    private var hintRing: ModelEntity? = null
    private var alertRing: ModelEntity? = null
    private var hoverRing: ModelEntity? = null
    /** [0] teal (yours / a seat in 2P), [1] amber (the AI's five). */
    private val winRings = List(2) { ArrayList<ModelEntity>() }
    private val beams = List(2) { ArrayList<ModelEntity>() }
    private var ghost = List<ModelEntity?>(2) { null }

    @Volatile private var hover: String? = null
    private var hoverAt = 0L
    private var clock = 0f
    private var resultAt = 0L
    private var lastPhase: Phase? = null

    init {
        root.addChild(sceneRoot)
        sceneRoot.addChild(tiltRoot)
        tiltRoot.addChild(boardRoot)
        quadRoots.forEach { boardRoot.addChild(it) }
        place(tiltRoot, Layout.TILT_ORIGIN)
    }

    val ready: Boolean get() = built

    /** D50 hover: the target name under a resting pointer; light feedback only (kept 1.5 s after the last event, 围墙棋 QA). */
    fun setHover(name: String?) {
        hoverAt = SystemClock.uptimeMillis()
        if (name == hover) return
        hover = name
        if (name != null) Log.i(TAG, "HOVER $name")
    }

    fun setRegion(halfY: Float) {
        if (kotlin.math.abs(halfY - regionHalfY) < 1e-4f) return
        regionHalfY = halfY
        sceneRoot.transform().setPosition(Vector3(0f, -halfY, 0f))
        Log.i(TAG, "SCENE region halfY=$halfY")
    }

    private fun place(e: Entity, v: V3) = e.transform().setPosition(Vector3(v.x, v.y, v.z))

    /** S00 stand-in: a plain slab with four darker blocks where the board will be, until the models are in. */
    fun showFallback(slab: MeshResource, block: MeshResource, wood: Material, dark: Material) {
        if (built || fallback.isNotEmpty()) return
        val list = ArrayList<Entity>()
        list += ModelEntity(slab, wood).apply { setName("fallbackBoard"); place(this, V3(0f, -Layout.FRAME_T / 2, 0f)) }
        for (q in 0..3) list += ModelEntity(block, dark).apply { setName("fallbackBlock"); place(this, Layout.quadCentre(q) + V3(0f, -Layout.QUAD_T / 2, 0f)) }
        fallback = list
        fallback.forEach { boardRoot.addChild(it) }
        Log.i(TAG, "SCENE fallback shown S00")
    }

    private fun clone(e: Entity): Entity = e.clone(Entity.CloneOptions(recursive = true, shouldShareMaterialInstance = true))!!.also { it.enabled = true }

    private fun ring(mesh: MeshResource, m: Material, name: String): ModelEntity =
        ModelEntity(mesh, m).apply { setName(name); enabled = false; boardRoot.addChild(this) }

    fun install(r: SceneResources, models: Models, plateEntity: Entity?) {
        if (built) { r.close(); return }
        res = r
        val t0 = SystemClock.elapsedRealtime()
        boardRoot.addChild(clone(models.frame).apply { setName("frame") })
        for (q in 0..3) {
            val qr = quadRoots[q]
            // the block body: a collider holder (centred on the 2 cm body) with the model as its child; no HoverEffect
            val body = Entity().apply { setName(BoardNames.quad(q)) }
            body.components.set(CollisionComponent(collisionShape = listOf(r.quadShape), physicsMaterial = r.physics))
            body.components.set(InteractableComponent())
            place(body, V3(0f, -Layout.QUAD_T / 2, 0f))
            body.addChild(clone(models.quad).apply { setName("quadModel"); place(this, V3(0f, Layout.QUAD_T / 2, 0f)) })
            qr.addChild(body)
            // nine pad holders: collider + Interactable + system HoverEffect, the 5 mm marker is the visible child (template)
            for (c in Pentago.QUAD_CELLS[q]) {
                val holder = Entity().apply { setName(BoardNames.cell(c)) }
                place(holder, Layout.cellInQuad(c))
                val marker = clone(models.marker).apply { setName("mk:" + BoardNames.cell(c)); place(this, V3(0f, Layout.MARKER_Y, 0f)) }
                GazeTargets.install(holder, r.padShape, r.physics, marker)
                qr.addChild(holder)
                padHolders[c] = holder; markers[c] = marker
            }
            rims += clone(models.rim).apply { setName("rim:$q"); enabled = false; qr.addChild(this) }
        }
        for (colour in 0..1) repeat(Pentago.PER_SIDE) {
            val e = clone(models.marbles[colour]).apply { setName(BoardNames.TRAY) }
            GazeTargets.install(e, r.marbleShape, r.physics)
            sceneRoot.addChild(e)
            marbles[colour] += e
        }
        for (side in 0..1) trays += clone(models.tray).apply { setName(BoardNames.TRAY); sceneRoot.addChild(this) }
        for ((k, tpl) in models.handles) {
            val e = clone(tpl).apply { setName("handle:x"); enabled = false }
            GazeTargets.install(e, r.handleShape, r.physics)
            boardRoot.addChild(e)
            handles[k] = e
        }
        lastRing = ring(r.ringThin, r.last, "lastRing")
        hintRing = ring(r.ring, r.teal, "hintRing")
        alertRing = ring(r.ring, r.red, "alertRing")
        hoverRing = ring(r.ringThin, r.hover, "hoverRing")
        for ((k, m) in listOf(r.teal, r.amber).withIndex()) {
            repeat(12) { i -> winRings[k] += ring(r.ring, m, "winRing:$k:$i") }
            repeat(2) { i -> beams[k] += ring(r.beam, m, "winBeam:$k:$i") }
        }
        ghost = listOf(ring(r.ghostMesh, r.ghostBlack, "ghost:b"), ring(r.ghostMesh, r.ghostWhite, "ghost:w"))
        plate = plateEntity?.also { sceneRoot.addChild(it) }
        buildLight()

        fallback.forEach { it.removeFromParent() }
        fallback = emptyList()
        built = true
        Log.i(TAG, "SCENE built in ${SystemClock.elapsedRealtime() - t0} ms tilt=${settings.tilt} size=${settings.size} plate=${Layout.plateCentre(settings.tilt, settings.size)}")
        onFrame(0f)
    }

    private fun buildLight() {
        runCatching {
            val light = Entity().apply { setName("keyLight") }
            // B24: a moderate warm key light from the front right, above (no blown-out pearl marbles)
            light.components.set(DirectionalLightComponent(Color4(1f, 0.95f, 0.88f, 1f), 0.85f, true))
            val q = Quat(Vector3(0f, 1f, 0f), (30 * PI / 180).toFloat()).times(Quat(Vector3(1f, 0f, 0f), (-55 * PI / 180).toFloat()))
            light.transform().setQuaternion(q)
            root.addChild(light)
        }.onFailure { Log.w(TAG, "LIGHT unavailable: $it") }
    }

    fun apply(next: PlayState, s: Settings) {
        state = next
        settings = s
        if (!built) return
        onFrame(0f)
    }

    // ---------------------------------------------------------------- per frame

    /** The block turning now: (quad, yaw radians, lift metres at 100 %). */
    private data class Turning(val quad: Int, val yaw: Float, val lift: Float)

    private data class Flight(val cell: Int, val side: Int, val toDish: Boolean, val t: Float)

    fun onFrame(dt: Float) {
        clock += dt
        if (!built) return
        val r = res ?: return
        val st = state
        val now = SystemClock.uptimeMillis()
        val tilt = settings.tilt
        val size = settings.size
        val s = Layout.scale(size)
        val reduced = settings.reducedMotion
        if (st.phase != lastPhase) { if (st.phase == Phase.RESULT) resultAt = now; lastPhase = st.phase }

        // ---------------- tilt / size: board, trays, plate in the same frame
        tiltRoot.transform().setQuaternion(Quat(Vector3(1f, 0f, 0f), Layout.theta(tilt)))
        place(boardRoot, Layout.boardOffset(s))
        boardRoot.transform().setScaleVector(Vector3(s, s, s))
        plate?.let { place(it, Layout.plateCentre(tilt, size)) }
        for (side in 0..1) trays.getOrNull(side)?.let { t -> place(t, Layout.trayCentre(side, size)); t.transform().setScaleVector(Vector3(s, s, s)) }

        // ---------------- the animation leg playing now
        var base: Pos = st.board
        var turning: Turning? = null
        var flight: Flight? = null
        st.anim?.let { a ->
            var e = now - a.startedAt
            st.freezeMs?.let { e = min(e, it) }
            var acc = 0L
            val leg = a.steps.firstOrNull { acc += it.durMs; e < acc } ?: a.steps.last()
            val legStart = acc - leg.durMs
            val t = ((e - legStart).toFloat() / leg.durMs).coerceIn(0f, 1f)
            base = leg.before
            when (val step = leg.step) {
                is Fly -> flight = Flight(step.cell, step.side, step.toDish, t)
                is Turn -> turning = if (step.lift) {
                    val ms = t * GameFlow.TWIST_MS
                    val lift = when {
                        ms < GameFlow.LIFT_MS -> Layout.TWIST_LIFT * Layout.easeOut(ms / GameFlow.LIFT_MS)
                        ms < GameFlow.LIFT_MS + GameFlow.TURN_MS -> Layout.TWIST_LIFT
                        else -> Layout.TWIST_LIFT * (1f - Layout.easeInOut((ms - GameFlow.LIFT_MS - GameFlow.TURN_MS) / GameFlow.DROP_MS))
                    }
                    val k = Layout.easeOut(((ms - GameFlow.LIFT_MS) / GameFlow.TURN_MS).coerceIn(0f, 1f))
                    Turning(step.quad, Layout.yawOf(step.dir, k), lift)
                } else Turning(step.quad, Layout.yawOf(step.dir, Layout.easeInOut(t)), 0f)
            }
        }
        val tq = turning
        for (q in 0..3) {
            val yaw = if (tq?.quad == q) tq.yaw else 0f
            val lift = if (tq?.quad == q) tq.lift else 0f
            place(quadRoots[q], Layout.quadCentre(q) + V3(0f, lift, 0f))
            quadRoots[q].transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), yaw))
        }

        // ---------------- marbles: on the board (riding a turning block), the flying one, the rest in the trays
        val win = st.winRuns.takeIf { st.phase == Phase.RESULT }.orEmpty()
        val winLift = HashMap<Int, Float>()
        if (win.isNotEmpty() && !reduced && win.size == 1) {
            win[0].second.forEachIndexed { i, c ->
                val k = ((now - resultAt - i * 80L) / 160f).coerceIn(0f, 1f)
                winLift[c] = Layout.WIN_LIFT * Layout.easeOut(k)
            }
        }
        val used = intArrayOf(0, 0)
        fun put(colour: Int, at: V3, name: String) {
            val ent = marbles[colour].getOrNull(used[colour]++) ?: return
            ent.enabled = true
            place(ent, at)
            ent.transform().setScaleVector(Vector3(s, s, s))
            if (marbleNames[ent] != name) { ent.setName(name); marbleNames[ent] = name }
        }
        for (c in 0 until Pentago.N) {
            val colour = base.owner(c)
            if (colour < 0) continue
            val q = Pentago.quadOf(c)
            val yaw = if (tq?.quad == q) tq.yaw else 0f
            val lift = (if (tq?.quad == q) tq.lift else 0f) + (winLift[c] ?: 0f)
            put(colour, Layout.toScene(Layout.marbleLocal(c, yaw, lift), tilt, size), BoardNames.cell(c))
        }
        val fl = flight
        val onBoard = intArrayOf(base.count(0), base.count(1))
        if (fl != null) {
            val slot = Layout.traySlot(fl.side, Pentago.PER_SIDE - onBoard[fl.side] - 1, size)
            val pit = Layout.toScene(Layout.marbleLocal(fl.cell), tilt, size)
            val t = if (reduced) 1f else fl.t
            val k = Layout.easeOut(t)
            val at = if (fl.toDish) Layout.arc(pit, slot, k, Layout.ARC * s * 0.6f)
            else Layout.arc(slot, pit, k, Layout.ARC * s) + Layout.normal(tilt) * (Layout.settle(t) * s)
            put(fl.side, at, BoardNames.FLYING)
        }
        for (colour in 0..1) {
            val inTray = Pentago.PER_SIDE - onBoard[colour] - (if (fl?.side == colour) 1 else 0)
            for (k in 0 until inTray) put(colour, Layout.traySlot(colour, k, size), BoardNames.TRAY)
            for (i in used[colour] until marbles[colour].size) marbles[colour][i].enabled = false
        }

        // ---------------- rims (S07: the four blocks glow amber; held = only that one; picked = only that one)
        val humanTwist = st.phase == Phase.TWIST && st.humansTurn && !st.paused
        val lit: Set<Int> = when {
            tq != null && st.phase == Phase.TWISTING -> setOf(tq.quad)
            humanTwist && st.held != null -> setOf(st.held)
            humanTwist && st.picked != null -> setOf(st.picked)
            humanTwist -> setOf(0, 1, 2, 3)
            st.phase == Phase.TWIST && !st.humansTurn -> emptySet()
            else -> emptySet()
        }
        rims.forEachIndexed { q, rim -> rim.enabled = q in lit }

        // ---------------- arrow handles (S07: the picked block; with the hint on, the recommended block before a pick)
        val hint = st.hint?.takeIf { st.hintOn && st.humansTurn }
        val handleQuad = if (humanTwist) st.picked ?: hint?.quad?.takeIf { it >= 0 && st.held == null } else null
        for ((key, e) in handles) {
            val side = if (key.startsWith("l")) 0 else 1
            val dir = if (key.endsWith("_cw")) Pentago.CW else Pentago.CCW
            val q = handleQuad?.takeIf { it % 2 == side }
            if (q == null) { e.enabled = false; continue }
            e.enabled = true
            place(e, Layout.handle(q, dir))
            val pulse = hint != null && hint.quad == q && hint.dir == dir && !reduced
            val k = if (pulse) 1f + 0.10f * sin(clock * 6f) else 1f
            e.transform().setScaleVector(Vector3(k, 1f, k))
            val n = BoardNames.handle(q, dir)
            if (handleNames[e] != n) { e.setName(n); handleNames[e] = n }
        }

        // ---------------- rings
        val calm = st.anim == null
        fun ringAt(ring: ModelEntity?, c: Int?, y: Float = 0.0012f) {
            ring ?: return
            if (c == null) { ring.enabled = false; return }
            place(ring, Layout.cell(c) + V3(0f, y, 0f)); ring.enabled = true
        }
        // last move: under the marble placed last (it rode its twist), pulsing once in S07 on the marble just placed
        val lastCell: Int? = when {
            st.pending != null -> st.pending
            st.phase == Phase.RESULT -> null
            else -> st.lastMove?.let { m -> if (m.quad >= 0 && m.quad == Pentago.quadOf(m.cell)) Pentago.rotateCell(m.cell, m.dir) else m.cell }
        }
        ringAt(lastRing, lastCell.takeIf { calm }, 0.0010f)
        lastRing?.takeIf { it.enabled && st.pending != null && !reduced }?.let { it.transform().setScaleVector(Vector3(1f + 0.06f * sin(clock * 5f), 1f, 1f + 0.06f * sin(clock * 5f))) }
            ?: lastRing?.transform()?.setScaleVector(Vector3(1f, 1f, 1f))
        val hintCell = hint?.cell?.takeIf { st.phase == Phase.PLACE && calm && (reduced || (now / 400) % 2 == 0L) }
        ringAt(hintRing, hintCell, 0.0016f)
        val al = st.alert
        val flashing = al != null && (st.freezeMs != null || (now - al.at < GameFlow.FLASH_MS && ((now - al.at) / 75) % 2 == 0L))
        ringAt(alertRing, if (flashing) al?.cell ?: al?.quad?.let { Pentago.at(Pentago.quadCol(it), Pentago.quadRow(it)) } else null, 0.0022f)

        // ---------------- win: rings under the line's marbles (teal = yours / a seat in 2P, amber = the AI's), the band
        val cfg = st.config
        val wi = intArrayOf(0, 0); val bi = intArrayOf(0, 0)
        win.forEach { (side, cells) ->
            val k = if (cfg != null && cfg.mode == Mode.AI && side != cfg.humanSide && st.outcome != Outcome.DRAW_DOUBLE) 1 else 0
            cells.forEachIndexed { i, c ->
                val ring = winRings[k].getOrNull(wi[k]++) ?: return@forEachIndexed
                val shown = reduced || win.size > 1 || now - resultAt >= i * 80L
                if (!shown) { ring.enabled = false; return@forEachIndexed }
                ringAt(ring, c, 0.0014f + (winLift[c] ?: 0f))
            }
            beams[k].getOrNull(bi[k]++)?.let { b ->
                val a = Layout.cell(cells.first()); val z = Layout.cell(cells.last())
                val dx = z.x - a.x; val dz = z.z - a.z
                val len = sqrt(dx * dx + dz * dz) + 0.04f
                place(b, a.lerp(z, 0.5f) + V3(0f, 0.0015f + (if (win.size == 1 && !reduced) Layout.WIN_LIFT * 0.5f else 0f), 0f))
                b.transform().setQuaternion(Quat(Vector3(0f, 1f, 0f), atan2(-dz, dx)).times(Quat(Vector3(1f, 0f, 0f), (-PI / 2).toFloat())))
                b.transform().setScaleVector(Vector3(len, 1f, 1f))
                b.enabled = reduced || win.size > 1 || now - resultAt >= cells.size * 80L
            }
        }
        for (k in 0..1) {
            for (i in wi[k] until winRings[k].size) winRings[k][i].enabled = false
            for (i in bi[k] until beams[k].size) beams[k][i].enabled = false
        }

        // ---------------- hover (D50): a ghost marble on an empty dimple in S03, a faint ring elsewhere; never changes state
        val hv = hover?.takeIf { now - hoverAt < 1500 }?.let { BoardNames.resolveName(it) } as? BoardNames.CellHit
        val ghostCell = hv?.cell?.takeIf { st.phase == Phase.PLACE && st.humansTurn && st.board.empty(it) && calm }
        ghost.forEachIndexed { side, g ->
            g ?: return@forEachIndexed
            if (ghostCell != null && side == st.side) { place(g, Layout.marbleLocal(ghostCell)); g.enabled = true } else g.enabled = false
        }
        ringAt(hoverRing, hv?.cell?.takeIf { ghostCell == null && calm && st.phase in setOf(Phase.PLACE, Phase.TWIST) }, 0.0008f)
    }

    /**
     * The real entities a pinch on cell c can hit (build.md 命中回放): the pad holder, its marker, and when a marble rests
     * there the marble root and its deepest mesh node. Deepest first so the replay exercises the parent walk.
     */
    fun replayEntities(c: Int): List<Entity> {
        val out = ArrayList<Entity>()
        val name = BoardNames.cell(c)
        marbles.flatten().firstOrNull { it.enabled && marbleNames[it] == name }?.let { m ->
            var deep = m
            while (deep.hasChild()) deep = deep.getChildren().first()
            if (deep !== m) out += deep
            out += m
        }
        markers[c]?.let { mk -> var deep = mk; while (deep.hasChild()) deep = deep.getChildren().first(); out += deep }
        padHolders[c]?.let { out += it }
        return out
    }

    fun evidence(): String {
        val t = settings.tilt; val z = settings.size
        return "tilt=$t size=$z plate=${Layout.plateCentre(t, z)} plateW=${Layout.PLATE_W} plateH=${Layout.PLATE_H} " +
            "near=${Layout.toScene(V3(0f, 0f, Layout.FRAME / 2), t, z)} far=${Layout.toScene(V3(0f, 0f, -Layout.FRAME / 2), t, z)} " +
            "a1=${Layout.toScene(Layout.marbleLocal(0), t, z)} f6=${Layout.toScene(Layout.marbleLocal(35), t, z)} trayB=${Layout.trayCentre(0, z)} halfY=$regionHalfY"
    }

    fun dispose() {
        res?.close()
        res = null
    }
}
