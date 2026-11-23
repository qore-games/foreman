package games.qore.foreman.server.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Change
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.EntityEdits
import games.qore.foreman.server.world.EntityNbt
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Locks
import games.qore.foreman.server.world.Sync
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.World
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.util.Vector
import java.util.Locale
import java.util.UUID

/**
 * Any entity in bulk: its scoreboard tags, persistent data, whole NBT, custom name and persistence, a display's view
 * range, a text display's text, a living entity's AI, health and attributes; removed and copied.
 */
object EntityTools {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private const val MAX_AREA = 256L * 256
    private const val MAX_EDITED = 10_000
    /** Entities listed with their NBT, or given an NBT change, or removed or copied, in one call at most. */
    private const val MAX_NBT = 1000

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.LIST_ENTITIES,
            "Lists the entities whose position is in a box (any kind: displays, armor stands, item frames, interactions, " +
                "mobs…; players only when types names them), filtered by type and by what they show, or those given by " +
                "uuid: uuid, type, position, custom name, scoreboard tags, persistent data (PDC: each key with its type and " +
                "value), the item model or block they show, for displays their view_range and the furniture whose root " +
                "display they are, a text display's text, a living entity's health and max health, and with " +
                "nbt their whole NBT as SNBT (what /data get entity shows). Read them before edit_entities.",
            schema {
                vec("from", "A corner of the box", required = false)
                vec("to", "The opposite corner", required = false)
                strings("uuids", "Or the entities to list, by uuid (loaded ones)", required = false)
                string("world", "World name", required = false)
                filters()
                bool("nbt", "Each one's whole NBT too, as SNBT (at most $MAX_NBT entities)", required = false)
                format("an array of {uuid, type, world, position, model?, block?, view_range? (displays), tags: [tag], " +
                    "data: {\"namespace:key\": {type, value} (a container's value: its own keys; a list: type list, of: its elements' type)}, " +
                    "furniture?: {id, handle}, persistent? (false when not saved), custom_name? and text? (MiniMessage), " +
                    "health? and max_health? (living entities; attribute base values are in their nbt), nbt? (SNBT)}")
            },
        ) { args -> list(plugin, args) },

        Tool(
            ForemanTool.EDIT_ENTITIES,
            "Changes entities in bulk, one undo step for all: those listed by uuid, or every one in a box that the filters " +
                "take (as list_entities; hundreds at once). Sets a display's view_range (how far it is seen: 1 = 64 blocks, " +
                "within the server's entity-tracking-range; other entities' range is the server's, spigot.yml, so they " +
                "fail), adds and removes scoreboard tags, sets and removes persistent data keys (PDC, typed), sets the " +
                "custom name and a text display's text (MiniMessage), whether it is saved with the world, a living " +
                "entity's health and attribute base values (scale, max_health…), and changes their NBT as /data does: " +
                "remove_nbt paths removed, then merge_nbt merged in (anything else: a display's brightness, glow, shadow, " +
                "item or block, an armor stand's flags, a mob's NoAI). NBT can't change an entity's uuid or type, move it " +
                "to another world, nor change a player. What is left out stays; undo puts it all back (the whole NBT " +
                "after an NBT change). Returns what changed on each, those already so, and what failed.",
            schema {
                strings("uuids", "The entities to change (list_entities); or give from and to", required = false)
                vec("from", "Or a corner of a box: every entity in it the filters take", required = false)
                vec("to", "The opposite corner", required = false)
                string("world", "World name", required = false)
                filters()
                number("view_range", "Displays only: their view range (1 is the default, 64 blocks; 0 hides them)", required = false)
                strings("add_tags", "Scoreboard tags to add", required = false)
                strings("remove_tags", "Scoreboard tags to remove", required = false)
                array("set_data", dataSchema(), "Persistent data to set: [{key: \"namespace:key\", type: ${EntityEdits.SETTABLE.joinToString("|")}, value}] (boolean is stored as a byte 0 or 1)", required = false)
                strings("remove_data", "Persistent data keys to remove (namespace:key)", required = false)
                string("merge_nbt", "SNBT compound merged into each one's NBT, as /data merge entity: e.g. {Glowing:1b,brightness:{block:15,sky:15}}", required = false)
                strings("remove_nbt", "NBT paths removed from each one first, as /data remove entity: e.g. brightness, item.components.\"minecraft:item_model\"", required = false)
                string("custom_name", "Its custom name, MiniMessage (<red>Guard); empty takes it away", required = false)
                bool("ai", "Living entities: whether their AI runs (false: they stand still, as NoAI)", required = false)
                string("text", "Text displays only: their text, MiniMessage", required = false)
                bool("persistent", "Whether it is saved with the world (false: gone when its chunk unloads)", required = false)
                number("health", "Living entities: their health, at most their max_health", required = false)
                prop("set_attributes", SchemaBuilder.type("object").apply {
                    add("additionalProperties", SchemaBuilder.type("number"))
                }, "Living entities: attribute base values, {\"minecraft:scale\": 2, \"max_health\": 40}", required = false)
                format("{edited: [{uuid, view_range?: [before, after], tags_added?, tags_removed?, data_set?, data_removed?, nbt?: [top-level keys changed], changed?: [custom_name|text|persistent|ai|health|attribute key]}], unchanged: [uuid], failed: [{uuid, error}]}")
            },
            modifies = true,
        ) { args -> edit(plugin, args) },

        Tool(
            ForemanTool.REMOVE_ENTITIES,
            "Removes entities, one undo step for all: those listed by uuid, or every one in a box that the filters take " +
                "(as list_entities). Any entity but a player: a furniture's root display through its plugin, the others " +
                "with all their data, which undo brings back (with their uuid where it is still free). What rides one " +
                "stays. Returns those removed, and those it couldn't remove with why.",
            schema {
                strings("uuids", "The entities to remove (list_entities); or give from and to", required = false)
                vec("from", "Or a corner of a box: every entity in it the filters take", required = false)
                vec("to", "The opposite corner", required = false)
                string("world", "World name", required = false)
                filters()
                format("{removed: [uuid], failed: [{uuid, error}]}")
            },
            modifies = true,
        ) { args -> remove(plugin, args) },

        Tool(
            ForemanTool.COPY_ENTITIES,
            "Copies entities by uuid with all their data (NBT: item, name, tags, persistent data…) under new uuids, in " +
                "place or moved by an offset, one undo step for all (undo removes the copies). Not players, nor " +
                "furniture (place_furniture places another one). Returns each copy's uuid.",
            schema {
                strings("uuids", "The entities to copy (list_entities), each once")
                prop("offset", SchemaBuilder.type("array").apply {
                    add("items", SchemaBuilder.type("number"))
                    addProperty("minItems", 3)
                    addProperty("maxItems", 3)
                }, "[dx, dy, dz] in blocks from each original (default in place)", required = false)
                string("world", "World name", required = false)
                format("{copies: {uuid: copy's uuid}, failed: [{uuid, error}]}")
            },
            modifies = true,
        ) { args -> copy(plugin, args) },
    )

    // ---- Schemas ------------------------------------------------------------------------------------------------------

    private fun SchemaBuilder.format(json: String) =
        enum("format", listOf("text", "json"), "text (default), or json for programs: $json", required = false)

    private fun SchemaBuilder.filters() {
        strings("types", "Only these entity types (item_display, block_display, text_display, armor_stand…)", required = false)
        string("model", "Only those showing this item model (item displays, item frames, dropped items): namespace:path, or a path alone", required = false)
        string("item", "Only those showing this item type (item displays, item frames, dropped items)", required = false)
        string("block", "Only block displays of this block type (any state)", required = false)
        bool("furniture", "Only furniture root displays (true), or none of them (false)", required = false)
    }

    private fun dataSchema() = SchemaBuilder.type("object").apply {
        add("properties", JsonObject().apply {
            add("key", SchemaBuilder.type("string").apply { addProperty("description", "namespace:key") })
            add("type", SchemaBuilder.type("string").apply { add("enum", JsonArray().apply { EntityEdits.SETTABLE.forEach { add(it) } }) })
            add("value", JsonObject().apply { addProperty("description", "A number, a boolean or a string, as the type says") })
        })
        add("required", JsonArray().apply { add("key"); add("type"); add("value") })
    }

    // ---- Arguments ----------------------------------------------------------------------------------------------------

    private fun json(args: Args) = when (args.string("format", "text")) {
        "text" -> false
        "json" -> true
        else -> throw ToolError("format is text or json")
    }

    private fun filter(args: Args) = EntityEdits.Filter(
        args.strings("types").takeIf { it.isNotEmpty() }?.map(EntityEdits::type)?.toSet(),
        args.string("model", null),
        args.string("item", null)?.let { EntityEdits.material(it, block = false) },
        args.string("block", null)?.let { EntityEdits.material(it, block = true) },
        if (args.has("furniture")) args.bool("furniture", false) else null,
    )

    private fun box(args: Args, tool: String): Box {
        val box = Box.of(args.vec("from"), args.vec("to"))
        if (box.sizeX.toLong() * box.sizeZ > MAX_AREA) throw ToolError("$tool covers 256x256 blocks at most: split the box")
        return box
    }

    /** Loads the box's chunks with their entities, and lets the furniture plugin take them in. Off the main thread. */
    private fun load(world: World, box: Box) {
        Furniture.ensureLoaded(world, box)
        Chunks.loadEntities(world, listOf(box))
    }

    private fun request(args: Args): EntityEdits.Request {
        val range = if (args.has("view_range")) args.double("view_range", 1.0).takeIf { it.isFinite() && it >= 0 }?.toFloat()
            ?: throw ToolError("view_range is a number from 0") else null
        val add = args.strings("add_tags").distinct()
        val remove = args.strings("remove_tags").distinct()
        (add + remove).firstOrNull { it.isBlank() || it.any(Char::isWhitespace) }?.let { throw ToolError("Tags have no spaces: '$it'") }
        add.firstOrNull { it in remove }?.let { throw ToolError("'$it' is both added and removed") }
        val set = if (args.has("set_data")) args.array("set_data").map { e ->
            val o = runCatching { e.asJsonObject }.getOrElse { throw ToolError("set_data holds {key, type, value} objects, got $e") }
            val key = o["key"]?.takeIf { it.isJsonPrimitive }?.asString ?: throw ToolError("set_data: each has a key, got $o")
            val type = o["type"]?.takeIf { it.isJsonPrimitive }?.asString ?: throw ToolError("set_data: $key has no type (${EntityEdits.SETTABLE.joinToString(", ")})")
            EntityEdits.value(key, type, o["value"])
        } else emptyList()
        set.groupBy { it.key }.entries.firstOrNull { it.value.size > 1 }?.let { throw ToolError("set_data sets ${it.key} twice") }
        val drop = args.strings("remove_data").map(EntityEdits::key).distinct()
        drop.firstOrNull { k -> set.any { it.key == k } }?.let { throw ToolError("$it is both set and removed") }
        val merge = args.string("merge_nbt", null)?.let { snbt ->
            runCatching { EntityNbt.parse(snbt) }.getOrElse { throw ToolError("merge_nbt is not an SNBT compound: ${error(it)}") }
        }
        val paths = args.strings("remove_nbt").distinct().map { p ->
            runCatching { EntityNbt.path(p) }.getOrElse { throw ToolError("remove_nbt: ${error(it)}") }
        }
        val health = if (args.has("health")) args.double("health", 0.0).takeIf { it.isFinite() && it > 0 } ?: throw ToolError("health is a number above 0") else null
        return EntityEdits.Request(
            range, add, remove, set, drop, merge, paths, args.string("custom_name", null), args.string("text", null),
            if (args.has("persistent")) args.bool("persistent", true) else null, attributes(args), health,
            if (args.has("ai")) args.bool("ai", true) else null,
        )
    }

    /** `set_attributes`: attribute key (`minecraft:` may be left out) to base value. */
    private fun attributes(args: Args): Map<Attribute, Double> {
        if (!args.has("set_attributes")) return emptyMap()
        val o = runCatching { args.obj("set_attributes") }.getOrElse { throw ToolError("set_attributes is an object: {\"minecraft:scale\": 2}") }
        return o.entrySet().associate { (k, v) ->
            val attribute = NamespacedKey.fromString(k.lowercase(Locale.ROOT))?.let { Registry.ATTRIBUTE.get(it) } ?: throw ToolError("Unknown attribute '$k'")
            val base = runCatching { v.asDouble }.getOrNull()?.takeIf { it.isFinite() } ?: throw ToolError("set_attributes: $k is a number, got $v")
            attribute to base
        }
    }

    private fun entities(n: Int) = if (n == 1) "1 entity" else "$n entities"

    /** Which entities a call takes: its [uuids], or those in its [box] (loaded) that its [filter] takes. */
    private class Scope(val uuids: List<String>, val box: Box?, val world: World?, val filter: EntityEdits.Filter)

    private fun scope(plugin: Foreman, args: Args, tool: String): Scope {
        val uuids = args.strings("uuids")
        val boxed = args.has("from") || args.has("to")
        val filter = filter(args)
        if (uuids.isNotEmpty() == boxed) throw ToolError("Give uuids, or a box (from and to)")
        if (uuids.isNotEmpty() && filter.any) throw ToolError("The filters choose in a box: with uuids, leave them out")
        val worldName = args.string("world", null)
        val box = if (boxed) box(args, tool) else null
        val world = if (box != null) plugin.world(worldName).also { load(it, box) } else worldName?.let { plugin.world(it) }
        return Scope(uuids, box, world, filter)
    }

    /**
     * The entities of [scope], each with its uuid as given and a lookup run when its turn comes (a missing one fails
     * alone). Main thread.
     */
    private fun targets(scope: Scope): List<Pair<String, () -> EntityEdits.Found>> {
        val box = scope.box ?: return scope.uuids.map { raw -> raw to { EntityEdits.found(entity(scope.world, raw)) } }
        return EntityEdits.inBox(scope.world!!, box, scope.filter).map { f -> f.entity.uniqueId.toString() to { f } }
    }

    private fun entity(world: World?, raw: String): Entity {
        val id = runCatching { UUID.fromString(raw) }.getOrElse { throw ToolError("not a uuid") }
        val e = EntityEdits.find(world, id) ?: throw ToolError("no loaded entity has this uuid (list_entities loads them)")
        if (world != null && e.world != world) throw ToolError("it is in world ${e.world.name}")
        return e
    }

    private fun failures(failed: List<Pair<String, String>>) = JsonArray().apply {
        failed.forEach { (id, error) -> add(JsonObject().apply { addProperty("uuid", id); addProperty("error", error) }) }
    }

    /** Failures grouped by reason, one line each. */
    private fun failureLines(failed: List<Pair<String, String>>, what: String) = failed.groupBy({ it.second }, { it.first }).map { (error, ids) ->
        "$what, ${entities(ids.size)}: $error" + if (ids.size <= 10) " (${ids.joinToString(", ") { it.ifEmpty { "no uuid" } }})" else ""
    }

    private fun error(e: Throwable) = (e as? ToolError)?.message ?: e.message ?: e.javaClass.simpleName

    // ---- Tools --------------------------------------------------------------------------------------------------------

    private fun list(plugin: Foreman, args: Args): ToolResult {
        val uuids = args.strings("uuids")
        val boxed = args.has("from") || args.has("to")
        val filter = filter(args)
        if (uuids.isNotEmpty() == boxed) throw ToolError("Give a box (from and to), or uuids")
        if (uuids.isNotEmpty() && filter.any) throw ToolError("The filters choose in a box: with uuids, leave them out")
        val nbt = args.bool("nbt", false)
        val json = json(args)
        val worldName = args.string("world", null)
        val box = if (boxed) box(args, "list_entities") else null
        val boxWorld = box?.let { plugin.world(worldName).also { w -> load(w, it) } }
        return Sync.main {
            val missing = mutableListOf<String>()
            val found = if (box != null) EntityEdits.inBox(boxWorld!!, box, filter) else {
                val world = worldName?.let { plugin.world(it) }
                uuids.distinct().mapNotNull { raw ->
                    val e = runCatching { UUID.fromString(raw) }.getOrNull()?.let { EntityEdits.find(world, it) }
                    if (e == null) missing += raw
                    e?.let(EntityEdits::found)
                }
            }
            if (nbt && found.size > MAX_NBT) throw ToolError("${entities(found.size)}: nbt lists $MAX_NBT at most, use a smaller box, filters or uuids")
            if (json) return@main ToolResult.text(gson.toJson(JsonArray().apply { found.forEach { add(EntityEdits.json(it, nbt)) } }))
            val where = box?.describe()?.let { "in $it" } ?: "of ${uuids.size} uuid(s)"
            val listed = if (found.isEmpty()) "No entity $where" + (if (filter.any) " that the filters take" else "")
            else "${entities(found.size)} $where:\n" + found.take(300).joinToString("\n") { EntityEdits.describe(it, nbt) } +
                if (found.size > 300) "\n… ${found.size - 300} more: use a smaller box, filters, or format json" else ""
            ToolResult.text(listed + if (missing.isNotEmpty()) "\nNot loaded (or not uuids): ${missing.joinToString(", ")}" else "")
        }
    }

    private fun edit(plugin: Foreman, args: Args): ToolResult {
        val scope = scope(plugin, args, "edit_entities")
        val request = request(args)
        if (request.empty) throw ToolError("Nothing to change: give view_range, add_tags, remove_tags, set_data, remove_data, merge_nbt, remove_nbt, custom_name, text, persistent, ai, health or set_attributes")
        val json = json(args)
        val edited = mutableListOf<EntityEdits.Edited>()
        val unchanged = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        Sync.main {
            val targets = targets(scope)
            if (targets.size > MAX_EDITED) throw ToolError("${targets.size} entities: $MAX_EDITED at most in one call, use a smaller box or filters")
            if (request.nbt && targets.size > MAX_NBT) throw ToolError("${targets.size} entities: an NBT change takes $MAX_NBT at most in one call, use a smaller box or filters")
            var change: Change? = null
            for ((raw, found) in targets) try {
                val e = found().entity
                Locks.guard(e.location)
                val c = change ?: Change("edit entities", e.world).also { change = it }
                EntityEdits.edit(e, request, c)?.let { edited += it } ?: run { unchanged += raw }
            } catch (x: Exception) {
                failed += raw to error(x)
            }
            change?.takeIf { it.restores.isNotEmpty() }?.let {
                it.label = "edit ${entities(edited.size)}"
                it.blocks = edited.size
                Edits.record(it)
            }
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("edited", JsonArray().apply { edited.forEach { add(EntityEdits.json(it)) } })
            add("unchanged", JsonArray().apply { unchanged.forEach { add(it) } })
            add("failed", failures(failed))
        }))
        return ToolResult.text(summary(edited, unchanged, failed, scope.box?.describe()))
    }

    private fun remove(plugin: Foreman, args: Args): ToolResult {
        val scope = scope(plugin, args, "remove_entities")
        val json = json(args)
        val removed = mutableListOf<String>()
        val failed = mutableListOf<Pair<String, String>>()
        Sync.main {
            val targets = targets(scope)
            if (targets.size > MAX_NBT) throw ToolError("${targets.size} entities: $MAX_NBT at most in one call, use a smaller box or filters")
            var change: Change? = null
            for ((raw, found) in targets) try {
                val f = found()
                Locks.guard(f.entity.location)
                val c = change ?: Change("remove entities", f.entity.world).also { change = it }
                EntityEdits.remove(f, c)
                removed += raw
            } catch (x: Exception) {
                failed += raw to error(x)
            }
            change?.takeIf { it.restores.isNotEmpty() }?.let {
                it.label = "remove ${entities(removed.size)}"
                it.blocks = removed.size
                Edits.record(it)
            }
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("removed", JsonArray().apply { removed.forEach { add(it) } })
            add("failed", failures(failed))
        }))
        val head = if (removed.isEmpty()) (if (failed.isEmpty()) "No entity" + (scope.box?.let { " in ${it.describe()}" } ?: "") + " to remove." else "Nothing removed.")
        else "Removed ${entities(removed.size)}: undo brings them back."
        return ToolResult.text((listOf(head) + failureLines(failed, "Not removed")).joinToString("\n"))
    }

    private fun copy(plugin: Foreman, args: Args): ToolResult {
        val uuids = args.strings("uuids").distinct().ifEmpty { throw ToolError("Give uuids") }
        if (uuids.size > MAX_NBT) throw ToolError("${uuids.size} entities: $MAX_NBT at most in one call")
        val offset = if (args.has("offset")) args.point("offset").let { Vector(it[0], it[1], it[2]) } else Vector()
        val world = args.string("world", null)?.let { plugin.world(it) }
        val json = json(args)
        val copies = LinkedHashMap<String, String>()
        val failed = mutableListOf<Pair<String, String>>()
        Sync.main {
            var change: Change? = null
            for (raw in uuids) try {
                val f = EntityEdits.found(entity(world, raw))
                val c = change ?: Change("copy entities", f.entity.world).also { change = it }
                copies[raw] = EntityEdits.copy(f, offset, c).uniqueId.toString()
            } catch (x: Exception) {
                failed += raw to error(x)
            }
            change?.takeIf { it.spawnedEntities.isNotEmpty() }?.let {
                it.label = "copy ${entities(copies.size)}"
                it.blocks = copies.size
                Edits.record(it)
            }
        }
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("copies", JsonObject().apply { copies.forEach { (from, to) -> addProperty(from, to) } })
            add("failed", failures(failed))
        }))
        val head = if (copies.isEmpty()) "Nothing copied." else "Copied ${entities(copies.size)}: undo removes the copies.\n" +
            copies.entries.joinToString("\n") { (from, to) -> "  $from → $to" }
        return ToolResult.text((listOf(head) + failureLines(failed, "Not copied")).joinToString("\n"))
    }

    /** What an edit did, counted by change, and its failures grouped by reason. */
    private fun summary(edited: List<EntityEdits.Edited>, unchanged: List<String>, failed: List<Pair<String, String>>, box: String?): String {
        val lines = mutableListOf<String>()
        lines += when {
            edited.isEmpty() && unchanged.isEmpty() && failed.isEmpty() -> "No entity" + (box?.let { " in $it" } ?: "") + " to change."
            edited.isEmpty() -> "Nothing edited."
            else -> "Edited ${entities(edited.size)}: undo puts them back."
        }
        edited.mapNotNull { it.viewRange }.groupingBy { it }.eachCount().forEach { (r, n) -> lines += "  view_range ${r.first} → ${r.second}: ${entities(n)}" }
        fun counted(what: String, values: List<String>) = values.groupingBy { it }.eachCount().forEach { (v, n) -> lines += "  $what $v: ${entities(n)}" }
        counted("tag added", edited.flatMap { it.tagsAdded })
        counted("tag removed", edited.flatMap { it.tagsRemoved })
        counted("data set", edited.flatMap { it.dataSet })
        counted("data removed", edited.flatMap { it.dataRemoved })
        counted("nbt changed", edited.flatMap { it.nbt })
        counted("changed", edited.flatMap { it.changed })
        if (unchanged.isNotEmpty()) lines += "Already so: ${entities(unchanged.size)}" + if (unchanged.size <= 10) " (${unchanged.joinToString(", ")})" else ""
        lines += failureLines(failed, "Not edited")
        return lines.joinToString("\n")
    }
}
