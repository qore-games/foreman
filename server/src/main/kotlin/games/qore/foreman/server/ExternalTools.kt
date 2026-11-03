package games.qore.foreman.server

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.api.ExternalTool
import games.qore.foreman.server.api.FurnitureProvider
import games.qore.foreman.server.api.ForemanTools
import games.qore.foreman.server.api.ToolCall
import games.qore.foreman.server.api.ToolInfo
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolRegistry
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.tools.AssetTools
import games.qore.foreman.server.tools.ServerTools
import games.qore.foreman.server.tools.ServerTrace
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.ServicePriority
import java.util.Base64
import java.util.function.Function
import java.util.function.Supplier

/** [ForemanTools] for other plugins: their tools go into the MCP server's registry under their name as provider. */
class ExternalTools(private val plugin: Foreman, private val registry: ToolRegistry) : ForemanTools, Listener {
    private val gson = Gson()
    private val furnitureTools = mutableSetOf<String>()

    fun start() {
        Bukkit.getServicesManager().register(ForemanTools::class.java, this, plugin, ServicePriority.Normal)
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        if (event.plugin !== plugin) {
            registry.removeProvider(event.plugin.name)
            plugin.skills.removeOwner(event.plugin.name)
            ServerTrace.values.remove(event.plugin.name)
        }
    }

    @EventHandler
    fun onServiceRegister(event: ServiceRegisterEvent) {
        if (event.provider.service == FurnitureProvider::class.java) syncFurniture()
    }

    // Unregistered services are still registered during the event: look again a tick later.
    @EventHandler
    fun onServiceUnregister(event: ServiceUnregisterEvent) {
        if (event.provider.service == FurnitureProvider::class.java) Bukkit.getScheduler().runTask(plugin, Runnable { syncFurniture() })
    }

    /** The furniture tools follow the [FurnitureProvider]: published under its plugin's name while there is one. */
    fun syncFurniture() {
        furnitureTools.forEach { registry.remove(it) }
        furnitureTools.clear()
        if (!plugin.worldTools || !Furniture.available) return
        val owner = Furniture.providerName ?: return
        AssetTools.furniture(plugin, owner).forEach { registry.add(it); furnitureTools += it.name }
    }

    private fun check(owner: Plugin, name: String) {
        if (!name.matches(Regex("[a-z0-9_]{2,64}"))) throw IllegalArgumentException("Tool names are lowercase letters, digits and _: '$name'")
        // Foreman's own names stay Foreman's, even while one isn't published (the furniture tools without a provider).
        if (ForemanTool.of(name) != null) throw IllegalArgumentException("Tool '$name' is one of Foreman's own tools: pick another name")
        registry[name]?.let { if (it.provider != owner.name) throw IllegalArgumentException("Tool '$name' is already published by ${it.provider}") }
    }

    override fun registerSkill(owner: Plugin, name: String, description: String, markdown: String) =
        plugin.skills.register(owner, name, description, markdown)

    override fun unregisterSkill(owner: Plugin, name: String) = plugin.skills.unregister(owner, name)

    override fun register(owner: Plugin, tool: ExternalTool) {
        check(owner, tool.name)
        val schema = try {
            JsonParser.parseString(tool.inputSchema).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("Tool '${tool.name}': inputSchema is not a JSON object", e)
        }
        registry.add(Tool(tool.name, tool.description, schema, tool.modifies, owner.name) { args ->
            @Suppress("UNCHECKED_CAST")
            val parsed = gson.fromJson(args.json, Map::class.java) as Map<String, Any?>
            val player = args.caller?.let { Bukkit.getPlayer(it.id) }
            val reply = try {
                tool.handler.apply(ToolCall(args.json.toString(), parsed, player, args.caller?.name))
            } catch (e: Exception) {
                throw ToolError("${owner.name} failed: ${e.message ?: e.javaClass.simpleName}")
            }
            val content = listOf(JsonObject().apply { addProperty("type", "text"); addProperty("text", reply.text) }) +
                reply.images.map { png -> JsonObject().apply { addProperty("type", "image"); addProperty("mimeType", "image/png"); addProperty("data", Base64.getEncoder().encodeToString(png)) } }
            ToolResult(content, reply.isError)
        })
    }

    override fun registerCommand(owner: Plugin, name: String, description: String, command: String, arguments: Map<String, String>, asPlayer: Boolean) {
        check(owner, name)
        val schema = JsonObject().apply {
            addProperty("type", "object")
            add("properties", JsonObject().apply {
                arguments.forEach { (arg, what) -> add(arg, JsonObject().apply { addProperty("type", "string"); addProperty("description", what) }) }
            })
        }
        registry.add(Tool(name, "$description (runs `/$command`)", schema, true, owner.name) { args ->
            val caller = args.caller
            val player = if (asPlayer && caller != null) {
                Bukkit.getPlayer(caller.id) ?: throw ToolError("${caller.name} must be online: this tool runs its command as them")
            } else null
            // From the console, like server_command: a player's token needs foreman.admin.
            if (player == null) ServerTools.admin(args)
            val line = arguments.keys.fold(command) { c, arg -> c.replace("{$arg}", value(args, arg, command.trimEnd().endsWith("{$arg}"))) }.trim()
            // Like server_command: its replies (a player's from what the server sends them) and what it logged meanwhile.
            ServerTools.report(line, player, ServerTools.run(line, player, 250, null))
        })
    }

    /** [arg]'s value in a command: one word, so it can't add arguments, unless its placeholder ends the command. */
    private fun value(args: Args, arg: String, last: Boolean): String {
        if (!args.has(arg)) return ""
        val value = args.string(arg)
        if (value.any { it.isISOControl() } || (!last && value.any { it.isWhitespace() })) {
            throw ToolError("'$arg' must be ${if (last) "one line" else "one word"}")
        }
        return value
    }

    override fun unregister(owner: Plugin, name: String) {
        if (registry[name]?.provider == owner.name) registry.remove(name)
    }

    override fun registerTraceValues(owner: Plugin, values: Function<Player, Map<String, Any?>?>) {
        ServerTrace.values[owner.name] = values
    }

    override fun unregisterTraceValues(owner: Plugin) {
        ServerTrace.values.remove(owner.name)
    }

    override fun tools(): List<ToolInfo> = registry.all().map { ToolInfo(it.name, it.provider, it.description, it.modifies) }

    override fun <T> onMainThread(block: Supplier<T>): T = Sync.main { block.get() }
}
