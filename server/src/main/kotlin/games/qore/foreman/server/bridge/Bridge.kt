package games.qore.foreman.server.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.tools.TestTools
import games.qore.foreman.server.world.Box
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRegisterChannelEvent
import org.bukkit.scheduler.BukkitTask
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Level

/**
 * The `foreman:bridge` plugin channel to the foreman client mod, whose AI runs on the builder's computer.
 *
 * S2C: `hello` (MCP URL, the player's token, and `test`: whether they have `foreman.test`), `world`, `areas`, `points` (on subscribe and when they change), `issues` (after each check), `change` (after each modifying tool call). C2S: `selection`, `subscribe` and
 * `unsubscribe` {layers}. Messages are JSON objects with a `type`; the README has the fields. Without the world tools
 * (no FastAsyncWorldEdit) only `hello` is sent: the token, for the server tools.
 */
class Bridge(private val plugin: Foreman) : Listener {
    companion object {
        const val CHANNEL = "foreman:bridge"
        const val PERMISSION = "foreman.client"
        val LAYERS = setOf("areas", "points", "issues", "changes")
    }

    data class Selection(val world: String, val box: Box)

    val tokens = Tokens(File(plugin.dataFolder, "tokens.yml"))
    /** Bridged players and the layers they subscribed to. */
    private val clients = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val selections = ConcurrentHashMap<UUID, Selection>()
    private val lastSent = HashMap<String, Any>()
    private var poll: BukkitTask? = null

    val mcpUrl: String by lazy {
        plugin.config.getString("mcp.public-url").orEmpty().ifBlank {
            val host = plugin.config.getString("mcp.host", "127.0.0.1")!!.let { if (it == "0.0.0.0" || it.isBlank()) Bukkit.getIp().ifBlank { "127.0.0.1" } else it }
            "http://$host:${plugin.config.getInt("mcp.port", 25599)}/mcp"
        }
    }

