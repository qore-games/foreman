package games.qore.foreman.server.tools

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.session.ClipboardHolder
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Blocks
import games.qore.foreman.server.world.Camera
import games.qore.foreman.server.world.Renderer
import games.qore.foreman.server.world.TallTrees
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Arrange
import games.qore.foreman.server.world.Checks
import games.qore.foreman.server.world.Displays
import games.qore.foreman.server.world.Grid
import games.qore.foreman.server.world.Walk
import games.qore.foreman.server.world.Change
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.block.BlockFace
import org.bukkit.configuration.file.YamlConfiguration
import org.joml.Vector3d
import java.io.File
import kotlin.math.hypot
import kotlin.math.max

object AssetTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.LIST_ASSETS,
            "Lists the schematic library (houses, stalls, trees, rocks, ruins…) and WorldEdit's schematics (fawe/…): " +
                "name, category, size [x, y, z], main blocks, tags, description. Run index_assets after adding schematics.",
            schema {
                string("filter", "Only names, tags, blocks or descriptions containing this text", required = false)
                enum("category", listOf("tree", "plant", "rock", "ruin", "structure"), "Only this category", required = false)
            },
        ) { args ->
            val filter = args.string("filter", null)?.lowercase()
            val category = args.string("category", null)
            val assets = plugin.assets.list().filter {
                (category == null || it.category == category) &&
                    (filter == null || it.name.contains(filter) || it.description.lowercase().contains(filter) || it.tags.any { t -> t.contains(filter) } || it.blocks.any { b -> b.contains(filter) })
            }
            val unindexed = assets.count { it.category == null }
            ToolResult.text(
                if (assets.isEmpty()) "No assets" + (if (filter != null || category != null) " matching" else "") + ". Players add some with /foreman asset save, or put .schem files in WorldEdit's schematics folder."
                else assets.take(400).joinToString("\n") { a ->
                    a.name + (a.category?.let { " ($it)" } ?: "") + (a.size?.let { " ${it[0]}x${it[1]}x${it[2]}" } ?: "") +
                        (if (a.blocks.isNotEmpty()) " {${a.blocks.take(4).joinToString(", ")}}" else "") +
                        (if (a.tags.isNotEmpty()) " [${a.tags.joinToString(", ")}]" else "") +
                        (if (a.description.isNotEmpty()) " — ${a.description}" else "")
                } + (if (assets.size > 400) "\n… ${assets.size - 400} more: use a filter" else "") +
                    (if (unindexed > 0) "\n$unindexed not indexed yet: run index_assets" else "")
            )
        },

        Tool(
            ForemanTool.INDEX_ASSETS,
            "Reads the schematics not indexed yet (or changed since): size, main blocks and a guessed category (tree, " +
                "plant, rock, ruin, structure) for list_assets and scatter_assets. A <name>.yml next to a schematic " +
                "(description, tags, category) overrides the guess.",
            schema { bool("all", "Read every schematic again (default false)", required = false) },
        ) { args ->
            val n = plugin.assets.reindex(args.bool("all", false))
            val counts = plugin.assets.list().groupingBy { it.category ?: "unreadable" }.eachCount()
            ToolResult.text("Indexed $n schematics. Library: " + counts.entries.sortedByDescending { it.value }.joinToString(", ") { "${it.value} ${it.key}" })
        },

        Tool(
            ForemanTool.PREVIEW_ASSET,
            "Renders a schematic of the library alone, textured, from four sides (front-left, front-right, back, above; " +
                "its north is the front): look before pasting or scattering it.",
            schema { string("name", "Asset name from list_assets") },
        ) { args ->
            val asset = plugin.assets.get(args.string("name"))
            val clipboard = plugin.assets.read(asset)
            val region = clipboard.region
            if (region.volume > 400_000) throw ToolError("${asset.name} is too big to preview (${region.volume} blocks)")
            val min = region.minimumPoint
            val cells = HashMap<Triple<Int, Int, Int>, org.bukkit.block.data.BlockData>()
            for (p in region) {
                val data = BukkitAdapter.adapt(clipboard.getBlock(p))
                if (!data.material.isAir) cells[Triple(p.x() - min.x(), p.y() - min.y(), p.z() - min.z())] = data
            }
            if (cells.isEmpty()) throw ToolError("${asset.name} is empty")
            val d = clipboard.dimensions
            val image = ModelTools.turntable(Camera.blocks(cells), Vector3d(0.0), Vector3d(d.x().toDouble(), d.y().toDouble(), d.z().toDouble()))
            ToolResult.image(
                Renderer.png(image),
                "${asset.name}: ${d.x()}x${d.y()}x${d.z()}, ${cells.size} blocks" + (asset.category?.let { ", $it" } ?: "") +
                    (if (asset.description.isNotEmpty()) " — ${asset.description}" else ""),
            )
        },

        Tool(
            ForemanTool.SCATTER_ASSETS,
            "Scatters schematics (rocks, ruins, bushes, trees…) and tall trees over an area, each on the ground: on " +
                "natural ground only, not on water, roads, builds or slopes steeper than max_step, clear of claims, " +
                "spaced, randomly turned. One undo step. Items: asset names, `category:<tree|plant|rock|ruin|structure>` " +
                "for all of a category, or `tree:<tall_spruce|tall_birch|tall_oak|snag>` (tall trees, for places " +
                "players walk); append `*<weight>` to weigh one (`tree:tall_spruce*3`).",
            schema {
                vec("from", "A corner of the area (y is ignored)")
                vec("to", "The opposite corner")
                strings("items", "What to scatter (see above)")
                number("density", "Items per 100 blocks² (default 1)", required = false)
                int("count", "Exactly this many at most, instead of density", required = false)
                number("spacing", "Minimum distance between items, edge to edge (default 2)", required = false)
                int("max_step", "Most the ground may vary under an item, in blocks (default 2)", required = false)
                int("sink", "Blocks sunk into the ground besides the ground layers saved with an asset, which are always sunk (default 0; 1 for rocks and ruins)", required = false)
                string("avoid", "Surface blocks to keep off besides builds and water (default dirt_path,gravel,coarse_dirt,*_slab,*_stairs)", required = false)
                array("keep_clear", SchemaBuilder.type("array"), "Boxes [[x, y, z], [x, y, z]] to keep empty", required = false)
                int("seed", "Random seed (default from the area)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> scatter(plugin, args) },

        Tool(
            ForemanTool.PASTE_ASSET,
            "Pastes a schematic from the library. `position` is where the bottom-center of the asset goes (put y on the " +
                "ground surface + 1 for a build standing on it). Returns the box it occupies: then run foundation / " +
                "blend_terrain on it and render to check.",
            schema {
                string("name", "Asset name from list_assets")
                vec("position", "Bottom-center target [x, y, z]")
                int("rotate", "Clockwise degrees seen from above: 0, 90, 180, 270 (default 0)", required = false)
                bool("ignore_air", "Keep existing blocks where the asset has air (default true)", required = false)
                string("claim", "Claim the pasted box under this name; fails if it overlaps another claim", required = false)
                bool("allow_overlap", "Allow pasting into other claims (default false)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> paste(plugin, args) },

        Tool(
            ForemanTool.SAVE_ASSET,
            "Saves a box of the world into the library, e.g. a build you made and want to reuse.",
            schema {
                string("name", "Lowercase name, folders with /, e.g. houses/fisher_hut")
                vec("from", "A corner")
                vec("to", "The opposite corner")
                string("description", "What it is and how to use it", required = false)
                strings("tags", "Tags (style, type, zone…)", required = false)
                string("world", "World name", required = false)
            },
        ) { args ->
            val world = plugin.world(args.string("world", null))
            val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxEditBlocks.toLong(), "save_asset")
            saveBox(plugin, world, box, args.string("name"), args.string("description", "")!!, args.strings("tags"))
            ToolResult.text("Saved asset ${args.string("name")} (${box.sizeX}x${box.sizeY}x${box.sizeZ})")
        },
    )

    /** The furniture tools, published while a plugin provides furniture ([Furniture]), under its name. */
    fun furniture(plugin: Foreman, provider: String) = listOf(
        Tool(
            ForemanTool.LIST_FURNITURE,
            "Lists the server's furniture (custom models): id, surfaces (floor/wall/ceiling/hanging), " +
                "rotation steps, footprint size in blocks, notes from the catalog. Use a filter: there are many.",
            schema { string("filter", "Only ids or notes containing this text", required = false) },
            provider = provider,
        ) { args -> listFurniture(plugin, args) },

        Tool(
            ForemanTool.PLACE_FURNITURE,
            "Places furniture. Floor furniture: {id, pos: the block it stands in (above the floor), or [x, z] " +
                "to stand on the top block of that column (outdoors), facing: the side its front looks to (north/south/" +
                "east/west: a sofa against a north wall faces south)}. yaw instead of facing is the yaw of a player " +
                "placing it (the front turns toward that player). Wall/hanging/ceiling furniture: {id, against: the block " +
                "it attaches to, face: the face of that block (north/south/east/west/up/down)}, which needs a player " +
                "online. Or let it find the spot: {id, room: a point [x, y, z] in the room (feet level), or [[x, y, z], " +
                "[x, y, z]] its floor box, facing? (else any side with its back to a wall)}: back against a wall, front " +
                "free, clear of doors, doorways and other furniture. {relocate: [x, y, z]} takes the furniture rooted " +
                "there and moves it to the best spot of its room (fixes furniture blocking a doorway). Each placement " +
                "is then checked: its model sunk into walls, it blocking a door or doorway. Look at the result with " +
                "render view=plan. Undo removes them.",
            schema {
                array("items", SchemaBuilder.type("object"), "Furniture to place: [{id, pos, facing?, yaw?, state?} | {id, against, face}]")
                string("claim", "Only place inside this claim (others are refused unless allow_overlap)", required = false)
                bool("allow_overlap", "Allow placing inside other claims (default false)", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
            provider = provider,
        ) { args -> placeFurniture(plugin, args) },

        Tool(
            ForemanTool.REMOVE_FURNITURE,
            "Removes the furniture whose root block is in a box (no drops). Undo puts it back.",
            schema {
                vec("from", "A corner")
                vec("to", "The opposite corner")
                string("id", "Only this furniture id", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
            provider = provider,
        ) { args -> removeFurniture(plugin, args) },
    )

    fun saveBox(plugin: Foreman, world: org.bukkit.World, box: Box, name: String, description: String, tags: List<String>) {
        val clipboard = Sync.main {
            WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(world)).build().use { session ->
                val region = CuboidRegion(session.world, box.min, box.max)
                val clipboard = BlockArrayClipboard(region)
                clipboard.origin = box.min
                val copy = ForwardExtentCopy(session, region, clipboard, region.minimumPoint)
                copy.isCopyingEntities = true
                Operations.complete(copy)
                clipboard
            }
        }
        plugin.assets.save(name, clipboard, description, tags)
    }

    private fun paste(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val asset = plugin.assets.get(args.string("name"))
        val position = args.vec("position")
        val rotate = Math.floorMod(args.int("rotate", 0), 360)
        if (rotate % 90 != 0) throw ToolError("rotate must be 0, 90, 180 or 270")

        val clipboard = plugin.assets.read(asset)
        // Bottom-center origin, so position and rotation are about the footprint's center.
        clipboard.origin = origin(clipboard)
        val transform = AffineTransform().rotateY(-rotate.toDouble())
        val result = footprint(clipboard, rotate, position)

        QualityTools.guard(plugin, world, result, args.string("claim", null), args.bool("allow_overlap", false))
        val label = "paste ${asset.name} at ${Box.fmt(position)}" + if (rotate != 0) " rotated $rotate°" else ""
        val (_, change) = Edits.edit(world, label) { session, _ ->
            val holder = ClipboardHolder(clipboard)
            holder.transform = transform
            Operations.complete(
                holder.createPaste(session).to(position).ignoreAirBlocks(args.bool("ignore_air", true)).copyEntities(true).copyBiomes(false).build()
            )
        }
        Sync.main { Furniture.inBox(world, result) }
        args.string("claim", null)?.let { plugin.worlds.reserve(it, world.name, result) }
        return InspectTools.result(plugin, world, "Pasted ${asset.name}: it occupies ${result.describe()}, ${change.blocks} blocks.", result, args, asset.name)
    }

    private class Item(val name: String, val weight: Double, val clipboard: com.sk89q.worldedit.extent.clipboard.Clipboard?, val tree: String?) {
        /** Half the footprint's larger side: the radius used for spacing. */
        val radius: Double = clipboard?.dimensions?.let { max(it.x(), it.z()) / 2.0 } ?: 2.5

        /** The bottom layers that are ground saved with the asset (mostly natural ground): sunk into the ground. */
        val base: Int = clipboard?.let { c ->
            val r = c.region
            var n = 0
            for (y in r.minimumPoint.y()..r.maximumPoint.y()) {
                var ground = 0
                var total = 0
                for (x in r.minimumPoint.x()..r.maximumPoint.x()) for (z in r.minimumPoint.z()..r.maximumPoint.z()) {
                    val m = BukkitAdapter.adapt(c.getBlock(BlockVector3.at(x, y, z)).blockType)
                    if (m.isAir) continue
                    total++
                    if (Blocks.isNaturalGround(m)) ground++
                }
                if (total == 0 || ground < total * 0.8) break
                n++
            }
            n
        } ?: 0
    }

    private fun scatter(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val area = Box.of(args.vec("from"), args.vec("to"))
        val items = args.strings("items").flatMap { spec ->
            val name = spec.substringBefore('*')
            val weight = spec.substringAfter('*', "1").toDoubleOrNull() ?: throw ToolError("Bad weight in '$spec'")
            when {
                name.startsWith("tree:") -> {
                    val kind = name.removePrefix("tree:")
                    if (kind !in TallTrees.KINDS) throw ToolError("Tall trees: ${TallTrees.KINDS.joinToString(", ")}")
                    listOf(Item(name, weight, null, kind))
                }
                name.startsWith("category:") -> {
                    val list = plugin.assets.list().filter { it.category == name.removePrefix("category:") }
                    if (list.isEmpty()) throw ToolError("No asset in $name (run index_assets?)")
                    list.map { Item(it.name, weight / list.size, plugin.assets.read(it), null) }
                }
                else -> listOf(plugin.assets.get(name).let { Item(it.name, weight, plugin.assets.read(it), null) })
            }
        }
        if (items.isEmpty()) throw ToolError("Give items")
        val sizeX = area.max.x() - area.min.x() + 1
        val sizeZ = area.max.z() - area.min.z() + 1
        if (sizeX.toLong() * sizeZ > 512L * 512) throw ToolError("The area is too big: 512x512 at most")
        val target = if (args.has("count")) args.int("count") else (sizeX * sizeZ * args.double("density", 1.0) / 100).toInt().coerceAtLeast(1)
        val spacing = args.double("spacing", 2.0)
        val maxStep = args.int("max_step", 2)
        val sink = args.int("sink", 0)
        val avoid = Blocks.matcher(args.string("avoid", "dirt_path,gravel,coarse_dirt,*_slab,*_stairs")!!)
        val keepClear = if (args.has("keep_clear")) args.array("keep_clear").map { b -> b.asJsonArray.let { Box.of(Args.vecOf(it[0]), Args.vecOf(it[1])) } } else emptyList()
        val rng = kotlin.random.Random(args.int("seed", area.min.x() * 31 + area.min.z()))

        // The ground of every column: its height, or null where nothing may stand.
        val ground = Sync.main {
            Chunks.keepLoaded(world, area)
            Array(sizeX) { i -> Array(sizeZ) { k ->
                val s = Blocks.surface(world, area.min.x() + i, area.min.z() + k)
                s.y.takeIf { s.natural && !s.underLiquid && !avoid(s.material) }
            } }
        }
        fun height(x: Int, z: Int) = ground.getOrNull(x - area.min.x())?.getOrNull(z - area.min.z())

        class Placed(val x: Int, val z: Int, val radius: Double)
        val placed = ArrayList<Placed>()
        val plan = ArrayList<Triple<Item, BlockVector3, Int>>()
        val total = items.sumOf { it.weight }
        var tries = 0
        while (plan.size < target && tries++ < target * 40) {
            var r = rng.nextDouble() * total
            val item = items.firstOrNull { r -= it.weight; r <= 0 } ?: items.last()
            val x = area.min.x() + rng.nextInt(sizeX)
            val z = area.min.z() + rng.nextInt(sizeZ)
            if (placed.any { p -> hypot((p.x - x).toDouble(), (p.z - z).toDouble()) < p.radius + item.radius + spacing }) continue
            val rotate = rng.nextInt(4) * 90
            // The columns its bottom layer stands on (rotated), or the trunk for a tree.
            val feet = item.clipboard?.let { bottom(it, rotate, item.base) } ?: listOf(0 to 0)
            val heights = feet.map { (dx, dz) -> height(x + dx, z + dz) ?: -1 }
            if (heights.any { it < 0 }) continue
            if (heights.max() - heights.min() > maxStep) continue
            val position = BlockVector3.at(x, heights.min() + 1 - sink - item.base, z)
            val box = item.clipboard?.let { footprint(it, rotate, position) } ?: Box(position.subtract(4, 0, 4), position.add(4, 17, 4))
            if (keepClear.any { it.intersect(box) != null }) continue
            if (plugin.worlds.conflicts(world.name, box, null).isNotEmpty()) continue
            placed += Placed(x, z, item.radius)
            plan += Triple(item, position, rotate)
        }
        if (plan.isEmpty()) throw ToolError("Nowhere to put them: no natural, flat enough ground clear of claims, roads and water in ${area.describe()}")

        val label = "scatter ${plan.size} ${items.joinToString(", ") { it.name }.take(80)}"
        val (_, change) = Edits.edit(world, label) { session, _ ->
            for ((item, position, rotate) in plan) {
                if (item.tree != null) {
                    TallTrees.grow(item.tree, position.x(), position.y(), position.z(), rng) { x, y, z, block ->
                        if (Blocks.isCover(world.getType(x, y, z))) session.setBlock(x, y, z, BukkitAdapter.adapt(Bukkit.createBlockData(block)))
                    }
                    continue
                }
                val clipboard = item.clipboard!!
                clipboard.origin = origin(clipboard)
                val holder = ClipboardHolder(clipboard)
                holder.transform = AffineTransform().rotateY(-rotate.toDouble())
                Operations.complete(holder.createPaste(session).to(position).ignoreAirBlocks(true).copyEntities(true).copyBiomes(false).build())
            }
        }
        val box = plan.map { (item, p, rotate) -> item.clipboard?.let { footprint(it, rotate, p) } ?: Box(p.subtract(4, 0, 4), p.add(4, 17, 4)) }.reduce { a, b -> a.union(b) }
        Sync.main { Furniture.inBox(world, box) }
        val counts = plan.groupingBy { it.first.name }.eachCount().entries.joinToString(", ") { "${it.value} ${it.key}" }
        val short = if (plan.size < target) " (${target - plan.size} found no room)" else ""
        return InspectTools.result(plugin, world, "Scattered ${plan.size}$short: $counts. ${change.blocks} blocks in ${box.describe()}.", box, args, label)
    }

    /** Bottom-center of a clipboard: what paste_asset and scatter_assets position. */
    private fun origin(clipboard: com.sk89q.worldedit.extent.clipboard.Clipboard): BlockVector3 {
        val min = clipboard.region.minimumPoint
        val max = clipboard.region.maximumPoint
        return BlockVector3.at(Math.floorDiv(min.x() + max.x(), 2), min.y(), Math.floorDiv(min.z() + max.z(), 2))
    }

    /**
     * The columns (relative to the origin, turned by [rotate]) where the clipboard's lowest layer above its [base]
     * ground layers has blocks.
     */
    private fun bottom(clipboard: com.sk89q.worldedit.extent.clipboard.Clipboard, rotate: Int, base: Int): List<Pair<Int, Int>> {
        val region = clipboard.region
        val origin = origin(clipboard)
        val transform = AffineTransform().rotateY(-rotate.toDouble())
        val y = region.minimumPoint.y() + base
        val cells = (region.minimumPoint.x()..region.maximumPoint.x()).flatMap { x -> (region.minimumPoint.z()..region.maximumPoint.z()).map { z -> BlockVector3.at(x, y, z) } }
            .filter { !clipboard.getBlock(it).blockType.material.isAir }
            .map { transform.apply(it.subtract(origin).toVector3()).toBlockPoint().let { p -> p.x() to p.z() } }
        return cells.ifEmpty { listOf(0 to 0) }
    }

    private fun footprint(clipboard: com.sk89q.worldedit.extent.clipboard.Clipboard, rotate: Int, position: BlockVector3): Box {
        val min = clipboard.region.minimumPoint
        val max = clipboard.region.maximumPoint
        val origin = origin(clipboard)
        val transform = AffineTransform().rotateY(-rotate.toDouble())
        val corners = listOf(min, max, BlockVector3.at(min.x(), min.y(), max.z()), BlockVector3.at(max.x(), max.y(), min.z()))
            .map { transform.apply(it.subtract(origin).toVector3()).toBlockPoint().add(position) }
        return Box(corners.reduce { a, b -> a.getMinimum(b) }, corners.reduce { a, b -> a.getMaximum(b) })
    }

    private fun notes(plugin: Foreman): Map<String, String> {
        val file = File(plugin.dataFolder, "catalog/furniture.yml")
        if (!file.exists()) return emptyMap()
        val yaml = YamlConfiguration.loadConfiguration(file)
        return yaml.getKeys(false).associateWith { yaml.getString(it) ?: "" }
    }

    private fun listFurniture(plugin: Foreman, args: Args): ToolResult {
        val filter = args.string("filter", null)?.lowercase()
        val notes = notes(plugin)
        val lines = Sync.main { Furniture.require().types().toList() }
            .filter { filter == null || it.id().contains(filter) || notes[it.id()]?.lowercase()?.contains(filter) == true }
            .sortedBy { it.id() }
            .map { d ->
                val cells = d.footprint()
                val size = if (cells.isEmpty()) "1x1x1" else {
                    val xs = cells.map { it.blockX }
                    val ys = cells.map { it.blockY }
                    val zs = cells.map { it.blockZ }
                    "${xs.max() - xs.min() + 1}x${ys.max() - ys.min() + 1}x${zs.max() - zs.min() + 1}"
                }
                "${d.id()} ${d.surfaces().joinToString("/")} ${size} ${d.rotations()}-way" + (notes[d.id()]?.let { " — $it" } ?: "")
            }
        return ToolResult.text(
            if (lines.isEmpty()) "No furniture matches" else
                "id surfaces footprint(x*y*z, unrotated) rotations — notes\n" + lines.take(300).joinToString("\n") +
                    if (lines.size > 300) "\n… ${lines.size - 300} more: use a filter" else ""
        )
    }

    private fun placeFurniture(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val items = args.array("items").map { Args(it.asJsonObject) }

        val change = Change("place ${items.size} furniture", world)
        // Furniture already there (for collisions, relocations) must be loaded first.
        items.mapNotNull { i -> listOf("relocate", "room", "pos").firstOrNull { i.has(it) }?.let { k -> runCatching { i.array(k) }.getOrNull() } }
            .mapNotNull { a -> runCatching { if (a.size() == 2 && a[0].isJsonArray) Args.vecOf(a[0]) else if (a.size() == 3) Args.vecOf(a) else null }.getOrNull() }
            .forEach { p -> Furniture.ensureLoaded(world, Box(p.subtract(16, 4, 16), p.add(16, 6, 16))) }
        val lines = Sync.main {
            val provider = Furniture.require()
            items.map { item ->
                if (item.has("room") || item.has("relocate")) return@map arrange(plugin, world, item, args, change)
                val pos = if (item.has("pos")) position(world, item) else null
                (pos ?: item.vec("against", null))?.let { Chunks.keepLoaded(world, Box(it, it)) }
                val id = item.string("id")
                val type = Furniture.type(id)
                val placed = if (item.has("against")) {
                    val player = Bukkit.getOnlinePlayers().firstOrNull { it.world == world }
                        ?: throw ToolError("Wall/ceiling placement needs a player online in ${world.name}")
                    val against = item.vec("against")
                    val face = runCatching { BlockFace.valueOf(item.string("face").uppercase()) }.getOrNull()
                        ?: throw ToolError("face must be north, south, east, west, up or down")
                    provider.place(type, player, world.getBlockAt(against.x(), against.y(), against.z()), face)
                        ?.also { change.spawnedFurniture += it.handle }?.let(Furniture::of)
                } else {
                    pos ?: throw ToolError("Floor furniture needs pos")
                    QualityTools.guard(plugin, world, Box(pos, pos), args.string("claim", null), args.bool("allow_overlap", false))
                    Furniture.spawn(world, type, pos, yaw(item), item.string("state", null), change)
                }
                placed?.let { "$id placed at [${it.x}, ${it.y}, ${it.z}] facing ${Checks.front(it.yaw).name.lowercase()}" + inspect(world, it) }
                    ?: "$id: placement refused (obstructed or protected)"
            }
        }
        change.blocks = change.spawnedFurniture.size + change.removedFurniture.size
        if (change.blocks > 0) Edits.record(change)
        return ToolResult.text(lines.joinToString("\n"))
    }

    /** place_furniture with `room` or `relocate`: the best spot of the room (see [Arrange]). Main thread. */
    private fun arrange(plugin: Foreman, world: org.bukkit.World, item: Args, args: Args, change: Change): String {
        var id = item.string("id", null)
        var state = item.string("state", null)
        var original: Furniture.Placed? = null
        val roomArg = if (item.has("room")) item.array("room") else null
        val point: BlockVector3
        var box: Box? = null
        if (item.has("relocate")) {
            point = item.vec("relocate")
            Chunks.keepLoaded(world, Box(point, point))
            val f = Furniture.inBox(world, Box(point, point)).firstOrNull() ?: throw ToolError("No furniture rooted at ${Box.fmt(point)}")
            id = f.id
            state = f.state
            original = f
            Furniture.lift(world, listOf(f), change)
        } else if (roomArg != null && roomArg.size() == 2 && roomArg[0].isJsonArray) {
            box = Box.of(Args.vecOf(roomArg[0]), Args.vecOf(roomArg[1]))
            point = box.min
        } else {
            point = item.vec("room")
        }
        val type = Furniture.type(id ?: throw ToolError("Give id"))
        val y = box?.min?.y() ?: point.y()
        val area = (box ?: Box(point, point)).let { b -> Box(BlockVector3.at(b.min.x() - 14, y - 2, b.min.z() - 14), BlockVector3.at(b.max.x() + 14, y + 5, b.max.z() + 14)) }
        val grid = Grid.read(world, area)
        val walk = Walk(grid)
        val openings = Checks.openings(grid, walk)
        val room = if (box != null) (box.min.x()..box.max.x()).flatMap { x -> (box.min.z()..box.max.z()).map { z -> BlockVector3.at(x, y, z) } }
            .filter { c -> walk.column(c.x(), c.z()).any { walk.nodes[it].y == y } }.toSet()
        else Arrange.room(grid, walk, openings, point)
        val taken = Furniture.inBox(world, area).flatMap { f -> f.cells.ifEmpty { listOf(BlockVector3.at(f.x, f.y, f.z)) } }.toSet()
        val facing = item.string("facing", null)?.let { runCatching { BlockFace.valueOf(it.uppercase()) }.getOrNull() ?: throw ToolError("facing must be north, south, east or west") }
        val why = HashMap<String, Int>()
        val spot = Arrange.find(grid, room, openings, type.footprint(), taken, facing, ModelTools.boundsByFacing(type), why)
        if (spot == null) {
            // Nothing fits: a relocated furniture goes back where it was.
            original?.let { Furniture.spawn(world, type, BlockVector3.at(it.x, it.y, it.z), it.yaw, it.state, change) }
            return "$id: no free spot against a wall in that room (${room.size} floor cells; refused: " +
                why.entries.sortedByDescending { it.value }.joinToString(", ") { if (it.value == 0) it.key else "${it.key} ${it.value}" } + ")" +
                if (original != null) ", left where it was" else ""
        }
        QualityTools.guard(plugin, world, Box(spot.root, spot.root), args.string("claim", null), args.bool("allow_overlap", true))
        val placed = Furniture.spawn(world, type, spot.root, spot.yaw, state, change)
        return placed?.let { "$id ${if (original != null) "moved" else "placed"} to ${Box.fmt(spot.root)} facing ${spot.facing.name.lowercase()}" + inspect(world, it) }
            ?: "$id: placement refused at ${Box.fmt(spot.root)}"
    }

    /** The yaw that makes the front look to `facing` (the placing player stands in front), or `yaw`. */
    private fun yaw(item: Args): Float {
        val facing = item.string("facing", null) ?: return item.double("yaw", 0.0).toFloat()
        return when (facing.lowercase()) {
            "north" -> 0f; "east" -> 90f; "south" -> 180f; "west" -> 270f
            else -> throw ToolError("facing must be north, south, east or west")
        }
    }

    /** Problems of a furniture just placed: its model inside blocks, a door or doorway it blocks. Main thread. */
    private fun inspect(world: org.bukkit.World, f: Furniture.Placed): String {
        val box = Box(BlockVector3.at(f.x - 4, f.y - 2, f.z - 4), BlockVector3.at(f.x + 4, f.y + 4, f.z + 4))
        val grid = Grid.read(world, box)
        val shown = Displays.bake(Displays.read(world, box).filter { it.id == f.entity })
        val report = Checks.run(grid, listOf(f), shown, setOf("furniture", "doors"), Checks.Options())
        val mine = report.issues.filter { it.check == "furniture" || it.text.contains(f.id) }
        val model = shown.firstOrNull()?.baked?.let { " (model ${Displays.describe(it.min, it.max)})" } ?: ""
        return model + mine.joinToString("") { "\n  ! ${it.text}" }
    }

    /** `pos` as [x, y, z], or [x, z] on the top block of the column. */
    private fun position(world: org.bukkit.World, item: Args): BlockVector3 {
        val a = item.array("pos")
        return when (a.size()) {
            3 -> Args.vecOf(a)
            2 -> BlockVector3.at(a[0].asInt, Blocks.surface(world, a[0].asInt, a[1].asInt).y + 1, a[1].asInt)
            else -> throw ToolError("pos is [x, y, z] or [x, z], got $a")
        }
    }

    /** Removes the furniture rooted in [box] (only [only] if given) into [change], for undo. Main thread, loaded first. */
    fun removeIn(world: org.bukkit.World, box: Box, only: String?, change: Change) {
        Furniture.lift(world, Furniture.inBox(world, box).filter { only == null || it.id == only }, change)
    }

    private fun removeFurniture(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        val only = args.string("id", null)

        val change = Change("remove furniture in ${box.describe()}", world)
        Furniture.ensureLoaded(world, box)
        Sync.main { removeIn(world, box, only, change) }
        change.blocks = change.removedFurniture.size
        if (change.removedFurniture.isNotEmpty()) Edits.record(change)
        return ToolResult.text("Removed ${change.removedFurniture.size} furniture" + change.removedFurniture.groupingBy { it.id }.eachCount().entries.joinToString("") { "\n  ${it.value}x ${it.key}" })
    }
}
