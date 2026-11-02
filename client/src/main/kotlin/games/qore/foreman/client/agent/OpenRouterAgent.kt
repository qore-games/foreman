package games.qore.foreman.client.agent

import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.long
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * The agent loop in the mod, with any model on OpenRouter: the MCP server's tools are offered as OpenAI-style
 * functions (the Foreman server's and the client's), each call runs `tools/call` on the server that has the tool, and the loop goes on until the model answers without a call.
 * Images returned by tools are given back as image content. The conversation is [history] (kept by the chat).
 */
class OpenRouterAgent(private val history: MutableList<JsonObject>) : Agent {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    /** The tokens of the current run. */
    private var usage = TokenUsage()

    private class ToolCall(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    override fun run(request: AgentRequest, listener: AgentListener, cancel: CancelToken) {
        val s = request.settings
        if (s.openRouterKey.isBlank()) throw AgentException("Set the OpenRouter key in the settings")
        usage = TokenUsage()
        // The Foreman server's tools (when there is one) and the client's; each call goes to the server that has the tool.
        val servers = listOfNotNull(
            if (request.mcpUrl != null && request.token != null) McpClient(request.mcpUrl, request.token) else null,
            McpClient(request.clientUrl, request.clientToken),
        )
        val instructions = servers.joinToString("\n\n") { it.initialize() }
        val owner = HashMap<String, McpClient>()
        val tools = buildJsonArray {
            servers.flatMap { server -> server.tools().onEach { owner[it["name"].str() ?: ""] = server } }.forEach { t ->
                add(buildJsonObject {
                    put("type", "function")
                    put("function", buildJsonObject {
                        put("name", t["name"].str() ?: "")
                        put("description", t["description"].str() ?: "")
                        put("parameters", t["inputSchema"] ?: buildJsonObject { put("type", "object") })
                    })
                })
            }
        }

        val system = buildJsonObject { put("role", "system"); put("content", instructions + "\n\n" + request.system) }
        if (history.firstOrNull()?.get("role").str() == "system") history[0] = system else history.add(0, system)
        history += buildJsonObject { put("role", "user"); put("content", request.prompt) }

        repeat(s.maxTurns) { round ->
            if (cancel.cancelled) return
            val (text, calls) = complete(s.openRouterKey, s.openRouterModel, tools, listener, cancel)
            history += buildJsonObject {
                put("role", "assistant")
                put("content", text)
                if (calls.isNotEmpty()) put("tool_calls", buildJsonArray {
                    calls.forEach { c ->
                        add(buildJsonObject {
                            put("id", c.id)
                            put("type", "function")
                            put("function", buildJsonObject { put("name", c.name); put("arguments", c.arguments.toString().ifBlank { "{}" }) })
                        })
                    }
                })
            }
            if (cancel.cancelled) return
            if (calls.isEmpty()) return listener.info(usage.done(round + 1))

            val images = mutableListOf<String>()
            for (call in calls) {
                if (cancel.cancelled) return
                listener.toolCall(call.id, call.name, call.arguments.toString())
                val (ok, output) = try {
                    val args = JSON.parseToJsonElement(call.arguments.toString().ifBlank { "{}" }).jsonObject
                    val result = (owner[call.name] ?: throw AgentException("No tool ${call.name}")).callTool(call.name, args)
                    val content = result["content"].arr() ?: JsonArray(emptyList())
                    content.filter { it["type"].str() == "image" }.forEach { images += "data:${it["mimeType"].str() ?: "image/png"};base64,${it["data"].str()}" }
                    (result["isError"].bool() != true) to content.mapNotNull { it["text"].str() }.joinToString("\n")
                } catch (e: AgentException) {
                    false to (e.message ?: "error")
                } catch (e: Exception) {
                    false to "Invalid arguments: ${e.message}"
                }
                listener.toolResult(call.id, ok, summarize(output))
                history += buildJsonObject { put("role", "tool"); put("tool_call_id", call.id); put("content", output.ifEmpty { if (ok) "ok" else "error" }) }
            }
            if (images.isNotEmpty()) history += buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", "Images returned by the tool calls above:") })
                    images.forEach { url -> add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", url) }) }) }
                })
            }
        }
        listener.info("Stopped after ${s.maxTurns} tool rounds (maxTurns) · " + usage.done(null).substringAfter(" · "))
    }

    /** One streamed completion: the text (streamed to [listener]) and the tool calls. */
    private fun complete(key: String, model: String, tools: JsonArray, listener: AgentListener, cancel: CancelToken): Pair<String, List<ToolCall>> {
        val body = buildJsonObject {
            put("model", model)
            put("messages", JsonArray(history))
            put("tools", tools)
            put("stream", true)
            put("usage", buildJsonObject { put("include", true) })
        }
        val request = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .header("X-Title", "foreman")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        val stream = response.body()
        cancel.onCancel { stream.close() }
        if (response.statusCode() != 200) {
            throw AgentException("OpenRouter answered ${response.statusCode()}: ${stream.readAllBytes().decodeToString().take(500)}")
        }

        val text = StringBuilder()
        val calls = sortedMapOf<Int, ToolCall>()
        try {
            stream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val data = line.removePrefix("data:").trim()
                    if (data == "[DONE]") break
                    val chunk = JSON.parseToJsonElement(data)
                    chunk["error"]?.let { throw AgentException("OpenRouter: ${it["message"].str() ?: it}") }
                    chunk["usage"]?.let { u ->
                        val cached = u["prompt_tokens_details"]["cached_tokens"].long() ?: 0
                        usage.input += u["prompt_tokens"].long() ?: 0
                        usage.cached += cached
                        usage.output += u["completion_tokens"].long() ?: 0
                    }
                    val delta = chunk["choices"].arr()?.firstOrNull()?.get("delta") ?: continue
                    delta["content"].str()?.takeIf { it.isNotEmpty() }?.let {
                        text.append(it)
                        listener.text(it)
                    }
                    delta["tool_calls"].arr()?.forEach { tc: JsonElement ->
                        val call = calls.getOrPut(tc["index"].int() ?: calls.size) { ToolCall() }
                        tc["id"].str()?.let { call.id = it }
                        tc["function"]["name"].str()?.let { call.name += it }
                        tc["function"]["arguments"].str()?.let { call.arguments.append(it) }
                    }
                }
            }
        } catch (e: java.io.IOException) {
            if (!cancel.cancelled) throw AgentException("OpenRouter stream broke: ${e.message}")
        }
        if (text.isNotEmpty()) listener.text("\n")
        return text.toString() to calls.values.toList()
    }
}
