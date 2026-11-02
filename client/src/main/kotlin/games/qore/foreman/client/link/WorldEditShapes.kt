package games.qore.foreman.client.link

import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A WorldEdit selection in one of its shapes, in block coordinates as WorldEdit gives them: drawn, fitted into another
 * box (the gizmo's), and selected again with the commands that make it. Ellipsoids and cylinders hold the blocks within
 * radius + 0.5 of their center, as WorldEdit counts them.
 */
sealed class WeShape {
    /** The blocks it covers, inclusive. */
    abstract val bounds: BlockBox

    /** What the HUD and the hub call it. */
    abstract val label: String

    /** This shape stretched to fill [to] instead of [bounds]. */
    abstract fun fitted(to: BlockBox): WeShape

    /** The commands that select it from scratch, with the selector WorldEdit already has for this shape. */
    abstract fun commands(): List<String>

    abstract fun draw(draw: OverlayDraw, color: Int)

    /** The box the gizmo may give it: a shape around a center grows on both sides when one face is dragged. */
    open fun constrain(start: BlockBox, next: BlockBox): BlockBox = next

    class Cuboid(override val bounds: BlockBox) : WeShape() {
        override val label get() = "cuboid ${bounds.sizeX}×${bounds.sizeY}×${bounds.sizeZ}"
        override fun fitted(to: BlockBox) = Cuboid(to)
        override fun commands() = listOf(pos(1, bounds.minX, bounds.minY, bounds.minZ), pos(2, bounds.maxX, bounds.maxY, bounds.maxZ))
        override fun draw(draw: OverlayDraw, color: Int) =
            draw.blockBox(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, color, 2.5f, Colors.withAlpha(color, 0.06f), onTop = true)
    }

    /** A polygon of [points] (x, z) extruded from [minY] to [maxY]. */
    class Polygon(val points: List<IntArray>, val minY: Int, val maxY: Int) : WeShape() {
        override val bounds = BlockBox(points.minOf { it[0] }, minY, points.minOf { it[1] }, points.maxOf { it[0] }, maxY, points.maxOf { it[1] })
        override val label get() = "polygon, ${points.size} points, y $minY to $maxY"
        override fun fitted(to: BlockBox) = Polygon(points.map { intArrayOf(map(it[0], bounds, to, 0), map(it[1], bounds, to, 2)) }, to.minY, to.maxY)

        override fun commands() = points.mapIndexed { i, p -> pos(if (i == 0) 1 else 2, p[0], if (i == 1) maxY else minY, p[1]) } +
            if (points.size == 1) listOf(pos(2, points[0][0], maxY, points[0][1])) else emptyList()

        override fun draw(draw: OverlayDraw, color: Int) {
            val corners = points.map { Vec3(it[0] + 0.5, 0.0, it[1] + 0.5) }
            corners.forEachIndexed { i, a ->
                val b = corners[(i + 1) % corners.size]
                draw.line(a.with(minY.toDouble()), b.with(minY.toDouble()), color, 2.5f, onTop = true)
                draw.line(a.with(maxY + 1.0), b.with(maxY + 1.0), color, 2.5f, onTop = true)
                draw.line(a.with(minY.toDouble()), a.with(maxY + 1.0), Colors.withAlpha(color, 0.6f), 1.5f, onTop = true)
            }
        }
    }

    /**
     * WorldEdit's ellipsoid or sphere selector (it calls both "ellipsoid"): [center] and [radius] per axis. Equal radii
     * are taken for a [sphere], which keeps them equal when resized: a sphere selector can't hold anything else.
     */
    class Ellipsoid(val center: DoubleArray, val radius: DoubleArray, val sphere: Boolean) : WeShape() {
        override val bounds = BlockBox(lo(0), lo(1), lo(2), hi(0), hi(1), hi(2))
        private fun lo(i: Int) = ceil(center[i] - radius[i] - 0.5).toInt()
        private fun hi(i: Int) = floor(center[i] + radius[i] + 0.5).toInt()

        override val label get() = if (sphere) "sphere, radius ${radius[0].fmt()}" else "ellipsoid, radius ${radius.joinToString("×") { it.fmt() }}"
        override fun fitted(to: BlockBox) = Ellipsoid(DoubleArray(3) { (to.min(it) + to.max(it)) / 2.0 }, DoubleArray(3) { (to.max(it) - to.min(it)) / 2.0 }, sphere)

        override fun commands(): List<String> {
            val c = IntArray(3) { center[it].roundToInt() }
            val r = IntArray(3) { radius[it].roundToInt() }
            // One second position per axis: an ellipsoid keeps the largest radius on each axis, a sphere takes the
            // distance; both end with these radii, whichever selector the server has.
            return listOf(pos(1, c[0], c[1], c[2]), pos(2, c[0] + r[0], c[1], c[2]), pos(2, c[0], c[1] + r[1], c[2]), pos(2, c[0], c[1], c[2] + r[2]))
        }

        override fun constrain(start: BlockBox, next: BlockBox) = if (sphere) uniform(start, next) else mirrored(start, next, 0, 1, 2)

        override fun draw(draw: OverlayDraw, color: Int) {
            val c = Vec3(center[0] + 0.5, center[1] + 0.5, center[2] + 0.5)
            val (rx, ry, rz) = radius.map { it + 0.5 }
            ring(draw, color) { a -> c.add(rx * cos(a), 0.0, rz * sin(a)) }
            ring(draw, color) { a -> c.add(rx * cos(a), ry * sin(a), 0.0) }
            ring(draw, color) { a -> c.add(0.0, ry * cos(a), rz * sin(a)) }
            for (k in listOf(-0.6, 0.6)) ring(draw, Colors.withAlpha(color, 0.5f), 1.5f) { a -> c.add(0.8 * rx * cos(a), k * ry, 0.8 * rz * sin(a)) }
        }
    }

    /** A cylinder around ([cx], [cz]) with radii [rx] and [rz], from [minY] to [maxY]. */
    class Cylinder(val cx: Double, val cz: Double, val rx: Double, val rz: Double, val minY: Int, val maxY: Int) : WeShape() {
        override val bounds = BlockBox(ceil(cx - rx - 0.5).toInt(), minY, ceil(cz - rz - 0.5).toInt(), floor(cx + rx + 0.5).toInt(), maxY, floor(cz + rz + 0.5).toInt())
        override val label get() = "cylinder, radius ${rx.fmt()}×${rz.fmt()}, y $minY to $maxY"
        override fun fitted(to: BlockBox) = Cylinder((to.minX + to.maxX) / 2.0, (to.minZ + to.maxZ) / 2.0, (to.maxX - to.minX) / 2.0, (to.maxZ - to.minZ) / 2.0, to.minY, to.maxY)

        // The first position starts it at the center (one block high), the second sets the radii and the height.
        override fun commands() = listOf(
            pos(1, cx.roundToInt(), minY, cz.roundToInt()),
            pos(2, cx.roundToInt() + rx.roundToInt(), maxY, cz.roundToInt() + rz.roundToInt()),
        )

        override fun constrain(start: BlockBox, next: BlockBox) = mirrored(start, next, 0, 2)

        override fun draw(draw: OverlayDraw, color: Int) {
            val (x, z) = cx + 0.5 to cz + 0.5
            val (ax, az) = rx + 0.5 to rz + 0.5
            for (y in listOf(minY.toDouble(), maxY + 1.0)) ring(draw, color) { a -> Vec3(x + ax * cos(a), y, z + az * sin(a)) }
            for (k in 0 until 8) {
                val a = k * PI / 4
                draw.line(Vec3(x + ax * cos(a), minY.toDouble(), z + az * sin(a)), Vec3(x + ax * cos(a), maxY + 1.0, z + az * sin(a)), Colors.withAlpha(color, 0.6f), 1.5f, onTop = true)
            }
        }
    }

    /** The convex hull of [vertices]; [faces] are triangles of indices into them. */
    class Polyhedron(val vertices: List<IntArray>, val faces: List<IntArray>) : WeShape() {
        override val bounds = BlockBox(
            vertices.minOf { it[0] }, vertices.minOf { it[1] }, vertices.minOf { it[2] },
            vertices.maxOf { it[0] }, vertices.maxOf { it[1] }, vertices.maxOf { it[2] },
        )
        override val label get() = "polyhedron, ${vertices.size} vertices"
        override fun fitted(to: BlockBox) = Polyhedron(vertices.map { v -> IntArray(3) { map(v[it], bounds, to, it) } }, faces)
        override fun commands() = vertices.mapIndexed { i, v -> pos(if (i == 0) 1 else 2, v[0], v[1], v[2]) }

        override fun draw(draw: OverlayDraw, color: Int) {
            val points = vertices.map { Vec3(it[0] + 0.5, it[1] + 0.5, it[2] + 0.5) }
            points.forEach { draw.point(it, color, 5f, onTop = true) }
            val edges = HashSet<Pair<Int, Int>>()
            for (face in faces) face.indices.forEach { i -> edges += minOf(face[i], face[(i + 1) % face.size]) to maxOf(face[i], face[(i + 1) % face.size]) }
            edges.forEach { (a, b) -> if (a in points.indices && b in points.indices) draw.line(points[a], points[b], color, 2f, onTop = true) }
        }
    }

    protected companion object {
        fun pos(n: Int, x: Int, y: Int, z: Int) = "/pos$n $x,$y,$z"

        fun Double.fmt() = if (this == floor(this)) toInt().toString() else "%.1f".format(this)

        fun Vec3.with(y: Double) = Vec3(x, y, z)

        /** Where block [v] of [from] lands in [to] along [axis]. */
        fun map(v: Int, from: BlockBox, to: BlockBox, axis: Int): Int {
            val span = from.max(axis) - from.min(axis)
            if (span == 0) return to.min(axis)
            return to.min(axis) + ((v - from.min(axis)) * (to.max(axis) - to.min(axis)) / span.toDouble()).roundToInt()
        }

        /** On [axes], a dragged face moves the opposite one the other way (the center stays); a move stays a move. */
        fun mirrored(start: BlockBox, next: BlockBox, vararg axes: Int): BlockBox {
            var box = next
            for (i in axes) {
                val down = next.min(i) - start.min(i)
                val up = next.max(i) - start.max(i)
                if (down == up) continue
                val grow = if (up != 0) up else -down
                box = grown(box, start, i, grow)
            }
            return box
        }

        /** Every axis grows like the dragged one (a sphere). */
        fun uniform(start: BlockBox, next: BlockBox): BlockBox {
            val i = (0..2).firstOrNull { next.min(it) - start.min(it) != next.max(it) - start.max(it) } ?: return next
            val grow = (next.max(i) - start.max(i)).takeIf { it != 0 } ?: -(next.min(i) - start.min(i))
            return (0..2).fold(start) { box, axis -> grown(box, start, axis, grow) }
        }

        /** [box] with axis [i] of [start] grown by [grow] blocks on both sides (at least one block thick). */
        private fun grown(box: BlockBox, start: BlockBox, i: Int, grow: Int): BlockBox {
            val half = (start.max(i) - start.min(i)) / 2
            val g = grow.coerceAtLeast(-half)
            val (min, max) = start.min(i) - g to start.max(i) + g
            return when (i) {
                0 -> box.copy(minX = min, maxX = max)
                1 -> box.copy(minY = min, maxY = max)
                else -> box.copy(minZ = min, maxZ = max)
            }
        }

        /** A closed loop through [at] (an angle in radians to a point). */
        fun ring(draw: OverlayDraw, color: Int, width: Float = 2f, at: (Double) -> Vec3) {
            val steps = 48
            var previous = at(0.0)
            for (k in 1..steps) {
                val next = at(2 * PI * k / steps)
                draw.line(previous, next, color, width, onTop = true)
                previous = next
            }
        }
    }
}
