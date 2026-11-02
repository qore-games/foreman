package games.qore.foreman.server.world

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Stairs
import kotlin.math.abs
import kotlin.math.floor

/**
 * Where a player can stand in a [Grid] and how they move between those spots: the navigation graph the checks walk.
 *
 * A node is a standing spot (a column and the y of the feet block) with a floor height, 1.8 blocks of headroom and a
 * kind (ground, water, ladder). Moves are 4-way: a step up to 0.6 (slabs, carpets) is walked, a bottom stairs block is
 * walked up from any side but its back, up to 1.25 needs a jump, drops of any height are allowed (falls over 3 hurt).
 * Doors, gates and trapdoors count as open.
 */
class Walk(val grid: Grid) {
    enum class Kind { GROUND, WATER, CLIMB }
    enum class Move { WALK, JUMP, FALL }

    class Node(
        val x: Int, val y: Int, val z: Int, val floor: Double, val kind: Kind,
        /** A block over the head within 8: inside a build (or under a bridge, a tree…). */
        val covered: Boolean,
        /** The block the player stands on. */
        val on: Material,
        /** Facing of the bottom stairs stood on (they climb toward it), else null. */
        val stairs: BlockFace?,
    ) {
        fun at() = "[$x, $y, $z]"
        val indoor get() = covered && !Tag.LEAVES.isTagged(on)
    }

    val nodes = ArrayList<Node>()
    private val columns = HashMap<Long, IntArray>()

    // Edges in CSR form: out[start[i] until start[i + 1]] with their move.
    private lateinit var start: IntArray
    private lateinit var targets: IntArray
    private lateinit var moves: Array<Move>

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

    fun column(x: Int, z: Int): IntArray = columns[key(x, z)] ?: EMPTY

    init {
        val box = grid.box
        for (x in box.min.x()..box.max.x()) for (z in box.min.z()..box.max.z()) {
            val list = ArrayList<Int>()
            for (y in box.min.y() + 1..box.max.y() - 1) node(x, y, z)?.let { nodes += it; list += nodes.size - 1 }
            if (list.isNotEmpty()) columns[key(x, z)] = list.toIntArray()
        }
        link()
    }

    private fun node(x: Int, y: Int, z: Int): Node? {
        val feet = grid.solid(x, y, z)
        val type = grid.type(x, y, z)
        val floor: Double
        val kind: Kind
        var on: Material
        if (feet.empty) {
            val below = grid.solid(x, y - 1, z)
            on = grid.type(x, y - 1, z)
            when {
                Blocks.isLiquid(type) -> { floor = y.toDouble(); kind = Kind.WATER }
                Grid.isClimbable(type) -> { floor = y.toDouble(); kind = Kind.CLIMB }
                // Not on furniture, props or railings: nobody is meant to walk on a sofa, a barrel or a balustrade.
                below.top in 0.75..1.0 && !isProp(on) -> { floor = y - 1 + below.top; kind = Kind.GROUND }
                else -> return null
            }
        } else if (feet.top <= 0.75 && feet.bottom < 0.01) {
            floor = y + feet.top
            kind = Kind.GROUND
            on = type
        } else return null
        if (!clear(x, z, floor, floor + 1.8, y)) return null

        val standing = grid.data(x, if (floor > y) y else y - 1, z)
        val stairs = (standing as? Stairs)?.takeIf { it.half == Bisected.Half.BOTTOM }?.facing
        // Under a roof: something solid overhead. A tree's canopy and branches don't make the forest floor indoors.
        val covered = (y + 2..y + 9).any { yy -> !grid.solid(x, yy, z).empty && grid.type(x, yy, z).let { !Tag.LEAVES.isTagged(it) && !Tag.LOGS.isTagged(it) } }
        return Node(x, y, z, floor, kind, covered, on, stairs)
    }

    /** No collision between [from] and [to] in the column (the low block a floor stands on is skipped). */
    fun clear(x: Int, z: Int, from: Double, to: Double, feetY: Int = floor(from).toInt()): Boolean {
        for (yy in floor(from).toInt()..floor(to - 1e-3).toInt()) {
            val shape = grid.solid(x, yy, z)
            for (b in shape.boxes) {
                if (yy == feetY && yy + b.maxY <= from + 1e-3) continue
                if (yy + b.minY < to - 1e-3 && yy + b.maxY > from + 1e-3) return false
            }
        }
        return true
    }

    private fun link() {
        val starts = IntArray(nodes.size + 1)
        val outT = ArrayList<Int>()
        val outM = ArrayList<Move>()
        nodes.forEachIndexed { i, s ->
            starts[i] = outT.size
            for (face in FACES) {
                for (j in column(s.x + face.modX, s.z + face.modZ)) {
                    val move = move(s, nodes[j], face) ?: continue
                    outT += j
                    outM += move
                }
            }
            // Ladders and water: up and down the column.
            if (s.kind != Kind.GROUND) for (j in column(s.x, s.z)) {
                val t = nodes[j]
                if (abs(t.y - s.y) == 1) {
                    outT += j
                    outM += Move.WALK
                }
            }
            for (j in column(s.x, s.z)) {
                val t = nodes[j]
                if (t.y == s.y + 1 && t.kind != Kind.GROUND && s.kind == Kind.GROUND) {
                    outT += j
                    outM += Move.WALK
                }
            }
        }
        starts[nodes.size] = outT.size
        start = starts
        targets = outT.toIntArray()
        moves = outM.toTypedArray()
    }

