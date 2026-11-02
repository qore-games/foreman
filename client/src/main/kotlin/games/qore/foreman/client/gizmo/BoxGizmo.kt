package games.qore.foreman.client.gizmo

import games.qore.foreman.client.render.Colors
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt

/** A box of whole blocks, both corners inclusive. */
data class BlockBox(val minX: Int, val minY: Int, val minZ: Int, val maxX: Int, val maxY: Int, val maxZ: Int) {
    val sizeX get() = maxX - minX + 1
    val sizeY get() = maxY - minY + 1
    val sizeZ get() = maxZ - minZ + 1
    val center get() = Vec3((minX + maxX + 1) / 2.0, (minY + maxY + 1) / 2.0, (minZ + maxZ + 1) / 2.0)

    fun min(axis: Int) = when (axis) { 0 -> minX; 1 -> minY; else -> minZ }
    fun max(axis: Int) = when (axis) { 0 -> maxX; 1 -> maxY; else -> maxZ }

    fun moved(dx: Int, dy: Int, dz: Int) = BlockBox(minX + dx, minY + dy, minZ + dz, maxX + dx, maxY + dy, maxZ + dz)

    fun withMin(axis: Int, value: Int) = when (axis) {
        0 -> copy(minX = minOf(value, maxX)); 1 -> copy(minY = minOf(value, maxY)); else -> copy(minZ = minOf(value, maxZ))
    }

    fun withMax(axis: Int, value: Int) = when (axis) {
        0 -> copy(maxX = maxOf(value, minX)); 1 -> copy(maxY = maxOf(value, minY)); else -> copy(maxZ = maxOf(value, minZ))
    }

    fun describe() = "[$minX, $minY, $minZ] to [$maxX, $maxY, $maxZ] (${sizeX}x${sizeY}x${sizeZ})"

    companion object {
        fun of(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int) =
            BlockBox(minOf(x0, x1), minOf(y0, y1), minOf(z0, z1), maxOf(x0, x1), maxOf(y0, y1), maxOf(z0, z1))
    }
}

/**
 * Moves and resizes a [BlockBox]: a knob on each face drags that face along its normal, three arrows from the center
 * move the whole box, both in whole blocks.
 */
class BoxGizmo(
    private val box: () -> BlockBox?,
    private val changed: (BlockBox) -> Unit,
    private val committed: (BlockBox) -> Unit,
) : Gizmo<BoxGizmo.BoxHandle>() {
    /** [face] is -1 for a move arrow, else the face (axis * 2 + 1 for the max side). */
    class BoxHandle(from: Vec3, to: Vec3, axis: Vec3, color: Int, radius: Double, knob: Boolean, val axisIndex: Int, val face: Int) :
        Handle(if (face < 0) 6 + axisIndex else face, from, to, axis, color, radius, knob)

    private var start: BlockBox? = null

    override fun handles(camera: Vec3): List<BoxHandle> {
        val b = box() ?: return emptyList()
        val center = b.center
        val length = size(camera.distanceTo(center))
        val handles = mutableListOf<BoxHandle>()
        AXES.forEachIndexed { i, (axis, color) ->
            handles += BoxHandle(center, center.add(axis.scale(length)), axis, color, length * 0.1 + 0.05, false, i, -1)
            for (side in 0..1) {
                val sign = if (side == 1) 1.0 else -1.0
                val faceOffset = (if (side == 1) b.max(i) + 1 - center.get(i) else b.min(i) - center.get(i))
                val faceCenter = center.add(axis.scale(faceOffset))
                val knob = faceCenter.add(axis.scale(sign * length * 0.35))
                handles += BoxHandle(faceCenter, knob, axis, Colors.withAlpha(color, 0.9f) or 0x202020, length * 0.12 + 0.08, true, i, i * 2 + side)
            }
        }
        return handles
    }

    private fun Vec3.get(axis: Int) = when (axis) { 0 -> x; 1 -> y; else -> z }

    override fun dragStarted(handle: BoxHandle) {
        start = box()
    }

    override fun dragged(handle: BoxHandle, delta: Double) {
        val from = start ?: return
        val blocks = delta.roundToInt()
        val i = handle.axisIndex
        val next = when {
            handle.face < 0 -> from.moved(if (i == 0) blocks else 0, if (i == 1) blocks else 0, if (i == 2) blocks else 0)
            handle.face % 2 == 1 -> from.withMax(i, from.max(i) + blocks)
            else -> from.withMin(i, from.min(i) + blocks)
        }
        if (next != box()) changed(next)
    }

    override fun dragEnded(handle: BoxHandle) {
        val from = start
        start = null
        box()?.let { if (it != from) committed(it) }
    }

    override fun cancel() {
        start?.let(changed)
        start = null
        super.cancel()
    }
}
