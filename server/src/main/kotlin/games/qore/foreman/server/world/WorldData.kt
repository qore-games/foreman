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
import games.qore.foreman.server.api.WorldInstance
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
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * A named part of a world: one or more boxes (a single block for a point), a note, and optionally a [kind] (`build`,
 * `road`, `yard`…) that reserves its space: other builds may not go into it, roads go around builds, `check` reports
 * reserved areas that overlap. A [theme] makes it a zone with its own building rules, after the world's. [tags] are
 * free labels; `asset` makes it an asset of the library ([AreaAssets]). A [lock] keeps others' edits out of it ([Locks]).
 * Its kind and tags that name a declared area type ([Layout]) make it of that type, its [data] their field values.
 */
data class Area(
    val name: String, val world: String, val boxes: List<Box>, val kind: String? = null, val note: String = "", val theme: String = "",
    val tags: List<String> = emptyList(), val lock: AreaLock? = null, val data: JsonObject = JsonObject(),
) {
    val bounds: Box get() = boxes.reduce { a, b -> a.union(b) }
    val isPoint get() = boxes.size == 1 && boxes[0].min == boxes[0].max
    val reserved get() = kind != null

    fun describe() = name + (kind?.let { " ($it)" } ?: "") + ": " +
        (if (isPoint) "point ${Box.fmt(boxes[0].min)}" else boxes.joinToString(" + ") { it.describe() }) + " in $world" +
        (if (note.isEmpty()) "" else " — $note") + (if (theme.isEmpty()) "" else " [zone]") + tags.joinToString("") { " #$it" } +
        (lock?.let { " [locked by ${it.describe()}]" } ?: "") + (if (data.size() > 0) " $data" else "")

    companion object {
        /** A plain tag, or a plugin's area type (`ns:name`). */
        private val TAG = Regex("[a-z0-9_-]+|[a-z0-9_.-]+:[a-z0-9_./-]+")

        fun isTag(tag: String) = tag.matches(TAG)

        /** [tags] lowercased and de-duplicated, in order; a tag that isn't `[a-z0-9_-]+` (or an `ns:name` type) is an error. */
        fun tags(tags: List<String>): List<String> = tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().onEach {
            if (!isTag(it)) throw games.qore.foreman.server.mcp.ToolError("Tags are lowercase a-z, 0-9, _ and - (an area type: ns:name): '$it'")
        }
    }
}

/**
 * A gameplay point: an exact position ([x], [y], [z]; one recorded at a block stands on its bottom centre, x.5 y z.5) and
 * the way it faces ([yaw], [pitch]: null when it faces nowhere in particular), a type and kind (`container:chest`), its
 * build and room, its [data] (the field values of its type when a plugin declares it, [Layout]).
 */
data class Point(
    val world: String, val x: Double, val y: Double, val z: Double, val type: String, val kind: String, val build: String, val room: String,
    val yaw: Float? = null, val pitch: Float? = null, val data: JsonObject = JsonObject(),
) {
    /** The block it is in. */
    val block: BlockVector3 get() = BlockVector3.at(Math.floor(x), Math.floor(y), Math.floor(z))

    fun inside(box: Box) = block.let { box.contains(it.x(), it.y(), it.z()) }

    fun describe() = "$type${if (kind.isNotEmpty()) ":$kind" else ""} at [${fmt(x)}, ${fmt(y)}, ${fmt(z)}]" +
        (yaw?.let { " yaw ${fmt(it.toDouble())}" } ?: "") + (pitch?.let { " pitch ${fmt(it.toDouble())}" } ?: "") +
        " ($build${if (room.isNotEmpty()) ", $room" else ""})" + (if (data.size() > 0) " $data" else "")

    companion object {
        /** A point standing on the bottom centre of the block [at]. */
        fun at(world: String, at: BlockVector3, type: String, kind: String, build: String, room: String) =
            Point(world, at.x() + 0.5, at.y().toDouble(), at.z() + 0.5, type, kind, build, room)

        /** A coordinate as short as it reads exactly: 12, 12.5, 12.0625. */
        fun fmt(d: Double): String = java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
    }
}

/**
 * An asset of the library pasted into [world] ([AreaAssets], `paste_asset`): its [id] (`<asset>-<n>`, unique in the
 * world), the [asset] id, the area it came from (area assets), where its bottom centre went ([origin]) turned [rotate]°,
 * the [box] it occupies, the [points] and [areas] it brought (named `<id>/…`), its [entities] (the asset's UUID → the
 * copy's), when and by whom, and the blocks it [written] (null for a paste recorded before Foreman kept them).
 */
