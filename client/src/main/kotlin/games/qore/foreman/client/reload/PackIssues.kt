package games.qore.foreman.client.reload

import games.qore.foreman.client.agent.Chats
import games.qore.foreman.client.mcp.ClientLog
import games.qore.foreman.client.ui.ChatScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import org.apache.logging.log4j.Level

/**
 * The warnings and errors of the last resource reload (missing textures and models, shaders that fail, fonts, sounds…),
 * grouped by kind, for the hub's Resource packs page, and a chat that finds their causes in the pack's sources.
 */
object PackIssues {
    /** The lines that say the same thing about different resources, oldest first. */
    class Group(val logger: String, val kind: String, val lines: List<ClientLog.Line>) {
        val error = lines.any { it.level.isMoreSpecificThan(Level.ERROR) }
        val key get() = "$logger $kind"
    }

    private val ID = Regex("""[a-z0-9_.-]+:[a-z0-9_./-]+""")
    private val NUMBER = Regex("""\d+""")
    private var cached = emptyList<Group>()
    private var cachedAt = 0L

    /**
     * The last reload's issues, errors then the most frequent kinds first: the lines at warning or above that the client's
     * threads logged from its start to its loading screen closing (all since then while one runs), and the shader
     * manager's since (post effects are compiled as they're first used). Recounted twice a second.
     */
    fun groups(): List<Group> {
        val now = System.currentTimeMillis()
        if (now - cachedAt < 500) return cached
        val end = ReloadTimer.closedMillis.takeIf { it >= ClientLog.lastReload() }
        cached = ClientLog.matching(Level.WARN, null, sinceReload = true)
            .filter { it.client && (end == null || it.time <= end + 100 || it.logger == "ShaderManager") }
            .groupBy { it.logger to kind(it.text) }
            .map { (key, lines) -> Group(key.first, key.second, lines) }
            .sortedWith(compareByDescending<Group> { it.error }.thenByDescending { it.lines.size })
        cachedAt = now
        return cached
    }

    /** A line's first line without its resource ids and numbers: what the lines of a kind share. */
    private fun kind(text: String) = text.lineSequence().first().replace(ID, "…").replace(NUMBER, "#").trim()

    /** A new chat about [groups]: what each kind means, the file that causes it, and the fix. */
    fun ask(groups: List<Group>) {
        val chat = Chats.create()
        if (chat.data.folders.isEmpty()) Chat.warn("This chat has no folder to search: add your resource pack's sources as a default workspace (H, Workspaces) so the AI can find the files")
        val lines = groups.flatMap { group -> group.lines.take(LINES_PER_KIND).map { "[${it.level}] [${it.logger}] ${it.text}" } +
            listOfNotNull((group.lines.size - LINES_PER_KIND).takeIf { it > 0 }?.let { "… and $it more of this kind" }) }
        chat.send(PROMPT + "\n\n" + lines.joinToString("\n").take(MAX_CHARS),
            if (groups.size == 1) "Fix: ${groups[0].kind}" else "Fix the last resource reload's issues")
        Screens.open(ChatScreen(open = chat))
    }

    private const val LINES_PER_KIND = 20
    private const val MAX_CHARS = 12_000

    private const val PROMPT = "These warnings and errors come from my client's last resource reload (the game's log). For each " +
        "kind, say in a line what it means for players, then find the file that causes it in the resource pack sources in " +
        "this chat's folders and fix it, showing each change; group lines with the same cause. client_resource_packs lists " +
        "the loaded packs, client_log with since_reload has the whole log, and client_resource_packs with reload reloads " +
        "after a fix to check it's gone. If a pack's sources aren't in the folders, say which files to look at."
}
