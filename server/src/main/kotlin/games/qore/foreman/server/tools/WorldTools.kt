package games.qore.foreman.server.tools

import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.world.Assets
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.WorldData
import java.io.File

/**
 * Everything that needs FastAsyncWorldEdit, started only when it runs: each world's data (areas, points and profile,
 * the ForemanWorlds service), the schematic library, chunk loading and the world tools. Nothing else loads a class of
 * this package's world side, so the plugin enables without WorldEdit.
 */
object WorldTools {
    fun start(plugin: Foreman): List<Tool> {
        if (!File(plugin.dataFolder, "styles").exists()) plugin.saveResource("styles/example.md", false)
        if (!File(plugin.dataFolder, "catalog/furniture.yml").exists()) plugin.saveResource("catalog/furniture.yml", false)
        // The server's own building presets, commented examples to start from: Foreman's stay in the jar (a copy of them
        // here would hide their updates).
        for (name in listOf("buildings.yml", "rooms.yml")) File(plugin.dataFolder, "catalog/$name").takeIf { !it.exists() }?.let { file ->
            file.parentFile.mkdirs()
            plugin.getResource("templates/$name")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        plugin.assets = Assets(File(plugin.dataFolder, "assets").apply { mkdirs() }, File(plugin.dataFolder.parentFile, "FastAsyncWorldEdit/schematics"))
        plugin.worlds = WorldData(plugin).also { it.start() }
        Chunks.start(plugin)
        return InspectTools.all(plugin) + QualityTools.all(plugin) + EditTools.all(plugin) + TerrainTools.all(plugin) + AssetTools.all(plugin) +
            ModelTools.all(plugin) + BuildTools.all(plugin) + RepairTools.all(plugin) + LandTools.all(plugin) + WorldEditTool.all(plugin)
    }

    fun stop() {
        Chunks.release()
    }
}