data class Instance(
    val id: String, val world: String, val asset: String, val sourceWorld: String?, val sourceArea: String?,
    val origin: BlockVector3, val rotate: Int, val box: Box, val points: List<Point>, val areas: List<String>,
    val entities: Map<UUID, UUID>, val created: Long, val by: String, val written: Written? = null,
) {
    fun describe() = "$id: $asset at ${Box.fmt(origin)}" + (if (rotate != 0) " turned $rotate°" else "") + ", ${box.describe()}" +
        (if (points.isNotEmpty()) ", ${points.size} points" else "") + (if (areas.isNotEmpty()) ", ${areas.size} areas" else "") +
        (if (entities.isNotEmpty()) ", ${entities.size} entities" else "") + " — by $by"
}

/**
 * What Foreman keeps in each world, in its persistent data container (so it travels with the world file, copies
 * included): the world's [Profile], its [Area]s, its gameplay [Point]s and the [Instance]s of assets pasted into it, each
 * as gzipped JSON. Cached; changes are
 * written back at once (on the main thread) and announced with [WorldDataChangeEvent]. `/foreman world export|import`
 * round-trips them as YAML (`plugins/Foreman/worlds/<world>.yml`) for review. Other plugins read them through
 * [ForemanWorlds].
 */
class WorldData(private val plugin: Foreman) : Listener, ForemanWorlds {
    private val areasKey = NamespacedKey(plugin, "areas")
    private val pointsKey = NamespacedKey(plugin, "points")
    private val profileKey = NamespacedKey(plugin, "profile")
    private val instancesKey = NamespacedKey(plugin, "instances")
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().create()
    /** The point and area types plugins declare. */
    val layout = Layout(plugin)

    private class Entry(val areas: LinkedHashMap<String, Area>, val points: ArrayList<Point>, var profile: JsonObject, val instances: LinkedHashMap<String, Instance>)

    private val worlds = HashMap<String, Entry>()

