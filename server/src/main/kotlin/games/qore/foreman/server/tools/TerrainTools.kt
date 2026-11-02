package games.qore.foreman.server.tools

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.convolution.GaussianKernel
import com.sk89q.worldedit.math.convolution.HeightMap
import com.sk89q.worldedit.math.convolution.HeightMapFilter
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.world.block.BlockState
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Blocks
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Checks
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Nature
import games.qore.foreman.server.world.Sync
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import java.util.PriorityQueue
import java.util.Random
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

object TerrainTools {
    private val naturalMask by lazy {
        Material.entries.filter { it.isBlock && !it.name.startsWith("LEGACY_") && Blocks.isNaturalGround(it) }
            .joinToString(",") { it.key.key }
    }

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.FOUNDATION,
            "Fills under a build down to the ground, column by column, so nothing floats on uneven terrain. Every " +
                "column of the box that has blocks gets filled below its lowest block until solid ground.",
            schema {
                vec("from", "A corner of the build's box")
                vec("to", "The opposite corner")
                string("block", "Foundation pattern (default: stone_bricks), e.g. `70%cobblestone,30%mossy_cobblestone`", required = false)
                int("max_depth", "Deepest fill per column (default 24)", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> foundation(plugin, args) },

        Tool(
            ForemanTool.BLEND_TERRAIN,
            "Reshapes the natural ground around a footprint so it meets the build at ground_y and eases back into the " +
                "original terrain over `margin` blocks (raise with fill, lower by carving; builds, trees and water are " +
                "left alone). flatten_inside=true also levels the footprint itself: use it to prepare a plot.",
            schema {
                vec("from", "A corner of the footprint")
                vec("to", "The opposite corner (y is ignored)")
                int("ground_y", "Y of the ground surface around the build (default: the box's lowest y - 1)", required = false)
                int("margin", "Width of the transition ring (default 6)", required = false)
                bool("flatten_inside", "Also level the footprint to ground_y (default false)", required = false)
                string("surface", "Top block pattern of reshaped columns (default: keep each column's own, e.g. grass_block)", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> blend(plugin, args) },

        Tool(
            ForemanTool.SMOOTH_TERRAIN,
            "Smooths the natural ground surface in a box (Gaussian height map, like WorldEdit //smooth). The box must " +
                "contain the surface with some air above. Builds are not touched.",
            schema {
                vec("from", "A corner")
                vec("to", "The opposite corner")
                int("iterations", "Passes (default 2)", required = false)
                int("radius", "Kernel radius (default 2): bigger = broader smoothing", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> smooth(plugin, args) },

        Tool(
            ForemanTool.MAKE_GROUND,
            "Creates natural ground in one call: a round or oval patch of layered terrain (surface, subsurface, rock) " +
                "with optional rolling hills, plants and trees. Use it for a platform, an island, a meadow or a plain, " +
                "or to rebuild a patch of ground. underside=island tapers it into floating rock (void worlds), fill " +
                "fills down to the ground below and carves the ground above the new surface. It overwrites everything " +
                "in its shape: keep it away from builds.",
            schema {
                vec("center", "Center [x, y, z]; y is the surface height (players stand at y + 1)")
                int("radius", "East-west radius, 1..64")
                int("radius_z", "North-south radius for an oval (default: radius)", required = false)
                int("hills", "Height of the hills above the surface, 0 = flat (default 0)", required = false)
                number("edge_noise", "How irregular the outline is, 0..0.5 (default 0.15)", required = false)
                string("surface", "Top block pattern (default grass_block)", required = false)
                string("subsurface", "Pattern under the top (default dirt)", required = false)
                int("depth", "Subsurface layers (default 3)", required = false)
                string("rock", "Pattern below the subsurface (default 80%stone,15%andesite,5%tuff)", required = false)
                enum("underside", listOf("island", "fill"), "island = tapered floating rock, fill = down to the ground below (default: island over void, else fill)", required = false)
                number("plants", "Share of surface blocks with a plant, 0..1 (default 0.2)", required = false)
                string("plant_block", "Plant pattern (default: plains grass and flowers); double plants get their upper half", required = false)
                int("trees", "How many trees (default 0)", required = false)
                string("tree_type", "WorldEdit tree type: oak, birch, spruce, dark_oak, cherry, acacia, pine… (default oak)", required = false)
                array("keep_clear", SchemaBuilder.vecSchema(), "Points [x, y, z] that get no plant or tree (spawn, a door)", required = false)
                int("seed", "Noise seed, to redo the same shape (default: random)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> ground(plugin, args) },

        Tool(
            ForemanTool.GRADE,
            "Shapes the ground for a build like a site engineer: levels a pad (rectangle with rounded corners, or an " +
                "ellipse) at `y` and slopes the ground around it no steeper than max_slope, cutting into hills and " +
                "filling hollows, then eases into the natural terrain. Natural, walkable slopes instead of square " +
                "terraces or cliffs: use it under every build on uneven ground, around a sunken or raised one, and to " +
                "soften a cliff (a pad on the low side with a gentle max_slope). Builds, trees and water are left alone.",
            schema {
                vec("from", "A corner of the pad")
                vec("to", "The opposite corner (y ignored)")
                int("y", "Height of the pad's surface block (players stand at y + 1)")
                number("max_slope", "Steepest rise per horizontal block of the banks: 0.5 = gentle, 1 = 45°, 2 = steep (default 0.6)", required = false)
                int("corner_radius", "Rounded pad corners (default 3)", required = false)
                bool("ellipse", "Oval pad inscribed in the box instead of a rectangle (default false)", required = false)
                bool("flatten_inside", "Level the pad itself (default true; false only shapes the banks)", required = false)
                number("noise", "Irregularity of the banks, 0..1 (default 0.3)", required = false)
                int("reach", "Furthest the banks may go from the pad (default 64): they fade out before it, so a short reach makes steeper ends", required = false)
                string("surface", "Top block pattern of reshaped columns (default: keep each column's)", required = false)
                string("pad_surface", "Top block pattern of the pad (default: surface)", required = false)
                bool("extend", "Grow the pad over the ground next to it that is already at y (an existing plateau larger than the box), so the banks start at its real edge (default true)", required = false)
                enum("mode", listOf("both", "fill", "cut"), "both (default): cut what is above the slope and fill what is below; fill: only raise (a plateau on lower ground, without shaving hills nearby); cut: only lower (a pad dug into a hillside)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> grade(plugin, args) },

        Tool(
            ForemanTool.BUILD_PATH,
            "Lays a walkable path or road through points [[x, z], …] (y ignored): finds a route over the terrain between " +
                "consecutive points (avoiding steep slopes, builds and water) or follows them (route direct/spline), " +
                "gives it a profile no steeper than max_grade, terraforms along it with banks no steeper than bank_slope " +
                "and fills under it (no holes). Under eaves and overhangs it follows the ground. End a path just outside " +
                "a door. Water crossings are reported, not bridged.",
            schema {
                array("points", SchemaBuilder.type("array").apply { add("items", SchemaBuilder.type("integer")) }, "Waypoints [[x, z], …], at least 2")
                int("width", "Path width (default 3)", required = false)
                string("block", "Path pattern (default dirt_path), e.g. `50%dirt_path,30%coarse_dirt,20%gravel`", required = false)
                string("border", "Optional pattern for the outer edge cells", required = false)
                int("max_step", "Max height change per block along the path (default 1)", required = false)
                number("max_grade", "Steepest grade, rise per block (default max_step). Roads: 0.2-0.35. With route auto, a grade under 1 makes the route wind (switchbacks) up steep slopes; the profile cuts and fills to keep it", required = false)
                enum("route", listOf("auto", "direct", "spline"), "auto (default) searches a route between the points; direct follows straight segments through them; spline a smooth curve through them (roads you planned)", required = false)
                number("bank_slope", "Steepest bank beside the path where it cuts or fills (default 1): no walls of dirt along roads", required = false)
                int("bank", "How far the banks may reach beside the path (default 12, max 32); they fade out toward it, so deep cuts need more", required = false)
                string("claim", "Claim the path's cells under this name (kind road) so builds keep off it", required = false)
                prop("ends", SchemaBuilder.type("array").apply { add("items", SchemaBuilder.type("integer")) }, "[y at the first point, y at the last] of the surface: pins the ends to a known level (a road it joins) instead of the ground found there", required = false)
                bool("follow_ground", "false: the profile goes straight from one end's height to the other's (with `ends`), cutting and filling whatever lies between (default true: it follows the ground within max_grade)", required = false)
                bool("relay", "Re-lay an existing road: its paving (concrete, gravel, slabs, stairs…) along the route is reshaped like ground (default false)", required = false)
                bool("slabs", "Half steps as slabs on the surface, so a grade up to 0.5 is walked without jumping (default true)", required = false)
                string("slab", "Slab used by slabs (default: the block's own slab; spruce_slab steps on dirt trails, else polished_andesite_slab)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> path(plugin, args) },
    )

    private val states = HashMap<Material, BlockState>()
    private fun state(m: Material) = synchronized(states) { states.getOrPut(m) { BukkitAdapter.adapt(m.createBlockData()) } }
    private val AIR get() = state(Material.AIR)

    private fun foundation(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        val maxDepth = args.int("max_depth", 24)
        var columns = 0
        var deepest = 0
        var unfinished = 0

        val (_, change) = Edits.edit(world, "foundation under ${box.describe()}") { session, _ ->
            val pattern = Edits.pattern(session, args.string("block", "stone_bricks")!!)
            for (x in box.min.x()..box.max.x()) for (z in box.min.z()..box.max.z()) {
                val bottom = (box.min.y()..box.max.y()).firstOrNull { y ->
                    val m = world.getType(x, y, z)
                    !Blocks.isCover(m) && !Blocks.isLiquid(m)
                } ?: continue

                var y = bottom - 1
                var depth = 0
                while (y > world.minHeight) {
                    val m = world.getType(x, y, z)
                    if (!Blocks.isCover(m) && !Blocks.isLiquid(m)) break
                    if (depth == maxDepth) {
                        unfinished++
                        break
                    }
                    session.setBlock(BlockVector3.at(x, y, z), pattern)
                    y--
                    depth++
                }
                if (depth > 0) columns++
                deepest = max(deepest, depth)
            }
        }
        return ToolResult.text(
            "Filled $columns columns, deepest $deepest blocks, ${change.blocks} blocks." +
                if (unfinished > 0) " $unfinished columns did not reach the ground within max_depth." else ""
        )
    }

    /**
     * Raises or lowers one natural column to [target]: fills with [sub] and caps with [top], or carves down. Plants
     * that would float or be buried are removed. Returns false when the column is not natural ground.
     */
    private fun reshape(
        session: EditSession, world: World, x: Int, z: Int, s: Blocks.Surface, target: Int, top: Pattern?, sub: BlockState,
        diggable: (Material) -> Boolean = { false },
    ): Boolean {
        val topPattern = top ?: state(s.material)
        if (target > s.y) {
            for (y in s.y until target) session.setBlock(x, y, z, sub)
        } else if (target < s.y) {
            for (y in target + 1..s.y) {
                val m = world.getType(x, y, z)
                // Rubble in the rock layers of generated ground is terrain too.
                if (!Blocks.isNaturalGround(m) && m != Material.COBBLESTONE && m != Material.MOSSY_COBBLESTONE && !diggable(m)) return false
            }
            for (y in target + 1..s.y) session.setBlock(x, y, z, AIR)
        }
        session.setBlock(BlockVector3.at(x, target, z), topPattern)

        // Plants standing on the old surface or the new one.
        var y = max(s.y, target) + 1
        while (y < world.maxHeight && Blocks.isPlant(world.getType(x, y, z))) {
            session.setBlock(x, y, z, AIR)
            y++
        }
        return true
    }

    private fun blend(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        val groundY = args.int("ground_y", box.min.y() - 1)
        val margin = args.int("margin", 6).coerceIn(1, 32)
        val flatten = args.bool("flatten_inside", false)
        if ((box.sizeX + 2L * margin) * (box.sizeZ + 2L * margin) > 512 * 512) throw ToolError("Footprint + margin above 512x512")

        var reshaped = 0
        var skipped = 0
        val (_, change) = Edits.edit(world, "blend terrain around ${box.describe()}") { session, _ ->
            val top = args.string("surface", null)?.let { Edits.pattern(session, it) }
            for (x in box.min.x() - margin..box.max.x() + margin) for (z in box.min.z() - margin..box.max.z() + margin) {
                val dx = max(max(box.min.x() - x, x - box.max.x()), 0)
                val dz = max(max(box.min.z() - z, z - box.max.z()), 0)
                val d = hypot(dx.toDouble(), dz.toDouble())
                val inside = dx == 0 && dz == 0
                if ((inside && !flatten) || d > margin) continue

                val s = Blocks.ground(world, x, z)
                if (s.underLiquid || !s.natural) {
                    skipped++
                    continue
                }
                val t = d / (margin + 1)
                val ease = t * t * (3 - 2 * t)
                val target = (groundY + (s.y - groundY) * ease).roundToInt()
                if (target == s.y) continue
                if (reshape(session, world, x, z, s, target, top, state(Blocks.subsurface(s.material)))) reshaped++ else skipped++
            }
        }
        return ToolResult.text(
            "Reshaped $reshaped columns (${change.blocks} blocks) toward y=$groundY over $margin blocks" +
                (if (flatten) ", footprint levelled" else "") + ". Skipped $skipped columns (builds, trees, water)."
        )
    }

    private fun smooth(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxEditBlocks.toLong(), "smooth_terrain")
        val iterations = args.int("iterations", 2).coerceIn(1, 10)
        val radius = args.int("radius", 2).coerceIn(1, 8)
        // A column without ground counts as the bottom of the box and drags its neighbours down.
        val bare = Sync.main {
            (box.min.x()..box.max.x()).sumOf { x ->
                (box.min.z()..box.max.z()).count { z -> (box.min.y()..box.max.y()).none { y -> Blocks.isNaturalGround(world.getType(x, y, z)) } }
            }
        }
        if (bare > 0) throw ToolError(
            "$bare columns of the box have no natural ground between y=${box.min.y()} and y=${box.max.y()} (void, water, " +
                "builds or ground below the box): smoothing would pull the surface down toward them. Shrink the box to " +
                "the ground or lower its bottom."
        )
        val (_, change) = Edits.edit(world, "smooth terrain ${box.describe()}") { session, _ ->
            val region = CuboidRegion(session.world, box.min, box.max)
            HeightMap(session, region, Edits.mask(session, naturalMask))
                .applyFilter(HeightMapFilter(GaussianKernel(radius, 1.0)), iterations)
        }
        return ToolResult.text("Smoothed ${box.describe()}: ${change.blocks} blocks changed.")
    }

    private fun ground(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val center = args.vec("center")
        val cx = center.x()
        val cy = center.y()
        val cz = center.z()
        val rx = args.int("radius").coerceIn(1, 64)
        val rz = args.int("radius_z", rx).coerceIn(1, 64)
        val hills = args.int("hills", 0).coerceIn(0, 32)
        val edgeNoise = args.double("edge_noise", 0.15).coerceIn(0.0, 0.5)
        val depth = args.int("depth", 3).coerceIn(0, 16)
        val plantShare = args.double("plants", 0.2).coerceIn(0.0, 1.0)
        val treeCount = args.int("trees", 0).coerceIn(0, 64)
        val treeType = Nature.treeType(args.string("tree_type", "oak")!!)
        val keepClear = if (args.has("keep_clear")) args.array("keep_clear").map { Args.vecOf(it) } else emptyList()
        val seed = args.int("seed", Random().nextInt(1_000_000)).toLong()
        val islandDepth = max(4, (max(rx, rz) * 0.9).roundToInt())
        fun cleared(x: Int, z: Int) = keepClear.any { abs(it.x() - x) <= 1 && abs(it.z() - z) <= 1 }

        val tops = HashMap<Long, Int>()
        var lowest = cy
        var trees = 0
        var plants = 0
        lateinit var underside: String
        val (_, change) = Edits.scoped(world, "ground r=$rx${if (rz != rx) "x$rz" else ""} at ${Box.fmt(center)}") { scope ->
            val session = scope.session
            val surface = Edits.pattern(session, args.string("surface", "grass_block")!!)
            val sub = Edits.pattern(session, args.string("subsurface", "dirt")!!)
            val rock = Edits.pattern(session, args.string("rock", "80%stone,15%andesite,5%tuff")!!)
            val plant = Edits.pattern(session, args.string("plant_block", Nature.PLAINS_PLANTS)!!)
            underside = args.string("underside", null) ?: if (Blocks.surface(world, cx, cz).material.isAir) "island" else "fill"

            for (x in cx - rx..cx + rx) for (z in cz - rz..cz + rz) {
                val dx = (x - cx) / (rx + 0.5)
                val dz = (z - cz) / (rz + 0.5)
                val d = sqrt(dx * dx + dz * dz) / (1 - edgeNoise * Nature.noise(seed + 3, x.toDouble(), z.toDouble(), 5.0))
                if (d > 1) continue
                val inner = 1 - d * d
                val bump = ((Nature.fractal(seed, x.toDouble(), z.toDouble(), max(rx, rz) / 2.0 + 4) - 0.4) / 0.6).coerceAtLeast(0.0)
                val top = cy + (hills * bump * min(1.0, inner * 2)).roundToInt()

                val bottom = if (underside == "island") {
                    cy - depth - 1 - (islandDepth * sqrt(inner) * (0.7 + 0.6 * Nature.noise(seed + 5, x.toDouble(), z.toDouble(), 4.0))).roundToInt()
                } else {
                    var y = top - 1
                    while (y > max(world.minHeight, top - 64) && (Blocks.isCover(world.getType(x, y, z)) || Blocks.isLiquid(world.getType(x, y, z)))) y--
                    y + 1
                }
                if (underside == "fill") {
                    val s = Blocks.surface(world, x, z)
                    var y = top + 1
                    while (y <= s.y + 1 && (Blocks.isNaturalGround(world.getType(x, y, z)) || Blocks.isPlant(world.getType(x, y, z)))) {
                        session.setBlock(x, y, z, AIR)
                        y++
                    }
                }
                for (y in bottom..top) {
                    session.setBlock(BlockVector3.at(x, y, z), if (y == top) surface else if (y >= top - depth) sub else rock)
                }
                tops[key(x, z)] = top
                lowest = min(lowest, bottom)
            }

            // Trees first so plants do not take their spot, away from the edge and from each other. Tree generation
            // reads the world, which has not seen the ground yet: commit it first.
            scope.flush()
            val random = Random(seed)
            val spots = tops.keys.shuffled(random).filter { k ->
                val x = (k shr 32).toInt()
                val z = k.toInt()
                hypot((x - cx) / (rx + 0.5), (z - cz) / (rz + 0.5)) < 0.75 && !cleared(x, z)
            }
            val placed = ArrayList<Pair<Int, Int>>()
            for (k in spots) {
                if (placed.size == treeCount) break
                val x = (k shr 32).toInt()
                val z = k.toInt()
                if (placed.any { hypot((it.first - x).toDouble(), (it.second - z).toDouble()) < 6 }) continue
                if (Nature.tree(scope.session, BlockVector3.at(x, tops.getValue(k) + 1, z), treeType)) placed += x to z
            }
            trees = placed.size
            if (placed.isNotEmpty()) scope.flush()

            tops.forEach { (k, top) ->
                val x = (k shr 32).toInt()
                val z = k.toInt()
                if (cleared(x, z) || Nature.random(seed, x, z) >= plantShare) return@forEach
                if (Nature.plant(scope.session, BlockVector3.at(x, top + 1, z), plant)) plants++
            }
        }

        val highest = tops.values.maxOrNull() ?: cy
        val bounds = Box(BlockVector3.at(cx - rx, lowest, cz - rz), BlockVector3.at(cx + rx, highest + if (trees > 0) 10 else 2, cz + rz))
        // The image of a floating island shows its top and the start of the underside, not the rock cone.
        val view = Box(BlockVector3.at(cx - rx, max(lowest, cy - 6), cz - rz), bounds.max)
        val text = "Ground of ${tops.size} columns, surface y=$cy" + (if (highest > cy) " with hills up to y=$highest" else "") +
            ", underside $underside down to y=$lowest; $plants plants, $trees/$treeCount trees. ${change.blocks} blocks, seed $seed. " +
            "Bounds ${bounds.describe()}." + (if (trees < treeCount) " Too little room for all the trees: add more with the edit op tree." else "")
        return InspectTools.result(plugin, world, text, view, args, "ground made by make_ground", bounds)
    }

    // ---- Paths --------------------------------------------------------------------------------------------------

    private fun key(x: Int, z: Int) = (x.toLong() shl 32) or (z.toLong() and 0xffffffffL)

    private class Terrain(val world: World) {
        private val cache = HashMap<Long, Blocks.Surface>()
        operator fun get(x: Int, z: Int): Blocks.Surface = cache.getOrPut(key(x, z)) { Blocks.ground(world, x, z) }
    }

    /**
     * A* over columns. Builds are passable but costly (existing roads), water is penalized. With [grade] under 1 the
     * state also counts the level cells walked since the last 1-block rise: rising again before 1/grade of them is
     * costly, so on a steep hillside the cheapest route winds up it in switchbacks.
     */
    private fun route(terrain: Terrain, sx: Int, sz: Int, ex: Int, ez: Int, grade: Double = 1.0): List<Pair<Int, Int>> {
        val run = if (grade >= 1.0) 0 else kotlin.math.ceil(1 / grade).toInt().coerceAtMost(8)
        val pad = if (run > 0) 48 else 24
        val minX = min(sx, ex) - pad
        val maxX = max(sx, ex) + pad
        val minZ = min(sz, ez) - pad
        val maxZ = max(sz, ez) + pad

        // State: column + level cells since the last rise (capped at run).
        fun state(k: Long, flat: Int) = k * 16 + flat
        val g = HashMap<Long, Double>()
        val came = HashMap<Long, Long>()
        val open = PriorityQueue<Pair<Long, Double>>(compareBy { it.second })
        fun h(x: Int, z: Int) = hypot((x - ex).toDouble(), (z - ez).toDouble())

        val start = state(key(sx, sz), run)
        val goal = key(ex, ez)
        g[start] = 0.0
        open.add(start to h(sx, sz))
        var expanded = 0
        var reached: Long? = null
        while (open.isNotEmpty()) {
            val (current, _) = open.poll()
            val cell = current shr 4
            if (cell == goal) { reached = current; break }
            if (++expanded > 600_000) throw ToolError("No route found between [$sx, $sz] and [$ex, $ez] (search too large): add waypoints")
            val flat = (current and 15).toInt()
            val cx = (cell shr 32).toInt()
            val cz = cell.toInt()
            val here = terrain[cx, cz]
            for (dx in -1..1) for (dz in -1..1) {
                if (dx == 0 && dz == 0) continue
                val nx = cx + dx
                val nz = cz + dz
                if (nx !in minX..maxX || nz !in minZ..maxZ) continue
                val next = terrain[nx, nz]
                val dh = abs(next.y - here.y)
                val len = if (dx != 0 && dz != 0) 1.414 else 1.0
                var cost = len + dh * 3.0 + max(0, dh - 1) * 12.0
                var nflat = if (dh == 0) min(run, flat + 1) else 0
                // Rising (or dropping) again too soon: steeper than the grade.
                if (run > 0 && dh > 0 && flat < run) cost += (run - flat) * 6.0
                if (next.underLiquid) cost += 40.0
                val nk = key(nx, nz)
                // Roads and paving are cheap to follow; builds (walls, roofs, fences) are gone around.
                if (!next.natural && nk != goal) cost += if (Checks.DEFAULT_ROADS(next.material) || next.material.name.endsWith("_SLAB")) 0.5 else 400.0
                if (run == 0) nflat = 0
                val ns = state(nk, nflat)
                val ng = g.getValue(current) + cost
                if (ng < (g[ns] ?: Double.MAX_VALUE)) {
                    g[ns] = ng
                    came[ns] = current
                    open.add(ns to ng + h(nx, nz))
                }
            }
        }
        if (reached == null) throw ToolError("No route found between [$sx, $sz] and [$ex, $ez]")

        val path = ArrayList<Pair<Int, Int>>()
        var k: Long? = reached
        while (k != null) {
            val c = k shr 4
            path.add((c shr 32).toInt() to c.toInt())
            k = came[k]
        }
        return path.reversed()
    }

    /** Cells of straight segments through [points] (8-connected). */
    private fun polyline(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        points.zipWithNext().forEach { (a, b) ->
            val n = max(abs(b.first - a.first), abs(b.second - a.second))
            for (i in (if (out.isEmpty()) 0 else 1)..n) {
                val t = if (n == 0) 0.0 else i.toDouble() / n
                out += (a.first + (b.first - a.first) * t).roundToInt() to (a.second + (b.second - a.second) * t).roundToInt()
            }
        }
        return out.distinct()
    }

    /** Cells of a Catmull-Rom curve through [points]. */
    private fun spline(points: List<Pair<Int, Int>>): List<Pair<Int, Int>> {
        val p = listOf(points.first()) + points + listOf(points.last())
        val out = ArrayList<Pair<Int, Int>>()
        for (i in 1 until p.size - 2) {
            val (p0, p1, p2, p3) = listOf(p[i - 1], p[i], p[i + 1], p[i + 2])
            val steps = max(2, (hypot((p2.first - p1.first).toDouble(), (p2.second - p1.second).toDouble()) * 3).toInt())
            for (k in 0..steps) {
                val t = k.toDouble() / steps
                fun cr(a: Int, b: Int, c: Int, d: Int) = 0.5 * (2 * b + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t * t + (-a + 3 * b - 3 * c + d) * t * t * t)
                val cell = cr(p0.first, p1.first, p2.first, p3.first).roundToInt() to cr(p0.second, p1.second, p2.second, p3.second).roundToInt()
                if (out.lastOrNull() != cell) {
                    // Keep the cells 8-connected.
                    out.lastOrNull()?.let { last -> if (max(abs(last.first - cell.first), abs(last.second - cell.second)) > 1) out.addAll(polyline(listOf(last, cell)).drop(1).dropLast(1)) }
                    out += cell
                }
            }
        }
        return out.distinct()
    }

    fun path(plugin: Foreman, args: Args): ToolResult {
        var bounds: Box? = null
        val world = plugin.world(args.string("world", null))
        val points = args.array("points").map {
            val a = it.asJsonArray
            when (a.size()) {
                2 -> a[0].asInt to a[1].asInt
                3 -> a[0].asInt to a[2].asInt
                else -> throw ToolError("Each point is [x, z]")
            }
        }
        if (points.size < 2) throw ToolError("At least 2 points")
        val width = args.int("width", 3).coerceIn(1, 9)
        val maxStep = args.int("max_step", 1).coerceIn(1, 3)
        val grade = args.double("max_grade", maxStep.toDouble()).coerceIn(0.05, 3.0)
        val bankSlope = args.double("bank_slope", 1.0).coerceIn(0.2, 4.0)
        val bankReach = args.int("bank", 12).coerceIn(0, 32)
        val radius = (width - 1) / 2.0
        val claimName = args.string("claim", null)
        val relay = args.bool("relay", false)
        // Paving of the road being re-laid: dug and filled like ground.
        val paving: (Material) -> Boolean = { m ->
            relay && (Checks.DEFAULT_ROADS(m) || m.name.endsWith("_SLAB") || m.name.endsWith("_STAIRS") || m == Material.ANDESITE || m == Material.STONE ||
                Tag.LOGS.isTagged(m) || Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || m == Material.IRON_BARS || m.name.endsWith("CHAIN"))
        }

        var water = 0
        var built = 0
        var length = 0
        var banks = 0
        var steepest = 0.0
        val label = "path through ${points.joinToString(" → ") { "[${it.first}, ${it.second}]" }}"
        val (_, change) = Edits.edit(world, label) { session, _ ->
            val top = Edits.pattern(session, args.string("block", "dirt_path")!!)
            val border = args.string("border", null)?.let { Edits.pattern(session, it) }
            val terrain = Terrain(world)

            val cells = when (args.string("route", "auto")) {
                "direct" -> polyline(points)
                "spline" -> spline(points)
                else -> ArrayList<Pair<Int, Int>>().also { cells ->
                    points.zipWithNext().forEach { (a, b) ->
                        val segment = route(terrain, a.first, a.second, b.first, b.second, grade)
                        cells.addAll(if (cells.isEmpty()) segment else segment.drop(1))
                    }
                }
            }
            length = cells.size
            val r = radius.toInt() + 1

            // Height profile: averaged, then limited to `grade` per block both ways (cuts and fills). Build cells (left
            // as they are) take the height of the nearest natural cell: a roof or a wall must not pull the path up.
            val natural = cells.map { (x, z) -> terrain[x, z].let { if (it.natural || it.underLiquid || paving(it.material)) it.y else null } }
            if (natural.all { it == null }) throw ToolError("The route has no natural ground: add waypoints off the builds")
            val ground = natural.indices.map { i ->
                natural[i] ?: natural.indices.filter { natural[it] != null }.minBy { abs(it - i) }.let { natural[it]!! }
            }
            val window = if (grade < 0.6) 4 else 2
            val profile = ground.indices.map { i -> ground.subList(max(0, i - window), min(ground.size, i + window + 1)).average() }.toDoubleArray()
            profile[0] = ground.first().toDouble()
            profile[profile.size - 1] = ground.last().toDouble()
            if (args.has("ends")) args.array("ends").let { e ->
                profile[0] = e[0].asDouble
                profile[profile.size - 1] = e[1].asDouble
            }
            if (!args.bool("follow_ground", true)) {
                // Straight between the ends, by distance along the route.
                var total = 0.0
                for (i in 1 until cells.size) total += hypot((cells[i].first - cells[i - 1].first).toDouble(), (cells[i].second - cells[i - 1].second).toDouble())
                var along = 0.0
                for (i in 1 until profile.size - 1) {
                    along += hypot((cells[i].first - cells[i - 1].first).toDouble(), (cells[i].second - cells[i - 1].second).toDouble())
                    profile[i] = profile[0] + (profile[profile.size - 1] - profile[0]) * along / total
                }
            }
            val step = DoubleArray(cells.size) { i -> if (i == 0) 0.0 else hypot((cells[i].first - cells[i - 1].first).toDouble(), (cells[i].second - cells[i - 1].second).toDouble()) }
            val limit = min(grade, maxStep.toDouble())
            // The ends stay where they are (the ground there, or `ends`): both passes work between them.
            for (i in 1 until profile.size - 1) profile[i] = profile[i].coerceIn(profile[i - 1] - limit * step[i], profile[i - 1] + limit * step[i])
            for (i in profile.size - 2 downTo 1) profile[i] = profile[i].coerceIn(profile[i + 1] - limit * step[i + 1], profile[i + 1] + limit * step[i + 1])
            val slabs = args.bool("slabs", true)
            // With slabs, the surface block is the profile's floor and a half step on top carries the fraction.
            val heights = profile.map { if (slabs) kotlin.math.floor(it + 0.25).toInt() else it.roundToInt() }
            val half = profile.map { slabs && it + 0.25 - kotlin.math.floor(it + 0.25) >= 0.5 }
            for (i in 4 until heights.size) steepest = max(steepest, abs(heights[i] - heights[i - 4]) / (step[i - 3] + step[i - 2] + step[i - 1] + step[i]))

            bounds = Box(
                BlockVector3.at(cells.minOf { it.first } - r - bankReach, min(heights.min(), cells.minOf { terrain[it.first, it.second].y }) - 1, cells.minOf { it.second } - r - bankReach),
                BlockVector3.at(cells.maxOf { it.first } + r + bankReach, max(heights.max(), cells.maxOf { terrain[it.first, it.second].y }) + 3, cells.maxOf { it.second } + r + bankReach),
            )

            // Widen: every cell takes the height of the nearest route cell.
            data class Target(val y: Int, val distance: Double, val half: Boolean = false)
            val targets = HashMap<Long, Target>()
            cells.forEachIndexed { i, (x, z) ->
                for (dx in -r..r) for (dz in -r..r) {
                    val d = sqrt((dx * dx + dz * dz).toDouble())
                    if (d > radius + 0.5) continue
                    val k = key(x + dx, z + dz)
                    val current = targets[k]
                    if (current == null || d < current.distance) targets[k] = Target(heights[i], d, half[i])
                }
            }
            val slabBlock = if (!slabs) null else Edits.pattern(session, (args.string("slab", null) ?: run {
                val id = args.string("block", "dirt_path")!!.substringAfterLast('%').substringBefore(',').substringBefore('[')
                listOf("${id}_slab", "${id.removeSuffix("s")}_slab").firstOrNull { Material.matchMaterial(it) != null }
                    // Dirt trails get wooden steps.
                    ?: if (id in listOf("dirt_path", "coarse_dirt", "dirt", "grass_block", "rooted_dirt", "podzol")) "spruce_slab" else "polished_andesite_slab"
            }) + "[type=bottom]")

            targets.forEach { (k, t) ->
                val x = (k shr 32).toInt()
                val z = k.toInt()
                val s = terrain[x, z]
                if (s.underLiquid) {
                    water++
                    return@forEach
                }
                if (!s.natural && !paving(s.material)) {
                    built++
                    return@forEach
                }
                val edge = border != null && radius >= 1 && t.distance > radius - 0.5
                // Old slabs and steps on a re-laid road go: the new surface has its own.
                if (relay) for (y in s.y + 1..s.y + 2) if (paving(world.getType(x, y, z))) session.setBlock(x, y, z, AIR)
                val sub = if (s.natural) state(Blocks.subsurface(s.material)) else state(Material.STONE)
                if (!reshape(session, world, x, z, s, t.y, if (edge) border else top, sub, paving)) {
                    built++
                    return@forEach
                }
                // Headroom: low leaves and plants over the path.
                for (y in t.y + 1..t.y + 4) {
                    val m = world.getType(x, y, z)
                    if (Blocks.isPlant(m) || Tag.LEAVES.isTagged(m)) session.setBlock(x, y, z, AIR)
                }
                if (t.half && slabBlock != null) session.setBlock(BlockVector3.at(x, t.y + 1, z), slabBlock)
            }

            // Banks: the ground beside the path no steeper than bank_slope toward it, so cuts and fills have slopes, not walls.
            if (bankReach > 0) {
                val bank = HashMap<Long, Target>()
                targets.forEach { (k, t) ->
                    val x = (k shr 32).toInt()
                    val z = k.toInt()
                    for (dx in -bankReach..bankReach) for (dz in -bankReach..bankReach) {
                        val nk = key(x + dx, z + dz)
                        if (nk in targets) continue
                        val d = sqrt((dx * dx + dz * dz).toDouble())
                        if (d > bankReach + 0.5) continue
                        val current = bank[nk]
                        if (current == null || d < current.distance) bank[nk] = Target(t.y, d)
                    }
                }
                // Other claims (builds, yards, roads) keep their ground: the banks stop at their edge.
                val others = plugin.worlds.reserved(world.name).filter { it.name != claimName }.flatMap { it.boxes }
                bank.forEach { (k, t) ->
                    val x = (k shr 32).toInt()
                    val z = k.toInt()
                    val s = terrain[x, z]
                    // Banks are ground only: yards and other roads beside the route keep their paving.
                    if (s.underLiquid || !s.natural) return@forEach
                    if (others.any { it.contains(x, s.y, z) }) return@forEach
                    val reach = bankSlope * t.distance
                    // Fading out over the last 30% of the reach: where the bank stops it still meets the terrain.
                    val r = t.distance / bankReach
                    val fade = if (r < 0.7) 1.0 else ((1 - r) / 0.3).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
                    val clamped = s.y.toDouble().coerceIn(t.y - reach, t.y + reach)
                    val target = (s.y + (clamped - s.y) * fade).roundToInt()
                    if (target != s.y && reshape(session, world, x, z, s, target, null, state(Blocks.subsurface(s.material)))) banks++
                }
            }

            if (claimName != null) cells.chunked(16).forEachIndexed { i, chunk ->
                val ys = chunk.indices.map { heights[i * 16 + it] }
                plugin.worlds.reserve(
                    claimName, world.name,
                    Box(
                        BlockVector3.at(chunk.minOf { it.first } - r + 1, ys.min() - 1, chunk.minOf { it.second } - r + 1),
                        BlockVector3.at(chunk.maxOf { it.first } + r - 1, ys.max() + 4, chunk.maxOf { it.second } + r - 1),
                    ),
                    "road",
                )
            }
        }
        return InspectTools.result(
            plugin, world,
            "Path laid along $length route cells, ${change.blocks} blocks changed, steepest grade ${(steepest * 100).roundToInt()}% over 4 blocks" +
                (if (banks > 0) ", $banks bank columns reshaped" else "") + "." +
                (if (built > 0) " $built cells over builds/roads were left as they are." else "") +
                (if (water > 0) " $water cells cross water and were NOT built: add a bridge with edit." else "") +
                (if (steepest > grade + 0.26) " The ends are too far apart in height for max_grade: add waypoints for a longer (switchback) route." else ""),
            bounds, args, "path",
        )
    }

    // ---- Grading ---------------------------------------------------------------------------------------------------

    fun grade(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val pad = Box.of(args.vec("from"), args.vec("to"))
        val y = args.int("y")
        val slope = args.double("max_slope", 0.6).coerceIn(0.15, 4.0)
        val radius = args.int("corner_radius", 3).coerceAtLeast(0).toDouble()
        val ellipse = args.bool("ellipse", false)
        val flatten = args.bool("flatten_inside", true)
        val noise = args.double("noise", 0.3).coerceIn(0.0, 1.0)
        val reachMax = args.int("reach", 64).coerceIn(4, 64)
        val mode = args.string("mode", "both")!!
        val seed = Random().nextLong()
        if ((pad.sizeX + 2L * reachMax) * (pad.sizeZ + 2L * reachMax) > 480L * 480) throw ToolError("Pad + reach above 480x480: grade in parts or lower reach")

        val cx = (pad.min.x() + pad.max.x() + 1) / 2.0
        val cz = (pad.min.z() + pad.max.z() + 1) / 2.0
        val hx = pad.sizeX / 2.0
        val hz = pad.sizeZ / 2.0
        // Distance from a column's center to the pad (0 inside).
        fun outside(x: Int, z: Int): Double {
            val px = abs(x + 0.5 - cx)
            val pz = abs(z + 0.5 - cz)
            if (ellipse) {
                val e = sqrt((px / hx) * (px / hx) + (pz / hz) * (pz / hz))
                return if (e <= 1) 0.0 else (e - 1) * min(hx, hz)
            }
            val r = min(radius, min(hx, hz))
            val qx = max(0.0, px - hx + r)
            val qz = max(0.0, pz - hz + r)
            return max(0.0, hypot(qx, qz) - r)
        }

        val minX = pad.min.x() - reachMax
        val maxX = pad.max.x() + reachMax
        val minZ = pad.min.z() - reachMax
        val maxZ = pad.max.z() + reachMax
        val ground = Sync.main {
            HashMap<Long, Blocks.Surface>().also { m -> for (x in minX..maxX) for (z in minZ..maxZ) m[key(x, z)] = Blocks.ground(world, x, z) }
        }
        // The pad: the box (rounded or oval), grown over the ground already at its level when extend. Then each
        // column's distance to it (chamfer distance transform), from which the banks are sloped.
        val sx = maxX - minX + 1
        val sz = maxZ - minZ + 1
        val dist = DoubleArray(sx * sz) { Double.MAX_VALUE }
        fun at(x: Int, z: Int) = (x - minX) * sz + (z - minZ)
        val queue = ArrayDeque<Pair<Int, Int>>()
        for (x in minX..maxX) for (z in minZ..maxZ) if (outside(x, z) == 0.0) { dist[at(x, z)] = 0.0; queue += x to z }
        if (args.bool("extend", true)) while (queue.isNotEmpty()) {
            val (x, z) = queue.removeFirst()
            for ((dx, dz) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val nx = x + dx
                val nz = z + dz
                if (nx !in minX..maxX || nz !in minZ..maxZ || dist[at(nx, nz)] == 0.0) continue
                val g = ground.getValue(key(nx, nz))
                if (g.natural && !g.underLiquid && g.y == y) { dist[at(nx, nz)] = 0.0; queue += nx to nz }
            }
        }
        for (pass in 0..1) {
            val xs = if (pass == 0) minX..maxX else maxX downTo minX
            val zs = if (pass == 0) minZ..maxZ else maxZ downTo minZ
            for (x in xs) for (z in zs) {
                var d = dist[at(x, z)]
                for ((dx, dz, w) in listOf(Triple(-1, 0, 1.0), Triple(0, -1, 1.0), Triple(-1, -1, 1.414), Triple(-1, 1, 1.414))) {
                    val nx = if (pass == 0) x + dx else x - dx
                    val nz = if (pass == 0) z + dz else z - dz
                    if (nx in minX..maxX && nz in minZ..maxZ) d = min(d, dist[at(nx, nz)] + w)
                }
                dist[at(x, z)] = d
            }
        }

        // Targets as offsets from the ground: the pad's height on it, around it the ground clamped into the cone of
        // max_slope from the pad's edge (continuous distance: even slopes, not terraces).
        val offset = HashMap<Long, Double>()
        for (x in minX..maxX) for (z in minZ..maxZ) {
            val s = ground.getValue(key(x, z))
            if (s.underLiquid || !s.natural) continue
            val d = dist[at(x, z)]
            if (d == 0.0) {
                if (flatten) offset[key(x, z)] = (y - s.y).toDouble()
                continue
            }
            val wobble = 1 + noise * (Nature.fractal(seed, x.toDouble(), z.toDouble(), 7.0) - 0.5) * 1.2
            val limit = slope * d * wobble
            val delta = s.y - y
            if (abs(delta) <= limit) continue
            if ((delta > 0 && mode == "fill") || (delta < 0 && mode == "cut")) continue
            // Fading out over the last 30% of the reach: where the banks are cut off they still meet the terrain.
            val r = d / reachMax
            val fade = if (r < 0.7) 1.0 else ((1 - r) / 0.3).coerceIn(0.0, 1.0).let { it * it * (3 - 2 * it) }
            offset[key(x, z)] = ((y + (if (delta > 0) limit else -limit)) - s.y) * fade
        }

        var cut = 0
        var fill = 0
        var maxCut = 0
        var maxFill = 0
        var reach = 0.0
        var touched: Box? = null
        val claimed = plugin.worlds.reserved(world.name).filter { c -> c.boxes.none { b -> b.intersect(pad.grow(0, 64, 0)) != null } }.flatMap { it.boxes }
        val label = "grade pad ${pad.min.x()}..${pad.max.x()} x ${pad.min.z()}..${pad.max.z()} at y=$y"
        val (_, change) = Edits.edit(world, label) { session, _ ->
            val surface = args.string("surface", null)?.let { Edits.pattern(session, it) }
            val padSurface = args.string("pad_surface", null)?.let { Edits.pattern(session, it) } ?: surface
            offset.forEach { (k, o) ->
                val target = (ground.getValue(k).y + o).roundToInt()
                val x = (k shr 32).toInt()
                val z = k.toInt()
                val s = ground.getValue(k)
                if (target == s.y) return@forEach
                val inside = dist[at(x, z)] == 0.0
                // Roads and other builds' claims keep their ground (claims around the pad itself don't count).
                if (!inside && claimed.any { it.contains(x, s.y, z) }) return@forEach
                if (!reshape(session, world, x, z, s, target, if (inside) padSurface else surface, state(Blocks.subsurface(s.material)))) return@forEach
                if (target < s.y) { cut++; maxCut = max(maxCut, s.y - target) } else { fill++; maxFill = max(maxFill, target - s.y) }
                reach = max(reach, dist[at(x, z)])
                val p = BlockVector3.at(x, min(target, s.y), z)
                val q = BlockVector3.at(x, max(target, s.y) + 1, z)
                touched = touched?.union(Box(p, q)) ?: Box(p, q)
            }
        }
        val text = "Graded: ${cut} columns cut (up to $maxCut), $fill filled (up to $maxFill), banks reach ${reach.roundToInt()} blocks " +
            "from the pad at max slope $slope. ${change.blocks} blocks changed." +
            (if (reach >= reachMax * 0.7) " The banks needed more than 70% of `reach` ($reachMax) and were faded out toward it: steeper there, raise reach or max_slope." else "")
        return InspectTools.result(plugin, world, text, touched?.grow(1, 2, 1), args, label)
    }
}
