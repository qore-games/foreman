package games.qore.foreman.server.world

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.ForemanWorlds
import games.qore.foreman.server.api.WorldArea
import games.qore.foreman.server.api.WorldDataChangeEvent
import games.qore.foreman.server.api.WorldPoint
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.ServicePriority
import org.bukkit.util.BoundingBox
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A named part of a world: one or more boxes (a single block for a point), a note, and optionally a [kind] (`build`,
 * `road`, `yard`…) that reserves its space: other builds may not go into it, roads go around builds, `check` reports
 * reserved areas that overlap. A [theme] makes it a zone with its own building rules, after the world's.
 */
data class Area(val name: String, val world: String, val boxes: List<Box>, val kind: String? = null, val note: String = "", val theme: String = "") {
    val bounds: Box get() = boxes.reduce { a, b -> a.union(b) }
    val isPoint get() = boxes.size == 1 && boxes[0].min == boxes[0].max
    val reserved get() = kind != null

    fun describe() = name + (kind?.let { " ($it)" } ?: "") + ": " +
        (if (isPoint) "point ${Box.fmt(boxes[0].min)}" else boxes.joinToString(" + ") { it.describe() }) + " in $world" +
        (if (note.isEmpty()) "" else " — $note") + (if (theme.isEmpty()) "" else " [zone]")
}

/** A gameplay point a build recorded at a block: a type and kind (`container:chest`), its build and room. */
data class Point(val world: String, val x: Int, val y: Int, val z: Int, val type: String, val kind: String, val build: String, val room: String) {
    fun describe() = "$type${if (kind.isNotEmpty()) ":$kind" else ""} at [$x, $y, $z] ($build${if (room.isNotEmpty()) ", $room" else ""})"
}

/**
 * What Foreman keeps in each world, in its persistent data container (so it travels with the world file, copies
 * included): the world's [Profile], its [Area]s and its gameplay [Point]s, each as gzipped JSON. Cached; changes are
 * written back at once (on the main thread) and announced with [WorldDataChangeEvent]. `/foreman world export|import`
 * round-trips them as YAML (`plugins/Foreman/worlds/<world>.yml`) for review. Other plugins read them through
 * [ForemanWorlds].
 */
class WorldData(private val plugin: Foreman) : Listener, ForemanWorlds {
    private val areasKey = NamespacedKey(plugin, "areas")
    private val pointsKey = NamespacedKey(plugin, "points")
    private val profileKey = NamespacedKey(plugin, "profile")
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()

    private class Entry(val areas: LinkedHashMap<String, Area>, val points: ArrayList<Point>, var profile: JsonObject)

    private val worlds = HashMap<String, Entry>()

    fun start() {
        Bukkit.getWorlds().forEach(::load)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getServicesManager().register(ForemanWorlds::class.java, this, plugin, ServicePriority.Normal)
    }

    @EventHandler
    fun onLoad(event: WorldLoadEvent) = load(event.world)

    @EventHandler
    fun onUnload(event: WorldUnloadEvent) {
        synchronized(worlds) { worlds.remove(event.world.name) }
    }

    private fun load(world: World) {
        val pdc = world.persistentDataContainer
        val areas = LinkedHashMap<String, Area>()
        (read(pdc, areasKey) as? JsonObject)?.entrySet()?.forEach { (name, e) -> areaOf(name, world.name, e.asJsonObject)?.let { areas[name] = it } }
        val points = ArrayList<Point>()
        (read(pdc, pointsKey) as? JsonArray)?.forEach { e -> pointOf(world.name, e.asJsonObject)?.let { points += it } }
        val profile = read(pdc, profileKey) as? JsonObject ?: JsonObject()
        synchronized(worlds) { worlds[world.name] = Entry(areas, points, profile) }
    }

    private fun entry(world: String): Entry = synchronized(worlds) {
        worlds[world] ?: Bukkit.getWorld(world)?.let { load(it); worlds[world] } ?: throw games.qore.foreman.server.mcp.ToolError("No world '$world'")
    }