    fun start() {
        Bukkit.getWorlds().forEach(::load)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        Bukkit.getServicesManager().register(ForemanWorlds::class.java, this, plugin, ServicePriority.Normal)
        layout.start()
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
        val instances = LinkedHashMap<String, Instance>()
        (read(pdc, instancesKey) as? JsonArray)?.forEach { e -> instanceOf(world.name, e.asJsonObject)?.let { instances[it.id] = it } }
        synchronized(worlds) { worlds[world.name] = Entry(areas, points, profile, instances) }
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
        val instances = synchronized(worlds) { JsonArray().apply { e.instances.values.forEach { add(json(it)) } } }
        Sync.main {
            val w = Bukkit.getWorld(world) ?: return@main
            val pdc = w.persistentDataContainer
            fun put(key: NamespacedKey, json: JsonElement, empty: Boolean) = if (empty) pdc.remove(key) else pdc.set(key, PersistentDataType.BYTE_ARRAY, gzip(json))
            for (part in parts) {
                when (part) {
                    WorldDataChangeEvent.Part.AREAS -> put(areasKey, areas, areas.size() == 0)
                    WorldDataChangeEvent.Part.POINTS -> put(pointsKey, points, points.size() == 0)
                    WorldDataChangeEvent.Part.PROFILE -> put(profileKey, profile, profile.size() == 0)
                    WorldDataChangeEvent.Part.INSTANCES -> put(instancesKey, instances, instances.size() == 0)
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

    /** Changes the area [name] of [world]; [guarded]: refused when it is or reaches into an area someone else locked ([Locks.guardArea]). */
    private fun update(world: String, name: String, guarded: Boolean = true, change: (Area?) -> Area?): Area? {
        val e = entry(world)
        val result = synchronized(worlds) {
            val old = e.areas[name]
            val updated = change(old)
            if (guarded) listOfNotNull(old, updated).forEach { Locks.guardArea(world, name, it.boxes) }
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

    /** Replaces the tags of [name] with [tags] ([Area.tags] checks them). Null when there is no such area. */
    fun tag(name: String, world: String, tags: List<String>): Area? {
        val clean = Area.tags(tags)
        if (area(name, world) == null) return null
        return update(world, name) { old -> old?.copy(tags = clean) }
    }

    /** Sets the data of [name] (null when there is no such area). */
    fun data(name: String, world: String, data: JsonObject): Area? = if (area(name, world) == null) null else update(world, name) { old -> old?.copy(data = data) }

    /** Puts [area] in its world as it is: a new area, or in place of the one of its name. */
    fun put(area: Area): Area = update(area.world, area.name) { area }!!

    /** Sets who holds [name]'s lock to what [change] makes of the current one ([Locks]): null unlocks. Null when there is no such area. */
    fun lock(name: String, world: String, change: (AreaLock?) -> AreaLock?): Area? {
        if (area(name, world) == null) return null
        return update(world, name, guarded = false) { old -> old?.copy(lock = change(old.lock)) }
    }

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
        if (a.tags.isNotEmpty()) add("tags", JsonArray().apply { a.tags.forEach { add(it) } })
        a.lock?.let { add("locked_by", it.json()) }
        if (a.data.size() > 0) add("data", a.data.deepCopy())
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
        // Tags edited by hand in an exported file: the ones that aren't tags are left out.
        val tags = (o.get("tags") as? JsonArray)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString?.lowercase()?.takeIf(Area::isTag) }?.distinct().orEmpty()
        return Area(name, world, boxes, o.get("kind")?.takeIf { !it.isJsonNull }?.asString, o.get("note")?.asString ?: "", o.get("theme")?.asString ?: "", tags, lock = AreaLock.of(o.get("locked_by")), data = data(o))
    }

    // ---- Points ------------------------------------------------------------------------------------------------------

    /** Every point of the loaded worlds. */
    val allPoints: List<Point> get() = synchronized(worlds) { worlds.values.flatMap { it.points } }

    fun points(world: String): List<Point> = synchronized(worlds) { entry(world).points.toList() }

    /** Replaces the points of [build] in [world] with [list]. */
    fun replacePoints(world: String, build: String, list: List<Point>) {
        val e = entry(world)
        synchronized(worlds) {
            Locks.guardPoints(world, e.points.filter { it.build == build } + list)
            e.points.removeIf { it.build == build }
            e.points += list
        }
        save(world, WorldDataChangeEvent.Part.POINTS)
    }

    fun addPoints(world: String, list: List<Point>) {
        val e = entry(world)
        Locks.guardPoints(world, list)
        synchronized(worlds) { e.points += list }
        save(world, WorldDataChangeEvent.Part.POINTS)
    }

    /** Puts [new] in place of [old] in [world], and in the record of the instance that brought it. */
    fun replacePoint(world: String, old: Point, new: Point) {
        val e = entry(world)
        Locks.guardPoints(world, listOf(old, new))
        val instances = synchronized(worlds) {
            val i = e.points.indexOf(old)
            if (i >= 0) e.points[i] = new else e.points += new
            e.instances.values.filter { old in it.points }.onEach { e.instances[it.id] = it.copy(points = it.points.map { p -> if (p == old) new else p }) }
        }
        save(world, *listOfNotNull(WorldDataChangeEvent.Part.POINTS, WorldDataChangeEvent.Part.INSTANCES.takeIf { instances.isNotEmpty() }).toTypedArray())
    }

    /** Removes the points matching [filter] in [world]; returns how many. */
    fun removePoints(world: String, filter: (Point) -> Boolean): Int {
        val e = entry(world)
        val n = synchronized(worlds) {
            Locks.guardPoints(world, e.points.filter(filter))
            e.points.size.also { e.points.removeIf(filter) } - e.points.size
        }
        if (n > 0) save(world, WorldDataChangeEvent.Part.POINTS)
        return n
    }

    private fun json(p: Point) = JsonObject().apply {
        add("position", JsonArray().apply { add(p.x); add(p.y); add(p.z) })
        p.yaw?.let { addProperty("yaw", it) }
        p.pitch?.let { addProperty("pitch", it) }
        addProperty("type", p.type)
        if (p.kind.isNotEmpty()) addProperty("kind", p.kind)
        if (p.build.isNotEmpty()) addProperty("build", p.build)
        if (p.room.isNotEmpty()) addProperty("room", p.room)
        if (p.data.size() > 0) add("data", p.data.deepCopy())
    }

    /** A stored `data` object, its nulls left out; `{}` when there is none. */
    private fun data(o: JsonObject): JsonObject =
        (o.get("data") as? JsonObject)?.let { d -> JsonObject().apply { d.entrySet().forEach { (k, v) -> if (!v.isJsonNull) add(k, v) } } } ?: JsonObject()

    /** A stored point; one stored at a block (`pos`, whole numbers, before points were exact) stands on its bottom centre. */
    private fun pointOf(world: String, o: JsonObject): Point? {
        fun s(k: String) = o.get(k)?.takeIf { !it.isJsonNull }?.asString.orEmpty()
        fun f(k: String) = o.get(k)?.takeIf { it.isJsonPrimitive }?.asFloat
        val type = s("type").ifEmpty { return null }
        o.getAsJsonArray("position")?.map { it.asDouble }?.takeIf { it.size == 3 }?.let { p ->
            return Point(world, p[0], p[1], p[2], type, s("kind"), s("build"), s("room"), f("yaw"), f("pitch"), data(o))
        }
        val pos = o.getAsJsonArray("pos")?.map { it.asInt }?.takeIf { it.size == 3 } ?: return null
        return Point.at(world, BlockVector3.at(pos[0], pos[1], pos[2]), type, s("kind"), s("build"), s("room")).copy(data = data(o))
    }

    // ---- API ---------------------------------------------------------------------------------------------------------

    override fun areas(world: World): List<WorldArea> = areas(world.name).map { api(world, it) }

    /** [a] as the API gives it, its data with the defaults of its declared types. */
    fun api(world: World, a: Area) = WorldArea(
        a.name, world, a.boxes.map { b -> BoundingBox(b.min.x().toDouble(), b.min.y().toDouble(), b.min.z().toDouble(), b.max.x() + 1.0, b.max.y() + 1.0, b.max.z() + 1.0) },
        a.kind, a.note, a.theme, a.tags, layout.api(layout.of(a), a.data),
    )

    override fun area(world: World, name: String): WorldArea? = areas(world).firstOrNull { it.name == name }

    override fun points(world: World): List<WorldPoint> = points(world.name).map { api(world, it) }

    /** [p] as the API gives it, its data with the defaults of its declared type. */
    fun api(world: World, p: Point) = WorldPoint(world, p.x, p.y, p.z, p.yaw, p.pitch, p.type, p.kind, p.build, p.room, layout.api(layout.of(p), p.data))

    override fun profile(world: World): String = gson.toJson(profile(world.name).json)

    override fun instances(world: World): List<WorldInstance> = instances(world.name).map { i ->
        val b = i.box
        WorldInstance(
            i.id, world, i.asset, i.sourceWorld, i.sourceArea, i.origin.x(), i.origin.y(), i.origin.z(), i.rotate,
            BoundingBox(b.min.x().toDouble(), b.min.y().toDouble(), b.min.z().toDouble(), b.max.x() + 1.0, b.max.y() + 1.0, b.max.z() + 1.0),
            i.points.map { api(world, it) }, i.areas, i.entities, i.created, i.by,
        )
    }

    // ---- Instances ---------------------------------------------------------------------------------------------------

    /** Every instance of the loaded worlds. */
    val allInstances: List<Instance> get() = synchronized(worlds) { worlds.values.flatMap { it.instances.values } }

    fun instances(world: String): List<Instance> = synchronized(worlds) { entry(world).instances.values.toList() }

    fun instance(world: String, id: String): Instance? = synchronized(worlds) { entry(world).instances[id] }

    /** A free instance id in [world] for an asset named [name]: `<name>-<n>`, the lowest n not taken. */
    fun instanceId(world: String, name: String): String {
        val base = name.substringAfterLast('/').lowercase().replace(Regex("[^a-z0-9_-]+"), "_").trim('_').ifEmpty { "asset" }
        val taken = synchronized(worlds) { entry(world).instances.keys.toSet() }
        return generateSequence(1) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }

    /** Adds or replaces [instance]. */
    fun putInstance(instance: Instance) {
        val e = entry(instance.world)
        synchronized(worlds) { e.instances[instance.id] = instance }
        save(instance.world, WorldDataChangeEvent.Part.INSTANCES)
    }

    fun removeInstance(world: String, id: String): Instance? {
        val e = entry(world)
        val removed = synchronized(worlds) { e.instances.remove(id) } ?: return null
        save(world, WorldDataChangeEvent.Part.INSTANCES)
        return removed
    }

    private fun json(i: Instance) = JsonObject().apply {
        addProperty("id", i.id)
        addProperty("asset", i.asset)
        if (i.sourceWorld != null) add("source", JsonObject().apply { addProperty("world", i.sourceWorld); addProperty("area", i.sourceArea) })
        add("origin", JsonArray().apply { add(i.origin.x()); add(i.origin.y()); add(i.origin.z()) })
        addProperty("rotate", i.rotate)
        add("box", JsonArray().apply { listOf(i.box.min.x(), i.box.min.y(), i.box.min.z(), i.box.max.x(), i.box.max.y(), i.box.max.z()).forEach { add(it) } })
        if (i.points.isNotEmpty()) add("points", JsonArray().apply { i.points.forEach { add(json(it)) } })
        if (i.areas.isNotEmpty()) add("areas", JsonArray().apply { i.areas.forEach { add(it) } })
        if (i.entities.isNotEmpty()) add("entities", JsonObject().apply { i.entities.forEach { (k, v) -> addProperty(k.toString(), v.toString()) } })
        addProperty("created", i.created)
        addProperty("by", i.by)
        i.written?.let { addProperty("written", it.encode()) }
    }

    private fun instanceOf(world: String, o: JsonObject): Instance? = runCatching {
        val origin = o.getAsJsonArray("origin").map { it.asInt }
        val box = o.getAsJsonArray("box").map { it.asInt }
        val source = o.get("source") as? JsonObject
        Instance(
            o.get("id").asString, world, o.get("asset").asString, source?.get("world")?.asString, source?.get("area")?.asString,
            BlockVector3.at(origin[0], origin[1], origin[2]), o.get("rotate")?.asInt ?: 0,
            Box.of(BlockVector3.at(box[0], box[1], box[2]), BlockVector3.at(box[3], box[4], box[5])),
            (o.get("points") as? JsonArray)?.mapNotNull { pointOf(world, it.asJsonObject) }.orEmpty(),
            (o.get("areas") as? JsonArray)?.map { it.asString }.orEmpty(),
            (o.get("entities") as? JsonObject)?.entrySet()?.associate { (k, v) -> UUID.fromString(k) to UUID.fromString(v.asString) }.orEmpty(),
            o.get("created")?.asLong ?: 0L, o.get("by")?.asString ?: "",
        ).let { i -> i.copy(written = o.get("written")?.asString?.let { runCatching { Written.decode(i.box, it) }.getOrNull() }) }
    }.getOrNull()

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
        yaml.options().setHeader(listOf("Foreman data of world $world: its building profile, areas, points and asset instances. /foreman world import $world loads it back."))
        yaml.set("profile", toYaml(e.profile))
        yaml.set("areas", toYaml(JsonObject().apply { synchronized(worlds) { e.areas.values.forEach { add(it.name, json(it)) } } }))
        yaml.set("points", toYaml(JsonArray().apply { synchronized(worlds) { e.points.forEach { add(json(it)) } } }))
        yaml.set("instances", toYaml(JsonArray().apply { synchronized(worlds) { e.instances.values.forEach { add(json(it)) } } }))
        val file = file(world)
        file.parentFile.mkdirs()
        yaml.save(file)
        return file
    }

    /** Replaces the world's profile, areas, points and instances with those of [file] (default [file(world)]). */
    fun import(world: String, file: File = file(world)): Triple<Int, Int, Int> {
        if (!file.exists()) throw games.qore.foreman.server.mcp.ToolError("No file ${file.path}")
        val yaml = YamlConfiguration.loadConfiguration(file)
        val profile = (yaml.getConfigurationSection("profile")?.let { fromYaml(it.getValues(false)) } as? JsonObject) ?: JsonObject()
        val areas = LinkedHashMap<String, Area>()
        (yaml.getConfigurationSection("areas")?.let { fromYaml(it.getValues(false)) } as? JsonObject)?.entrySet()?.forEach { (name, v) ->
            (v as? JsonObject)?.let { o -> areaOf(name, world, o)?.let { areas[name] = it } }
        }
        val points = (yaml.getList("points")?.let { fromYaml(it) } as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> pointOf(world, o) } }.orEmpty()
        val instances = (yaml.getList("instances")?.let { fromYaml(it) } as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let { o -> instanceOf(world, o) } }.orEmpty()
        val e = entry(world)
        synchronized(worlds) {
            e.profile = profile
            e.areas.clear()
            e.areas.putAll(areas)
            e.points.clear()
            e.points += points
            e.instances.clear()
            instances.forEach { e.instances[it.id] = it }
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
