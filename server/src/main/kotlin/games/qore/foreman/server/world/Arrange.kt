package games.qore.foreman.server.world

import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.util.Vector
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Finds where a furniture goes in a room: back against a wall, its front free to use, clear of doors and doorways
 * and of the other furniture. Used by place_furniture's `room` placement.
 */
object Arrange {
    class Spot(val root: BlockVector3, val facing: BlockFace, val yaw: Float, val cells: List<BlockVector3>)

    /** The cells of a room: the floor cells at [at]'s level reachable from it without crossing a wall opening. */
    fun room(grid: Grid, walk: Walk, openings: List<Checks.Opening>, at: BlockVector3): Set<BlockVector3> {
        val gaps = openings.flatMap { it.run }.toSet()
        val start = BlockVector3.at(at.x(), at.y(), at.z())
        fun floor(c: BlockVector3) = walk.column(c.x(), c.z()).any { walk.nodes[it].y == c.y() && walk.nodes[it].kind == Walk.Kind.GROUND } ||
            grid.type(c.x(), c.y(), c.z()) == Material.BARRIER
        if (!floor(start)) return emptySet()
        val seen = linkedSetOf(start)
        val queue = ArrayDeque(listOf(start))
        while (queue.isNotEmpty() && seen.size < 600) {
            val c = queue.removeFirst()
            for (f in Walk.FACES) {
                val n = BlockVector3.at(c.x() + f.modX, c.y(), c.z() + f.modZ)
                if (n in seen || n in gaps || !grid.inside(n.x(), n.y(), n.z()) || !floor(n)) continue
                seen += n
                queue += n
            }
        }
        return seen
    }

    /** A footprint cell placed: the offset turned by the yaw snapped to 90°, around the root, floored ([FurnitureType.footprint]). */
    fun cell(root: BlockVector3, offset: Vector, yaw: Float): BlockVector3 {
        val angle = Math.round(yaw / 90.0) * 90.0
        if (angle == 0.0) return root.add(offset.blockX, offset.blockY, offset.blockZ)
        val rad = Math.toRadians(angle)
        val x = offset.blockX.toDouble()
        val z = offset.blockZ.toDouble()
        return BlockVector3.at(floor(x * cos(rad) - z * sin(rad) + root.x()).toInt(), root.y() + offset.blockY, floor(x * sin(rad) + z * cos(rad) + root.z()).toInt())
    }

    /** The box goes into full blocks (not furniture barriers) by more than a sliver. */
    private fun clips(grid: Grid, lo: org.joml.Vector3d, hi: org.joml.Vector3d): Boolean {
        var inside = 0.0
        for (x in floor(lo.x).toInt()..floor(hi.x - 1e-6).toInt()) for (y in floor(lo.y).toInt()..floor(hi.y - 1e-6).toInt())
            for (z in floor(lo.z).toInt()..floor(hi.z - 1e-6).toInt()) {
                if (!grid.shape(x, y, z).full || grid.type(x, y, z) == Material.BARRIER) continue
                val ox = min(hi.x, x + 1.0) - kotlin.math.max(lo.x, x.toDouble())
                val oy = min(hi.y, y + 1.0) - kotlin.math.max(lo.y, y.toDouble())
                val oz = min(hi.z, z + 1.0) - kotlin.math.max(lo.z, z.toDouble())
                // Models are often made to sink a little into the wall behind them: thin overlaps don't count.
                if (ox > 0.16 && oy > 0.16 && oz > 0.16) inside += ox * oy * oz
            }
        return inside > 0.04
    }

    fun yaw(facing: BlockFace) = when (facing) {
        BlockFace.NORTH -> 0f; BlockFace.EAST -> 90f; BlockFace.SOUTH -> 180f; else -> 270f
    }

