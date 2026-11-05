package games.qore.foreman.client.agent

import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.long
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * opencode: `opencode run --format json` with a config (OPENCODE_CONFIG) adding the Foreman server's and the client's MCP
 * servers and turning off every other tool (but its file tools when the chat has workspace folders: it then runs in the
 * first one).
 */
class OpencodeAgent : CliAgent("opencode") {
    override fun command(request: AgentRequest): List<String> {
        val s = request.settings
        reported.clear()
        model = request.model
        effort = request.effort.takeIf { request.model.isNotBlank() }.orEmpty()
        return executable(s.opencodeCommand, "opencode") + listOf("run", "--format", "json", "--standalone", "--auto") +
            // The effort is a variant of the model (`provider/model#high`): opencode has no other way.
            (if (request.model.isNotBlank()) listOf("--model", request.model + if (request.effort.isNotBlank()) "#${request.effort}" else "") else emptyList()) +
            (request.resume?.let { listOf("--session", it) } ?: emptyList()) +
            listOf(request.prompt)
    }

    override fun environment(request: AgentRequest, env: MutableMap<String, String>) {
        val config = buildJsonObject {
            put("\$schema", "https://opencode.ai/config.json")
            put("mcp", buildJsonObject {
                if (request.mcpUrl != null && request.token != null) put("minecraft", remote(request.mcpUrl, request.token))
                put("client", remote(request.clientUrl, request.clientToken))
            })
            put("tools", buildJsonObject {
                put("*", false)
                put("minecraft_*", true)
                put("client_*", true)
                if (request.folders.isNotEmpty()) listOf("read", "edit", "write", "glob", "grep", "list").forEach { put(it, true) }
            })
        }
        env["OPENCODE_CONFIG"] = writePrivate("opencode-${request.chatId}.json", config.toString()).toString()
    }

    override fun directory(request: AgentRequest) = request.folders.firstOrNull()

    private fun remote(url: String, token: String) = buildJsonObject {
        put("type", "remote")
        put("url", url)
        put("enabled", true)
        put("headers", buildJsonObject { put("Authorization", "Bearer $token") })
    }

    private val reported = HashSet<String>()
    private var model = ""
    private var effort = ""
    private val usage = TokenUsage()
    private var steps = 0

    override fun handle(event: JsonObject, listener: AgentListener): String? {
        event["sessionID"].str()?.let(listener::session)
        val part = event["part"]
        when (event["type"].str()) {
            "text" -> part["text"].str()?.let { listener.text(it + "\n") }
            "tool_use" -> {
                val id = part["callID"].str() ?: part["id"].str() ?: ""
                val state = part["state"]
                if (reported.add(id)) listener.toolCall(id, (part["tool"].str() ?: "?").removePrefix("minecraft_").removePrefix("client_"), state["input"].toString())
                when (state["status"].str()) {
                    "completed" -> listener.toolResult(id, true, summarize(state["output"].str() ?: ""))
                    "error" -> listener.toolResult(id, false, summarize(state["error"].str() ?: "error"))
                }
            }
            "step_finish" -> part["tokens"]?.let { t ->
                val cached = t["cache"]["read"].long() ?: 0
                usage.input += (t["input"].long() ?: 0) + cached + (t["cache"]["write"].long() ?: 0)
                usage.cached += cached
                usage.output += (t["output"].long() ?: 0) + (t["reasoning"].long() ?: 0)
                steps++
            }
            "error" -> return event["error"]["data"]["message"].str() ?: event["error"]["message"].str() ?: event["error"].toString()
        }
        return null
    }

    override fun finished(listener: AgentListener) {
        if (steps > 0) listener.info(usage.done(steps, model, effort))
    }
}
