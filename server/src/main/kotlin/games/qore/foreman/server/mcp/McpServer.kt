package games.qore.foreman.server.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import games.qore.foreman.server.Secrets
import java.net.BindException
import java.net.InetSocketAddress
import java.net.URI
import java.util.Collections
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.logging.Logger

/**
 * A minimal MCP server over the Streamable HTTP transport: JSON-RPC requests are POSTed to `/mcp` and answered with a
 * single JSON body, by the [McpDispatcher] the relay shares. `initialize` gives a session, whose client may open its
 * stream of server messages (`GET /mcp`, [EventStreams]): `notifications/tools/list_changed` when the tools change.
 */
class McpServer(
    private val logger: Logger,
    private val host: String,
    /** The port asked for. When it's taken: the first free one of the [FALLBACK_PORTS] after it, else any free one. */
    private val configuredPort: Int,
    /** Fail when [configuredPort] is taken, rather than listen on another port. */
    private val strictPort: Boolean,
    /** The server's token. Every request needs it or a player's. */
    private val token: String,
    /** The player a per-player token belongs to (the foreman bridge issues them), or null. */
    private val playerToken: (String) -> Caller?,
    /** The player whose mod's own pages hold a token (their calls are the builder's own edits, not an AI's), or null. */
    private val editorToken: (String) -> Caller?,
    private val dispatcher: McpDispatcher,
) {
    private val gson = Gson()
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private val streams = EventStreams()

    /**
     * Sessions by id, given at `initialize`, with whom they belong to (null: the server's token). Kept in memory only:
     * after a restart, or past [MAX_SESSIONS] (the least recently used, with its stream), clients are told theirs is
     * gone (404) and start again, listing the tools again.
     */
    private val sessions: MutableMap<String, Session> = Collections.synchronizedMap(object : LinkedHashMap<String, Session>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>) =
            (size > MAX_SESSIONS).also { if (it) streams.close(eldest.key) }
    })

    /** Its thread lives only while changes are announced. */
    private val timer = ScheduledThreadPoolExecutor(1) { Thread(it, "foreman-mcp-notify").apply { isDaemon = true } }.apply {
        setKeepAliveTime(30, TimeUnit.SECONDS)
        allowCoreThreadTimeOut(true)
        removeOnCancelPolicy = true
    }
    private val listChanged = Debounce(timer, LIST_CHANGED_QUIET_MS, LIST_CHANGED_MAX_MS) { streams.broadcast(LIST_CHANGED) }

    private class Session(val owner: UUID?)

    /** The port it listens on, once started: [configuredPort], or another one when that was taken. */
    val port: Int get() = server.address.port

    /** Its address from this machine: `127.0.0.1` when it listens on every interface. */
    val url: String get() = "http://${localHost()}:$port/mcp"

    fun start() {
        server = bind()
        executor = Executors.newFixedThreadPool(4)
        server.createContext("/mcp", ::handle)
        server.executor = executor
        dispatcher.tools.onChange(::toolsChanged)
        server.start()
        logger.info("MCP server listening on http://$host:$port/mcp (${dispatcher.tools.size} tools)")
    }

    /** An HTTP server on [configuredPort], or, when it's taken and the port isn't strict, on the next free one. */
    private fun bind(): HttpServer {
        val taken = try {
            return HttpServer.create(InetSocketAddress(host, configuredPort), 0)
        } catch (e: BindException) {
            if (strictPort) throw BindException("MCP port $configuredPort on $host: ${e.message} (mcp.strict-port is on)")
            e
        }
        for (p in (configuredPort + 1..minOf(configuredPort + FALLBACK_PORTS, 65535)).asSequence() + 0) {
            val server = try { HttpServer.create(InetSocketAddress(host, p), 0) } catch (_: BindException) { continue }
            logger.warning(
                "MCP port $configuredPort on $host is taken (${taken.message}): listening on ${server.address.port} instead. " +
                    "plugins/Foreman/mcp.json gives the address; mcp.strict-port: true fails instead",
            )
            return server
        }
        throw taken
    }

    private fun localHost() = when (host.removeSurrounding("[", "]")) {
        "", "0.0.0.0", "::" -> "127.0.0.1"
        else -> if (':' in host && !host.startsWith("[")) "[$host]" else host
    }

    fun stop() {
        streams.stop()
        timer.shutdownNow()
        server.stop(0)
        executor.shutdownNow()
    }

    /** Tools added or removed: announced on the open streams once the changes stop for a second. Nothing while none is open. */
    private fun toolsChanged() {
        if (streams.isEmpty) return
        try {
            listChanged.poke()
        } catch (_: RejectedExecutionException) {
            // Stopping.
        }
    }

    private fun handle(exchange: HttpExchange) {
        var streaming = false
        try {
            // A web page must not reach the server, even on this machine (DNS rebinding): MCP clients send no Origin.
            if (!localOrigin(exchange.requestHeaders.getFirst("Origin"))) return respond(exchange, 403, null)
            val bearer = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            val editor = bearer?.let(editorToken)
            val caller = editor ?: bearer?.let(playerToken)
            if (caller == null && (bearer == null || !Secrets.same(bearer, token))) {
                return respond(exchange, 401, null)
            }

            // A session is used only with the token that opened it.
            val session = exchange.requestHeaders.getFirst("Mcp-Session-Id")
            if (session != null && sessions[session]?.let { it.owner == caller?.id } != true) return respond(exchange, 404, NO_SESSION)
            when (exchange.requestMethod) {
                "POST" -> post(exchange, caller, if (editor != null) "mod" else "http")
                "GET" -> streaming = stream(exchange, session)
                "DELETE" -> {
                    if (session != null) {
                        sessions.remove(session)
                        streams.close(session)
                    }
                    respond(exchange, 200, null)
                }
                else -> notAllowed(exchange)
            }
        } finally {
            if (!streaming) exchange.close()
        }
    }

    private fun post(exchange: HttpExchange, caller: Caller?, via: String) {
        if (exchange.requestHeaders.getFirst("Content-Type")?.startsWith("application/json") != true) {
            return respond(exchange, 415, null)
        }
        val bytes = exchange.requestBody.readNBytes(MAX_BODY + 1)
        if (bytes.size > MAX_BODY) return respond(exchange, 413, null)
        val body = try {
            JsonParser.parseString(bytes.decodeToString())
        } catch (e: Exception) {
            return respond(exchange, 400, McpDispatcher.error(null, -32700, "Parse error"))
        }
        if (body.isJsonArray && body.asJsonArray.size() > MAX_BATCH) {
            return respond(exchange, 400, McpDispatcher.error(null, -32600, "More than $MAX_BATCH requests in a batch"))
        }

        val response: JsonElement? = if (body.isJsonArray) {
            JsonArray().apply { body.asJsonArray.mapNotNull { dispatcher.dispatch(it.asJsonObject, caller, via) }.forEach { add(it) } }
                .takeIf { it.size() > 0 }
        } else {
            dispatcher.dispatch(body.asJsonObject, caller, via)
        }
        if ((body as? JsonObject)?.get("method")?.takeIf { it.isJsonPrimitive }?.asString == "initialize") {
            val id = Secrets.token("")
            sessions[id] = Session(caller?.id)
            exchange.responseHeaders.add("Mcp-Session-Id", id)
        }

        // Notifications and responses only: accepted, nothing to answer.
        if (response == null) respond(exchange, 202, null) else respond(exchange, 200, response)
    }

    /** `GET /mcp`: the session's stream of server messages. True when it opened: [streams] closes it. */
    private fun stream(exchange: HttpExchange, session: String?): Boolean {
        when {
            exchange.requestHeaders["Accept"].orEmpty().none { "text/event-stream" in it } -> notAllowed(exchange)
            session == null -> respond(exchange, 400, McpDispatcher.error(null, -32600, "Mcp-Session-Id missing: the stream is a session's, from initialize"))
            !streams.hasRoom(session) -> respond(exchange, 429, McpDispatcher.error(null, -32600, "Too many open streams"))
            else -> {
                streams.open(session, exchange)
                return true
            }
        }
        return false
    }

    private fun notAllowed(exchange: HttpExchange) {
        exchange.responseHeaders.add("Allow", "GET, POST, DELETE")
        respond(exchange, 405, null)
    }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonElement?) {
        if (body == null) {
            exchange.sendResponseHeaders(status, -1)
            return
        }
        val bytes = gson.toJson(body).encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    /** No Origin (not a browser), or a page served from this machine. */
    private fun localOrigin(origin: String?): Boolean {
        if (origin == null) return true
        val host = runCatching { URI(origin).host }.getOrNull()?.removeSurrounding("[", "]") ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }

    companion object {
        private const val MAX_BODY = 4 shl 20
        /** A few dozen at most in use (agents, the mod's pages, the probes of players joining): the oldest dropped only starts again. */
        private const val MAX_SESSIONS = 256
        private const val LIST_CHANGED_QUIET_MS = 1000L
        private const val LIST_CHANGED_MAX_MS = 5000L
        private const val LIST_CHANGED = """{"jsonrpc":"2.0","method":"notifications/tools/list_changed"}"""
        /** As MCP's SDK servers answer an unknown or ended session: the client starts a new one. */
        private val NO_SESSION = McpDispatcher.error(null, -32001, "Session not found")
        private const val MAX_BATCH = 50
        /** How many ports after the configured one are tried before any free one. */
        private const val FALLBACK_PORTS = 20
    }
}
