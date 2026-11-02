package games.qore.foreman.client.locations

import games.qore.foreman.client.config.Config
import games.qore.foreman.api.data.DisplayConfig
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.data.LocationType
import games.qore.foreman.api.data.RenderShape
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.util.UUID
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import kotlin.random.Random

/**
 * Local location sets: lists of positions kept on this computer (`config/foreman/local-locations/<set>.yaml`, the
 * format of the plugins' location lists). The active set is shown and edited as the source `local:<set>`; every change
 * to it is saved.
 */
object LocalSets {
    private val logger = LoggerFactory.getLogger("foreman")
    private const val PREFIX = "local:"
    private val dir = Config.dir.resolve("local-locations")

    private val sets = sortedMapOf<String, MutableMap<String, MutableList<LocationEntry>>>()

    var active: String? = null
        private set

    fun isLocal(source: String) = source.startsWith(PREFIX)
    fun source(set: String) = PREFIX + set

    fun init() {
        Files.createDirectories(dir)
        saved().forEach(::load)
        LocationStore.onChange { source ->
            val set = active ?: return@onChange
            if (source != source(set)) return@onChange
            sets[set] = LocationStore.categories(source).associateWithTo(linkedMapOf()) { LocationStore.entries(source, it).toMutableList() }
            save(set)
        }
    }

    fun names(): Set<String> = sets.keys
    fun categories(set: String): Set<String> = sets[set]?.keys ?: emptySet()
    fun count(set: String) = sets[set]?.values?.sumOf { it.size } ?: 0

    fun saved(): List<String> = dir.listDirectoryEntries("*.yaml").map { it.nameWithoutExtension }.sorted()

    fun create(name: String): Boolean {
        if (name in sets) return false
        sets[name] = linkedMapOf()
        save(name)
        return true
    }

    fun delete(name: String): Boolean {
        if (active == name) use(null)
        val existed = sets.remove(name) != null
        Files.deleteIfExists(dir.resolve("$name.yaml"))
        return existed
    }

    /** Shows [name] (or nothing) as the active set. */
    fun use(name: String?): Boolean {
        if (name != null && name !in sets) return false
        val previous = active
        active = null
        previous?.let { LocationStore.removeSource(source(it)) }
        if (name != null) {
            sets.getValue(name).forEach { (category, entries) -> LocationStore.add(source(name), category, entries, notify = false) }
            active = name
        }
        return true
    }

    fun rename(old: String, new: String): Boolean {
        if (new in sets || old !in sets) return false
        val wasActive = active == old
        if (wasActive) use(null)
        sets[new] = sets.remove(old)!!
        Files.deleteIfExists(dir.resolve("$old.yaml"))
        save(new)
        if (wasActive) use(new)
        return true
    }

    /** Adds a location at the player's position to the active set. */
    fun add(category: String, label: String?, world: String, x: Double, y: Double, z: Double, yaw: Float, pitch: Float): LocationEntry? {
        val set = active ?: return null
        val entry = LocationEntry(UUID.randomUUID().toString().take(8), LocationType.PRECISE, world, x, y, z, yaw, pitch, display(label))
        LocationStore.add(source(set), category, listOf(entry))
        return entry
    }

    fun save(name: String): Boolean = try {
        Files.writeString(dir.resolve("$name.yaml"), LocalSetFormat.write(sets[name] ?: return false))
        true
    } catch (e: Exception) {
        logger.warn("Could not save local set {}", name, e)
        false
    }

    fun load(name: String): Boolean = try {
        val file = dir.resolve("$name.yaml")
        if (!Files.exists(file)) false else {
            sets[name] = LocalSetFormat.read(Files.readString(file)) { display(it) }
            if (active == name) use(name)
            true
        }
    } catch (e: Exception) {
        logger.warn("Could not load local set {}", name, e)
        false
    }

    private fun display(name: String?) = DisplayConfig(Random.nextInt(0x404040, 0xFFFFFF), name, true, RenderShape.CUBE, 1f, 128f)
}

/**
 * The YAML subset of the plugins' location lists: a key per category with either `- "world;x;y;z;yaw;pitch"` items or
 * named entries (`name:` then `key: value` lines and `location: "…"`).
 */
object LocalSetFormat {
    fun write(categories: Map<String, List<LocationEntry>>): String = buildString {
        for ((category, entries) in categories) {
            appendLine("$category:")
            for (e in entries) {
                val loc = "${e.world};${e.x};${e.y};${e.z};${e.yaw};${e.pitch};${e.id}"
                if (e.metadata.isEmpty() && e.display.name == null) {
                    appendLine("  - \"$loc\"")
                } else {
                    appendLine("  ${e.display.name ?: e.id}:")
                    e.metadata.forEach { (k, v) -> appendLine("    $k: $v") }
                    appendLine("    location: \"$loc\"")
                }
            }
        }
    }

    fun read(text: String, display: (String?) -> DisplayConfig): MutableMap<String, MutableList<LocationEntry>> {
        val result = linkedMapOf<String, MutableList<LocationEntry>>()
        var category: MutableList<LocationEntry>? = null
        var name: String? = null
        val meta = linkedMapOf<String, String>()

        fun entry(loc: String, label: String?, metadata: Map<String, String>): LocationEntry {
            val p = loc.trim('"').split(";")
            require(p.size >= 6) { "Invalid location: $loc" }
            return LocationEntry(
                p.getOrNull(6) ?: UUID.randomUUID().toString().take(8), LocationType.PRECISE, LocationEntry.normalizeWorld(p[0]),
                p[1].toDouble(), p[2].toDouble(), p[3].toDouble(), p[4].toFloat(), p[5].toFloat(), display(label), metadata.toMap(),
            )
        }

        for (raw in text.lines()) {
            if (raw.isBlank()) continue
            val indent = raw.length - raw.trimStart().length
            val line = raw.trim()
            when {
                indent == 0 && line.endsWith(":") -> category = result.getOrPut(line.removeSuffix(":")) { mutableListOf() }
                indent in 1..3 && line.startsWith("- ") -> category?.add(entry(line.removePrefix("- ").trim(), null, emptyMap()))
                indent in 1..3 && line.endsWith(":") -> { name = line.removeSuffix(":"); meta.clear() }
                indent >= 4 && line.startsWith("location:") -> category?.add(entry(line.removePrefix("location:").trim(), name, meta))
                indent >= 4 && ": " in line -> meta[line.substringBefore(": ")] = line.substringAfter(": ")
            }
        }
        return result.filterValues { it.isNotEmpty() }.toMutableMap()
    }
}
