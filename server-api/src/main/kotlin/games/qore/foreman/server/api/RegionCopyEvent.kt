package games.qore.foreman.server.api

import org.bukkit.World
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.util.BlockVector
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Foreman copied ([Kind.PASTE]) or moved ([Kind.MOVE]) a box of a world: an asset pasted, an asset moved, a region
 * transformed. Fired on the main thread once the blocks, the block data and the entities are in place, before
 * Foreman asks the [FurnitureProvider] about the furniture there: fix what your plugin keeps by position or by entity
 * (indexes, ownership, links between blocks and entities). An undo of a move fires a [Kind.MOVE] back.
 *
 * The blocks of [source] in [sourceWorld] went into [world]: the block at `p` went to [block] `(p)`, that is
 * `origin + offset + turn(p - origin)` where `turn` turns [rotation]° clockwise seen from above (90: `(dx, dz) → (-dz, dx)`).
 * Entities turned about the centre of the [origin] block ([position], [yaw]). Foreman also copied the block data of the
 * CustomBlockData library (chunk persistent data keyed `<namespace>:x<x>y<y>z<z>`) to the destination blocks, and a
 * move removed it from the source: entries that name entities or other blocks still name the old ones.
 *
 * [entities]: the entities of the source → those at the destination. A move teleports them (each is its own UUID); a
 * paste spawns copies. In the [Kind.MOVE] back of an undo, an entity an undo put back since (as another entity) maps
 * from the one recorded to the one it is now: block data put back names the first. Entities Foreman didn't carry over (a
 * furniture its provider placed again) aren't in it.
 */
class RegionCopyEvent(
    val kind: Kind,
    val sourceWorld: World,
    /** Block coordinates, max corners exclusive like Bukkit's. */
    val source: BoundingBox,
    val world: World,
    /** Where [source] went, max corners exclusive. */
    val destination: BoundingBox,
    /** The block the turn is about. */
    val origin: BlockVector,
    /** Where [origin] went, minus [origin]. */
    val offset: BlockVector,
    /** Clockwise degrees seen from above: 0, 90, 180 or 270. */
    val rotation: Int,
    val entities: Map<UUID, UUID>,
) : Event() {
    enum class Kind { PASTE, MOVE }

    /** Where the source block at [x], [y], [z] went. */
    fun block(x: Int, y: Int, z: Int): BlockVector {
        val dx = x - origin.blockX
        val dz = z - origin.blockZ
        val (tx, tz) = when (rotation) {
            90 -> -dz to dx
            180 -> -dx to -dz
            270 -> dz to -dx
            else -> dx to dz
        }
        return BlockVector(origin.blockX + offset.blockX + tx, y + offset.blockY, origin.blockZ + offset.blockZ + tz)
    }

    /** Where a point of the source (an entity's position) went. */
    fun position(x: Double, y: Double, z: Double): Vector {
        val dx = x - (origin.blockX + 0.5)
        val dz = z - (origin.blockZ + 0.5)
        val (tx, tz) = when (rotation) {
            90 -> -dz to dx
            180 -> -dx to -dz
            270 -> dz to -dx
            else -> dx to dz
        }
        return Vector(origin.blockX + offset.blockX + 0.5 + tx, y + offset.blockY, origin.blockZ + offset.blockZ + 0.5 + tz)
    }

    /** An entity's yaw at the destination. */
    fun yaw(yaw: Float): Float = yaw + rotation

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
