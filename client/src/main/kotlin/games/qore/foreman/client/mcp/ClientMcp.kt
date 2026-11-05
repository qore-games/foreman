package games.qore.foreman.client.mcp

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.lean.Lean
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.newToken
import games.qore.foreman.client.util.str
import games.qore.foreman.client.util.writePrivateFile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.LevelLoadingScreen
import org.slf4j.LoggerFactory
import java.io.BufferedInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Executors

/** What a chat lets its client tools do (folders the file tools may touch, commands, screenshots). */
interface ToolScope {
    val folders: List<String>
    val allowCommands: Boolean
    val allowScreenshots: Boolean
}

/** A tool of the client MCP server. [run] gets the calling chat's scope and returns MCP content blocks. */
class ClientTool(
    val name: String,
    val description: String,
    val schema: JsonObject,
    /** Whether the chat lets the AI use it (listed only then). */
    val enabled: (ToolScope) -> Boolean = { true },
    /** Whether a call with these arguments acts on the game (takes the [ClientLock] lease) rather than only looking. */
    val acts: (JsonObject) -> Boolean = { true },
    val run: (ToolScope, JsonObject) -> List<JsonObject>,
)

class ToolFailure(message: String) : Exception(message)

/**
 * The client's own MCP server (Streamable HTTP, one JSON answer per request) on the loopback interface: the tools that
 * need the game client (screenshots, commands as the player, the camera, the player's context) and the chat's
 * workspace folders. Each chat has its path `/mcp/<chat id>`, so its scope applies; a random token guards it. Outside
 * agents use `/mcp/external` with the token of the connection file, which also opens `/stream` ([FrameStream]).
 * A minimal HTTP server: launcher runtimes don't always ship `jdk.httpserver`.
 */
