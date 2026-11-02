package games.qore.foreman.server.world

import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The inspection behind the `check` tool: measures a built area the way players will meet it and lists what is wrong,
 * with coordinates. Each check is independent; [ALL] runs them all.
 */
object Checks {
    val ALL = listOf("walk", "doors", "ground", "terrain", "roads", "furniture", "floating", "claims")

    enum class Severity { ERROR, WARNING }

    class Issue(val check: String, val severity: Severity, val x: Int, val y: Int, val z: Int, val text: String) {
        fun at() = "[$x, $y, $z]"
    }

    /** A building found in the area: connected columns with built blocks two or more above the ground. */
    class Structure(val number: Int, val columns: Set<Long>, val box: Box, val floor: Int?, val doors: Int) {
        fun describe() = "#$number ${box.describe()}" + (floor?.let { ", ground floor at y=$it" } ?: "") + ", $doors door${if (doors == 1) "" else "s"}"
    }

    class Options(
        val seeds: List<BlockVector3> = emptyList(),
        val targets: List<BlockVector3> = emptyList(),
        val roads: (Material) -> Boolean = DEFAULT_ROADS,
        val maxGrade: Double = 0.5,
        val claims: List<games.qore.foreman.server.world.Area> = emptyList(),
    )

    /** A 1-block step a player has to jump: [at] is the lower spot, [toward] the way up. */
    class Step(val at: BlockVector3, val toward: BlockFace)

    class Report(
        val box: Box, val issues: List<Issue>, val structures: List<Structure>, val notes: List<String>,
        val hingeFixes: List<Pair<BlockVector3, Door.Hinge>>, val steps: List<Step>,
    )

    /** Paving and dirt road surfaces. Natural stone is left out: rock outcrops and cuttings are not roads. */
    val DEFAULT_ROADS: (Material) -> Boolean = { m ->
        m == Material.DIRT_PATH || m == Material.GRAVEL || m == Material.COARSE_DIRT || m == Material.PACKED_MUD ||
            m.name.endsWith("_CONCRETE") || m.name.endsWith("_CONCRETE_POWDER") || m == Material.POLISHED_ANDESITE ||
            m == Material.COBBLESTONE || m == Material.MOSSY_COBBLESTONE || m == Material.STONE_BRICKS || m == Material.SMOOTH_STONE ||
            m == Material.POLISHED_BLACKSTONE || m == Material.DEEPSLATE_TILES || m == Material.COBBLED_DEEPSLATE || m.name.endsWith("_TERRACOTTA")
    }

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
    private fun kx(k: Long) = (k shr 32).toInt()
    private fun kz(k: Long) = k.toInt()

    /** Per column: the natural ground surface, the top block and whether water covers it. */
    private class Terrain(val grid: Grid) {
        val ground = HashMap<Long, Int>()
        val top = HashMap<Long, Int>()
        val topType = HashMap<Long, Material>()
        val water = HashSet<Long>()

        init {
            val b = grid.box
            for (x in b.min.x()..b.max.x()) for (z in b.min.z()..b.max.z()) {
                val k = key(x, z)
                var liquid = false
                var y = b.max.y()
                while (y >= b.min.y()) {
                    val m = grid.type(x, y, z)
                    if (Blocks.isLiquid(m)) liquid = true
                    else if (!Blocks.isCover(m)) break
                    y--
                }
                if (y < b.min.y()) continue
                top[k] = y
                topType[k] = grid.type(x, y, z)
                if (liquid) water += k
                var g = y
                while (g >= b.min.y() && !Blocks.isNaturalGround(grid.type(x, g, z))) g--
                if (g >= b.min.y()) ground[k] = g
            }
        }
    }

    fun run(grid: Grid, furniture: List<Furniture.Placed>, shown: List<Displays.Shown>, checks: Set<String>, options: Options): Report {
        val issues = ArrayList<Issue>()
        val notes = ArrayList<String>()
        val hinges = ArrayList<Pair<BlockVector3, Door.Hinge>>()
        val steps = ArrayList<Step>()
        val walk = Walk(grid)
        val terrain = Terrain(grid)
        val structures = structures(grid, terrain, walk)
        val visuals = shown.associate { it.id to it.baked }
        val barriers = HashMap<Long, Furniture.Placed>()
        furniture.forEach { f -> f.cells.forEach { c -> barriers[cellKey(c.x(), c.y(), c.z())] = f } }

        if ("walk" in checks) walk(grid, walk, furniture, visuals, options, issues, notes, steps)
        if ("doors" in checks) doors(grid, walk, furniture, visuals, barriers, issues, hinges)
        if ("ground" in checks) ground(grid, terrain, structures, issues)
        if ("terrain" in checks) cliffs(terrain, structures, issues)
        if ("roads" in checks) roads(grid, terrain, structures, options, issues)
        if ("furniture" in checks) furniture(grid, furniture, visuals, issues)
        if ("floating" in checks) floating(grid, issues)
        if ("claims" in checks) claims(grid.box, options.claims, issues)
        return Report(grid.box, issues, structures, notes, hinges, steps)
    }

    private fun cellKey(x: Int, y: Int, z: Int) = (x.toLong() and 0x3ffffff shl 38) or ((y.toLong() and 0xfff) shl 26) or (z.toLong() and 0x3ffffff)

    // ---- Structures ---------------------------------------------------------------------------------------------

