package games.qore.foreman.server.tools

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
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
import games.qore.foreman.server.world.Sync
import games.qore.foreman.server.world.WorldObjects
import org.bukkit.Bukkit
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

/**
 * The objects plugins draw with entities and declare through `ForemanObjects` (cables, props): their kinds, and listing,
 * placing, editing and removing them through their plugin, each call one undo step. The mod's objects tool calls them too.
 */
object ObjectTools {
    private val gson = GsonBuilder().disableHtmlEscaping().create()
    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private const val MAX_RADIUS = 128.0
    private const val MAX_AREA = 256L * 256
    private const val MAX_LISTED = 300

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.OBJECT_KINDS,
            "The kinds of objects the server's plugins draw with entities and let you edit (`ns:name`: cables, props…): " +
                "what each is, its shape (point: 1 anchor where it stands; line: 2 anchors, its ends) and the fields of " +
                "its data (type, required, default, choices, range; a target field holds another thing as a target " +
                "object, as the panel tools take it). Read it before place_objects. With `kind` and `data` (an object's " +
                "data as you mean to give it, and `id` when it is an existing one's), the choices of fields that depend " +
                "on others (an output of the thing a field links) are those for that data.",
            schema {
                string("kind", "Only this kind", required = false)
                prop("data", SchemaBuilder.type("object"), "With kind: the data to give the choices for", required = false)
                string("id", "With data: the object it is for (else a new one)", required = false)
                string("world", "World name", required = false)
                enum("format", listOf("text", "json"), "text (default) or json (every detail, as the client gets it)", required = false)
            },
        ) { args -> kinds(plugin, args) },

        Tool(
            ForemanTool.LIST_OBJECTS,
            "Lists the objects plugins declare (object_kinds) near a point (`at`, within `radius`) or in a box (`from`, " +
                "`to`), else near the calling player: id, kind, anchors (exact positions) and data, the nearest first. " +
                "Read them before edit_objects or remove_objects.",
            schema {
                prop("at", SchemaBuilder.pointSchema(), "[x, y, z]: list around this point", required = false)
                number("radius", "Around `at` or the player, in blocks (default 16, at most 128)", required = false)
                vec("from", "Or a box: a corner", required = false)
                vec("to", "The opposite corner", required = false)
                string("kind", "Only objects of this kind", required = false)
                bool("outline", "json: also each one's outline (the line where it shows), when its plugin gives one", required = false)
                string("world", "World name", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {objects: [{id, kind, world, anchors, data, outline?}], failed?: {kind: error}}", required = false)
            },
        ) { args -> list(plugin, args) },

        Tool(
            ForemanTool.PLACE_OBJECTS,
            "Places objects of the kinds plugins declare (object_kinds), one undo step for all: each {kind, anchors: " +
                "[[x, y, z], …] as many as its shape has (a line: its two ends), data?: field values (the others take " +
                "their defaults)}. Its plugin makes it and may refuse it (its own rules). Returns each placed object (its " +
                "id for edit_objects) or why not.",
            schema {
                array("objects", SchemaBuilder.type("object"), "[{kind, anchors: [[x, y, z], …], data?: {field: value}}]")
                string("world", "World name", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {placed: [{id, kind, world, anchors, data, outline?}], failed: [{index, error}]}", required = false)
            },
            modifies = true,
        ) { args -> place(plugin, args) },

        Tool(
            ForemanTool.EDIT_OBJECTS,
            "Changes objects (list_objects), one undo step for all: each {id, kind?, anchors?: new positions (all of " +
                "them), data?: the fields to change (null puts a field back to its default), replace_data?: data is all " +
                "of it}. Its plugin applies it and may refuse; it may give the object a new id (`replaced`).",
            schema {
                array("objects", SchemaBuilder.type("object"), "[{id, kind?, anchors?: [[x, y, z], …], data?: {field: value}, replace_data?: bool}]")
                string("world", "World name", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {edited: [{id, kind, world, anchors, data, outline?}], failed: [{id, error}], replaced: {old id: new id}}", required = false)
            },
            modifies = true,
        ) { args -> edit(plugin, args) },

        Tool(
            ForemanTool.REMOVE_OBJECTS,
            "Removes objects by id (list_objects) through their plugin, one undo step for all: undo makes them again.",
            schema {
                strings("ids", "The objects' ids")
                string("kind", "Only of this kind (default: the first kind that has each id)", required = false)
                string("world", "World name", required = false)
                enum("format", listOf("text", "json"), "text (default) or json: {removed: [id], failed: [{id, error}]}", required = false)
            },
            modifies = true,
        ) { args -> remove(plugin, args) },
    )

    private fun json(args: Args) = when (args.string("format", "text")) {
        "text" -> false
        "json" -> true
        else -> throw ToolError("format is text or json")
    }

    private fun kinds(plugin: Foreman, args: Args): ToolResult {
        val objects = plugin.worlds.objects
        val id = args.string("kind", null) ?: return if (json(args)) ToolResult.text(pretty.toJson(JsonObject().apply { add("kinds", objects.json()) }))
            else ToolResult.text(objects.describeKinds())
        val kind = objects.require(id)
        val choices = if (!args.has("data")) emptyMap() else {
            val world = plugin.world(args.string("world", null))
            val data = data(args)
            Sync.main { objects.choices(world, kind, args.string("id", null)?.let { objects.get(world, it, kind.id) ?: throw missing(world) }, data) }
        }
        if (json(args)) return ToolResult.text(pretty.toJson(JsonObject().apply { add("kinds", JsonArray().apply { add(objects.json(kind, choices)) }) }))
        return ToolResult.text(objects.describe(kind, choices))
    }

    private fun list(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val kind = args.string("kind", null)
        val json = json(args)
        val box = if (args.has("from") || args.has("to")) {
            Box.of(args.vec("from"), args.vec("to")).also {
                if (it.sizeX.toLong() * it.sizeZ > MAX_AREA) throw ToolError("The box is too large: 256 × 256 blocks at most")
            }
        } else {
            val radius = args.double("radius", 16.0).takeIf { it > 0 && it <= MAX_RADIUS } ?: throw ToolError("radius is above 0 and at most 128")
            val at = if (args.has("at")) args.point("at").let { Vector(it[0], it[1], it[2]) }
            else Sync.main { args.caller?.let { Bukkit.getPlayer(it.id) }?.takeIf { it.world == world }?.location?.toVector() }
                ?: throw ToolError("Give at (or from and to): no player of yours in ${world.name} to list around")
            Box.of(block(at.clone().subtract(Vector(radius, radius, radius))), block(at.clone().add(Vector(radius, radius, radius))))
        }
        if (Chunks.loadEntities(world, listOf(box))) Thread.sleep(250)
        val listing = Sync.main { plugin.worlds.objects.find(world, bounds(box), kind) }
        val center = Vector((box.min.x() + box.max.x() + 1) / 2.0, (box.min.y() + box.max.y() + 1) / 2.0, (box.min.z() + box.max.z() + 1) / 2.0)
        val objects = listing.objects.sortedBy { f -> f.line.minOf { it.distanceSquared(center) } }
        val objectsTool = plugin.worlds.objects
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("objects", JsonArray().apply { objects.take(MAX_LISTED).forEach { add(objectsTool.json(it, args.bool("outline", false))) } })
            if (listing.failed.isNotEmpty()) add("failed", JsonObject().apply { listing.failed.forEach { (k, e) -> addProperty(k, e) } })
        }))
        val lines = objects.take(MAX_LISTED).map { objectsTool.describe(it) }
        return ToolResult.text(
            (if (objects.isEmpty()) "No objects${kind?.let { " of $it" }.orEmpty()} in ${box.describe()}." else "${plural(objects.size, "object")} in ${box.describe()}:\n" + lines.joinToString("\n")) +
                (if (objects.size > MAX_LISTED) "\n… ${objects.size - MAX_LISTED} more: a smaller box" else "") +
                listing.failed.entries.joinToString("") { (k, e) -> "\nThe plugin of $k failed: $e" }
        )
    }

    private fun place(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val entries = entries(args)
        val json = json(args)
        val objects = plugin.worlds.objects
        val placed = mutableListOf<WorldObjects.Found>()
        val failed = mutableListOf<Pair<String, String>>()
        Sync.main {
            val change = Change("place objects", world)
            entries.forEachIndexed { i, e ->
                try {
                    val kind = objects.require(e.string("kind"))
                    placed += objects.create(world, kind, anchors(e), data(e), change)
                } catch (x: Exception) {
                    failed += "$i" to error(x)
                }
            }
            record(change, "place", placed.map { it.kind.id })
        }
        if (placed.isEmpty()) refused("placed", failed)
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("placed", JsonArray().apply { placed.forEach { add(objects.json(it, true, 256)) } })
            add("failed", failures(failed, "index"))
        }))
        return ToolResult.text(
            "Placed ${plural(placed.size, "object")} (${box(placed).describe()}): undo removes ${them(placed.size)}.\n" + placed.joinToString("\n") { objects.describe(it) } +
                failed.joinToString("") { (i, error) -> "\nNot placed #$i: $error" }
        )
    }

    private fun edit(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val entries = entries(args)
        val json = json(args)
        val objects = plugin.worlds.objects
        val edited = mutableListOf<WorldObjects.Found>()
        val failed = mutableListOf<Pair<String, String>>()
        val replaced = LinkedHashMap<String, String>()
        Sync.main {
            val change = Change("edit objects", world)
            for (e in entries) {
                val id = e.string("id", null)
                if (id == null) {
                    failed += "" to "missing id"
                    continue
                }
                try {
                    val found = objects.get(world, id, e.string("kind", null)) ?: throw missing(world)
                    val anchors = if (e.has("anchors")) anchors(e) else null
                    val patch = if (e.has("data")) data(e) else null
                    if (anchors == null && patch == null) throw ToolError("give anchors or data")
                    val now = objects.update(found, anchors, patch, e.bool("replace_data", false), change)
                    if (now.obj.id != id) replaced[id] = now.obj.id
                    edited += now
                } catch (x: Exception) {
                    failed += id to error(x)
                }
            }
            record(change, "edit", edited.map { it.kind.id })
        }
        if (edited.isEmpty()) refused("edited", failed)
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("edited", JsonArray().apply { edited.forEach { add(objects.json(it, true, 256)) } })
            add("failed", failures(failed, "id"))
            add("replaced", JsonObject().apply { replaced.forEach { (old, now) -> addProperty(old, now) } })
        }))
        return ToolResult.text(
            "Edited ${plural(edited.size, "object")} (${box(edited).describe()}): undo puts ${them(edited.size)} back.\n" + edited.joinToString("\n") { objects.describe(it) } +
                replaced.entries.joinToString("") { (old, now) -> "\n$old is $now now." } +
                failed.joinToString("") { (id, error) -> "\nNot edited ${id.ifEmpty { "(no id)" }}: $error" }
        )
    }

    private fun remove(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val ids = args.strings("ids").ifEmpty { throw ToolError("Give ids") }
        val kind = args.string("kind", null)
        val json = json(args)
        val objects = plugin.worlds.objects
        val removed = mutableListOf<WorldObjects.Found>()
        val failed = mutableListOf<Pair<String, String>>()
        Sync.main {
            val change = Change("remove objects", world)
            for (id in ids) try {
                val found = objects.get(world, id, kind) ?: throw missing(world)
                objects.remove(found, change)
                removed += found
            } catch (x: Exception) {
                failed += id to error(x)
            }
            record(change, "remove", removed.map { it.kind.id })
        }
        if (removed.isEmpty()) refused("removed", failed)
        if (json) return ToolResult.text(gson.toJson(JsonObject().apply {
            add("removed", JsonArray().apply { removed.forEach { add(it.obj.id) } })
            add("failed", failures(failed, "id"))
        }))
        return ToolResult.text(
            "Removed ${plural(removed.size, "object")} (${box(removed).describe()}): undo makes ${them(removed.size)} again." +
                failed.joinToString("") { (id, error) -> "\nNot removed $id: $error" }
        )
    }

    /** Nothing was done: an error naming why, so the caller knows the call failed. */
    private fun refused(done: String, failed: List<Pair<String, String>>): Nothing =
        throw ToolError(if (failed.size == 1) failed[0].second.replaceFirstChar { it.uppercase() } else "Nothing $done:" + failed.joinToString("") { (id, e) -> "\n  ${id.ifEmpty { "?" }}: $e" })

    /** Records [change] for undo when it did something: labelled with what was done to which kinds. */
    private fun record(change: Change, verb: String, kinds: List<String>) {
        if (change.restores.isEmpty()) return
        change.label = "$verb " + (kinds.distinct().singleOrNull()?.let { if (kinds.size == 1) it else "${kinds.size} $it" } ?: plural(kinds.size, "object"))
        change.blocks = kinds.size
        Edits.record(change)
    }

    private fun entries(args: Args): List<Args> = args.array("objects").map { e ->
        Args(runCatching { e.asJsonObject }.getOrElse { throw ToolError("objects holds objects, got $e") }).also { it.caller = args.caller }
    }.ifEmpty { throw ToolError("Give objects") }

    private fun anchors(e: Args): List<Vector> = e.array("anchors").map { a ->
        val p = runCatching { a.asJsonArray.map { it.asDouble } }.getOrNull()?.takeIf { it.size == 3 } ?: throw ToolError("Anchors are [x, y, z], got $a")
        Vector(p[0], p[1], p[2])
    }

    private fun data(e: Args): JsonObject = if (!e.has("data")) JsonObject() else e.json.get("data").let { d: JsonElement ->
        if (d.isJsonObject) d.asJsonObject else throw ToolError("data is an object of field values, got $d")
    }

    private fun box(found: List<WorldObjects.Found>): Box = Foreman.instance.worlds.objects.box(found.flatMap { it.line })

    private fun block(v: Vector) = com.sk89q.worldedit.math.BlockVector3.at(v.blockX, v.blockY, v.blockZ)

    private fun bounds(box: Box) = BoundingBox(
        box.min.x().toDouble(), box.min.y().toDouble(), box.min.z().toDouble(),
        box.max.x() + 1.0, box.max.y() + 1.0, box.max.z() + 1.0,
    )

    private fun error(x: Exception) = if (x is ToolError) x.message.orEmpty() else x.toString()

    private fun failures(failed: List<Pair<String, String>>, key: String) = JsonArray().apply {
        failed.forEach { (id, error) ->
            add(JsonObject().apply {
                if (key == "index") id.toIntOrNull()?.let { addProperty(key, it) } else addProperty(key, id)
                addProperty("error", error)
            })
        }
    }

    private fun plural(n: Int, word: String) = "$n $word${if (n == 1) "" else "s"}"

    private fun them(n: Int) = if (n == 1) "it" else "them"

    /** An id no plugin finds: gone, or in chunks not loaded (plugins find loaded entities). */
    private fun missing(world: org.bukkit.World) = ToolError("no such object in ${world.name}: gone, or where no chunk is loaded (list_objects around it loads them)")
}
