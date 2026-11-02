package games.qore.foreman.server.world

import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.ItemDisplay
import org.bukkit.util.BoundingBox
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.UUID

/**
 * Item and block display entities (furniture is usually item displays) as models placed in the world, the way the
 * client draws them.
 */
object Displays {
    /** What the client needs to draw one display: read on the main thread, baked later. */
    class Entry(val id: UUID, val pose: Matrix4d, val item: String?, val block: BlockData?)

    /** A display baked in world coordinates. */
    class Shown(val id: UUID, val baked: Baked)

    /** Main thread. */
    fun read(world: World, box: Box): List<Entry> {
        val bounds = BoundingBox(box.min.x() - 4.0, box.min.y() - 4.0, box.min.z() - 4.0, box.max.x() + 5.0, box.max.y() + 5.0, box.max.z() + 5.0)
        return world.getNearbyEntities(bounds) { it is ItemDisplay || it is BlockDisplay }.mapNotNull { e ->
            val l = e.location
            val t = (e as org.bukkit.entity.Display).transformation
            // Display: position, orientation (fixed billboard), then its transformation.
            val pose = Matrix4d().translate(l.x, l.y, l.z)
                .rotate(Quaterniond().rotationYXZ(-Math.toRadians(l.yaw.toDouble()), Math.toRadians(l.pitch.toDouble()), 0.0))
                .translate(t.translation.x.toDouble(), t.translation.y.toDouble(), t.translation.z.toDouble())
                .rotate(Quaterniond(t.leftRotation.x.toDouble(), t.leftRotation.y.toDouble(), t.leftRotation.z.toDouble(), t.leftRotation.w.toDouble()))
                .scale(t.scale.x.toDouble(), t.scale.y.toDouble(), t.scale.z.toDouble())
                .rotate(Quaterniond(t.rightRotation.x.toDouble(), t.rightRotation.y.toDouble(), t.rightRotation.z.toDouble(), t.rightRotation.w.toDouble()))
            when (e) {
                is ItemDisplay -> {
                    val stack = e.itemStack
                    if (stack.type.isAir) return@mapNotNull null
                    val meta = stack.itemMeta
                    val model = if (meta != null && meta.hasItemModel()) meta.itemModel.toString() else stack.type.key.toString()
                    // ItemDisplayRenderer turns the item half a turn before rendering it with its display context.
                    Entry(e.uniqueId, pose.rotateY(Math.PI), model, null)
                }

                is BlockDisplay -> Entry(e.uniqueId, pose, null, e.block)
                else -> null
            }
        }
    }

    /** Bakes [entries] to world quads. Off the main thread is fine. */
    fun bake(entries: List<Entry>): List<Shown> = entries.mapNotNull { e ->
        when {
            e.item != null -> Models.item(e.item)?.let { m -> Shown(e.id, m.baked.transformed(Matrix4d(e.pose).mul(m.fixed))) }
            e.block != null -> Shown(e.id, Models.block(e.block).transformed(e.pose))
            else -> null
        }?.takeIf { !it.baked.empty }
    }

    /**
     * A plain item display at the centre of the block whose corner is the origin, with the item display renderer's half
     * turn: apply it to an item model's fixed transform.
     */
    fun itemPose(): Matrix4d = Matrix4d().translate(0.5, 0.5, 0.5).rotateY(Math.PI)

    fun describe(min: Vector3d, max: Vector3d) =
        "x %.1f..%.1f, y %.1f..%.1f, z %.1f..%.1f".format(min.x, max.x, min.y, max.y, min.z, max.z)
}
