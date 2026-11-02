package games.qore.foreman.server.world.build

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.world.Edits
import org.bukkit.block.BlockFace

/**
 * A building's own coordinates, so one plan serves every facing: u runs along the front wall, v from the front wall (0)
 * to the back (depth - 1), y is world y. [x0], [z0] is the footprint's north-west
 * corner in the world.
 */
class Frame(val x0: Int, val z0: Int, val width: Int, val depth: Int, val facing: BlockFace) {
    /** World size along x and z. */
    val sizeX = if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) width else depth
    val sizeZ = if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) depth else width
    val x1 = x0 + sizeX - 1
    val z1 = z0 + sizeZ - 1

    fun x(u: Int, v: Int) = when (facing) {
        BlockFace.SOUTH -> x0 + u
        BlockFace.NORTH -> x1 - u
        BlockFace.EAST -> x1 - v
        else -> x0 + v
    }

    fun z(u: Int, v: Int) = when (facing) {
        BlockFace.SOUTH -> z1 - v
        BlockFace.NORTH -> z0 + v
        BlockFace.EAST -> z0 + u
        else -> z1 - u
    }

    fun at(u: Int, y: Int, v: Int): BlockVector3 = BlockVector3.at(x(u, v), y, z(u, v))

    /** World direction of a local one: front (out of the front wall), back, right (+u), left (-u). */
    fun dir(local: String): BlockFace {
        val back = facing.oppositeFace
        // The way +u runs in the world.
        val right = when (facing) {
            BlockFace.SOUTH -> BlockFace.EAST; BlockFace.NORTH -> BlockFace.WEST; BlockFace.EAST -> BlockFace.SOUTH; else -> BlockFace.NORTH
        }
        return when (local) {
            "front" -> facing; "back" -> back; "right" -> right; "left" -> right.oppositeFace
            else -> throw IllegalArgumentException(local)
        }
    }
}

/** Writes blocks in a [Frame] through an edit session, caching parsed patterns. */
class Writer(val session: EditSession, val frame: Frame) {
    private val patterns = HashMap<String, Pattern>()
    var count = 0
        private set

    fun pattern(spec: String): Pattern = patterns.getOrPut(spec) { Edits.pattern(session, spec) }

    fun set(u: Int, y: Int, v: Int, block: String) {
        if (session.setBlock(frame.at(u, y, v), pattern(block))) count++
    }

    /** Fills the local box, both corners inclusive; mode walls = its 4 sides only. */
    fun fill(u0: Int, y0: Int, v0: Int, u1: Int, y1: Int, v1: Int, block: String, walls: Boolean = false) {
        val p = pattern(block)
        for (u in minOf(u0, u1)..maxOf(u0, u1)) for (v in minOf(v0, v1)..maxOf(v0, v1)) {
            if (walls && u != minOf(u0, u1) && u != maxOf(u0, u1) && v != minOf(v0, v1) && v != maxOf(v0, v1)) continue
            for (y in minOf(y0, y1)..maxOf(y0, y1)) if (session.setBlock(frame.at(u, y, v), p)) count++
        }
    }

    fun air(u0: Int, y0: Int, v0: Int, u1: Int, y1: Int, v1: Int) = fill(u0, y0, v0, u1, y1, v1, "air")

    /** A block state with its facing given locally ("front", "back", "left", "right"). */
    fun facing(block: String, local: String) = "$block[facing=${frame.dir(local).name.lowercase()}]"
}
