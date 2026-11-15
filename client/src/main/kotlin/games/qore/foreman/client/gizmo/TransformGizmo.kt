package games.qore.foreman.client.gizmo

import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sin

/**
 * Where a [TransformGizmo] stands: its [pivot] and its three [axes] (unit vectors in the world: its x, y and z), and the
 * three its rings turn around, [turns] (its axes; a point's own, as it faces, while it moves along the world's).
 */
class GizmoFrame(val pivot: Vec3, val axes: List<Vec3>, val turns: List<Vec3> = axes) {
    companion object {
        val WORLD_AXES: List<Vec3> = Gizmo.AXES.map { it.first }
    }
}

/** What a drag of a [TransformGizmo] did since it started, snapped unless Ctrl is held. */
sealed interface GizmoChange {
    /** Moved by [offset] (world). */
    data class Move(val offset: Vec3) : GizmoChange

    /** Turned [angle] radians around [axis] (world, unit; right-handed) through the pivot. */
    data class Rotate(val axis: Vec3, val angle: Double) : GizmoChange

    /** Scaled by [factors] along the frame's x, y and z axes, from the pivot. */
    data class Scale(val factors: Vec3) : GizmoChange
}

/**
 * Move, rotate and scale handles around a pivot, along a frame's axes (the world's, or what is edited's own): in
 * [Mode.MOVE] three arrows, three plane squares and a center knob that moves it anywhere (in the plane facing the camera
 * as it was grabbed, the scroll wheel pushing it farther or pulling it nearer), in [Mode.ROTATE] the [rings], in
 * [Mode.SCALE] three stretch handles and a center knob for all axes at once. With [ringsWithMove] the rings show with the arrows, outside them, and there
 * are no modes to switch (a point: where it is and where it faces). A drag reports its whole change from its start
 * ([changed], live; [committed] on release), snapped as the editor's [Snapping] says: moves onto the block grid ([grid]
 * says where the pivot sits in a block) or by the move step, turns and scaled values (from [scaleBase]) by theirs;
 * [wholeBlocks] moves by whole blocks whatever the setting. The frame is read when a drag starts and kept until it ends.
 */
