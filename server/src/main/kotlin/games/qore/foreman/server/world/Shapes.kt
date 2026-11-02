package games.qore.foreman.server.world

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockSupport
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.bukkit.block.data.type.Wall
import java.util.Random

/**
 * Block shapes WorldEdit leaves as placed: it sets states without physics, so panes, fences and bars stay unconnected
 * posts, walls lose their arms and stairs never turn corners. [connect] redoes what vanilla neighbour updates would.
 * Also the roof generator, which places stairs whose facing is easy to get wrong by hand.
 */
object Shapes {
    private val SIDES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

    /** Main thread, after the edit was flushed to the world. Rewrites the connectable blocks of [box]; returns how many changed. */
    fun connect(world: World, session: EditSession, box: Box): Int {
        var changed = 0
        for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
            val data = world.getBlockData(x, y, z)
            val fixed = when {
                data is Stairs -> stairShape(world, x, y, z, data)
                data is Wall -> wall(world, x, y, z, data)
                data is MultipleFacing && isPaneLike(data.material) -> sides(world, x, y, z, data) { isPaneLike(it.material) || Tag.WALLS.isTagged(it.material) }
                data is MultipleFacing && Tag.FENCES.isTagged(data.material) -> sides(world, x, y, z, data) { n ->
                    Tag.FENCE_GATES.isTagged(n.material) ||
                        (Tag.FENCES.isTagged(n.material) && Tag.WOODEN_FENCES.isTagged(n.material) == Tag.WOODEN_FENCES.isTagged(data.material))
                }
                else -> null
            } ?: continue
            if (fixed == data) continue
            session.setBlock(BlockVector3.at(x, y, z), BukkitAdapter.adapt(fixed))
            changed++
        }
        return changed
    }

    private fun isPaneLike(m: Material) = m == Material.IRON_BARS || m.name.endsWith("_PANE")

    /** Leaves, barriers and gourds never take connections (vanilla's exception list). */
    private fun solidSide(n: BlockData, face: BlockFace): Boolean =
        !Tag.LEAVES.isTagged(n.material) && n.material != Material.BARRIER && n.material != Material.MELON &&
            n.material != Material.PUMPKIN && n.material != Material.CARVED_PUMPKIN && n.material != Material.JACK_O_LANTERN &&
            !Tag.SHULKER_BOXES.isTagged(n.material) && n.isFaceSturdy(face.oppositeFace, BlockSupport.FULL)

    private fun sides(world: World, x: Int, y: Int, z: Int, data: MultipleFacing, joins: (BlockData) -> Boolean): BlockData {
        val fixed = data.clone() as MultipleFacing
        SIDES.filter { it in fixed.allowedFaces }.forEach { face ->
            val n = world.getBlockData(x + face.modX, y, z + face.modZ)
            fixed.setFace(face, joins(n) || solidSide(n, face))
        }
        return fixed
    }

    private fun wall(world: World, x: Int, y: Int, z: Int, data: Wall): BlockData {
        val fixed = data.clone() as Wall
        val above = world.getBlockData(x, y + 1, z)
        val connected = SIDES.filter { face ->
            val n = world.getBlockData(x + face.modX, y, z + face.modZ)
            val joins = Tag.WALLS.isTagged(n.material) || isPaneLike(n.material) || Tag.FENCE_GATES.isTagged(n.material) || solidSide(n, face)
            // A side is tall when the block above covers it, like a wall under a wall.
            fixed.setHeight(face, if (!joins) Wall.Height.NONE else if (Tag.WALLS.isTagged(above.material) || above.isFaceSturdy(BlockFace.DOWN, BlockSupport.FULL)) Wall.Height.TALL else Wall.Height.LOW)
            joins
        }
        val straight = connected.size == 2 && connected[0].oppositeFace == connected[1]
        fixed.isUp = !straight || Tag.WALLS.isTagged(above.material) && (above as? Wall)?.isUp == true
        return fixed
    }

    private fun ccw(face: BlockFace) = when (face) {
        BlockFace.NORTH -> BlockFace.WEST
        BlockFace.WEST -> BlockFace.SOUTH
        BlockFace.SOUTH -> BlockFace.EAST
        else -> BlockFace.NORTH
    }

    /** Vanilla StairBlock.getStairsShape: outer corner from the stair in front, inner corner from the one behind. */
    private fun stairShape(world: World, x: Int, y: Int, z: Int, data: Stairs): BlockData {
        fun stairAt(face: BlockFace) = world.getBlockData(x + face.modX, y, z + face.modZ) as? Stairs
        fun canTake(face: BlockFace) = stairAt(face).let { it == null || it.facing != data.facing || it.half != data.half }
        val facing = data.facing
        val fixed = data.clone() as Stairs
        val front = stairAt(facing)
        val back = stairAt(facing.oppositeFace)
        fixed.shape = when {
            front != null && front.half == data.half && isPerpendicular(front.facing, facing) && canTake(front.facing.oppositeFace) ->
                if (front.facing == ccw(facing)) Stairs.Shape.OUTER_LEFT else Stairs.Shape.OUTER_RIGHT
            back != null && back.half == data.half && isPerpendicular(back.facing, facing) && canTake(back.facing) ->
                if (back.facing == ccw(facing)) Stairs.Shape.INNER_LEFT else Stairs.Shape.INNER_RIGHT
            else -> Stairs.Shape.STRAIGHT
        }
        return fixed
    }

    private fun isPerpendicular(a: BlockFace, b: BlockFace) = (a.modX == 0) != (b.modX == 0)

    // ---- Roofs --------------------------------------------------------------------------------------------------

    /** A weighted material list, `70%dark_oak_stairs,30%spruce_stairs` or `dark_oak_stairs`. */
    class Palette(spec: String, private val random: Random, check: (Material) -> Boolean, kind: String) {
        private val entries: List<Pair<Material, Double>> = spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }.map { token ->
            val weight = token.substringBefore('%', "").toDoubleOrNull()
            val id = token.substringAfter('%').removePrefix("minecraft:")
            val m = Material.matchMaterial(id) ?: throw ToolError("Unknown block '$id'")
            if (!check(m)) throw ToolError("'$id' is not $kind")
            m to (weight ?: 1.0)
        }
        private val total = entries.sumOf { it.second }
        val first get() = entries.first().first

        fun pick(): Material {
            var r = random.nextDouble() * total
            for ((m, w) in entries) {
                r -= w
                if (r <= 0) return m
            }
            return entries.last().first
        }
    }

    class Roof(
        val x0: Int, val z0: Int, val x1: Int, val z1: Int, val base: Int,
        val style: String, val alongX: Boolean, val high: BlockFace,
        val stairs: Palette, val slab: Palette, val ridge: com.sk89q.worldedit.function.pattern.Pattern?,
        val gable: com.sk89q.worldedit.function.pattern.Pattern?, val overhang: Int, val eaves: Boolean,
    )

    fun roof(session: EditSession, r: Roof): Int {
        var placed = 0
        fun stair(x: Int, y: Int, z: Int, facing: BlockFace, top: Boolean = false) {
            val data = Bukkit.createBlockData(r.stairs.pick()) as Stairs
            data.facing = facing
            data.half = if (top) Bisected.Half.TOP else Bisected.Half.BOTTOM
            session.setBlock(BlockVector3.at(x, y, z), BukkitAdapter.adapt(data))
            placed++
        }
        fun ridge(x: Int, y: Int, z: Int) {
            val pos = BlockVector3.at(x, y, z)
            if (r.ridge != null) session.setBlock(pos, r.ridge)
            else session.setBlock(pos, BukkitAdapter.adapt((Bukkit.createBlockData(r.slab.pick()) as Slab).apply { type = Slab.Type.BOTTOM }))
            placed++
        }
        fun gable(x: Int, y: Int, z: Int) {
            if (r.gable == null || x !in r.x0..r.x1 || z !in r.z0..r.z1) return
            session.setBlock(BlockVector3.at(x, y, z), r.gable)
            placed++
        }

        val o = r.overhang
        val ox0 = r.x0 - o
        val ox1 = r.x1 + o
        val oz0 = r.z0 - o
        val oz1 = r.z1 + o

        when (r.style) {
            "gable" -> {
                // Slopes on the two long sides, rows run along the ridge. Written for a ridge along x, mirrored for z.
                val (a0, a1) = if (r.alongX) ox0 to ox1 else oz0 to oz1
                val (b0, b1) = if (r.alongX) oz0 to oz1 else ox0 to ox1
                val (lowFacing, highFacing) = if (r.alongX) BlockFace.SOUTH to BlockFace.NORTH else BlockFace.EAST to BlockFace.WEST
                fun at(a: Int, b: Int, y: Int, f: (Int, Int, Int) -> Unit) = if (r.alongX) f(a, y, b) else f(b, y, a)
                var k = 0
                while (b0 + k <= b1 - k) {
                    val y = r.base + k
                    val n = b0 + k
                    val s = b1 - k
                    for (a in a0..a1) {
                        if (n == s) at(a, n, y) { x, yy, z -> ridge(x, yy, z) }
                        else {
                            at(a, n, y) { x, yy, z -> stair(x, yy, z, lowFacing) }
                            at(a, s, y) { x, yy, z -> stair(x, yy, z, highFacing) }
                        }
                    }
                    // Gable ends: the triangle of wall under the slopes, on the end walls.
                    for (b in n + 1 until s) for (end in listOf(if (r.alongX) r.x0 else r.z0, if (r.alongX) r.x1 else r.z1)) {
                        at(end, b, y) { x, yy, z -> gable(x, yy, z) }
                    }
                    k++
                }
                if (r.eaves && o > 0) for (a in a0..a1) {
                    at(a, b0, r.base - 1) { x, yy, z -> stair(x, yy, z, lowFacing, top = true) }
                    at(a, b1, r.base - 1) { x, yy, z -> stair(x, yy, z, highFacing, top = true) }
                }
            }

            "hip" -> {
                var k = 0
                while (true) {
                    val rx0 = ox0 + k
                    val rx1 = ox1 - k
                    val rz0 = oz0 + k
                    val rz1 = oz1 - k
                    val y = r.base + k
                    if (rx0 > rx1 || rz0 > rz1) break
                    if (rx0 == rx1 || rz0 == rz1) {
                        for (x in rx0..rx1) for (z in rz0..rz1) ridge(x, y, z)
                        break
                    }
                    for (x in rx0..rx1) {
                        stair(x, y, rz0, BlockFace.SOUTH)
                        stair(x, y, rz1, BlockFace.NORTH)
                    }
                    for (z in rz0 + 1 until rz1) {
                        stair(rx0, y, z, BlockFace.EAST)
                        stair(rx1, y, z, BlockFace.WEST)
                    }
                    k++
                }
                if (r.eaves && o > 0) {
                    for (x in ox0..ox1) {
                        stair(x, r.base - 1, oz0, BlockFace.SOUTH, top = true)
                        stair(x, r.base - 1, oz1, BlockFace.NORTH, top = true)
                    }
                    for (z in oz0 + 1 until oz1) {
                        stair(ox0, r.base - 1, z, BlockFace.EAST, top = true)
                        stair(ox1, r.base - 1, z, BlockFace.WEST, top = true)
                    }
                }
            }

            "shed" -> {
                // One slope climbing toward [high]: rows across it from the low edge to the high edge.
                val alongZ = r.high == BlockFace.NORTH || r.high == BlockFace.SOUTH
                val (lo, hi) = when (r.high) {
                    BlockFace.SOUTH -> oz0 to oz1
                    BlockFace.NORTH -> oz1 to oz0
                    BlockFace.EAST -> ox0 to ox1
                    else -> ox1 to ox0
                }
                val step = if (hi >= lo) 1 else -1
                var k = 0
                var c = lo
                while (true) {
                    val y = r.base + k
                    if (alongZ) {
                        for (x in ox0..ox1) stair(x, y, c, r.high)
                        for (x in listOf(r.x0, r.x1)) {
                            var b = c + step
                            while (b != hi + step) { gable(x, y, b); b += step }
                        }
                    } else {
                        for (z in oz0..oz1) stair(c, y, z, r.high)
                        for (z in listOf(r.z0, r.z1)) {
                            var b = c + step
                            while (b != hi + step) { gable(b, y, z); b += step }
                        }
                    }
                    if (c == hi) break
                    c += step
                    k++
                }
                if (r.eaves && o > 0) {
                    if (alongZ) for (x in ox0..ox1) stair(x, r.base - 1, lo, r.high, top = true)
                    else for (z in oz0..oz1) stair(lo, r.base - 1, z, r.high, top = true)
                }
            }

            else -> throw ToolError("style must be gable, hip or shed")
        }
        return placed
    }
}