    /**
     * The best spot in [room] for a furniture with [footprint] offsets (its root alone when empty), or null. [taken]:
     * cells of other furniture. [facing]: forced, else any side whose back is against a wall.
     */
    /**
     * [model]: the model's bounds relative to its root block's corner for each facing (when known): spots where it
     * goes into walls are refused, and "against the wall" is its back face within half a block of one.
     */
    fun find(
        grid: Grid, room: Set<BlockVector3>, openings: List<Checks.Opening>, footprint: List<Vector>, taken: Set<BlockVector3>,
        facing: BlockFace?, model: Map<BlockFace, Pair<org.joml.Vector3d, org.joml.Vector3d>> = emptyMap(),
        /** Counts why spots were refused, to tell when none is found. */
        why: MutableMap<String, Int>? = null,
    ): Spot? {
        fun no(reason: String) { why?.merge(reason, 1, Int::plus) }
        var example: String? = null
        val keep = openings.flatMap { it.keep }.toSet()
        val offsets = footprint.ifEmpty { listOf(Vector(0, 0, 0)) }
        fun wall(c: BlockVector3) = grid.solid(c.x(), c.y(), c.z()).full && grid.type(c.x(), c.y(), c.z()) != Material.BARRIER
        var best: Spot? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (root in room) for (f in facing?.let { listOf(it) } ?: Walk.FACES) {
            val yaw = yaw(f)
            val cells = offsets.map { cell(root, it, yaw) }
            val ground = cells.filter { it.y() == root.y() }
            if (ground.any { it !in room || it in keep || it in taken }) { no("floor cells outside the room, in a doorway or taken"); continue }
            if (cells.any { it.y() != root.y() && (!grid.solid(it.x(), it.y(), it.z()).empty || it in taken) }) { no("upper cells blocked"); continue }
            val back = f.oppositeFace
            // The row against the back wall, and the row in front that must stay usable.
            val backRow = ground.filter { c -> BlockVector3.at(c.x() + back.modX, c.y(), c.z() + back.modZ) !in ground }
            val frontRow = ground.filter { c -> BlockVector3.at(c.x() + f.modX, c.y(), c.z() + f.modZ) !in ground }
            val bounds = model[f]
            val against = if (bounds == null) backRow.count { wall(BlockVector3.at(it.x() + back.modX, it.y(), it.z() + back.modZ)) }
            else {
                val lo = org.joml.Vector3d(bounds.first).add(root.x().toDouble(), root.y().toDouble(), root.z().toDouble())
                val hi = org.joml.Vector3d(bounds.second).add(root.x().toDouble(), root.y().toDouble(), root.z().toDouble())
                if (clips(grid, lo, hi)) { no("model into blocks"); continue }
                // The model's back face, and the cells just behind it along its width.
                val face = when (back) { BlockFace.NORTH -> lo.z; BlockFace.SOUTH -> hi.z; BlockFace.WEST -> lo.x; else -> hi.x }
                // The first cell entirely behind the face, and how far the face is from it.
                val low = back == BlockFace.NORTH || back == BlockFace.WEST
                // Models often sink a little into the wall behind them (a back face at 278.1): up to 0.2 still counts as
                // that wall's cell.
                val behind = if (low) floor(face + 0.2).toInt() - 1 else kotlin.math.ceil(face - 0.2).toInt()
                val gap = kotlin.math.max(0.0, if (low) face - (behind + 1) else behind - face)
                if (gap > 0.6 && example == null) example = "e.g. ${Box.fmt(root)} facing ${f.name.lowercase()}: its back is %.1f from the wall".format(gap)
                if (gap > 0.6) 0 else {
                    val span = if (back == BlockFace.NORTH || back == BlockFace.SOUTH) floor(lo.x + 0.1).toInt()..floor(hi.x - 0.1).toInt()
                    else floor(lo.z + 0.1).toInt()..floor(hi.z - 0.1).toInt()
                    val walls = span.count { a -> if (back == BlockFace.NORTH || back == BlockFace.SOUTH) wall(BlockVector3.at(a, root.y(), behind)) else wall(BlockVector3.at(behind, root.y(), a)) }
                    if (walls * 2 < span.count() && example == null && span.count() > 0)
                        example = "e.g. ${Box.fmt(root)} facing ${f.name.lowercase()}: wall behind %d of its %d cells".format(walls, span.count())
                    if (walls * 2 >= span.count()) backRow.size else 0
                }
            }
            if (against * 2 < backRow.size) { no("back not against a wall"); continue }
            if (frontRow.any { c -> BlockVector3.at(c.x() + f.modX, c.y(), c.z() + f.modZ).let { it !in room || it in taken } }) { no("front not free"); continue }
            // Away from the openings, not under a window, toward the room's quiet corners.
            val distance = keep.minOfOrNull { k -> ground.minOf { abs(it.x() - k.x()) + abs(it.z() - k.z()) } }?.toDouble() ?: 10.0
            val window = backRow.count { c -> grid.type(c.x() + back.modX, c.y() + 1, c.z() + back.modZ).name.contains("GLASS") }
            val corner = ground.count { c -> Walk.FACES.count { s -> wall(BlockVector3.at(c.x() + s.modX, c.y(), c.z() + s.modZ)) } >= 2 }
            val score = min(distance, 6.0) + against * 0.5 + corner * 0.8 - window * 2.0
            if (score > bestScore) {
                bestScore = score
                best = Spot(root, f, yaw, cells)
            }
        }
        if (best == null && example != null) why?.put(example!!, 0)
        return best
    }
}
