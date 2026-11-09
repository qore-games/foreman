package games.qore.foreman.server.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.Msg
import games.qore.foreman.server.api.ForemanWorldLoader
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Area
import games.qore.foreman.server.world.AreaAssets
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Change
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Instance
import games.qore.foreman.server.world.Instances
import games.qore.foreman.server.world.Library
import games.qore.foreman.server.world.Locks
import games.qore.foreman.server.world.Placement
import games.qore.foreman.server.world.Sync
import games.qore.foreman.server.world.WorldEditSelections
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.math.max

/**
 * The asset library's own tools: defining asset areas (fitted to what they hold, or on a free plot), resizing and moving
 * them with everything inside, the instances paste_asset records, and taking a player to an asset's source.
 */
object LibraryTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.DEFINE_ASSET,
            "Makes an asset area of the library (an area tagged `asset`, area:<world>/<name>), or redefines one: its box, " +
                "tags and note. `fit` shrinks the box to what it holds (blocks, entities, points) plus a block; `plot` keeps " +
                "only the box's size and puts it on the next free plot of the world (a grid beside the other asset areas, " +
                "8 blocks apart), to build a new asset in. Undo puts the area back.",
            schema {
                string("name", "Area name, e.g. market_stall")
                vec("from", "A corner")
                vec("to", "The opposite corner")
                strings("tags", "Tags besides `asset` (style, type, zone…)", required = false)
                string("note", "What it is and how to use it (its description in list_assets)", required = false)
                bool("fit", "Shrink the box to its content plus 1 block (default false)", required = false)
                bool("plot", "Put a box of this size on the world's next free plot instead (default false)", required = false)
                format("{id, box: [x1, y1, z1, x2, y2, z2]}")
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> define(plugin, args) },

        Tool(
            ForemanTool.FIT_ASSET,
            "Shrinks an asset area's box to what it holds (blocks, entities, points) plus a margin, within its box. The " +
                "content doesn't change. Undo puts the box back.",
            schema {
                string("name", "Asset area name or id (area:<world>/<area>)")
                int("margin", "Blocks around the content (default 1)", required = false)
                format("{id, box: [x1, y1, z1, x2, y2, z2]}")
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> fit(plugin, args) },

        Tool(
            ForemanTool.RESIZE_ASSET,
            "Sets an asset area's box. Only the area changes, not what is in the world (move_asset moves the content). " +
                "Undo puts the box back.",
            schema {
                string("name", "Asset area name or id (area:<world>/<area>)")
                vec("from", "A corner")
                vec("to", "The opposite corner")
                format("{id, box: [x1, y1, z1, x2, y2, z2]}")
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> resize(plugin, args) },

        Tool(
            ForemanTool.MOVE_ASSET,
            "Moves an asset area and everything in it within its world: blocks with their block data, entities " +
                "(teleported, keeping their UUIDs: furniture, displays, interactions, mobs…; players standing in it go " +
                "along), the points, areas and " +
                "instances inside it, then the area. `to` is the new min corner, or `by` an offset; `rotate` turns it " +
                "about its bottom centre first. Refused when the destination overlaps another asset area or a claim. " +
                "One undo step.",
            schema {
                string("name", "Asset area name or id (area:<world>/<area>)")
                vec("to", "The new min corner [x, y, z] (after rotate)", required = false)
                vec("by", "Or an offset [dx, dy, dz]", required = false)
                int("rotate", "Clockwise degrees seen from above: 0, 90, 180, 270 (default 0)", required = false)
                format("{id, box, from, blocks, entities, block_data, points, areas, instances}")
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> move(plugin, args) },

        Tool(
            ForemanTool.LIST_INSTANCES,
            "Lists the instances paste_asset recorded (each paste of a library asset): id, asset, where, turned how, " +
                "points and areas it brought, when and by whom. With `asset`: where that asset is used.",
            schema {
                string("asset", "Only instances of this asset (its id or name, or part of it)", required = false)
                format("an array of {id, world, dim, asset, source?: {world, area}, origin, rotate, box, points: [{name, type, kind, position}], areas, created, by}")
                string("world", "Only this world (default: every loaded world)", required = false)
            },
        ) { args -> listInstances(plugin, args) },

        Tool(
            ForemanTool.INSTANCE_INFO,
            "Everything recorded of one instance: its asset and source, origin, rotation, box, points, areas and " +
                "entities (the asset's UUID → the pasted copy's).",
            schema {
                string("id", "Instance id, e.g. market_stall-2")
                format("{id, world, dim, asset, source?, origin, rotate, box, points, areas, entities: {source uuid: pasted uuid}, created, by}")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
        ) { args -> instanceInfo(plugin, args) },

        Tool(
            ForemanTool.DETACH_INSTANCE,
            "Forgets an instance: its record and its entities' `foreman:instance` tag go, what it pasted stays (blocks, " +
                "entities, points and areas, which keep their names).",
            schema {
                string("id", "Instance id")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> detach(plugin, args) },

        Tool(
            ForemanTool.DELETE_INSTANCE,
            "Removes a pasted copy (an instance, list_instances) and only it: the blocks its paste wrote (to air; " +
                "blocks it didn't write, like the ground it was pasted on, stay), the entities tagged with it (furniture " +
                "through the furniture plugin), their block data, the points and areas it brought, and its record. One " +
                "undo step.",
            schema {
                string("id", "Instance id")
                format("{deleted: id}")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> deleteInstance(plugin, args) },

        Tool(
            ForemanTool.ROTATE_INSTANCE,
            "Turns a pasted copy in place about the bottom centre of its box, with everything in the box (blocks and " +
                "their block data, entities and furniture, points, areas), as move_asset does; its record follows " +
                "(rotation, box, points, the blocks it wrote). Refused when the turned box lands on another asset area " +
                "or claim. One undo step.",
            schema {
                string("id", "Instance id")
                int("rotate", "Clockwise degrees seen from above: 90, 180 or 270")
                format("the instance, as list_instances gives it")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> rotateInstance(plugin, args) },

        Tool(
            ForemanTool.SYNC_INSTANCE,
            "Replaces a pasted copy with its asset's current content, at the same place and rotation and under the same " +
                "id: the copy is removed (as delete_instance) and the asset pasted again. Anything changed inside the copy " +
                "since it was pasted is lost. Fails when the asset, or the world it comes from, isn't available. One undo " +
                "step.",
            schema {
                string("id", "Instance id")
                format("the instance, as list_instances gives it")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> syncInstance(plugin, args) },

        Tool(
            ForemanTool.OPEN_ASSET_SOURCE,
            "Takes the calling player to the area an asset comes from (its library world, loaded first when a plugin " +
                "loads worlds for Foreman), in front of it (north side) facing it: on the ground there, else at its floor " +
                "level, flying (flight turned on). For a player's own session only.",
            schema {
                string("instance", "An instance id: open the asset it was pasted from", required = false)
                string("asset", "Or an asset id (area:<world>/<area>) or area name", required = false)
                string("world", "Where the instance is, or the area (default: the player's world, else any loaded world)", required = false)
            },
        ) { args -> openSource(plugin, args) },
    )

    /**
     * `/foreman asset new <name> [tags…]` (an asset area of the WorldEdit selection) and `/foreman asset fit <name>`;
     * false for the other `asset` subcommands. Main thread; the work runs off it.
     */
    fun command(plugin: Foreman, sender: CommandSender, args: List<String>): Boolean {
        val sub = args.getOrNull(0)
        if (sub != "new" && sub != "fit") return false
        val player = sender as? Player
        val name = args.getOrNull(1)
        if (name == null) {
            Msg.error(sender, Component.text(if (sub == "new") "/foreman asset new <name> [tags…]" else "/foreman asset fit <name>"))
            return true
        }
        val json = JsonObject().apply {
            addProperty("name", name)
            player?.let { addProperty("world", it.world.name) }
        }
        if (sub == "new") {
            val box = player?.let { WorldEditSelections.of(it) } ?: run {
                Msg.error(sender, Component.text("Select the build with WorldEdit first"))
                return true
            }
            json.add("from", JsonArray().apply { add(box.min.x()); add(box.min.y()); add(box.min.z()) })
            json.add("to", JsonArray().apply { add(box.max.x()); add(box.max.y()); add(box.max.z()) })
            json.add("tags", JsonArray().apply { args.drop(2).forEach { add(it) } })
        }
        val caller = Caller.of(sender)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            val result = try {
                Caller.acting(caller.takeUnless { it.isConsole }) { if (sub == "new") define(plugin, Args(json)) else fit(plugin, Args(json)) }
            } catch (e: Exception) {
                if (e !is ToolError) plugin.logger.log(java.util.logging.Level.WARNING, "/foreman asset $sub $name failed", e)
                ToolResult.error(e.message ?: e.toString())
            }
            val text = result.content.mapNotNull { it.get("text")?.asString }.joinToString("\n")
            Bukkit.getScheduler().runTask(plugin, Runnable { if (result.isError) Msg.error(sender, Component.text(text)) else Msg.success(sender, Component.text(text)) })
        })
        return true
    }

    private fun games.qore.foreman.server.mcp.SchemaBuilder.format(json: String) =
        enum("format", listOf("text", "json"), "text (default), or json: $json", required = false)

    private fun json(args: Args) = when (args.string("format", "text")) {
        "text" -> false
        "json" -> true
        else -> throw ToolError("format is text or json")
    }

    // ---- Defining -----------------------------------------------------------------------------------------------------

    /** The asset area [name] (an id, or a name in [world] / the default world). */
    private fun asset(plugin: Foreman, name: String, world: String?): Area {
        if (AreaAssets.isId(name)) return AreaAssets.get(plugin.worlds, name)
        val w = plugin.world(world)
        val area = plugin.worlds.area(name, w.name) ?: throw ToolError("No area '$name' in ${w.name} (define_asset makes one)")
        if (AreaAssets.TAG !in area.tags) throw ToolError("$name is an area but not an asset: tag it `${AreaAssets.TAG}` or use define_asset")
        return area
    }

    /** Puts [area] in place of [old] (null: it is new), one undo step. */
    private fun save(plugin: Foreman, label: String, old: Area?, area: Area) = Sync.main {
        val world = Bukkit.getWorld(area.world) ?: throw ToolError("World ${area.world} is not loaded")
        plugin.worlds.put(area)
        val change = Change(label, world)
        change.touch(area.bounds)
        old?.let { change.touch(it.bounds) }
        change.restores += Runnable { if (old == null) plugin.worlds.remove(area.name, area.world) else plugin.worlds.put(old) }
        Edits.record(change)
    }

    private fun reply(args: Args, area: Area, text: String): ToolResult =
        if (json(args)) ToolResult.text(JsonObject().apply {
            addProperty("id", AreaAssets.id(area))
            add("box", box(area.bounds))
        }.toString())
        else ToolResult.text("$text ${AreaAssets.id(area)}: ${area.bounds.describe()}" + area.tags.filter { it != AreaAssets.TAG }.joinToString("") { " #$it" })

    private fun define(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val name = args.string("name").trim()
        if (name.isEmpty() || name.any { it.isWhitespace() }) throw ToolError("An area name is one word")
        var box = Box.of(args.vec("from"), args.vec("to"))
        val fit = args.bool("fit", false)
        if (fit && args.bool("plot", false)) throw ToolError("fit reads what a box holds and plot finds an empty one: give one of them")
        if (args.bool("plot", false)) box = Library.plot(plugin.worlds, world.name, box)
        if (fit) box = fitted(plugin, world, box, 1)
        val tags = Area.tags(listOf(AreaAssets.TAG) + args.strings("tags"))
        val old = plugin.worlds.area(name, world.name)
        val area = (old ?: Area(name, world.name, listOf(box))).copy(
            boxes = listOf(box), tags = (old?.tags.orEmpty() + tags).distinct(), note = args.string("note", null) ?: old?.note ?: "",
        )
        save(plugin, "define asset $name", old, area)
        return reply(args, area, if (old == null) "Defined" else "Redefined")
    }

    /** What [box] holds, [margin] blocks around it, within [box]. */
    private fun fitted(plugin: Foreman, world: World, box: Box, margin: Int): Box {
        box.requireVolume(plugin.limits.maxEditBlocks.toLong(), "fit")
        Chunks.loadEntities(world, listOf(box.grow(Library.REACH, Library.REACH, Library.REACH)))
        val content = Sync.main { Library.content(world, box) } ?: throw ToolError("Nothing in ${box.describe()} to fit to")
        return content.grow(margin, margin, margin).intersect(box)!!
    }

    private fun fit(plugin: Foreman, args: Args): ToolResult {
        val area = asset(plugin, args.string("name"), args.string("world", null))
        val world = AreaAssets.world(area)
        val box = fitted(plugin, world, area.bounds, args.int("margin", 1).coerceAtLeast(0))
        val fitted = area.copy(boxes = listOf(box))
        save(plugin, "fit asset ${area.name}", area, fitted)
        return reply(args, fitted, "Fitted")
    }

    private fun resize(plugin: Foreman, args: Args): ToolResult {
        val area = asset(plugin, args.string("name"), args.string("world", null))
        val resized = area.copy(boxes = listOf(Box.of(args.vec("from"), args.vec("to"))))
        save(plugin, "resize asset ${area.name}", area, resized)
        return reply(args, resized, "Resized")
    }

    // ---- Moving -------------------------------------------------------------------------------------------------------

    private fun move(plugin: Foreman, args: Args): ToolResult {
        val area = asset(plugin, args.string("name"), args.string("world", null))
        val world = AreaAssets.world(area)
        val from = area.bounds.requireVolume(plugin.limits.maxEditBlocks.toLong(), "move_asset")
        val rotate = Math.floorMod(args.int("rotate", 0), 360)
        if (rotate % 90 != 0) throw ToolError("rotate must be 0, 90, 180 or 270")
        if (args.has("to") && args.has("by")) throw ToolError("Give to or by, not both")
        if (!args.has("to") && !args.has("by") && rotate == 0) throw ToolError("Give to (the new min corner), by (an offset) or rotate")
        val turned = Placement.around(from, rotate).box(from)
        val offset = args.vec("to", null)?.subtract(turned.min) ?: args.vec("by", BlockVector3.ZERO)!!
        val placement = Placement.around(from, rotate, offset)
        val to = placement.box(from)
        if (to.min.y() < world.minHeight || to.max.y() >= world.maxHeight) throw ToolError("${to.describe()} is outside the world's height (${world.minHeight} to ${world.maxHeight - 1})")

        // What moves along doesn't stand in the way (the area, the areas inside it), nor a claim it already stands in.
        refuseObstacles(plugin, world, from, to)

        val around = from.grow(Library.REACH, Library.REACH, Library.REACH)
        Furniture.ensureLoaded(world, around)
        Chunks.loadEntities(world, listOf(around, to))
        val label = "move asset ${area.name}" + (if (offset != BlockVector3.ZERO) " by ${Box.fmt(offset)}" else "") + (if (rotate != 0) " turned $rotate°" else "")
        val (moved, change) = Edits.scoped(world, label) { scope -> Library.move(scope, from, placement) }
        val now = plugin.worlds.area(area.name, world.name) ?: area
        if (json(args)) return ToolResult.text(JsonObject().apply {
            addProperty("id", AreaAssets.id(now))
            add("box", box(moved.box))
            add("from", box(moved.from))
            addProperty("blocks", change.blocks)
            addProperty("entities", moved.entities)
            addProperty("block_data", moved.blockData)
            addProperty("points", moved.points)
            addProperty("areas", moved.areas - 1)
            addProperty("instances", moved.instances.size)
        }.toString())
        return ToolResult.text(
            "Moved ${AreaAssets.id(now)} from ${moved.from.describe()} to ${moved.box.describe()}" + (if (rotate != 0) ", turned $rotate°" else "") +
                ": ${change.blocks} blocks, ${moved.entities} entities, ${moved.blockData} block data entries, ${moved.points} points, " +
                "${moved.areas - 1} areas, ${moved.instances.size} instances. Undo moves it all back.",
        )
    }

    /** Refuses a move of [from] to [to] that lands on another asset area or a claim ([Library.obstacles]). */
    private fun refuseObstacles(plugin: Foreman, world: World, from: Box, to: Box) {
        Locks.guard(world.name, from)
        Locks.guard(world.name, to)
        val hits = Library.obstacles(plugin.worlds, world.name, from, to)
        if (hits.isNotEmpty()) throw ToolError(
            "The destination ${to.describe()} overlaps " + hits.take(4).joinToString(", ") { a -> (if (AreaAssets.TAG in a.tags) "asset " else "claim ") + "${a.name} (${a.bounds.describe()})" } +
                ": choose another place",
        )
    }

    // ---- Instances ----------------------------------------------------------------------------------------------------

    /** An instance for programs and the bridge: everything when [full], else what the client draws. */
    fun json(i: Instance, full: Boolean = true) = JsonObject().apply {
        addProperty("id", i.id)
        addProperty("world", i.world)
        addProperty("dim", Bukkit.getWorld(i.world)?.key?.toString() ?: "minecraft:${i.world}")
        addProperty("asset", i.asset)
        if (i.sourceWorld != null) add("source", JsonObject().apply {
            addProperty("world", i.sourceWorld)
            addProperty("area", i.sourceArea)
        })
        if (full) add("origin", JsonArray().apply { add(i.origin.x()); add(i.origin.y()); add(i.origin.z()) })
        addProperty("rotate", i.rotate)
        add("box", box(i.box))
        add("points", JsonArray().apply {
            i.points.forEach { p ->
                add(JsonObject().apply {
                    addProperty("name", p.build)
                    addProperty("type", p.type)
                    addProperty("kind", p.kind)
                    add("position", JsonArray().apply { add(p.x); add(p.y); add(p.z) })
                    p.yaw?.let { addProperty("yaw", it) }
                    p.pitch?.let { addProperty("pitch", it) }
                })
            }
        })
        if (full) {
            i.written?.let { addProperty("written", it.count) }
            add("areas", JsonArray().apply { i.areas.forEach { add(it) } })
            add("entities", JsonObject().apply { i.entities.forEach { (k, v) -> addProperty(k.toString(), v.toString()) } })
        }
        addProperty("created", i.created)
        addProperty("by", i.by)
    }

    private fun box(b: Box) = JsonArray().apply { listOf(b.min.x(), b.min.y(), b.min.z(), b.max.x(), b.max.y(), b.max.z()).forEach { add(it) } }

    /** The instance [id], in [world] or the loaded world that has it. */
    private fun instance(plugin: Foreman, id: String, world: String?, prefer: World? = null): Instance {
        if (world != null) return plugin.worlds.instance(plugin.world(world).name, id) ?: throw ToolError("No instance '$id' in $world (list_instances)")
        val found = plugin.worlds.allInstances.filter { it.id == id }
        return found.firstOrNull { it.world == prefer?.name } ?: found.singleOrNull()
            ?: if (found.isEmpty()) throw ToolError("No instance '$id' in the loaded worlds (list_instances)")
            else throw ToolError("Instance '$id' is in several worlds (${found.joinToString(", ") { it.world }}): give world")
    }

    private fun listInstances(plugin: Foreman, args: Args): ToolResult {
        val asset = args.string("asset", null)?.lowercase()
        val all = args.string("world", null)?.let { plugin.worlds.instances(plugin.world(it).name) } ?: plugin.worlds.allInstances
        val list = all.filter { asset == null || it.asset.lowercase() == asset || it.sourceArea?.lowercase() == asset || it.asset.lowercase().contains(asset) }
        if (json(args)) return ToolResult.text(JsonArray().apply { list.forEach { add(json(it)) } }.toString())
        if (list.isEmpty()) return ToolResult.text("No instances" + (asset?.let { " of $it" } ?: "") + ". paste_asset records one per paste.")
        return ToolResult.text(list.groupBy { it.world }.entries.joinToString("\n") { (world, items) ->
            "$world:\n" + items.take(300).joinToString("\n") { "  " + it.describe() }
        })
    }

    private fun instanceInfo(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        if (json(args)) return ToolResult.text(json(i).toString())
        return ToolResult.text(
            i.describe() + " in ${i.world}, ${java.time.Instant.ofEpochMilli(i.created)}" +
                (i.sourceWorld?.let { "\nSource: area ${i.sourceArea} in $it" } ?: "\nSource: schematic ${i.asset}") +
                (if (i.points.isNotEmpty()) "\nPoints:\n" + i.points.joinToString("\n") { "  " + it.describe() } else "") +
                (if (i.areas.isNotEmpty()) "\nAreas: " + i.areas.joinToString(", ") else "") +
                (if (i.entities.isNotEmpty()) "\nEntities (asset → pasted):\n" + i.entities.entries.take(100).joinToString("\n") { "  ${it.key} → ${it.value}" } else ""),
        )
    }

    private fun detach(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        Locks.guard(world.name, i.box)
        Chunks.loadEntities(world, listOf(i.box))
        val untagged = Sync.main {
            val tagged = (i.entities.values.mapNotNull { Bukkit.getEntity(it) } + Library.entities(world, i.box))
                .filter { it.persistentDataContainer.get(AreaAssets.INSTANCE_KEY, PersistentDataType.STRING) == i.id }.distinct()
            tagged.forEach { it.persistentDataContainer.remove(AreaAssets.INSTANCE_KEY) }
            plugin.worlds.removeInstance(i.world, i.id)
            tagged.size
        }
        return ToolResult.text("Detached ${i.id}: its record is gone, $untagged entities untagged; its blocks, entities, points and areas stay.")
    }

    private fun deleteInstance(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        Locks.guard(world.name, i.box)
        Instances.prepare(world, i)
        val (deleted, change) = Edits.scoped(world, "delete instance ${i.id}") { scope -> Instances.delete(scope, i) }
        if (json(args)) return ToolResult.text(JsonObject().apply { addProperty("deleted", i.id) }.toString())
        return ToolResult.text(
            "Deleted ${i.id}: " + (deleted.blocks?.let { "$it blocks it wrote (${change.blocks} changed)" } ?: "its blocks stayed (pasted before Foreman recorded the blocks pastes write)") +
                ", ${deleted.entities} entities (${deleted.furniture} furniture), ${deleted.blockData} block data entries, ${deleted.points} points, " +
                "${deleted.areas} areas, and its record. Undo puts it back.",
        )
    }

    private fun rotateInstance(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        val rotate = Math.floorMod(args.int("rotate"), 360)
        if (rotate == 0 || rotate % 90 != 0) throw ToolError("rotate must be 90, 180 or 270")
        i.box.requireVolume(plugin.limits.maxEditBlocks.toLong(), "rotate_instance")
        val placement = Placement.around(i.box, rotate)
        val to = placement.box(i.box)
        refuseObstacles(plugin, world, i.box, to)
        val around = i.box.grow(Library.REACH, Library.REACH, Library.REACH)
        Furniture.ensureLoaded(world, around)
        Chunks.loadEntities(world, listOf(around, to))
        val (moved, change) = Edits.scoped(world, "turn instance ${i.id} $rotate°") { scope -> Library.move(scope, i.box, placement) }
        val now = moved.instances.firstOrNull { it.id == i.id } ?: throw ToolError("${i.id} was not moved")
        if (json(args)) return ToolResult.text(json(now).toString())
        return ToolResult.text(
            "Turned ${i.id} $rotate° in place: it occupies ${now.box.describe()} (was ${i.box.describe()}), turned ${now.rotate}° from its asset; " +
                "${change.blocks} blocks, ${moved.entities} entities, ${moved.blockData} block data entries, ${moved.points} points. Undo turns it back.",
        )
    }

    private fun syncInstance(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        Locks.guard(world.name, i.box)
        val label = "sync instance ${i.id} with ${i.asset}"
        val (pasted, change) = if (i.sourceWorld != null && i.sourceArea != null) {
            val source = Bukkit.getWorld(i.sourceWorld)
                ?: throw ToolError("${i.id} comes from ${i.asset}, whose world ${i.sourceWorld} isn't loaded: load it (open_asset_source), then sync again")
            val area = plugin.worlds.area(i.sourceArea, source.name)?.takeIf { AreaAssets.TAG in it.tags }
                ?: throw ToolError("${i.id} comes from ${i.asset}, which isn't an asset area any more (list_assets)")
            area.bounds.requireVolume(plugin.limits.maxEditBlocks.toLong(), "sync_instance")
            Instances.prepare(world, i)
            AreaAssets.prepare(area, world, AreaAssets.footprint(area.bounds, i.rotate, i.origin))
            val copy = Sync.main { AreaAssets.copy(area) }
            Edits.scoped(world, label) { scope ->
                Instances.delete(scope, i)
                scope.flush()
                AreaAssets.paste(scope, copy, i.origin, i.rotate, true, i.id).instance
            }
        } else {
            val asset = try {
                plugin.assets.get(i.asset)
            } catch (_: ToolError) {
                throw ToolError("${i.id} comes from schematic ${i.asset}, which isn't in the library any more (list_assets)")
            }
            val clipboard = plugin.assets.read(asset)
            Instances.prepare(world, i)
            Chunks.loadEntities(world, listOf(AreaAssets.footprint(Box(clipboard.region.minimumPoint, clipboard.region.maximumPoint), i.rotate, i.origin)))
            Edits.scoped(world, label) { scope ->
                Instances.delete(scope, i)
                scope.flush()
                AssetTools.pasteSchematic(plugin, scope, asset.name, clipboard, i.origin, i.rotate, true, i.id)
            }
        }
        if (json(args)) return ToolResult.text(json(pasted).toString())
        return ToolResult.text(
            "Synced ${i.id} with ${i.asset}: removed and pasted again at ${Box.fmt(pasted.origin)}" + (if (pasted.rotate != 0) " turned ${pasted.rotate}°" else "") +
                ", ${pasted.box.describe()}, ${change.blocks} blocks changed. Changes made inside it since its paste are gone; undo brings them back.",
        )
    }

    // ---- Opening the source -------------------------------------------------------------------------------------------

    private fun openSource(plugin: Foreman, args: Args): ToolResult {
        val caller = args.caller?.takeUnless { it.isConsole }
            ?: throw ToolError("open_asset_source moves the calling player: call it from a player's session (their token), not the console")
        val player = Sync.main { Bukkit.getPlayer(caller.id) } ?: throw ToolError("${caller.name} is not online")
        val (worldName, areaName) = when {
            args.has("instance") -> {
                val i = instance(plugin, args.string("instance"), args.string("world", null), player.world)
                if (i.sourceWorld == null || i.sourceArea == null) throw ToolError("${i.id} was pasted from schematic ${i.asset}, not from an area")
                i.sourceWorld to i.sourceArea
            }
            args.has("asset") -> {
                val asset = args.string("asset")
                if (AreaAssets.isId(asset)) source(asset)
                else (args.string("world", null) ?: player.world.name) to asset
            }
            else -> throw ToolError("Give instance or asset")
        }
        val world = Bukkit.getWorld(worldName) ?: load(worldName)
        val area = plugin.worlds.area(areaName, world.name) ?: throw ToolError("No area '$areaName' in ${world.name}")
        val spot = Sync.main { front(world, area.bounds) }
        val moved = Sync.main { player.teleportAsync(spot.at) }.get(30, TimeUnit.SECONDS)
        if (!moved) throw ToolError("${player.name} could not be teleported to ${world.name}")
        // Nothing to stand on (a void library world): they hover at the asset's floor instead of falling.
        val flying = !spot.ground && Sync.main {
            player.fallDistance = 0f
            if (player.gameMode == GameMode.SPECTATOR) return@main false
            player.allowFlight = true
            player.isFlying = true
            true
        }
        return ToolResult.text(
            "Took ${player.name} to ${AreaAssets.id(area)}" + (if (world.name != worldName) " (recorded as $worldName)" else "") + ": " +
                (if (spot.ground) "standing on the ground in front of it (north side), facing it"
                else "no ground in front of it, so at its floor level just outside it (north side), facing it" + if (flying) ", flying (flight turned on)" else "") +
                ". Its box: ${area.bounds.describe()}",
        )
    }

    /** The world and area of an area id, its world loaded or not. */
    private fun source(id: String): Pair<String, String> {
        val rest = id.removePrefix(AreaAssets.PREFIX)
        val world = Bukkit.getWorlds().map { it.name }.filter { rest.startsWith("$it/") }.maxByOrNull { it.length } ?: rest.substringBefore('/')
        if (rest.length <= world.length + 1) throw ToolError("Area ids are area:<world>/<area>, got '$id'")
        return world to rest.removePrefix("$world/")
    }

    /** [name] loaded by the plugin that loads worlds for Foreman ([ForemanWorldLoader]). */
    private fun load(name: String): World {
        val registration = Sync.main { Bukkit.getServicesManager().getRegistration(ForemanWorldLoader::class.java) }
            ?: throw ToolError("World $name is not loaded, and no plugin loads worlds for Foreman: load it, then call again")
        val loader = registration.provider
        if (!Sync.main { loader.canLoad(name) }) throw ToolError("World $name is not loaded, and ${registration.plugin.name} can't load it")
        val future = CompletableFuture<World>()
        Sync.main {
            loader.load(name) { world, error ->
                if (world != null) future.complete(world)
                else future.completeExceptionally(ToolError("${registration.plugin.name} could not load $name: ${error ?: "no reason given"}"))
            }
        }
        return try {
            future.get(110, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (_: TimeoutException) {
            throw ToolError("$name is still loading: call again in a moment")
        }
    }

    private const val EYE_HEIGHT = 1.62

    /** Where a player is put to see an asset, and whether they stand on the ground there. */
    private class Spot(val at: Location, val ground: Boolean)

    /**
     * In front of [box] (its north side, its front), looking at its centre: on the ground, from far enough to see it whole
     * down to 2 blocks from it, near its floor level; else at its floor level 2 blocks out (in the air). Main thread.
     */
    private fun front(world: World, box: Box): Spot {
        val centre = Vector((box.min.x() + box.max.x() + 1) / 2.0, (box.min.y() + box.max.y() + 1) / 2.0, (box.min.z() + box.max.z() + 1) / 2.0)
        val x = Math.floorDiv(box.min.x() + box.max.x(), 2)
        val far = (max(box.sizeX, box.sizeY) * 0.9 + 2).toInt().coerceAtLeast(2)
        fun spot(z: Int, feet: Int, ground: Boolean): Spot {
            val at = Location(world, x + 0.5, feet.toDouble(), z + 0.5)
            return Spot(at.setDirection(centre.clone().subtract(Vector(at.x, at.y + EYE_HEIGHT, at.z))), ground)
        }
        for (d in far downTo 2) {
            val z = box.min.z() - d
            val floor = (box.min.y() + FLOOR_REACH downTo box.min.y() - FLOOR_REACH).firstOrNull { y -> standable(world, x, y, z) } ?: continue
            return spot(z, floor + 1, true)
        }
        return spot(box.min.z() - 2, box.min.y(), false)
    }

    /** How far above or below an asset's floor ground in front of it may be. */
    private const val FLOOR_REACH = 4

    /** A solid block at [y] with room for a player above it. */
    private fun standable(world: World, x: Int, y: Int, z: Int): Boolean {
        if (y < world.minHeight || y + 2 >= world.maxHeight) return false
        val block = world.getBlockAt(x, y, z)
        return block.type.isSolid && !block.isPassable && world.getBlockAt(x, y + 1, z).isPassable && world.getBlockAt(x, y + 2, z).isPassable
    }
}
