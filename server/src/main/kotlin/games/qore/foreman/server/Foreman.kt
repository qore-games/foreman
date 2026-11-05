package games.qore.foreman.server

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.admin.ServerLog
import games.qore.foreman.server.admin.TestPlayers
import games.qore.foreman.server.admin.ServerWatch
import games.qore.foreman.server.bridge.Bridge
import games.qore.foreman.server.bridge.Relay
import games.qore.foreman.server.mcp.Audit
import games.qore.foreman.server.mcp.McpDispatcher
import games.qore.foreman.server.integrations.Integrations
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.McpServer
import games.qore.foreman.server.mcp.ToolRegistry
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.tools.LessonTools
import games.qore.foreman.server.skills.Skills
import games.qore.foreman.server.tools.ServerTools
import games.qore.foreman.server.tools.PacketTools
import games.qore.foreman.server.tools.TestPlayerTools
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
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

class Foreman : JavaPlugin() {
    companion object {
        lateinit var instance: Foreman
        /** The MCP token when `mcp.token` isn't set, made at the first start. */
        const val TOKEN_FILE = "mcp-token.txt"
        /** Where the MCP server listens and its token, written while it runs, for scripts, launchers and agents. */
        const val MCP_FILE = "mcp.json"
        /** Every tool call, a JSON line each ([Audit]). */
        const val AUDIT_FILE = "audit.log"
    }

    data class Limits(val maxEditBlocks: Int, val maxReadBlocks: Int, val maxRenderBlocks: Int)

    lateinit var limits: Limits
    /** What this server offers (`preset`, `features:`), read before the other plugins enable. */
    lateinit var features: Features
        private set
    /** FastAsyncWorldEdit runs and the world feature is on: the world tools are there, with the world data, assets and overlays they use. */
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
    /** The MCP server, once listening. */
    var mcp: McpServer? = null
        private set
    /** Test players (let through the whitelist, elevated on demand), when the test-players feature is on. */
    var testPlayers: TestPlayers? = null
        private set
    /** The relay over the game connection, when the relay feature is on. */
    var relay: Relay? = null
        private set
    /** What this server wrote to [MCP_FILE], so it removes only its own. */
    private var mcpFile: String? = null
    /** The foreman client bridge (overlays, per-player tokens, client selections). */
    var bridge: Bridge? = null
    /** Every MCP tool: Foreman's and those other plugins publish, of the features that are on. */
    val tools = ToolRegistry { features.allows(it) }
    lateinit var externalTools: ExternalTools
        private set

    // As early as possible: the server log is kept from here (the other plugins' enable included), for the server tools.
    override fun onLoad() {
        saveDefaultConfig()
        features = Features.load(config, logger)
        if (features[Feature.DIAGNOSTICS] || features[Feature.CONSOLE]) ServerLog.start()
    }

    override fun onEnable() {
        instance = this
        logger.info("Preset ${features.preset}: ${features.describe()} (config.yml, /foreman status)")
        worldTools = features[Feature.WORLD] && Bukkit.getPluginManager().isPluginEnabled("FastAsyncWorldEdit")
        if (features[Feature.WORLD] && !worldTools) logger.info("FastAsyncWorldEdit isn't installed: the world tools are off, the server tools and plugins' tools are on")

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

        val builtin = (if (worldTools) WorldTools.start(this) else emptyList()) + LessonTools.all(this) + SkillTools.all(this) + ServerTools.all(this) + TestTools.all(this) + TestPlayerTools.all(this) + PacketTools.all(this)
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
        if (features[Feature.DIAGNOSTICS] || features[Feature.CONSOLE]) ServerWatch(this).start()
        if (features[Feature.TEST_PLAYERS]) testPlayers = TestPlayers(this).also { it.start() }
        if (features[Feature.MCP] || features[Feature.RELAY]) startAccess(instructions, prompts)
        getCommand("foreman")?.let { ForemanCommand(this).also { c -> it.setExecutor(c); it.tabCompleter = c } }
    }

