package games.qore.foreman.server.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Arrange
import games.qore.foreman.server.world.Blocks
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Checks
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Point
import games.qore.foreman.server.world.Grid
import games.qore.foreman.server.world.Shapes
import games.qore.foreman.server.world.Sync
import games.qore.foreman.server.world.Walk
import games.qore.foreman.server.world.build.Compiler
import games.qore.foreman.server.world.build.Frame
import games.qore.foreman.server.world.build.Room
import games.qore.foreman.server.world.build.Spec
import games.qore.foreman.server.world.build.Style
import games.qore.foreman.server.world.build.Writer
import games.qore.foreman.server.world.build.decorOp
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.util.Vector
import java.io.File
import java.io.InputStreamReader
import java.util.Random
import kotlin.math.roundToInt

/** Whole buildings from a spec, and the gameplay points they record. */
object BuildTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.BUILD_STRUCTURE,
            "Builds a complete building from a spec, instead of block-by-block edits: site levelling, foundation, " +
                "storeys, outer and inner walls, rooms, 2-wide doors (double doors with the right hinges), windows per " +
                "room, walkable switchback stairs between storeys, roof, furniture by room type (the catalog) and the " +
                "points it names (containers, spawns…, for gameplay plugins), optional weathering and clutter; then it runs check on the result. Defaults come from the world's profile " +
                "(get_context shows it), else neutral ones. Archetypes: corridor (office, school, hotel: rooms on both " +
                "sides of a 3-wide corridor, stair halls at the ends; depth >= 19), " +
                "sections (apartment block: `sections` stair cores, each between two flats of `flat_width`; width = 1 + " +
                "sections × (2 × flat_width + 9), depth >= 15), hall (one big room per storey, 4-wide gate), house (rooms " +
                "of 6+, stairs on the left). Use list_styles for the styles, uses and room types; new ones go in the world's " +
                "profile (world_profile) or this call's `materials` and `catalog`.",
            schema {
                string("name", "Name: the area and points of the building")
                vec("center", "Center of the footprint [x, y, z] (y ignored)")
                enum("facing", listOf("north", "south", "east", "west"), "The side the front (entrance) faces")
                int("width", "Size along the front")
                int("depth", "Size from the front to the back")
                enum("archetype", listOf("corridor", "sections", "hall", "house"), "Layout")
                int("floors", "Storeys above ground (default 1)", required = false)
                int("floor_height", "Slab to slab (default 5: 4 blocks of air)", required = false)
                string("style", "Material preset (list_styles; default: the world profile's, else brick)", required = false)
                prop("materials", SchemaBuilder.type("object"), "Overrides of the preset: wall, trim, base, floor, slab, inner, paint, roof, roof_stairs, parapet, window, door, entrance, stairs, pillar, railing", required = false)
                enum("roof", listOf("flat", "gable", "hip"), "Roof (default flat)", required = false)
                bool("basement", "A storey under the ground, reached by the stairs (default false)", required = false)
                string("use", "Building use: its room types in order (list_styles shows the uses: office, school, apartment…)", required = false)
                strings("rooms", "Room types in order, instead of use (see list_styles)", required = false)
                prop("catalog", SchemaBuilder.type("object"), "Room types to furnish differently in this building only, as in catalog/rooms.yml: " +
                    "{\"office\": [{\"item\": \"block:lectern[facing={facing}]\"}], \"clutter\": [{\"item\": \"block:white_wool\"}]}", required = false)
                int("room_width", "Target room width for corridor buildings (default 9, min 6)", required = false)
                int("sections", "Stair sections of an apartment block (default 2)", required = false)
                int("flat_width", "Flat width in an apartment block (default 10)", required = false)
                number("decay", "0..1: broken windows, wear, vines, moss, cobwebs, rubble (default 0)", required = false)
                bool("dark", "Boarded windows, a dark interior (default false)", required = false)
                number("clutter", "0..1: barricades, debris and junk breaking the sightlines in corridors and rooms, a path kept free (default 0)", required = false)
                bool("furnish", "Furniture and points from the room catalog (default true)", required = false)
                bool("connect", "Lay paths from its entrances to the nearest roads (connect_doors) (default true)", required = false)
                int("ground", "Ground y the building stands on (default: the median ground of the footprint)", required = false)
                bool("site", "Grade the ground around it first (default true)", required = false)
                bool("allow_overlap", "Build even if it overlaps another claim (default false)", required = false)
                int("seed", "Random seed (default: from the name)", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> structure(plugin, args) },

        Tool(
            ForemanTool.DEMOLISH,
            "Removes a building and puts the land back: every block that isn't natural ground in the box goes (walls, " +
                "roads, basements), its furniture too; the hole is filled and the ground surface rebuilt from the ground " +
                "around the box (grass on dirt), so a new build can take the place. `name` also forgets that build's " +
                "area and points. One undo step. Give the box with some margin: blocks outside it are kept.",
            schema {
                vec("from", "A corner")
                vec("to", "The opposite corner (include the basement and the roof)")
                string("name", "The build's area and points to forget", required = false)
                bool("keep_roads", "Keep dirt_path, gravel and other path blocks at ground level (default false)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> demolish(plugin, args) },

        Tool(
            ForemanTool.LIST_STYLES,
            "The building presets of build_structure in a world (Foreman's, the server's catalog/buildings.yml and " +
                "catalog/rooms.yml, the world profile's): material styles, archetypes, uses and the room types of the " +
                "furniture catalog with what each gets.",
            schema { string("world", "World name", required = false) },
        ) { args -> ToolResult.text(styles(plugin, plugin.world(args.string("world", null)).name)) },

        Tool(
            ForemanTool.POINTS,
            "Gameplay points of a world, kept in it: spots builds recorded from the catalog's `point` entries " +
                "(type:kind, e.g. container:chest), or added here, for gameplay plugins to read (they get them through " +
                "Foreman's API). list (by build, type or box), export as JSON, add [{pos, type, kind?, room?}] to a build, " +
                "remove a build's (or those in a box).",
            schema {
                enum("action", listOf("list", "export", "add", "remove"), "What to do (default list)", required = false)
                string("build", "Only this build (add: the build they belong to)", required = false)
                string("type", "Only this type (container, spawn…)", required = false)
                vec("from", "A corner of a box to filter by", required = false)
                vec("to", "The opposite corner", required = false)
                array("items", SchemaBuilder.type("object"), "add: [{pos: [x, y, z], type, kind?, room?}]", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> points(plugin, args) },
    )

    // ---- Catalog -------------------------------------------------------------------------------------------------

    class Entry(val item: String, val min: Int, val max: Int, val chance: Double, val center: Boolean, val point: String?)

    /**
     * The room catalog: the plugin's defaults, room types redefined by the server's catalog/rooms.yml, then by the
     * world's profile, then by the call's [overrides] replacing them.
     */
    fun catalog(plugin: Foreman, world: String, overrides: JsonObject? = null): Map<String, List<Entry>> {
        val out = LinkedHashMap<String, List<Entry>>()
        fun entry(m: Map<*, *>): Entry {
            val count = m["count"]?.let { if (it is Number) it.toInt().toString() else it.toString() } ?: "1"
            val (a, b) = if ('-' in count) count.split('-').map { it.trim().toInt() } else listOf(count.toInt(), count.toInt())
            return Entry(m["item"]?.toString() ?: "", a, b, (m["chance"] as? Number)?.toDouble() ?: 1.0, m["place"] == "center", m["point"]?.toString())
        }
        fun read(yaml: YamlConfiguration) = yaml.getKeys(false).forEach { type -> out[type] = yaml.getMapList(type).map(::entry).filter { it.item.isNotEmpty() || it.point != null } }
        plugin.getResource("catalog/rooms.yml")?.let { read(YamlConfiguration.loadConfiguration(InputStreamReader(it))) }
        File(plugin.dataFolder, "catalog/rooms.yml").takeIf { it.exists() }?.let { read(YamlConfiguration.loadConfiguration(it)) }
        fun json(o: JsonObject?) = o?.entrySet()?.forEach { (type, list) ->
            out[type] = list.asJsonArray.map { e -> entry(com.google.gson.Gson().fromJson(e, Map::class.java)) }.filter { it.item.isNotEmpty() || it.point != null }
        }
        json(plugin.worlds.profile(world).rooms)
        json(overrides)
        return out
    }

    /** Material styles by name and room types in order by building use, each with where it comes from. */
    class Presets(val styles: JsonObject, val uses: Map<String, List<String>>, val origin: Map<String, String>)

    /**
     * build_structure's presets: the plugin's catalog/buildings.yml, the server's (a style or use of the same name
     * replaces it), then the world's profile.
     */
    fun presets(plugin: Foreman, world: String): Presets {
        val styles = JsonObject()
        val uses = LinkedHashMap<String, List<String>>()
        val origin = HashMap<String, String>()
        fun read(yaml: YamlConfiguration, from: String) {
            yaml.getConfigurationSection("styles")?.let { section ->
                section.getKeys(false).forEach { name ->
                    val style = section.getConfigurationSection(name) ?: return@forEach
                    styles.add(name, JsonObject().apply { style.getKeys(false).forEach { k -> addProperty(k, style.getString(k)) } })
                    origin["style $name"] = from
                }
            }
            yaml.getConfigurationSection("uses")?.let { section -> section.getKeys(false).forEach { name -> uses[name] = section.getStringList(name); origin["use $name"] = from } }
        }
        plugin.getResource("catalog/buildings.yml")?.let { read(YamlConfiguration.loadConfiguration(InputStreamReader(it)), "foreman") }
        File(plugin.dataFolder, "catalog/buildings.yml").takeIf { it.exists() }?.let { read(YamlConfiguration.loadConfiguration(it), "server") }
        val profile = plugin.worlds.profile(world)
        profile.styles.entrySet().forEach { (name, style) -> styles.add(name, style); origin["style $name"] = "world" }
        profile.uses.forEach { (name, rooms) -> uses[name] = rooms; origin["use $name"] = "world" }
        return Presets(styles, uses, origin)
    }

    private fun styles(plugin: Foreman, world: String): String {
        val presets = presets(plugin, world)
        fun from(key: String) = presets.origin[key]?.takeIf { it != "foreman" }?.let { " ($it)" } ?: ""
        val out = StringBuilder("Styles (override any material with `materials`: {${Style.KEYS.joinToString(", ") { "\"$it\"" }}}):\n")
        presets.styles.keySet().forEach { k ->
            val s = runCatching { Style.of(k, null, presets.styles) }.getOrElse { e -> out.appendLine("- $k${from("style $k")}: ${e.message}"); return@forEach }
            out.appendLine("- $k${from("style $k")}: wall ${s.wall}, trim ${s.trim}, floor ${s.floor}, inner ${s.inner}, paint ${s.paint}, window ${s.window}, door ${s.door}, stairs ${s.stairs}, roof ${s.roof}")
        }
        out.appendLine("\nArchetypes: corridor (depth >= 19), sections (apartment block), hall, house (13 x 13 or more).")
        out.appendLine("Uses (room types in order): " + presets.uses.entries.joinToString("; ") { (k, v) -> "$k${from("use $k")}: ${v.joinToString(", ")}" })
        out.appendLine("\nRoom types of the catalog:")
        catalog(plugin, world).forEach { (type, entries) ->
            out.appendLine("- $type: " + entries.joinToString(", ") { e -> "${e.item.substringAfter(':').ifEmpty { "(free cell)" }}${if (e.max > 1) " ×${e.min}-${e.max}" else ""}${e.point?.let { " → $it" } ?: ""}" }.ifEmpty { "(nothing)" })
        }
        return out.toString()
    }

    // ---- build_structure -----------------------------------------------------------------------------------------

    private fun face(name: String) = runCatching { BlockFace.valueOf(name.uppercase()) }.getOrNull()
        ?.takeIf { it in Walk.FACES } ?: throw ToolError("facing must be north, south, east or west")

    private fun structure(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val name = args.string("name")
        val facing = face(args.string("facing"))
        val width = args.int("width").coerceIn(7, 200)
        val depth = args.int("depth").coerceIn(7, 120)
        val c = args.vec("center")
        val sizeX = if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) width else depth
        val sizeZ = if (facing == BlockFace.NORTH || facing == BlockFace.SOUTH) depth else width
        val frame = Frame(c.x() - sizeX / 2, c.z() - sizeZ / 2, width, depth, facing)
        val footprint = Box(BlockVector3.at(frame.x0, world.minHeight, frame.z0), BlockVector3.at(frame.x1, world.maxHeight - 1, frame.z1))
        val ground = if (args.has("ground")) args.int("ground") else Sync.main {
            val ys = (frame.x0..frame.x1 step 2).flatMap { x -> (frame.z0..frame.z1 step 2).map { z -> Blocks.ground(world, x, z) } }
                .filter { it.natural && !it.underLiquid }.map { it.y }.sorted()
            if (ys.isEmpty()) throw ToolError("No natural ground under the footprint: give ground")
            ys[ys.size / 2]
        }
        // An argument, else the world profile's default, else the neutral one.
        val profile = plugin.worlds.profile(world.name)
        val presets = presets(plugin, world.name)
        fun int(k: String, d: Int) = if (args.has(k)) args.int(k) else profile.default(k)?.asInt ?: d
        fun double(k: String, d: Double) = if (args.has(k)) args.double(k, d) else profile.default(k)?.asDouble ?: d
        fun bool(k: String, d: Boolean) = if (args.has(k)) args.bool(k, d) else profile.default(k)?.asBoolean ?: d
        fun string(k: String, d: String) = if (args.has(k)) args.string(k) else profile.default(k)?.asString ?: d
        val spec = Spec(
            name, frame, ground, int("floors", 1).coerceIn(1, 20), int("floor_height", 5).coerceIn(4, 12),
            args.string("archetype"), Style.of(string("style", Style.BASE), if (args.has("materials")) args.obj("materials") else null, presets.styles),
            string("roof", "flat"), bool("basement", false), args.string("use", null), args.strings("rooms"),
            int("room_width", 9).coerceIn(6, 20), int("sections", 2).coerceIn(1, 8), int("flat_width", 10).coerceIn(8, 20),
            double("decay", 0.0).coerceIn(0.0, 1.0), bool("dark", false), double("clutter", 0.0).coerceIn(0.0, 1.0),
            bool("furnish", true), args.int("seed", name.hashCode()).toLong(), presets.uses,
        )
        val layout = spec.layout()
        val box = Box(BlockVector3.at(frame.x0 - 3, spec.slab(spec.lowest) - 3, frame.z0 - 3), BlockVector3.at(frame.x1 + 3, spec.top + 10, frame.z1 + 3))
        QualityTools.guard(plugin, world, box, name, args.bool("allow_overlap", false))

        val notes = ArrayList<String>()
        if (args.bool("site", true)) {
            val grade = JsonObject().apply {
                add("from", Compiler.array(frame.x0 - 4, 0, frame.z0 - 4)); add("to", Compiler.array(frame.x1 + 4, 0, frame.z1 + 4))
                addProperty("y", ground); addProperty("max_slope", 0.6); addProperty("corner_radius", 3); addProperty("reach", 28)
                world.name.let { addProperty("world", it) }
            }
            notes += TerrainTools.grade(plugin, Args(grade)).content.first().get("text").asString.lineSequence().first()
        }

        val overrides = if (args.has("catalog")) args.obj("catalog") else null
        val points = ArrayList<Point>()
        val furnitureCount = HashMap<String, Int>()
        // Cells of the furniture placed: clutter and wear must leave them empty (some have no collision blocks).
        val furnitureCells = HashSet<BlockVector3>()
        // Every cell furnish used (blocks too): clutter keeps off them and their sides, so all stays reachable.
        val takenCells = HashSet<BlockVector3>()
        val (_, change) = Edits.scoped(world, "build $name (${spec.archetype}, ${spec.floors} storeys)") { scope ->
            val writer = Writer(scope.session, frame)
            Compiler(spec, layout, writer).build()
            scope.flush()
            Shapes.connect(world, scope.session, box)
            scope.flush()
            if (spec.furnish) furnish(plugin, world, spec, layout.rooms, box, scope.session, scope.change, points, furnitureCount, furnitureCells, takenCells, overrides)
            if (spec.clutter > 0) clutter(plugin, world, spec, layout, scope.session, takenCells, overrides)
            scope.flush()
            if (spec.decay > 0) {
                val shell = Box(BlockVector3.at(frame.x0, spec.slab(spec.lowest), frame.z0), BlockVector3.at(frame.x1, spec.top + 2, frame.z1))
                Decor.weather(world, scope.session, shell, Args(decorOp("weather", shell, mapOf("amount" to spec.decay * 0.4, "seed" to spec.seed.toInt()))))
                scope.flush()
                val kinds = if (spec.dark) listOf("vines", "moss", "cobwebs", "rubble", "litter") else listOf("vines", "moss", "grass", "rubble", "litter")
                Decor.overgrow(world, scope.session, box, Args(decorOp("overgrow", box, mapOf("amount" to spec.decay * 0.6, "seed" to spec.seed.toInt() + 1, "kinds" to kinds))))
                scope.flush()
            }
            val air = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(org.bukkit.Material.AIR.createBlockData())
            furnitureCells.forEach { c -> world.getType(c.x(), c.y(), c.z()).let { m -> if (!m.isAir && m != org.bukkit.Material.BARRIER) scope.session.setBlock(c, air) } }
        }
        plugin.worlds.replacePoints(world.name, name, points)
        plugin.worlds.reserve(name, world.name, Box(BlockVector3.at(frame.x0 - 1, spec.slab(spec.lowest) - 3, frame.z0 - 1), BlockVector3.at(frame.x1 + 1, spec.top + 6, frame.z1 + 1)), "build")
        plugin.worlds.mark(name, world.name, box, "${spec.archetype} building, ${spec.floors} storeys")

        val out = StringBuilder()
        out.appendLine("Built $name: ${spec.archetype}, ${spec.floors} storey(s)${if (spec.basement) " + basement" else ""}, footprint x ${frame.x0}..${frame.x1}, z ${frame.z0}..${frame.z1}, ground y=$ground (ground floor feet y=${spec.slab(0) + 1}), ${change.blocks} blocks.")
        notes.forEach { out.appendLine("Site: $it") }
        val byLevel = layout.rooms.groupBy { it.level }
        byLevel.forEach { (level, rooms) ->
            out.appendLine("Storey $level (feet y=${spec.slab(level) + 1}): " + rooms.filter { it.type != "stairwell" }.joinToString(", ") { r ->
                val a = frame.at(r.u0, 0, r.v0); val b = frame.at(r.u1, 0, r.v1)
                "${r.type} ${r.width}x${r.depth} [${minOf(a.x(), b.x())}..${maxOf(a.x(), b.x())}, ${minOf(a.z(), b.z())}..${maxOf(a.z(), b.z())}]"
            })
        }
        if (furnitureCount.isNotEmpty()) out.appendLine("Furnished: " + furnitureCount.entries.joinToString(", ") { "${it.value} ${it.key}" })
        if (points.isNotEmpty()) out.appendLine("${points.size} points recorded (points tool): " + points.groupingBy { "${it.type}:${it.kind}" }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" })
        out.appendLine("Area $name (build).")
        if (args.bool("connect", true)) {
            val connect = JsonObject().apply {
                add("from", Compiler.array(frame.x0 - 3, spec.slab(0), frame.z0 - 3)); add("to", Compiler.array(frame.x1 + 3, spec.slab(0) + 2, frame.z1 + 3))
                addProperty("world", world.name)
            }
            out.appendLine(runCatching { RepairTools.connect(plugin, Args(connect)).content.first().get("text").asString }.getOrElse { "Paths: ${it.message}" })
        }

        // The result checked like any build. Neighbouring builds the box reaches into are checked whole: cut in half,
        // their rooms would look unreachable.
        var checked = Box(BlockVector3.at(frame.x0 - 2, spec.slab(spec.lowest) - 1, frame.z0 - 2), BlockVector3.at(frame.x1 + 2, spec.top + 3, frame.z1 + 2))
        // The check reads a few blocks around its box too.
        val reach = checked.grow(6, 0, 6)
        plugin.worlds.conflicts(world.name, reach, name).filter { it.first.kind == "build" }
            .flatMap { (c, _) -> c.boxes.filter { b -> b.intersect(reach) != null } }
            .forEach { b -> checked = checked.union(b) }
        val check = JsonObject().apply {
            add("from", Compiler.array(checked.min.x(), checked.min.y(), checked.min.z())); add("to", Compiler.array(checked.max.x(), checked.max.y(), checked.max.z()))
            add("checks", JsonArray().apply { listOf("walk", "doors", "furniture", "ground").forEach { add(it) } })
            addProperty("image", false); addProperty("world", world.name)
        }
        out.appendLine("\n" + QualityTools.check(plugin, Args(check)).content.first().get("text").asString)
        val plan = QualityTools.plan(world, Box(BlockVector3.at(frame.x0, ground, frame.z0), BlockVector3.at(frame.x1, ground, frame.z1)), spec.slab(0) + 1)
        return ToolResult(ToolResult.text(out.toString()).content + plan.content)
    }

    private fun demolish(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxEditBlocks.toLong(), "demolish")
        val keepRoads = args.bool("keep_roads", false)
        val road = Blocks.matcher("dirt_path,gravel,coarse_dirt,*_slab")
        // The ground around the box: its edges' natural ground, interpolated across by inverse distance to each side.
        val (target, top) = Sync.main {
            Furniture.ensureLoaded(world, box)
            // The ground around: natural ground, else the paving it stands in (a forecourt, a square).
            fun ring(x: Int, z: Int) = Blocks.ground(world, x, z).takeIf { !it.underLiquid && (it.natural || it.material.isSolid) }
            val north = (box.min.x()..box.max.x()).map { ring(it, box.min.z() - 1) }
            val south = (box.min.x()..box.max.x()).map { ring(it, box.max.z() + 1) }
            val west = (box.min.z()..box.max.z()).map { ring(box.min.x() - 1, it) }
            val east = (box.min.z()..box.max.z()).map { ring(box.max.x() + 1, it) }
            val all = (north + south + west + east).filterNotNull()
            if (all.isEmpty()) throw ToolError("No ground around the box to rebuild the land from")
            val fallback = all.map { it.y }.sorted()[all.size / 2]
            val top = all.groupingBy { it.material }.eachCount().maxBy { it.value }.key.takeIf { it != org.bukkit.Material.STONE } ?: org.bukkit.Material.GRASS_BLOCK
            val sx = box.max.x() - box.min.x() + 1
            val sz = box.max.z() - box.min.z() + 1
            Array(sx) { i -> IntArray(sz) { k ->
                val sides = listOf(north[i] to k + 1, south[i] to sz - k, west[k] to i + 1, east[k] to sx - i)
                    .mapNotNull { (s, d) -> s?.let { it.y to 1.0 / d } }
                if (sides.isEmpty()) fallback else (sides.sumOf { it.first * it.second } / sides.sumOf { it.second }).roundToInt()
            } } to top
        }
        val air = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(org.bukkit.Material.AIR.createBlockData())
        val grass = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(top.createBlockData())
        val dirt = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(Blocks.subsurface(top).createBlockData())
        val stone = com.sk89q.worldedit.bukkit.BukkitAdapter.adapt(org.bukkit.Material.STONE.createBlockData())
        val (_, change) = Edits.edit(world, "demolish ${args.string("name", null) ?: box.describe()}") { session, change ->
            AssetTools.removeIn(world, box, null, change)
            for (x in box.min.x()..box.max.x()) for (z in box.min.z()..box.max.z()) {
                val g = target[x - box.min.x()][z - box.min.z()]
                for (y in box.min.y()..box.max.y()) {
                    val m = world.getType(x, y, z)
                    when {
                        y > g -> if (!m.isAir && !(keepRoads && y == g + 1 && road(m))) session.setBlock(x, y, z, air)
                        y == g -> if (!(keepRoads && road(m))) session.setBlock(x, y, z, grass)
                        y >= g - 3 -> if (!Blocks.isNaturalGround(m) || m.isAir) session.setBlock(x, y, z, dirt)
                        else -> if (!Blocks.isNaturalGround(m) || m.isAir) session.setBlock(x, y, z, stone)
                    }
                }
            }
        }
        val forgotten = args.string("name", null)?.let { name ->
            listOfNotNull(
                "area".takeIf { plugin.worlds.remove(name, world.name) },
                plugin.worlds.removePoints(world.name) { it.build == name }.takeIf { it > 0 }?.let { "$it points" },
            ).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = " Forgot $name's ", postfix = ".")
        } ?: ""
        val furniture = if (change.removedFurniture.isNotEmpty()) ", ${change.removedFurniture.size} furniture" else ""
        return InspectTools.result(plugin, world, "Demolished ${box.describe()}: ${change.blocks} blocks$furniture; ground rebuilt with ${top.key.key}.$forgotten", box, args, "demolished")
    }

    /** Catalog furniture and blocks in every room, through the room-aware spot search; their points recorded. */
    private fun furnish(
        plugin: Foreman, world: World, spec: Spec, rooms: List<Room>, box: Box, session: com.sk89q.worldedit.EditSession,
        change: games.qore.foreman.server.world.Change, points: MutableList<Point>, counts: MutableMap<String, Int>,
        furnitureCells: MutableSet<BlockVector3>, taken: MutableSet<BlockVector3>, overrides: JsonObject?,
    ) {
        val catalog = catalog(plugin, world.name, overrides)
        val rng = Random(spec.seed + 7)
        val grid = Grid.read(world, box)
        val walk = Walk(grid)
        val openings = Checks.openings(grid, walk)
        val frame = spec.frame
        val provider = Furniture.provider
        for (room in rooms) {
            val entries = catalog[room.type] ?: continue
            val y = spec.slab(room.level) + 1
            val cells = (room.u0..room.u1).flatMap { u -> (room.v0..room.v1).map { v -> frame.at(u, y, v) } }.toSet()
            // Bigger rooms get more of everything: the catalog counts are for a room of about 50 cells.
            val scale = (room.width * room.depth / 50.0).coerceIn(1.0, 4.0)
            for (e in entries) repeat(((e.min + rng.nextInt(e.max - e.min + 1)) * scale).roundToInt()) {
                if (rng.nextDouble() >= e.chance) return@repeat
                fun record(at: BlockVector3) = e.point?.let { p -> points += Point(world.name, at.x(), at.y(), at.z(), p.substringBefore(':'), p.substringAfter(':', ""), spec.name, room.name) }
                // A point alone (a spawn, an item on the floor): a free floor cell, nothing placed.
                if (e.item.isEmpty()) {
                    val spot = center(cells, taken, openings, rng) ?: return@repeat
                    taken += spot.root
                    record(spot.root)
                    return@repeat
                }
                val furniture = e.item.startsWith("furniture:")
                val id = e.item.substringAfter(':').split('|').let { it[rng.nextInt(it.size)] }
                val definition = if (furniture) provider?.type(id) ?: return@repeat else null
                val bed = !furniture && id.substringBefore('[').endsWith("_bed")
                val footprint = definition?.footprint() ?: if (bed) listOf(Vector(0, 0, 0), Vector(0, 0, -1)) else listOf(Vector(0, 0, 0))
                val spot = if (e.center) center(cells, taken, openings, rng) else
                    Arrange.find(grid, cells, openings, footprint, taken, null, definition?.let { ModelTools.boundsByFacing(it) } ?: emptyMap())
                spot ?: return@repeat
                if (definition != null) {
                    if (Furniture.spawn(world, definition, spot.root, spot.yaw, null, change) != null) furnitureCells += spot.cells
                } else {
                    val state = id.replace("{facing}", spot.facing.name.lowercase()).replace("{back}", spot.facing.oppositeFace.name.lowercase())
                    session.setBlock(spot.root, Edits.pattern(session, state))
                    if (bed) session.setBlock(spot.cells[1], Edits.pattern(session, state.replace("part=head", "part=foot")))
                }
                taken += spot.cells
                counts.merge(id.substringBefore('['), 1, Int::plus)
                record(spot.root)
            }
        }
    }

    /** A free cell away from walls and openings, for center items. */
    private fun center(cells: Set<BlockVector3>, taken: Set<BlockVector3>, openings: List<Checks.Opening>, rng: Random): Arrange.Spot? {
        val keep = openings.flatMap { it.keep }.toSet()
        val inner = cells.filter { c -> c !in taken && c !in keep && Walk.FACES.all { f -> BlockVector3.at(c.x() + f.modX, c.y(), c.z() + f.modZ) in cells } }
        if (inner.isEmpty()) return null
        val c = inner[rng.nextInt(inner.size)]
        val f = Walk.FACES[rng.nextInt(4)]
        return Arrange.Spot(c, f, Arrange.yaw(f), listOf(c))
    }

    /**
     * View blockers. Corridors: stacks 1-2 cells long and 2 high against alternate walls every few blocks, so sight
     * lines zigzag while a 2-wide way stays free. Rooms: a partition sticking out from a wall and a pile in a corner.
     * Only the cells in front of doors and doorways, and next to furniture, are left alone. The blocks are the
     * catalog's `clutter` entries when defined (block:<state>), else junk.
     */
    private fun clutter(
        plugin: Foreman, world: World, spec: Spec, layout: games.qore.foreman.server.world.build.Layout, session: com.sk89q.worldedit.EditSession,
        taken: Set<BlockVector3>, overrides: JsonObject?,
    ) {
        val rng = Random(spec.seed + 11)
        val frame = spec.frame
        // Per storey: the cells of each opening and their neighbours stay free.
        val keep = HashMap<Int, HashSet<Pair<Int, Int>>>()
        layout.openings.forEach { o -> for (k in 0 until o.width) {
            val (u, v) = if (o.alongU) (o.u + k) to o.v else o.u to (o.v + k)
            for (du in -1..1) for (dv in -1..1) keep.getOrPut(o.level) { HashSet() } += (u + du) to (v + dv)
        } }
        val near = taken.flatMap { c -> Walk.FACES.map { f -> BlockVector3.at(c.x() + f.modX, c.y(), c.z() + f.modZ) } + c }.toSet()
        val junk = catalog(plugin, world.name, overrides)["clutter"]?.map { it.item.removePrefix("block:") }?.takeIf { it.isNotEmpty() }
            ?: listOf("barrel[facing=up]", "spruce_planks", "iron_bars", "white_wool", "dark_oak_planks", "cobblestone", "hay_block")
        fun free(level: Int, u: Int, y: Int, v: Int): Boolean {
            if ((u to v) in (keep[level] ?: emptySet<Pair<Int, Int>>())) return false
            val p = frame.at(u, y, v)
            return p !in near && world.getType(p.x(), p.y(), p.z()).isAir
        }
        fun put(u: Int, y: Int, v: Int, block: String) = session.setBlock(frame.at(u, y, v), Edits.pattern(session, block))
        /** A stack of [length] cells along u (or v), [height] high, all or nothing. */
        fun stack(level: Int, cells: List<Pair<Int, Int>>, y: Int, height: Int): Boolean {
            if (cells.any { (u, v) -> !free(level, u, y, v) }) return false
            val block = junk[rng.nextInt(junk.size)]
            cells.forEach { (u, v) -> for (h in 0 until height) put(u, y + h, v, if (h > 0 && block.startsWith("barrel")) "barrel[facing=up]" else block) }
            return true
        }
        for (r in layout.rooms) {
            val y = spec.slab(r.level) + 1
            when {
                r.type == "corridor" -> {
                    val alongU = r.u1 - r.u0 >= r.v1 - r.v0
                    val (a0, a1) = if (alongU) r.u0 to r.u1 else r.v0 to r.v1
                    val (b0, b1) = if (alongU) r.v0 to r.v1 else r.u0 to r.u1
                    // Narrow corridors keep their whole width.
                    if (b1 - b0 < 2) continue
                    var a = a0 + 2
                    var side = rng.nextBoolean()
                    while (a <= a1 - 2) {
                        if (rng.nextDouble() < spec.clutter) {
                            val length = 1 + rng.nextInt(2)
                            val height = if (rng.nextDouble() < 0.7) 2 else 1
                            // This side, else the other one (a door there): the sides alternate after each stack.
                            for (s in listOf(side, !side)) {
                                val b = if (s) b0 else b1
                                val cells = (a until minOf(a + length, a1 - 1)).map { if (alongU) it to b else b to it }
                                if (stack(r.level, cells, y, height)) { side = !s; break }
                            }
                        }
                        a += 2 + rng.nextInt(3)
                    }
                }
                r.type != "stairwell" && r.width >= 6 && r.depth >= 6 -> {
                    if (rng.nextDouble() < spec.clutter) {
                        // A partition from the middle of a long wall, 2-3 cells into the room, 2 high.
                        val length = 2 + rng.nextInt(2)
                        val u = (r.u0 + r.u1) / 2 + rng.nextInt(3) - 1
                        val fromLow = rng.nextBoolean()
                        val cells = (0 until length).map { k -> u to (if (fromLow) r.v0 + k else r.v1 - k) }
                        stack(r.level, cells, y, 2)
                    }
                    if (rng.nextDouble() < spec.clutter) {
                        val (u, v) = listOf(r.u0 to r.v0, r.u1 to r.v0, r.u0 to r.v1, r.u1 to r.v1)[rng.nextInt(4)]
                        stack(r.level, listOf(u to v), y, 1)
                        if (free(r.level, u, y + 1, v)) put(u, y + 1, v, "cobblestone_slab")
                    }
                }
            }
        }
    }

    // ---- points --------------------------------------------------------------------------------------------------

    private fun points(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null)).name
        val build = args.string("build", null)
        val type = args.string("type", null)
        val box = if (args.has("from") && args.has("to")) Box.of(args.vec("from"), args.vec("to")) else null
        fun matches(p: Point) = (build == null || p.build == build) && (type == null || p.type == type) && (box == null || box.contains(p.x, p.y, p.z))
        val list = plugin.worlds.points(world).filter(::matches)
        return when (args.string("action", "list")) {
            "add" -> {
                val added = args.array("items").map { Args(it.asJsonObject) }.map { i ->
                    val pos = i.vec("pos")
                    Point(world, pos.x(), pos.y(), pos.z(), i.string("type"), i.string("kind", "")!!, build ?: "", i.string("room", "")!!)
                }
                plugin.worlds.addPoints(world, added)
                ToolResult.text("Added ${added.size} points" + (build?.let { " to $it" } ?: ""))
            }
            "remove" -> {
                if (build == null && box == null && type == null) throw ToolError("Give build, type or from/to")
                ToolResult.text("Removed ${plugin.worlds.removePoints(world, ::matches)} points")
            }
            "export" -> ToolResult.text(JsonArray().apply {
                list.forEach { p -> add(JsonObject().apply {
                    addProperty("world", p.world); add("pos", Compiler.array(p.x, p.y, p.z)); addProperty("type", p.type)
                    addProperty("kind", p.kind); addProperty("build", p.build); addProperty("room", p.room)
                }) }
            }.toString())
            else -> ToolResult.text(if (list.isEmpty()) "No points" else "${list.size} points:\n" + list.take(200).joinToString("\n") { it.describe() } +
                if (list.size > 200) "\n… ${list.size - 200} more: filter by build, type or box" else "")
        }
    }
}