    private fun move(s: Node, t: Node, face: BlockFace): Move? {
        val rise = t.floor - s.floor
        if (rise > 0) {
            val limit = when {
                s.kind == Kind.WATER && grid.type(s.x, s.y + 1, s.z).let { !Blocks.isLiquid(it) } -> 1.25
                s.kind == Kind.CLIMB -> 1.25
                else -> 0.6
            }
            val stairs = t.stairs != null && rise <= 1.0 + 1e-3 && face != t.stairs.oppositeFace
            if (rise <= limit + 1e-3 || stairs) return if (limit > 0.6 || rise <= 0.6 + 1e-3 || stairs) Move.WALK else Move.JUMP
            if (rise <= 1.25 + 1e-3 && clear(s.x, s.z, s.floor, t.floor + 1.8)) return Move.JUMP
            return null
        }
        // Down or level: the player moves over at their own height, then falls.
        if (!clear(t.x, t.z, t.floor, s.floor + 1.8, t.y)) return null
        return if (-rise > 3.0 + 1e-3) Move.FALL else Move.WALK
    }

    inline fun edges(i: Int, block: (Int, Move) -> Unit) {
        for (e in edgeStart(i) until edgeStart(i + 1)) block(edgeTarget(e), edgeMove(e))
    }

    fun edgeStart(i: Int) = start[i]
    fun edgeTarget(e: Int) = targets[e]
    fun edgeMove(e: Int) = moves[e]

    /** Nodes reachable from [seeds] with the [allowed] moves. */
    fun reach(seeds: Collection<Int>, allowed: Set<Move> = Move.entries.toSet()): BooleanArray {
        val seen = BooleanArray(nodes.size)
        val queue = ArrayDeque<Int>()
        seeds.forEach { if (!seen[it]) { seen[it] = true; queue.add(it) } }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            edges(i) { j, m -> if (m in allowed && !seen[j]) { seen[j] = true; queue.add(j) } }
        }
        return seen
    }

    /** Nodes from which one of [seeds] can be reached (any move). */
    fun reachBack(seeds: Collection<Int>): BooleanArray {
        val incoming = Array(nodes.size) { ArrayList<Int>(4) }
        for (i in nodes.indices) edges(i) { j, _ -> incoming[j] += i }
        val seen = BooleanArray(nodes.size)
        val queue = ArrayDeque<Int>()
        seeds.forEach { if (!seen[it]) { seen[it] = true; queue.add(it) } }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            for (j in incoming[i]) if (!seen[j]) { seen[j] = true; queue.add(j) }
        }
        return seen
    }

    /** Connected groups (edges taken both ways) of the nodes [member] selects. */
    fun groups(member: (Int) -> Boolean): List<List<Int>> {
        val neighbours = Array(nodes.size) { ArrayList<Int>(4) }
        for (i in nodes.indices) if (member(i)) edges(i) { j, _ -> if (member(j)) { neighbours[i] += j; neighbours[j] += i } }
        val seen = BooleanArray(nodes.size)
        val out = ArrayList<List<Int>>()
        for (i in nodes.indices) {
            if (seen[i] || !member(i)) continue
            val group = ArrayList<Int>()
            val queue = ArrayDeque(listOf(i))
            seen[i] = true
            while (queue.isNotEmpty()) {
                val k = queue.removeFirst()
                group += k
                for (j in neighbours[k]) if (!seen[j]) { seen[j] = true; queue.add(j) }
            }
            out += group
        }
        return out.sortedByDescending { it.size }
    }

    /** The node of column (x, z) whose floor is closest to [y] (a block position: its feet y), within [range]. */
    fun nearest(x: Int, y: Int, z: Int, range: Int = 3): Int? =
        column(x, z).filter { abs(nodes[it].y - y) <= range }.minByOrNull { abs(nodes[it].y - y) }

    /** Outdoor nodes on the box's sides: where players come from when no start is given. */
    fun border(): List<Int> {
        val b = grid.box
        return nodes.indices.filter {
            val n = nodes[it]
            !n.covered && (n.x == b.min.x() || n.x == b.max.x() || n.z == b.min.z() || n.z == b.max.z())
        }
    }

    companion object {
        private val EMPTY = IntArray(0)

        /** Furniture, containers, railings and other things stood on only by climbing them. */
        fun isProp(m: Material) = m == Material.BARRIER || Grid.isContainer(m) || m == Material.IRON_BARS || m.name.endsWith("_PANE") ||
            m == Material.BOOKSHELF || m == Material.CAULDRON || m == Material.LECTERN || m.name.endsWith("ANVIL") ||
            m == Material.CRAFTING_TABLE || m == Material.IRON_BLOCK || m == Material.SMOKER || m == Material.SEA_LANTERN
        val FACES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
    }
}
