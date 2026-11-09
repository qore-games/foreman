package games.qore.foreman.server.world

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.ForemanLayout
import games.qore.foreman.server.api.LayoutData
import games.qore.foreman.server.api.LayoutField
import games.qore.foreman.server.api.LayoutType
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * The point and area types plugins declare ([ForemanLayout]) and what their data must hold: the tools that write data
 * refuse what doesn't match ([require]), `check_layout` reports what doesn't any more ([check]). A point is of the type
 * its `type` names, an area of every type its claim kind or tags name. Namespaced names that no plugin declares are
 * unknown; plain names are free text.
 */
class Layout(private val plugin: Foreman) : ForemanLayout, Listener {
    private class Entry(val owner: String, val type: LayoutType)

    /** A problem with a value: [field] when it is one field's, an [error] or a warning. */
    class Problem(val field: String?, val text: String, val error: Boolean = true)

    /** What `check_layout` reports: a point's or an area's problem, of [type]. */
    class Issue(val error: Boolean, val type: String, val text: String, val field: String? = null, val point: Point? = null, val area: Area? = null)

    class Report(val world: String, val points: Int, val typedPoints: Int, val areas: Int, val typedAreas: Int, val issues: List<Issue>, val free: Map<String, Int>)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val gson = Gson()

    /** Bumped on every change: the bridge pushes the types again. */
    @Volatile var version = 0
        private set

    fun start() {
        Bukkit.getServicesManager().register(ForemanLayout::class.java, this, plugin, ServicePriority.Normal)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        if (entries.values.removeIf { it.owner == event.plugin.name }) version++
    }

    private fun key(target: LayoutType.Target, id: String) = "${target.name}/$id"

    override fun register(owner: Plugin, type: LayoutType) {
        val key = key(type.target, type.id)
        entries[key]?.let { require(it.owner == owner.name) { "${type.id} is declared by ${it.owner}" } }
        entries[key] = Entry(owner.name, type)
        version++
    }

    override fun unregister(owner: Plugin, target: LayoutType.Target, id: String) {
        if (entries[key(target, id)]?.owner == owner.name && entries.remove(key(target, id)) != null) version++
    }

    override fun types(): List<LayoutType> = entries.values.map { it.type }.sortedWith(compareBy({ it.target }, { it.id }))

    override fun type(target: LayoutType.Target, id: String): LayoutType? = entries[key(target, id)]?.type

    fun owner(type: LayoutType): String = entries[key(type.target, type.id)]?.owner.orEmpty()

    /** The declared type of [p], if any. */
    fun of(p: Point): List<LayoutType> = listOfNotNull(type(LayoutType.Target.POINT, p.type))

    /** The declared types [a] is: those its claim kind and tags name. */
    fun of(a: Area): List<LayoutType> = areaTypes(a.kind, a.tags)

    fun areaTypes(kind: String?, tags: List<String>): List<LayoutType> = names(kind, tags).mapNotNull { type(LayoutType.Target.AREA, it) }

    /** The names an area's types are matched by: its claim kind and tags. */
    fun names(a: Area): List<String> = names(a.kind, a.tags)

    private fun names(kind: String?, tags: List<String>) = (listOfNotNull(kind) + tags).distinct()

    /**
     * A catalog `point` entry (`type:kind`) as type and kind: a declared type keeps its namespace (`loot:container:chest`
     * is type `loot:container`, kind `chest` when `loot:container` is declared).
     */
    fun split(entry: String): Pair<String, String> {
        val declared = entries.values.map { it.type }.filter { it.target == LayoutType.Target.POINT }
            .firstOrNull { entry == it.id || entry.startsWith(it.id + ":") }
        return if (declared != null) declared.id to entry.removePrefix(declared.id).removePrefix(":") else entry.substringBefore(':') to entry.substringAfter(':', "")
    }

    // ---- Data -------------------------------------------------------------------------------------------------------

    /** [data] as the API reads it: plain values, the defaults of [types]' fields where it has none. */
    fun api(types: List<LayoutType>, data: JsonObject): LayoutData {
        if (data.size() == 0 && types.all { t -> t.fields.all { it.defaultValue == null } }) return LayoutData.EMPTY
        val values = LinkedHashMap<String, Any?>()
        data.entrySet().forEach { (k, v) -> values[k] = plain(v) }
        types.flatMap { it.fields }.forEach { f -> f.defaultValue?.let { values.putIfAbsent(f.name, it) } }
        return LayoutData(values)
    }

    private fun plain(e: JsonElement): Any? = when {
        e.isJsonNull -> null
        e.isJsonObject -> LinkedHashMap<String, Any?>().apply { e.asJsonObject.entrySet().forEach { (k, v) -> put(k, plain(v)) } }
        e.isJsonArray -> e.asJsonArray.map(::plain)
        e.asJsonPrimitive.isBoolean -> e.asBoolean
        e.asJsonPrimitive.isNumber -> e.asDouble
        else -> e.asString
    }

