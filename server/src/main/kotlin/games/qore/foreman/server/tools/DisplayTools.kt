package games.qore.foreman.server.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.Msg
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Change
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.DisplayEdits
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Locks
import games.qore.foreman.server.world.Models
import games.qore.foreman.server.world.Resources
import games.qore.foreman.server.world.Sync
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.minimessage.MiniMessage
import org.bukkit.Bukkit
import org.bukkit.FluidCollisionMode
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.Player
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.UUID

/**
 * Display entities one by one (move, turn, scale furniture and decor; spawn and remove them), and the item models of the
 * server's resources: the tools and `/foreman model`.
 */
object DisplayTools {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private const val MAX_AREA = 256L * 256

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.LIST_DISPLAYS,
            "Lists the display entities (item, block and text displays: furniture, decor, signs) whose position is in a " +
                "box: uuid, kind, model / block / text, position, yaw, pitch, billboard, view_range, transformation (translation, " +
                "left_rotation [x, y, z, w], scale, right_rotation, in the display's own axes) and the furniture whose " +
                "root display it is. Read them before edit_displays or remove_displays.",
            schema {
                vec("from", "A corner of the box")
                vec("to", "The opposite corner")
                string("world", "World name", required = false)
                format("an array of {uuid, type: item|block|text, world, position, yaw, pitch, billboard, view_range, transformation: " +
                    "{translation, left_rotation, scale, right_rotation}, model?, block?, text?, furniture?: {id, handle}, persistent?}")
            },
        ) { args -> list(plugin, args) },

        Tool(
            ForemanTool.EDIT_DISPLAYS,
            "Moves, turns, scales or stretches display entities, one undo step for all: for each, by uuid (list_displays), " +
                "the absolute values to set (position, yaw, pitch, any part of the transformation, billboard, view_range); what is " +
                "left out stays. A furniture's root display stays on its root block with its yaw and pitch: a move by less " +
                "than half a block and turns go into its transformation, a move by whole blocks places the furniture again " +
                "there (its collision cells follow; refused when they are taken), and it gets a new uuid (`replaced`).",
            schema {
                array("entities", entitySchema(), "Displays to change: [{uuid, position?, yaw?, pitch?, transformation?, billboard?, view_range?}]")
                string("world", "Only displays in this world", required = false)
                format("{edited: [uuid], failed: [{uuid, error}], replaced: {old uuid: new uuid}, displays: [each edited display as it ended, as list_displays gives it]}")
            },
            modifies = true,
        ) { args -> edit(plugin, args) },

        Tool(
            ForemanTool.SPAWN_DISPLAY,
            "Spawns a display entity, saved with the world (undo removes it): an item model of the resources (list_models), " +
                "a block or a text. A whole-number position is a block: the display stands at the centre of its floor " +
                "(else exactly at the position). An item model shows as the furniture using it would, else stands on " +
                "the floor with its front toward a player looking at `yaw`; a block fills the block and turns about its " +
                "bottom centre; a text faces every viewer. Use it for decor that needs no furniture behaviour. `copy_of` " +
                "copies a display instead (its item with every component, its look), in its place or at `position`.",
            schema {
                string("model", "Item model id (namespace:path, list_models; a path alone: a pack's model first)", required = false)
                string("block", "Or a block state, e.g. oak_stairs[facing=east]", required = false)
                string("text", "Or a text (MiniMessage tags allowed)", required = false)
                string("copy_of", "Or a display's uuid (list_displays) to copy; not a furniture's", required = false)
                prop("position", numbers(3), "[x, y, z]: whole numbers for a block, decimals for an exact point; a copy: exactly there (default: in place)", required = false)
                number("yaw", "Its yaw (default 0): an item model's front faces a player looking this way", required = false)
                number("pitch", "Its pitch (default 0)", required = false)
                number("scale", "Multiplies its default size (default 1)", required = false)
                prop("transformation", transformationSchema(), "Parts of the transformation to set over the default", required = false)
                enum("billboard", BILLBOARDS, "How it turns toward viewers (default fixed, center for a text)", required = false)
                number("view_range", VIEW_RANGE + " (default 1)", required = false)
                string("world", "World name", required = false)
                format("{uuid, world}")
            },
            modifies = true,
        ) { args -> spawn(plugin, args) },

        Tool(
            ForemanTool.REMOVE_DISPLAYS,
            "Removes display entities by uuid (list_displays); a furniture's root display removes the furniture through " +
                "its plugin. Undo puts them back as they were.",
            schema {
                strings("uuids", "The displays' uuids")
                string("world", "Only displays in this world", required = false)
                format("{removed: [uuid], failed: [{uuid, error}]}")
            },
            modifies = true,
        ) { args -> remove(plugin, args) },

        Tool(
            ForemanTool.LIST_MODELS,
            "Lists the item model ids of the server's resources (resource packs and the vanilla game: what an item's " +
                "item_model names, spawn_display's model), with the furniture showing each. Pack models first.",
            schema {
                string("filter", "Only ids containing this text", required = false)
                string("namespace", "Only this namespace (minecraft for the vanilla ones)", required = false)
                format("an array of {id, furniture: [furniture ids], vanilla: true when no resource pack defines it}")
            },
        ) { args -> models(args) },
    )

    // ---- Schemas ------------------------------------------------------------------------------------------------------

    private val BILLBOARDS = Display.Billboard.entries.map { it.name.lowercase() }
    private const val VIEW_RANGE = "How far it is seen: 1 is 64 blocks (within the server's entity-tracking-range), 0 hides it"

    private fun SchemaBuilder.format(json: String) =
        enum("format", listOf("text", "json"), "text (default), or json for programs: $json", required = false)

    private fun numbers(n: Int) = SchemaBuilder.type("array").apply {
        add("items", SchemaBuilder.type("number"))
        addProperty("minItems", n)
        addProperty("maxItems", n)
    }

    private fun described(schema: JsonObject, description: String) = schema.apply { addProperty("description", description) }

    private fun transformationSchema() = SchemaBuilder.type("object").apply {
        add("properties", JsonObject().apply {
            add("translation", described(numbers(3), "[x, y, z] in blocks"))
            add("left_rotation", described(numbers(4), "Quaternion [x, y, z, w], applied after the scale"))
            add("scale", described(numbers(3), "[x, y, z]: stretch along each axis"))
            add("right_rotation", described(numbers(4), "Quaternion [x, y, z, w], applied before the scale"))
        })
    }

    private fun entitySchema() = SchemaBuilder.type("object").apply {
        add("properties", JsonObject().apply {
            add("uuid", described(SchemaBuilder.type("string"), "The display's uuid"))
            add("position", described(numbers(3), "Where it is, [x, y, z] (decimals)"))
            add("yaw", described(SchemaBuilder.type("number"), "Its yaw"))
            add("pitch", described(SchemaBuilder.type("number"), "Its pitch (-90..90)"))
            add("transformation", described(transformationSchema(), "Parts of its transformation to set"))
            add("billboard", described(SchemaBuilder.type("string").apply { add("enum", JsonArray().apply { BILLBOARDS.forEach { add(it) } }) }, "How it turns toward viewers"))
            add("view_range", described(SchemaBuilder.type("number"), VIEW_RANGE))
        })
        add("required", JsonArray().apply { add("uuid") })
    }

    // ---- Arguments ----------------------------------------------------------------------------------------------------

    private fun json(args: Args) = when (args.string("format", "text")) {
        "text" -> false
        "json" -> true
        else -> throw ToolError("format is text or json")
    }

    private fun numbers(args: Args, name: String, n: Int): DoubleArray {
        val a = runCatching { args.array(name) }.getOrElse { throw ToolError("$name is an array of $n numbers") }
        if (a.size() != n) throw ToolError("$name is an array of $n numbers, got $a")
        return DoubleArray(n) { i ->
            runCatching { a[i].asDouble }.getOrNull()?.takeIf { it.isFinite() } ?: throw ToolError("$name is an array of $n numbers, got $a")
        }
    }

    private fun vector(args: Args, name: String) = numbers(args, name, 3).let { Vector3f(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }

    private fun quaternion(args: Args, name: String): Quaternionf {
        val q = numbers(args, name, 4).let { Quaternionf(it[0].toFloat(), it[1].toFloat(), it[2].toFloat(), it[3].toFloat()) }
        if (q.lengthSquared() < 1e-6f) throw ToolError("$name is a rotation quaternion [x, y, z, w], not zero")
        return q.normalize()
    }

    private fun angle(args: Args, name: String, range: Double = Double.MAX_VALUE): Float? {
        if (!args.has(name)) return null
        val v = args.double(name, 0.0)
        if (!v.isFinite() || v < -range || v > range) throw ToolError("$name is a number" + if (range < Double.MAX_VALUE) " from -$range to $range" else "")
        return v.toFloat()
    }

    private fun billboard(args: Args): Display.Billboard? = args.string("billboard", null)?.let { b ->
        Display.Billboard.entries.firstOrNull { it.name.equals(b, ignoreCase = true) } ?: throw ToolError("billboard is ${BILLBOARDS.joinToString(", ")}")
    }

    private fun viewRange(args: Args): Float? {
        if (!args.has("view_range")) return null
        return args.double("view_range", 1.0).takeIf { it.isFinite() && it >= 0 }?.toFloat() ?: throw ToolError("view_range is a number from 0")
    }

    /** An edit's values; [placed] reads the position, yaw and pitch too. */
    private fun request(args: Args, placed: Boolean): DisplayEdits.Request {
        val t = if (args.has("transformation")) Args(runCatching { args.obj("transformation") }.getOrElse { throw ToolError("transformation is an object") }) else null
        val position = if (placed && args.has("position")) numbers(args, "position", 3).let { Vector3d(it[0], it[1], it[2]) } else null
        return DisplayEdits.Request(
            position, if (placed) angle(args, "yaw") else null, if (placed) angle(args, "pitch", 90.0) else null,
            t?.takeIf { it.has("translation") }?.let { vector(it, "translation") },
            t?.takeIf { it.has("left_rotation") }?.let { quaternion(it, "left_rotation") },
            t?.takeIf { it.has("scale") }?.let { vector(it, "scale") },
            t?.takeIf { it.has("right_rotation") }?.let { quaternion(it, "right_rotation") },
            billboard(args), viewRange(args),
        )
    }

    private fun plural(n: Int, what: String) = "$n $what" + if (n == 1) "" else "s"

    private fun uuid(raw: String): UUID = runCatching { UUID.fromString(raw) }.getOrElse { throw ToolError("not a uuid") }

    /** A model id as given: `namespace:path`, or a path alone: a resource pack's model of that path first, else vanilla's. */
    private fun modelId(id: String): String {
        if (':' in id) return id
        return Models.itemIds().entries.filter { it.key.substringAfter(':') == id }.sortedBy { !it.value }.firstOrNull()?.key ?: "minecraft:$id"
    }

    private fun error(e: Exception) = (e as? ToolError)?.message ?: e.message ?: e.javaClass.simpleName

    private fun failures(failed: List<Pair<String, String>>) = JsonArray().apply {
        failed.forEach { (id, error) -> add(JsonObject().apply { addProperty("uuid", id); addProperty("error", error) }) }
    }

    // ---- Tools --------------------------------------------------------------------------------------------------------

    private fun list(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        if (box.sizeX.toLong() * box.sizeZ > MAX_AREA) throw ToolError("list_displays covers 256x256 blocks at most: split the box")
        val json = json(args)
        Furniture.ensureLoaded(world, box)
        Chunks.loadEntities(world, listOf(box))
        return Sync.main {
            val found = DisplayEdits.inBox(world, box)
            ToolResult.text(
                if (json) gson.toJson(JsonArray().apply { found.forEach { add(DisplayEdits.json(it)) } })
                else if (found.isEmpty()) "No display entities in ${box.describe()}"
                else "${found.size} display entities in ${box.describe()}:\n" + found.take(300).joinToString("\n") { DisplayEdits.describe(it) } +
                    if (found.size > 300) "\n… ${found.size - 300} more: use a smaller box" else ""
            )
        }
    }

    private fun edit(plugin: Foreman, args: Args): ToolResult {
        val entries = args.array("entities").map { e -> Args(runCatching { e.asJsonObject }.getOrElse { throw ToolError("entities holds objects, got $e") }) }
        if (entries.isEmpty()) throw ToolError("Give entities")
        val json = json(args)
        val worldName = args.string("world", null)
        val edited = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        val replaced = LinkedHashMap<UUID, UUID>()
        val poses = JsonArray()
        Sync.main {
            val world = worldName?.let { plugin.world(it) }
            var change: Change? = null
            for (e in entries) {
                val raw = e.string("uuid", null)
                if (raw == null) {
                    failed += "" to "missing uuid"
                    continue
                }
                try {
                    val request = request(e, true)
                    val found = DisplayEdits.find(world, uuid(raw)) ?: throw ToolError("no loaded display has this uuid (list_displays loads them)")
                    if (world != null && found.display.world != world) throw ToolError("it is in world ${found.display.world.name}")
                Locks.guard(found.display.location)
                    val c = change ?: Change("edit displays", found.display.world).also { change = it }
                    DisplayEdits.edit(found, request, c, replaced)
                    edited += raw
                } catch (x: Exception) {
                    failed += raw to error(x)
                }
            }
            change?.takeIf { it.restores.isNotEmpty() }?.let {
                it.label = "edit ${plural(edited.size, "display")}"
                it.blocks = edited.size
                Edits.record(it)
            }
            // What each edited display ended as: a furniture keeps its root and takes the move in its transformation.
            for (raw in edited) DisplayEdits.find(change?.world ?: world, replaced[uuid(raw)] ?: uuid(raw))?.let { poses.add(DisplayEdits.json(it)) }
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("edited", JsonArray().apply { edited.forEach { add(it) } })
            add("failed", failures(failed))
            add("replaced", JsonObject().apply { replaced.forEach { (old, now) -> addProperty(old.toString(), now.toString()) } })
            add("displays", poses)
        }))
        return ToolResult.text(
            (if (edited.isEmpty()) "Nothing edited." else "Edited ${plural(edited.size, "display")}: undo puts them back.") +
                replaced.entries.joinToString("") { (old, now) -> "\nFurniture $old moved to another root block: it is $now now." } +
                failed.joinToString("") { (id, error) -> "\nNot edited ${id.ifEmpty { "(no uuid)" }}: $error" }
        )
    }

    private fun spawn(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val model = args.string("model", null)?.let(::modelId)
        val block = args.string("block", null)
        val text = args.string("text", null)
        val copyOf = args.string("copy_of", null)
        if (listOfNotNull(model, block, text, copyOf).size != 1) throw ToolError("Give one of model, block, text or copy_of")
        if (copyOf != null) return copy(plugin, args, uuid(copyOf))
        val position = numbers(args, "position", 3).let { Vector3d(it[0], it[1], it[2]) }
        val yaw = angle(args, "yaw") ?: 0f
        val pitch = angle(args, "pitch", 90.0) ?: 0f
        val scale = args.double("scale", 1.0).takeIf { it.isFinite() && it > 0 }?.toFloat() ?: throw ToolError("scale is a number above 0")
        val request = request(args, false)
        val json = json(args)
        val known = model == null || model in Models.itemIds()
        val lift = model?.let { DisplayEdits.lift(it) }
        val display = Sync.main {
            val shows = when {
                model != null -> DisplayEdits.Shows(model = model)
                block != null -> DisplayEdits.Shows(block = runCatching { Bukkit.createBlockData(block) }.getOrElse { throw ToolError("Invalid block '$block'") })
                else -> DisplayEdits.Shows(text = MiniMessage.miniMessage().deserialize(text!!))
            }
            Locks.guard(Location(world, position.x, position.y, position.z))
            val change = Change("spawn ${model ?: block ?: "text"} display", world)
            DisplayEdits.spawn(world, shows, position, yaw, pitch, scale, lift, request, change).also {
                change.blocks = 1
                Edits.record(change)
            }
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            addProperty("uuid", display.uniqueId.toString())
            addProperty("world", world.name)
        }))
        val l = display.location
        return ToolResult.text(
            "Spawned ${DisplayEdits.kind(display)} display ${display.uniqueId} at [%.2f, %.2f, %.2f] yaw %.1f: undo removes it.".format(java.util.Locale.ROOT, l.x, l.y, l.z, l.yaw) +
                if (!known) " $model is not in the server's resources (${Resources.status()}): players without it see a missing model." else ""
        )
    }

    /** spawn_display's `copy_of`: the copy of a loaded display, in its place or at `position`, with the request over it. */
    private fun copy(plugin: Foreman, args: Args, id: UUID): ToolResult {
        val world = args.string("world", null)?.let { plugin.world(it) }
        val position = if (args.has("position")) numbers(args, "position", 3) else null
        val yaw = angle(args, "yaw")
        val pitch = angle(args, "pitch", 90.0)
        val request = request(args, false)
        val json = json(args)
        val display = Sync.main {
            val found = DisplayEdits.find(world, id) ?: throw ToolError("No loaded display has the uuid $id (list_displays loads them)")
            if (world != null && found.display.world != world) throw ToolError("$id is in world ${found.display.world.name}")
            val from = found.display.location
            val at = Location(from.world, position?.get(0) ?: from.x, position?.get(1) ?: from.y, position?.get(2) ?: from.z, yaw ?: from.yaw, pitch ?: from.pitch)
            Locks.guard(at)
            val change = Change("copy ${DisplayEdits.kind(found.display)} display", from.world)
            DisplayEdits.copy(found, at, request, change).also {
                change.blocks = 1
                Edits.record(change)
            }
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            addProperty("uuid", display.uniqueId.toString())
            addProperty("world", display.world.name)
        }))
        val l = display.location
        return ToolResult.text("Copied $id as ${DisplayEdits.kind(display)} display ${display.uniqueId} at [%.2f, %.2f, %.2f]: undo removes it.".format(java.util.Locale.ROOT, l.x, l.y, l.z))
    }

    private fun remove(plugin: Foreman, args: Args): ToolResult {
        val ids = args.strings("uuids")
        if (ids.isEmpty()) throw ToolError("Give uuids")
        val json = json(args)
        val worldName = args.string("world", null)
        val removed = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        Sync.main {
            val world = worldName?.let { plugin.world(it) }
            var change: Change? = null
            for (raw in ids) try {
                val found = DisplayEdits.find(world, uuid(raw)) ?: throw ToolError("no loaded display has this uuid (list_displays loads them)")
                if (world != null && found.display.world != world) throw ToolError("it is in world ${found.display.world.name}")
                Locks.guard(found.display.location)
                val c = change ?: Change("remove displays", found.display.world).also { change = it }
                DisplayEdits.remove(found, c)
                removed += raw
            } catch (x: Exception) {
                failed += raw to error(x)
            }
            change?.takeIf { it.restores.isNotEmpty() }?.let {
                it.label = "remove ${plural(removed.size, "display")}"
                it.blocks = removed.size
                Edits.record(it)
            }
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("removed", JsonArray().apply { removed.forEach { add(it) } })
            add("failed", failures(failed))
        }))
        return ToolResult.text(
            (if (removed.isEmpty()) "Nothing removed." else "Removed ${plural(removed.size, "display")}: undo puts them back.") +
                failed.joinToString("") { (id, error) -> "\nNot removed $id: $error" }
        )
    }

    /** Item model ids matching [filter] and [namespace], resource packs' first, with the furniture showing each. */
    private class Listed(val id: String, val pack: Boolean, val furniture: List<String>)

    private fun listed(filter: String?, namespace: String?): List<Listed> {
        val ids = Models.itemIds()
        val furniture = Sync.main {
            Furniture.provider?.types()?.mapNotNull { t -> runCatching { t.model() }.getOrNull()?.let { it to t.id() } }.orEmpty()
        }.groupBy({ it.first }, { it.second })
        val f = filter?.lowercase()
        return ids.entries.filter { (id, _) -> (namespace == null || id.substringBefore(':') == namespace) && (f == null || id.contains(f)) }
            .sortedBy { !it.value }
            .map { (id, pack) -> Listed(id, pack, furniture[id].orEmpty().sorted()) }
    }

    private fun models(args: Args): ToolResult {
        val filter = args.string("filter", null)
        val namespace = args.string("namespace", null)
        val json = json(args)
        val all = listed(filter, namespace)
        if (json) return ToolResult.text(gson.toJson(JsonArray().apply {
            all.forEach { m ->
                add(JsonObject().apply {
                    addProperty("id", m.id)
                    add("furniture", JsonArray().apply { m.furniture.forEach { add(it) } })
                    addProperty("vanilla", !m.pack)
                })
            }
        }))
        // Unfiltered, the vanilla ones would bury the packs': only counted.
        val shown = if (filter == null && namespace == null) all.filter { it.pack } else all
        val vanilla = all.size - all.count { it.pack }
        val lines = shown.map { m -> m.id + (if (m.furniture.isNotEmpty()) " — furniture ${m.furniture.joinToString(", ")}" else "") + if (!m.pack) " (vanilla)" else "" }
        return ToolResult.text(
            "${all.size} item models (${Resources.status()})" + (if (shown.size < all.size) ", $vanilla vanilla ones not listed (namespace minecraft, or a filter)" else "") +
                ":\n" + lines.take(400).joinToString("\n") + if (lines.size > 400) "\n… ${lines.size - 400} more: use a filter or a namespace" else ""
        )
    }

    // ---- /foreman model -----------------------------------------------------------------------------------------------

    private const val PAGE = 12

    /** `/foreman model spawn <model> [scale]` and `/foreman model list [filter] [page]`. False for a usage error. */
    fun command(plugin: Foreman, sender: CommandSender, args: List<String>): Boolean {
        when (args.getOrNull(0)) {
            "spawn" -> {
                val player = sender as? Player ?: return false
                val given = args.getOrNull(1) ?: return false
                val scale = args.getOrNull(2)?.let { s -> s.toFloatOrNull()?.takeIf { it.isFinite() && it > 0 } ?: throw ToolError("The scale is a number above 0") } ?: 1f
                async(plugin, sender) {
                    val model = modelId(given)
                    val known = model in Models.itemIds()
                    val lift = DisplayEdits.lift(model)
                    Sync.main { spawnModel(player, model, scale, lift, known) }
                }
            }

            "list" -> {
                val filter = args.getOrNull(1)?.takeIf { it.toIntOrNull() == null }
                val page = (args.getOrNull(if (filter == null) 1 else 2)?.toIntOrNull() ?: 1).coerceAtLeast(1)
                async(plugin, sender) {
                    val all = listed(filter, null)
                    Sync.main { page(sender, all, filter, page) }
                }
            }

            else -> return false
        }
        return true
    }

    /** Spawns [model] on the block face [player] looks at (else at their feet), its front toward them. Main thread. */
    private fun spawnModel(player: Player, model: String, scale: Float, lift: Double?, known: Boolean) {
        val hit = player.rayTraceBlocks(16.0, FluidCollisionMode.NEVER)
        val block = hit?.hitBlock?.let { b -> hit.hitBlockFace?.let { b.getRelative(it) } ?: b } ?: player.location.block
        Locks.locks(block.location, Caller.of(player))?.let { return Msg.error(player, Component.text(Locks.refusal(it))) }
        val change = Change("spawn $model", player.world, Caller.of(player))
        val display = DisplayEdits.spawn(
            player.world, DisplayEdits.Shows(model = model), Vector3d(block.x.toDouble(), block.y.toDouble(), block.z.toDouble()),
            player.location.yaw, 0f, scale, lift, null, change,
        )
        change.blocks = 1
        Edits.record(change)
        Msg.success(player, Component.text("Spawned $model at [${block.x}, ${block.y}, ${block.z}] (/foreman undo removes it)")
            .hoverEvent(HoverEvent.showText(Component.text(display.uniqueId.toString()))))
        if (!known) Msg.warning(player, Component.text("$model is not in the server's resources (${Resources.status()}): it may show as a missing model"))
    }

    private fun page(sender: CommandSender, all: List<Listed>, filter: String?, page: Int) {
        if (all.isEmpty()) return Msg.warning(sender, Component.text("No item model" + (filter?.let { " matching '$it'" } ?: "") + " (${Resources.status()})"))
        val pages = (all.size + PAGE - 1) / PAGE
        val p = page.coerceAtMost(pages)
        Msg.info(sender, Component.text("${all.size} item models" + (filter?.let { " matching '$it'" } ?: "") + ", page $p/$pages (click one to spawn it):"))
        all.drop((p - 1) * PAGE).take(PAGE).forEach { m ->
            sender.sendMessage(
                Component.text("  ${m.id}", if (m.pack) NamedTextColor.YELLOW else NamedTextColor.GRAY)
                    .append(Component.text(if (m.furniture.isNotEmpty()) " furniture ${m.furniture.joinToString(", ")}" else "", NamedTextColor.DARK_GRAY))
                    .clickEvent(ClickEvent.suggestCommand("/foreman model spawn ${m.id}"))
                    .hoverEvent(HoverEvent.showText(Component.text("/foreman model spawn ${m.id} [scale]")))
            )
        }
        if (p < pages) Msg.info(sender, Component.text("/foreman model list ${filter?.let { "$it " } ?: ""}${p + 1}")
            .clickEvent(ClickEvent.runCommand("/foreman model list ${filter?.let { "$it " } ?: ""}${p + 1}")))
    }

    private fun async(plugin: Foreman, sender: CommandSender, block: () -> Unit) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, Runnable {
            try {
                block()
            } catch (e: Exception) {
                Bukkit.getScheduler().runTask(plugin, Runnable { Msg.error(sender, Component.text(error(e))) })
            }
        })
    }

    /** Model ids starting with [prefix] (or whose path does), for tab completion: none until the resources were read once. */
    fun complete(prefix: String): List<String> {
        val ids = Models.itemIdsIfRead() ?: return emptyList()
        val p = prefix.lowercase()
        return ids.entries.asSequence().filter { (id, _) -> id.startsWith(p) || id.substringAfter(':').startsWith(p) }
            .sortedBy { !it.value }.map { it.key }.take(200).toList()
    }
}
