package games.qore.foreman.client.agent

import games.qore.foreman.client.config.AgentBackend
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.mcp.ToolScope
import games.qore.foreman.client.util.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.Executors
import games.qore.foreman.client.util.Chat as ChatMessages

enum class EntryKind { USER, ASSISTANT, TOOL, INFO, ERROR }

/** A transcript line. A tool entry gets its result appended in [detail]. */
@Serializable
data class Entry(val kind: EntryKind, var text: String, var detail: String = "", var ok: Boolean = true, val id: String = "")

/** What a chat keeps on disk. */
@Serializable
data class ChatData(
    val id: String = UUID.randomUUID().toString().take(8),
    var title: String = "New chat",
    val created: Long = System.currentTimeMillis(),
    var updated: Long = System.currentTimeMillis(),
    var backend: AgentBackend = AgentBackend.CLAUDE_CODE,
    /** Workspaces (by name, from the settings) the chat works in. */
    var workspaces: List<String> = emptyList(),
    /** Folders added to this chat only. */
    var extraFolders: List<String> = emptyList(),
    /** The server it was last used on (its address, or `singleplayer`): the world its transcript talks about. */
    var server: String = "",
    override var allowCommands: Boolean = true,
    override var allowScreenshots: Boolean = true,
    val entries: MutableList<Entry> = mutableListOf(),
    val sessions: MutableMap<AgentBackend, String> = mutableMapOf(),
    val openRouter: MutableList<JsonObject> = mutableListOf(),
) : ToolScope {
    /** Every folder the AI may read and edit: its workspaces' and its own. */
    override val folders: List<String>
        get() = (workspaces.flatMap { name -> Config.settings.workspaces.firstOrNull { it.name == name }?.folders.orEmpty() } + extraFolders).distinct()
}

/**
 * One conversation: its transcript, the backends' session ids to continue it, its workspace and permissions, and its
 * run. Chats run in parallel; their events are applied on the client thread.
 */
class Chat(val data: ChatData) {
    private var cancel: CancelToken? = null
    val running get() = cancel != null
    private var runningSince = 0L
    private var lastTool: String? = null

    val id get() = data.id
    val entries get() = data.entries

    private fun changed() {
        data.updated = System.currentTimeMillis()
        Chats.version++
    }

    private fun add(entry: Entry) {
        data.entries += entry
        changed()
    }

    /** "12s · build_structure" while running. */
    fun status(): String? {
        if (!running) return null
        val seconds = (System.currentTimeMillis() - runningSince) / 1000
        return "${seconds}s" + (lastTool?.let { " · $it" } ?: "")
    }

    fun cancel() {
        cancel?.cancel()
    }

    fun update(change: ChatData.() -> Unit) {
        data.change()
        changed()
        Chats.save(this)
    }

