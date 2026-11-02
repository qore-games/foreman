package games.qore.foreman.server.world

import org.bukkit.ChunkSnapshot
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Gate
import org.bukkit.block.data.type.TrapDoor
import org.bukkit.util.BoundingBox

/** Collision of a block state, in block-local coordinates (0..1, fences reach 1.5). */
class Shape(val boxes: List<BoundingBox>) {
    val empty get() = boxes.isEmpty()
    val top = boxes.maxOfOrNull { it.maxY } ?: 0.0
    val bottom = boxes.minOfOrNull { it.minY } ?: 1.0

    /** Covers the whole cell from bottom to top: a wall. */
    val full = boxes.any { it.minX <= 0.01 && it.minZ <= 0.01 && it.maxX >= 0.99 && it.maxZ >= 0.99 && it.minY <= 0.01 && it.maxY >= 0.99 }

    companion object {
        val NONE = Shape(emptyList())
    }
}

/**
 * Chunk snapshots of an area, readable off the main thread. Shapes are computed on the main thread for each distinct
 * block state when the grid is made ([Grid.read]).
 */
class Snapshots(val world: World, minX: Int, minZ: Int, maxX: Int, maxZ: Int) {
    private val cx0 = minX shr 4
    private val cz0 = minZ shr 4
    private val cx1 = maxX shr 4
    private val cz1 = maxZ shr 4
    private val w = cx1 - cx0 + 1
    private val chunks = arrayOfNulls<ChunkSnapshot>(w * (cz1 - cz0 + 1))
    val minY = world.minHeight
    val maxY = world.maxHeight - 1
    private val air = Material.AIR.createBlockData()

    /** Main thread. */
    fun load(): Snapshots {
        Chunks.keepLoaded(world, Box(com.sk89q.worldedit.math.BlockVector3.at(cx0 shl 4, 0, cz0 shl 4), com.sk89q.worldedit.math.BlockVector3.at((cx1 shl 4) + 15, 0, (cz1 shl 4) + 15)))
        for (cx in cx0..cx1) for (cz in cz0..cz1) {
            chunks[(cz - cz0) * w + (cx - cx0)] = world.getChunkAt(cx, cz).getChunkSnapshot(true, false, false, true)
        }
        return this
    }

    private fun chunk(x: Int, z: Int): ChunkSnapshot? {
        val cx = (x shr 4) - cx0
        val cz = (z shr 4) - cz0
        if (cx < 0 || cz < 0 || cx >= w || (z shr 4) > cz1) return null
        return chunks[cz * w + cx]
    }

    fun data(x: Int, y: Int, z: Int): BlockData {
        if (y < minY || y > maxY) return air
        return chunk(x, z)?.getBlockData(x and 15, y, z and 15) ?: air
    }

    fun type(x: Int, y: Int, z: Int): Material {
        if (y < minY || y > maxY) return Material.AIR
        return chunk(x, z)?.getBlockType(x and 15, y, z and 15) ?: Material.AIR
    }

    fun skyLight(x: Int, y: Int, z: Int): Int {
        if (y > maxY) return 15
        if (y < minY) return 0
        return chunk(x, z)?.getBlockSkyLight(x and 15, y, z and 15) ?: 15
    }

    fun blockLight(x: Int, y: Int, z: Int): Int {
        if (y < minY || y > maxY) return 0
        return chunk(x, z)?.getBlockEmittedLight(x and 15, y, z and 15) ?: 0
    }

    /** Highest non-air block of the column, from the snapshot's height map. */
    fun highest(x: Int, z: Int): Int = chunk(x, z)?.getHighestBlockYAt(x and 15, z and 15) ?: minY
}

/** Blocks and collision shapes of a box, for the analyses (walking, doors, terrain). Read with [read]. */
class Grid private constructor(val box: Box, val snapshots: Snapshots) {
    private val sx = box.sizeX
    private val sy = box.sizeY
    private val sz = box.sizeZ
    private val ids = IntArray((sx.toLong() * sy * sz).toInt())
    private val states = ArrayList<BlockData>()
    private val shapes = ArrayList<Shape>()
    private val air = Material.AIR.createBlockData()

    val world get() = snapshots.world

    private fun index(x: Int, y: Int, z: Int) = ((x - box.min.x()) * sy + (y - box.min.y())) * sz + (z - box.min.z())

    fun inside(x: Int, y: Int, z: Int) = box.contains(x, y, z)

    fun data(x: Int, y: Int, z: Int): BlockData = if (inside(x, y, z)) states[ids[index(x, y, z)]] else snapshots.data(x, y, z)
    fun type(x: Int, y: Int, z: Int): Material = data(x, y, z).material

    fun shape(x: Int, y: Int, z: Int): Shape = if (inside(x, y, z)) shapes[ids[index(x, y, z)]] else Shape.NONE

    /** Doors, gates and trapdoors (a hatch over a ladder): walked through whatever their state. */
    fun openable(x: Int, y: Int, z: Int): Boolean = data(x, y, z).let { it is Door || it is Gate || it is TrapDoor }

    /** What the player collides with: openables count as open. */
    fun solid(x: Int, y: Int, z: Int): Shape = if (openable(x, y, z)) Shape.NONE else shape(x, y, z)

    companion object {
        /** Reads [box] (chunk snapshots on the main thread, then off it), plus the shapes of its block states. */
        fun read(world: World, box: Box): Grid {
            val snapshots = Sync.main { Snapshots(world, box.min.x() - 1, box.min.z() - 1, box.max.x() + 1, box.max.z() + 1).load() }
            val grid = Grid(box, snapshots)
            val known = HashMap<BlockData, Int>()
            var i = 0
            for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
                val data = snapshots.data(x, y, z)
                grid.ids[i++] = known.getOrPut(data) { grid.states.add(data); grid.states.size - 1 }
            }
            val location = Location(world, box.min.x().toDouble(), box.min.y().toDouble(), box.min.z().toDouble())
            Sync.main {
                grid.states.forEach { data -> grid.shapes += Shape(runCatching { data.getCollisionShape(location).boundingBoxes.toList() }.getOrDefault(emptyList())) }
            }
            return grid
        }

        fun isContainer(m: Material) = m == Material.CHEST || m == Material.TRAPPED_CHEST || m == Material.BARREL ||
            Tag.SHULKER_BOXES.isTagged(m) || m == Material.FURNACE || m == Material.SMOKER || m == Material.BLAST_FURNACE ||
            m == Material.DISPENSER || m == Material.DROPPER || m == Material.HOPPER || m == Material.ENDER_CHEST ||
            m == Material.DECORATED_POT || m == Material.CHISELED_BOOKSHELF || Tag.BEDS.isTagged(m)

        fun isClimbable(m: Material) = Tag.CLIMBABLE.isTagged(m) || m == Material.LADDER || m == Material.VINE ||
            m == Material.SCAFFOLDING || m == Material.TWISTING_VINES || m == Material.WEEPING_VINES
    }
}
