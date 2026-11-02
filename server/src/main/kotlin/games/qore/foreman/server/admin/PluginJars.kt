package games.qore.foreman.server.admin

import org.bukkit.Bukkit
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.Plugin
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile
import java.util.jar.Manifest
import java.util.zip.ZipFile

/**
 * Whether the server runs the plugin jars that are on disk. Two sources for what was loaded: the jar each plugin's class
 * loader holds open (its entries as they were when it opened, even once the file is replaced), and the size, time and
 * hash of the file when Foreman first saw the plugin enabled (at its own enable for those already running, then on each
 * enable). Both are compared with the file there now.
 */
object PluginJars {
    class Seen(val path: Path, val size: Long, val modified: Long, val at: Long) {
        @Volatile
        var sha256: String? = null
    }

    enum class State { CURRENT, STALE, GONE, UNKNOWN }

    class Status(val state: State, val text: String?, val changed: List<String> = emptyList())

    /** What a jar on disk has: its entries (name → CRC), its manifest, its plugin.yml name and version. */
    class Disk(val entries: Map<String, Long>, val manifest: Manifest?, val name: String?, val version: String?)

    private val seen = ConcurrentHashMap<String, Seen>()
    private val BUILD_KEYS = Regex("(?i)implementation-version|build|commit|revision|git|timestamp|branch")
    private val SKIP_KEYS = Regex("(?i)build-jdk|created-by|manifest-version|build-tool|built-by|build-os|paperweight|multi-release")

    fun seen(plugin: Plugin): Seen? = seen[plugin.name]

    /** The jar a plugin was loaded from. */
    fun jar(plugin: Plugin): Path? = runCatching { Path.of(plugin.javaClass.protectionDomain.codeSource.location.toURI()) }.getOrNull()

    /** Records the plugin's jar as loaded now; [async] runs the hashing off the server thread. */
    fun record(plugin: Plugin, async: (Runnable) -> Unit) {
        val path = jar(plugin) ?: return
        val s = runCatching { Seen(path, Files.size(path), Files.getLastModifiedTime(path).toMillis(), System.currentTimeMillis()) }.getOrNull() ?: return
        seen[plugin.name] = s
        async(Runnable { s.sha256 = runCatching { sha256(path) }.getOrNull() })
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // A field of the plugin's class loader (Bukkit's PluginClassLoader, Paper's plugin class loaders): its open jar, its manifest.
    private fun <T> loaderField(plugin: Plugin, type: Class<T>): T? {
        val loader = plugin.javaClass.classLoader
        val field = generateSequence<Class<*>>(loader.javaClass) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .firstOrNull { type.isAssignableFrom(it.type) } ?: return null
        return runCatching { field.isAccessible = true; type.cast(field.get(loader)) }.getOrNull()
    }

    /** The entries of the jar the plugin's class loader opened (name → CRC), or null when it can't be reached. */
    fun loadedEntries(plugin: Plugin): Map<String, Long>? = runCatching {
        loaderField(plugin, JarFile::class.java)?.let { jar -> jar.entries().asSequence().filter { !it.isDirectory }.associate { it.name to it.crc } }
    }.getOrNull()

    fun loadedManifest(plugin: Plugin): Manifest? = loaderField(plugin, Manifest::class.java) ?: runCatching { loaderField(plugin, JarFile::class.java)?.manifest }.getOrNull()

    fun disk(path: Path): Disk? = runCatching {
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().filter { !it.isDirectory }.associate { it.name to it.crc }
            val manifest = zip.getEntry("META-INF/MANIFEST.MF")?.let { e -> zip.getInputStream(e).use { Manifest(it) } }
            val description = (zip.getEntry("paper-plugin.yml") ?: zip.getEntry("plugin.yml"))?.let { e ->
                zip.getInputStream(e).use { YamlConfiguration.loadConfiguration(it.reader()) }
            }
            Disk(entries, manifest, description?.getString("name"), description?.getString("version"))
        }
    }.getOrNull()

