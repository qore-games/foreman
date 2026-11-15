package games.qore.foreman.server.world

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.ForemanObjects
import games.qore.foreman.server.api.LayoutData
import games.qore.foreman.server.api.LayoutField
import games.qore.foreman.server.api.LayoutType
import games.qore.foreman.server.api.ObjectAction
import games.qore.foreman.server.api.ObjectKind
import games.qore.foreman.server.api.ObjectResult
import games.qore.foreman.server.api.PanelResult
import games.qore.foreman.server.api.PanelTarget
import games.qore.foreman.server.api.WorldObject
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.panels.PanelJson
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level
import kotlin.math.floor

/**
 * The object kinds plugins declare ([ForemanObjects]) and what Foreman does with their objects: asks their plugin for
 * them, checks data against their fields ([Layout]'s checks and defaults) and records every change in a [Change], whose
 * undo calls the plugin back. Foreman keeps none of them: the plugin's entities are the truth. Objects on the main thread.
 */
class WorldObjects(private val plugin: Foreman) : ForemanObjects, Listener {
    /** A declared kind: its [owner] plugin and its fields as a point type ([type]), for [Layout]'s checks. */
    private class Entry(val owner: String, val kind: ObjectKind, val type: LayoutType)

    /** An object as its plugin gives it ([obj]), with its [kind] and its [data], the kind's defaults filled in. */
    class Found(val kind: ObjectKind, val obj: WorldObject, val data: LayoutData) {
        /** Where it shows: its outline, else its anchors. */
        val line: List<Vector> get() = obj.outline.ifEmpty { obj.anchors }
    }

    /** What a search found, and the kinds whose plugin failed ([failed]: kind id → why). */
    class Listing(val objects: List<Found>, val failed: Map<String, String>)

    /** An action a plugin ([owner]) added to the objects tool. */
    class Action(val owner: String, val action: ObjectAction)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val actions = java.util.concurrent.CopyOnWriteArrayList<Action>()
    private val gson = Gson()
    private val layout get() = plugin.worlds.layout

    /** Objects whose plugin gave them a new id (moved, put back by an undo): older changes naming the old id reach them. */
    private val replaced = HashMap<String, String>()

    /** `id what` pairs whose failure, polled for the editors, was logged: the next ones aren't, until it's registered again. */
    private val logged = ConcurrentHashMap.newKeySet<String>()

    /** Bumped when the kinds change: the bridge sends them again. */
    @Volatile var version = 0
        private set

    /** Bumped by every change made here: the bridge sends the objects near each editor again at once. */
    @Volatile var changes = 0
        private set

    fun start() {
        Bukkit.getServicesManager().register(ForemanObjects::class.java, this, plugin, ServicePriority.Normal)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        if (entries.values.removeIf { it.owner == event.plugin.name }) version++
        actions.removeIf { it.owner == event.plugin.name }
    }

    override fun register(owner: Plugin, kind: ObjectKind) {
        entries[kind.id]?.let { require(it.owner == owner.name) { "${kind.id} is declared by ${it.owner}" } }
        val type = LayoutType.point(kind.id).label(kind.label).description(kind.description).apply {
            kind.color?.let { color(it) }
            kind.icon?.let { icon(it) }
            kind.fields.forEach { field(it) }
        }.build()
        entries[kind.id] = Entry(owner.name, kind, type)
        logged.removeIf { it.startsWith("${kind.id} ") }
        version++
    }

    override fun unregister(owner: Plugin, id: String) {
        if (entries[id]?.owner == owner.name && entries.remove(id) != null) version++
    }

    override fun kinds(): List<ObjectKind> = entries.values.map { it.kind }.sortedBy { it.id }

    override fun kind(id: String): ObjectKind? = entries[id]?.kind

    fun owner(kind: ObjectKind): String = entries[kind.id]?.owner.orEmpty()

    override fun addAction(owner: Plugin, action: ObjectAction) {
        synchronized(actions) {
            val i = actions.indexOfFirst { it.action.id == action.id }
            if (i >= 0) {
                require(actions[i].owner == owner.name) { "${action.id} is added by ${actions[i].owner}" }
                actions[i] = Action(owner.name, action)
            } else actions += Action(owner.name, action)
        }
        logged.removeIf { it.startsWith("${action.id} ") }
    }

