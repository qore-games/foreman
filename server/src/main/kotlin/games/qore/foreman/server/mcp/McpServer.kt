package games.qore.foreman.server.mcp

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import games.qore.foreman.server.Secrets
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.logging.Level
import java.util.logging.Logger

/**
 * A minimal MCP server over the Streamable HTTP transport: JSON-RPC requests are POSTed to `/mcp` and answered with a
 * single JSON body (no SSE stream, no server-initiated messages). Enough for tools and prompts.
 */
class McpServer(
    private val logger: Logger,
    private val host: String,
    private val port: Int,
    /** The server's token. Every request needs it or a player's. */
    private val token: String,
    /** The player a per-player token belongs to (the foreman bridge issues them), or null. */
    private val playerToken: (String) -> Caller?,
    /** Read at each initialize: they include what the server learned since it started. */
    private val instructions: () -> String,
    private val tools: ToolRegistry,
    /** Read at each request, so edited prompt files apply at once. */
    private val prompts: () -> List<Prompt>,
    /** Called after every tool call, from the HTTP thread, with the player whose token made the call (or null). */
    private val onCall: (Tool, Args, ToolResult, Long, Caller?) -> Unit,
) {
    private val gson = Gson()
    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService

    fun start() {
        executor = Executors.newFixedThreadPool(4)
        server = HttpServer.create(InetSocketAddress(host, port), 0)
        server.createContext("/mcp", ::handle)
        server.executor = executor
        server.start()
        logger.info("MCP server listening on http://$host:$port/mcp (${tools.size} tools)")
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
                        return respond(exchange, 400, error(null, -32700, "Parse error"))
                    }
                    if (body.isJsonArray && body.asJsonArray.size() > MAX_BATCH) {
                        return respond(exchange, 400, error(null, -32600, "More than $MAX_BATCH requests in a batch"))
                    }

                    val response: JsonElement? = if (body.isJsonArray) {
                        JsonArray().apply { body.asJsonArray.mapNotNull { dispatch(it.asJsonObject, caller) }.forEach { add(it) } }
                            .takeIf { it.size() > 0 }
                    } else {
                        dispatch(body.asJsonObject, caller)
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

    private fun dispatch(request: JsonObject, caller: Caller?): JsonObject? {
        val id = request.get("id")
        val method = request.get("method")?.asString ?: return null
        if (id == null || id.isJsonNull) return null

        val params = request.getAsJsonObject("params") ?: JsonObject()
        return try {
            val result = when (method) {
                "initialize" -> initialize(params)
                "ping" -> JsonObject()
                "tools/list" -> JsonObject().apply {
                    add("tools", JsonArray().apply {
                        tools.all().forEach { tool ->
                            add(JsonObject().apply {
                                addProperty("name", tool.name)
                                addProperty("description", tool.description)
                                add("inputSchema", tool.schema)
                                // Who provides it, for the client's tool list.
                                add("_meta", JsonObject().apply { addProperty("provider", tool.provider); addProperty("modifies", tool.modifies) })
                            })
                        }
                    })
                }

                "tools/call" -> call(params, caller) ?: return error(id, -32602, "Unknown tool: ${params.get("name")}")
                "prompts/list" -> JsonObject().apply {
                    add("prompts", JsonArray().apply {
                        prompts().forEach { prompt ->
                            add(JsonObject().apply {
                                addProperty("name", prompt.name)
                                addProperty("description", prompt.description)
                                add("arguments", JsonArray().apply {
                                    prompt.arguments.forEach { a ->
                                        add(JsonObject().apply {
                                            addProperty("name", a.name)
                                            addProperty("description", a.description)
                                            addProperty("required", a.required)
                                        })
                                    }
                                })
                            })
                        }
                    })
                }

                "prompts/get" -> {
                    val prompt = prompts().firstOrNull { it.name == params.get("name")?.asString }
                        ?: return error(id, -32602, "Unknown prompt: ${params.get("name")}")
                    val values = params.getAsJsonObject("arguments")?.entrySet()?.associate { it.key to it.value.asString } ?: emptyMap()
                    val text = try {
                        prompt.render(values)
                    } catch (e: ToolError) {
                        return error(id, -32602, e.message ?: "Bad arguments")
                    }
                    JsonObject().apply {
                        addProperty("description", prompt.description)
                        add("messages", JsonArray().apply {
                            add(JsonObject().apply {
                                addProperty("role", "user")
                                add("content", JsonObject().apply {
                                    addProperty("type", "text")
                                    addProperty("text", text)
                                })
                            })
                        })
                    }
                }

                else -> return error(id, -32601, "Method not found: $method")
            }

            JsonObject().apply {
                addProperty("jsonrpc", "2.0")
                add("id", id)
                add("result", result)
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "MCP request $method failed", e)
            error(id, -32603, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun initialize(params: JsonObject): JsonObject {
        val requested = params.get("protocolVersion")?.asString
        return JsonObject().apply {
            addProperty("protocolVersion", if (requested in PROTOCOL_VERSIONS) requested else PROTOCOL_VERSIONS.last())
            add("capabilities", JsonObject().apply {
                add("tools", JsonObject())
                add("prompts", JsonObject())
            })
            add("serverInfo", JsonObject().apply {
                addProperty("name", "foreman")
                addProperty("version", "0.1")
            })
            addProperty("instructions", instructions())
        }
    }

    private fun call(params: JsonObject, caller: Caller?): JsonObject? {
        val tool = tools[params.get("name")?.asString] ?: return null
        val args = Args(params.getAsJsonObject("arguments") ?: JsonObject()).also { it.caller = caller }

        val start = System.currentTimeMillis()
        val result = try {
            tool.handler(args)
        } catch (e: ToolError) {
            ToolResult.error(e.message ?: "Error")
        } catch (e: Exception) {
            val cause = generateSequence<Throwable>(e) { it.cause }.last()
            logger.log(Level.WARNING, "Tool ${tool.name} failed", e)
            ToolResult.error("${cause.javaClass.simpleName}: ${cause.message}")
        }
        onCall(tool, args, result, System.currentTimeMillis() - start, caller)

        return JsonObject().apply {
            add("content", JsonArray().apply { result.content.forEach { add(it) } })
            addProperty("isError", result.isError)
        }
    }

    private fun error(id: JsonElement?, code: Int, message: String) = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        add("id", id)
        add("error", JsonObject().apply {
            addProperty("code", code)
            addProperty("message", message)
        })
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
        private val PROTOCOL_VERSIONS = listOf("2024-11-05", "2025-03-26", "2025-06-18", "2025-11-25")
        private const val MAX_BODY = 4 shl 20
        private const val MAX_BATCH = 50
    }
}
