package games.qore.foreman.server.tools

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
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
import games.qore.foreman.server.world.Camera
import games.qore.foreman.server.world.Checks
import games.qore.foreman.server.world.Displays
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Grid
import games.qore.foreman.server.world.Plans
import games.qore.foreman.server.world.Renderer
import games.qore.foreman.server.world.Resources
import games.qore.foreman.server.world.Sync
import games.qore.foreman.server.world.Walk
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.block.data.type.Door
import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Quality control: the checks, plans, first-person views and claims that catch what renders from above miss. */
object QualityTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.CHECK,
            "Inspects an area like a player would and lists what is wrong, with coordinates. Run it after every build " +
                "step that matters and before saying a place is done. Checks: walk (rooms, floors, seats, containers and " +
                "terrain that can't be reached from outside; indoor steps that need a jump; pits and rivers you can't " +
                "climb out of), doors (blocked or stepped door sides, double door hinges, furniture in front of doors and " +
                "doorways), ground (builds sunk into or floating above the terrain), terrain (cliffs of 2+ blocks), roads " +
                "(steps, grades above max_grade, holes), furniture (models inside walls, fronts facing a wall), floating " +
                "(blocks hanging in the air), claims (overlapping claims). Give the area with from/to or an existing mark " +
                "(`area`); the box is grown by 3 blocks around, keep it on the place (up to a few hundred blocks wide).",
            schema {
                vec("from", "A corner (or use area)", required = false)
                vec("to", "The opposite corner", required = false)
                string("area", "A mark to check instead of from/to", required = false)
                strings("checks", "Subset of: ${Checks.ALL.joinToString(", ")} (default all)", required = false)
                array("start", SchemaBuilder.vecSchema(), "Where players come from (a road, an entrance), default: outdoor spots on the area's sides", required = false)
                array("targets", SchemaBuilder.vecSchema(), "Extra points players must reach (spots the game uses)", required = false)
                number("max_grade", "Steepest road grade allowed, rise per block over 4 blocks (default 0.5)", required = false)
                bool("fix", "Also fix what is safe to fix automatically: double door hinges, and indoor 1-block steps " +
                    "(a stairs block on the lower side) (default false)", required = false)
                string("step_block", "Stairs used by fix for the steps (default polished_andesite_stairs)", required = false)
                bool("image", "Also return a top view with the issues circled and numbered (default true)", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> check(plugin, args) },

        Tool(
            ForemanTool.LOOK,
            "First-person view with the game's textures and lighting (furniture included): see a place as a player " +
                "does, inside rooms too. Camera: `from` (eye position) with `at` (a point to look at) or yaw/pitch (yaw 0 = " +
                "south, 90 = west, 180 = north, 270 = east; pitch 90 = down); or player=true for an online player's own " +
                "view; or `orbit` {center, radius, height} for 4 views of a place from the south, west, north and east. " +
                "Use it to judge façades, interiors, sightlines, materials and decoration.",
            schema {
                prop("from", SchemaBuilder.type("array").apply { add("items", SchemaBuilder.type("number")) }, "Eye position [x, y, z] (a standing player's eye is feet + 1.62)", required = false)
                vec("at", "Point to look at", required = false)
                number("yaw", "Yaw when no `at`", required = false)
                number("pitch", "Pitch when no `at` (default 10)", required = false)
                bool("player", "Use the view of the first online player (or `name`)", required = false)
                string("name", "Player name for player=true", required = false)
                prop("orbit", SchemaBuilder.type("object"), "{center: [x, y, z], radius (default 16), height above center (default 6)}: 4 views around", required = false)
                number("fov", "Vertical field of view in degrees (default 70)", required = false)
                int("width", "Image width (default 960, max 1600)", required = false)
                int("height", "Image height (default 540, max 900)", required = false)
                int("distance", "View distance in blocks (default 96, max 192)", required = false)
                enum("time", listOf("day", "current", "night"), "Lighting: day (default), the world's current time, or night (block light only)", required = false)
                bool("fullbright", "Ignore light levels (dark interiors, caves): see shapes and materials (default false)", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> look(plugin, args) },

        Tool(
            ForemanTool.MEASURE_SITE,
            "Measures a footprint before building on it: ground heights under it and around it, slope, water, existing " +
                "builds and claims, the floor y to use and how much ground levelling at that y cuts and fills. Use it to " +
                "set a build's floor from the terrain instead of guessing.",
            schema {
                vec("from", "A corner of the footprint")
                vec("to", "The opposite corner (y ignored)")
                int("margin", "Ring around the footprint to measure too (default 4)", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> measure(plugin, args) },

        Tool(
            ForemanTool.CLAIM,
            "Reserves space in an area for a build, road or yard (a claim) so later edits don't build into it: edit, " +
                "paste_asset and place_furniture refuse to change blocks inside another claim unless allow_overlap. action " +
                "set adds a box to the area `name` and reserves it (kind: build, road, yard, terrain…), list shows the " +
                "areas with their boxes (in a box when from/to are given), delete stops one reserving space (it keeps its " +
                "name). Edits with `claim` add their changed box automatically. Areas are kept in the world.",
            schema {
                enum("action", listOf("set", "list", "delete"), "What to do")
                string("name", "Area name (set, delete)", required = false)
                vec("from", "A corner (set; list filter)", required = false)
                vec("to", "The opposite corner", required = false)
                string("kind", "build, road, yard, terrain… (set, default build)", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> claim(plugin, args) },
    )

    private val MAX_CHECK = 8_000_000L

    /** The box of `area` (a named area) or from/to. */
    fun area(plugin: Foreman, args: Args): Box {
        args.string("area", null)?.let { name ->
            return (plugin.worlds.area(name) ?: throw ToolError("No area '$name'")).bounds
        }
        if (!args.has("from") || !args.has("to")) throw ToolError("Give from and to, or area")
        return Box.of(args.vec("from"), args.vec("to"))
    }

    fun check(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        // Builds the area cuts through are checked whole: cut in half, their rooms would look unreachable.
        var base = area(plugin, args)
        val reach = base.grow(4, 0, 4)
        plugin.worlds.reserved(world.name).filter { it.kind == "build" }.flatMap { it.boxes }
            .filter { b -> b.intersect(reach) != null && !base.containsBox(b) }
            .forEach { b -> base = base.union(b) }
        val box = Box(
            BlockVector3.at(base.min.x() - 3, max(world.minHeight, base.min.y() - 3), base.min.z() - 3),
            BlockVector3.at(base.max.x() + 3, min(world.maxHeight - 1, base.max.y() + 6), base.max.z() + 3),
        )
        if (box.volume > MAX_CHECK) throw ToolError("Area too large to check (${box.volume} blocks with its margin, max $MAX_CHECK): check it in parts or tighten y")
        val checks = args.strings("checks").ifEmpty { Checks.ALL }.toSet()
        checks.firstOrNull { it !in Checks.ALL }?.let { throw ToolError("Unknown check '$it' (${Checks.ALL.joinToString(", ")})") }

        val grid = Grid.read(world, box)
        Furniture.ensureLoaded(world, box)
        val (furniture, entries) = Sync.main { Furniture.inBox(world, box) to Displays.read(world, box) }
        val shown = Displays.bake(entries)
        val options = Checks.Options(
            seeds = if (args.has("start")) args.array("start").map { Args.vecOf(it) } else emptyList(),
            targets = if (args.has("targets")) args.array("targets").map { Args.vecOf(it) } else emptyList(),
            maxGrade = args.double("max_grade", 0.5),
            claims = plugin.worlds.reserved(world.name),
        )
        val report = Checks.run(grid, furniture, shown, checks, options)
        plugin.bridge?.onCheck(world, box, report.issues)

        val out = StringBuilder()
        val errors = report.issues.filter { it.severity == Checks.Severity.ERROR }
        val warnings = report.issues.filter { it.severity == Checks.Severity.WARNING }
        out.appendLine("Check of ${base.describe()} (${checks.joinToString(", ")}): ${errors.size} error(s), ${warnings.size} warning(s).")
        if (report.structures.isNotEmpty()) {
            out.appendLine("Structures found: " + report.structures.take(20).joinToString("; ") { it.describe() } + if (report.structures.size > 20) "; …" else "")
        }
        report.notes.forEach { out.appendLine(it) }
        if (furniture.isNotEmpty() && shown.isEmpty()) out.appendLine("Furniture models could not be read (${Resources.status()}): furniture checks were skipped, run check again shortly.")
        val listed = (errors + warnings).take(80)
        listed.forEachIndexed { i, issue -> out.appendLine("${i + 1}. ${if (issue.severity == Checks.Severity.ERROR) "ERROR" else "warn"} ${issue.check} ${issue.at()}: ${issue.text}") }
        if (report.issues.size > listed.size) out.appendLine("… ${report.issues.size - listed.size} more: check a smaller area or fewer checks.")
        if (report.issues.isEmpty()) out.appendLine("No issue found.")

        if (args.bool("fix", false) && report.hingeFixes.isNotEmpty()) {
            Edits.edit(world, "fix ${report.hingeFixes.size / 2} double doors") { session, _ ->
                report.hingeFixes.forEach { (pos, hinge) ->
                    for (dy in 0..1) {
                        val data = world.getBlockData(pos.x(), pos.y() + dy, pos.z()) as? Door ?: continue
                        data.hinge = hinge
                        session.setBlock(pos.add(0, dy, 0), BukkitAdapter.adapt(data))
                    }
                }
            }
            out.appendLine("Fixed the hinges of ${report.hingeFixes.size / 2} double door(s).")
        }
        if (args.bool("fix", false) && report.steps.isNotEmpty()) {
            val block = args.string("step_block", "polished_andesite_stairs")!!.removePrefix("minecraft:")
            Edits.edit(world, "fix ${report.steps.size} steps") { session, _ ->
                report.steps.forEach { s -> session.setBlock(s.at, Edits.pattern(session, "$block[facing=${s.toward.name.lowercase()},half=bottom]")) }
            }
            out.appendLine("Put a $block on ${report.steps.size} 1-block step(s): " + report.steps.take(12).joinToString(" ") { Box.fmt(it.at) } + if (report.steps.size > 12) " …" else "")
        }

        if (!args.bool("image", true) || report.issues.isEmpty() || base.sizeX * base.sizeZ > 512 * 512) return ToolResult.text(out.toString())
        val top = Sync.main { Renderer.readTop(world, box.min.x(), box.min.z(), box.max.x(), box.max.z()) }
        val markers = listed.mapIndexed { i, issue -> Renderer.Marker(issue.x, issue.z, "${i + 1}", issue.severity == Checks.Severity.ERROR) }
        val image = Renderer.renderTop(top, emptyList(), markers)
        return ToolResult.images(out.toString() + "\nImage: top view, issues circled with their number (red = error).", listOf(Renderer.png(image)))
    }

    // ---- Plans, for the render tool ------------------------------------------------------------------------------

    fun plan(world: World, box: Box, y: Int): ToolResult {
        // From the box's own lowest y when lower: upper storeys are then walked to from the ground outside.
        val area = Box(BlockVector3.at(box.min.x() - 2, min(box.min.y(), y - 3), box.min.z() - 2), BlockVector3.at(box.max.x() + 2, y + 10, box.max.z() + 2))
        if (area.sizeX > 160 || area.sizeZ > 160) throw ToolError("Plans are limited to 156x156 blocks: render one building at a time")
        val grid = Grid.read(world, area)
        val walk = Walk(grid)
        val seeds = walk.border()
        val reached = if (seeds.isEmpty()) null else walk.reach(seeds)
        Furniture.ensureLoaded(world, area)
        val (furniture, entries) = Sync.main { Furniture.inBox(world, area) to Displays.read(world, area) }
        val visuals = Displays.bake(entries).associate { it.id to it.baked }
        val image = Plans.render(grid, walk, reached, y, furniture, visuals)
        return ToolResult.image(
            Renderer.png(image),
            "Floor plan of storey y=$y over x ${area.min.x()}..${area.max.x()}, z ${area.min.z()}..${area.max.z()}" +
                (if (reached == null) " (no outdoor spot on the sides: reachability not shown; give `from` the ground's y to walk up from it)" else "") +
                (if (furniture.isNotEmpty()) ". ${furniture.size} furniture" else "") + ".",
        )
    }

    // ---- First-person views ---------------------------------------------------------------------------------------

    private fun look(plugin: Foreman, args: Args): ToolResult {
        var world = plugin.world(args.string("world", null))
        val fov = args.double("fov", 70.0).coerceIn(20.0, 120.0)
        val width = args.int("width", 960).coerceIn(160, 1600)
        val height = args.int("height", 540).coerceIn(90, 900)
        val distance = args.int("distance", 96).coerceIn(16, 192)
        val views = ArrayList<Pair<Camera.View, String>>()

        when {
            args.bool("player", false) -> Sync.main {
                val name = args.string("name", null)
                val p = (if (name != null) Bukkit.getPlayerExact(name) else Bukkit.getOnlinePlayers().firstOrNull()) ?: throw ToolError("No player online" + (name?.let { " named $it" } ?: ""))
                world = p.world
                val e = p.eyeLocation
                views += Camera.View(Vector3d(e.x, e.y, e.z), e.yaw.toDouble(), e.pitch.toDouble(), fov) to "${p.name}'s view"
            }

            args.has("orbit") -> {
                val o = Args(args.obj("orbit"))
                val c = o.point("center")
                val r = o.double("radius", 16.0)
                val h = o.double("height", 6.0)
                val center = Vector3d(c[0] + 0.5, c[1] + 0.5, c[2] + 0.5)
                for ((name, angle) in listOf("south" to 0.0, "west" to 90.0, "north" to 180.0, "east" to 270.0)) {
                    val a = Math.toRadians(angle)
                    // Standing on that side, looking back at the center.
                    val eye = Vector3d(center.x - sin(a) * r, center.y + h, center.z + cos(a) * r)
                    val (yaw, pitch) = Camera.lookAt(eye, center)
                    views += Camera.View(eye, yaw, pitch, fov) to "from the $name"
                }
            }

            else -> {
                val f = args.point("from")
                val eye = Vector3d(f[0], f[1], f[2])
                val (yaw, pitch) = if (args.has("at")) args.vec("at").let { Camera.lookAt(eye, Vector3d(it.x() + 0.5, it.y() + 0.5, it.z() + 0.5)) }
                else args.double("yaw", 0.0) to args.double("pitch", 10.0)
                views += Camera.View(eye, yaw, pitch, fov) to "view"
            }
        }
        val stage = Camera.world(world, views.map { it.first }, distance, args.string("time", "day")!!, args.bool("fullbright", false))
        val images = views.map { (v, label) ->
            val caption = "$label: eye [%.1f, %.1f, %.1f] yaw %.0f pitch %.0f (looking %s)".format(v.eye.x, v.eye.y, v.eye.z, v.yaw, v.pitch, Camera.facing(v.yaw))
            Renderer.png(stage.render(v, width, height, distance.toDouble(), caption))
        }
        return ToolResult.images(
            "${views.size} view(s) rendered with the game's textures (${Resources.status()}). Lighting from the world's light " +
                "levels; entities other than displays are not drawn.",
            images,
        )
    }

    // ---- Sites ----------------------------------------------------------------------------------------------------

    private fun measure(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        val margin = args.int("margin", 4).coerceIn(0, 32)
        if ((box.sizeX + 2L * margin) * (box.sizeZ + 2L * margin) > 256 * 256) throw ToolError("Footprint + margin above 256x256")

        data class Col(val x: Int, val z: Int, val s: Blocks.Surface, val inside: Boolean)
        val cols = Sync.main {
            (box.min.x() - margin..box.max.x() + margin).flatMap { x ->
                (box.min.z() - margin..box.max.z() + margin).map { z ->
                    Col(x, z, Blocks.ground(world, x, z), x in box.min.x()..box.max.x() && z in box.min.z()..box.max.z())
                }
            }
        }
        val inside = cols.filter { it.inside }
        val ring = cols.filter { !it.inside }
        fun stats(l: List<Col>): String {
            val ys = l.filter { !it.s.underLiquid }.map { it.s.y }.sorted()
            if (ys.isEmpty()) return "all under water"
            return "min ${ys.first()}, median ${ys[ys.size / 2]}, max ${ys.last()}"
        }
        val natural = inside.filter { it.s.natural && !it.s.underLiquid }
        val ys = natural.map { it.s.y }.sorted()
        val median = ys.getOrNull(ys.size / 2) ?: inside.map { it.s.y }.sorted()[inside.size / 2]
        val cut = natural.sumOf { max(0, it.s.y - median) }
        val fill = natural.sumOf { max(0, median - it.s.y) }
        val wet = inside.count { it.s.underLiquid }
        val built = inside.count { !it.s.natural && !it.s.underLiquid }
        val steepest = cols.maxOf { c ->
            listOf(1 to 0, 0 to 1).maxOf { (dx, dz) -> cols.firstOrNull { it.x == c.x + dx && it.z == c.z + dz }?.let { kotlin.math.abs(it.s.y - c.s.y) } ?: 0 }
        }
        val claims = plugin.worlds.conflicts(world.name, Box(box.min.withY(world.minHeight), box.max.withY(world.maxHeight - 1)), null)
        val out = StringBuilder()
        out.appendLine("Site ${box.min.x()}..${box.max.x()} x ${box.min.z()}..${box.max.z()} (${box.sizeX}x${box.sizeZ}):")
        out.appendLine("- Ground under the footprint: ${stats(inside)}; around it (${margin} blocks): ${stats(ring)}. Steepest step between neighbours: $steepest.")
        out.appendLine("- Suggested floor: the ground at y=$median, so the ground floor's feet level is y=${median + 1}. Levelling the footprint to y=$median cuts $cut and fills $fill blocks.")
        if (wet > 0) out.appendLine("- $wet columns under water.")
        if (built > 0) out.appendLine("- $built columns already have builds or paving on them.")
        if (claims.isNotEmpty()) out.appendLine("- Overlaps claims: " + claims.joinToString(", ") { (c, b) -> "${c.name} (${b.describe()})" })
        out.appendLine("Then: `grade` the pad at y=$median with a gentle max_slope, build with the floor at y=${median + 1}, and claim the footprint.")
        return ToolResult.text(out.toString())
    }

    private fun claim(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        return when (args.string("action")) {
            "set" -> {
                val c = plugin.worlds.reserve(args.string("name"), world.name, Box.of(args.vec("from"), args.vec("to")), args.string("kind", null))
                val conflicts = c.boxes.flatMap { plugin.worlds.conflicts(world.name, it, c.name) }
                ToolResult.text("Claim ${c.describe()}" + if (conflicts.isEmpty()) "" else "\nOverlaps: " + conflicts.joinToString(", ") { (o, b) -> "${o.name} in ${b.describe()}" })
            }

            "delete" -> ToolResult.text(if (plugin.worlds.unreserve(args.string("name"), world.name)) "${args.string("name")} no longer reserves space" else "No claim ${args.string("name")}")
            else -> {
                val filter = if (args.has("from") && args.has("to")) Box.of(args.vec("from"), args.vec("to")) else null
                val list = plugin.worlds.areas(world.name).filter { (filter == null || it.boxes.any { b -> b.intersect(Box(filter.min.withY(-2048), filter.max.withY(2048))) != null }) }
                ToolResult.text(if (list.isEmpty()) "No areas" else list.joinToString("\n") { it.describe() })
            }
        }
    }

    /** Fails when [box] overlaps a claim other than [own], unless allowed. Used by the tools that change blocks. */
    fun guard(plugin: Foreman, world: World, box: Box, own: String?, allow: Boolean) {
        if (allow) return
        val conflicts = plugin.worlds.conflicts(world.name, box, own)
        if (conflicts.isNotEmpty()) throw ToolError(
            "This would build into " + conflicts.take(4).joinToString(", ") { (c, b) -> "claim ${c.name} (${c.kind}) at ${b.describe()}" } +
                ". Move it, or pass allow_overlap=true if that is intended"
        )
    }
}