object ClientMcp {
    private val logger = LoggerFactory.getLogger("foreman")
    val token: String = newToken()
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { Thread(it, "foreman-client-mcp").apply { isDaemon = true } }
    private val tools = LinkedHashMap<String, ClientTool>()
    private val scopes = HashMap<String, ToolScope>()
    /**
     * MCP sessions by id, given at `initialize`: who calls (a [ClientLock.Holder]). Kept in memory only, so after a
     * restart their ids are unknown and agents are told to start again (404), listing the tools again: a rebuilt mod's
     * new tools reach sessions that started before.
     */
    private val sessions: MutableMap<String, ClientLock.Holder> = java.util.Collections.synchronizedMap(object : LinkedHashMap<String, ClientLock.Holder>() {
        // a few dozen at most: the oldest dropped only starts again
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClientLock.Holder>) = size > MAX_SESSIONS
    })

    val port get() = socket?.localPort ?: 0

    fun url(chat: String) = "http://127.0.0.1:$port/mcp/$chat"

    /** Every client tool (whatever chats allow). */
    fun tools(): List<ClientTool> = tools.values.toList()

    fun register(tool: ClientTool) {
        tools[tool.name] = tool
    }

    /** The scope used for calls on `/mcp/<id>` (a chat registers itself before each run). */
    fun scope(id: String, scope: ToolScope) = synchronized(scopes) { scopes[id] = scope }

    /**
     * Outside access as the launch's system properties set it, for scripts and launchers: over the settings, for this
     * launch only (nothing saved). Null when not set.
     */
    object Launch {
        /** `-Dforeman.mcp.external=true`: outside agents may connect, without the hub's switch. */
        val external: Boolean? = System.getProperty("foreman.mcp.external")?.trim()?.toBooleanStrictOrNull()
        /** `-Dforeman.mcp.port=<n>`: the client MCP server's port, 0 for any free one. */
        val port: Int? = System.getProperty("foreman.mcp.port")?.trim()?.toIntOrNull()?.takeIf { it in 0..65535 }
        /** `-Dforeman.mcp.allow=commands,screenshots`: what outside agents may do, only what is listed (empty: neither). */
        val allow: Set<String>? = System.getProperty("foreman.mcp.allow")?.split(',')?.map { it.trim().lowercase() }?.filter { it.isNotEmpty() }?.toSet()
    }

    /** Whether outside agents may use the client's tools: the hub's switch, or `-Dforeman.mcp.external`. */
    val externalEnabled get() = Launch.external ?: Config.settings.external.enabled

    /** Outside sessions' scope: no workspace folder (they have their own file tools), commands and screenshots as allowed. */
    private object External : ToolScope {
        override val folders: List<String> get() = emptyList()
        override val allowCommands get() = Launch.allow?.contains("commands") ?: Config.settings.external.allowCommands
        override val allowScreenshots get() = Launch.allow?.contains("screenshots") ?: Config.settings.external.allowScreenshots
    }

    val externalUrl get() = "http://127.0.0.1:$port/mcp/external"

    /** Turns outside access on or off, and writes (or removes) the file that says how to connect. */
    fun setExternal(enabled: Boolean) {
        Config.update {
            external.enabled = enabled
            if (enabled && external.token.isEmpty()) external.token = newToken()
        }
        writeConnection()
    }

    /**
     * Where the game is, in the connection file, for a launcher waiting until it can test: `starting` (loading),
     * `menu` (no world: the title screen, or a server that refused it), `joining`, `in_game`, `stopped`.
     */
    @Volatile
    private var state = "starting"
    /** When the server started listening, in the connection file: with `pid`, it tells a stale file from a live one. */
    private var startedAt = ""

    private fun state(minecraft: Minecraft): String {
        val screen = minecraft.gui.screen()
        return when {
            minecraft.level != null && minecraft.player != null && screen !is LevelLoadingScreen -> "in_game"
            minecraft.level != null || screen is ConnectScreen || screen is LevelLoadingScreen -> "joining"
            state == "starting" && (minecraft.gui.overlay() != null || screen == null) -> "starting"
            else -> "menu"
        }
    }

    private fun updateState(minecraft: Minecraft) {
        // `stopped` is the last word: the game may still tick while its shutdown hooks run.
        if (!externalEnabled || state == "stopped") return
        val now = state(minecraft)
        if (now == state) return
        state = now
        writeConnection()
    }

    /** `claude mcp add …`: how an outside Claude Code session connects. */
    fun externalCommand() = "claude mcp add --transport http foreman-client $externalUrl --header \"Authorization: Bearer ${Config.settings.external.token}\""

    private fun writeConnection() {
        val file = Config.dir.resolve("client-mcp.json")
        try {
            if (!externalEnabled || socket == null) return run { Files.deleteIfExists(file) }
            val json = buildJsonObject {
                put("url", externalUrl)
                put("token", Config.settings.external.token)
                put("claude", externalCommand())
                put("state", state)
                put("pid", ProcessHandle.current().pid())
                put("lean", Lean.enabled)
                put("version", FabricLoader.getInstance().getModContainer("foreman").map { it.metadata.version.friendlyString }.orElse(""))
                put("started_at", startedAt)
            }
            writePrivateFile(file, json.toString())
        } catch (e: Exception) {
            logger.warn("Could not write {}", file, e)
        }
    }

    /** Starts the server, once every tool is registered (they are read from its threads). */
    fun start() {
        if (socket != null) return
        if (externalEnabled && Config.settings.external.token.isEmpty()) Config.update { external.token = newToken() }
        // A fixed port, so outside sessions keep their address from one launch to the next.
        val server = runCatching { ServerSocket(Launch.port ?: Config.settings.external.port, 50, InetAddress.getLoopbackAddress()) }.getOrNull()
            ?: ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        socket = server
        startedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
        writeConnection()
        ClientTickEvents.END_CLIENT_TICK.register(::updateState)
        // quitting, closing the window, or a SIGTERM
        Runtime.getRuntime().addShutdownHook(Thread({
            state = "stopped"
            if (externalEnabled) writeConnection()
        }, "foreman-client-mcp-stopped"))
        Thread({
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: Exception) { break }
                pool.execute { handle(client) }
            }
        }, "foreman-client-mcp-accept").apply { isDaemon = true; start() }
        logger.info("Client MCP server on {}", url("<chat>"))
    }

    private fun handle(socket: Socket): Unit = socket.use {
        try {
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val requestLine = readLine(input) ?: return
            val headers = HashMap<String, String>()
            while (headers.size < 64) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val i = line.indexOf(':')
                if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
            }
            val (method, path) = requestLine.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
            // MCP clients send no Origin: a web page must not reach the game, even from this machine.
            if (headers["origin"] != null) return respond(socket, 403, null)
            val stream = path.substringBefore('?') == "/stream"
            val external = stream || path == "/mcp/external"
            val expected = if (external) Config.settings.external.token.takeIf { externalEnabled && it.isNotEmpty() } else token
            val sent = headers["authorization"]?.removePrefix("Bearer ")
            if (expected == null || sent == null || !MessageDigest.isEqual(sent.encodeToByteArray(), expected.encodeToByteArray())) {
                return respond(socket, 401, null)
            }
            // a live screenshot: refused where outside agents may not take screenshots
            if (stream) return if (External.allowScreenshots) FrameStream.serve(socket, method, path.substringAfter('?', "")) else respond(socket, 403, null)
            val length = headers["content-length"]?.toIntOrNull() ?: 0
            if (length > MAX_BODY) return respond(socket, 413, null)
            val body = input.readNBytes(length).decodeToString()
            if (!path.startsWith("/mcp/")) return respond(socket, 404, null)
            val sessionId = headers["mcp-session-id"]
            val session = sessionId?.let { sessions[it] }
            // as the MCP SDK's servers answer: the agent starts a new session, and lists the tools again
            if (sessionId != null && session == null) return respond(socket, 404, NO_SESSION)
            if (method == "DELETE") {
                if (sessionId != null) sessions.remove(sessionId)?.let { ClientLock.ended(it) }
                return respond(socket, 200, null)
            }
            // no stream of server messages: nothing is sent unasked
            if (method != "POST") return respond(socket, 405, null, "Allow: POST, DELETE")
            val scope = if (external) External else synchronized(scopes) { scopes[path.removePrefix("/mcp/")] } ?: return respond(socket, 404, null)
            val request = JSON.parseToJsonElement(body) as? JsonObject ?: return respond(socket, 400, null)
            if (request["method"].str() == "initialize") {
                val id = newToken()
                val client = request["params"]["clientInfo"]["name"].str() ?: "an agent"
                val holder = ClientLock.Holder("session:$id", "$client ${id.take(4)}" + if (external) "" else " (chat)")
                sessions[id] = holder
                return respond(socket, 200, dispatch(scope, request, holder).toString(), "Mcp-Session-Id: $id")
            }
            val holder = session ?: ClientLock.Holder(path, if (external) "an outside agent without an MCP session" else "a chat without an MCP session")
            val answer = dispatch(scope, request, holder)
            if (answer == null) respond(socket, 202, null) else respond(socket, 200, answer.toString())
        } catch (e: Exception) {
            logger.warn("Client MCP request failed", e)
            runCatching { respond(socket, 500, null) }
        }
    }

    private fun readLine(input: InputStream): String? {
        val out = StringBuilder()
        while (out.length < 8192) {
            val c = input.read()
            if (c == -1) return if (out.isEmpty()) null else out.toString()
            if (c == '\n'.code) return out.toString().trimEnd('\r')
            out.append(c.toChar())
        }
        return null
    }

    internal fun respond(socket: Socket, status: Int, json: String?, header: String? = null) {
        val bytes = json?.encodeToByteArray() ?: ByteArray(0)
        val head = "HTTP/1.1 $status ${if (status in 200..299) "OK" else "Error"}\r\n" +
            (if (json != null) "Content-Type: application/json\r\n" else "") +
            (header?.let { "$it\r\n" } ?: "") +
            "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.encodeToByteArray()); write(bytes); flush() }
    }

    private fun dispatch(scope: ToolScope, request: JsonObject, holder: ClientLock.Holder): JsonObject? {
        val id = request["id"] ?: return null
        val result: JsonElement = try {
            when (request["method"].str()) {
                "initialize" -> buildJsonObject {
                    put("protocolVersion", request["params"]["protocolVersion"].str() ?: "2025-06-18")
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject {}) })
                    put("serverInfo", buildJsonObject { put("name", "foreman-client"); put("version", "1.0") })
                    put("instructions", INSTRUCTIONS)
                }
                "ping" -> buildJsonObject {}
                "tools/list" -> buildJsonObject {
                    put("tools", buildJsonArray {
                        tools.values.filter { it.enabled(scope) }.forEach { t ->
                            add(buildJsonObject { put("name", t.name); put("description", t.description); put("inputSchema", t.schema) })
                        }
                    })
                }
                "tools/call" -> {
                    val name = request["params"]["name"].str()
                    val tool = tools[name]?.takeIf { it.enabled(scope) }
                        ?: return error(id, -32602, "Unknown tool $name (or not allowed in this chat)")
                    val args = request["params"]["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                    var counted = false
                    try {
                        counted = ClientLock.enter(holder, tool.acts(args))
                        ClientLock.caller.set(holder)
                        buildJsonObject { put("content", JsonArray(AgentActivity.call { tool.run(scope, args) })) }
                    } catch (e: ToolFailure) {
                        buildJsonObject { put("content", JsonArray(listOf(text(e.message ?: "failed")))); put("isError", true) }
                    } finally {
                        ClientLock.caller.remove()
                        if (counted) ClientLock.exit(holder)
                    }
                }
                else -> return error(id, -32601, "Method not found")
            }
        } catch (e: Exception) {
            logger.warn("Client MCP {} failed", request["method"], e)
            return error(id, -32603, e.message ?: e.javaClass.simpleName)
        }
        return buildJsonObject { put("jsonrpc", "2.0"); put("id", id); put("result", result) }
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0"); put("id", id)
        put("error", buildJsonObject { put("code", code); put("message", message) })
    }

    fun text(text: String) = buildJsonObject { put("type", "text"); put("text", text) }
    fun image(png: ByteArray) = buildJsonObject {
        put("type", "image"); put("mimeType", "image/png"); put("data", java.util.Base64.getEncoder().encodeToString(png))
    }

    private const val MAX_BODY = 4 shl 20
    private const val MAX_SESSIONS = 64
    private const val NO_SESSION = """{"jsonrpc":"2.0","error":{"code":-32001,"message":"Session not found"},"id":null}"""

    private const val INSTRUCTIONS = """The player's own Minecraft client. Its tools act as the player: client_screenshot shows you what the
player sees (use it to check a build or a place with your own eyes: teleport and look first), client_run_command runs a
command as the player (they have their permissions, the output is returned), client_teleport and client_look move the
player and the camera, client_context says where they are, what they look at and their selections. File tools work in
the folders the player added to this chat only. Tell the player before running a command that changes the world or
their inventory. client_screen, client_click, client_type and client_key use the open screen (menus, dialogs,
inventories); client_dialog_preview opens a dialog from its JSON locally, to check its layout without the server.
client_input holds key bindings across ticks, in steps (walking, sprinting, sneaking, a double jump, a dash, holding use
or attack), and client_key hold_ms one of them.
What the client knows, without asking the server: client_entities (the entities around the player or along the look,
packet-only ones too, with their synced data: display transformations, item models, texts, interaction sizes…),
client_item (an item's components), client_post_effects (applied and failed post effects), client_resource_packs (the
loaded packs; reload re-reads them and returns the errors) and client_log (the client's log lines: shader, model and
resource errors, since_reload after a resource pack reload). For network lag or client stutter, the packets_* tools
record what the server and the client send each other (packets_record), then show it (packets_summary first;
packets_list filters by type, time, text, or packets near another one). For HUD texts (action bar, titles, boss bars),
the ui_* tools show how the client lays them out glyph by glyph (ui_capture), what moves when they change (ui_watch),
and whether declared glyph widths match what the client draws (ui_check_widths). client_test runs a test of steps over
the client's and the server's tools in one call and answers a line a step: save each check you repeat as a test (`name`
with `steps`) and rerun it by name; `action: guide` first says how to write tests that hold. client_trace records the client's view tick by tick (server_trace, on a server with
Foreman, the server's); client_record records what the player does and client_replay plays it back, a test. One
session drives the client at a time: a tool that acts takes it until 2 minutes after your last call, others are refused
meanwhile (client_lock: status, acquire for longer, release when done, wait for it). While a session uses these tools,
the game doesn't pause when its window loses focus."""
}
