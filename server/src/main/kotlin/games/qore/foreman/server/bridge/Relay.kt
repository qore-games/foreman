package games.qore.foreman.server.bridge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.McpDispatcher
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerQuitEvent
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * MCP over the game connection, for servers that open no port: the foreman mod sends its AI's JSON-RPC requests on
 * [CHANNEL] and gets the answers back the same way. The connection is the login: a player with `foreman.client` calls as
 * themself, the tools checking their permissions as with a personal token. Each player's requests run one after the
 * other, [perMinute] tool calls a minute at most; the dispatcher audits every call.
 *
 * A message: its kind (a byte: [REQUEST] or [ANSWER]), the request's id (an int, the client's), the part's index and
 * the number of parts (shorts), then the part's bytes of the UTF-8 JSON-RPC body. Requests come in parts of at most
 * 30000 bytes (what a client may send), answers go in parts of [ANSWER_PART].
 */
class Relay(private val plugin: Foreman, private val dispatcher: McpDispatcher, private val perMinute: Int) : Listener {
    companion object {
        const val CHANNEL = "foreman:mcp"
        const val REQUEST: Byte = 0
        const val ANSWER: Byte = 1
        private const val ANSWER_PART = 256 * 1024
        private const val MAX_REQUEST = 1 shl 20
        private const val MAX_ANSWER = 16 shl 20
        /** Parts of a request: 30000 bytes each, [MAX_REQUEST] in all. */
        private const val MAX_PARTS = 40
        /** Requests waiting behind the running one, and requests whose parts are coming, per player. */
        private const val MAX_QUEUED = 8
    }

    /** A request whose parts are coming. */
    private class Parts(val parts: Array<ByteArray?>, val started: Long)

    /** A player's lane: the requests waiting, whether one runs, the times of their last tool calls. */
    private class Lane {
        val waiting = ArrayDeque<Pair<Int, JsonObject>>()
        var running = false
        val calls = ArrayDeque<Long>()
    }

    private val incoming = ConcurrentHashMap<Pair<UUID, Int>, Parts>()
    private val lanes = ConcurrentHashMap<UUID, Lane>()
    /** Threads only while calls run (one per player at most): none stay once the relay is idle. */
    private val pool: ExecutorService = Executors.newCachedThreadPool { Thread(it, "Foreman relay").apply { isDaemon = true } }

