package io.embodit.pentagotwist.ui.board

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.SceneUpdateContext
import com.pico.spatial.core.ecs.System
import com.pico.spatial.core.ecs.ViewCoordinateSpace
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicallyBasedMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.Text
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.registerSystem
import com.pico.spatial.ui.foundation.dsl.unregisterSystem
import com.pico.spatial.ui.geometry.Offset3D
import com.pico.spatial.ui.platform.containers.LocalSpatialNavigator
import io.embodit.pentagotwist.R
import io.embodit.pentagotwist.domain.Pentago
import io.embodit.pentagotwist.domain.Layout
import io.embodit.pentagotwist.domain.Phase
import io.embodit.pentagotwist.domain.PlayState
import io.embodit.pentagotwist.platform.Graph
import io.embodit.pentagotwist.platform.TAG
import io.embodit.pentagotwist.ui.board.input.BoardInputConfig
import io.embodit.pentagotwist.ui.board.input.BoardInputListener
import io.embodit.pentagotwist.ui.board.input.InputSource
import io.embodit.pentagotwist.ui.board.input.Offset3M
import io.embodit.pentagotwist.ui.board.input.TargetResolver
import io.embodit.pentagotwist.ui.board.input.BoardInputLog
import io.embodit.pentagotwist.domain.V3
import io.embodit.pentagotwist.ui.board.input.boardInput
import io.embodit.pentagotwist.ui.statusText
import io.embodit.pentagotwist.ui.theme.PtColors
import io.embodit.pentagotwist.ui.theme.localized
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.math.abs

/** Runs the board's per-frame motion inside the ECS (never in Compose) and feeds the window's level gate. */
class BoardFrameSystem : System() {
    override fun update(context: SceneUpdateContext) {
        SceneHolder.scene?.onFrame(context.deltaTime)
        SceneHolder.graph?.window?.stepFrame()
    }
}

internal object SceneHolder {
    @Volatile var scene: BoardScene? = null
    @Volatile var graph: Graph? = null
    @Volatile var listener: BoardListener? = null
    @Volatile var targets: BoardTargets? = null

    /** Debug `replay <cell> [src]`: resolve every real entity of that cell up its parents, then tap the deepest through the listener. */
    fun replay(c: Int, src: String) {
        val sc = scene ?: return; val l = listener ?: return; val tg = targets ?: return
        val source = InputSource.entries.firstOrNull { it.label == src } ?: InputSource.HAND_RAY
        val ents = sc.replayEntities(c)
        var first: BoardNames.Hit? = null
        for (e in ents) {
            val (hit, path) = io.embodit.pentagotwist.ui.board.input.resolveUp(e, tg)
            if (first == null) first = hit
            BoardInputLog.write(TAG, "REPLAY src=${source.label} path=$path -> square=${BoardNames.squareText(hit)}")
        }
        l.onTap(first, source, null)
    }
}

private const val PLATE = "counterPlate"

/**
 * The board volume (default container). Opens the control window beside it through the level gate, shows the board,
 * and routes every pinch / trigger on a collider (marble, cell, pad, board) to [GameFlow] through [route].
 */