    /** The manifest's version and build attributes: `Implementation-Version 1.2, Build-Time …`. */
    fun build(manifest: Manifest?): String? = manifest?.mainAttributes?.entries
        ?.map { it.key.toString() to it.value.toString() }
        ?.filter { (k, _) -> BUILD_KEYS.containsMatchIn(k) && !SKIP_KEYS.containsMatchIn(k) }
        ?.take(4)?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ") { (k, v) -> "$k $v" }

    /** The loaded jar against the one on disk now. */
    fun status(plugin: Plugin): Status {
        val path = jar(plugin) ?: return Status(State.UNKNOWN, "its jar isn't known")
        val s = seen[plugin.name]
        if (!Files.exists(path)) return Status(State.GONE, "GONE: its jar was deleted: the server runs the deleted one")
        val size = runCatching { Files.size(path) }.getOrDefault(-1)
        val modified = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0)
        val written = "written ${ServerLog.shortTime(modified)}" + (s?.let { ", loaded ${ServerLog.shortTime(it.at)}" } ?: "")
        val loaded = loadedEntries(plugin)
        if (loaded != null) {
            val disk = disk(path)?.entries ?: return Status(State.UNKNOWN, "its jar on disk can't be read")
            val changed = loaded.keys.filter { it in disk && disk[it] != loaded[it] }
            val added = disk.keys.filter { it !in loaded }
            val removed = loaded.keys.filter { it !in disk }
            if (changed.isEmpty() && added.isEmpty() && removed.isEmpty()) return Status(State.CURRENT, null)
            val counts = listOfNotNull(
                changed.size.takeIf { it > 0 }?.let { "$it changed" },
                added.size.takeIf { it > 0 }?.let { "$it added" },
                removed.size.takeIf { it > 0 }?.let { "$it removed" },
            ).joinToString(", ")
            // Classes by their top-level class (its lambdas and inner classes with it), other files by path.
            fun named(entries: List<String>) = entries.map { if (it.endsWith(".class")) it.substringBefore('$').removeSuffix(".class").replace('/', '.') else it }.distinct()
            return Status(State.STALE, "STALE: the jar on disk differs from the loaded one ($counts entries; $written)", named(changed + added) + named(removed).map { "$it (removed)" })
        }
        s ?: return Status(State.UNKNOWN, "not seen loading by Foreman")
        if (size == s.size && modified == s.modified) return Status(State.CURRENT, null)
        val hash = s.sha256
        if (hash != null && runCatching { sha256(path) }.getOrNull() == hash) return Status(State.CURRENT, null)
        return Status(State.STALE, "STALE: the jar on disk is newer than the loaded one (${size(s.size)} → ${size(size)}; $written)")
    }

    fun size(bytes: Long) = when {
        bytes >= 1 shl 20 -> "%.1f MB".format(java.util.Locale.ROOT, bytes / 1048576.0)
        bytes >= 1 shl 10 -> "${bytes shr 10} KB"
        else -> "$bytes B"
    }

    /** Jars in the plugins folder that no running plugin was loaded from (new versions, ones that failed to load). */
    fun unloaded(): List<Pair<Path, Disk?>> {
        val loaded = Bukkit.getPluginManager().plugins.mapNotNull { jar(it) }.flatMap { listOfNotNull(it.toAbsolutePath().normalize(), real(it)) }.toSet()
        val files = Bukkit.getPluginsFolder().listFiles { f -> f.name.endsWith(".jar") }?.map { it.toPath() }.orEmpty()
        return files.filter { it.toAbsolutePath().normalize() !in loaded && real(it) !in loaded }.sortedBy { it.fileName.toString() }.map { it to disk(it) }
    }

    fun real(path: Path): Path? = runCatching { path.toRealPath() }.getOrNull()
}
