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
import games.qore.foreman.server.world.Clipboards
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Fingerprints
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
            "Lists the instances paste_asset recorded (each paste of a library asset) as a tree: the copies nested in a " +
                "copy (the instances its asset area held, `<parent>/<child>`) under it. Each: id, asset, where, turned and " +
                "mirrored how, detached or not, points and areas it brought, when and by whom. With `asset`: where that " +
                "asset is used, nested copies included.",
            schema {
                string("asset", "Only instances of this asset (its id or name, or part of it)", required = false)
                format("an array of {id, world, dim, asset, source?: {world, area}, origin, rotate, mirror, detached, parent?, box, points: [{name, type, kind, position}], areas, created, by}, parents before their children")
                string("world", "Only this world (default: every loaded world)", required = false)
            },
        ) { args -> listInstances(plugin, args) },

        Tool(
            ForemanTool.INSTANCE_INFO,
            "Everything recorded of one instance: its asset and source, origin, rotation, mirror, box, points, areas and " +
                "entities (the asset's UUID → the pasted copy's), the copy it is nested in and those nested in it, and " +
                "whether each was changed since its paste (what sync_instance and sync_asset skip without force).",
            schema {
                string("id", "Instance id, e.g. market_stall-2 or a nested one, town_hall-1/market_stall-2")
                format("{id, world, dim, asset, source?, origin, rotate, mirror, detached, parent?, children: [{id, asset, detached, edited}], edited: true|false|null (unknown), box, points, areas, entities: {source uuid: pasted uuid}, created, by}")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
        ) { args -> instanceInfo(plugin, args) },

        Tool(
            ForemanTool.DETACH_INSTANCE,
            "Detaches a pasted copy from its asset, reversibly: it stays recorded with its points, areas and entities, " +
                "flagged detached, and syncs never touch it (sync_asset skips it; a sync of the copy it is nested in keeps " +
                "its box exactly as it is). For a copy you mean to change by hand. attach_instance undoes it; " +
                "forget_instance drops the record instead. Undo puts it back.",
            schema {
                string("id", "Instance id")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> detach(plugin, args) },

        Tool(
            ForemanTool.ATTACH_INSTANCE,
            "Attaches a detached copy to its asset again: what it holds now counts as unchanged, so its next sync " +
                "(sync_asset, sync_instance) replaces it with the asset's content; `sync: true` syncs it now. Undo puts it " +
                "back (the sync too).",
            schema {
                string("id", "Instance id")
                bool("sync", "Sync it with its asset now (default false)", required = false)
                bool("force", "With sync: also replace copies nested in it that were changed (default false)", required = false)
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> attach(plugin, args) },

        Tool(
            ForemanTool.FORGET_INSTANCE,
            "Forgets a pasted copy and the copies nested in it: their records and their entities' `foreman:instance` tag " +
                "go, what they pasted stays (blocks, entities, points and areas, which keep their names): plain blocks, no " +
                "longer a copy of anything. Undo puts them back.",
            schema {
                string("id", "Instance id")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> forget(plugin, args) },

        Tool(
            ForemanTool.DELETE_INSTANCE,
            "Removes a pasted copy (an instance, list_instances) and only it, with the copies nested in it: the blocks " +
                "their pastes wrote (to air; blocks they didn't write, like the ground it was pasted on, stay), the " +
                "entities tagged with them (furniture through the furniture plugin), their block data, the points and " +
                "areas they brought, and their records. One undo step.",
            schema {
                string("id", "Instance id")
                format("{deleted: id, instances: [ids]}")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> deleteInstance(plugin, args) },

        Tool(
            ForemanTool.ROTATE_INSTANCE,
            "Turns a pasted copy in place about the bottom centre of its box, with everything in the box (blocks and " +
                "their block data, entities and furniture, points, areas, the copies nested in it), as move_asset does; " +
                "the records follow (rotation, box, points, the blocks written, fingerprints). Refused when the turned box " +
                "lands on another asset area or claim. One undo step.",
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
            "Replaces a pasted copy with its asset's current content, at the same place, rotation and mirror, under the " +
                "same id: the copy and those nested in it removed (as delete_instance), its detached nested copies kept " +
                "as they are, the asset pasted again, its nested copies made again from the asset's current inner " +
                "instances. A nested copy is pasted again inside its own box only (refused when its asset changed size). " +
                "Refused for a detached copy, and for one changed since its paste (or with nested copies changed) unless " +
                "`force`. Fails when the asset, or the world it comes from, isn't available. One undo step.",
            schema {
                string("id", "Instance id")
                bool("force", "Sync it even when it, or a copy nested in it, was changed since its paste: the changes are lost (default false)", required = false)
                format("the instance, as list_instances gives it, with children: [ids]")
                string("world", "World name (default: the loaded world that has it)", required = false)
            },
            modifies = true,
        ) { args -> syncInstance(plugin, args) },

        Tool(
            ForemanTool.SYNC_ASSET,
            "After changing an asset, makes every copy of it follow: pastes it again over each of its instances in the " +
                "loaded worlds, nested ones included (an apartment inside each pasted building), as sync_instance does. " +
                "Skips, and lists with the reason, detached copies, copies changed since their paste (unless `force`), " +
                "copies lying in the asset's own area, and nested copies whose asset changed size; a copy nested in " +
                "another being synced is made again with it. One undo step for every world. Returns what it synced and " +
                "what it skipped.",
            schema {
                string("asset", "Asset id (area:<world>/<area>), asset area name, or schematic name")
                bool("force", "Also sync copies changed since their paste: their changes are lost (default false)", required = false)
                string("world", "Only the copies in this world (default: every loaded world)", required = false)
                format("{asset, synced: [{id, world, children: [ids]}], covered: [{id, world, with}], skipped: [{id, world, reason}], blocks}")
            },
            modifies = true,
        ) { args -> syncAsset(plugin, args) },

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
        if (full || i.mirror) addProperty("mirror", i.mirror)
        if (full || i.detached) addProperty("detached", i.detached)
        i.parent?.let { addProperty("parent", it) }
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
            .sortedWith(compareBy({ it.world }, { it.id }))
        if (json(args)) return ToolResult.text(JsonArray().apply { list.forEach { add(json(it)) } }.toString())
        if (list.isEmpty()) return ToolResult.text("No instances" + (asset?.let { " of $it" } ?: "") + ". paste_asset records one per paste.")
        return ToolResult.text(list.groupBy { it.world }.entries.joinToString("\n") { (world, items) ->
            "$world:\n" + items.take(300).joinToString("\n") { i ->
                // Under its parent when it is listed too.
                val depth = if (asset == null) i.id.count { it == '/' } else 0
                "  " + "  ".repeat(depth) + i.describe()
            }
        })
    }

    private fun instanceInfo(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        val children = plugin.worlds.descendants(i.world, i.id)
        Instances.prepare(world, i)
        val tree = listOf(i) + children
        val changed = Sync.main { tree.filter { Fingerprints.changed(world, it) }.map { it.id }.toSet() }
        val states = tree.associate { c -> c.id to Fingerprints.state(c) { c.id in changed } }
        fun state(id: String) = when {
            id in changed -> "changed since its paste"
            states[id] == Fingerprints.State.EDITED -> "pasted with the changes its source copy had (in the asset holding it)"
            states[id] == Fingerprints.State.CLEAN -> "unchanged"
            else -> "unknown (pasted before Foreman took fingerprints)"
        }
        if (json(args)) return ToolResult.text(json(i).apply {
            add("children", JsonArray().apply {
                children.forEach { c ->
                    add(JsonObject().apply {
                        addProperty("id", c.id)
                        addProperty("asset", c.asset)
                        addProperty("detached", c.detached)
                        states[c.id]?.takeIf { it != Fingerprints.State.UNKNOWN }?.let { addProperty("edited", it == Fingerprints.State.EDITED) }
                    })
                }
            })
            when (states[i.id]) {
                Fingerprints.State.UNKNOWN, null -> add("edited", com.google.gson.JsonNull.INSTANCE)
                else -> addProperty("edited", states[i.id] == Fingerprints.State.EDITED)
            }
        }.toString())
        return ToolResult.text(
            i.describe() + " in ${i.world}, ${java.time.Instant.ofEpochMilli(i.created)}" +
                (i.sourceWorld?.let { "\nSource: area ${i.sourceArea} in $it" } ?: "\nSource: schematic ${i.asset}") +
                (i.parent?.let { "\nNested in $it" } ?: "") +
                "\nState: " + (if (i.detached) "detached (syncs leave it); " else "") + state(i.id) +
                (if (children.isNotEmpty()) "\nNested copies:\n" + children.joinToString("\n") { c ->
                    "  " + "  ".repeat(c.id.count { it == '/' } - i.id.count { it == '/' } - 1) + "${c.id}: ${c.asset}" +
                        c.orientation.let { if (it.isEmpty()) "" else ", $it" } + (if (c.detached) ", detached" else "") + ", ${state(c.id)}"
                } else "") +
                (if (i.points.isNotEmpty()) "\nPoints:\n" + i.points.joinToString("\n") { "  " + it.describe() } else "") +
                (if (i.areas.isNotEmpty()) "\nAreas: " + i.areas.joinToString(", ") else "") +
                (if (i.entities.isNotEmpty()) "\nEntities (asset → pasted):\n" + i.entities.entries.take(100).joinToString("\n") { "  ${it.key} → ${it.value}" } else ""),
        )
    }

    /** Puts [now] in place of [i]'s record as [label], one undo step. Main thread. */
    private fun replace(plugin: Foreman, world: World, i: Instance, now: Instance, label: String): Change {
        plugin.worlds.putInstance(now)
        val change = Change(label, world)
        change.touch(i.box)
        change.restores += Runnable { if (Bukkit.getWorld(i.world) != null) plugin.worlds.putInstance(i) }
        Edits.record(change)
        return change
    }

    private fun detach(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        if (i.detached) return ToolResult.text("${i.id} is already detached (attach_instance to make it follow its asset again)")
        Locks.guard(world.name, i.box)
        Sync.main { replace(plugin, world, i, i.copy(detached = true), "detach instance ${i.id}") }
        val nested = plugin.worlds.descendants(i.world, i.id).size
        return ToolResult.text(
            "Detached ${i.id}: syncs leave it as it is" + (if (i.parent != null) ", a sync of ${i.parent} too (its box stays exactly as it is)" else "") +
                (if (nested > 0) "; the $nested copies nested in it still follow their assets (detach them too to keep them)" else "") +
                ". attach_instance makes it follow its asset again; undo puts it back.",
        )
    }

    private fun attach(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        val sync = args.bool("sync", false)
        if (Clipboards.pasted(i)) throw ToolError("${i.id} was pasted from a clipboard: it has no asset to follow")
        if (!i.detached && !sync) return ToolResult.text("${i.id} isn't detached: it follows its asset")
        Locks.guard(world.name, i.box)
        val attached = if (!i.detached) null else {
            Instances.prepare(world, i)
            Sync.main {
                val now = i.copy(detached = false, edited = false)
                replace(plugin, world, i, now.copy(fingerprint = Fingerprints.of(world, now)), "attach instance ${i.id}")
            }
        }
        if (!sync) return ToolResult.text("Attached ${i.id}: what it holds now counts as unchanged, its next sync replaces it with ${i.asset}. Undo puts it back.")
        val now = plugin.worlds.instance(i.world, i.id) ?: throw ToolError("${i.id} is gone")
        val source = InstanceSync.Source.of(plugin, now)
        val plan = InstanceSync.plan(plugin, source, listOf(now), args.bool("force", false))
        plan.skipped.firstOrNull()?.let { return ToolResult.text("Attached ${i.id}, but didn't sync it: ${it.reason}") }
        val result = InstanceSync.run(plugin, source, plan, "attach and sync instance ${i.id}")
        val label = "attach and sync instance ${i.id}"
        Edits.join(listOfNotNull(attached, result.change), label)
        val synced = result.synced.single()
        return ToolResult.text(
            (if (attached != null) "Attached and synced" else "Synced") + " ${i.id} with ${i.asset}: ${result.blocks} blocks changed" +
                (if (synced.children.isNotEmpty()) ", ${synced.children.size} nested copies made again" else "") + ". Undo puts it back.",
        )
    }

    private fun forget(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        Locks.guard(world.name, i.box)
        Instances.prepare(world, i)
        val (forgotten, untagged) = Sync.main {
            val change = Change("forget instance ${i.id}", world)
            Instances.forget(world, i, change).also { Edits.record(change) }
        }
        return ToolResult.text(
            "Forgot ${i.id}" + (if (forgotten.size > 1) " and the ${forgotten.size - 1} copies nested in it" else "") +
                ": records gone, $untagged entities untagged; blocks, entities, points and areas stay as plain content. Undo puts them back.",
        )
    }

    private fun deleteInstance(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val world = Bukkit.getWorld(i.world) ?: throw ToolError("World ${i.world} is not loaded")
        Locks.guard(world.name, i.box)
        Instances.prepare(world, i)
        val (deleted, change) = Edits.scoped(world, "delete instance ${i.id}") { scope -> Instances.delete(scope, i) }
        if (json(args)) return ToolResult.text(JsonObject().apply {
            addProperty("deleted", i.id)
            add("instances", JsonArray().apply { deleted.instances.forEach { add(it.id) } })
        }.toString())
        return ToolResult.text(
            "Deleted ${i.id}" + (if (deleted.instances.size > 1) " and the ${deleted.instances.size - 1} copies nested in it" else "") + ": " +
                (deleted.blocks?.let { "$it blocks they wrote (${change.blocks} changed)" } ?: "their blocks stayed (pasted before Foreman recorded the blocks pastes write)") +
                ", ${deleted.entities} entities (${deleted.furniture} furniture), ${deleted.blockData} block data entries, ${deleted.points} points, " +
                "${deleted.areas} areas, and their records. Undo puts it back.",
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
        val nested = moved.instances.count { it.isUnder(i.id) }
        if (json(args)) return ToolResult.text(json(now).toString())
        return ToolResult.text(
            "Turned ${i.id} $rotate° in place: it occupies ${now.box.describe()} (was ${i.box.describe()}), ${now.orientation.ifEmpty { "as" }} from its asset" +
                (if (nested > 0) ", with the $nested copies nested in it" else "") + "; " +
                "${change.blocks} blocks, ${moved.entities} entities, ${moved.blockData} block data entries, ${moved.points} points. Undo turns it back.",
        )
    }

    private fun syncInstance(plugin: Foreman, args: Args): ToolResult {
        val i = instance(plugin, args.string("id"), args.string("world", null))
        val source = InstanceSync.Source.of(plugin, i)
        val plan = InstanceSync.plan(plugin, source, listOf(i), args.bool("force", false))
        plan.skipped.firstOrNull()?.let { throw ToolError("Didn't sync ${i.id}: ${it.reason}") }
        val result = InstanceSync.run(plugin, source, plan, "sync instance ${i.id} with ${i.asset}")
        val synced = result.synced.single()
        val pasted = synced.instance
        if (json(args)) return ToolResult.text(json(pasted).apply { add("children", JsonArray().apply { synced.children.forEach { add(it.id) } }) }.toString())
        val kept = Instances.detachedIn(pasted)
        return ToolResult.text(
            "Synced ${i.id} with ${i.asset}: removed and pasted again at ${Box.fmt(pasted.origin)}" + pasted.orientation.let { if (it.isEmpty()) "" else " $it" } +
                ", ${pasted.box.describe()}, ${result.blocks} blocks changed" +
                (if (synced.children.isNotEmpty()) ", ${synced.children.size} nested copies made again (${synced.children.joinToString(", ") { it.id }})" else "") +
                (if (kept.isNotEmpty()) ", detached ones kept as they were (${kept.joinToString(", ") { it.id }})" else "") +
                ". Undo brings the old one back.",
        )
    }

    private fun syncAsset(plugin: Foreman, args: Args): ToolResult {
        val source = InstanceSync.Source.of(plugin, args.string("asset"))
        val world = args.string("world", null)?.let { plugin.world(it).name }
        val instances = plugin.worlds.allInstances.filter { it.asset == source.id && (world == null || it.world == world) }
        if (instances.isEmpty()) return ToolResult.text("No copies of ${source.id}" + (world?.let { " in $it" } ?: " in the loaded worlds") + " (list_instances)")
        val plan = InstanceSync.plan(plugin, source, instances, args.bool("force", false))
        val result = InstanceSync.run(plugin, source, plan, "sync ${plan.sync.size} copies of ${source.id}")
        if (json(args)) return ToolResult.text(JsonObject().apply {
            addProperty("asset", source.id)
            add("synced", JsonArray().apply {
                result.synced.forEach { s ->
                    add(JsonObject().apply {
                        addProperty("id", s.instance.id)
                        addProperty("world", s.instance.world)
                        add("children", JsonArray().apply { s.children.forEach { add(it.id) } })
                    })
                }
            })
            add("covered", JsonArray().apply {
                plan.covered.forEach { (i, with) -> add(JsonObject().apply { addProperty("id", i.id); addProperty("world", i.world); addProperty("with", with.id) }) }
            })
            add("skipped", JsonArray().apply {
                plan.skipped.forEach { k -> add(JsonObject().apply { addProperty("id", k.instance.id); addProperty("world", k.instance.world); addProperty("reason", k.reason) }) }
            })
            addProperty("blocks", result.blocks)
        }.toString())
        val out = StringBuilder()
        if (result.synced.isEmpty()) out.append("Synced no copy of ${source.id}.")
        else out.append("Synced ${result.synced.size} copies of ${source.id} (${result.blocks} blocks changed, one undo step):\n")
            .append(result.synced.joinToString("\n") { s ->
                "  ${s.instance.id} in ${s.instance.world}" + (if (s.children.isNotEmpty()) ", ${s.children.size} nested copies made again" else "")
            })
        if (plan.covered.isNotEmpty()) out.append("\nMade again with the copy they are nested in:\n")
            .append(plan.covered.entries.joinToString("\n") { (i, with) -> "  ${i.id} in ${i.world}, with ${with.id}" })
        if (plan.skipped.isNotEmpty()) out.append("\nSkipped ${plan.skipped.size}:\n")
            .append(plan.skipped.joinToString("\n") { "  ${it.instance.id} in ${it.instance.world}: ${it.reason}" })
        return ToolResult.text(out.toString())
    }

    // ---- Opening the source -------------------------------------------------------------------------------------------

    private fun openSource(plugin: Foreman, args: Args): ToolResult {
        val caller = args.caller?.takeUnless { it.isConsole }
            ?: throw ToolError("open_asset_source moves the calling player: call it from a player's session (their token), not the console")
        val player = Sync.main { Bukkit.getPlayer(caller.id) } ?: throw ToolError("${caller.name} is not online")
        val (worldName, areaName) = when {
            args.has("instance") -> {
                val i = instance(plugin, args.string("instance"), args.string("world", null), player.world)
                if (Clipboards.pasted(i)) throw ToolError("${i.id} was pasted from a clipboard: it has no source to open")
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
