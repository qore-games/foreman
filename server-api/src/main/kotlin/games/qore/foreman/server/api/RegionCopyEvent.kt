package games.qore.foreman.server.api

import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.util.BlockVector
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID

/**
 * Foreman copied ([Kind.PASTE]) or moved ([Kind.MOVE]) a box of a world: an asset or a clipboard pasted, an asset
 * moved, a region transformed. Fired on the main thread once the blocks, the block data and the entities are in place,
 * before Foreman asks the [FurnitureProvider] about the furniture there: fix what your plugin keeps by position or by
 * entity (indexes, ownership, links between blocks and entities). An undo of a move fires a [Kind.MOVE] back.
 *
 * The blocks of [source] in [sourceWorld] went into [world]: the block at `p` went to [block] `(p)`, that is
 * `origin + offset + turn(mirror(p - origin))` where `mirror` negates x when [mirror] (east and west swap, about the
 * [origin] block) and `turn` turns [rotation]° clockwise seen from above (90: `(dx, dz) → (-dz, dx)`). Entities went
 * the same way about the centre of the [origin] block ([position], [yaw]); a face turns with [face]. Use these helpers
 * rather than your own arithmetic: they follow the mirror. Foreman also copied the block data of the CustomBlockData
 * library (chunk persistent data keyed `<namespace>:x<x>y<y>z<z>`) to the destination blocks, and a move removed it
 * from the source: entries that name entities or other blocks still name the old ones.
 *
 * [entities]: the entities of the source → those at the destination. A move teleports them (each is its own UUID); a
 * paste spawns copies. In the [Kind.MOVE] back of an undo, an entity an undo put back since (as another entity) maps
 * from the one recorded to the one it is now: block data put back names the first. Entities Foreman didn't carry over (a
 * furniture its provider placed again) aren't in it.
 *
 * A [clipboard] paste copies the box as it was copied, maybe long before: the source may have changed or been cut
 * since, and its world unloaded ([sourceWorld] is then [world]). Read what you need from what the paste carried (the
 * destination's blocks and block data, the copies in [entities]), [source] only for its coordinates.
 */
class RegionCopyEvent @JvmOverloads constructor(
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
    /** Mirrored east–west (x negated about [origin]) before the turn: a mirrored paste. */
    val mirror: Boolean = false,
    /** A paste of a clipboard: of a copy taken earlier, not of what [source] holds now. */
    val clipboard: Boolean = false,
) : Event() {
    enum class Kind { PASTE, MOVE }

    /** Where the source block at [x], [y], [z] went. */
    fun block(x: Int, y: Int, z: Int): BlockVector {
        val (tx, tz) = turn((x - origin.blockX).toDouble(), (z - origin.blockZ).toDouble())
        return BlockVector(origin.blockX + offset.blockX + tx.toInt(), y + offset.blockY, origin.blockZ + offset.blockZ + tz.toInt())
    }

    /** Where a point of the source (an entity's position) went. */
    fun position(x: Double, y: Double, z: Double): Vector {
        val (tx, tz) = turn(x - (origin.blockX + 0.5), z - (origin.blockZ + 0.5))
        return Vector(origin.blockX + offset.blockX + 0.5 + tx, y + offset.blockY, origin.blockZ + offset.blockZ + 0.5 + tz)
    }

    /** An entity's yaw at the destination. */
    fun yaw(yaw: Float): Float = (if (mirror) -yaw else yaw) + rotation

    /** A face (north, north_east…, up and down stay) at the destination: an item frame's, a stored direction. */
    fun face(face: BlockFace): BlockFace {
        if (face.modX == 0 && face.modZ == 0) return face
        val (tx, tz) = turn(face.modX.toDouble(), face.modZ.toDouble())
        return BlockFace.entries.firstOrNull { it.modX == tx.toInt() && it.modY == face.modY && it.modZ == tz.toInt() } ?: face
    }

    /** An offset [dx], [dz] from the origin, mirrored then turned. */
    private fun turn(dx: Double, dz: Double): Pair<Double, Double> {
        val x = if (mirror) -dx else dx
        return when (rotation) {
            90 -> -dz to x
            180 -> -x to -dz
            270 -> dz to -x
            else -> x to dz
        }
    }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
