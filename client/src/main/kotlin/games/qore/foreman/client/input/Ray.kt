package games.qore.foreman.client.input

import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.max

/** A half line from [origin] along the unit vector [dir]. */
data class Ray(val origin: Vec3, val dir: Vec3) {
    fun at(t: Double): Vec3 = origin.add(dir.scale(t))

    /** Distance from the ray to [point] and how far along the ray that is. */
    fun toPoint(point: Vec3): Pair<Double, Double> {
        val t = max(0.0, point.subtract(origin).dot(dir))
        return at(t).distanceTo(point) to t
    }

    /**
     * The parameter s of the point of the line `start + s * axis` (axis a unit vector) closest to this ray, or null when
     * the ray is nearly parallel to the line.
     */
    fun closestOnLine(start: Vec3, axis: Vec3): Double? {
        val w0 = start.subtract(origin)
        val b = axis.dot(dir)
        val denom = 1.0 - b * b
        if (abs(denom) < 1e-4) return null
        val d = axis.dot(w0)
        val e = dir.dot(w0)
        return (b * e - d) / denom
    }

    /** How far along the ray it meets the plane through [point] with [normal], or null when it doesn't (parallel, behind). */
    fun toPlane(point: Vec3, normal: Vec3): Double? {
        val denom = dir.dot(normal)
        if (abs(denom) < 1e-6) return null
        return (point.subtract(origin).dot(normal) / denom).takeIf { it >= 0 }
    }

    /** Distance from the ray to the segment [a]–[b] and how far along the ray the closest point is. */
    fun toSegment(a: Vec3, b: Vec3): Pair<Double, Double> {
        val length = b.distanceTo(a)
        if (length < 1e-6) return toPoint(a)
        val axis = b.subtract(a).scale(1 / length)
        val s = (closestOnLine(a, axis) ?: 0.0).coerceIn(0.0, length)
        return toPoint(a.add(axis.scale(s)))
    }

    /**
     * The block face the ray hits within [reach], however far (the tools aim from a distance), or null; [solid]: through
     * grass and flowers, onto what has a collision shape.
     */
    fun block(reach: Double, solid: Boolean = false): BlockHitResult? {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return null
        val player = minecraft.player ?: return null
        val shape = if (solid) ClipContext.Block.COLLIDER else ClipContext.Block.OUTLINE
        return level.clip(ClipContext(origin, at(reach), shape, ClipContext.Fluid.NONE, player)).takeIf { it.type == HitResult.Type.BLOCK }
    }

    companion object {
        /**
         * The yaw of something put on [face] (degrees, Minecraft's: 0 faces south): away from a wall, else [looking]
         * rounded to a quarter.
         */
        fun facing(face: Direction, looking: Float): Float = when (face) {
            Direction.SOUTH -> 0f
            Direction.WEST -> 90f
            Direction.NORTH -> 180f
            Direction.EAST -> -90f
            else -> Math.floorMod(Math.round(looking / 90f) * 90, 360).toFloat().let { if (it > 180f) it - 360f else it }
        }

        /** The crosshair: from the camera along where it looks. */
        fun crosshair(): Ray {
            val camera = Minecraft.getInstance().gameRenderer.mainCamera()
            return Ray(camera.position(), Vec3(camera.forwardVector()).normalize())
        }
    }
}