    fun start() {
        Bukkit.getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL)
        Bukkit.getMessenger().registerIncomingPluginChannel(plugin, CHANNEL) { _, player, data -> receive(player, data) }
        Bukkit.getPluginManager().registerEvents(this, plugin)
    }

    fun stop() {
        HandlerList.unregisterAll(this)
        Bukkit.getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL)
        Bukkit.getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL)
        pool.shutdownNow()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        val id = event.player.uniqueId
        lanes.remove(id)
        incoming.keys.removeIf { it.first == id }
    }

    /** A part of a request (on the server thread): once it's whole, it joins the player's lane. */
    private fun receive(player: Player, data: ByteArray) {
        val (id, part, count, bytes) = runCatching {
            DataInputStream(data.inputStream()).run {
                if (readByte() != REQUEST) return
                Header(readInt(), readUnsignedShort(), readUnsignedShort(), readAllBytes())
            }
        }.getOrElse { return plugin.logger.warning("Bad $CHANNEL message from ${player.name}") }
        // Nothing is kept for a player who may not call: told once per request.
        if (!player.hasPermission(Bridge.PERMISSION)) {
            if (part == 0) answer(player, id, McpDispatcher.error(null, -32001, "This needs ${Bridge.PERMISSION}, which ${player.name} doesn't have"))
            return
        }
        if (count == 0 || part >= count || count > MAX_PARTS) return answer(player, id, McpDispatcher.error(null, -32600, "A request is at most ${MAX_REQUEST shr 20} MiB"))
        val now = System.currentTimeMillis()
        incoming.entries.removeIf { now - it.value.started > 60_000 }
        val key = player.uniqueId to id
        if (!incoming.containsKey(key) && incoming.keys.count { it.first == player.uniqueId } >= MAX_QUEUED) {
            if (part == 0) answer(player, id, McpDispatcher.error(null, -32002, "Too many requests coming at once: one runs at a time"))
            return
        }
        val parts = incoming.getOrPut(key) { Parts(arrayOfNulls(count), now) }
        if (parts.parts.size != count) return
        parts.parts[part] = bytes
        if (parts.parts.any { it == null }) return
        incoming.remove(key)
        val body = ByteArrayOutputStream().apply { parts.parts.forEach { write(it!!) } }.toByteArray()
        if (body.size > MAX_REQUEST) return answer(player, id, McpDispatcher.error(null, -32600, "A request is at most ${MAX_REQUEST shr 20} MiB"))
        val request = runCatching { JsonParser.parseString(body.decodeToString()).asJsonObject }.getOrNull()
            ?: return answer(player, id, McpDispatcher.error(null, -32700, "Parse error: one JSON-RPC request per message"))
        val lane = lanes.getOrPut(player.uniqueId) { Lane() }
        synchronized(lane) {
            if (lane.waiting.size >= MAX_QUEUED) return answer(player, id, McpDispatcher.error(request.get("id"), -32002, "Too many requests waiting: one runs at a time"))
            lane.waiting.add(id to request)
            if (!lane.running) next(player, lane)
        }
    }

    private data class Header(val id: Int, val part: Int, val count: Int, val bytes: ByteArray)

    /** Runs the lane's next request off the server thread, then the one after (called holding the lane). */
    private fun next(player: Player, lane: Lane) {
        val (id, request) = lane.waiting.poll() ?: run { lane.running = false; return }
        lane.running = true
        val caller = Caller(player.uniqueId, player.name)
        pool.execute {
            val reply = try {
                limited(lane, request) ?: dispatcher.dispatch(request, caller, "relay")
            } catch (e: Exception) {
                McpDispatcher.error(request.get("id"), -32603, e.message ?: e.javaClass.simpleName)
            }
            reply?.let { answer(player, id, it) }
            synchronized(lane) { next(player, lane) }
        }
    }

    /** An error when this tool call goes over [perMinute], else null (and the call counts). */
    private fun limited(lane: Lane, request: JsonObject): JsonObject? {
        if (request.get("method")?.asString != "tools/call") return null
        val now = System.currentTimeMillis()
        synchronized(lane) {
            while (lane.calls.isNotEmpty() && now - lane.calls.peekFirst() > 60_000) lane.calls.pollFirst()
            if (lane.calls.size >= perMinute) {
                val wait = (60_000 - (now - lane.calls.peekFirst())) / 1000 + 1
                return McpDispatcher.error(request.get("id"), -32003, "Rate limit: $perMinute tool calls a minute through the game connection (relay.calls-per-minute); try again in $wait s")
            }
            lane.calls.add(now)
        }
        return null
    }

    /** Sends [reply] to [player] in parts, on the server thread. */
    private fun answer(player: Player, id: Int, reply: JsonObject) {
        var bytes = reply.toString().encodeToByteArray()
        if (bytes.size > MAX_ANSWER) bytes = McpDispatcher.error(reply.get("id"), -32004, "The answer is ${bytes.size shr 20} MiB: the relay carries ${MAX_ANSWER shr 20} MiB at most (ask for less, a smaller image)").toString().encodeToByteArray()
        val count = (bytes.size + ANSWER_PART - 1) / ANSWER_PART
        val messages = (0 until maxOf(count, 1)).map { i ->
            ByteArrayOutputStream().apply {
                DataOutputStream(this).run {
                    writeByte(ANSWER.toInt())
                    writeInt(id)
                    writeShort(i)
                    writeShort(maxOf(count, 1))
                    write(bytes, i * ANSWER_PART, minOf(ANSWER_PART, bytes.size - i * ANSWER_PART))
                }
            }.toByteArray()
        }
        Bukkit.getScheduler().runTask(plugin, Runnable { if (player.isOnline) messages.forEach { player.sendPluginMessage(plugin, CHANNEL, it) } })
    }
}