    /** Sends [prompt] with the game context to the chat's backend; the transcript shows [shown]. */
    fun send(prompt: String, shown: String = prompt) {
        if (running) return add(Entry(EntryKind.ERROR, "This chat is still working: wait, cancel, or open another chat"))
        val backend = data.backend
        val settings = Config.settings.agent.copy(backend = backend)
        if (data.title == "New chat") data.title = summarize(shown, 40)
        val system = listOf(PromptContext.build(), movedNote(), settings.extraInstructions, workspaceNote(), workspaceInstructions()).filter { it.isNotBlank() }.joinToString("\n\n")
        data.server = Chats.server
        ClientMcp.scope(id, data)
        // Without a Foreman server (another server, singleplayer) the AI has the client tools only, and is told which
        // server tools it is missing (built off thread: it may list the server's tools).
        val mcpUrl = Bridge.mcpUrl
        val mcpToken = Bridge.token
        val folders = data.folders
        val resume = data.sessions[backend]
        fun request() = AgentRequest(
            prompt, listOf(system, ServerCapabilities.note(mcpUrl, mcpToken)).filter { it.isNotBlank() }.joinToString("\n\n"),
            mcpUrl, mcpToken, ClientMcp.url(id), ClientMcp.token, id, folders, resume, settings,
        )
        val agent = when (backend) {
            AgentBackend.CLAUDE_CODE -> ClaudeCodeAgent()
            AgentBackend.OPENCODE -> OpencodeAgent()
            AgentBackend.OPENROUTER -> OpenRouterAgent(data.openRouter)
        }

        add(Entry(EntryKind.USER, shown))
        val token = CancelToken()
        cancel = token
        runningSince = System.currentTimeMillis()
        lastTool = null
        val minecraft = Minecraft.getInstance()
        val listener = object : AgentListener {
            override fun text(delta: String) = minecraft.execute {
                val last = data.entries.lastOrNull()
                if (last?.kind == EntryKind.ASSISTANT) last.text += delta else add(Entry(EntryKind.ASSISTANT, delta.trimStart()))
                changed()
            }

            override fun toolCall(id: String, name: String, input: String) = minecraft.execute {
                lastTool = name
                add(Entry(EntryKind.TOOL, name, compact(input), id = id))
            }

            override fun toolResult(id: String, ok: Boolean, summary: String) = minecraft.execute {
                data.entries.lastOrNull { it.kind == EntryKind.TOOL && it.id == id }?.let {
                    it.ok = ok
                    it.detail += " → $summary"
                }
                changed()
            }

            override fun session(id: String) = minecraft.execute { data.sessions[backend] = id }

            override fun info(text: String) = minecraft.execute { add(Entry(EntryKind.INFO, text)) }
        }

        Chats.executor.execute {
            val error = try {
                agent.run(request(), listener, token)
                null
            } catch (e: AgentException) {
                e.message
            } catch (e: Exception) {
                LoggerFactory.getLogger("foreman").error("Agent run failed", e)
                "${e.javaClass.simpleName}: ${e.message}"
            }
            minecraft.execute {
                cancel = null
                lastTool = null
                when {
                    token.cancelled -> add(Entry(EntryKind.INFO, "Cancelled"))
                    error != null -> add(Entry(EntryKind.ERROR, error))
                }
                Chats.save(this)
                // Told in chat when the player isn't watching this conversation.
                val watching = (minecraft.gui.screen() as? games.qore.foreman.client.ui.ChatScreen)?.chat === this
                if (!watching) {
                    if (error != null) ChatMessages.error("${data.title}: $error")
                    else if (!token.cancelled) ChatMessages.success("${data.title}: " + summarize(data.entries.lastOrNull { it.kind == EntryKind.ASSISTANT }?.text ?: "done", 120))
                }
            }
        }
    }

    /** Continued on another server than the one it was last used on: its positions and edits were in another world. */
    private fun movedNote() = if (data.server.isEmpty() || data.server == Chats.server || entries.isEmpty()) "" else
        "This conversation continues on ${Chats.server}; until now it was on ${data.server}. Positions, areas, entities and " +
            "edits mentioned above are in that server's world, not this one: the server tools now act on ${Chats.server}."

    private fun workspaceNote() = if (data.folders.isEmpty()) "" else
        "Workspace folders of this chat" + (if (data.workspaces.isNotEmpty()) " (workspaces ${data.workspaces.joinToString()})" else "") +
            ": configs and code to read and edit when asked:\n" + data.folders.joinToString("\n") { "- $it" }

    /** The instructions of the chat's workspaces, each under its name. */
    private fun workspaceInstructions() = data.workspaces
        .mapNotNull { name -> Config.settings.workspaces.firstOrNull { it.name == name }?.takeIf { it.instructions.isNotBlank() } }
        .joinToString("\n\n") { "Instructions of the workspace ${it.name}:\n${it.instructions.trim()}" }

    private fun compact(input: String) = if (input.length > 140) input.take(139) + "…" else input

    /** Runs the server's MCP prompt [name] with [arguments] (fetched off thread, then sent like a typed prompt). */
    fun runPrompt(name: String, arguments: Map<String, String>) {
        val url = Bridge.mcpUrl ?: return send("/$name")
        val token = Bridge.token ?: return
        Chats.executor.execute {
            val text = try {
                McpClient(url, token).apply { initialize() }.prompt(name, arguments)
            } catch (e: Exception) {
                Minecraft.getInstance().execute { add(Entry(EntryKind.ERROR, "Prompt $name: ${e.message}")) }
                return@execute
            }
            Minecraft.getInstance().execute { send(text, "/$name " + arguments.values.joinToString(" · ")) }
        }
    }
}