@Composable
fun BoardScreen() {
    val context = LocalContext.current
    val graph = remember(context) { Graph.from(context) }
    val state by graph.flow.state.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val navigator = LocalSpatialNavigator.current
    val scene = remember { BoardScene() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    DisposableEffect(navigator) {
        graph.window.attach(navigator)
        graph.window.request("start")
        onDispose { graph.window.detach() }
    }

    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            when (e) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { Log.i(TAG, "LIFECYCLE background"); graph.flow.onBackground() }
                androidx.lifecycle.Lifecycle.Event.ON_START -> { Log.i(TAG, "LIFECYCLE foreground"); graph.flow.onForeground() }
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    DisposableEffect(scene) {
        SceneHolder.scene = scene
        SceneHolder.graph = graph
        registerSystem<BoardFrameSystem>()
        onDispose {
            unregisterSystem<BoardFrameSystem>()
            if (SceneHolder.scene === scene) SceneHolder.scene = null
            scene.dispose()
        }
    }

    SpatialView(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { viewSize = it }
            // D50: the factory board-input template (taps + short drags the system reports as drags, targets resolved up
            // the parent chain) for hand ray, controller ray and eye-hand alike.
            .boardInput(remember(scene) { BoardTargets(scene).also { SceneHolder.targets = it } }, remember(graph) { BoardListener(graph, scene).also { SceneHolder.listener = it } }, BoardInputConfig(logTag = TAG)),
        attachments = {
            AttachmentPanel(PLATE) { PlateRoot(graph) }
        },
        initial = { content, attachments ->
            content.addEntity(scene.root)
            val quick = withContext(NonCancellable + Dispatchers.IO) {
                runCatching {
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底 素色方板"), removed when frame.glb is in
                    val slab = MeshResource.createBox(Vector3(Layout.FRAME, Layout.FRAME_T, Layout.FRAME), cornerRadius = 0.01f)
                    // gate:allow geometry S00 loading stand-in (design lifecycle "首帧兜底 四个深色方块"), removed when quad.glb is in
                    val block = MeshResource.createBox(Vector3(Layout.QUAD, Layout.QUAD_T, Layout.QUAD), cornerRadius = 0.02f)
                    val wood = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.85f, 0.72f, 0.54f, 1f)); setRoughness(0.8f) }
                    val dark = PhysicallyBasedMaterial.create().apply { setBaseColor(Color4(0.43f, 0.29f, 0.17f, 1f)); setRoughness(0.8f) }
                    listOf(slab, block, wood, dark)
                }.getOrNull()
            }
            quick?.let { scene.showFallback(it[0] as MeshResource, it[1] as MeshResource, it[2] as PhysicallyBasedMaterial, it[3] as PhysicallyBasedMaterial) }
            val t0 = android.os.SystemClock.elapsedRealtime()
            suspend fun load(n: String): Entity = Entity.loadSuspend("asset://models/$n.glb").also { it.enabled = false; content.addEntity(it) }
            val models = runCatching {
                val handles = LinkedHashMap<String, Entity>()
                for (k in listOf("l_cw", "l_ccw", "r_cw", "r_ccw")) handles[k] = load("handle_$k")
                Models(load("frame"), load("quad"), load("rim"), listOf(load("marble_black"), load("marble_white")), load("tray"), handles, load("marker"))
            }.onFailure { Log.e(TAG, "MODELS failed", it) }.getOrNull()
            val resources = withContext(NonCancellable + Dispatchers.Default) { SceneResources.create() }
            Log.i(TAG, "MODELS loaded=${models != null} in ${android.os.SystemClock.elapsedRealtime() - t0} ms")
            val prefs = context.getSharedPreferences("debug", android.content.Context.MODE_PRIVATE)
            if (prefs.getBoolean("s00hold", false)) { Log.i(TAG, "S00 hold 10 s"); kotlinx.coroutines.delay(10_000) }
            if (models != null) scene.install(resources, models, attachments.entity(PLATE))
        },
        update = { content, _ ->
            if (viewSize != IntSize.Zero) {
                val corner = content.convertPosition(Vector3.ZERO, ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
                scene.setRegion(abs(corner.y))
            }
            scene.apply(state, settings)
        },
    )
}

/** Hit entity (or an ancestor) -> what the game knows (BoardNames.resolveName: cells, blocks, handles; null = keep walking up). */
internal class BoardTargets(@Suppress("unused") private val scene: BoardScene) : TargetResolver<BoardNames.Hit> {
    override fun resolve(entity: Entity): BoardNames.Hit? = runCatching { entity.getName() }.getOrNull()?.let { BoardNames.resolveName(it) }
}

/**
 * Every resolved tap goes through [route] (the same calls the panel keys and the debug broadcast make). A press that moves
 * past 2.5 cm on a block (its pad, marble or body) is a twist drag (design input.twistDrag): on release the travel along
 * the block's edge (>= 5 cm) turns it one step, the sense from (r × d).
 */
internal class BoardListener(private val graph: Graph, private val scene: BoardScene) : BoardInputListener<BoardNames.Hit> {
    private var dragQuad: Int? = null
    private var dragCell: Int? = null
    private var dragTotal = Offset3M(0f, 0f, 0f)
    private var dragSrc = InputSource.UNKNOWN

    override fun onTap(target: BoardNames.Hit?, source: InputSource, position: Offset3D?) {
        BoardInputLog.write(TAG, "INPUT tap src=${source.label} hit=$target -> square=${BoardNames.squareText(target)} phase=${graph.flow.state.value.phase.id}")
        route(graph, target)
    }

    override fun onDragStart(target: BoardNames.Hit, source: InputSource): Boolean {
        val q = BoardNames.quadOf(target) ?: return false
        dragQuad = q; dragCell = BoardNames.cellOf(target); dragTotal = Offset3M(0f, 0f, 0f); dragSrc = source
        BoardInputLog.write(TAG, "INPUT drag start src=${source.label} hit=$target -> square=${BoardNames.squareText(target)}")
        return graph.flow.dragStart(q)
    }

