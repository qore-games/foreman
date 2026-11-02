package games.qore.foreman.client.input

import net.minecraft.client.Minecraft
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

    /** Distance from the ray to the segment [a]–[b] and how far along the ray the closest point is. */
    fun toSegment(a: Vec3, b: Vec3): Pair<Double, Double> {
        val length = b.distanceTo(a)
        if (length < 1e-6) return toPoint(a)
        val axis = b.subtract(a).scale(1 / length)
        val s = (closestOnLine(a, axis) ?: 0.0).coerceIn(0.0, length)
        return toPoint(a.add(axis.scale(s)))
    }

    companion object {
        /** The crosshair: from the camera along where it looks. */
        fun crosshair(): Ray {
            val camera = Minecraft.getInstance().gameRenderer.mainCamera()
            return Ray(camera.position(), Vec3(camera.forwardVector()).normalize())
        }
    }
}
