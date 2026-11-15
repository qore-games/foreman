package games.qore.foreman.server.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Feature
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.tools.LibraryTools
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
 * S2C: `hello` (MCP URL and the player's token with the MCP server, `relay` with the relay, `test`: whether they have
 * `foreman.test` and testing is on, `features`: the server's), `world`, `areas`, `points`, `instances`, `layout` (the declared point and area types; on subscribe and when they change), `issues` (after each check), `change`
 * (after each modifying tool call), and to a player subscribed to `objects` (an editor showing them) `object_kinds` (on
 * subscribe and when they change) and `objects` (those near them, when they change: looked at every second, at once
 * after a change made through Foreman). C2S: `selection`, `subscribe` and `unsubscribe` {layers}. With the panels feature
 * ([PanelFeed]): C2S `hello` (what the mod draws), `panel_open`, `panel_close` and `panel_resolve`, S2C `panel`,
 * `panel_resolved`, and `relations` to a player subscribed to them. Messages are JSON objects with a `type`; the README
 * has the fields. Without the world tools only `hello` and the panels are sent: the token, for the server tools. It runs
 * with the MCP server or the relay: how to reach the tools is what it gives.
 */
class Bridge(private val plugin: Foreman) : Listener {
    companion object {
        const val CHANNEL = "foreman:bridge"
        const val PERMISSION = "foreman.client"
        val LAYERS = setOf("areas", "points", "instances", "layout", "issues", "changes", "objects", "relations")
        /** The objects sent to an editor: those within this many blocks of them, the nearest this many, outlines of at most this many points. */
        private const val OBJECTS_RADIUS = 64.0
        private const val OBJECTS_MAX = 200
        private const val OUTLINE_POINTS = 48
        private const val OBJECTS_EVERY_MS = 1000L
    }

    /** What an editor subscribed to `objects` was last sent: the kinds' version, the objects (as sent), when, after which change. */
    private class ObjectFeed(var kinds: Int = -1, var objects: String = "", var at: Long = 0, var changes: Int = -1, var actions: String = "")

    data class Selection(val world: String, val box: Box)

    val tokens = Tokens(File(plugin.dataFolder, "tokens.yml"))
    /** Bridged players and the layers they subscribed to. */
    private val clients = ConcurrentHashMap<UUID, MutableSet<String>>()
    private val selections = ConcurrentHashMap<UUID, Selection>()
    private val lastSent = HashMap<String, Any>()
    private var poll: BukkitTask? = null
    private var objectPoll: BukkitTask? = null
    private val feeds = ConcurrentHashMap<UUID, ObjectFeed>()
    /** The panels and relations the mod shows, with the panels feature. */
    private var panels: PanelFeed? = null

    /** The URL the mod gives its AI: `mcp.public-url` (`{port}` the port the MCP server took), else its own address. */
    val mcpUrl: String get() {
        val port = plugin.mcp?.port ?: plugin.config.getInt("mcp.port", 25599)
        return plugin.config.getString("mcp.public-url").orEmpty().replace("{port}", port.toString()).ifBlank {
            val host = plugin.config.getString("mcp.host", "127.0.0.1")!!.let { if (it == "0.0.0.0" || it.isBlank()) Bukkit.getIp().ifBlank { "127.0.0.1" } else it }
            "http://$host:$port/mcp"
        }
    }

    fun start() {
        tokens.load()
        val messenger = Bukkit.getMessenger()
        messenger.registerOutgoingPluginChannel(plugin, CHANNEL)
        messenger.registerIncomingPluginChannel(plugin, CHANNEL) { _, player, data -> receive(player, data) }
        Bukkit.getPluginManager().registerEvents(this, plugin)
        panels = plugin.panels?.let { PanelFeed(plugin, it, ::send).also { f -> f.start() } }
        // Marks, claims and points change from tool calls, commands and generators: watch them.
        if (plugin.worldTools) poll = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { pushChangedLayers() }, 40L, 40L)
        Bukkit.getOnlinePlayers().filter { CHANNEL in it.listeningPluginChannels }.forEach(::hello)
    }

    fun stop() {
        poll?.cancel()
        objectPoll?.cancel()
        panels?.stop()
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
    fun onQuit(event: PlayerQuitEvent) = drop(event.player)

    /** Forgets [player]'s client: its layers, selection, objects and panels. */
    private fun drop(player: Player) {
        clients.remove(player.uniqueId)
        selections.remove(player.uniqueId)
        feeds.remove(player.uniqueId)
        panels?.quit(player)
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
            // Where its AI reaches the tools: the MCP server with the player's token, and/or the relay over this connection.
            // The mod's own pages call with a token of their own.
            if (plugin.mcp != null) {
                addProperty("mcpUrl", mcpUrl)
                addProperty("token", tokens.issue(player))
                addProperty("editorToken", tokens.editor(player))
            }
            addProperty("relay", plugin.relay != null)
            // The server's opt-in for the client's packet options of dupe testing (network profiles, inbound drops).
            addProperty("test", plugin.features[Feature.TESTING] && player.hasPermission(TestTools.PERMISSION))
            // What the server offers, for the hub.
            add("features", JsonArray().apply { Feature.entries.filter { plugin.features[it] }.forEach { add(it.key) } })
            addProperty("version", 1)
            // The panels protocol: the mod answers with its own hello (what it draws).
            if (panels != null) addProperty("panels_version", PanelFeed.VERSION)
            addWorld(player.world)
        })
    }

    /**
     * [player]'s permissions changed (a test player elevated or demoted): a mod listening on the channel is greeted when
     * they may use Foreman now, dropped when they may not any more, without rejoining.
     */
    fun permissionsChanged(player: Player) {
        val allowed = player.hasPermission(PERMISSION)
        when {
            allowed && !clients.containsKey(player.uniqueId) && CHANNEL in player.listeningPluginChannels -> hello(player)
            !allowed && clients.containsKey(player.uniqueId) -> {
                drop(player)
                tokens.revoke(player.uniqueId)
            }
        }
    }

    /** Revokes [name]'s token; an online client gets a new one at once. */
    fun revoke(name: String): Boolean {
        val owner = tokens.revoke(name) ?: return false
        Bukkit.getPlayer(owner.uuid)?.takeIf { clients.containsKey(it.uniqueId) }?.let(::hello)
        return true
    }

    private fun receive(player: Player, data: ByteArray) {
        if (!clients.containsKey(player.uniqueId)) return
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, Runnable { receive(player, data) })
            return
        }
        try {
            val json = JsonParser.parseString(data.decodeToString()).asJsonObject
            val type = json.get("type")?.asString
            val panels = panels
            // The panels work without the world tools; the rest needs them.
            if (panels != null) when (type) {
                "hello" -> return panels.hello(player, json)
                "panel_open" -> return panels.open(player, json)
                "panel_close" -> return panels.close(player)
                "panel_resolve" -> return panels.resolve(player, json)
            }
            when (type) {
                "selection" -> {
                    if (!plugin.worldTools) return
                    if (json.get("clear")?.asBoolean == true) selections.remove(player.uniqueId)
                    else {
                        val world = NamespacedKey.fromString(json.get("dim").asString)?.let(Bukkit::getWorld) ?: player.world
                        selections[player.uniqueId] = Selection(world.name, Box.of(Args.vecOf(json.get("min")), Args.vecOf(json.get("max"))))
                    }
                }
                "subscribe" -> {
                    val layers = json.getAsJsonArray("layers").map { it.asString }.filter { it in LAYERS && (plugin.worldTools || it == "relations") }
                        .filter { it != "relations" || panels != null }
                    clients[player.uniqueId]?.addAll(layers)
                    if ("relations" in layers) panels?.subscribe(player)
                    if (!plugin.worldTools) return
                    layers.forEach { layer -> layerMessage(layer)?.let { send(player, it) } }
                    if ("objects" in layers) {
                        feeds.remove(player.uniqueId)
                        pushObjects(player)
                        if (objectPoll == null) objectPoll = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { pushObjects() }, 10L, 10L)
                    }
                }
                "object_action" -> {
                    if (!plugin.worldTools || "objects" !in clients[player.uniqueId].orEmpty()) return
                    val id = json.get("id")?.asString ?: return
                    val result = plugin.worlds.objects.run(player, id)
                    send(player, message("object_action_done") {
                        addProperty("id", id)
                        addProperty("ok", result.isOk)
                        result.message?.let { addProperty("message", it) }
                    })
                    pushActions(player, feeds.getOrPut(player.uniqueId) { ObjectFeed() })
                }
                "unsubscribe" -> {
                    val layers = json.getAsJsonArray("layers").map { it.asString }.toSet()
                    clients[player.uniqueId]?.removeAll(layers)
                    if ("objects" in layers) feeds.remove(player.uniqueId)
                    if ("relations" in layers) panels?.unsubscribe(player)
                }
            }
        } catch (e: Exception) {
            plugin.logger.log(Level.WARNING, "Bad $CHANNEL message from ${player.name}", e)
        }
    }

    /** Called after every MCP tool call (HTTP thread): pushes check issues and AI changes, with whose they are. */
    /** A tool call: a change it made goes to the mods, [editor] when a player made it in the mod's pages (not an AI). */
    fun onToolCall(tool: Tool, args: Args, result: ToolResult, ms: Long, caller: Caller?, editor: Boolean) {
        if (result.isError || clients.isEmpty() || !plugin.worldTools) return
        // Panel edits change the plugins' things, not the world: no undo step, no change to show.
        if (ForemanTool.of(tool.name)?.group == "Panels") return
        val world = runCatching { plugin.world(args.string("world", null)) }.getOrNull() ?: return
        if (tool.modifies) {
            val box = ToolEvents.changedBox(plugin, args, result)
            val author = caller ?: Caller.CONSOLE
            val message = message("change") {
                addProperty("label", ToolEvents.label(result, ms, author).ifEmpty { tool.name })
                addProperty("by", author.name)
                addProperty("editor", editor)
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

    /**
     * Sends each editor subscribed to `objects` the kinds when they change and the objects near them when those do. Its
     * task stops when none is left.
     */
    private fun pushObjects() {
        var any = false
        clients.forEach { (uuid, layers) ->
            if ("objects" in layers) {
                any = true
                Bukkit.getPlayer(uuid)?.let(::pushObjects)
            }
        }
        if (!any) {
            objectPoll?.cancel()
            objectPoll = null
        }
    }

    private fun pushObjects(player: Player) {
        val objects = plugin.worlds.objects
        val feed = feeds.getOrPut(player.uniqueId) { ObjectFeed() }
        if (feed.kinds != objects.version) {
            feed.kinds = objects.version
            feed.changes = -1
            send(player, message("object_kinds") { add("kinds", objects.json()) })
        }
        pushActions(player, feed)
        val now = System.currentTimeMillis()
        if (feed.changes == objects.changes && now - feed.at < OBJECTS_EVERY_MS) return
        feed.changes = objects.changes
        feed.at = now
        val text = message("objects") {
            addWorld(player.world)
            add("objects", JsonArray().apply { objects.near(player.location, OBJECTS_RADIUS, OBJECTS_MAX).forEach { add(objects.json(it, true, OUTLINE_POINTS)) } })
        }.toString()
        if (text == feed.objects) return
        feed.objects = text
        player.sendPluginMessage(plugin, CHANNEL, text.encodeToByteArray())
    }

    /** Sends [player] the actions plugins add to the objects tool, with their state for them, when those changed. */
    private fun pushActions(player: Player, feed: ObjectFeed) {
        val message = message("object_actions") {
            add("actions", JsonArray().apply {
                plugin.worlds.objects.actions(player).forEach { (a, state) ->
                    add(JsonObject().apply {
                        addProperty("id", a.action.id)
                        addProperty("label", a.action.label)
                        a.action.key?.let { addProperty("key", it.toString()) }
                        addProperty("description", a.action.description)
                        addProperty("owner", a.owner)
                        state?.let { addProperty("state", it) }
                    })
                }
            })
        }
        val text = message.toString()
        if (text == feed.actions) return
        feed.actions = text
        send(player, message)
    }

    private fun pushChangedLayers() {
        if (clients.isEmpty()) return
        for (layer in listOf("areas", "points", "instances", "layout")) {
            val snapshot: Any = when (layer) {
                "areas" -> plugin.worlds.areas
                "points" -> plugin.worlds.allPoints
                "layout" -> plugin.worlds.layout.version
                else -> plugin.worlds.allInstances
            }
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
                        if (a.tags.isNotEmpty()) add("tags", JsonArray().apply { a.tags.forEach { add(it) } })
                        a.lock?.let { add("locked_by", it.json()) }
                        add("data", a.data)
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
                        add("position", JsonArray().apply { add(p.x); add(p.y); add(p.z) })
                        p.yaw?.let { addProperty("yaw", it) }
                        p.pitch?.let { addProperty("pitch", it) }
                        addProperty("type", p.type)
                        addProperty("kind", p.kind)
                        addProperty("build", p.build)
                        addProperty("room", p.room)
                        add("data", p.data)
                    })
                }
            })
        }
        // The pastes of library assets: what the client outlines and links to their source.
        // The point and area types plugins declare, with their fields: what the client's forms are made of.
        "layout" -> message("layout") { add("types", plugin.worlds.layout.json()) }
        "instances" -> message("instances") {
            add("instances", JsonArray().apply { plugin.worlds.allInstances.forEach { add(LibraryTools.json(it, full = false)) } })
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