    /** [base] with [patch] merged in key by key (null removes a key), or [patch] alone when [replace]. Nulls are dropped. */
    fun merge(base: JsonObject, patch: JsonObject, replace: Boolean): JsonObject {
        val out = if (replace) JsonObject() else base.deepCopy()
        patch.entrySet().forEach { (k, v) -> if (v is JsonNull) out.remove(k) else out.add(k, v.deepCopy()) }
        return out
    }

    /** [data] with the [LayoutField.Type.YAW] fields of [types] turned [rotate]° clockwise (a paste or move turned so). */
    fun turned(types: List<LayoutType>, data: JsonObject, rotate: Int): JsonObject {
        if (rotate % 360 == 0 || data.size() == 0) return data
        val yaws = types.flatMap { it.fields }.filter { it.type == LayoutField.Type.YAW && data.get(it.name)?.let { v -> v.isJsonPrimitive && v.asJsonPrimitive.isNumber } == true }
        if (yaws.isEmpty()) return data
        return data.deepCopy().apply { yaws.forEach { f -> addProperty(f.name, Placement.normalize(get(f.name).asFloat + rotate)) } }
    }

    /** What is wrong with [data] for [types]: unknown fields (warnings), bad values, missing required fields. [world] checks area names. */
    fun problems(types: List<LayoutType>, data: JsonObject, world: String? = null): List<Problem> {
        if (types.isEmpty()) return emptyList()
        val fields = LinkedHashMap<String, LayoutField>().apply { types.flatMap { it.fields }.forEach { putIfAbsent(it.name, it) } }
        val out = ArrayList<Problem>()
        data.entrySet().forEach { (k, v) ->
            val f = fields[k]
            if (f == null) out += Problem(k, "unknown field $k (${types.joinToString { it.id }}: ${fields.keys.joinToString().ifEmpty { "no fields" }})", error = false)
            else value(f, v, world)?.let { out += Problem(k, "$k: $it") }
        }
        fields.values.filter { it.isRequired && !data.has(it.name) }.forEach { out += Problem(it.name, "${it.name} is required" + it.description.takeIf(String::isNotEmpty)?.let { d -> " ($d)" }.orEmpty()) }
        return out
    }

    private fun value(f: LayoutField, v: JsonElement, world: String?): String? {
        val p = v as? JsonPrimitive
        fun number() = p?.takeIf { it.isNumber }?.asDouble
        fun range(d: Double) = when {
            f.min != null && d < f.min!! -> "${Point.fmt(d)} is below ${Point.fmt(f.min!!)}"
            f.max != null && d > f.max!! -> "${Point.fmt(d)} is above ${Point.fmt(f.max!!)}"
            else -> null
        }
        return when (f.type) {
            LayoutField.Type.STRING -> if (p?.isString == true) null else "text expected"
            LayoutField.Type.INT -> number().let { if (it == null || it % 1.0 != 0.0) "a whole number expected" else range(it) }
            LayoutField.Type.NUMBER, LayoutField.Type.YAW -> number().let { if (it == null) "a number expected" else range(it) }
            LayoutField.Type.BOOL -> if (p?.isBoolean == true) null else "true or false expected"
            LayoutField.Type.ENUM -> if (p?.isString == true && p.asString in f.values) null else "one of ${f.values.joinToString()} expected"
            LayoutField.Type.COLOR -> if (p?.isString == true && p.asString.matches(COLOR)) null else "a colour \"#rrggbb\" expected"
            LayoutField.Type.AREA -> when {
                p?.isString != true -> "an area name expected"
                world != null && plugin.worlds.area(p.asString, world) == null -> "no area ${p.asString} in $world"
                else -> null
            }
        }
    }

    /** Refuses [data] for [what] when it doesn't fit [types] (field types, choices, ranges, required and unknown fields). */
    fun require(what: String, types: List<LayoutType>, data: JsonObject) {
        val problems = problems(types, data)
        if (problems.isEmpty()) return
        throw ToolError("$what: its data doesn't fit ${types.joinToString { it.id }}:\n" + problems.joinToString("\n") { "  " + it.text } +
            "\nlayout_types lists their fields.")
    }

    /** A note on the namespaced [names] no plugin declares (typos, a plugin that isn't here), or null. */
    fun unknown(target: LayoutType.Target, names: List<String>): String? =
        names.filter { it.matches(LayoutType.ID) && type(target, it) == null }.takeIf { it.isNotEmpty() }
            ?.let { "No plugin declares the ${target.name.lowercase()} type ${it.joinToString()} (layout_types lists them)." }

    // ---- Checks -----------------------------------------------------------------------------------------------------

