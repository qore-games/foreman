package games.qore.foreman.client.agent

import games.qore.foreman.client.config.AgentSettings
import java.util.concurrent.CopyOnWriteArrayList

/** What the agent reports while it runs (called from its own thread). */
interface AgentListener {
    /** A piece of the assistant's answer, as it streams. */
    fun text(delta: String)
    fun toolCall(id: String, name: String, input: String)
    fun toolResult(id: String, ok: Boolean, summary: String)
    /** The backend's session id, to continue the conversation next time. */
    fun session(id: String)
    /** The model the backend says it runs (Claude Code's default when the chat names none). */
    fun model(name: String)
    fun info(text: String)
}

class AgentRequest(
    /**
     * The player's prompt after the game context, and the chat's instructions when the session hasn't had them yet.
     * The server's instructions are given by MCP. Nothing goes in a system prompt: a resumed Claude Code session keeps
     * the one of its first run, and one that changes breaks the prompt cache of the whole conversation after it.
     */
    val prompt: String,
    /** The Foreman server's MCP endpoint and this player's token: null on a server without Foreman. */
    val mcpUrl: String?,
    val token: String?,
    /** The client's own MCP server for this chat (screenshots, commands, the workspace). */
    val clientUrl: String,
    val clientToken: String,
    /** The chat, to keep its files apart from the other chats' (runs are parallel). */
    val chatId: String,
    /** Workspace folders the AI may read and edit. */
    val folders: List<String>,
    /** The session to continue, from [AgentListener.session]. */
    val resume: String?,
    val settings: AgentSettings,
    /** The chat's model and effort, or the defaults of the settings; empty: the backend's own. */
    val model: String,
    val effort: String,
)

/** Cancels a run: hooks registered by the backend (kill the process, close the stream) run once. */
class CancelToken {
    @Volatile
    var cancelled = false
        private set
    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    fun onCancel(hook: () -> Unit) {
        hooks += hook
        if (cancelled) hook()
    }

    fun cancel() {
        if (cancelled) return
        cancelled = true
        hooks.forEach { runCatching(it) }
    }
}

class AgentException(message: String) : Exception(message)

/** A way to run the agent. [run] blocks until the agent is done, throws [AgentException] on failure. */
interface Agent {
    fun run(request: AgentRequest, listener: AgentListener, cancel: CancelToken)
}

/** The tokens a run used: input (read from the cache included), the part read from the cache, output. */
class TokenUsage(var input: Long = 0, var cached: Long = 0, var output: Long = 0) {
    /** `Done in 35 turns · 1.8M tokens in (1.7M cached) · 13.8k out · claude-opus-5-5, effort high` */
    fun done(turns: Int?, model: String? = null, effort: String = "") = (if (turns != null) "Done in $turns turn${if (turns == 1) "" else "s"}" else "Done") +
        " · ${count(input)} tokens in" + (if (cached > 0) " (${count(cached)} cached)" else "") + " · ${count(output)} out" +
        (model?.takeIf { it.isNotBlank() }?.let { " · $it" + if (effort.isNotBlank()) ", effort $effort" else "" } ?: "")

    private fun count(n: Long) = when {
        n < 1000 -> "$n"
        n < 1_000_000 -> String.format(java.util.Locale.ROOT, "%.1fk", n / 1000.0)
        else -> String.format(java.util.Locale.ROOT, "%.1fM", n / 1_000_000.0)
    }
}

/** One line of a tool result for the transcript. */
fun summarize(text: String, max: Int = 160): String {
    val line = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    return if (line.length > max) line.take(max - 1) + "…" else line
}
