package io.embodit.pentagotwist.ui.board

import androidx.compose.ui.graphics.Color
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.BlendingMode
import com.pico.spatial.core.ecs.resource.Material
import com.pico.spatial.core.ecs.resource.MaterialCullingMode
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.PhysicsMaterialResource
import com.pico.spatial.core.ecs.resource.Resource
import com.pico.spatial.core.ecs.resource.ShapeResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.Vector3
import io.embodit.pentagotwist.domain.Layout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

internal fun Entity.transform(): TransformComponent = components[TransformComponent::class.java]!!

internal fun Color.c4(alpha: Float = this.alpha): Color4 = Color4(red, green, blue, alpha)

/**
 * The abstract marks of the volume (rings, the win band, the ghost marble) and the invisible collider shapes. The visible
 * models (frame, blocks, marbles, trays, arrow handles, rims, gaze markers) are GLBs built by tools/build_models.py.
 * Creating a resource waits on the renderer, so everything is created concurrently on Dispatchers.IO.
 */
internal class SceneResources private constructor() {
    private val all = ArrayList<Resource>()

    lateinit var ring: MeshResource; private set
    lateinit var ringThin: MeshResource; private set
    lateinit var beam: MeshResource; private set
    lateinit var ghostMesh: MeshResource; private set

    lateinit var teal: Material; private set
    lateinit var amber: Material; private set
    lateinit var red: Material; private set
    lateinit var last: Material; private set
    lateinit var hover: Material; private set
    lateinit var ghostBlack: Material; private set
    lateinit var ghostWhite: Material; private set

    lateinit var padShape: ShapeResource; private set
    lateinit var marbleShape: ShapeResource; private set
    lateinit var quadShape: ShapeResource; private set
    lateinit var handleShape: ShapeResource; private set
    lateinit var physics: PhysicsMaterialResource; private set

    fun close() {
        synchronized(all) { all.forEach { runCatching { it.close() } }; all.clear() }
    }

    companion object {
        private fun unlit(color: Color, opacity: Float = 1f): UnlitMaterial =
            UnlitMaterial.create(if (opacity < 1f) BlendingMode.TRANSPARENT else BlendingMode.OPAQUE).apply {
                setBaseColor(color.c4(1f))
                if (opacity < 1f) setOpacity(opacity)
                setCullingMode(MaterialCullingMode.NONE)
                setApplyToneMapping(false)
            }

        suspend fun create(): SceneResources = coroutineScope {
            val r = SceneResources()
            fun <T : Resource> T.keep(): T { toGlobal(); synchronized(r.all) { r.all += this }; return this }
            val io = Dispatchers.IO
            listOf(
                // rings lie in the board plane round a marble's foot (marble R 20 mm, dimple mouth R 22 mm): Ø 4.6 cm
                async(io) { r.ring = MeshResource.createTorus(outerRingRadius = 0.0245f, innerRingRadius = 0.0205f).keep() },
                async(io) { r.ringThin = MeshResource.createTorus(outerRingRadius = 0.0240f, innerRingRadius = 0.0222f).keep() },
                async(io) { r.beam = MeshResource.createPlane(1f, 0.01f, 0f).keep() },
                // gate:allow geometry ghost marble: the abstract hover preview (unlit, 45 % opacity), not an object
                async(io) { r.ghostMesh = MeshResource.createSphere(Layout.MARBLE_R).keep() },
                async(io) { r.teal = unlit(Color(0xFF2FA69A), 0.95f).keep() },
                async(io) { r.amber = unlit(Color(0xFFD99A2B), 0.95f).keep() },
                async(io) { r.red = unlit(Color(0xFFC8423F), 0.95f).keep() },
                async(io) { r.last = unlit(Color(0xFFF5F0E6), 0.85f).keep() },
                async(io) { r.hover = unlit(Color(0xFFFFFFFF), 0.5f).keep() },
                async(io) { r.ghostBlack = unlit(Color(0xFF141418), 0.45f).keep() },
                async(io) { r.ghostWhite = unlit(Color(0xFFF2EEE6), 0.45f).keep() },
                async(io) { r.padShape = ShapeResource.createBox(Vector3(Layout.PAD_HIT, Layout.PAD_T, Layout.PAD_HIT)).keep() },
                async(io) { r.marbleShape = ShapeResource.createSphere(Layout.MARBLE_R).keep() },
                async(io) { r.quadShape = ShapeResource.createBox(Vector3(Layout.QUAD, Layout.QUAD_T, Layout.QUAD)).keep() },
                async(io) { r.handleShape = ShapeResource.createBox(Vector3(Layout.HANDLE_WIDE, 0.006f, Layout.HANDLE_LONG)).keep() },
                async(io) { r.physics = PhysicsMaterialResource().keep() },
            ).awaitAll()
            r
        }
    }
}
