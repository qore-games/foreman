package games.qore.foreman.server.tools

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Blocks
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Checks
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Grid
import games.qore.foreman.server.world.Sync
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Terrain repair: what terraforming leaves behind, fixed in one pass. Builds on exposed foundations or dirt boxes get
 * banks down (or up) to their floor, cliffs relax into slopes, air pockets under the ground are filled and loose
 * floating blocks removed. Roads, builds, water and road/yard claims are left as they are; trees move with their ground.
 */
object RepairTools {
    private val ALL = listOf("hollows", "floating", "seat", "cliffs")
    private val OPTIONAL = listOf("naturalize")

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.CONNECT_DOORS,
            "Lays a path from every outside door in an area that has none to the nearest road (gravel, dirt path, " +
                "concrete… or a claimed road): routed around slopes, slab half-steps, banks. Run it after building " +
                "houses, shops, a church: every entrance should be reachable on a path.",
            schema {
                vec("from", "A corner (or give area)", required = false)
                vec("to", "The opposite corner", required = false)
                string("area", "A mark name instead of from/to", required = false)
                string("block", "Path pattern (default 60%dirt_path,40%coarse_dirt)", required = false)
                int("width", "Path width (default 2)", required = false)
                int("search", "How far to look for a road from a door (default 80)", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> connect(plugin, args) },

        Tool(
            ForemanTool.REPAIR_TERRAIN,
            "Repairs the terraforming of an area in one pass (one undo step), then reports what `check` still finds: " +
                "hollows (air pockets under the ground filled), floating (loose blocks hanging in the air removed: debris, " +
                "broken branches; leaves and furniture stay), seat (builds standing on an exposed foundation or a dirt " +
                "box, or sunk in a pit: the ground around them banked to their floor level), cliffs (2+ block steps of " +
                "natural ground relaxed into slopes). Roads, builds, water and road or yard claims are kept; trees move with their ground. " +
                "Run it after building or terraforming, and on old areas: it is the fix for `check`'s terrain, ground " +
                "and floating issues.",
            schema {
                vec("from", "A corner (or give area)", required = false)
                vec("to", "The opposite corner", required = false)
                string("area", "A mark name instead of from/to", required = false)
                strings("fix", "What to repair (default all but naturalize): hollows, floating, seat, cliffs, naturalize (flat, " +
                    "square-cut ground given gentle swells and dips, fading out near builds and roads: for plateaus and pads " +
                    "that look man-made)", required = false)
                int("amplitude", "naturalize: highest swell or dip in blocks (default 2)", required = false)
                number("max_slope", "Steepest bank around seated builds, rise per block (default 0.7)", required = false)
                int("max_step", "Steps of natural ground allowed between neighbours by cliffs (default 1)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> repair(plugin, args) },
    )

    /** Outside doors (a door or gate with open sky on one side) with no road near, each joined to the nearest road. */
    fun connect(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val area = area(plugin, args, world)
        val search = args.int("search", 80).coerceIn(10, 160)
        val outer = Box(
            BlockVector3.at(area.min.x() - search, max(world.minHeight, area.min.y() - 16), area.min.z() - search),
            BlockVector3.at(area.max.x() + search, min(world.maxHeight - 1, area.max.y() + 8), area.max.z() + search),
        )
        // Entrances: doors and open doorways, on the side where the sky opens within 3 blocks (eaves cover the step).
        val grid = Sync.main { Chunks.keepLoaded(world, outer); Grid.read(world, Box(area.min, area.max.add(0, 16, 0))) }
        fun sky(x: Int, y: Int, z: Int) = (y + 2..y + 14).all { yy -> !grid.inside(x, yy, z) || grid.type(x, yy, z).let { it.isAir || Tag.LEAVES.isTagged(it) || Blocks.isPlant(it) } }
        // Something to stand on within 3 below: upper-floor doors (to nothing, or a balcony) don't lead to the ground.
        fun grounded(x: Int, y: Int, z: Int) = (1..3).any { !grid.solid(x, y - it, z).empty }
        fun outside(c: BlockVector3, dx: Int, dz: Int) = (1..3).any { d -> sky(c.x() + dx * d, c.y(), c.z() + dz * d) }
        val doors = ArrayList<Pair<BlockVector3, Int>>()
        for (x in area.min.x()..area.max.x()) for (y in area.min.y()..area.max.y()) for (z in area.min.z()..area.max.z()) {
            val d = grid.data(x, y, z) as? org.bukkit.block.data.type.Door ?: continue
            if (d.half != org.bukkit.block.data.Bisected.Half.BOTTOM) continue
            val c = BlockVector3.at(x, y, z)
            for (f in listOf(d.facing, d.facing.oppositeFace)) if (outside(c, f.modX, f.modZ) && grounded(x + f.modX, y, z + f.modZ)) doors += BlockVector3.at(x + f.modX, y, z + f.modZ) to 0
        }
        Checks.openings(grid, games.qore.foreman.server.world.Walk(grid)).filter { it.what == "doorway" }.forEach { o ->
            val c = o.run.first()
            o.front.filter { it !in o.run }.forEach { f -> if (outside(c, f.x() - c.x(), f.z() - c.z()) && grounded(f.x(), f.y(), f.z())) doors += f to 0 }
        }
        val roads = Sync.main {
            val roads = HashMap<Long, Int>()
            val claimed = plugin.worlds.reserved(world.name).filter { it.kind == "road" }.flatMap { it.boxes }
            // Builds and what stands around them (porches, plinths) are not roads.
            val builds = plugin.worlds.reserved(world.name).filter { it.kind == "build" }.flatMap { it.boxes }.map { it.grow(4, 0, 4) }
            for (x in outer.min.x()..outer.max.x()) for (z in outer.min.z()..outer.max.z()) {
                val s = Blocks.surface(world, x, z)
                if (builds.any { it.min.x() <= x && x <= it.max.x() && it.min.z() <= z && z <= it.max.z() }) continue
                // A road lies on the ground: solid under its surface (a roof has a room under it).
                if ((1..2).any { !world.getType(x, s.y - it, z).isSolid }) continue
                if (Checks.DEFAULT_ROADS(s.material) && s.material != Material.COBBLESTONE && s.material != Material.MOSSY_COBBLESTONE || claimed.any { it.contains(x, s.y, z) }) roads[key(x, z)] = s.y
            }
            roads
        }
        // A road is a road when it has road around it: isolated blocks (a stone, a patch of gravel) aren't.
        val real = roads.filterKeys { k -> (-1..1).sumOf { dx -> (-1..1).count { dz -> key(kx(k) + dx, kz(k) + dz) in roads } } >= 6 }
        val lines = ArrayList<String>()
        // Double doors give two door cells side by side: one path each pair.
        val done = ArrayList<BlockVector3>()
        for ((cell, _) in doors) {
            if (done.any { abs(it.x() - cell.x()) + abs(it.z() - cell.z()) <= 3 && abs(it.y() - cell.y()) <= 2 }) continue
            done += cell
            val near = real.entries.minByOrNull { (k, _) -> abs(kx(k) - cell.x()) + abs(kz(k) - cell.z()) }
            if (near == null) { lines += "${Box.fmt(cell)}: no road within $search blocks"; continue }
            val d = abs(kx(near.key) - cell.x()) + abs(kz(near.key) - cell.z())
            if (d <= 3) { lines += "${Box.fmt(cell)}: on a road already"; continue }
            val path = com.google.gson.JsonObject().apply {
                add("points", com.google.gson.JsonArray().apply {
                    add(com.google.gson.JsonArray().apply { add(cell.x()); add(cell.z()) })
                    add(com.google.gson.JsonArray().apply { add(kx(near.key)); add(kz(near.key)) })
                })
                add("ends", com.google.gson.JsonArray().apply { add(cell.y() - 1); add(near.value) })
                addProperty("width", args.int("width", 2)); addProperty("block", args.string("block", "60%dirt_path,40%coarse_dirt"))
                addProperty("route", "auto"); addProperty("max_grade", 0.3); addProperty("bank", 8); addProperty("world", world.name)
            }
            val text = runCatching { TerrainTools.path(plugin, Args(path)).content.first().get("text").asString.lineSequence().first() }.getOrElse { "failed: ${it.message}" }
            lines += "${Box.fmt(cell)} → road at [${kx(near.key)}, ${near.value}, ${kz(near.key)}] ($d blocks): $text"
        }
        if (doors.isEmpty()) return ToolResult.text("No outside doors in ${area.describe()}")
        return ToolResult.text("${done.size} entrances in ${area.describe()}:\n" + lines.joinToString("\n"))
    }

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)
    private fun kx(k: Long) = (k shr 32).toInt()
    private fun kz(k: Long) = k.toInt()
    private val FACES = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private fun area(plugin: Foreman, args: Args, world: World): Box = if (args.has("area")) {
        (plugin.worlds.area(args.string("area"), world.name) ?: throw ToolError("No area '${args.string("area")}'")).bounds
    } else Box.of(args.vec("from"), args.vec("to"))

    private fun repair(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val area = area(plugin, args, world)
        if (area.sizeX.toLong() * area.sizeZ > 260L * 260) throw ToolError("Repair at most 260x260 at once: split the area")
        val fix = args.strings("fix").ifEmpty { ALL }
        fix.firstOrNull { it !in ALL && it !in OPTIONAL }?.let { throw ToolError("Unknown fix '$it' (${(ALL + OPTIONAL).joinToString()})") }
        val slope = args.double("max_slope", 0.7).coerceIn(0.3, 2.0)
        val maxStep = args.int("max_step", 1).coerceIn(1, 3)
        // Everything from below the deepest ground to above the highest build.
        val box = Box(
            BlockVector3.at(area.min.x(), max(world.minHeight, area.min.y() - 16), area.min.z()),
            BlockVector3.at(area.max.x(), min(world.maxHeight - 1, area.max.y() + 8), area.max.z()),
        )
        Sync.main { Chunks.keepLoaded(world, box) }
        val grid = Sync.main { Grid.read(world, box) }
        val report = Checks.run(grid, emptyList(), emptyList(), setOf("ground"), Checks.Options())
        val ground = Ground(grid, plugin, world)
        report.structures.forEach { ground.built += it.columns }

        val notes = ArrayList<String>()
        val (_, change) = Edits.scoped(world, "repair terrain in ${area.describe()}") { scope ->
            if ("hollows" in fix) notes += hollows(grid, ground, scope.session)
            if ("floating" in fix) notes += floating(grid, scope.session)
            scope.flush()
            if ("seat" in fix) notes += seat(report.structures, ground, slope, scope.session)
            if ("naturalize" in fix) notes += naturalize(ground, args.int("amplitude", 2).coerceIn(1, 6), scope.session)
            if ("cliffs" in fix || "naturalize" in fix) notes += cliffs(ground, maxStep, scope.session)
        }
        val after = QualityTools.check(plugin, Args(com.google.gson.JsonObject().apply {
            add("from", games.qore.foreman.server.world.build.Compiler.array(area.min.x(), area.min.y(), area.min.z()))
            add("to", games.qore.foreman.server.world.build.Compiler.array(area.max.x(), area.max.y(), area.max.z()))
            add("checks", com.google.gson.JsonArray().apply { listOf("ground", "terrain", "floating").forEach { add(it) } })
            addProperty("image", false); addProperty("world", world.name)
        })).content.first().get("text").asString
        return InspectTools.result(
            plugin, world,
            "Repaired ${area.describe()}: ${change.blocks} blocks.\n" + notes.joinToString("\n") { "- $it" } + "\n\nWhat check still finds:\n" + after,
            area, args, "repaired",
        )
    }

    /** Per column: the natural ground (top natural block with only cover above it), and what may not be reshaped. */
    private class Ground(val grid: Grid, plugin: Foreman, world: World) {
        val y = HashMap<Long, Int>()
        val material = HashMap<Long, Material>()
        val fixed = HashSet<Long>()
        val water = HashSet<Long>()
        val built = HashSet<Long>()
        /** The log of a tree standing on the column: it moves with its ground. */
        val trunk = HashMap<Long, Material>()
        /** Columns the cliffs pass may not move (the banks just made around seated builds). */
        val pinned = HashSet<Long>()

        init {
            val b = grid.box
            val claims = plugin.worlds.reserved(world.name).filter { it.kind == "road" || it.kind == "yard" }.flatMap { it.boxes }
            for (x in b.min.x()..b.max.x()) for (z in b.min.z()..b.max.z()) {
                val k = key(x, z)
                var top = b.max.y()
                var liquid = false
                while (top >= b.min.y()) {
                    val m = grid.type(x, top, z)
                    if (Blocks.isLiquid(m)) liquid = true
                    else if (Tag.LOGS.isTagged(m) && top > b.min.y() && (Blocks.isNaturalGround(grid.type(x, top - 1, z)) || Tag.LOGS.isTagged(grid.type(x, top - 1, z)))) trunk[k] = m
                    else if (!Blocks.isCover(m)) break
                    top--
                }
                if (top < b.min.y()) continue
                val m = grid.type(x, top, z)
                y[k] = top
                material[k] = m
                // Water, roads and paving, and claimed roads and yards stay as they are.
                if (liquid) water += k
                if (liquid || !Blocks.isNaturalGround(m) || Checks.DEFAULT_ROADS(m) || claims.any { it.contains(x, top, z) }) fixed += k
            }
        }

        fun free(k: Long) = k in y && k !in fixed && k !in built
    }

    /** Sets a natural column's surface to [target]: fills with its subsoil or carves down; plants on it go. */
    private fun reshape(grid: Grid, session: EditSession, ground: Ground, k: Long, target: Int) {
        val x = kx(k); val z = kz(k)
        val g = ground.y[k] ?: return
        if (target == g) return
        val top = ground.material[k]!!.let { if (it == Material.DIRT && target > g) Material.GRASS_BLOCK else it }
        val sub = BukkitAdapter.adapt(Blocks.subsurface(top).createBlockData())
        val air = BukkitAdapter.adapt(Material.AIR.createBlockData())
        if (target > g) for (yy in g until target) session.setBlock(x, yy, z, sub)
        else {
            // A tree on a lowered column keeps standing: its trunk goes down to the new ground.
            val log = ground.trunk[k]?.let { BukkitAdapter.adapt(it.createBlockData()) }
            for (yy in target + 1..g) session.setBlock(x, yy, z, log ?: air)
        }
        session.setBlock(x, target, z, BukkitAdapter.adapt(top.createBlockData()))
        var yy = g + 1
        while (yy <= grid.box.max.y() && Blocks.isPlant(grid.type(x, yy, z)) && k !in ground.trunk) session.setBlock(x, yy++, z, air)
        ground.y[k] = target
    }

    // ---- Hollows --------------------------------------------------------------------------------------------------

    /** Air pockets under natural ground, closed on all sides by natural ground: filled with the ground's subsoil. */
    private fun hollows(grid: Grid, ground: Ground, session: EditSession): String {
        val b = grid.box
        fun air(x: Int, y: Int, z: Int) = grid.type(x, y, z).let { it.isAir || it == Material.CAVE_AIR }
        val under = HashSet<Triple<Int, Int, Int>>()
        for ((k, g) in ground.y) {
            if (k in ground.built) continue
            val x = kx(k); val z = kz(k)
            for (yy in max(b.min.y() + 1, g - 24) until g) if (air(x, yy, z)) under += Triple(x, yy, z)
        }
        val seen = HashSet<Triple<Int, Int, Int>>()
        var filled = 0
        var pockets = 0
        for (start in under) {
            if (!seen.add(start)) continue
            val group = ArrayList<Triple<Int, Int, Int>>()
            val queue = ArrayDeque(listOf(start))
            var open = false
            while (queue.isNotEmpty()) {
                val c = queue.removeFirst()
                group += c
                for ((dx, dy, dz) in listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))) {
                    val n = Triple(c.first + dx, c.second + dy, c.third + dz)
                    if (!b.contains(n.first, n.second, n.third)) { open = true; continue }
                    val m = grid.type(n.first, n.second, n.third)
                    if (m.isAir || m == Material.CAVE_AIR) {
                        // Air that isn't under the ground: the pocket opens to the outside (a cave, a pit, a cutting).
                        if (n !in under) open = true else if (seen.add(n)) queue.add(n)
                    } else if (!Blocks.isNaturalGround(m) && m != Material.COBBLESTONE) open = true // a basement, a tunnel
                }
                if (group.size > 4000) { open = true; break }
            }
            if (open) continue
            pockets++
            for ((x, yy, z) in group) {
                val g = ground.y[key(x, z)] ?: continue
                val m = if (g - yy <= 3) Blocks.subsurface(ground.material[key(x, z)]!!) else Material.STONE
                session.setBlock(x, yy, z, BukkitAdapter.adapt(m.createBlockData()))
                filled++
            }
        }
        return "hollows: $pockets air pockets filled ($filled blocks)"
    }