    override fun onDragMove(totalM: Offset3M) { dragTotal = totalM }

    override fun onDragEnd() {
        val q = dragQuad ?: return
        val (dir, along) = Layout.twistOfDrag(q, dragCell, V3(dragTotal.x, dragTotal.y, dragTotal.z), graph.settings.value.tilt)
        BoardInputLog.write(TAG, "INPUT drag end src=${dragSrc.label} quad=${Pentago.quadName(q)} total=(${"%.3f".format(dragTotal.x)}, ${"%.3f".format(dragTotal.y)}, ${"%.3f".format(dragTotal.z)}) along=${"%.3f".format(along)} dir=$dir")
        graph.flow.dragEnd(q, dir, along)
        dragQuad = null
    }

    override fun onDragCancel() { dragQuad = null; graph.flow.dragCancel() }

    /** D50 hover: a ghost marble / a faint ring where the pointer rests (never changes state). */
    override fun onHover(target: BoardNames.Hit?, source: InputSource) = scene.setHover(when (target) {
        is BoardNames.CellHit -> BoardNames.cell(target.cell)
        is BoardNames.QuadHit -> BoardNames.quad(target.quad)
        else -> null
    })
}

/** Hit -> the one flow call (the same calls the debug broadcast makes). */
internal fun route(graph: Graph, hit: BoardNames.Hit?) = BoardNames.dispatch(graph.flow, hit)

/** The plate is its own composition: it reads state + language itself so a language switch always reaches it. */
@Composable
private fun PlateRoot(graph: Graph) {
    val base = LocalContext.current
    val st by graph.flow.state.collectAsStateWithLifecycle()
    val s by graph.settings.settings.collectAsStateWithLifecycle()
    key(s.language) {
        CompositionLocalProvider(LocalContext provides base.localized(s.language)) { MovePlate(st, graph) }
    }
}

@Composable
private fun plateText(text: String, tag: String, size: Int, weight: FontWeight = FontWeight.SemiBold, color: Color = PtColors.Ink) {
    Text(text, color = color, fontSize = size.sp, fontWeight = weight, maxLines = 1, softWrap = false, textAlign = TextAlign.Center,
        onTextLayout = { if (it.hasVisualOverflow) Log.w(TAG, "OVERFLOW $tag \"$text\"") })
}

/** A small marble dot; [lit] = a ring of the amber colour around it (whose turn). */
@Composable
internal fun MarbleDot(side: Int, size: Int, lit: Boolean = false) {
    Box(
        Modifier.size((size + if (lit) 6 else 0).dp).clip(CircleShape).background(if (lit) PtColors.Tertiary else Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(size.dp).clip(CircleShape).background(if (side == Pentago.BLACK) PtColors.MarbleBlack else PtColors.MarbleWhite).border(1.dp, PtColors.Ink, CircleShape))
    }
}

/**
 * Move plate (design spatialLayout.attachments, 0.22 x 0.045 m, one line): "第 9 手 · 黑：转盘中" with a dot of the side shown
 * (D38 / D45: the same status as the window, short form). No buttons except "open panel" after the system ×.
 */
@Composable
private fun MovePlate(st: PlayState, graph: Graph) {
    val window = graph.window
    Box(
        Modifier.size(Layout.PLATE_W_DP.dp, Layout.PLATE_H_DP.dp).clip(RoundedCornerShape(14.dp)).background(PtColors.TagFace),
        contentAlignment = Alignment.Center,
    ) {
        when {
            window.levelHint -> plateText(stringResource(R.string.level_hint), "plate/level", 18)
            window.panelState == "ON_DESTROY" -> Button(onClick = { window.request("plate") }) {
                // design-style: inherited-content-color Button
                Text(stringResource(R.string.open_panel), fontSize = 16.sp)
            }
            st.config == null || st.phase == Phase.SETUP || st.phase == Phase.LOADING -> plateText(stringResource(R.string.plate_idle), "plate/idle", 19)
            else -> Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                MarbleDot(st.shownSide, 16, lit = st.phase != Phase.RESULT)
                Spacer(Modifier.width(8.dp))
                val line = stringResource(R.string.plate_move, st.moveNo) + " · " + statusText(st, short = true)
                plateText(line, "plate/status", 17, FontWeight.SemiBold, if (st.alert != null) PtColors.ErrorText else PtColors.Ink)
            }
        }
    }
}
