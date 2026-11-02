package games.qore.foreman.server.tools

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
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
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Renderer
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

object InspectTools {
    private const val HEIGHTS = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val SYMBOLS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789#$%&*+=?!@^~<>/|;:"

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.GET_CONTEXT,
            "Start here. Online players (position, facing, the block they look at, their WorldEdit and foreman selections), " +
                "the building profile of their worlds (theme and rules to follow there) and the zones they stand in, named " +
                "areas, style guides, asset and furniture counts, limits and the recent AI changes. When the user says " +
                "\"here\", \"this house\" or \"where I look\", resolve it with this.",
            schema {},
        ) { context(plugin) },

        Tool(
            ForemanTool.READ_STYLE_GUIDE,
            "Reads a style guide (palette, rules, references) by name. Read the one of the zone before building there.",
            schema { string("name", "Style guide name, as listed by get_context") },
        ) { args ->
            val name = args.string("name").removeSuffix(".md")
            val file = File(plugin.stylesFolder, "$name.md")
            if (!file.canonicalPath.startsWith(plugin.stylesFolder.canonicalPath) || !file.exists()) throw ToolError("No style guide '$name'")
            ToolResult.text(file.readText())
        },

        Tool(
            ForemanTool.SCAN_AREA,
            "Terrain overview of a horizontal area: surface height grid, surface block grid (natural ground vs builds, " +
                "water) and stats. Use it before placing or terraforming. Areas above 96x96 are downsampled (step).",
            schema {
                vec("from", "A corner [x, y, z] (y is ignored)")
                vec("to", "The opposite corner [x, y, z] (y is ignored)")
                string("world", "World name, default: the main build world", required = false)
            },
        ) { args -> scan(plugin, args) },

        Tool(
            ForemanTool.READ_BLOCKS,
            "Exact blocks of a small box as text slices with a legend of full block states (stairs facing, slab half…). " +
                "Use it to inspect a façade, a door, a roof edge before detailing. Limited to limits.max-read-blocks.",
            schema {
                vec("from", "A corner [x, y, z]")
                vec("to", "The opposite corner [x, y, z]")
                enum("axis", listOf("y", "x", "z"), "Slice axis: y = horizontal layers (default), x or z = vertical cross-sections", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> read(plugin, args) },

        Tool(
            ForemanTool.FIND_BLOCKS,
            "Finds blocks in a box: doors, chests, a material… Returns positions with their block state.",
            schema {
                vec("from", "A corner [x, y, z]")
                vec("to", "The opposite corner [x, y, z]")
                string("match", "Comma-separated: block ids (oak_door), globs (*_door, *_stairs) or block tags (#doors, #beds)")
                int("limit", "Max positions returned (default 100)", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> find(plugin, args) },

        Tool(
            ForemanTool.RENDER,
            "Renders a box as an image to check your work visually. view=top: map seen from above (north is up), with " +
                "a grid every 10 blocks. view=slope: top view tinted by steepness (orange = 2-block steps, red = 3+): " +
                "find cliffs and harsh terraforming. view=iso_se|iso_sw|iso_ne|iso_nw: isometric 3D view from that " +
                "corner; lower `to`'s y to cut a building open. view=plan: floor plan of the storey whose feet level is " +
                "`y` (walls, windows, stairs, doors with hinge and swing, furniture outlines and facing, unreachable spots " +
                "in red): use it for every storey you furnish. Colors are map colors (coarse); for textures use look.",
            schema {
                vec("from", "A corner [x, y, z]")
                vec("to", "The opposite corner [x, y, z]; for iso views keep the y range tight around the build")
                enum("view", listOf("top", "slope", "plan", "iso_se", "iso_sw", "iso_ne", "iso_nw"), "View")
                int("y", "plan: the storey's feet level (the floor block is y - 1); default: the box's lowest y + 1. Keep `from`'s y at the ground outside to see what can't be reached from it", required = false)
                prop("highlight", SchemaBuilder.type("array").apply {
                    add("items", SchemaBuilder.type("array").apply { add("items", SchemaBuilder.vecSchema()) })
                }, "Boxes to outline in red: [[from, to], …]", required = false)
                string("world", "World name", required = false)
            },
        ) { args -> render(plugin, args) },

        Tool(
            ForemanTool.WORLD_PROFILE,
            "A world's building profile, kept in the world: what to follow there instead of neutral defaults. Without " +
                "`set`, shows it (JSON). `set` merges into it (objects key by key, null removes a key): theme (text: look, " +
                "palette, era, level-design rules), build (build_structure defaults: style, roof, decay, clutter, dark, " +
                "furnish, room_width, floor_height, basement…), styles ({name: {extends?: preset, wall: …}}), rooms (room " +
                "types of the furniture catalog, as list_styles shows), uses ({use: [room types]}). Change it only when " +
                "the user asks to.",
            schema {
                prop("set", SchemaBuilder.type("object"), "Fields to merge in (null removes one)", required = false)
                string("world", "World name", required = false)
            },
        ) { args ->
            val world = plugin.world(args.string("world", null)).name
            val profile = if (args.has("set")) plugin.worlds.patchProfile(world, args.obj("set")) else plugin.worlds.profile(world)
            ToolResult.text("Profile of $world" + (if (args.has("set")) " (updated)" else "") + ":\n" +
                com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(profile.json))
        },

        Tool(
            ForemanTool.SET_MARK,
            "Names a place for later (yours and the players'): an area, a box (from + to) or a point (from only). Use it " +
                "to remember what you built (\"town_hall\", \"east_gate\"). theme makes it a zone with its own building " +
                "rules (on top of the world's profile). delete=true removes the area. Areas are kept in the world.",
            schema {
                string("name", "Area name")
                vec("from", "Point, or a corner of the box (not needed to only change the theme)", required = false)
                vec("to", "Opposite corner of the box", required = false)
                string("note", "What it is", required = false)
                string("theme", "Zone rules: palette, era, what to build there (empty: an ordinary area again)", required = false)
                bool("delete", "Remove the area", required = false)
                string("world", "World name", required = false)
            },
        ) { args ->
            val name = args.string("name")
            val world = plugin.world(args.string("world", null)).name
            if (args.bool("delete", false)) {
                return@Tool ToolResult.text(if (plugin.worlds.remove(name, world)) "Removed area $name" else "No area $name")
            }
            if (args.has("from")) {
                val from = args.vec("from")
                plugin.worlds.mark(name, world, Box.of(from, args.vec("to", from)!!), args.string("note", "")!!)
            }
            if (args.has("theme")) plugin.worlds.theme(name, world, args.string("theme"))
            val area = plugin.worlds.area(name, world) ?: throw ToolError("No area $name: give from")
            ToolResult.text("Saved ${area.describe()}" + if (area.theme.isNotEmpty()) "\nZone theme: ${area.theme}" else "")
        },
    )

    private fun context(plugin: Foreman): ToolResult = Sync.main {
        val out = StringBuilder()
        out.appendLine("Default world: ${plugin.world(null).name}")

        val players = Bukkit.getOnlinePlayers()
        out.appendLine("\nPlayers:")
        if (players.isEmpty()) out.appendLine("  (none online)")
        players.forEach { p ->
            val l = p.location
            out.appendLine("  ${p.name} in ${l.world.name} at [${l.blockX}, ${l.blockY}, ${l.blockZ}], facing ${facing(l.yaw)}")
            p.getTargetBlockExact(120)?.let { b ->
                out.appendLine("    looking at ${Blocks.id(b.blockData)} at [${b.x}, ${b.y}, ${b.z}]")
            }
            try {
                val session = WorldEdit.getInstance().sessionManager.get(BukkitAdapter.adapt(p))
                val world = session.selectionWorld
                if (world != null) {
                    val region = session.getSelection(world)
                    out.appendLine("    WorldEdit selection in ${world.name}: ${Box(region.minimumPoint, region.maximumPoint).describe()}")
                }
            } catch (_: IncompleteRegionException) {
            }
            plugin.bridge?.selection(p)?.let { out.appendLine("    Client selection (foreman) in ${it.world}: ${it.box.describe()}") }
        }

        // The profile of each world with someone in it (or the default one), and the zones they stand in.
        val worlds = (players.map { it.world.name } + plugin.world(null).name).distinct()
        worlds.forEach { w ->
            out.appendLine("\nBuilding profile of $w (follow it there; world_profile changes it):")
            out.appendLine(plugin.worlds.profile(w).describe().prependIndent("  "))
            plugin.worlds.areas(w).filter { it.theme.isNotEmpty() }.forEach { z ->
                val here = players.filter { p -> p.world.name == w && z.boxes.any { it.contains(p.location.blockX, p.location.blockY, p.location.blockZ) } }
                out.appendLine("  Zone ${z.name}${if (here.isNotEmpty()) " (${here.joinToString { it.name }} in it)" else ""}: ${z.theme}")
            }
        }

        val areas = plugin.worlds.areas
        out.appendLine("\nAreas (${areas.size}):")
        if (areas.isEmpty()) out.appendLine("  (none)")
        if (areas.size <= 60) areas.forEach { out.appendLine("  ${it.describe()}") }
        else areas.groupBy { it.world }.forEach { (w, list) -> out.appendLine("  $w: " + list.joinToString(", ") { it.name + (it.kind?.let { k -> " ($k)" } ?: "") } + " — claim action=list for boxes") }

        val styles = plugin.stylesFolder.listFiles { f -> f.extension == "md" }?.map { it.nameWithoutExtension }?.sorted().orEmpty()
        out.appendLine("\nStyle guides: ${styles.ifEmpty { listOf("(none)") }.joinToString(", ")}")
        out.appendLine("Assets (schematics): ${plugin.assets.list().size} — list_assets")
        out.appendLine(
            if (Furniture.available) "Furniture (${Furniture.providerName}): ${Furniture.require().types().size} types — list_furniture"
            else "Furniture: none (no plugin provides it)"
        )
        val limits = plugin.limits
        out.appendLine("\nLimits: ${limits.maxEditBlocks} blocks per edit, ${limits.maxReadBlocks} per read_blocks, ${limits.maxRenderBlocks} per render")

        val recent = Edits.recent.take(8)
        if (recent.isNotEmpty()) {
            out.appendLine("\nRecent AI changes (newest first, undo pops them in this order):")
            recent.forEach { out.appendLine("  ${it.label} (${it.blocks} blocks)") }
        }
        ToolResult.text(out.toString())
    }

    private fun facing(yaw: Float): String {
        val faces = listOf("south (+z)", "west (-x)", "north (-z)", "east (+x)")
        return faces[Math.floorMod(Math.round(yaw / 90f), 4)]
    }

    private fun scan(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        if (box.sizeX * box.sizeZ > 1024 * 1024) throw ToolError("Area too large: scan at most 1024x1024")
        val step = max(1, ceil(max(box.sizeX, box.sizeZ) / 96.0).toInt())

        data class Cell(val surface: Blocks.Surface)
        var xs = (box.min.x()..box.max.x() step step).toList()
        var zs = (box.min.z()..box.max.z() step step).toList()
        var cells = Sync.main { zs.map { z -> xs.map { x -> Cell(Blocks.surface(world, x, z)) } } }

        val all = cells.flatten()
        val solid = all.filter { !it.surface.material.isAir }
        if (solid.isEmpty()) return ToolResult.text("Area x ${box.min.x()}..${box.max.x()}, z ${box.min.z()}..${box.max.z()} is empty (void).")

        // Void around the ground is only noise in the grids: crop them to the ground's bounds.
        val rows = cells.indices.filter { r -> cells[r].any { !it.surface.material.isAir } }
        val columns = xs.indices.filter { c -> cells.any { !it[c].surface.material.isAir } }
        val cropped = rows.size < zs.size || columns.size < xs.size
        cells = cells.slice(rows.first()..rows.last()).map { it.slice(columns.first()..columns.last()) }
        xs = xs.slice(columns.first()..columns.last())
        zs = zs.slice(rows.first()..rows.last())
        val base = solid.minOf { it.surface.y }
        val top = solid.maxOf { it.surface.y }

        // Surface materials, most common first, get the first symbols.
        val counts = solid.groupingBy { it.surface.material }.eachCount().entries.sortedByDescending { it.value }
        val symbols = HashMap<Material, Char>()
        counts.forEachIndexed { i, e -> symbols[e.key] = SYMBOLS.getOrElse(i) { '?' } }

        val out = StringBuilder()
        out.appendLine("Area x ${box.min.x()}..${box.max.x()}, z ${box.min.z()}..${box.max.z()} in ${world.name}, step $step (one cell = ${step}x$step blocks, sampled at its north-west block).")
        out.appendLine("Surface height (top solid block, plants and leaves skipped): min $base, max $top, mean ${"%.1f".format(solid.map { it.surface.y }.average())}.")
        val built = solid.count { !it.surface.natural }
        val wet = solid.count { it.surface.underLiquid }
        val void = all.size - solid.size
        out.appendLine("Columns: ${solid.size - built} natural ground, $built built or other, $wet under water/lava" + (if (void > 0) ", $void empty (void: '.' in heights, blank in surface)." else "."))
        if (cropped) out.appendLine("Grids cropped to the ground: x ${xs.first()}..${xs.last()}, z ${zs.first()}..${zs.last()}; the rest of the area is void.")

        out.appendLine("\nHEIGHTS above y=$base, one character per cell: 0-9, then a-z = 10-35, A-Z = 36-61, ^ = higher. Rows go north→south (z), columns west→east (x); header = x mod 10.")
        out.append("       ")
        xs.forEach { out.append(Math.floorMod(it, 10)) }
        out.appendLine()
        cells.forEachIndexed { r, row ->
            out.append("z%6d".format(zs[r]))
            row.forEach { out.append(if (it.surface.material.isAir) '.' else HEIGHTS.getOrElse(it.surface.y - base) { '^' }) }
            out.appendLine()
        }

        out.appendLine("\nSURFACE blocks (uppercase legend flag N = natural ground, B = built/other; '~' = under water). Header = x mod 10.")
        out.append("       ")
        xs.forEach { out.append(Math.floorMod(it, 10)) }
        out.appendLine()
        cells.forEachIndexed { r, row ->
            out.append("z%6d".format(zs[r]))
            row.forEach {
                out.append(if (it.surface.material.isAir) ' ' else if (it.surface.underLiquid) '~' else symbols[it.surface.material])
            }
            out.appendLine()
        }
        out.appendLine("Legend: " + counts.joinToString(", ") { (m, n) ->
            "${symbols[m]}=${Blocks.id(m)}(${if (Blocks.isNaturalGround(m)) "N" else "B"},$n)"
        })

        val furniture = Sync.main { Furniture.inBox(world, Box(box.min.withY(world.minHeight), box.max.withY(world.maxHeight))) }
        if (furniture.isNotEmpty()) out.appendLine("\n" + Furniture.describe(furniture.take(60)))
        return ToolResult.text(out.toString())
    }

    private fun read(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxReadBlocks.toLong(), "read_blocks")
        val axis = args.string("axis", "y")!!

        val (states, furniture) = Sync.main {
            val data = HashMap<BlockVector3, BlockData>()
            for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
                data[BlockVector3.at(x, y, z)] = world.getBlockAt(x, y, z).blockData
            }
            data to Furniture.inBox(world, box)
        }

        val symbols = LinkedHashMap<String, Char>()
        var overflow = false
        fun symbol(data: BlockData): Char {
            if (data.material.isAir) return '.'
            val id = Blocks.id(data)
            return symbols.getOrPut(id) {
                SYMBOLS.getOrNull(symbols.size) ?: run { overflow = true; '?' }
            }
        }

        val out = StringBuilder()
        val body = StringBuilder()
        fun slice(title: String, rows: List<Pair<String, List<BlockVector3>>>, header: String) {
            val text = rows.map { (label, cells) -> label to cells.map { symbol(states[it]!!) }.joinToString("") }
            if (text.all { (_, line) -> line.all { it == '.' } }) {
                body.appendLine("$title: all air")
                return
            }
            body.appendLine(title)
            body.appendLine(header)
            text.forEach { (label, line) -> body.appendLine("$label $line") }
        }
        fun ruler(label: String, from: Int, to: Int) = " ".repeat(label.length) + " " + (from..to).joinToString("") { Math.floorMod(it, 10).toString() }

        when (axis) {
            "y" -> {
                out.appendLine("Layers bottom→top. Rows are z (north→south), columns are x (west→east); the header is x mod 10.")
                for (y in box.min.y()..box.max.y()) {
                    val rows = (box.min.z()..box.max.z()).map { z -> "z%6d".format(z) to (box.min.x()..box.max.x()).map { x -> BlockVector3.at(x, y, z) } }
                    slice("y=$y", rows, ruler("z000000", box.min.x(), box.max.x()))
                }
            }

            "x" -> {
                out.appendLine("Cross-sections west→east. Rows are y (top→bottom), columns are z (north→south); the header is z mod 10.")
                for (x in box.min.x()..box.max.x()) {
                    val rows = (box.max.y() downTo box.min.y()).map { y -> "y%4d".format(y) to (box.min.z()..box.max.z()).map { z -> BlockVector3.at(x, y, z) } }
                    slice("x=$x", rows, ruler("y0000", box.min.z(), box.max.z()))
                }
            }

            else -> {
                out.appendLine("Cross-sections north→south. Rows are y (top→bottom), columns are x (west→east); the header is x mod 10.")
                for (z in box.min.z()..box.max.z()) {
                    val rows = (box.max.y() downTo box.min.y()).map { y -> "y%4d".format(y) to (box.min.x()..box.max.x()).map { x -> BlockVector3.at(x, y, z) } }
                    slice("z=$z", rows, ruler("y0000", box.min.x(), box.max.x()))
                }
            }
        }

        out.appendLine("Legend: . = air" + symbols.entries.joinToString("") { ", ${it.value} = ${it.key}" })
        if (overflow) out.appendLine("(more than ${SYMBOLS.length} distinct states: '?' marks the rest, read a smaller box)")
        out.append(body)
        if (furniture.isNotEmpty()) out.appendLine(Furniture.describe(furniture) + "\n(barrier blocks around them are furniture footprints)")
        return ToolResult.text(out.toString())
    }

    private fun find(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(4_000_000, "find_blocks")
        val matches = Blocks.matcher(args.string("match"))
        val limit = args.int("limit", 100)

        val found = Sync.main {
            val list = mutableListOf<String>()
            var total = 0
            for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
                if (!matches(world.getType(x, y, z))) continue
                val data = world.getBlockAt(x, y, z).blockData
                // Doors and beds are two blocks: report the bottom half / foot only.
                if (data is org.bukkit.block.data.Bisected && data.half == org.bukkit.block.data.Bisected.Half.TOP &&
                    data is org.bukkit.block.data.type.Door) continue
                total++
                if (list.size < limit) list += "[$x, $y, $z] ${Blocks.id(data)}"
            }
            list to total
        }
        val (list, total) = found
        return ToolResult.text(
            if (total == 0) "No match in ${box.describe()}"
            else "$total match(es)${if (total > list.size) ", showing ${list.size}" else ""}:\n" + list.joinToString("\n")
        )
    }

    private fun render(plugin: Foreman, args: Args): ToolResult {
        val highlights = if (args.has("highlight")) args.array("highlight").map {
            val pair = it.asJsonArray
            Box.of(Args.vecOf(pair[0]), Args.vecOf(pair[1]))
        } else emptyList()
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        if (args.string("view") == "plan") return QualityTools.plan(world, box, args.int("y", box.min.y() + 1))
        return render(plugin, world, box, args.string("view"), highlights)
    }

    fun render(plugin: Foreman, world: World, box: Box, view: String, highlights: List<Box>): ToolResult {
        if (view == "plan") return QualityTools.plan(world, box, box.min.y() + 1)
        if (view == "top" || view == "slope") {
            if (box.sizeX * box.sizeZ > 512 * 512) throw ToolError("Top renders are limited to 512x512")
            val top = Sync.main { Renderer.readTop(world, box.min.x(), box.min.z(), box.max.x(), box.max.z()) }
            val image = Renderer.renderTop(top, highlights, slope = view == "slope")
            val scale = image.width / box.sizeX
            return ToolResult.image(
                Renderer.png(image),
                "Top view of x ${box.min.x()}..${box.max.x()}, z ${box.min.z()}..${box.max.z()}: north is up, east is right, " +
                    "$scale px per block, top-left pixel = (x ${box.min.x()}, z ${box.min.z()}). Thin lines every 10 blocks, " +
                    "darker every 50. Brighter = higher (relief lit from the north-west).",
            )
        }

        box.requireVolume(plugin.limits.maxRenderBlocks.toLong(), "Iso renders")
        val corner = Renderer.Corner.valueOf(view.removePrefix("iso_").uppercase())
        val volume = Sync.main { Renderer.readVolume(world, box) }
        val image = Renderer.renderIso(volume, corner, highlights)
        return ToolResult.image(
            Renderer.png(image),
            "Isometric view of ${box.describe()}, ${corner.description}. Slabs and carpets are drawn thin, plants small; " +
                "stairs, fences and walls are drawn as full blocks.",
        )
    }

    /**
     * The result of a tool that changed [box]: [text], plus the mark and the image its `mark` and `render` arguments
     * ask for (see [games.qore.foreman.server.mcp.SchemaBuilder.resultOptions]). The mark covers [markBox] when it differs.
     */
    fun result(plugin: Foreman, world: World, text: String, box: Box?, args: Args, note: String, markBox: Box? = box): ToolResult {
        var out = text
        val mark = args.string("mark", null)
        if (mark != null && markBox != null) {
            val saved = plugin.worlds.mark(mark, world.name, markBox, note)
            out += "\nSaved area ${saved.describe()}"
        }
        val view = args.string("render", null)
        if (view == null || box == null) return ToolResult.text(out)
        return try {
            ToolResult(ToolResult.text(out).content + render(plugin, world, box.grow(2, 1, 2), view, emptyList()).content)
        } catch (e: ToolError) {
            ToolResult.text("$out\n(No render: ${e.message})")
        }
    }
}