    // ---- Floating -------------------------------------------------------------------------------------------------

    /** Blocks held by nothing (not the ground, not the box sides, not other blocks): small clusters removed. */
    private fun floating(grid: Grid, session: EditSession): String {
        val b = grid.box
        val sy = b.sizeY; val sz = b.sizeZ
        fun idx(x: Int, y: Int, z: Int) = ((x - b.min.x()) * sy + (y - b.min.y())) * sz + (z - b.min.z())
        fun counts(m: Material) = !m.isAir && !Blocks.isLiquid(m) && m != Material.LIGHT && m != Material.STRUCTURE_VOID && m != Material.CAVE_AIR
        val held = BooleanArray(b.sizeX * sy * sz)
        val queue = ArrayDeque<Int>()
        for (x in b.min.x()..b.max.x()) for (y in b.min.y()..b.max.y()) for (z in b.min.z()..b.max.z()) {
            val m = grid.type(x, y, z)
            if (!counts(m)) continue
            val edge = y == b.min.y() || x == b.min.x() || x == b.max.x() || z == b.min.z() || z == b.max.z()
            if (edge || Blocks.isNaturalGround(m)) { held[idx(x, y, z)] = true; queue.add(idx(x, y, z)) }
        }
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val x = i / (sy * sz) + b.min.x(); val y = (i / sz) % sy + b.min.y(); val z = i % sz + b.min.z()
            // Diagonal neighbours hold too: branches and roof edges are often attached by a corner.
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                val nx = x + dx; val ny = y + dy; val nz = z + dz
                if (!b.contains(nx, ny, nz)) continue
                val j = idx(nx, ny, nz)
                if (!held[j] && counts(grid.type(nx, ny, nz))) { held[j] = true; queue.add(j) }
            }
        }
        // Loose blocks grouped into clusters (6-connected), small ones removed; leaves and furniture barriers stay.
        val loose = HashSet<Triple<Int, Int, Int>>()
        for (x in b.min.x()..b.max.x()) for (y in b.min.y()..b.max.y()) for (z in b.min.z()..b.max.z()) {
            val m = grid.type(x, y, z)
            if (counts(m) && !held[idx(x, y, z)] && !Tag.LEAVES.isTagged(m) && m != Material.BARRIER) loose += Triple(x, y, z)
        }
        val air = BukkitAdapter.adapt(Material.AIR.createBlockData())
        val seen = HashSet<Triple<Int, Int, Int>>()
        var removed = 0
        var kept = 0
        for (start in loose) {
            if (!seen.add(start)) continue
            val group = ArrayList<Triple<Int, Int, Int>>()
            val q = ArrayDeque(listOf(start))
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                group += c
                for ((dx, dy, dz) in listOf(Triple(1, 0, 0), Triple(-1, 0, 0), Triple(0, 1, 0), Triple(0, -1, 0), Triple(0, 0, 1), Triple(0, 0, -1))) {
                    val n = Triple(c.first + dx, c.second + dy, c.third + dz)
                    if (n in loose && seen.add(n)) q.add(n)
                }
            }
            if (group.size > 40) { kept += group.size; continue }
            group.forEach { (x, y, z) -> session.setBlock(x, y, z, air) }
            removed += group.size
        }
        return "floating: $removed loose blocks removed" + if (kept > 0) " ($kept in larger clusters kept: look at them)" else ""
    }

    // ---- Seating builds -------------------------------------------------------------------------------------------

    /**
     * Builds whose surroundings are far from their floor (a dirt box with a foundation showing, a house in a pit):
     * the ground around them banked to the floor level, no steeper than [slope], fading out where it meets the land.
     */
    private fun seat(structures: List<Checks.Structure>, ground: Ground, slope: Double, session: EditSession): String {
        var seated = 0
        var columns = 0
        for (s in structures) {
            val floor = s.floor ?: continue
            if (s.columns.size < 12) continue
            val target = floor - 1
            // The ring of ground around it: how far from the floor it is.
            val ring = s.columns.flatMap { c -> FACES.map { (dx, dz) -> key(kx(c) + dx * 2, kz(c) + dz * 2) } }.filter { it !in s.columns && ground.free(it) }.toSet()
            if (ring.isEmpty()) continue
            val off = ring.count { abs(ground.y[it]!! - target) >= 2 }
            if (off * 4 < ring.size) continue
            seated++
            // Distance to the build (BFS over columns), the banks within reach.
            val reach = 28
            val dist = HashMap<Long, Int>()
            val q = ArrayDeque<Long>()
            s.columns.forEach { dist[it] = 0; q.add(it) }
            while (q.isNotEmpty()) {
                val c = q.removeFirst()
                val d = dist[c]!!
                if (d >= reach) continue
                for ((dx, dz) in FACES) {
                    val n = key(kx(c) + dx, kz(c) + dz)
                    if (n !in dist && n in ground.y) { dist[n] = d + 1; q.add(n) }
                }
            }
            // Alleys and yards between this build and another: neither build's banks fill them.
            val others = ground.built - s.columns
            fun nearOther(k: Long) = (-3..3).any { dx -> (-3..3).any { dz -> key(kx(k) + dx, kz(k) + dz) in others } }
            for ((k, d) in dist) {
                if (d == 0 || !ground.free(k) || nearOther(k)) continue
                val g = ground.y[k]!!
                // A plinth of one block is fine: the ground may stay a block under the floor's ground level.
                val band = slope * (d - 1)
                val clamped = g.toDouble().coerceIn(target - 1 - band, target + band)
                val r = d.toDouble() / reach
                val fade = if (r < 0.75) 1.0 else ((1 - r) / 0.25).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
                if (d <= 4) ground.pinned += k
                val t = (g + (clamped - g) * fade).roundToInt()
                if (t != g) { reshape(ground.grid, session, ground, k, t); columns++ }
            }
        }
        return "seat: $seated builds set into the ground ($columns columns banked)"
    }

    // ---- Naturalize -----------------------------------------------------------------------------------------------

    /** Gentle swells and dips (fractal noise, ~18-block waves) on free ground, fading to nothing within 8 of what is fixed. */
    private fun naturalize(ground: Ground, amplitude: Int, session: EditSession): String {
        val dist = HashMap<Long, Int>()
        val q = ArrayDeque<Long>()
        ground.y.keys.filter { !ground.free(it) }.forEach { dist[it] = 0; q.add(it) }
        while (q.isNotEmpty()) {
            val c = q.removeFirst()
            val d = dist[c]!!
            if (d >= 8) continue
            for ((dx, dz) in FACES) { val n = key(kx(c) + dx, kz(c) + dz); if (n in ground.y && n !in dist) { dist[n] = d + 1; q.add(n) } }
        }
        val seed = ground.grid.box.min.x() * 31L + ground.grid.box.min.z()
        var changed = 0
        for ((k, y) in ground.y.toMap()) {
            if (!ground.free(k) || k in ground.pinned) continue
            val fade = ((dist[k] ?: 8) / 8.0).coerceIn(0.0, 1.0)
            val n = games.qore.foreman.server.world.Nature.fractal(seed, kx(k).toDouble(), kz(k).toDouble(), 18.0) * 2 - 1
            val t = y + (n * amplitude * fade).roundToInt()
            if (t != y) { reshape(ground.grid, session, ground, k, t); changed++ }
        }
        return "naturalize: $changed columns swelled or dipped (up to $amplitude)"
    }

    // ---- Cliffs ---------------------------------------------------------------------------------------------------

    /**
     * Steps of more than [maxStep] between natural neighbours, relaxed: both sides move toward each other a block at
     * a time until every step is walkable (or nothing can move: a road or build edge on one side moves the other).
     */
    private fun cliffs(ground: Ground, maxStep: Int, session: EditSession): String {
        val h = HashMap<Long, Int>()
        ground.y.forEach { (k, y) -> h[k] = y }
        // The area's border stays: it must still meet the land outside.
        val b = ground.grid.box
        h.keys.filter { k -> kx(k) - b.min.x() < 2 || b.max.x() - kx(k) < 2 || kz(k) - b.min.z() < 2 || b.max.z() - kz(k) < 2 }.forEach { ground.pinned += it }
        // The neighbours a step is measured against: ground and roads, not builds or water.
        fun level(k: Long): Int? = h[k]?.takeIf { k !in ground.built && k !in ground.water }
        var active = h.keys.filter { k -> ground.free(k) && FACES.any { (dx, dz) -> level(key(kx(k) + dx, kz(k) + dz))?.let { abs(it - h[k]!!) > maxStep } == true } }.toHashSet()
        val cliffs = active.size
        var rounds = 0
        while (active.isNotEmpty() && rounds++ < 80) {
            val next = HashSet<Long>()
            val moves = HashMap<Long, Int>()
            for (k in active) {
                if (!ground.free(k) || k in ground.pinned) continue
                val y = h[k]!!
                var up = 0; var down = 0
                for ((dx, dz) in FACES) {
                    val o = level(key(kx(k) + dx, kz(k) + dz)) ?: continue
                    if (o - y > maxStep) up++
                    if (y - o > maxStep) down++
                }
                if (up > down) moves[k] = y + 1 else if (down > up) moves[k] = y - 1
            }
            for ((k, y) in moves) {
                h[k] = y
                next += k
                FACES.forEach { (dx, dz) -> key(kx(k) + dx, kz(k) + dz).let { if (ground.free(it)) next += it } }
            }
            active = next.filter { k -> FACES.any { (dx, dz) -> level(key(kx(k) + dx, kz(k) + dz))?.let { abs(it - h[k]!!) > maxStep } == true } }.toHashSet()
        }
        var changed = 0
        for ((k, y) in h) if (ground.free(k) && y != ground.y[k]) { reshape(ground.grid, session, ground, k, y); changed++ }
        val left = h.keys.count { k -> ground.free(k) && FACES.any { (dx, dz) -> level(key(kx(k) + dx, kz(k) + dz))?.let { abs(it - h[k]!!) > maxStep } == true } }
        return "cliffs: $cliffs columns on steps relaxed, $changed columns reshaped" + if (left > 0) ", $left still on a step (against a road, a build or water)" else ""
    }
}
