package games.qore.foreman.client.locations

import games.qore.foreman.api.data.LocationAction
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.packet.LocationDataPacket
import net.minecraft.world.phys.Vec3

/** Identifies one location: its source, category and id. */
data class LocationRef(val source: String, val category: String, val id: String)

data class Located(val source: String, val category: String, val entry: LocationEntry) {
    val ref get() = LocationRef(source, category, entry.id)
    val pos get() = Vec3(entry.x, entry.y, entry.z)
}

/**
 * The locations the client knows: server sources (sent by plugins) and local sets (`local:<set>`), by source and
 * category, with their colors and visibility. Used from the client thread only.
 */
object LocationStore {
    class Category(var entries: MutableList<LocationEntry> = mutableListOf(), var color: Int? = null, var displayText: String? = null, var hidden: Boolean = false)
    class Source(val categories: LinkedHashMap<String, Category> = linkedMapOf(), var color: Int? = null, var hidden: Boolean = false)

    private val sources = linkedMapOf<String, Source>()
    private val listeners = mutableListOf<(String) -> Unit>()

    /** Sources that appeared since the last call: they start hidden, the player is told about them. */
    private val newSources = mutableListOf<String>()

    /** Called with the source after each committed change (not for live gizmo moves). */
    fun onChange(listener: (String) -> Unit) {
        listeners += listener
    }

    private fun changed(source: String) = listeners.forEach { it(source) }

    private fun source(name: String) = sources.getOrPut(name) {
        val local = LocalSets.isLocal(name)
        if (!local) newSources += name
        Source(hidden = !local)
    }

    private fun category(source: String, name: String) = source(source).categories.getOrPut(name) { Category() }

    private fun normalize(entry: LocationEntry) = entry.copy(
        world = LocationEntry.normalizeWorld(entry.world),
        x = round3(entry.x), y = round3(entry.y), z = round3(entry.z),
        yaw = round3(entry.yaw.toDouble()).toFloat(), pitch = round3(entry.pitch.toDouble()).toFloat(),
    )

    private fun round3(v: Double) = Math.round(v * 1000.0) / 1000.0

    fun apply(packet: LocationDataPacket) {
        when (packet.action) {
            LocationAction.ADD -> add(packet.source, packet.category, packet.locations, notify = false)
            LocationAction.REPLACE -> category(packet.source, packet.category).entries = packet.locations.map(::normalize).toMutableList()
            LocationAction.REMOVE -> removeCategory(packet.source, packet.category)
        }
        if (packet.action != LocationAction.REMOVE) {
            packet.sourceColor?.let { source(packet.source).color = it }
            category(packet.source, packet.category).apply {
                packet.categoryColor?.let { color = it }
                displayText = packet.categoryDisplayText
            }
        }
        changed(packet.source)
    }

    fun drainNewSources(): List<String> = newSources.toList().also { newSources.clear() }

    /** Adds [entries] (ids already there are skipped). */
    fun add(source: String, category: String, entries: List<LocationEntry>, notify: Boolean = true) {
        val list = category(source, category).entries
        val ids = list.mapTo(HashSet()) { it.id }
        entries.filter { it.id !in ids }.forEach { list += normalize(it) }
        if (notify) changed(source)
    }

    fun removeCategory(source: String, category: String) {
        val s = sources[source] ?: return
        s.categories.remove(category)
        if (s.categories.isEmpty()) sources.remove(source)
        changed(source)
    }

    fun removeSource(source: String) {
        if (sources.remove(source) != null) changed(source)
    }

    fun clear() {
        val names = sources.keys.toList()
        sources.clear()
        names.forEach(::changed)
    }

    fun sources(): Set<String> = sources.keys
    fun categories(source: String): Set<String> = sources[source]?.categories?.keys ?: emptySet()
    fun entries(source: String, category: String): List<LocationEntry> = sources[source]?.categories?.get(category)?.entries ?: emptyList()
    fun count(source: String) = sources[source]?.categories?.values?.sumOf { it.entries.size } ?: 0
    fun totalCategories() = sources.values.sumOf { it.categories.size }
    fun totalLocations() = sources.keys.sumOf(::count)

    operator fun get(ref: LocationRef): Located? =
        entries(ref.source, ref.category).firstOrNull { it.id == ref.id }?.let { Located(ref.source, ref.category, it) }

    /** Visible locations in [dimension] (a dimension id like `minecraft:overworld`). */
    fun visible(dimension: String): Sequence<Located> = sources.asSequence()
        .filter { !it.value.hidden }
        .flatMap { (source, s) ->
            s.categories.asSequence().filter { !it.value.hidden }.flatMap { (category, c) ->
                c.entries.asSequence().filter { it.world == dimension }.map { Located(source, category, it) }
            }
        }

    /** Replaces the entry [ref] with [change] of it. [notify] false for live previews (gizmo drags). */
    fun update(ref: LocationRef, notify: Boolean = true, change: (LocationEntry) -> LocationEntry): Boolean {
        val list = sources[ref.source]?.categories?.get(ref.category)?.entries ?: return false
        val i = list.indexOfFirst { it.id == ref.id }
        if (i < 0) return false
        list[i] = normalize(change(list[i]))
        if (notify) changed(ref.source)
        return true
    }

    fun remove(ref: LocationRef): Boolean {
        val s = sources[ref.source] ?: return false
        val c = s.categories[ref.category] ?: return false
        if (!c.entries.removeIf { it.id == ref.id }) return false
        if (c.entries.isEmpty()) s.categories.remove(ref.category)
        if (s.categories.isEmpty()) sources.remove(ref.source)
        changed(ref.source)
        return true
    }

    /** Moves the location [ref] to another source and/or category. Returns its new ref. */
    fun move(ref: LocationRef, source: String, category: String): LocationRef? {
        val located = get(ref) ?: return null
        if (ref.source == source && ref.category == category) return ref
        remove(ref)
        add(source, category, listOf(located.entry))
        return LocationRef(source, category, ref.id)
    }

    /** The color a source is shown with: its own, else its first location's. */
    fun sourceColor(source: String): Int = sources[source]?.color
        ?: sources[source]?.categories?.values?.firstNotNullOfOrNull { it.color ?: it.entries.firstOrNull()?.display?.color } ?: 0x55FF55

    fun categoryColor(source: String, category: String): Int = sources[source]?.categories?.get(category)?.let { it.color ?: it.entries.firstOrNull()?.display?.color }
        ?: sourceColor(source)

    fun isSourceVisible(source: String) = sources[source]?.hidden == false
    fun isCategoryVisible(source: String, category: String) = sources[source]?.categories?.get(category)?.hidden == false

    fun setSourceVisible(source: String, visible: Boolean) {
        sources[source]?.hidden = !visible
    }

    fun setCategoryVisible(source: String, category: String, visible: Boolean) {
        sources[source]?.categories?.get(category)?.hidden = !visible
    }

    fun color(located: Located): Int {
        val s = sources[located.source]
        return s?.categories?.get(located.category)?.color ?: s?.color ?: located.entry.display.color
    }

    fun displayText(source: String, category: String): String? = sources[source]?.categories?.get(category)?.displayText
}