    fun start() {
        tokens.load()
        val messenger = Bukkit.getMessenger()
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
        messenger.registerIncomingPluginChannel(plugin, CHANNEL) { _, player, data -> receive(player, data) }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        // Marks, claims and points change from tool calls, commands and generators: watch them.
        if (plugin.worldTools) poll = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { pushChangedLayers() }, 40L, 40L)
        Bukkit.getOnlinePlayers().filter { CHANNEL in it.listeningPluginChannels }.forEach(::hello)
    }

    fun stop() {
        poll?.cancel()
        HandlerList.unregisterAll(this)
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL)
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL)
    }

    /** The selection [player] made with the foreman selection tool. */
    fun selection(player: Player): Selection? = selections[player.uniqueId]

    @EventHandler
    fun onRegister(event: PlayerRegisterChannelEvent) {
        if (event.channel == CHANNEL) hello(event.player)
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        clients.remove(event.player.uniqueId)
        selections.remove(event.player.uniqueId)
    }

    @EventHandler
    fun onWorldChange(event: PlayerChangedWorldEvent) {
        if (clients.containsKey(event.player.uniqueId)) send(event.player, message("world") { addWorld(event.player.world) })
    }

    private fun hello(player: Player) {
        if (!player.hasPermission(PERMISSION)) {
            tokens.revoke(player.uniqueId)
            return
        }
        clients[player.uniqueId] = ConcurrentHashMap.newKeySet()
        send(player, message("hello") {
            addProperty("mcpUrl", mcpUrl)
            addProperty("token", tokens.issue(player))
            // The server's opt-in for the client's packet options of dupe testing (network profiles, inbound drops).
            addProperty("test", player.hasPermission(TestTools.PERMISSION))
            addProperty("version", 1)
            addWorld(player.world)
        })
    }

    /** Revokes [name]'s token; an online client gets a new one at once. */
    fun revoke(name: String): Boolean {
        val owner = tokens.revoke(name) ?: return false
        Bukkit.getPlayer(owner.uuid)?.takeIf { clients.containsKey(it.uniqueId) }?.let(::hello)
        return true
    }

    private fun receive(player: Player, data: ByteArray) {
        if (!clients.containsKey(player.uniqueId) || !plugin.worldTools) return
        try {
            val json = JsonParser.parseString(data.decodeToString()).asJsonObject
            when (json.get("type")?.asString) {
                "selection" -> {
                    if (json.get("clear")?.asBoolean == true) selections.remove(player.uniqueId)
                    else {
                        val world = NamespacedKey.fromString(json.get("dim").asString)?.let(Bukkit::getWorld) ?: player.world
                        selections[player.uniqueId] = Selection(world.name, Box.of(Args.vecOf(json.get("min")), Args.vecOf(json.get("max"))))
                    }
                }
                "subscribe" -> {
                    val layers = json.getAsJsonArray("layers").map { it.asString }.filter { it in LAYERS }
                    clients[player.uniqueId]?.addAll(layers)
                    layers.forEach { layer -> layerMessage(layer)?.let { send(player, it) } }
                }
                "unsubscribe" -> clients[player.uniqueId]?.removeAll(json.getAsJsonArray("layers").map { it.asString }.toSet())
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Bad $CHANNEL message from ${player.name}", e)
        }
    }

    /** Called after every MCP tool call (HTTP thread): pushes check issues and AI changes. */
    fun onToolCall(tool: Tool, args: Args, result: ToolResult, ms: Long, caller: String?) {
        if (result.isError || clients.isEmpty() || !plugin.worldTools) return
        val world = runCatching { plugin.world(args.string("world", null)) }.getOrNull() ?: return
        if (tool.modifies) {
            val box = ToolEvents.changedBox(plugin, args, result)
            val message = message("change") {
                addProperty("label", ToolEvents.label(result, ms).ifEmpty { tool.name })
                caller?.let { addProperty("by", it) }
                addWorld(world)
                box?.let { add("box", box(it)) }
            }
            Bukkit.getScheduler().runTask(plugin, Runnable { broadcast("changes", message) })
        }
    }

    /** Called by every `check` run (also inside build_structure and repair_terrain), with all its issues. */
    fun onCheck(world: World, box: Box, issues: List<games.qore.foreman.server.world.Checks.Issue>) {
        if (clients.isEmpty()) return
        val message = message("issues") {
            addWorld(world)
            add("box", box(box))
            add("issues", JsonArray().apply {
                issues.forEach { i ->
                    add(JsonObject().apply {
                        addProperty("check", i.check)
                        addProperty("severity", if (i.severity == games.qore.foreman.server.world.Checks.Severity.ERROR) "error" else "warning")
                        add("pos", vec(i.x, i.y, i.z))
                        addProperty("text", i.text)
                    })
                }
            })
        }
        Bukkit.getScheduler().runTask(plugin, Runnable { broadcast("issues", message) })
    }

    private fun pushChangedLayers() {
        if (clients.isEmpty()) return
        for (layer in listOf("areas", "points")) {
            val snapshot: Any = if (layer == "areas") plugin.worlds.areas else plugin.worlds.allPoints
            if (lastSent[layer] == snapshot) continue
            lastSent[layer] = snapshot
            layerMessage(layer)?.let { broadcast(layer, it) }
        }
    }

    /** The current content of a pushed layer (issues and changes are sent when they happen). */
    private fun layerMessage(layer: String): JsonObject? = when (layer) {
        "areas" -> message("areas") {
            add("areas", JsonArray().apply {
                plugin.worlds.areas.forEach { a ->
                    add(JsonObject().apply {
                        addProperty("name", a.name)
                        addWorld(a.world)
                        a.kind?.let { addProperty("kind", it) }
                        addProperty("note", a.note)
                        if (a.theme.isNotEmpty()) addProperty("theme", a.theme)
                        add("boxes", JsonArray().apply {
                            a.boxes.forEach { b -> add(JsonArray().apply { listOf(b.min.x(), b.min.y(), b.min.z(), b.max.x(), b.max.y(), b.max.z()).forEach { add(it) } }) }
                        })
                    })
                }
            })
        }
        "points" -> message("points") {
            add("points", JsonArray().apply {
                plugin.worlds.allPoints.forEach { p ->
                    add(JsonObject().apply {
                        addWorld(p.world)
                        add("pos", JsonArray().apply { add(p.x); add(p.y); add(p.z) })
                        addProperty("type", p.type)
                        addProperty("kind", p.kind)
                        addProperty("build", p.build)
                        addProperty("room", p.room)
                    })
                }
            })
        }
        else -> null
    }

    private fun broadcast(layer: String, message: JsonObject) {
        val bytes = message.toString().encodeToByteArray()
        clients.forEach { (uuid, layers) ->
            if (layer in layers) Bukkit.getPlayer(uuid)?.sendPluginMessage(plugin, CHANNEL, bytes)
        }
    }

    private fun send(player: Player, message: JsonObject) = player.sendPluginMessage(plugin, CHANNEL, message.toString().encodeToByteArray())

    private fun message(type: String, fill: JsonObject.() -> Unit) = JsonObject().apply {
        addProperty("type", type)
        fill()
    }

    /** `world` (its name, what tools use) and `dim` (its key, what the client knows). */
    private fun JsonObject.addWorld(world: World) {
        addProperty("world", world.name)
        addProperty("dim", world.key.toString())
    }

    private fun JsonObject.addWorld(name: String) {
        val world = Bukkit.getWorld(name)
        addProperty("world", name)
        addProperty("dim", world?.key?.toString() ?: "minecraft:$name")
    }

    // No WorldEdit type in a signature here: Bukkit reads them all when it registers the listener, with or without it.
    private fun vec(x: Int, y: Int, z: Int) = JsonArray().apply { add(x); add(y); add(z) }

    private fun box(b: Box) = JsonObject().apply {
        add("min", vec(b.min.x(), b.min.y(), b.min.z()))
        add("max", vec(b.max.x(), b.max.y(), b.max.z()))
    }
}
