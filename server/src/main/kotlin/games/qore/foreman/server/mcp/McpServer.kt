package games.qore.foreman.server.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import games.qore.foreman.server.Secrets
import java.net.BindException
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Logger

/**
 * A minimal MCP server over the Streamable HTTP transport: JSON-RPC requests are POSTed to `/mcp` and answered with a
 * single JSON body (no SSE stream, no server-initiated messages), by the [McpDispatcher] the relay shares.
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
    private val dispatcher: McpDispatcher,
) {
    private val gson = Gson()
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    /** The port it listens on, once started: [configuredPort], or another one when that was taken. */
    val port: Int get() = server.address.port

    /** Its address from this machine: `127.0.0.1` when it listens on every interface. */
    val url: String get() = "http://${localHost()}:$port/mcp"

    fun start() {
        server = bind()
        executor = Executors.newFixedThreadPool(4)
        server.createContext("/mcp", ::handle)
        server.executor = executor
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
        server.stop(0)
        executor.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use {
            // A web page must not reach the server, even on this machine (DNS rebinding): MCP clients send no Origin.
            if (!localOrigin(exchange.requestHeaders.getFirst("Origin"))) return respond(exchange, 403, null)
            val bearer = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
            val caller = bearer?.let(playerToken)
            if (caller == null && (bearer == null || !Secrets.same(bearer, token))) {
                return respond(exchange, 401, null)
            }

            when (exchange.requestMethod) {
                "POST" -> {
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
                        JsonArray().apply { body.asJsonArray.mapNotNull { dispatcher.dispatch(it.asJsonObject, caller, "http") }.forEach { add(it) } }
                            .takeIf { it.size() > 0 }
                    } else {
                        dispatcher.dispatch(body.asJsonObject, caller, "http")
                    }

                    // Notifications and responses only: accepted, nothing to answer.
                    if (response == null) respond(exchange, 202, null) else respond(exchange, 200, response)
                }

                "DELETE" -> respond(exchange, 200, null)
                else -> {
                    exchange.responseHeaders.add("Allow", "POST, DELETE")
                    respond(exchange, 405, null)
                }
            }
        }
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
        private const val MAX_BATCH = 50
        /** How many ports after the configured one are tried before any free one. */
        private const val FALLBACK_PORTS = 20
    }
}
