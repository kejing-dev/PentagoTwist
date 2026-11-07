package io.embodit.pentagotwist.ui.board

import io.embodit.pentagotwist.domain.GameFlow
import io.embodit.pentagotwist.domain.Pentago

/**
 * Entity names of the board volume and what a hit on them means (pure Kotlin, unit tested by BoardTargetTest with
 * resolvePath / parseLoggedPath, build.md 命中回放):
 *  "cell:<i>"         a dimple's pad holder (collider + Interactable + HoverEffect, with the 5 mm marker "mk:cell:<i>" under it)
 *                     or the marble resting in cell i (its GLB clone root carries the same name, so a hit on its mesh walks up);
 *  "quad:<q>"         block q's body (its rim between the dimples);
 *  "handle:<q>:cw|ccw" an arrow handle of block q;
 *  "tray" / "flying" / "frame" / anything else: not a target (a tap there = empty).
 * Every name is made here, from the number passed in, never inside an Entity.apply {} (斗兽棋 v0.1.3 `$id` = Entity.id bug).
 */
object BoardNames {
    fun cell(c: Int) = "cell:$c"
    fun quad(q: Int) = "quad:$q"
    fun handle(q: Int, dir: Int) = "handle:$q:${if (dir == Pentago.CW) "cw" else "ccw"}"
    const val TRAY = "tray"
    const val FLYING = "flying"

    sealed interface Hit
    data class CellHit(val cell: Int) : Hit
    data class QuadHit(val quad: Int) : Hit
    data class HandleHit(val quad: Int, val dir: Int) : Hit
    data object EmptyHit : Hit

    /** A name -> what was hit (null = not one of ours; an out-of-range index is null too, so a naming bug shows in the log). */
    fun resolveName(name: String): Hit? {
        val f = name.split(":")
        return when (f[0]) {
            "cell" -> f.takeIf { it.size == 2 }?.get(1)?.toIntOrNull()?.takeIf { it in 0 until Pentago.N }?.let { CellHit(it) }
            "quad" -> f.takeIf { it.size == 2 }?.get(1)?.toIntOrNull()?.takeIf { it in 0..3 }?.let { QuadHit(it) }
            "handle" -> if (f.size == 3) f[1].toIntOrNull()?.takeIf { it in 0..3 }?.let { q ->
                when (f[2]) { "cw" -> HandleHit(q, Pentago.CW); "ccw" -> HandleHit(q, Pentago.CCW); else -> null }
            } else null
            TRAY, FLYING, "frame", "boardModel" -> EmptyHit
            else -> null
        }
    }

    /** Hit -> the block it belongs to (drags turn that block), null for none. */
    fun quadOf(hit: Hit?): Int? = when (hit) {
        is CellHit -> Pentago.quadOf(hit.cell)
        is QuadHit -> hit.quad
        is HandleHit -> hit.quad
        else -> null
    }

    /** The cell a hit points at (pads / marbles), for logs and the drag radius. */
    fun cellOf(hit: Hit?): Int? = (hit as? CellHit)?.cell

    /** Hit -> the one flow call (the volume listener and the debug `tap` both come through here). */
    fun dispatch(flow: GameFlow, hit: Hit?) {
        when (hit) {
            is CellHit -> flow.tapCell(hit.cell)
            is QuadHit -> flow.tapQuad(hit.quad)
            is HandleHit -> flow.twist(hit.quad, hit.dir)
            EmptyHit, null -> flow.tapEmpty()
        }
    }

    /** "square" of a hit for the input log (build.md: `square=-` must be 0 on the headset). */
    fun squareText(hit: Hit?): String = when (hit) {
        is CellHit -> Pentago.name(hit.cell)
        is QuadHit -> Pentago.quadName(hit.quad)
        is HandleHit -> Pentago.quadName(hit.quad) + if (hit.dir == Pentago.CW) "cw" else "ccw"
        else -> "-"
    }
}