    /** Every point and area of [world] against the declared types, the plugins' checks included. Main thread. */
    fun check(world: String): Report = Sync.main {
        val w = Bukkit.getWorld(world) ?: throw ToolError("No world '$world'")
        val issues = ArrayList<Issue>()
        val free = sortedMapOf<String, Int>()
        val points = plugin.worlds.points(world)
        var typedPoints = 0
        for (p in points) {
            val types = of(p)
            if (types.isEmpty()) {
                if (p.type.matches(LayoutType.ID)) issues += Issue(false, p.type, "unknown type ${p.type}: no plugin declares it", point = p)
                else free.merge(p.type, 1, Int::plus)
                continue
            }
            typedPoints++
            problems(types, p.data, world).forEach { issues += Issue(it.error, p.type, it.text, it.field, point = p) }
            types.forEach { t -> validate(t, plugin.worlds.api(w, p))?.let { issues += Issue(true, t.id, it, point = p) } }
        }
        val areas = plugin.worlds.areas(world)
        var typedAreas = 0
        for (a in areas) {
            names(a).filter { it.matches(LayoutType.ID) && type(LayoutType.Target.AREA, it) == null }
                .forEach { issues += Issue(false, it, "unknown type $it: no plugin declares it", area = a) }
            val types = of(a)
            if (types.isEmpty()) continue
            typedAreas++
            problems(types, a.data, world).forEach { issues += Issue(it.error, types.joinToString { t -> t.id }, it.text, it.field, area = a) }
            types.forEach { t -> validate(t, plugin.worlds.api(w, a))?.let { issues += Issue(true, t.id, it, area = a) } }
        }
        Report(world, points.size, typedPoints, areas.size, typedAreas, issues.sortedBy { !it.error }, free)
    }

    private fun validate(type: LayoutType, subject: Any): String? = try {
        type.validate(subject)
    } catch (e: Exception) {
        plugin.logger.log(Level.WARNING, "The check of ${type.id} (${owner(type)}) failed", e)
        "its check failed: $e"
    }

    // ---- Output -----------------------------------------------------------------------------------------------------

    /** The declared types as the bridge and `layout_types` give them. */
    fun json(): JsonArray = JsonArray().apply { types().forEach { add(json(it)) } }

    fun json(t: LayoutType) = JsonObject().apply {
        addProperty("target", t.target.name.lowercase())
        addProperty("id", t.id)
        addProperty("label", t.label)
        addProperty("description", t.description)
        addProperty("owner", owner(t))
        t.color?.let { addProperty("color", "#%06x".format(it)) }
        t.icon?.let { addProperty("icon", it) }
        add("fields", JsonArray().apply {
            t.fields.forEach { f ->
                add(JsonObject().apply {
                    addProperty("name", f.name)
                    addProperty("type", f.type.name.lowercase())
                    addProperty("label", f.label)
                    addProperty("description", f.description)
                    addProperty("required", f.isRequired)
                    f.defaultValue?.let { add("default", gson.toJsonTree(it)) }
                    if (f.values.isNotEmpty()) add("values", JsonArray().apply { f.values.forEach { add(it) } })
                    f.min?.let { addProperty("min", it) }
                    f.max?.let { addProperty("max", it) }
                })
            }
        })
    }

    fun describe(): String {
        val types = types()
        if (types.isEmpty()) return "No plugin declares point or area types: points and areas carry free type names and data."
        return LayoutType.Target.entries.joinToString("\n\n") { target ->
            val list = types.filter { it.target == target }
            "${target.name.lowercase().replaceFirstChar { it.uppercase() }} types (${list.size}):" + (if (list.isEmpty()) " none" else "") + list.joinToString("") { t ->
                "\n- ${t.id} \"${t.label}\" (${owner(t)})" + (if (t.description.isNotEmpty()) ": ${t.description}" else "") +
                    t.fields.joinToString("") { f -> "\n    ${field(f)}" }
            }
        }
    }

    private fun field(f: LayoutField): String {
        val type = when (f.type) {
            LayoutField.Type.ENUM -> f.values.joinToString("|")
            else -> f.type.name.lowercase() + when {
                f.min != null && f.max != null -> " ${Point.fmt(f.min!!)}..${Point.fmt(f.max!!)}"
                f.min != null -> " >= ${Point.fmt(f.min!!)}"
                f.max != null -> " <= ${Point.fmt(f.max!!)}"
                else -> ""
            }
        }
        return "${f.name}${if (f.isRequired) "*" else ""}: $type" + (f.defaultValue?.let { " = ${gson.toJson(it)}" } ?: "") +
            (if (f.description.isNotEmpty()) " — ${f.description}" else "")
    }

    companion object {
        private val COLOR = Regex("#[0-9a-fA-F]{6}")

        val instance: Layout get() = Foreman.instance.worlds.layout
    }
}
