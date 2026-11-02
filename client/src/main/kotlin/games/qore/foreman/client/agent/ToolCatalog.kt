package games.qore.foreman.client.agent

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import net.minecraft.client.Minecraft

/**
 * Every tool the AI chats can use and who provides it: the Foreman server's (its own and other plugins', from
 * `_meta.provider`) and this client's. For the hub's tool list and `/ai tools`.
 */
object ToolCatalog {
    class Entry(val name: String, val provider: String, val description: String, val modifies: Boolean, val onClient: Boolean, val arguments: List<Argument> = emptyList())

    /** An argument of a tool, from its input schema. */
    class Argument(val name: String, val description: String, val required: Boolean)

    /** A skill of the server: [about] is where it comes from and the plugin it's for. */
    class Skill(val name: String, val about: String, val description: String)

    var entries: List<Entry> = clientEntries()
        private set
    /** The server's skills (read_skill's list), what the AI reads when a request matches one. */
    var skills: List<Skill> = emptyList()

    var loading = false
        private set
    var error: String? = null
        private set

    private fun clientEntries() = ClientMcp.tools().map { Entry(it.name, "Foreman client", it.description, false, true, arguments(it.schema)) }

    private fun arguments(schema: JsonElement?): List<Argument> {
        val required = schema["required"].arr()?.mapNotNull { it.str() }.orEmpty().toSet()
        return (schema["properties"] as? JsonObject)?.map { (name, spec) -> Argument(name, spec["description"].str().orEmpty(), name in required) }.orEmpty()
    }

    /** Lists the server's tools again (off thread); [then] runs on the client thread after. */
    fun refresh(then: () -> Unit = {}) {
        val url = Bridge.mcpUrl
        val token = Bridge.token
        if (url == null || token == null) {
            entries = clientEntries()
            skills = emptyList()
            error = null
            return then()
        }
        loading = true
        Chats.executor.execute {
            val result = runCatching {
                val client = McpClient(url, token).apply { initialize() }
                val tools = client.tools().map { t ->
                    Entry(t["name"].str() ?: "?", t["_meta"]["provider"].str() ?: "server", t["description"].str() ?: "", t["_meta"]["modifies"].bool() == true, false, arguments(t["inputSchema"]))
                }
                val skills = if (tools.none { it.name == "read_skill" }) emptyList() else runCatching {
                    val text = client.callTool("read_skill", buildJsonObject {})["content"].arr()?.firstOrNull()?.get("text").str().orEmpty()
                    text.lines().mapNotNull { SKILL.matchEntire(it)?.destructured?.let { (name, about, description) -> Skill(name, about, description) } }
                }.getOrDefault(emptyList())
                tools to skills
            }
            Minecraft.getInstance().execute {
                loading = false
                error = result.exceptionOrNull()?.message
                entries = result.getOrNull()?.first.orEmpty() + clientEntries()
                skills = result.getOrNull()?.second.orEmpty()
                then()
            }
        }
    }

    /** A line of read_skill's list: `- name (source, for Plugin): description`. */
    private val SKILL = Regex("- (\\S+) \\(([^)]*)\\): (.*)")

    fun providers(): Map<String, List<Entry>> = entries.groupBy { it.provider }

    /** `/ai tools`: the list in chat, by provider. */
    fun printToChat() = refresh {
        error?.let { Chat.warn("Server tools: $it") }
        Chat.title("${entries.size} AI tools")
        providers().forEach { (provider, list) ->
            Chat.send(Chat.line("§e$provider §7(${list.size})", list.joinToString(", ") { it.name }, hover = list.joinToString("\n") { "${it.name}: ${summarize(it.description, 90)}" }))
        }
    }
}
