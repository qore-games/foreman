package games.qore.foreman.client.gizmo

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.mc.Mc
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.round

/**
 * How every gizmo of editor mode snaps, one setting for all of them (saved with the editor's settings, cycled with its
 * key: the step of what the gizmo does now, its [Kind]): moves free, by a step, or onto the block grid; turns and scales
 * free or by a step. Ctrl held drags freely meanwhile. A step rounds what a drag adds; block snapping puts the position itself on the grid (block corners, or
 * centres where the thing stands in a block: [Grid]). What is made of whole blocks (boxes, areas, pasted assets,
 * furniture) always moves by whole blocks.
 */
object Snapping {
    /** Moves onto the block grid, the last move step. */
    const val BLOCK = 1.0
    /** Move steps in blocks; 0 is free. */
    val MOVES = listOf(0.0, 1.0 / 16, 1.0 / 8, 0.25, 0.5, BLOCK)
    /** Turn steps in degrees. */
    val TURNS = listOf(0.0, 5.0, 15.0, 22.5, 45.0, 90.0)
    val SCALES = listOf(0.0, 1.0 / 16, 0.25, 0.5)

    /** What scrolling during a free drag adds: a sixteenth, a degree. */
    private const val FINE_MOVE = 1.0 / 16
    private const val FINE_TURN = 1.0

    /** What a gizmo does now, whose step the editor's snap key cycles. */
    enum class Kind { MOVE, TURN, SCALE }

    /** Where block snapping puts a position in its block, per axis: 0 on the corners, 0.5 in the middle. */
    enum class Grid(val offset: Vec3) {
        /** Block corners: what starts at a block's corner (a block display). */
        CORNERS(Vec3.ZERO),
        /** The middle of a block's floor: where things stand (points, locations, models). */
        FLOOR(Vec3(0.5, 0.0, 0.5)),
        /** Block centres. */
        CENTRES(Vec3(0.5, 0.5, 0.5)),
    }

    private val settings get() = Config.settings.editor

    /** Ctrl held: free while it is. */
    val held get() = Mc.isKeyDown(InputConstants.KEY_LCONTROL) || Mc.isKeyDown(InputConstants.KEY_RCONTROL)

    /** The move step now, in blocks: 0 free (or Ctrl held), [BLOCK] onto the grid. */
    val move get() = if (held) 0.0 else settings.moveStep

    /** Moves land on the block grid. */
    val onGrid get() = move == BLOCK

    /** The turn step now, in radians: 0 free. */
    val turn get() = if (held) 0.0 else Math.toRadians(settings.turnStep)

    /** The scale step now: 0 free. */
    val scale get() = if (held) 0.0 else settings.scaleStep

    /** What a scroll during a drag adds: a step, or a fine one when free. */
    val moveNudge get() = settings.moveStep.takeIf { it > 0 } ?: FINE_MOVE
    val turnNudge get() = Math.toRadians(settings.turnStep.takeIf { it > 0 } ?: FINE_TURN)
    val scaleNudge get() = settings.scaleStep.takeIf { it > 0 } ?: FINE_MOVE

    /** The next step of [kind]. */
    fun next(kind: Kind) = Config.update {
        when (kind) {
            Kind.MOVE -> editor.moveStep = next(MOVES, editor.moveStep)
            Kind.TURN -> editor.turnStep = next(TURNS, editor.turnStep)
            Kind.SCALE -> editor.scaleStep = next(SCALES, editor.scaleStep)
        }
    }

    private fun next(steps: List<Double>, current: Double) = steps[(steps.indexOfFirst { it == current } + 1) % steps.size]

    /** `free`, `1/16`… `block`. */
    fun moveLabel(step: Double = settings.moveStep) = when (step) {
        0.0 -> "free"
        BLOCK -> "block"
        else -> fraction(step)
    }

    fun turnLabel(step: Double = settings.turnStep) = if (step == 0.0) "free" else number(step) + "°"

    fun scaleLabel(step: Double = settings.scaleStep) = if (step == 0.0) "free" else fraction(step)

    /** The snap key's line for [kind]: `Snap: 1/4 block`, `Snap: block grid`, `Snap: 15°`, `Snap: 1/16`, `Snap: free turn`. */
    fun caption(kind: Kind) = "Snap: " + when (kind) {
        Kind.MOVE -> when (settings.moveStep) {
            0.0 -> "free move"
            BLOCK -> "block grid"
            else -> moveLabel() + " block"
        }
        Kind.TURN -> if (settings.turnStep == 0.0) "free turn" else turnLabel()
        Kind.SCALE -> if (settings.scaleStep == 0.0) "free scale" else scaleLabel()
    }

    private fun fraction(step: Double) = (1 / step).let { if (it % 1.0 == 0.0) "1/${it.toInt()}" else number(step) }

    private fun number(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    /** [value] rounded to [step]; a step of 0 leaves it. */
    fun round(value: Double, step: Double): Double = if (step <= 0) value else round(value / step) * step

    /** [value] on the grid: the nearest whole number plus [offset]. */
    fun toGrid(value: Double, offset: Double) = round(value - offset) + offset

    /** [p] snapped as a position: onto [grid] in block mode, else to the move step (free: as it is). */
    fun position(p: Vec3, grid: Grid = Grid.CORNERS): Vec3 = if (onGrid) {
        Vec3(toGrid(p.x, grid.offset.x), toGrid(p.y, grid.offset.y), toGrid(p.z, grid.offset.z))
    } else Vec3(round(p.x, move), round(p.y, move), round(p.z, move))
}