    override fun removeAction(owner: Plugin, id: String) {
        actions.removeIf { it.action.id == id && it.owner == owner.name }
    }

    override fun actions(): List<ObjectAction> = actions.map { it.action }

    /** The actions offered to [player] here, with their state for them: what the bridge sends. Main thread. */
    fun actions(player: Player): List<Pair<Action, Boolean?>> = actions.mapNotNull { a ->
        val h = a.action.handler
        try {
            if (h.available(player)) a to h.state(player) else null
        } catch (e: Exception) {
            warnOnce(a.action.id, "state", "${a.owner}'s object action ${a.action.id} failed to say its state", e)
            null
        }
    }

    /** Runs the action [id] for [player]: how it went. Main thread. */
    fun run(player: Player, id: String): PanelResult {
        val a = actions.firstOrNull { it.action.id == id } ?: return PanelResult.refused("No action $id here any more")
        return try {
            if (!a.action.handler.available(player)) PanelResult.refused("${a.action.label}: not here")
            else a.action.handler.run(player)
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "${a.owner}'s object action ${a.action.id} failed", e)
            PanelResult.refused("${a.action.label} failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun require(id: String): ObjectKind = kind(id) ?: throw ToolError("No object kind '$id' (object_kinds lists them)")

    private fun type(kind: ObjectKind) = entries[kind.id]?.type ?: throw ToolError("${kind.id} is no longer declared")

    // ---- Finding ----------------------------------------------------------------------------------------------------

    /** The objects of [world] reaching into [box], of [kind] or of every kind. Main thread. */
    fun find(world: World, box: BoundingBox, kind: String? = null): Listing {
        val kinds = if (kind != null) listOf(require(kind)) else kinds()
        val failed = LinkedHashMap<String, String>()
        val found = kinds.flatMap { k ->
            try {
                k.handler.find(world, box.clone()).filter { it.world == world }.map { of(k, it) }
            } catch (e: Exception) {
                warnOnce(k.id, "find", "${owner(k)} failed to find its ${k.id} objects", e)
                failed[k.id] = e.toString()
                emptyList()
            }
        }
        return Listing(found, failed)
    }

    /** The objects within [radius] of [at], the nearest first, at most [limit]: what an editor there outlines. Main thread. */
    fun near(at: Location, radius: Double, limit: Int): List<Found> {
        val world = at.world ?: return emptyList()
        if (entries.isEmpty()) return emptyList()
        val box = BoundingBox(at.x - radius, at.y - radius, at.z - radius, at.x + radius, at.y + radius, at.z + radius)
        val from = at.toVector()
        return find(world, box).objects.map { it to it.line.minOf { p -> p.distanceSquared(from) } }
            .filter { it.second <= radius * radius }.sortedBy { it.second }.take(limit).map { it.first }
    }

    /**
     * The object [id] of [world] (or what it became): of [kind], else of the first kind that has it (a plugin failing on
     * an id it doesn't know is passed over). Main thread.
     */
    fun get(world: World, id: String, kind: String? = null): Found? {
        if (kind != null) return require(kind).let { k -> call(k, "find it") { k.handler.get(world, current(k, id)) }?.let { of(k, it) } }
        for (k in kinds()) {
            val obj = runCatching { k.handler.get(world, current(k, id)) }.getOrNull() ?: continue
            return of(k, obj)
        }
        return null
    }

    private fun of(kind: ObjectKind, obj: WorldObject) = Found(kind, obj, layout.api(listOf(type(kind)), json(obj.data)))

    /**
     * The choices of [kind]'s fields that depend on others ([LayoutField.dependsOn]) for [given], a form's data as it is
     * now (the object [current] of [world]'s, or a new one's): its plugin's, by field; those it leaves to their values
     * are left out. Main thread.
     */
    fun choices(world: World, kind: ObjectKind, current: Found?, given: JsonObject): Map<String, List<String>> {
        val fields = kind.fields.filter { it.dependsOn.isNotEmpty() }
        if (fields.isEmpty()) return emptyMap()
        val data = layout.api(listOf(type(kind)), runCatching { targets(kind, given) }.getOrDefault(given))
        val out = LinkedHashMap<String, List<String>>()
        for (f in fields) {
            val choices = call(kind, "give the choices of ${f.name}") { kind.handler.choices(world, current?.obj, f.name, data) } ?: continue
            out[f.name] = choices.filter { it in f.values }
        }
        return out
    }

    // ---- Changes ----------------------------------------------------------------------------------------------------

    /** Places an object of [kind] at [anchors] with [data] (checked against its fields, defaults filled in), in [change]. Main thread. */
    fun create(world: World, kind: ObjectKind, anchors: List<Vector>, given: JsonObject, change: Change): Found {
        requireAnchors(kind, anchors)
        check(kind, given)
        val data = targets(kind, given)
        Locks.guard(world.name, box(anchors))
        val made = result(kind, "make it") { kind.handler.create(world, anchors.map(Vector::clone), layout.api(listOf(type(kind)), data)) }
        change.restores += undoing(kind, "remove ${made.id}") { get(world, made.id, kind.id)?.let { kind.handler.remove(it.obj) } }
        return changed(change, of(kind, made))
    }

    /**
     * Changes [found] to [anchors] (null: where it is) and its data with [patch] merged in (a null value removes a field:
     * its default) or, with [replace], to [patch] alone, in [change]. Main thread.
     */
    fun update(found: Found, anchors: List<Vector>?, patch: JsonObject?, replace: Boolean, change: Change): Found {
        val kind = found.kind
        val world = found.obj.world
        val to = anchors?.also { requireAnchors(kind, it) } ?: found.obj.anchors
        val data = (if (patch == null) json(found.data) else layout.merge(json(found.data), patch, replace)).also { check(kind, it) }.let { targets(kind, it) }
        Locks.guard(world.name, box(found.line).union(box(to)))
        val now = result(kind, "change it") { kind.handler.update(found.obj, to.map(Vector::clone), layout.api(listOf(type(kind)), data)) }
        replaced(kind, found.obj.id, now.id)
        val before = found.obj.anchors.map(Vector::clone)
        change.restores += undoing(kind, "put ${now.id} back") {
            val current = get(world, now.id, kind.id) ?: return@undoing
            kind.handler.update(current.obj, before, found.data).worldObject?.let { replaced(kind, current.obj.id, it.id) }
        }
        return changed(change, of(kind, now), found.line)
    }

    /** Removes [found] from the world, in [change]: its undo makes it again. Main thread. */
    fun remove(found: Found, change: Change) {
        val kind = found.kind
        val world = found.obj.world
        Locks.guard(world.name, box(found.line))
        if (!call(kind, "remove it") { kind.handler.remove(found.obj) }) throw ToolError("it was gone already")
        val anchors = found.obj.anchors.map(Vector::clone)
        change.restores += undoing(kind, "make ${found.obj.id} again") {
            kind.handler.create(world, anchors, found.data).worldObject?.let { replaced(kind, found.obj.id, it.id) }
        }
        change.touch(box(found.line))
        changes++
    }

    private fun changed(change: Change, found: Found, before: List<Vector> = emptyList()): Found {
        change.touch(box(found.line + before))
        changes++
        return found
    }

    private fun requireAnchors(kind: ObjectKind, anchors: List<Vector>) {
        val count = kind.shape.anchors
        if (anchors.size != count) throw ToolError("${kind.id} is a ${kind.shape.name.lowercase()}: it has $count anchor${if (count == 1) "" else "s"}, not ${anchors.size}")
        if (anchors.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) throw ToolError("Anchors are [x, y, z] numbers")
    }

    /** Refuses [data] that doesn't fit [kind]'s fields: wrong types, choices, ranges, unknown or missing required fields. */
    private fun check(kind: ObjectKind, data: JsonObject) {
        val problems = layout.problems(listOf(type(kind)), data)
        if (problems.isEmpty()) return
        throw ToolError("Its data doesn't fit ${kind.id}:\n" + problems.joinToString("\n") { "  " + it.text } + "\nobject_kinds lists its fields.")
    }

    /**
     * [data] with its target fields' values as the targets they name, resolved by the panel providers when there are
     * some (a furniture for its cell block), as their JSON: what the plugin gets and stores.
     */
    private fun targets(kind: ObjectKind, data: JsonObject): JsonObject {
        val fields = kind.fields.filter { it.type == LayoutField.Type.TARGET && data.get(it.name)?.isJsonObject == true }
        if (fields.isEmpty()) return data
        val out = data.deepCopy()
        for (f in fields) {
            val target = PanelJson.target(data.get(f.name), f.name)
            out.add(f.name, PanelJson.target(plugin.panels?.resolved(target) ?: target))
        }
        return out
    }

    /** What [what] of [kind]'s plugin made: the object, else its refusal as a [ToolError]. */
    private fun result(kind: ObjectKind, what: String, block: () -> ObjectResult): WorldObject {
        val result = call(kind, what, block)
        return result.worldObject ?: throw ToolError(result.error?.ifBlank { null } ?: "${owner(kind)} refused to $what")
    }

    /** Logs a plugin's failure in what the editors poll, once per [id] and [what]. */
    private fun warnOnce(id: String, what: String, message: String, e: Exception) {
        if (logged.add("$id $what")) plugin.logger.log(Level.WARNING, "$message (logged once)", e)
    }

    /** Calls [kind]'s plugin: its exceptions are logged and become a [ToolError]. */
    private fun <T> call(kind: ObjectKind, what: String, block: () -> T): T = try {
        block()
    } catch (e: ToolError) {
        throw e
    } catch (e: Exception) {
        plugin.logger.log(Level.WARNING, "${owner(kind)} failed to $what (${kind.id})", e)
        throw ToolError("${owner(kind)} failed to $what: ${e.message ?: e.javaClass.simpleName}")
    }

    /** An undo step calling [kind]'s plugin: a failure is logged, and the rest of the undo goes on. */
    private fun undoing(kind: ObjectKind, what: String, block: () -> Unit) = Runnable {
        try {
            block()
            changes++
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Undo: ${owner(kind)} failed to $what (${kind.id})", e)
        }
    }

    /** What the object [id] of [kind] is now. Main thread. */
    private fun current(kind: ObjectKind, id: String): String {
        var c = id
        repeat(64) { c = replaced["${kind.id} $c"] ?: return c }
        return c
    }

    private fun replaced(kind: ObjectKind, old: String, new: String) {
        if (old == new) return
        replaced["${kind.id} $old"] = new
        replaced.remove("${kind.id} $new")
    }

    // ---- Output -----------------------------------------------------------------------------------------------------

    /** The blocks [points] are in, as a box. */
    fun box(points: List<Vector>): Box {
        fun block(x: Double, y: Double, z: Double) = BlockVector3.at(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
        return Box(block(points.minOf { it.x }, points.minOf { it.y }, points.minOf { it.z }), block(points.maxOf { it.x }, points.maxOf { it.y }, points.maxOf { it.z }))
    }

    /** [data] as JSON, its unset fields left out (a [PanelTarget] its plugin put there as its JSON). */
    private fun json(data: LayoutData): JsonObject = JsonObject().apply { data.asMap().forEach { (k, v) -> if (v != null) add(k, PanelJson.json(v)) } }

    /** [f] as the tools and the bridge give it: id, kind, world, anchors, data, and its outline with [outline] (at most [points] of it). */
    fun json(f: Found, outline: Boolean, points: Int = Int.MAX_VALUE) = JsonObject().apply {
        addProperty("id", f.obj.id)
        addProperty("kind", f.kind.id)
        addProperty("world", f.obj.world.name)
        add("anchors", vectors(f.obj.anchors))
        add("data", json(f.data))
        f.obj.status?.let { add("status", PanelJson.status(it)) }
        if (outline && f.obj.outline.isNotEmpty()) add("outline", vectors(thin(f.obj.outline, points)))
    }

    /** [line] with at most [max] points: every nth, its ends kept. */
    private fun thin(line: List<Vector>, max: Int): List<Vector> {
        if (line.size <= max || max < 2) return line
        val step = (line.size - 1).toDouble() / (max - 1)
        return (0 until max).map { line[Math.round(it * step).toInt().coerceAtMost(line.size - 1)] }
    }

    private fun vectors(points: List<Vector>) = JsonArray().apply {
        points.forEach { v -> add(JsonArray().apply { add(round(v.x)); add(round(v.y)); add(round(v.z)) }) }
    }

    private fun round(d: Double) = Math.round(d * 1000) / 1000.0

    /** [f] in one line: `ns:kind id: [x, y, z] → [x, y, z] · look black, sag 1 · status: no power (blocked)`. */
    fun describe(f: Found): String =
        "${f.kind.id} ${f.obj.id}: " + f.obj.anchors.joinToString(" → ") { at(it) } +
            f.data.asMap().entries.joinToString(", ") { (k, v) -> "$k ${value(v)}" }.let { if (it.isEmpty()) "" else " · $it" } +
            (f.obj.status?.let { " · status: ${it.text()}" + if (it.word.equals(it.level.key, true)) "" else " (${it.level.key})" }.orEmpty())

    private fun value(v: Any?): String = when (v) {
        is Double -> Point.fmt(v)
        is PanelTarget -> v.toString()
        else -> gson.toJson(v).removeSurrounding("\"")
    }

    fun at(v: Vector) = "[${Point.fmt(round(v.x))}, ${Point.fmt(round(v.y))}, ${Point.fmt(round(v.z))}]"

    /** The declared kinds, for `object_kinds` and the bridge: each as a layout type's JSON with its owner and shape. */
    fun json(): JsonArray = JsonArray().apply {
        kinds().forEach { k ->
            add(layout.json(type(k)).apply {
                remove("target")
                addProperty("owner", owner(k))
                addProperty("shape", k.shape.name.lowercase())
                addProperty("anchors", k.shape.anchors)
                k.category?.let { addProperty("category", it) }
                k.placement?.let { p ->
                    add("furniture", JsonObject().apply {
                        addProperty("id", p.furniture)
                        p.facing?.let { addProperty("facing", it) }
                        p.surface?.let { addProperty("surface", it) }
                    })
                }
            })
        }
    }

    /** [k] as `object_kinds` gives it, the fields of [choices] offering those. */
    fun json(k: ObjectKind, choices: Map<String, List<String>>): JsonObject {
        val out = json().map { it.asJsonObject }.firstOrNull { it.get("id")?.asString == k.id } ?: JsonObject()
        out.getAsJsonArray("fields")?.forEach { f ->
            val o = f.asJsonObject
            choices[o.get("name")?.asString]?.let { values -> o.add("values", JsonArray().apply { values.forEach { add(it) } }) }
        }
        return out
    }

    /** [k]'s fields for the AI, a line each, those of [choices] with the choices they offer for that data. */
    fun describe(k: ObjectKind, choices: Map<String, List<String>>): String = "${k.id} \"${k.label}\", its fields:" + k.fields.joinToString("") { f ->
        "\n    " + layout.field(f) + (choices[f.name]?.let { " — for this data: ${it.joinToString("|").ifEmpty { "none" }}" } ?: "")
    }

    fun describeKinds(): String {
        val kinds = kinds()
        if (kinds.isEmpty()) return "No plugin declares object kinds here: there are no objects to list or place."
        return "Object kinds (${kinds.size}):" + kinds.joinToString("") { k ->
            "\n- ${k.id} \"${k.label}\" (${listOfNotNull(owner(k), k.category).joinToString(", ")}), ${k.shape.name.lowercase()}: ${anchors(k)}" +
                (k.placement?.let { " (placed like furniture ${it.furniture})" } ?: "") +
                (if (k.description.isNotEmpty()) ". ${k.description}" else "") + k.fields.joinToString("") { f -> "\n    ${layout.field(f)}" }
        }
    }

    private fun anchors(k: ObjectKind) = when (k.shape) {
        ObjectKind.Shape.POINT -> "1 anchor, where it stands"
        ObjectKind.Shape.LINE -> "2 anchors, its ends"
    }
}
