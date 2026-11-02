package games.qore.foreman.client.gizmo

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import net.minecraft.world.phys.Vec3
import kotlin.math.round

/**
 * Handles around a target that are grabbed with the crosshair and dragged along their axis. The drag follows the point
 * of the axis closest to the crosshair ray, so the handle stays under the crosshair as the view turns or the player
 * moves. Subclasses say where the handles are and what a drag of `delta` blocks along a handle does.
 */
abstract class Gizmo<H : Gizmo.Handle> {
    /**
     * A handle: a segment [from]–[to] along the unit vector [axis], picked within [radius] of the ray (of its end when
     * [knob]). [id] tells the same handle apart across frames (handles are rebuilt every frame).
     */
    open class Handle(val id: Int, val from: Vec3, val to: Vec3, val axis: Vec3, val color: Int, val radius: Double, val knob: Boolean = false)

    private class Drag<H>(val handle: H, val startParam: Double)

    var hovered: H? = null
        private set
    private var drag: Drag<H>? = null
    val dragging: Boolean get() = drag != null
    val dragged: H? get() = drag?.handle

    /** The handles for a camera at [camera]; sized from the distance so they look the same size on screen. */
    protected abstract fun handles(camera: Vec3): List<H>

    protected abstract fun dragStarted(handle: H)

    /** [delta] is the distance dragged along the handle's axis since the drag started (not snapped). */
    protected abstract fun dragged(handle: H, delta: Double)

    protected abstract fun dragEnded(handle: H)

    /** The handle under [ray], the nearest along it. */
    fun pick(ray: Ray): H? = handles(ray.origin)
        .mapNotNull { h ->
            val (distance, along) = if (h.knob) ray.toPoint(h.to) else ray.toSegment(h.from, h.to)
            if (distance <= h.radius) h to along else null
        }
        .minByOrNull { it.second }?.first

    /** Starts a drag when [ray] is on a handle; true when it did. */
    fun press(ray: Ray): Boolean {
        val handle = pick(ray) ?: return false
        val param = ray.closestOnLine(handle.from, handle.axis) ?: return false
        drag = Drag(handle, param)
        dragStarted(handle)
        return true
    }

    fun update(ray: Ray) {
        val current = drag
        if (current == null) {
            hovered = pick(ray)
            return
        }
        val param = ray.closestOnLine(current.handle.from, current.handle.axis) ?: return
        dragged(current.handle, param - current.startParam)
    }

    fun release() {
        val current = drag ?: return
        drag = null
        dragEnded(current.handle)
    }

    /** Ends a drag without committing it (the subclass restores its start state in [dragEnded] when [cancelled]). */
    open fun cancel() {
        drag = null
    }

    fun draw(draw: OverlayDraw) {
        val active = drag?.handle
        for (h in handles(draw.camera)) {
            val highlighted = h.id == (active ?: hovered)?.id
            val color = if (highlighted) Colors.HOVER else h.color
            if (h.knob) {
                val r = h.radius * 0.8
                draw.box(h.to.subtract(r, r, r), h.to.add(r, r, r), color, 2f, Colors.withAlpha(color, 0.35f), onTop = true)
                draw.line(h.from, h.to, color, if (highlighted) 4f else 2.5f, onTop = true)
            } else {
                draw.arrow(h.from, h.to, color, if (highlighted) 6f else 4f, onTop = true)
            }
        }
    }

    companion object {
        val AXES = listOf(Vec3(1.0, 0.0, 0.0) to Colors.AXIS_X, Vec3(0.0, 1.0, 0.0) to Colors.AXIS_Y, Vec3(0.0, 0.0, 1.0) to Colors.AXIS_Z)

        /** Handle length for a gizmo at [distance] blocks from the camera. */
        fun size(distance: Double) = (distance * 0.15).coerceIn(0.8, 12.0)

        /** Rounds to [step]; Ctrl held means no snapping. */
        fun snap(value: Double, step: Double): Double =
            if (step <= 0 || InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)) value else round(value / step) * step
    }
}