    /** Writes the [parts] of [world] back into it and announces them. */
    private fun save(world: String, vararg parts: WorldDataChangeEvent.Part) {
        val e = entry(world)
        val (areas, points, profile) = synchronized(worlds) {
            Triple(JsonObject().apply { e.areas.values.forEach { add(it.name, json(it)) } }, JsonArray().apply { e.points.forEach { add(json(it)) } }, e.profile.deepCopy())
        }
        Sync.main {
            val w = Bukkit.getWorld(world) ?: return@main
            val pdc = w.persistentDataContainer
            fun put(key: NamespacedKey, json: JsonElement, empty: Boolean) = if (empty) pdc.remove(key) else pdc.set(key, PersistentDataType.BYTE_ARRAY, gzip(json))
            for (part in parts) {
                when (part) {
                    WorldDataChangeEvent.Part.AREAS -> put(areasKey, areas, areas.size() == 0)
                    WorldDataChangeEvent.Part.POINTS -> put(pointsKey, points, points.size() == 0)
                    WorldDataChangeEvent.Part.PROFILE -> put(profileKey, profile, profile.size() == 0)
                }
                WorldDataChangeEvent(w, part).callEvent()
            }
        }
    }

    /**
     * Gzipped bytes, not a string: NBT strings are written as modified UTF-8, at most 64 KB, and a world whose data is
     * longer can't be saved at all (blocks included). Data that doesn't read as gzipped JSON is ignored.
     */
    private fun gzip(json: JsonElement): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).writer().use { gson.toJson(json, it) } }.toByteArray()

    private fun read(pdc: PersistentDataContainer, key: NamespacedKey): JsonElement? =
        runCatching { pdc.get(key, PersistentDataType.BYTE_ARRAY)?.let { GZIPInputStream(it.inputStream()).reader().use { r -> JsonParser.parseReader(r) } } }.getOrNull()

    // ---- Areas -------------------------------------------------------------------------------------------------------

    /** Every area of the loaded worlds. */
    val areas: List<Area> get() = synchronized(worlds) { worlds.values.flatMap { it.areas.values } }

    fun areas(world: String): List<Area> = synchronized(worlds) { entry(world).areas.values.toList() }

    /** Areas reserving space ([Area.kind]) in [world]. */
    fun reserved(world: String): List<Area> = areas(world).filter { it.reserved }

    /** The area named [name]: in [world] when given, else the first in any loaded world. */
    fun area(name: String, world: String? = null): Area? =
        if (world != null) synchronized(worlds) { entry(world).areas[name] } else areas.firstOrNull { it.name == name }

    private fun update(world: String, name: String, change: (Area?) -> Area?): Area? {
        val e = entry(world)
        val result = synchronized(worlds) {
            val updated = change(e.areas[name])
            if (updated == null) e.areas.remove(name) else e.areas[name] = updated
            updated
        }
        save(world, WorldDataChangeEvent.Part.AREAS)
        return result
    }

    /** Names a box or point: a new area, or the named one with its box replaced (or added to, when it reserves space). */
    fun mark(name: String, world: String, box: Box, note: String): Area = update(world, name) { old ->
        when {
            old == null -> Area(name, world, listOf(box), note = note)
            old.reserved -> old.copy(boxes = merge(old.boxes, box), note = note.ifEmpty { old.note })
            else -> old.copy(boxes = listOf(box), note = note.ifEmpty { old.note })
        }
    }!!

    /** Reserves [box] for [name] (adding it to the area's boxes), as [kind] (default: its kind, else `build`). */
    fun reserve(name: String, world: String, box: Box, kind: String? = null): Area = update(world, name) { old ->
        if (old == null) Area(name, world, listOf(box), kind ?: "build")
        else old.copy(boxes = merge(old.boxes, box), kind = kind ?: old.kind ?: "build")
    }!!

    /** Stops [name] reserving space; it stays a named area. */
    fun unreserve(name: String, world: String): Boolean = area(name, world)?.reserved == true && update(world, name) { it?.copy(kind = null) } != null

    /** Makes [name] a zone with [theme] (empty: an ordinary area again). */
    fun theme(name: String, world: String, theme: String): Area? = update(world, name) { old -> old?.copy(theme = theme) }

    fun remove(name: String, world: String? = null): Boolean {
        val w = world ?: area(name)?.world ?: return false
        if (area(name, w) == null) return false
        update(w, name) { null }
        return true
    }

    /** The parts of reserved areas other than [except] that [box] overlaps. */
    fun conflicts(world: String, box: Box, except: String?): List<Pair<Area, Box>> =
        reserved(world).filter { it.name != except }.flatMap { a -> a.boxes.mapNotNull { b -> b.intersect(box)?.let { a to it } } }

    /** A box inside an existing one is not added again; one that contains others replaces them. */
    private fun merge(boxes: List<Box>, box: Box): List<Box> {
        if (boxes.any { it.containsBox(box) }) return boxes
        return boxes.filterNot { box.containsBox(it) } + box
    }

    private fun json(a: Area) = JsonObject().apply {
        a.kind?.let { addProperty("kind", it) }
        if (a.note.isNotEmpty()) addProperty("note", a.note)
        if (a.theme.isNotEmpty()) addProperty("theme", a.theme)
        add("boxes", JsonArray().apply {
            a.boxes.forEach { b -> add(JsonArray().apply { listOf(b.min.x(), b.min.y(), b.min.z(), b.max.x(), b.max.y(), b.max.z()).forEach { add(it) } }) }
        })
    }

    private fun areaOf(name: String, world: String, o: JsonObject): Area? {
        val boxes = o.getAsJsonArray("boxes")?.mapNotNull { e ->
            val a = e.asJsonArray.map { it.asInt }.takeIf { it.size == 6 } ?: return@mapNotNull null
            Box.of(BlockVector3.at(a[0], a[1], a[2]), BlockVector3.at(a[3], a[4], a[5]))
        }.orEmpty()
        if (boxes.isEmpty()) return null
        return Area(name, world, boxes, o.get("kind")?.takeIf { !it.isJsonNull }?.asString, o.get("note")?.asString ?: "", o.get("theme")?.asString ?: "")
    }

    // ---- Points ------------------------------------------------------------------------------------------------------

    /** Every point of the loaded worlds. */
    val allPoints: List<Point> get() = synchronized(worlds) { worlds.values.flatMap { it.points } }

    fun points(world: String): List<Point> = synchronized(worlds) { entry(world).points.toList() }

    /** Replaces the points of [build] in [world] with [list]. */
    fun replacePoints(world: String, build: String, list: List<Point>) {
        val e = entry(world)
        synchronized(worlds) {
            e.points.removeIf { it.build == build }
            e.points += list
        }
        save(world, WorldDataChangeEvent.Part.POINTS)
    }

    fun addPoints(world: String, list: List<Point>) {
        val e = entry(world)
        synchronized(worlds) { e.points += list }
        save(world, WorldDataChangeEvent.Part.POINTS)
    }

    /** Removes the points matching [filter] in [world]; returns how many. */
    fun removePoints(world: String, filter: (Point) -> Boolean): Int {
        val e = entry(world)
        val n = synchronized(worlds) { e.points.size.also { e.points.removeIf(filter) } - e.points.size }
        if (n > 0) save(world, WorldDataChangeEvent.Part.POINTS)
        return n
    }

    private fun json(p: Point) = JsonObject().apply {
        add("pos", JsonArray().apply { add(p.x); add(p.y); add(p.z) })
        addProperty("type", p.type)
        if (p.kind.isNotEmpty()) addProperty("kind", p.kind)
        if (p.build.isNotEmpty()) addProperty("build", p.build)
        if (p.room.isNotEmpty()) addProperty("room", p.room)
    }

    private fun pointOf(world: String, o: JsonObject): Point? {
        val pos = o.getAsJsonArray("pos")?.map { it.asInt }?.takeIf { it.size == 3 } ?: return null
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        return Point(world, pos[0], pos[1], pos[2], s("type").ifEmpty { return null }, s("kind"), s("build"), s("room"))
    }

    // ---- API ---------------------------------------------------------------------------------------------------------

    override fun areas(world: World): List<WorldArea> = areas(world.name).map { a ->
        WorldArea(a.name, world, a.boxes.map { b -> BoundingBox(b.min.x().toDouble(), b.min.y().toDouble(), b.min.z().toDouble(), b.max.x() + 1.0, b.max.y() + 1.0, b.max.z() + 1.0) }, a.kind, a.note, a.theme)
    }

    override fun area(world: World, name: String): WorldArea? = areas(world).firstOrNull { it.name == name }

    override fun points(world: World): List<WorldPoint> = points(world.name).map { WorldPoint(world, it.x, it.y, it.z, it.type, it.kind, it.build, it.room) }

    override fun profile(world: World): String = gson.toJson(profile(world.name).json)

    // ---- Profile -----------------------------------------------------------------------------------------------------

    fun profile(world: String): Profile = Profile(synchronized(worlds) { entry(world).profile.deepCopy() })

    /** Merges [patch] into the world's profile (objects merged key by key, null removes a key). */
    fun patchProfile(world: String, patch: JsonObject): Profile {
        val e = entry(world)
        synchronized(worlds) { e.profile = merged(e.profile, patch) }
        save(world, WorldDataChangeEvent.Part.PROFILE)
        return profile(world)
    }

    private fun merged(base: JsonObject, patch: JsonObject): JsonObject {
        val out = base.deepCopy()
        patch.entrySet().forEach { (k, v) ->
            when {
                v is JsonNull -> out.remove(k)
                v is JsonObject && out.get(k) is JsonObject -> out.add(k, merged(out.getAsJsonObject(k), v))
                else -> out.add(k, v.deepCopy())
            }
        }
        return out
    }

    // ---- Files -------------------------------------------------------------------------------------------------------

    fun file(world: String) = File(plugin.dataFolder, "worlds/$world.yml")

    /** Writes the world's profile and areas to [file(world)]. */
    fun export(world: String): File {
        val e = entry(world)
        val yaml = YamlConfiguration()
        yaml.options().setHeader(listOf("Foreman data of world $world: its building profile, areas and points. /foreman world import $world loads it back."))
        yaml.set("profile", toYaml(e.profile))
        yaml.set("areas", toYaml(JsonObject().apply { synchronized(worlds) { e.areas.values.forEach { add(it.name, json(it)) } } }))
        yaml.set("points", toYaml(JsonArray().apply { synchronized(worlds) { e.points.forEach { add(json(it)) } } }))
        val file = file(world)
        file.parentFile.mkdirs()
        yaml.save(file)
        return file
    }

    /** Replaces the world's profile, areas and points with those of [file] (default [file(world)]). */
    fun import(world: String, file: File = file(world)): Triple<Int, Int, Int> {
        if (!file.exists()) throw games.qore.foreman.server.mcp.ToolError("No file ${file.path}")
        val yaml = YamlConfiguration.loadConfiguration(file)
        val profile = (yaml.getConfigurationSection("profile")?.let { fromYaml(it.getValues(false)) } as? JsonObject) ?: JsonObject()
        val areas = LinkedHashMap<String, Area>()
        (yaml.getConfigurationSection("areas")?.let { fromYaml(it.getValues(false)) } as? JsonObject)?.entrySet()?.forEach { (name, v) ->
            (v as? JsonObject)?.let { o -> areaOf(name, world, o)?.let { areas[name] = it } }
        }
        val points = (yaml.getList("points")?.let { fromYaml(it) } as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> pointOf(world, o) } }.orEmpty()
        val e = entry(world)
        synchronized(worlds) {
            e.profile = profile
            e.areas.clear()
            e.areas.putAll(areas)
            e.points.clear()
            e.points += points
        }
        save(world, *WorldDataChangeEvent.Part.entries.toTypedArray())
        return Triple(profile.size(), areas.size, points.size)
    }

    private fun toYaml(e: JsonElement): Any? = when {
        e.isJsonNull -> null
        e.isJsonObject -> LinkedHashMap<String, Any?>().apply { e.asJsonObject.entrySet().forEach { (k, v) -> put(k, toYaml(v)) } }
        e.isJsonArray -> e.asJsonArray.map { toYaml(it) }
        else -> e.asJsonPrimitive.let { p -> if (p.isNumber) p.asNumber.let { n -> if (n.toDouble() % 1.0 == 0.0) n.toLong() else n.toDouble() } else if (p.isBoolean) p.asBoolean else p.asString }
    }

    private fun fromYaml(v: Any?): JsonElement = when (v) {
        null -> JsonNull.INSTANCE
        is org.bukkit.configuration.ConfigurationSection -> fromYaml(v.getValues(false))
        is Map<*, *> -> JsonObject().apply { v.forEach { (k, x) -> add(k.toString(), fromYaml(x)) } }
        is List<*> -> JsonArray().apply { v.forEach { add(fromYaml(it)) } }
        else -> gson.toJsonTree(v)
    }
}
