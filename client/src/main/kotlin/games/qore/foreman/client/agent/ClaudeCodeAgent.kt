package games.qore.foreman.client.agent

import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.long
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Claude Code in print mode with the builder's own login: `claude -p --output-format stream-json`, with two MCP
 * servers (`minecraft`: the Foreman server, when there is one; `client`: this client's tools) and only their tools,
 * plus its file tools on the chat's workspace folders.
 */
class ClaudeCodeAgent : CliAgent("Claude Code") {
    override val promptViaStdin = true

    /** Whether the current assistant message already streamed its text (partial messages). */
    private var streamed = false
    /** The model Claude Code says it runs, and the effort asked for, for the last line. */
    private var model: String? = null
    private var effort = ""

    override fun command(request: AgentRequest): List<String> {
        val config = buildJsonObject {
            put("mcpServers", buildJsonObject {
                if (request.mcpUrl != null && request.token != null) put("minecraft", http(request.mcpUrl, request.token))
                put("client", http(request.clientUrl, request.clientToken))
            })
        }
        val file = writePrivate("claude-mcp-${request.chatId}.json", config.toString())
        val s = request.settings
        effort = request.effort
        val files = if (request.folders.isEmpty()) emptyList() else listOf("Read", "Edit", "Write", "Glob", "Grep")
        return executable(s.claudeCommand, "claude") + listOf(
            "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--mcp-config", file.toString(), "--strict-mcp-config",
            // Only the built-in tools a chat uses, the others' definitions cost every request: ToolSearch, which loads
            // the MCP tools' definitions when they are needed (without it all of them are sent each time), and the file tools.
            "--tools", (listOf("ToolSearch") + files).joinToString(","),
            "--allowedTools", (listOf("mcp__minecraft__*", "mcp__client__*") + files).joinToString(","),
        ) + request.folders.drop(1).flatMap { listOf("--add-dir", it) } +
            (if (request.model.isNotBlank()) listOf("--model", request.model) else emptyList()) +
            (if (request.effort.isNotBlank()) listOf("--effort", request.effort) else emptyList()) +
            (request.resume?.let { listOf("--resume", it) } ?: emptyList())
    }

    /** Runs in the chat's first workspace folder (its CLAUDE.md, not those above the agent folder); the others are added. */
    override fun directory(request: AgentRequest) = request.folders.firstOrNull()

    private fun http(url: String, token: String) = buildJsonObject {
        put("type", "http")
        put("url", url)
        put("headers", buildJsonObject { put("Authorization", "Bearer $token") })
    }

    override fun handle(event: JsonObject, listener: AgentListener): String? {
        event["session_id"].str()?.let(listener::session)
        when (event["type"].str()) {
            "system" -> if (event["subtype"].str() == "init") event["model"].str()?.let { model = it; listener.model(it) }
            "stream_event" -> {
                val e = event["event"]
                if (e["type"].str() == "content_block_delta" && e["delta"]["type"].str() == "text_delta") {
                    streamed = true
                    listener.text(e["delta"]["text"].str() ?: "")
                }
            }
            "assistant" -> {
                for (block in event["message"]["content"].arr() ?: emptyList()) when (block["type"].str()) {
                    "text" -> if (!streamed) listener.text(block["text"].str() ?: "")
                    // ToolSearch is Claude Code loading the definitions of deferred tools: nothing the user asked for.
                    "tool_use" -> if (block["name"].str() != "ToolSearch") listener.toolCall(block["id"].str() ?: "", toolName(block["name"].str() ?: "?"), block["input"].toString())
                }
                streamed = false
            }
            "user" -> for (block in event["message"]["content"].arr() ?: emptyList()) {
                if (block["type"].str() != "tool_result") continue
                val content = block["content"]
                val text = (content as? JsonPrimitive)?.content
                    ?: content.arr()?.mapNotNull { it["text"].str() ?: if (it["type"].str() == "image") "[image]" else null }?.joinToString("\n") ?: ""
                listener.toolResult(block["tool_use_id"].str() ?: "", block["is_error"].bool() != true, summarize(text))
            }
            "result" -> {
                if (event["is_error"].bool() == true || event["subtype"].str() != "success") {
                    return event["result"].str() ?: "Claude Code stopped: ${event["subtype"].str()}"
                }
                val usage = event["usage"]
                fun n(key: String) = usage[key].long() ?: 0
                val cached = n("cache_read_input_tokens")
                listener.info(TokenUsage(n("input_tokens") + n("cache_creation_input_tokens") + cached, cached, n("output_tokens")).done(event["num_turns"].int(), model, effort))
            }
        }
        return null
    }

    private fun toolName(name: String) = name.removePrefix("mcp__minecraft__").removePrefix("mcp__client__")
}
