package games.qore.foreman.client.agent

import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/** A minimal MCP client over Streamable HTTP: JSON-RPC requests POSTed to the foreman server with the player's token. */
class McpClient(private val url: String, private val token: String) {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val ids = AtomicInteger()
    private var session: String? = null
    private var protocol: String? = null

    class Prompt(val name: String, val description: String, val arguments: List<Pair<String, Boolean>>)

    /** Initializes the session; returns the server's instructions. */
    fun initialize(): String {
        val result = request("initialize", buildJsonObject {
            put("protocolVersion", "2025-06-18")
            put("capabilities", buildJsonObject {})
            put("clientInfo", buildJsonObject { put("name", "foreman"); put("version", "1.0") })
        })
        protocol = result["protocolVersion"].str()
        notify("notifications/initialized")
        return result["instructions"].str() ?: ""
    }

    fun tools(): List<JsonObject> = request("tools/list", buildJsonObject {})["tools"].arr()?.map { it.jsonObject } ?: emptyList()

    fun callTool(name: String, arguments: JsonObject): JsonObject = request("tools/call", buildJsonObject {
        put("name", name)
        put("arguments", arguments)
    }, timeout = Duration.ofMinutes(10))

    fun prompts(): List<Prompt> = request("prompts/list", buildJsonObject {})["prompts"].arr()?.map { p ->
        Prompt(p["name"].str() ?: "", p["description"].str() ?: "", p["arguments"].arr()?.map { (it["name"].str() ?: "") to (it["required"].toString() == "true") } ?: emptyList())
    } ?: emptyList()

    /** The text of a prompt with its [arguments] filled in. */
    fun prompt(name: String, arguments: Map<String, String>): String {
        val result = request("prompts/get", buildJsonObject {
            put("name", name)
            put("arguments", buildJsonObject { arguments.forEach { (k, v) -> put(k, v) } })
        })
        return result["messages"].arr()?.joinToString("\n\n") { it["content"]["text"].str() ?: "" } ?: ""
    }

    private fun notify(method: String) {
        send(buildJsonObject { put("jsonrpc", "2.0"); put("method", method) }, Duration.ofSeconds(10))
    }

    private fun request(method: String, params: JsonObject, timeout: Duration = Duration.ofSeconds(60)): JsonObject {
        val body = send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", ids.incrementAndGet())
            put("method", method)
            put("params", params)
        }, timeout) ?: throw AgentException("MCP $method: empty answer")
        body["error"]?.let { throw AgentException("MCP $method: ${it["message"].str() ?: it}") }
        return body["result"] as? JsonObject ?: throw AgentException("MCP $method: no result")
    }

    private fun send(message: JsonObject, timeout: Duration): JsonElement? {
        val builder = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(message.toString()))
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        session?.let { builder.header("Mcp-Session-Id", it) }
        protocol?.let { builder.header("MCP-Protocol-Version", it) }
        val response = try {
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: java.io.IOException) {
            throw AgentException("Can't reach the MCP server at $url: ${e.message ?: e.javaClass.simpleName}")
        }
        response.headers().firstValue("Mcp-Session-Id").ifPresent { session = it }
        if (response.statusCode() == 401) throw AgentException("The MCP server refused the token (401): rejoin to get a new one")
        if (response.statusCode() !in 200..299) throw AgentException("MCP server answered ${response.statusCode()}")
        val text = response.body()
        if (text.isBlank()) return null
        // An SSE answer carries the JSON-RPC response in its data lines.
        val json = if (response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            text.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }.lastOrNull { it.isNotEmpty() } ?: return null
        } else text
        return JSON.parseToJsonElement(json)
    }
}