/**
 * Every chat, newest first, whatever the server: each remembers the server it was last used on (its world) and is told
 * when it continues on another. Saved in `config/foreman/chats/<server it started on>/<id>.json`.
 */
object Chats {
    private val logger = LoggerFactory.getLogger("foreman")
    internal val executor = Executors.newCachedThreadPool { Thread(it, "foreman-agent").apply { isDaemon = true } }

    val list = mutableListOf<Chat>()
    /** Bumped when any chat changes, so screens know when to re-layout. */
    var version = 0
    /** The server played on now: its address, or `singleplayer`. */
    var server = "singleplayer"
        private set
    /** The folder each chat's file is in (the server it started on). */
    private val folders = HashMap<String, Path>()

    var prompts: List<McpClient.Prompt> = emptyList()
        private set

    val running get() = list.filter { it.running }

    fun init() {
        load()
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            client.execute { server = client.currentServer?.ip ?: "singleplayer"; version++ }
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            list.forEach { it.cancel(); save(it) }
            prompts = emptyList()
        }
        // Agent processes would outlive the game.
        ClientLifecycleEvents.CLIENT_STOPPING.register { list.forEach { it.cancel() } }
    }

    /** One HUD line per working chat. */
    fun status(): List<String> = running.map { "AI · ${it.data.title.take(24)} · ${it.status()}" }

    fun create(): Chat {
        val chat = Chat(ChatData(backend = Config.settings.agent.backend, workspaces = Config.settings.workspaces.filter { it.default }.map { it.name }, server = server))
        list.add(0, chat)
        version++
        save(chat)
        return chat
    }

    /** The chat to open by default: the latest one, a new one when there is none. */
    fun latest(): Chat = list.firstOrNull() ?: create()

    fun delete(chat: Chat) {
        chat.cancel()
        list.remove(chat)
        version++
        folders.remove(chat.id)?.let { runCatching { Files.deleteIfExists(it.resolve("${chat.id}.json")) } }
    }

    private fun dir(server: String) = Config.dir.resolve("chats").resolve(server.replace(Regex("[^A-Za-z0-9._-]"), "_"))

    /** Every saved chat, once at start. */
    private fun load() {
        val root = Config.dir.resolve("chats")
        if (!Files.isDirectory(root)) return
        Files.list(root).use { dirs -> dirs.filter { Files.isDirectory(it) }.toList() }.forEach { dir ->
            Files.list(dir).use { files -> files.filter { it.toString().endsWith(".json") }.toList() }.forEach { file ->
                try {
                    val chat = Chat(JSON.decodeFromString(ChatData.serializer(), Files.readString(file)))
                    list += chat
                    folders[chat.id] = dir
                } catch (e: Exception) {
                    logger.warn("Could not read {}", file, e)
                }
            }
        }
        list.sortByDescending { it.data.updated }
        version++
    }

    fun save(chat: Chat) {
        try {
            val file = folders.getOrPut(chat.id) { dir(server) }.resolve("${chat.id}.json")
            Files.createDirectories(file.parent)
            // Images sent back to OpenRouter are not kept: they are large and only matter for the turn they were in.
            val history = chat.data.openRouter.map { m ->
                val content = m["content"]
                if (content is JsonArray) JsonObject(m + ("content" to JsonPrimitive(
                    content.mapNotNull { (it as? JsonObject)?.get("text")?.let { t -> (t as? JsonPrimitive)?.content } }.joinToString("\n") + " [images not kept]"
                ))) else m
            }.toMutableList()
            val saved = chat.data.copy(entries = chat.data.entries.takeLast(500).toMutableList(), openRouter = history)
            Files.writeString(file, JSON.encodeToString(ChatData.serializer(), saved))
        } catch (e: Exception) {
            logger.warn("Could not save the chat", e)
        }
    }

    /** Loads the server's MCP prompts for the quick actions (off thread). */
    fun loadPrompts() {
        val url = Bridge.mcpUrl ?: return
        val token = Bridge.token ?: return
        executor.execute {
            val loaded = try {
                McpClient(url, token).apply { initialize() }.prompts()
            } catch (e: Exception) {
                logger.warn("Could not list MCP prompts", e)
                emptyList()
            }
            Minecraft.getInstance().execute { prompts = loaded; version++ }
        }
    }
}
