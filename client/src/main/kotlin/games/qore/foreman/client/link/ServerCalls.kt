package games.qore.foreman.client.link

import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.agent.McpClient
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import java.util.concurrent.Executors

/**
 * The mod's own pages calling the Foreman plugin's tools (the library, the model browser): one MCP session reused while
 * the server's address and token stay the same, the calls off the client thread.
 */
object ServerCalls {
    private val executor = Executors.newCachedThreadPool { Thread(it, "foreman-server-calls").apply { isDaemon = true } }
    @Volatile
    private var session: Pair<String, McpClient>? = null

    /** Runs [work] off the client thread, then [done] with its outcome on it. */
    fun <T> run(work: () -> T, done: (Result<T>) -> Unit) = executor.execute {
        val result = runCatching(work)
        Minecraft.getInstance().execute { done(result) }
    }

    /**
     * Calls [tool] of the server and waits for its answer, at most [timeout]; its error result as an exception. Off the
     * client thread.
     */
    fun callNow(tool: String, args: JsonObject, timeout: java.time.Duration = java.time.Duration.ofMinutes(10)): JsonObject {
        val url = Bridge.mcpUrl
        val token = Bridge.editorToken
        if (url == null || token == null) throw AgentException("No Foreman plugin on this server")
        val client = client(url, token)
        val result = try {
            client.callTool(tool, args, timeout)
        } catch (e: AgentException) {
            session = null
            throw e
        }
        if (result["isError"].bool() == true) throw AgentException(text(result).ifBlank { "$tool failed" })
        return result
    }

    /** The text parts of a tool's result. */
    fun text(result: JsonObject) = result["content"].arr().orEmpty().mapNotNull { it["text"].str() }.joinToString("\n")

    /** Forgets the session (the game left the server). */
    fun reset() {
        session = null
    }

    @Synchronized
    private fun client(url: String, token: String): McpClient {
        val key = "$url $token"
        session?.takeIf { it.first == key }?.let { return it.second }
        return McpClient(url, token).also { it.initialize(); session = key to it }
    }
}
