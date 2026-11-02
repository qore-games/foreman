package games.qore.foreman.server.world

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.configuration.file.YamlConfiguration
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The schematic library: `assets/<name>.schem` (subfolders allowed, `houses/small_cottage`) with an optional
 * `<name>.yml` next to it: `description`, `tags`, `size`. Players curate it with `/foreman asset save`. Schematics in
 * WorldEdit's own folder are listed too, as `fawe/<name>` (read only). An index (`.index.yml`) keeps what reading a
 * schematic tells: size, main blocks and a guessed category, so listings stay fast. Folders starting with `_` are left
 * out of listings (`_backup/…`) but can be pasted by name.
 */
class Assets(private val folder: File, private val fawe: File?) {
    data class Asset(
        val name: String, val file: File, val description: String, val tags: List<String>, val size: List<Int>?,
        val category: String?, val blocks: List<String>,
    )

    private val indexFile = File(folder, ".index.yml")
    private val index = YamlConfiguration.loadConfiguration(indexFile)

    private fun roots() = listOfNotNull(folder to "", fawe?.takeIf { it.isDirectory }?.let { it to "fawe/" })

    /** The assets; folders starting with `_` (backups, work in progress) only when [hidden]. */
    fun list(hidden: Boolean = false): List<Asset> = roots().flatMap { (root, prefix) ->
        root.walkTopDown()
            .onEnter { hidden || it == root || !it.name.startsWith("_") }
            .filter { it.isFile && (it.extension == "schem" || it.extension == "schematic") }
            .map { file ->
                val name = prefix + file.relativeTo(root).path.substringBeforeLast('.').replace(File.separatorChar, '/')
                val meta = File(file.parentFile, file.nameWithoutExtension + ".yml")
                val yaml = if (meta.exists()) YamlConfiguration.loadConfiguration(meta) else null
                val indexed = index.getConfigurationSection(key(name))?.takeIf { it.getLong("modified") == file.lastModified() }
                Asset(
                    name, file,
                    yaml?.getString("description") ?: "",
                    yaml?.getStringList("tags") ?: emptyList(),
                    yaml?.getIntegerList("size")?.takeIf { it.size == 3 } ?: indexed?.getIntegerList("size")?.takeIf { it.size == 3 },
                    yaml?.getString("category") ?: indexed?.getString("category"),
                    indexed?.getStringList("blocks") ?: emptyList(),
                )
            }.toList()
    }.sortedBy { it.name }

    fun get(name: String): Asset = list(hidden = true).firstOrNull { it.name == name } ?: throw ToolError("No asset '$name' (see list_assets)")

    fun read(asset: Asset): Clipboard {
        val format = ClipboardFormats.findByFile(asset.file) ?: throw ToolError("Unknown schematic format: ${asset.file.name}")
        return format.getReader(FileInputStream(asset.file)).use { it.read() }
    }

    private fun key(name: String) = name.replace('/', ':').replace('.', '_')

    /** Reads the schematics not indexed yet (or changed): size, main blocks, category. Returns how many were read. */
    fun reindex(all: Boolean = false): Int {
        var n = 0
        for (asset in list()) {
            val k = key(asset.name)
            if (!all && index.getLong("$k.modified") == asset.file.lastModified()) continue
            val clipboard = runCatching { read(asset) }.getOrNull() ?: continue
            val counts = HashMap<Material, Int>()
            for (p in clipboard.region) {
                val m = BukkitAdapter.adapt(clipboard.getBlock(p).blockType)
                if (!m.isAir) counts.merge(m, 1, Int::plus)
            }
            val d = clipboard.dimensions
            index.set("$k.modified", asset.file.lastModified())
            index.set("$k.size", listOf(d.x(), d.y(), d.z()))
            index.set("$k.blocks", counts.entries.sortedByDescending { it.value }.take(6).map { it.key.key.key })
            index.set("$k.category", category(counts, d.x(), d.y(), d.z()))
            n++
        }
        index.save(indexFile)
        return n
    }

    /** A guess from the blocks: tree, plant, rock, ruin or structure. */
    private fun category(counts: Map<Material, Int>, x: Int, y: Int, z: Int): String {
        val total = counts.values.sum().coerceAtLeast(1)
        fun share(p: (Material) -> Boolean) = counts.filterKeys(p).values.sum().toDouble() / total
        // A tree is mostly logs and leaves once the ground and plants saved with it are left out.
        val above = counts.filterKeys { !Blocks.isNaturalGround(it) && !Blocks.isPlant(it) }
        val aboveTotal = above.values.sum().coerceAtLeast(1)
        val wood = above.filterKeys { Tag.LOGS.isTagged(it) }.values.sum().toDouble() / aboveTotal
        val leaves = above.filterKeys { Tag.LEAVES.isTagged(it) }.values.sum().toDouble() / aboveTotal
        return when {
            wood + leaves > 0.7 && leaves > 0.2 -> "tree"
            share { !it.isSolid } > 0.6 || (x <= 3 && z <= 3 && y <= 3) -> "plant"
            share { Blocks.isNaturalGround(it) } > 0.7 -> "rock"
            share { it.name.contains("MOSSY") || it.name.contains("CRACKED") || it == Material.COBBLESTONE } > 0.3 -> "ruin"
            else -> "structure"
        }
    }

    fun save(name: String, clipboard: Clipboard, description: String, tags: List<String>) {
        if (!name.matches(Regex("[a-z0-9_]+(/[a-z0-9_]+)*"))) throw ToolError("Asset names are lowercase a-z, 0-9, _ and / for folders")
        if (name.startsWith("fawe/")) throw ToolError("fawe/ is WorldEdit's folder: save under another name")
        val file = File(folder, "$name.schem")
        file.parentFile.mkdirs()
        BuiltInClipboardFormat.FAST_V3.getWriter(FileOutputStream(file)).use { it.write(clipboard) }

        val dimensions = clipboard.dimensions
        val yaml = YamlConfiguration()
        yaml.set("description", description)
        yaml.set("tags", tags)
        yaml.set("size", listOf(dimensions.x(), dimensions.y(), dimensions.z()))
        yaml.save(File(file.parentFile, file.nameWithoutExtension + ".yml"))
    }
}