class TransformGizmo(
    private val frame: () -> GizmoFrame?,
    private val changed: (GizmoChange) -> Unit,
    private val committed: (GizmoChange) -> Unit,
    private val cancelled: () -> Unit = {},
    /** The scale along each frame axis of what is scaled (the first selected), for snapping its scale to steps. */
    private val scaleBase: () -> Vec3 = { Vec3(1.0, 1.0, 1.0) },
    /** The frame axes the rings turn around: y alone for a facing. */
    private val rings: List<Int> = listOf(0, 1, 2),
    private val ringsWithMove: Boolean = false,
    private val grid: () -> Snapping.Grid = { Snapping.Grid.CORNERS },
    private val wholeBlocks: () -> Boolean = { false },
) {
    enum class Mode(val label: String) { MOVE("Move"), ROTATE("Rotate"), SCALE("Scale") }

    private enum class Kind { AXIS, PLANE, FREE, RING, STRETCH, UNIFORM }

    /** A handle: what it does and the frame axis it works along (around, for rings; the normal, for planes). */
    private data class Part(val kind: Kind, val axis: Int)

    private class Drag(val part: Part, val frame: GizmoFrame, val size: Double, val base: Vec3, val grid: Snapping.Grid, val whole: Boolean) {
        /** Where it was grabbed: on the axis or tangent line (a parameter), or on a plane (a point). */
        var param = 0.0
        var point: Vec3 = Vec3.ZERO
        var normal: Vec3 = Vec3.ZERO
        /** A free move's way away from the camera, where scrolling pushes it. */
        var depth: Vec3 = Vec3.ZERO
        /** A ring seen edge on is dragged along its tangent at the grabbed point instead of around. */
        var tangent: Vec3? = null
        var lastAngle = 0.0
        var turned = 0.0
        /** Scroll steps added while dragging (nudges), in the handle's steps. */
        var nudged = 0
        var last: GizmoChange? = null
    }

    var mode = Mode.MOVE

    private var hovered: Part? = null
    private var drag: Drag? = null
    val dragging get() = drag != null
    val hovering get() = hovered != null
    /** The center knob is dragged or under the crosshair: it moves anywhere, the scroll wheel farther or nearer. */
    val movingFreely get() = (drag?.part ?: hovered)?.kind == Kind.FREE

    /**
     * Whose step the editor's snap key changes for it now: its mode's; with the rings among the arrows, the part dragged
     * or under the crosshair (a ring turns), else moving.
     */
    val snapKind: Snapping.Kind
        get() = when (mode) {
            Mode.ROTATE -> Snapping.Kind.TURN
            Mode.SCALE -> Snapping.Kind.SCALE
            Mode.MOVE -> if ((drag?.part ?: hovered)?.kind == Kind.RING) Snapping.Kind.TURN else Snapping.Kind.MOVE
        }

    private fun parts(): List<Part> = when (mode) {
        Mode.MOVE -> (0..2).map { Part(Kind.AXIS, it) } + (0..2).map { Part(Kind.PLANE, it) } + Part(Kind.FREE, 0) +
            if (ringsWithMove) rings.map { Part(Kind.RING, it) } else emptyList()
        Mode.ROTATE -> rings.map { Part(Kind.RING, it) }
        Mode.SCALE -> (0..2).map { Part(Kind.STRETCH, it) } + Part(Kind.UNIFORM, 0)
    }

    /** The rings' radius, in gizmo sizes: outside the arrows when they show together. */
    private val ringRadius get() = if (ringsWithMove) OUTER_RING else RING

    private fun size(f: GizmoFrame, camera: Vec3) = Gizmo.size(camera.distanceTo(f.pivot))

    /** The plane square of [axis]: its corners, between [PLANE_FROM] and [PLANE_TO] of the size along the other axes. */
    private fun plane(f: GizmoFrame, axis: Int, size: Double): List<Vec3> {
        val u = f.axes[(axis + 1) % 3].scale(size)
        val v = f.axes[(axis + 2) % 3].scale(size)
        return listOf(PLANE_FROM to PLANE_FROM, PLANE_TO to PLANE_FROM, PLANE_TO to PLANE_TO, PLANE_FROM to PLANE_TO)
            .map { (a, b) -> f.pivot.add(u.scale(a)).add(v.scale(b)) }
    }

    private fun ring(f: GizmoFrame, axis: Int, size: Double): List<Vec3> {
        val u = f.turns[(axis + 1) % 3].scale(size * ringRadius)
        val v = f.turns[(axis + 2) % 3].scale(size * ringRadius)
        return (0 until RING_SEGMENTS).map { i ->
            val a = i * 2 * Math.PI / RING_SEGMENTS
            f.pivot.add(u.scale(cos(a))).add(v.scale(sin(a)))
        }
    }

    /** The axis [part] works along (a ring: around). */
    private fun axis(f: GizmoFrame, part: Part) = if (part.kind == Kind.RING) f.turns[part.axis] else f.axes[part.axis]

    /** How far along [ray] it meets [part], or null when it misses. */
    private fun hit(part: Part, f: GizmoFrame, size: Double, ray: Ray): Double? {
        val axis = f.axes[part.axis]
        return when (part.kind) {
            Kind.AXIS -> ray.toSegment(f.pivot, f.pivot.add(axis.scale(size))).takeIf { it.first <= size * 0.1 + 0.05 }?.second
            // Off the center, where the knob for all axes is.
            Kind.STRETCH -> ray.toSegment(f.pivot.add(axis.scale(size * 0.25)), f.pivot.add(axis.scale(size))).takeIf { it.first <= size * 0.1 + 0.05 }?.second
            Kind.PLANE -> {
                val t = ray.toPlane(f.pivot, axis) ?: return null
                val local = ray.at(t).subtract(f.pivot)
                val a = local.dot(f.axes[(part.axis + 1) % 3]) / size
                val b = local.dot(f.axes[(part.axis + 2) % 3]) / size
                t.takeIf { a in PLANE_FROM..PLANE_TO && b in PLANE_FROM..PLANE_TO }
            }
            Kind.RING -> {
                val points = ring(f, part.axis, size)
                points.indices.map { ray.toSegment(points[it], points[(it + 1) % points.size]) }
                    .filter { it.first <= size * 0.06 + 0.04 }.minOfOrNull { it.second }
            }
            Kind.FREE, Kind.UNIFORM -> ray.toPoint(f.pivot).takeIf { it.first <= size * KNOB + 0.05 }?.second
        }
    }

    private fun pick(ray: Ray): Part? {
        val f = frame() ?: return null
        val size = size(f, ray.origin)
        val hits = parts().mapNotNull { p -> hit(p, f, size, ray)?.let { p to it } }
        // The center knob before the arrows starting under it.
        return hits.firstOrNull { it.first.kind == Kind.UNIFORM || it.first.kind == Kind.FREE }?.first ?: hits.minByOrNull { it.second }?.first
    }

    /** Starts a drag when [ray] is on a handle; true when it did. */
    fun press(ray: Ray): Boolean {
        val f = frame() ?: return false
        val part = pick(ray) ?: return false
        val d = Drag(part, f, size(f, ray.origin), scaleBase(), grid(), wholeBlocks())
        val axis = axis(f, part)
        when (part.kind) {
            Kind.AXIS, Kind.STRETCH -> d.param = ray.closestOnLine(f.pivot, axis) ?: return false
            Kind.PLANE -> d.point = ray.toPlane(f.pivot, axis)?.let(ray::at) ?: return false
            Kind.FREE, Kind.UNIFORM -> {
                d.normal = ray.dir.reverse()
                d.depth = ray.dir
                d.point = ray.toPlane(f.pivot, d.normal)?.let(ray::at) ?: return false
            }
            Kind.RING -> if (abs(ray.dir.dot(axis)) > EDGE_ON) {
                d.point = ray.toPlane(f.pivot, axis)?.let(ray::at) ?: return false
            } else {
                // Seen edge on, the ring's plane is no use: drag along the tangent where it was grabbed.
                val grabbed = ring(f, part.axis, d.size).minBy { ray.toPoint(it).first }
                val tangent = axis.cross(grabbed.subtract(f.pivot)).normalize()
                d.tangent = tangent
                d.point = grabbed
                d.param = ray.closestOnLine(grabbed, tangent) ?: return false
            }
        }
        drag = d
        return true
    }

    fun update(ray: Ray) {
        val d = drag
        if (d == null) {
            hovered = pick(ray)
            return
        }
        val change = change(d, ray) ?: return
        if (change != d.last) {
            d.last = change
            changed(change)
        }
    }

    /** The drag's change for [ray], or null when the ray tells nothing (parallel to what it is dragged on). */
    private fun change(d: Drag, ray: Ray): GizmoChange? {
        val f = d.frame
        val axis = axis(f, d.part)
        return when (d.part.kind) {
            Kind.AXIS -> {
                val s = ray.closestOnLine(f.pivot, axis) ?: return null
                GizmoChange.Move(axis.scale(snapMove(d, d.part.axis, s - d.param) + d.nudged * moveNudge(d)))
            }
            Kind.PLANE -> {
                val offset = ray.toPlane(f.pivot, axis)?.let(ray::at)?.subtract(d.point) ?: return null
                val u = (d.part.axis + 1) % 3
                val v = (d.part.axis + 2) % 3
                GizmoChange.Move(f.axes[u].scale(snapMove(d, u, offset.dot(f.axes[u]))).add(f.axes[v].scale(snapMove(d, v, offset.dot(f.axes[v])))))
            }
            Kind.FREE -> {
                val offset = (ray.toPlane(f.pivot, d.normal)?.let(ray::at)?.subtract(d.point) ?: return null)
                    .add(d.depth.scale(d.nudged * moveNudge(d)))
                GizmoChange.Move((0..2).fold(Vec3.ZERO) { sum, i -> sum.add(f.axes[i].scale(snapMove(d, i, offset.dot(f.axes[i])))) })
            }
            Kind.RING -> {
                val tangent = d.tangent
                val angle = if (tangent != null) {
                    ((ray.closestOnLine(d.point, tangent) ?: return null) - d.param) / (d.size * ringRadius)
                } else {
                    val now = ray.toPlane(f.pivot, axis)?.let(ray::at) ?: return null
                    val step = signedAngle(d.point.subtract(f.pivot), now.subtract(f.pivot), axis)
                    // Unwrapped, so a drag can go round more than half a turn.
                    var delta = step - d.lastAngle
                    if (delta > Math.PI) delta -= 2 * Math.PI
                    if (delta < -Math.PI) delta += 2 * Math.PI
                    d.lastAngle = step
                    d.turned += delta
                    d.turned
                }
                GizmoChange.Rotate(axis, Snapping.round(angle, Snapping.turn) + d.nudged * Snapping.turnNudge)
            }
            Kind.STRETCH -> {
                val s = ray.closestOnLine(f.pivot, axis) ?: return null
                val factor = snapScale(1 + (s - d.param) / d.size, d.base.get(d.part.axis), d.nudged)
                GizmoChange.Scale(Vec3(if (d.part.axis == 0) factor else 1.0, if (d.part.axis == 1) factor else 1.0, if (d.part.axis == 2) factor else 1.0))
            }
            Kind.UNIFORM -> {
                val offset = ray.toPlane(f.pivot, d.normal)?.let(ray::at)?.subtract(d.point) ?: return null
                // Up or right on the screen grows it.
                val left = d.normal.cross(Vec3(0.0, 1.0, 0.0)).let { if (it.lengthSqr() < 1e-6) Vec3(-1.0, 0.0, 0.0) else it.normalize() }
                val up = left.cross(d.normal)
                val base = maxOf(abs(d.base.x), abs(d.base.y), abs(d.base.z))
                val factor = snapScale(1 + (offset.dot(up) - offset.dot(left)) / d.size, base, d.nudged)
                GizmoChange.Scale(Vec3(factor, factor, factor))
            }
        }
    }

    /**
     * How far a drag of [amount] along the frame's axis [axis] moves the pivot: by whole blocks, onto the block grid
     * (along a world axis; another goes by whole blocks), or by the move step.
     */
    private fun snapMove(d: Drag, axis: Int, amount: Double): Double {
        if (d.whole) return Snapping.round(amount, 1.0)
        if (!Snapping.onGrid) return Snapping.round(amount, Snapping.move)
        val direction = d.frame.axes[axis]
        val world = (0..2).firstOrNull { abs(abs(direction.get(it)) - 1) < 1e-6 } ?: return Snapping.round(amount, 1.0)
        val sign = sign(direction.get(world))
        val from = d.frame.pivot.get(world)
        return sign * (Snapping.toGrid(from + sign * amount, d.grid.offset.get(world)) - from)
    }

    private fun moveNudge(d: Drag) = if (d.whole || Snapping.onGrid) 1.0 else Snapping.moveNudge

    /** [factor] such that [base] times it is a whole number of scale steps (plus [nudged] steps), never 0 nor flipped. */
    private fun snapScale(factor: Double, base: Double, nudged: Int): Double {
        if (abs(base) < 1e-6) return max(factor, MIN_FACTOR)
        val step = Snapping.scale
        val scaled = Snapping.round(base * max(factor, MIN_FACTOR), step) + sign(base) * nudged * Snapping.scaleNudge
        return max(scaled / base, if (scaled == 0.0) Snapping.scaleNudge / abs(base) else MIN_FACTOR)
    }

    private fun Vec3.get(axis: Int) = when (axis) { 0 -> x; 1 -> y; else -> z }

    /**
     * Adds [steps] steps to the drag (scroll while dragging; the center knob's push it farther, or nearer when negative);
     * false when the handle has no steps (planes).
     */
    fun nudge(steps: Int): Boolean {
        val d = drag ?: return false
        if (d.part.kind == Kind.PLANE) return false
        d.nudged += steps
        return true
    }

    fun release() {
        val d = drag ?: return
        drag = null
        val change = d.last
        if (change == null || isIdentity(change)) cancelled() else committed(change)
    }

    /** Ends a drag without committing it. */
    fun cancel() {
        if (drag == null) return
        drag = null
        cancelled()
    }

    private fun isIdentity(change: GizmoChange) = when (change) {
        is GizmoChange.Move -> change.offset.lengthSqr() < 1e-12
        is GizmoChange.Rotate -> abs(change.angle) < 1e-9
        is GizmoChange.Scale -> abs(change.factors.x - 1) < 1e-9 && abs(change.factors.y - 1) < 1e-9 && abs(change.factors.z - 1) < 1e-9
    }

    /** What the current drag did, for a label: "+0.25 x", "45°", "×1.5". */
    fun describe(): String? = when (val c = drag?.last) {
        is GizmoChange.Move -> "%+.3f %+.3f %+.3f".format(c.offset.x, c.offset.y, c.offset.z)
        is GizmoChange.Rotate -> "%+.1f°".format(Math.toDegrees(c.angle))
        is GizmoChange.Scale -> "×" + listOf(c.factors.x, c.factors.y, c.factors.z).distinct().joinToString(" ") { "%.3f".format(it) }
        null -> null
    }

    fun draw(draw: OverlayDraw) {
        val f = frame() ?: return
        val size = size(f, draw.camera)
        val active = drag?.part ?: hovered
        for (part in parts()) {
            val on = part == active
            val color = if (on) Colors.HOVER else Gizmo.AXES[part.axis].second
            val axis = f.axes[part.axis]
            when (part.kind) {
                Kind.AXIS -> draw.arrow(f.pivot, f.pivot.add(axis.scale(size)), color, if (on) 6f else 4f, onTop = true)
                Kind.PLANE -> plane(f, part.axis, size).let { (a, b, c, d) -> draw.quad(a, b, c, d, color, 2f, Colors.withAlpha(color, if (on) 0.55f else 0.25f), onTop = true) }
                Kind.RING -> draw.loop(ring(f, part.axis, size), color, if (on) 5f else 3f, onTop = true)
                Kind.STRETCH -> {
                    val end = f.pivot.add(axis.scale(size))
                    val r = size * KNOB * 0.8
                    draw.line(f.pivot, end, color, if (on) 4f else 2.5f, onTop = true)
                    draw.box(end.subtract(r, r, r), end.add(r, r, r), color, 2f, Colors.withAlpha(color, 0.5f), onTop = true)
                }
                Kind.FREE, Kind.UNIFORM -> {
                    val r = size * KNOB
                    val c = if (on) Colors.HOVER else Colors.WHITE
                    draw.box(f.pivot.subtract(r, r, r), f.pivot.add(r, r, r), c, 2f, Colors.withAlpha(c, 0.35f), onTop = true)
                }
            }
        }
        describe()?.let { draw.label(f.pivot.add(0.0, size * 1.15, 0.0), it, Colors.HOVER) }
    }

    private companion object {
        const val PLANE_FROM = 0.25
        const val PLANE_TO = 0.5
        /** The rings' radius, in gizmo sizes; outside the arrows when they show with them. */
        const val RING = 0.85
        const val OUTER_RING = 1.3
        const val RING_SEGMENTS = 48
        /** The half size of the knobs, in gizmo sizes. */
        const val KNOB = 0.08
        /** Below this cosine between the view and a ring's axis, the ring is seen too edge on to drag around. */
        const val EDGE_ON = 0.2
        const val MIN_FACTOR = 0.01

        /** The angle from [a] to [b] around [axis] (right-handed). */
        fun signedAngle(a: Vec3, b: Vec3, axis: Vec3) = atan2(a.cross(b).dot(axis), a.dot(b))
    }
}