    private fun structures(grid: Grid, terrain: Terrain, walk: Walk): List<Structure> {
        val built = HashSet<Long>()
        val b = grid.box
        for ((k, g) in terrain.ground) {
            val x = kx(k); val z = kz(k)
            val topType = terrain.topType[k]
            val tree = topType != null && Tag.LEAVES.isTagged(topType)
            for (y in g + 2..min(b.max.y(), (terrain.top[k] ?: g))) {
                val m = grid.type(x, y, z)
                if (Blocks.isCover(m) || Blocks.isNaturalGround(m) || m == Material.BARRIER || Blocks.isLiquid(m)) continue
                if (tree && (Tag.LOGS.isTagged(m) || Tag.LEAVES.isTagged(m))) continue
                built += k
                break
            }
        }
        val seen = HashSet<Long>()
        val out = ArrayList<Structure>()
        for (k in built) {
            if (k in seen) continue
            val group = HashSet<Long>()
            val queue = ArrayDeque(listOf(k))
            seen += k
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                group += c
                for (dx in -1..1) for (dz in -1..1) {
                    val n = key(kx(c) + dx, kz(c) + dz)
                    if (n in built && seen.add(n)) queue.add(n)
                }
            }
            if (group.size < 9) continue
            val xs = group.map { kx(it) }
            val zs = group.map { kz(it) }
            val ys = group.mapNotNull { terrain.ground[it] }
            val tops = group.mapNotNull { terrain.top[it] }
            var doors = 0
            val doorYs = ArrayList<Int>()
            for (c in group) for (y in b.min.y()..b.max.y()) {
                val d = grid.data(kx(c), y, kz(c))
                if (d is Door && d.half == Bisected.Half.BOTTOM) { doors++; doorYs += y }
            }
            // The ground floor: the lowest door level at or above the ground around (not a basement), else the lowest
            // level with many indoor spots.
            val around = HashSet<Int>()
            for (c in group) for ((dx, dz) in listOf(4 to 0, -4 to 0, 0 to 4, 0 to -4)) {
                val o = key(kx(c) + dx, kz(c) + dz)
                if (o !in built) terrain.ground[o]?.let { around += it }
            }
            val ring = around.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            val floor = doorYs.filter { ring == null || it >= ring }.minOrNull() ?: doorYs.minOrNull() ?: group.flatMap { c -> walk.column(kx(c), kz(c)).map { walk.nodes[it] }.filter { it.indoor && !Blocks.isNaturalGround(it.on) } }
                .groupingBy { it.y }.eachCount().filter { it.value >= max(4, group.size / 7) }.keys.minOrNull()
            out += Structure(
                0, group,
                Box(BlockVector3.at(xs.min(), ys.minOrNull() ?: b.min.y(), zs.min()), BlockVector3.at(xs.max(), tops.maxOrNull() ?: b.max.y(), zs.max())),
                floor, doors,
            )
        }
        return out.sortedBy { it.box.min.x() * 100000 + it.box.min.z() }.mapIndexed { i, s -> Structure(i + 1, s.columns, s.box, s.floor, s.doors) }
    }

    // ---- Walking --------------------------------------------------------------------------------------------------

    private fun walk(
        grid: Grid, walk: Walk, furniture: List<Furniture.Placed>, visuals: Map<java.util.UUID, Baked>, options: Options,
        issues: MutableList<Issue>, notes: MutableList<String>, steps: MutableList<Step>,
    ) {
        val seeds = if (options.seeds.isNotEmpty()) options.seeds.mapNotNull { walk.nearest(it.x(), it.y(), it.z(), 4) } else walk.border()
        if (seeds.isEmpty()) {
            issues += Issue("walk", Severity.ERROR, grid.box.min.x(), grid.box.min.y(), grid.box.min.z(), "No outdoor standing spot on the box's sides to start from: grow the box past the build or pass `from`")
            return
        }
        notes += "Walked from ${if (options.seeds.isNotEmpty()) "${seeds.size} start point(s)" else "${seeds.size} outdoor spots on the box's sides"}: ${walk.nodes.size} standing spots."
        val all = walk.reach(seeds)
        val walking = walk.reach(seeds, setOf(Walk.Move.WALK, Walk.Move.FALL))
        val back = walk.reachBack(seeds)
        val n = walk.nodes

        // Rooms and terrain nobody can get to. Air pockets inside the ground are their own problem.
        val surface = HashMap<Long, Int>()
        for (node in n) {
            val k = key(node.x, node.z)
            if (k !in surface) surface[k] = (grid.box.max.y downTo grid.box.min.y).firstOrNull { y -> Blocks.isNaturalGround(grid.type(node.x, y, node.z)) } ?: Int.MIN_VALUE
        }
        fun buried(i: Int) = n[i].y < surface.getValue(key(n[i].x, n[i].z)) && Blocks.isNaturalGround(n[i].on)
        var shown = 0
        for (g in walk.groups { !all[it] && buried(it) }) {
            if (g.size < 4 || shown++ >= 6) continue
            val c = centre(walk, g)
            issues += Issue("terrain", Severity.WARNING, c.x, c.y, c.z, "Hollow ground: an air pocket of ${g.size} spots under the surface (${extent(walk, g)}), left by terraforming? Fill it")
        }
        shown = 0
        // The floor under a furniture model (a crate whose model reaches back to the wall) is not a place to stand.
        val models = visuals.values.filter { !it.empty }
        fun underModel(i: Int) = n[i].let { a -> models.any { overlaps(it.min, it.max, BlockVector3.at(a.x, a.y, a.z), 0.5) } }
        for (all0 in walk.groups { !all[it] && !buried(it) }) {
            val g = if (models.isEmpty()) all0 else all0.filter { !underModel(it) }
            if (g.size < 4) continue
            val indoor = g.count { n[it].indoor }
            val natural = g.count { Blocks.isNaturalGround(n[it].on) }
            val what = when {
                indoor * 2 >= g.size -> "rooms or floors"
                natural * 2 >= g.size -> "ground (cliffs all around)"
                else -> continue
            }
            if (shown++ >= 12) continue
            val c = centre(walk, g)
            // A closed space with nothing in it (an attic, a gap between walls) is a warning; one with doors, furniture or
            // containers is a room players are meant to use.
            val cells = g.map { n[it] }
            val used = cells.any { a ->
                (-1..1).any { dx -> (-1..1).any { dz -> (0..1).any { dy ->
                    val t = grid.data(a.x + dx, a.y + dy, a.z + dz)
                    t is Door || t.material == Material.BARRIER || Grid.isContainer(t.material)
                } } }
            }
            // A few cells of ground walled in with nothing in them: a grave plot, a planter, a flower bed.
            val plot = !used && what.startsWith("ground") && g.size <= 8
            issues += Issue(
                "walk", if (used || (what.startsWith("ground") && !plot)) Severity.ERROR else Severity.WARNING, c.x, c.y, c.z,
                "${g.size} standing spots of $what can't be reached from outside: ${extent(walk, g)}" +
                    if (plot) " (a walled-in plot: a grave or a planter?)" else if (!used && !what.startsWith("ground")) " (empty: an attic or closed void?)" else "",
            )
        }

        // Indoors, a 1-block step without stairs: walked only by jumping.
        val jumpFrom = HashMap<Int, Int>()
        for (i in n.indices) if (walking[i]) walk.edges(i) { j, m -> if (m == Walk.Move.JUMP && !walking[j]) jumpFrom.putIfAbsent(j, i) }
        shown = 0
        // A spot between two walls (a window opening, a niche) is not a room: windowsills are fine.
        // A ledge: on both sides across it, nothing to stand on at its level (a windowsill between the room and the drop).
        // The top of a counter or table (a top slab, upside-down stairs, a prop) is furniture, not a floor to step up to;
        // a window over a counter is still a niche.
        fun counter(i: Int) = n[i].let { a ->
            val below = grid.data(a.x, floor(a.floor - 0.01).toInt(), a.z)
            (below is Slab && below.type == Slab.Type.TOP) || (below is Stairs && below.half == Bisected.Half.TOP) || Walk.isProp(below.material)
        }
        fun level(x: Int, z: Int, floor: Double) = walk.column(x, z).any { abs(n[it].floor - floor) < 0.6 && !counter(it) }
        fun niche(i: Int) = n[i].let { a ->
            listOf(BlockFace.EAST to BlockFace.WEST, BlockFace.NORTH to BlockFace.SOUTH).any { (p, q) ->
                !level(a.x + p.modX, a.z + p.modZ, a.floor) && !level(a.x + q.modX, a.z + q.modZ, a.floor)
            }
        }
        for (g in walk.groups { all[it] && !walking[it] && n[it].indoor }) {
            if (g.size < 2 || (g.size <= 3 && g.all { niche(it) }) || (g.size <= 12 && g.all { niche(it) || counter(it) }) || shown++ >= 10) continue
            val entry = g.firstNotNullOfOrNull { j -> jumpFrom[j]?.let { i -> n[i] to n[j] } }
            // Reached through a jump outdoors (a terrace, a 1-block rise of the terrain): normal ground, not a step to fix.
            if (entry == null) continue
            val (from, to) = entry
            // The fix: a stairs block on the lower spot, climbing toward the higher one (when that spot is free).
            for (j in g) {
                val i = jumpFrom[j] ?: continue
                val a = n[i]; val b = n[j]
                val face = Walk.FACES.firstOrNull { it.modX == b.x - a.x && it.modZ == b.z - a.z } ?: continue
                // Only where it makes a step: the spot before it (its low side) at the same level, room above it.
                val before = walk.column(a.x - face.modX, a.z - face.modZ).any { abs(n[it].floor - a.floor) < 0.1 }
                if (b.floor - a.floor in 0.9..1.1 && grid.type(a.x, a.y, a.z).isAir && before && walk.clear(a.x, a.z, a.floor + 1, a.floor + 2.9, a.y + 1))
                    steps += Step(BlockVector3.at(a.x, a.y, a.z), face)
            }
            issues += Issue(
                "walk", Severity.ERROR, to.x, to.y, to.z,
                "Indoors, ${g.size} spots are only reached by jumping up ${"%.1f".format(to.floor - from.floor)} from ${from.at()}: add a stair or slab step (${extent(walk, g)})",
            )
        }

        // Places you can drop into and not climb out of.
        shown = 0
        for (g in walk.groups { all[it] && !back[it] }) {
            if (g.size < 2 || shown++ >= 10) continue
            val c = centre(walk, g)
            val wet = g.any { n[it].kind == Walk.Kind.WATER }
            issues += Issue(
                "walk", Severity.ERROR, c.x, c.y, c.z,
                "${g.size} spots ${if (wet) "in water " else ""}can be dropped into but not left (banks or walls too high to climb): ${extent(walk, g)}",
            )
        }

        // Things players must reach: containers, beds, interactive furniture, the given targets.
        val targets = ArrayList<Pair<String, BlockVector3>>()
        val b = grid.box
        for (x in b.min.x()..b.max.x()) for (y in b.min.y()..b.max.y()) for (z in b.min.z()..b.max.z()) {
            val m = grid.type(x, y, z)
            if (Grid.isContainer(m)) targets += Blocks.id(m) to BlockVector3.at(x, y, z)
        }
        furniture.filter { it.interactive }.forEach { targets += it.id to BlockVector3.at(it.x, it.y, it.z) }
        options.targets.forEach { targets += "target" to it }
        var unreachable = 0
        for ((what, t) in targets) {
            val ok = (-2..2).any { dx -> (-2..2).any { dz -> walk.column(t.x() + dx, t.z() + dz).any { i -> all[i] && n[i].floor in t.y() - 1.6..t.y() + 0.7 } } }
            if (ok) continue
            if (unreachable++ < 15) issues += Issue("walk", Severity.ERROR, t.x(), t.y(), t.z(), "$what can't be reached: no walkable spot next to it at its level")
        }
        if (unreachable > 15) notes += "${unreachable - 15} more unreachable containers/furniture not listed."
    }

    private fun centre(walk: Walk, g: List<Int>): Walk.Node {
        val cx = g.map { walk.nodes[it].x }.average()
        val cz = g.map { walk.nodes[it].z }.average()
        return walk.nodes[g.minBy { abs(walk.nodes[it].x - cx) + abs(walk.nodes[it].z - cz) }]
    }

    private fun extent(walk: Walk, g: List<Int>): String {
        val ns = g.map { walk.nodes[it] }
        val ys = ns.map { it.y }
        return "x ${ns.minOf { it.x }}..${ns.maxOf { it.x }}, z ${ns.minOf { it.z }}..${ns.maxOf { it.z }}, y ${ys.min()}" + (if (ys.max() != ys.min()) "..${ys.max()}" else "")
    }

    // ---- Doors ----------------------------------------------------------------------------------------------------

    private fun clockwise(f: BlockFace) = when (f) {
        BlockFace.NORTH -> BlockFace.EAST; BlockFace.EAST -> BlockFace.SOUTH; BlockFace.SOUTH -> BlockFace.WEST; else -> BlockFace.NORTH
    }

    private fun doors(
        grid: Grid, walk: Walk, furniture: List<Furniture.Placed>, visuals: Map<java.util.UUID, Baked>,
        barriers: Map<Long, Furniture.Placed>, issues: MutableList<Issue>, hinges: MutableList<Pair<BlockVector3, Door.Hinge>>,
    ) {
        val b = grid.box
        for (x in b.min.x()..b.max.x()) for (y in b.min.y()..b.max.y()) for (z in b.min.z()..b.max.z()) {
            val d = grid.data(x, y, z) as? Door ?: continue
            if (d.half != Bisected.Half.BOTTOM) continue
            val f = d.facing
            val floor = walk.column(x, z).map { walk.nodes[it] }.firstOrNull { it.y == y }?.floor
            if (floor == null) {
                issues += Issue("doors", Severity.ERROR, x, y, z, "Door with no floor to stand on in its opening")
                continue
            }
            for (side in listOf(f, f.oppositeFace)) {
                val sx = x + side.modX
                val sz = z + side.modZ
                val best = walk.column(sx, sz).map { walk.nodes[it] }.filter { abs(it.y - y) <= 3 }.minByOrNull { abs(it.floor - floor) }
                if (best != null && abs(best.floor - floor) <= 0.6) continue
                val blocker = (y..y + 1).firstNotNullOfOrNull { yy ->
                    barriers[cellKey(sx, yy, sz)]?.let { "furniture ${it.id}" }
                        ?: grid.solid(sx, yy, sz).takeIf { !it.empty }?.let { Blocks.id(grid.type(sx, yy, sz)) }
                }
                val text = when {
                    blocker != null -> "blocked on its ${side.name.lowercase()} side by $blocker at [$sx, ${y}, $sz]"
                    best == null -> "opens on its ${side.name.lowercase()} side onto a drop (no floor near y=$y)"
                    else -> "has a step of ${"%.1f".format(best.floor - floor)} on its ${side.name.lowercase()} side ([$sx, ${best.y}, $sz])"
                }
                issues += Issue("doors", Severity.ERROR, x, y, z, "Door $text")
            }
            // Double doors: this one with its neighbour on the clockwise side must hinge left, the neighbour right.
            val cw = clockwise(f)
            val other = grid.data(x + cw.modX, y, z + cw.modZ) as? Door
            if (other != null && other.half == Bisected.Half.BOTTOM && other.facing == f) {
                if (d.hinge != Door.Hinge.LEFT || other.hinge != Door.Hinge.RIGHT) {
                    issues += Issue("doors", Severity.ERROR, x, y, z, "Double door with [${x + cw.modX}, $y, ${z + cw.modZ}]: hinges are ${d.hinge.name.lowercase()}/${other.hinge.name.lowercase()}, they must be left/right to open apart (fix=true corrects them)")
                    hinges += BlockVector3.at(x, y, z) to Door.Hinge.LEFT
                    hinges += BlockVector3.at(x + cw.modX, y, z + cw.modZ) to Door.Hinge.RIGHT
                }
            }
        }

        // Openings in walls (with or without a door): nothing may stand in front of them.
        val reported = HashSet<java.util.UUID>()
        for (opening in openings(grid, walk)) {
            for (cell in opening.front) {
                for (f in furniture) {
                    if (f.entity in reported) continue
                    val inCell = f.cells.any { it.x() == cell.x() && it.z() == cell.z() && it.y() in cell.y()..cell.y() + 1 } ||
                        visuals[f.entity]?.let { v -> overlaps(v.min, v.max, cell, 0.45) } == true
                    if (!inCell) continue
                    reported += f.entity!!
                    issues += Issue("doors", Severity.ERROR, f.x, f.y, f.z, "Furniture ${f.id} stands in front of the ${opening.what} at ${opening.at}: move it out of the way")
                }
            }
        }
    }

    /** A gap in a wall: its [run] cells, [front] = them and the cells on both sides, [keep] = 2 deep on both sides. */
    class Opening(val what: String, val at: String, val front: List<BlockVector3>, val run: List<BlockVector3> = emptyList(), val keep: List<BlockVector3> = emptyList())

    /** Gaps 1 to 3 wide in walls, under a lintel, with floor on both sides: doorways (door or not). */
    fun openings(grid: Grid, walk: Walk): List<Opening> {
        val out = ArrayList<Opening>()
        fun barrier(x: Int, y: Int, z: Int) = grid.type(x, y, z) == Material.BARRIER
        fun wall(x: Int, y: Int, z: Int) = grid.solid(x, y, z).full && grid.solid(x, y + 1, z).full && !barrier(x, y, z)
        // Furniture standing in the opening still makes it an opening (that is what gets reported).
        fun open(x: Int, y: Int, z: Int) = walk.column(x, z).any { walk.nodes[it].y == y && walk.nodes[it].kind == Walk.Kind.GROUND } ||
            (barrier(x, y, z) && grid.solid(x, y - 1, z).top >= 0.75)

        fun scan(x0: Int, y: Int, z0: Int) {
            for ((ax, az) in listOf(1 to 0, 0 to 1)) {
                // Start of a run: wall before it.
                if (!wall(x0 - ax, y, z0 - az)) continue
                val run = ArrayList<BlockVector3>()
                var cx = x0
                var cz = z0
                while (run.size < 4 && open(cx, y, cz) && !wall(cx, y, cz)) {
                    run += BlockVector3.at(cx, y, cz)
                    cx += ax; cz += az
                }
                if (run.isEmpty() || run.size > 3 || !wall(cx, y, cz)) continue
                // Under a lintel or the ceiling: a gap in a wall, not the end of a wall in the open.
                if (run.any { c -> (2..4).none { dy -> !grid.solid(c.x(), c.y() + dy, c.z()).empty && !barrier(c.x(), c.y() + dy, c.z()) } }) continue
                // The passage crosses the wall: floor on both sides.
                val px = az
                val pz = ax
                // The wall runs along the gap: a gap whose two ends are both walled in across it is a narrow room
                // between two walls, not a doorway (one end can be, at a corner or a T with an outer wall).
                val ends = listOf(BlockVector3.at(x0 - ax, y, z0 - az), BlockVector3.at(cx, y, cz))
                if (ends.all { e -> wall(e.x() + px, y, e.z() + pz) && wall(e.x() - px, y, e.z() - pz) }) continue
                if (!run.all { open(it.x() + px, it.y(), it.z() + pz) && open(it.x() - px, it.y(), it.z() - pz) }) continue
                val door = run.any { grid.data(it.x(), it.y(), it.z()) is Door }
                val front = run + run.flatMap { c -> listOf(1, -1).map { s -> BlockVector3.at(c.x() + px * s, c.y(), c.z() + pz * s) } }
                val keep = front + run.flatMap { c -> listOf(2, -2).map { s -> BlockVector3.at(c.x() + px * s, c.y(), c.z() + pz * s) } }
                out += Opening(if (door) "door" else "doorway", "[${run[0].x()}, ${run[0].y()}, ${run[0].z()}]" + if (run.size > 1) " (${run.size} wide)" else "", front, run, keep)
            }
        }
        for (node in walk.nodes) if (node.kind == Walk.Kind.GROUND) scan(node.x, node.y, node.z)
        // A run can also start on a furniture cell next to the wall.
        val b = grid.box
        for (x in b.min.x()..b.max.x()) for (y in b.min.y() + 1..b.max.y() - 2) for (z in b.min.z()..b.max.z()) if (barrier(x, y, z) && open(x, y, z)) scan(x, y, z)
        return out.distinctBy { it.at }
    }

    private fun overlaps(min: Vector3d, max: Vector3d, cell: BlockVector3, minHeight: Double): Boolean =
        min.x < cell.x() + 0.9 && max.x > cell.x() + 0.1 && min.z < cell.z() + 0.9 && max.z > cell.z() + 0.1 &&
            min.y < cell.y() + 1.8 && max.y > cell.y() + minHeight

    // ---- Ground ---------------------------------------------------------------------------------------------------

    private fun ground(grid: Grid, terrain: Terrain, structures: List<Structure>, issues: MutableList<Issue>) {
        val owner = HashMap<Long, Int>()
        structures.forEach { s -> s.columns.forEach { owner[it] = s.number } }
        for (s in structures) {
            val floor = s.floor ?: continue
            val buried = HashMap<BlockFace, MutableList<Int>>()
            val floating = HashMap<BlockFace, MutableList<Int>>()
            for (c in s.columns) for (face in Walk.FACES) {
                val o = key(kx(c) + face.modX, kz(c) + face.modZ)
                if (o in owner || o in terrain.water) continue
                val g = terrain.ground[o] ?: continue
                // Ground that is the top of the column (no build over it) is what players walk on outside.
                if (terrain.top[o] != g) continue
                val diff = g + 1 - floor
                if (diff >= 1) buried.getOrPut(face) { ArrayList() } += diff
                if (diff <= -2) {
                    val lowest = (g + 1..floor).firstOrNull { y -> !Blocks.isCover(grid.type(kx(c), y, kz(c))) }
                    if (lowest == null || lowest > g + 1) floating.getOrPut(face) { ArrayList() } += -diff
                }
            }
            val perimeter = s.columns.count { c -> Walk.FACES.any { f -> key(kx(c) + f.modX, kz(c) + f.modZ) !in s.columns } }
            val center = BlockVector3.at((s.box.min.x() + s.box.max.x()) / 2, floor, (s.box.min.z() + s.box.max.z()) / 2)
            val b = buried.values.sumOf { it.size }
            if (b >= 3 && b * 6 >= perimeter) {
                issues += Issue(
                    "ground", Severity.ERROR, center.x(), center.y(), center.z(),
                    "Structure #${s.number} ${s.box.describe()} sits below the ground around it (floor y=$floor): " +
                        buried.entries.joinToString(", ") { (f, l) -> "${f.name.lowercase()} side ${l.size} columns up to ${l.max()} higher" } +
                        ". Lower the ground around it with `grade`, or raise the build",
                )
            }
            // In a pit: the ground a few blocks out is well above the floor even when the nearest ring is not.
            val ring = HashSet<Long>()
            for (c in s.columns) for (dx in -7..7) for (dz in -7..7) {
                val o = key(kx(c) + dx, kz(c) + dz)
                if (o !in s.columns && o !in owner && o !in terrain.water && max(abs(dx), abs(dz)) >= 3) ring += o
            }
            val heights = ring.mapNotNull { o -> terrain.ground[o]?.takeIf { terrain.top[o] == it } }.sorted()
            if (b < 3 && heights.size >= 20) {
                val median = heights[heights.size / 2]
                if (median + 1 - floor >= 2) issues += Issue(
                    "ground", Severity.ERROR, center.x(), center.y(), center.z(),
                    "Structure #${s.number} ${s.box.describe()} sits in a pit: its floor is y=$floor, the ground 3-7 blocks around " +
                        "it is at y=${median + 1} (median). Raise the build or grade the ground down to it gently",
                )
            }
            val fl = floating.values.sumOf { it.size }
            // Overhangs (a water tank on its tower, a balcony) are carried by the columns that do reach the ground.
            val contact = s.columns.count { c ->
                val g = terrain.ground[c] ?: return@count false
                (g + 1..g + 2).any { y -> !Blocks.isCover(grid.type(kx(c), y, kz(c))) }
            }
            if (fl >= 3 && fl * 6 >= perimeter && contact * 7 < s.columns.size) {
                issues += Issue(
                    "ground", Severity.ERROR, center.x(), center.y(), center.z(),
                    "Structure #${s.number} ${s.box.describe()} floats above the ground: " +
                        floating.entries.joinToString(", ") { (f, l) -> "${f.name.lowercase()} side ${l.size} columns up to ${l.max()} high" } +
                        ". Add a foundation or raise the ground with `grade`",
                )
            }
        }
    }

    // ---- Terrain --------------------------------------------------------------------------------------------------

    private fun cliffs(terrain: Terrain, structures: List<Structure>, issues: MutableList<Issue>) {
        val built = HashSet<Long>()
        structures.forEach { built += it.columns }
        val drop = HashMap<Long, Int>()
        for ((k, g) in terrain.ground) {
            if (k in built || k in terrain.water || terrain.top[k] != g) continue
            for (f in Walk.FACES) {
                val o = key(kx(k) + f.modX, kz(k) + f.modZ)
                if (o in built || o in terrain.water) continue
                val h = terrain.ground[o]?.takeIf { terrain.top[o] == it } ?: continue
                if (g - h >= 2) drop[k] = max(drop[k] ?: 0, g - h)
            }
        }
        val seen = HashSet<Long>()
        val groups = ArrayList<List<Long>>()
        for (k in drop.keys) {
            if (!seen.add(k)) continue
            val group = ArrayList<Long>()
            val queue = ArrayDeque(listOf(k))
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                group += c
                for (dx in -1..1) for (dz in -1..1) {
                    val n = key(kx(c) + dx, kz(c) + dz)
                    if (n in drop && seen.add(n)) queue.add(n)
                }
            }
            groups += group
        }
        val nearBuild = { g: List<Long> -> g.any { c -> (-4..4).any { dx -> (-4..4).any { dz -> key(kx(c) + dx, kz(c) + dz) in built } } } }
        groups.filter { it.size >= 3 }.sortedByDescending { g -> g.size * (g.maxOf { drop[it]!! }) }.take(15).forEach { g ->
            val worst = g.maxBy { drop[it]!! }
            val xs = g.map { kx(it) }; val zs = g.map { kz(it) }
            val near = nearBuild(g)
            issues += Issue(
                "terrain", if (near || drop[worst]!! >= 3) Severity.ERROR else Severity.WARNING, kx(worst), terrain.ground[worst]!!, kz(worst),
                "Cliff of up to ${drop[worst]} blocks along ${g.size} columns (x ${xs.min()}..${xs.max()}, z ${zs.min()}..${zs.max()})" +
                    (if (near) ", next to a build" else "") + ": unclimbable and unnatural. Soften it with `grade` (max_slope) or smooth_terrain",
            )
        }
    }

    // ---- Roads ----------------------------------------------------------------------------------------------------

    private fun roads(grid: Grid, terrain: Terrain, structures: List<Structure>, options: Options, issues: MutableList<Issue>) {
        val built = HashSet<Long>()
        structures.forEach { built += it.columns }
        val road = HashMap<Long, Int>()
        for ((k, y) in terrain.top) {
            if (k in built || k in terrain.water) continue
            if (options.roads(terrain.topType[k]!!)) road[k] = y
        }
        // Isolated road-like blocks (a gravel patch, a stone) are not roads: keep cells with road around them.
        val roads = road.filterKeys { k -> (-1..1).sumOf { dx -> (-1..1).count { dz -> key(kx(k) + dx, kz(k) + dz) in road } } >= 5 }
        val steps = HashMap<Long, Int>()
        val steep = HashMap<Long, Double>()
        for ((k, y) in roads) {
            for (f in listOf(BlockFace.EAST, BlockFace.SOUTH)) {
                val o = key(kx(k) + f.modX, kz(k) + f.modZ)
                val h = roads[o] ?: continue
                if (abs(h - y) >= 2) steps[k] = max(steps[k] ?: 0, abs(h - y))
                val far = roads[key(kx(k) + f.modX * 4, kz(k) + f.modZ * 4)] ?: continue
                val grade = abs(far - y) / 4.0
                if (grade > options.maxGrade) steep[k] = max(steep[k] ?: 0.0, grade)
            }
        }
        cluster(steps.keys).sortedByDescending { it.size }.take(10).forEach { g ->
            val worst = g.maxBy { steps[it]!! }
            issues += Issue("roads", Severity.ERROR, kx(worst), roads[worst]!!, kz(worst), "Road has a ${steps[worst]}-block step (${span(g)}): rebuild the profile with build_path max_grade")
        }
        cluster(steep.keys).filter { it.size >= 3 }.sortedByDescending { it.size }.take(10).forEach { g ->
            val worst = g.maxBy { steep[it]!! }
            issues += Issue(
                "roads", Severity.ERROR, kx(worst), roads[worst]!!, kz(worst),
                "Road grade ${(steep[worst]!! * 100).roundToInt()}% over ${g.size} cells (${span(g)}), limit ${(options.maxGrade * 100).roundToInt()}%: re-lay it with build_path max_grade or reroute along the slope",
            )
        }
        // Holes: a cell between road cells (on both sides) that is lower, water or void.
        val holes = HashSet<Long>()
        for (x in grid.box.min.x() + 1 until grid.box.max.x()) for (z in grid.box.min.z() + 1 until grid.box.max.z()) {
            val k = key(x, z)
            if (k in roads || k in built) continue
            for ((dx, dz) in listOf(1 to 0, 0 to 1)) {
                val a = roads[key(x - dx, z - dz)] ?: continue
                val c = roads[key(x + dx, z + dz)] ?: continue
                val h = terrain.top[k]
                if (h == null || k in terrain.water || h < min(a, c)) holes += k
            }
        }
        cluster(holes).sortedByDescending { it.size }.take(10).forEach { g ->
            val c = g.first()
            issues += Issue("roads", Severity.ERROR, kx(c), terrain.top[c] ?: grid.box.min.y(), kz(c), "Hole in the road: ${g.size} cells lower than the road around them (${span(g)})")
        }
    }

    private fun cluster(cells: Set<Long>): List<List<Long>> {
        val seen = HashSet<Long>()
        val out = ArrayList<List<Long>>()
        for (k in cells) {
            if (!seen.add(k)) continue
            val group = ArrayList<Long>()
            val queue = ArrayDeque(listOf(k))
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                group += c
                for (dx in -1..1) for (dz in -1..1) {
                    val n = key(kx(c) + dx, kz(c) + dz)
                    if (n in cells && seen.add(n)) queue.add(n)
                }
            }
            out += group
        }
        return out
    }

    private fun span(g: List<Long>) = "x ${g.minOf { kx(it) }}..${g.maxOf { kx(it) }}, z ${g.minOf { kz(it) }}..${g.maxOf { kz(it) }}"

    // ---- Furniture ------------------------------------------------------------------------------------------------

    private fun furniture(grid: Grid, furniture: List<Furniture.Placed>, visuals: Map<java.util.UUID, Baked>, issues: MutableList<Issue>) {
        for (f in furniture) {
            val v = visuals[f.entity]
            if (v != null) {
                // Model inside full blocks (a wall, the floor): wrong spot or facing.
                var inside = 0.0
                for (x in kotlin.math.floor(v.min.x).toInt()..kotlin.math.floor(v.max.x - 1e-6).toInt())
                    for (y in kotlin.math.floor(v.min.y).toInt()..kotlin.math.floor(v.max.y - 1e-6).toInt())
                        for (z in kotlin.math.floor(v.min.z).toInt()..kotlin.math.floor(v.max.z - 1e-6).toInt()) {
                            if (!grid.inside(x, y, z) || !grid.shape(x, y, z).full || grid.type(x, y, z) == Material.BARRIER) continue
                            val ox = min(v.max.x, x + 1.0) - max(v.min.x, x.toDouble())
                            val oy = min(v.max.y, y + 1.0) - max(v.min.y, y.toDouble())
                            val oz = min(v.max.z, z + 1.0) - max(v.min.z, z.toDouble())
                            if (ox > 0.08 && oy > 0.08 && oz > 0.08) inside += ox * oy * oz
                        }
                val volume = (v.max.x - v.min.x) * (v.max.y - v.min.y) * (v.max.z - v.min.z)
                if (inside > 0.12 && inside > volume * 0.15) {
                    issues += Issue(
                        "furniture", Severity.ERROR, f.x, f.y, f.z,
                        "${f.id}'s model goes ${(inside * 100 / volume).roundToInt()}% into solid blocks (model ${Displays.describe(v.min, v.max)}): move or turn it",
                    )
                }
            }
            // Front against a wall: the cells in front of its footprint are full blocks.
            val front = front(f.yaw)
            val cells = f.cells.ifEmpty { listOf(BlockVector3.at(f.x, f.y, f.z)) }
            val ahead = cells.map { BlockVector3.at(it.x() + front.modX, it.y(), it.z() + front.modZ) }.filter { c -> cells.none { it == c } }
            // Other furniture in front (a chair at a table) is fine: only real walls count.
            if (ahead.isNotEmpty() && ahead.all { grid.shape(it.x(), it.y(), it.z()).full && grid.type(it.x(), it.y(), it.z()) != Material.BARRIER }) {
                issues += Issue("furniture", Severity.WARNING, f.x, f.y, f.z, "${f.id} faces ${front.name.lowercase()} straight into a wall: check its facing (place_furniture `facing`)")
            }
        }
    }

    /** The side furniture placed with this yaw shows its front to: toward the player who placed it. */
    fun front(yaw: Float): BlockFace = when (Math.floorMod(Math.round(yaw / 90f), 4)) {
        0 -> BlockFace.NORTH; 1 -> BlockFace.EAST; 2 -> BlockFace.SOUTH; else -> BlockFace.WEST
    }

    // ---- Floating blocks ------------------------------------------------------------------------------------------

    private fun floating(grid: Grid, issues: MutableList<Issue>) {
        val b = grid.box
        val sx = b.sizeX; val sy = b.sizeY; val sz = b.sizeZ
        fun idx(x: Int, y: Int, z: Int) = ((x - b.min.x()) * sy + (y - b.min.y())) * sz + (z - b.min.z())
        fun counts(m: Material) = !m.isAir && !Blocks.isLiquid(m) && m != Material.LIGHT && m != Material.STRUCTURE_VOID
        val held = BooleanArray(sx * sy * sz)
        val queue = ArrayDeque<Int>()
        // Anchors: the bottom layer, the box sides (the rest of the world holds them) and the natural ground.
        for (x in b.min.x()..b.max.x()) for (y in b.min.y()..b.max.y()) for (z in b.min.z()..b.max.z()) {
            val m = grid.type(x, y, z)
            if (!counts(m)) continue
            val edge = y == b.min.y() || x == b.min.x() || x == b.max.x() || z == b.min.z() || z == b.max.z()
            if (edge || Blocks.isNaturalGround(m)) { held[idx(x, y, z)] = true; queue.add(idx(x, y, z)) }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i / (sy * sz) + b.min.x()
            val y = (i / sz) % sy + b.min.y()
            val z = i % sz + b.min.z()
            for ((dx, dy, dz) in NEIGHBOURS) {
                val nx = x + dx; val ny = y + dy; val nz = z + dz
                if (!b.contains(nx, ny, nz)) continue
                val j = idx(nx, ny, nz)
                if (!held[j] && counts(grid.type(nx, ny, nz))) { held[j] = true; queue.add(j) }
            }
        }
        val loose = HashMap<Long, MutableList<BlockVector3>>()
        for (x in b.min.x()..b.max.x()) for (y in b.min.y()..b.max.y()) for (z in b.min.z()..b.max.z()) {
            val m = grid.type(x, y, z)
            if (!counts(m) || held[idx(x, y, z)] || Tag.LEAVES.isTagged(m)) continue
            loose.getOrPut(key(x shr 3, z shr 3)) { ArrayList() } += BlockVector3.at(x, y, z)
        }
        loose.values.sortedByDescending { it.size }.take(12).forEach { l ->
            val c = l.first()
            issues += Issue("floating", Severity.WARNING, c.x(), c.y(), c.z(), "${l.size} blocks float with nothing under or beside them, e.g. ${Blocks.id(grid.type(c.x(), c.y(), c.z()))} at ${Box.fmt(c)}")
        }
    }

    private val NEIGHBOURS = listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))

    // ---- Claims ---------------------------------------------------------------------------------------------------

    private fun claims(box: Box, claims: List<games.qore.foreman.server.world.Area>, issues: MutableList<Issue>) {
        val near = claims.filter { c -> c.boxes.any { it.intersect(box) != null } }
        for (i in near.indices) for (j in i + 1 until near.size) {
            val a = near[i]; val b = near[j]
            // Roads cross and join: only builds overlapping something count.
            if (a.kind == "road" && b.kind == "road") continue
            for (ba in a.boxes) for (bb in b.boxes) {
                val common = ba.intersect(bb) ?: continue
                issues += Issue("claims", Severity.ERROR, common.min.x(), common.min.y(), common.min.z(), "${a.name} (${a.kind}) and ${b.name} (${b.kind}) overlap in ${common.describe()}")
            }
        }
    }
}
