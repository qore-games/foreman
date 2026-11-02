package games.qore.foreman.client.gizmo

import net.minecraft.world.phys.Vec3

/** Three arrows (x, y, z) that move a point. [moved] is called live while dragging, [committed] once on release. */
class PointGizmo(
    private val position: () -> Vec3?,
    private val step: () -> Double,
    private val moved: (Vec3) -> Unit,
    private val committed: (from: Vec3, to: Vec3) -> Unit,
) : Gizmo<Gizmo.Handle>() {
    private var start: Vec3? = null

    override fun handles(camera: Vec3): List<Handle> {
        val origin = position() ?: return emptyList()
        val length = size(camera.distanceTo(origin))
        return AXES.mapIndexed { i, (axis, color) -> Handle(i, origin, origin.add(axis.scale(length)), axis, color, length * 0.12 + 0.05) }
    }

    override fun dragStarted(handle: Handle) {
        start = position()
    }

    override fun dragged(handle: Handle, delta: Double) {
        val from = start ?: return
        moved(from.add(handle.axis.scale(snap(delta, step()))))
    }

    override fun dragEnded(handle: Handle) {
        val from = start ?: return
        start = null
        position()?.let { if (it != from) committed(from, it) }
    }

    override fun cancel() {
        start?.let(moved)
        start = null
        super.cancel()
    }

    /** Nudges along the dragged axis (scroll while dragging). */
    fun nudge(amount: Double) {
        val handle = dragged ?: return
        val pos = position() ?: return
        moved(pos.add(handle.axis.scale(amount)))
        start = start?.add(handle.axis.scale(amount))
    }
}