    /**
     * How the AI reaches the tools: the dispatcher both ways share (every call audited), the MCP server, the relay over
     * the game connection, then the mod's bridge, which gives players the MCP address (the port it took) and their
     * tokens, or tells them to relay.
     */
    private fun startAccess(instructions: String, prompts: Prompts) {
        val bridge = Bridge(this)
        this.bridge = bridge
        val audit = Audit(File(dataFolder, AUDIT_FILE))
        val dispatcher = McpDispatcher(
            logger,
            {
                listOf(
                    instructions,
                    skills.index(),
                    lessons.text().takeIf { it.isNotEmpty() }?.let { "Lessons learned on this server (follow them; add yours with `learn`):\n$it" }.orEmpty(),
                ).filter { it.isNotEmpty() }.joinToString("\n\n")
            },
            tools,
            prompts::all,
        ) { tool, args, result, ms, caller, via ->
            audit.call(via, caller, tool.name, args.json, !result.isError, ms)
            if (via == "relay") logger.info("Relay: ${caller?.name} called ${tool.name} (${if (result.isError) "error" else "ok"}, $ms ms)")
            bridge.onToolCall(tool, args, result, ms, caller?.name)
            if (!tool.modifies) return@McpDispatcher
            val text = result.content.firstOrNull()?.get("text")?.asString?.lineSequence()?.firstOrNull().orEmpty()
            Bukkit.getScheduler().runTask(this, Runnable {
                Bukkit.getOnlinePlayers().filter { it.hasPermission("foreman.watch") }.forEach {
                    val message = Component.text("AI${caller?.let { c -> " (${c.name})" }.orEmpty()} ${tool.name} (${ms}ms): ${text.take(160)}")
                    if (result.isError) Msg.warning(it, message) else Msg.info(it, message)
                }
            })
        }
        if (features[Feature.MCP]) startMcp(bridge, dispatcher)
        if (features[Feature.RELAY]) relay = Relay(this, dispatcher, config.getInt("relay.calls-per-minute", 120).coerceAtLeast(1)).also { it.start() }
        bridge.start()
    }

    private fun startMcp(bridge: Bridge, dispatcher: McpDispatcher) {
        val token = Secrets.mcpToken(config.getString("mcp.token", "")!!, File(dataFolder, TOKEN_FILE))
        val mcp = McpServer(
            logger,
            config.getString("mcp.host", "127.0.0.1")!!,
            config.getInt("mcp.port", 25599),
            config.getBoolean("mcp.strict-port", false),
            token,
            { bearer ->
                // A player who lost foreman.client can't use their token any more (it's revoked when they next join).
                bridge.tokens.owner(bearer)?.takeIf { Bukkit.getPlayer(it.uuid)?.hasPermission(Bridge.PERMISSION) != false }
                    ?.let { Caller(it.uuid, it.name) }
            },
            dispatcher,
        ).also { it.start() }
        this.mcp = mcp
        writeMcpFile(mcp, token)
        if (config.getString("mcp.token").isNullOrBlank()) logger.info("MCP clients send the token in plugins/Foreman/$TOKEN_FILE")
        val publicUrl = config.getString("mcp.public-url").orEmpty()
        if (mcp.port != config.getInt("mcp.port", 25599) && publicUrl.isNotBlank() && "{port}" !in publicUrl) {
            logger.warning("mcp.public-url ($publicUrl) doesn't follow the port the MCP server took: put {port} in it, or set mcp.strict-port: true")
        }
        if (features[Feature.CONSOLE] && config.getString("mcp.host", "127.0.0.1")!!.let { it != "127.0.0.1" && it != "localhost" && it != "::1" }) {
            logger.warning("The MCP server listens beyond this machine (mcp.host) with the console feature on: a token there is console access. Keep it behind TLS or a VPN, or turn console off (preset production)")
        }
    }

    override fun onDisable() {
        mcp?.stop()
        relay?.stop()
        PacketTools.stop()
        testPlayers?.stop()
        removeMcpFile()
        bridge?.stop()
        if (worldTools) WorldTools.stop()
        ServerLog.stop()
    }

    /**
     * Writes [MCP_FILE], readable by the server's user only: the MCP server's address from this machine and its token,
     * for scripts, launchers and agents, with what tells this server from another (port, pid, folder) and a stale file
     * from a live one (pid, started_at).
     */
    private fun writeMcpFile(mcp: McpServer, token: String) {
        val json = JsonObject().apply {
            addProperty("url", mcp.url)
            addProperty("token", token)
            addProperty("port", mcp.port)
            addProperty("pid", ProcessHandle.current().pid())
            addProperty("server", Path.of("").toAbsolutePath().fileName?.toString().orEmpty())
            addProperty("version", pluginMeta.version)
            addProperty("started_at", Instant.now().truncatedTo(ChronoUnit.SECONDS).toString())
        }
        val text = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(json) + "\n"
        runCatching { Secrets.write(File(dataFolder, MCP_FILE), text) }
            .onSuccess { mcpFile = text }
            .onFailure { logger.warning("Could not write $MCP_FILE: $it") }
    }

    private fun removeMcpFile() {
        val file = File(dataFolder, MCP_FILE)
        if (mcpFile != null && runCatching { file.readText() }.getOrNull() == mcpFile) file.delete()
        mcpFile = null
    }

    /** The named world, or the configured default, or the world of the first online player, or the first world. */
    fun world(name: String?): World {
        if (name != null) return Bukkit.getWorld(name) ?: throw ToolError("No world '$name'")
        config.getString("default-world")?.takeIf { it.isNotEmpty() }?.let { Bukkit.getWorld(it) }?.let { return it }
        return Bukkit.getOnlinePlayers().firstOrNull()?.world ?: Bukkit.getWorlds().first()
    }
}
