package games.qore.foreman.client.agent

import games.qore.foreman.api.ForemanTool
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
import kotlinx.serialization.json.JsonPrimitive
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
 *
 * Every tool's definition would cost each request (over a hundred of them, about 40k tokens): the model gets the
 * groups of [ALWAYS] and those it loaded with [LOAD_TOOLS] ([loaded], kept by the chat), and the names of the others by
 * group in the system message. Earlier runs are [compact]ed when a new one starts.
 */
class OpenRouterAgent(private val history: MutableList<JsonObject>, private val loaded: MutableSet<String>) : Agent {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()
    /** The tokens of the current run. */
    private var usage = TokenUsage()

    private class ToolCall(var id: String = "", var name: String = "", val arguments: StringBuilder = StringBuilder())

    /** A tool of one of the MCP servers: its function definition, the server that has it, its group. */
    private class Offered(val name: String, val function: JsonObject, val server: McpClient, val group: String)

    override fun run(request: AgentRequest, listener: AgentListener, cancel: CancelToken) {
        val s = request.settings
        if (s.openRouterKey.isBlank()) throw AgentException("Set the OpenRouter key in the settings")
        if (request.model.isBlank()) throw AgentException("Choose a model for this chat, or set the OpenRouter model in the settings")
        listener.model(request.model)
        usage = TokenUsage()
        // The Foreman server's tools (when there is one) and the client's; each call goes to the server that has the tool.
        val servers = listOfNotNull(
            if (request.mcpUrl != null && request.token != null) McpClient(request.mcpUrl, request.token) else null,
            McpClient(request.clientUrl, request.clientToken),
        )
        val instructions = servers.joinToString("\n\n") { it.initialize() }
        val all = LinkedHashMap<String, Offered>()
        servers.forEach { server ->
            server.tools().forEach { t ->
                val name = t["name"].str() ?: return@forEach
                all[name] = Offered(name, function(name, t), server, t["_meta"]["group"].str() ?: ForemanTool.of(name)?.group ?: t["_meta"]["provider"].str() ?: "Other")
            }
        }
        val groups = all.values.groupBy { it.group }
        // In the same order whatever was loaded when: the same set gives the same request prefix, which providers cache.
        fun tools() = JsonArray(listOf(LOAD_TOOLS) + all.values.filter { it.group in ALWAYS || it.group in loaded }.map { it.function })

        val system = buildJsonObject { put("role", "system"); put("content", instructions + "\n\n" + groupsNote(groups)) }
        compact()
        if (history.firstOrNull()?.get("role").str() == "system") history[0] = system else history.add(0, system)
        history += buildJsonObject { put("role", "user"); put("content", request.prompt) }

        repeat(s.maxTurns) { round ->
            if (cancel.cancelled) return
            val (text, calls) = complete(request, tools(), listener, cancel)
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
            if (calls.isEmpty()) return listener.info(usage.done(round + 1, request.model, request.effort))

            val images = mutableListOf<String>()
            for (call in calls) {
                if (cancel.cancelled) return
                listener.toolCall(call.id, call.name, call.arguments.toString())
                val (ok, output) = try {
                    val args = JSON.parseToJsonElement(call.arguments.toString().ifBlank { "{}" }).jsonObject
                    if (call.name == LOAD_TOOLS_NAME) load(args, groups) else {
                        val tool = all[call.name] ?: throw AgentException("No tool ${call.name}")
                        // Named in the groups list and called without loading it: it runs, and its group is there from now on.
                        loaded += tool.group
                        val result = tool.server.callTool(call.name, args)
                        val content = result["content"].arr() ?: JsonArray(emptyList())
                        content.filter { it["type"].str() == "image" }.forEach { images += "data:${it["mimeType"].str() ?: "image/png"};base64,${it["data"].str()}" }
                        (result["isError"].bool() != true) to content.mapNotNull { it["text"].str() }.joinToString("\n")
                    }
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

    /** [LOAD_TOOLS]: the groups asked for (or those of the tools named) are offered from the next completion on. */
    private fun load(args: JsonObject, groups: Map<String, List<Offered>>): Pair<Boolean, String> {
        fun group(name: String) = groups.keys.firstOrNull { it.equals(name, true) } ?: groups.entries.firstOrNull { (_, tools) -> tools.any { it.name == name } }?.key
        val asked = args["groups"].arr()?.mapNotNull { it.str() }.orEmpty()
        val found = asked.mapNotNull(::group).distinct()
        val unknown = asked.filter { group(it) == null }
        if (found.isEmpty()) return false to "No group or tool ${asked.joinToString()}. The groups: ${groups.keys.joinToString()}"
        loaded += found
        return true to "Loaded, to call from your next step on: " + found.joinToString("; ") { g -> "$g (${groups.getValue(g).joinToString { it.name }})" } +
            (if (unknown.isEmpty()) "" else ". No group or tool ${unknown.joinToString()}")
    }

    /** The tools the model doesn't have yet, by group, for the system message. */
    private fun groupsNote(groups: Map<String, List<Offered>>) =
        "Your tools come in groups. You have ${ALWAYS.filter { it in groups }.joinToString()} and $LOAD_TOOLS_NAME; call $LOAD_TOOLS_NAME with " +
            "the groups (or tool names) a task needs before using them. Every group:\n" +
            groups.entries.joinToString("\n") { (group, tools) -> "- $group: " + tools.joinToString(", ") { it.name } }

    /**
     * Before a new run, what earlier runs got back is cut down: images are dropped and long tool results keep their
     * start. Each run's messages are cut once, so the history before the last run stays the same (and cached).
     */
    internal fun compact() {
        for (i in history.indices) {
            val message = history[i]
            when (message["role"].str()) {
                "tool" -> {
                    val content = message["content"].str() ?: continue
                    if (content.length <= KEPT_RESULT) continue
                    history[i] = JsonObject(message + ("content" to JsonPrimitive(
                        content.take(KEPT_RESULT - 200) + "\n[${content.length - KEPT_RESULT + 200} more characters not kept: call the tool again for all of it]",
                    )))
                }
                "user" -> if (message["content"].arr()?.any { it["type"].str() == "image_url" } == true) {
                    history[i] = JsonObject(message + ("content" to JsonPrimitive("(The images the tool calls above returned are not kept: call the tool again to see them.)")))
                }
            }
        }
    }

    private fun function(name: String, tool: JsonObject) = buildJsonObject {
        put("type", "function")
        put("function", buildJsonObject {
            put("name", name)
            put("description", tool["description"].str() ?: "")
            put("parameters", tool["inputSchema"] ?: buildJsonObject { put("type", "object") })
        })
    }

    /** One streamed completion: the text (streamed to [listener]) and the tool calls. */
    private fun complete(request: AgentRequest, tools: JsonArray, listener: AgentListener, cancel: CancelToken): Pair<String, List<ToolCall>> {
        val body = buildJsonObject {
            put("model", request.model)
            put("messages", JsonArray(history))
            put("tools", tools)
            put("stream", true)
            put("usage", buildJsonObject { put("include", true) })
            // A chat stays on one provider, whose cache has its conversation; Anthropic's models cache only when asked.
            put("session_id", "foreman-${request.chatId}")
            if (request.model.removePrefix("~").startsWith("anthropic/")) put("cache_control", buildJsonObject { put("type", "ephemeral") })
            if (request.effort.isNotBlank()) put("reasoning", buildJsonObject { put("effort", request.effort) })
        }
        val request = HttpRequest.newBuilder(URI.create("https://openrouter.ai/api/v1/chat/completions"))
            .header("Authorization", "Bearer ${request.settings.openRouterKey}")
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

    private companion object {
        const val LOAD_TOOLS_NAME = "load_tools"
        /** The groups a chat always has: the player's basics, its workspace's files, the server's skills. */
        val ALWAYS = setOf("Player", "Files", "Skills")
        /** How much of an earlier run's tool result is kept. */
        const val KEPT_RESULT = 2000

        val LOAD_TOOLS = buildJsonObject {
            put("type", "function")
            put("function", buildJsonObject {
                put("name", LOAD_TOOLS_NAME)
                put("description", "Gives you more tools, by group (the groups are listed in your instructions), or the groups of the tools named: you can call them from your next step on, for the rest of the chat.")
                put("parameters", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("groups", buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                            put("description", "Group names, or tool names")
                        })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("groups")) })
                })
            })
        }
    }
}
