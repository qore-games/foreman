package games.qore.foreman.client.agent

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.client.util.str

/**
 * What the Foreman server plugin can do ([ForemanTool], shared with the server), so the AI recognises a request it
 * can't serve here ("place a chair" → place_furniture, missing) and says why in one line instead of searching for a
 * workaround. Told with a chat's first prompt and when it changes: nothing when every Foreman tool is there. Tools other plugins publish
 * aren't known in advance: the AI sees them in the server's tool list while their plugin runs.
 */
object ServerCapabilities {
    private class Cached(val url: String, val tools: Set<String>?, val error: String?, val at: Long)

    @Volatile
    private var cache: Cached? = null

    /**
     * The note for a run (off the client thread: it may list the server's tools). [url] and [token] are the Foreman
     * server's MCP endpoint, null without one.
     */
    fun note(url: String?, token: String?): String {
        if (url == null || token == null) return absent()
        val now = System.currentTimeMillis()
        val listed = cache?.takeIf { it.url == url && now - it.at < 60_000 } ?: run {
            val result = runCatching { McpClient(url, token).apply { initialize() }.tools().mapNotNull { it["name"].str() }.toSet() }
            Cached(url, result.getOrNull(), result.exceptionOrNull()?.message, now).also { cache = it }
        }
        listed.error?.let { return "The Foreman server is there but its tools could not be listed ($it): if its tools are missing, say so." }
        return missing(listed.tools ?: return "")
    }

    private fun absent() = """
        |This server doesn't run the Foreman server plugin: its world tools (below) are unavailable here. When a request
        |needs one of them, say so in one line (which tool, and that it needs Foreman on the server) before anything else,
        |then offer what the client tools can do instead (a command as the player, /give...). Don't search the code for a
        |workaround unless the player asks.
        |${lines(ForemanTool.entries).joinToString("\n|")}
    """.trimMargin()

    private fun missing(tools: Set<String>): String {
        val gaps = ForemanTool.entries.filter { it.id !in tools }
        if (gaps.isEmpty()) return ""
        return "The Foreman server runs here but some of its tools are unavailable. When a request needs one, say so in one " +
            "line with the reason, then offer an alternative:\n" + lines(gaps).joinToString("\n")
    }

    /** One line per group: its tools, and what a conditional one needs. */
    private fun lines(tools: List<ForemanTool>) = tools.groupBy { it.group to it.requires }.map { (key, list) ->
        "- ${key.first}: " + list.joinToString(", ") { it.id } + (key.second?.let { " (needs $it)" } ?: "")
    }
}
