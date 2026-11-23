package games.qore.foreman.client.entity

import com.mojang.math.Transformation
import games.qore.foreman.client.mixin.DisplayAccessor
import games.qore.foreman.client.mixin.DisplayStateAccessor
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.world.entity.Display
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.UUID

/**
 * A display's place and transformation: what the editor changes and `edit_displays` sets. Its yaw and pitch turn it
 * (a fixed billboard), then its transformation: [translation], [left] rotation, [scale], [right] rotation.
 */
data class DisplayPose(
    val pos: Vec3, val yaw: Float, val pitch: Float,
    val translation: Vector3d, val left: Quaterniond, val scale: Vector3d, val right: Quaterniond,
) {
    /** The rotation of its yaw and pitch. */
    val orientation: Quaterniond get() = orientation(yaw, pitch)

    /** Where its transformation starts in the world: its position, plus its translation turned by its yaw and pitch. */
    val origin: Vec3 get() = pos.add(vec(orientation.transform(Vector3d(translation))))

    /** Its model's rotation in the world: the axes it is scaled along. */
    val rotation: Quaterniond get() = Quaterniond(orientation).mul(left)

    fun axes(): List<Vec3> = UNITS.map { vec(rotation.transform(Vector3d(it))) }

    fun moved(offset: Vec3) = copy(pos = pos.add(offset))

    /** At the same yaw, pitch and translation, its transformation starting at [origin]. */
    fun withOrigin(origin: Vec3) = copy(pos = origin.subtract(vec(orientation.transform(Vector3d(translation)))))

    /** Turned [angle] radians around [axis] (world) through [pivot]: the turn goes into its left rotation. */
    fun rotated(axis: Vec3, angle: Double, pivot: Vec3): DisplayPose {
        val turn = Quaterniond().fromAxisAngleRad(axis.x, axis.y, axis.z, angle)
        val o = orientation
        val newLeft = Quaterniond(o).conjugate().mul(turn).mul(o).mul(left).normalize()
        val origin = pivot.add(vec(turn.transform(origin.subtract(pivot).let { Vector3d(it.x, it.y, it.z) })))
        return withOrigin(origin).copy(left = newLeft)
    }

    /**
     * Scaled by [factors] along [axes] (world) from [pivot]. Its own scale axes take the factors of the axes they lie
     * along (all of them when they are the same axes, or the scaling is uniform).
     */
    fun scaled(factors: Vec3, axes: List<Vec3>, pivot: Vec3): DisplayPose {
        val f = listOf(factors.x, factors.y, factors.z)
        val own = axes()
        val mine = own.map { a -> axes.indices.sumOf { i -> a.dot(axes[i]).let { it * it } * f[i] } }
        val rel = origin.subtract(pivot)
        val origin = axes.indices.fold(pivot) { p, i -> p.add(axes[i].scale(f[i] * rel.dot(axes[i]))) }
        return withOrigin(origin).copy(scale = Vector3d(scale.x * mine[0], scale.y * mine[1], scale.z * mine[2]))
    }

    fun transformation() = Transformation(Vector3f(translation.x.toFloat(), translation.y.toFloat(), translation.z.toFloat()), Quaternionf(left), Vector3f(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat()), Quaternionf(right))

    /** Shows it on the client's [e] at once (until the server says otherwise). */
    fun applyTo(e: Display) {
        e.interpolation?.cancel()
        e.snapTo(pos.x, pos.y, pos.z, yaw, pitch)
        e.setTransformation(transformation())
        val state = e as DisplayStateAccessor
        state.`foreman$setRenderState`(state.`foreman$freshRenderState`())
    }

    /** `edit_displays`'s entry for [uuid]: absolute values; its yaw and pitch only with [facing]. */
    fun json(uuid: UUID, facing: Boolean): JsonObject = buildJsonObject {
        put("uuid", uuid.toString())
        put("position", numbers(pos.x, pos.y, pos.z))
        if (facing) {
            put("yaw", yaw)
            put("pitch", pitch)
        }
        put("transformation", transformationJson())
    }

    fun transformationJson(): JsonObject = buildJsonObject {
        put("translation", numbers(translation.x, translation.y, translation.z))
        put("left_rotation", numbers(left.x, left.y, left.z, left.w))
        put("scale", numbers(scale.x, scale.y, scale.z))
        put("right_rotation", numbers(right.x, right.y, right.z, right.w))
    }

    companion object {
        private val UNITS = listOf(Vector3d(1.0, 0.0, 0.0), Vector3d(0.0, 1.0, 0.0), Vector3d(0.0, 0.0, 1.0))

        fun orientation(yaw: Float, pitch: Float): Quaterniond =
            Quaterniond().rotationYXZ(-Math.toRadians(yaw.toDouble()), Math.toRadians(pitch.toDouble()), 0.0)

        /** [e]'s pose as last synced (not the one it is interpolating through). */
        fun of(e: Display): DisplayPose {
            val t = DisplayAccessor.`foreman$transformation`(e.getEntityData())
            return DisplayPose(
                e.position(), e.yRot, e.xRot,
                Vector3d(t.translation()), Quaterniond(t.leftRotation()), Vector3d(t.scale()), Quaterniond(t.rightRotation()),
            )
        }

        fun vec(v: Vector3d) = Vec3(v.x, v.y, v.z)

        /** Numbers as JSON, rounded where floats stop being exact (what the entity keeps). */
        private fun numbers(vararg values: Double) = JsonArray(values.map { JsonPrimitive(Math.round(it * 1e6) / 1e6) })
    }
}
