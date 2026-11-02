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
 * servers (`minecraft`: the Foreman server, when there is one; `client`: this client's tools) and only their tools
 * allowed, plus its file tools on the chat's workspace folders.
 */
class ClaudeCodeAgent : CliAgent("Claude Code") {
    override val promptViaStdin = true

    /** Whether the current assistant message already streamed its text (partial messages). */
    private var streamed = false

    override fun command(request: AgentRequest): List<String> {
        val config = buildJsonObject {
            put("mcpServers", buildJsonObject {
                if (request.mcpUrl != null && request.token != null) put("minecraft", http(request.mcpUrl, request.token))
                put("client", http(request.clientUrl, request.clientToken))
            })
        }
        val file = writePrivate("claude-mcp-${request.chatId}.json", config.toString())
        val s = request.settings
        val allowed = mutableListOf("mcp__minecraft__*", "mcp__client__*")
        if (request.folders.isNotEmpty()) allowed += listOf("Read", "Edit", "MultiEdit", "Write", "Glob", "Grep", "LS")
        return executable(s.claudeCommand, "claude") + listOf(
            "-p", "--output-format", "stream-json", "--verbose", "--include-partial-messages",
            "--mcp-config", file.toString(), "--strict-mcp-config",
            "--allowedTools", allowed.joinToString(","),
            "--append-system-prompt", request.system,
        ) + request.folders.drop(1).flatMap { listOf("--add-dir", it) } +
            (if (s.claudeModel.isNotBlank()) listOf("--model", s.claudeModel) else emptyList()) +
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
                listener.info(TokenUsage(n("input_tokens") + n("cache_creation_input_tokens") + cached, cached, n("output_tokens")).done(event["num_turns"].int()))
            }
        }
        return null
    }

    private fun toolName(name: String) = name.removePrefix("mcp__minecraft__").removePrefix("mcp__client__")
}
