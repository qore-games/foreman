package games.qore.foreman.server

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.admin.ServerLog
import games.qore.foreman.server.admin.ServerWatch
import games.qore.foreman.server.bridge.Bridge
import games.qore.foreman.server.integrations.Integrations
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.McpServer
import games.qore.foreman.server.mcp.ToolRegistry
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.tools.LessonTools
import games.qore.foreman.server.skills.Skills
import games.qore.foreman.server.tools.ServerTools
import games.qore.foreman.server.tools.TestTools
import games.qore.foreman.server.tools.SkillTools
import games.qore.foreman.server.tools.WorldTools
import games.qore.foreman.server.world.Assets
import games.qore.foreman.server.world.WorldData
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.plugin.java.JavaPlugin
import java.io.File

class Foreman : JavaPlugin() {
    companion object {
        lateinit var instance: Foreman
        /** The MCP token when `mcp.token` isn't set, made at the first start. */
        const val TOKEN_FILE = "mcp-token.txt"
    }

    data class Limits(val maxEditBlocks: Int, val maxReadBlocks: Int, val maxRenderBlocks: Int)

    lateinit var limits: Limits
    /** FastAsyncWorldEdit runs: the world tools are there, with the world data, assets and overlays they use. */
    var worldTools = false
        private set
    /** Each world's profile and areas, kept in the world (with the world tools only). */
    lateinit var worlds: WorldData
    /** The schematic library (with the world tools only). */
    lateinit var assets: Assets
    lateinit var lessons: Lessons
    /** Skills: Foreman's own, other plugins', the server's. */
    lateinit var skills: Skills
    lateinit var stylesFolder: File
    private var server: McpServer? = null
    /** The foreman client bridge (overlays, per-player tokens, client selections). */
    var bridge: Bridge? = null
    /** Every MCP tool: Foreman's and those other plugins publish. */
    val tools = ToolRegistry()
    lateinit var externalTools: ExternalTools
        private set

    // As early as possible: the server log is kept from here (the other plugins' enable included).
    override fun onLoad() {
        ServerLog.start()
    }

    override fun onEnable() {
        instance = this
        saveDefaultConfig()
        worldTools = Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")
        if (!worldTools) logger.info("FastAsyncWorldEdit isn't installed: the world tools are off, the server tools and plugins' tools are on")

        limits = Limits(
            config.getInt("limits.max-edit-blocks", 1_000_000),
            config.getInt("limits.max-read-blocks", 20_000),
            config.getInt("limits.max-render-blocks", 800_000),
        )
        stylesFolder = File(dataFolder, "styles")
        lessons = Lessons(File(dataFolder, "lessons.md"))
        skills = Skills(this)
        val prompts = Prompts(this, lessons)
        fun text(resource: String) = getResource(resource)!!.readAllBytes().decodeToString()
        val instructions = if (worldTools) text("instructions-world.md") + "\n" + text("instructions.md") else text("instructions.md")

        val builtin = (if (worldTools) WorldTools.start(this) else emptyList()) + LessonTools.all(this) + SkillTools.all(this) + ServerTools.all(this) + TestTools.all(this)
        builtin.forEach(tools::add)
        // Every built-in tool is a ForemanTool (the client knows those), and every ForemanTool is built in or published
        // on its condition (`requires`: FastAsyncWorldEdit, a furniture plugin): a mismatch is a tool added on one side only.
        val unnamed = builtin.map { it.name }.filter { ForemanTool.of(it) == null }
        val unbuilt = ForemanTool.entries.filter { it.requires == null && builtin.none { t -> t.name == it.id } }
        if (unnamed.isNotEmpty() || unbuilt.isNotEmpty()) logger.warning(
            "Built-in tools and ForemanTool differ: not in ForemanTool $unnamed, ForemanTool without a tool ${unbuilt.map { it.id }}",
        )
        externalTools = ExternalTools(this, tools).also { it.start() }
        Integrations(this).start()
        externalTools.syncFurniture()
        ServerWatch(this).start()
        val bridge = Bridge(this).also { it.start() }
        this.bridge = bridge
        server = McpServer(
            logger,
            config.getString("mcp.host", "127.0.0.1")!!,
            config.getInt("mcp.port", 25599),
            Secrets.mcpToken(config.getString("mcp.token", "")!!, File(dataFolder, TOKEN_FILE)),
            { token ->
                // A player who lost foreman.client can't use their token any more (it's revoked when they next join).
                bridge.tokens.owner(token)?.takeIf { Bukkit.getPlayer(it.uuid)?.hasPermission(Bridge.PERMISSION) != false }
                    ?.let { Caller(it.uuid, it.name) }
            },
            {
                listOf(
                    instructions,
                    skills.index(),
                    lessons.text().takeIf { it.isNotEmpty() }?.let { "Lessons learned on this server (follow them; add yours with `learn`):\n$it" }.orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString("\n\n")
            },
            tools,
            prompts::all,
        ) { tool, args, result, ms, caller ->
            bridge.onToolCall(tool, args, result, ms, caller?.name)
            if (!tool.modifies) return@McpServer
            val text = result.content.firstOrNull()?.get("text")?.asString?.lineSequence()?.firstOrNull().orEmpty()
            Bukkit.getScheduler().runTask(this, Runnable {
                Bukkit.getOnlinePlayers().filter { it.hasPermission("foreman.watch") }.forEach {
                    val message = Component.text("AI${caller?.let { c -> " (${c.name})" }.orEmpty()} ${tool.name} (${ms}ms): ${text.take(160)}")
                    if (result.isError) Msg.warning(it, message) else Msg.info(it, message)
                }
            })
        }.also { it.start() }
        if (config.getString("mcp.token").isNullOrBlank()) logger.info("MCP clients send the token in plugins/Foreman/$TOKEN_FILE")

        getCommand("foreman")?.let { ForemanCommand(this).also { c -> it.setExecutor(c); it.tabCompleter = c } }
    }

    override fun onDisable() {
        server?.stop()
        bridge?.stop()
        if (worldTools) WorldTools.stop()
        ServerLog.stop()
    }

    /** The named world, or the configured default, or the world of the first online player, or the first world. */
    fun world(name: String?): World {
        if (name != null) return Bukkit.getWorld(name) ?: throw ToolError("No world '$name'")
        config.getString("default-world")?.takeIf { it.isNotEmpty() }?.let { Bukkit.getWorld(it) }?.let { return it }
        return Bukkit.getOnlinePlayers().firstOrNull()?.world ?: Bukkit.getWorlds().first()
    }
}
