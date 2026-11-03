package games.qore.foreman.server.api

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import java.util.function.Function

/**
 * Foreman's MCP server, for other plugins: publish tools the AI chats can call (they appear next to Foreman's own,
 * with your plugin as their provider, in `/foreman tools` and the client's hub). Get it as a Bukkit service:
 *
 * ```java
 * ForemanTools tools = Bukkit.getServicesManager().load(ForemanTools.class); // null without Foreman
 * tools.register(this, new ExternalTool("npc_list", "Lists the NPCs of a world", schema, false, call -> ...));
 * tools.registerCommand(this, "npc_reload", "Reloads the NPC configs", "npcs reload", Map.of(), false);
 * ```
 *
 * Depend on `games.qore:foreman-server-api` (compileOnly) and soft-depend on `Foreman`, and register in your
 * `onEnable` (Foreman enables first). A plugin's tools are removed when it disables, so a reload registers them again.
 * Handlers run off the main thread: use [onMainThread] for the world. Names are lowercase letters, digits and `_`;
 * Foreman's own (`edit`, `render`, `server_command`, `server_log`… every `games.qore.foreman.api.ForemanTool`) and
 * another plugin's are refused with an [IllegalArgumentException].
 */
interface ForemanTools {
    /** Publishes [tool] for [owner] (replacing one of the same name). */
    fun register(owner: Plugin, tool: ExternalTool)

    /**
     * A tool that runs a command: [command] with `{argument}` placeholders filled from the call (the [arguments]
     * map gives each one's description). Each value is one word, except the one whose placeholder ends the command
     * (free text). With [asPlayer] it runs as the calling player, who must be online; otherwise from the console, which
     * a player needs `foreman.admin` for, like `server_command`. The reply is what the command printed: its replies
     * (a player's read from what the server sends them), the log lines logged meanwhile, an unknown command or the
     * exception it threw.
     */
    fun registerCommand(owner: Plugin, name: String, description: String, command: String, arguments: Map<String, String>, asPlayer: Boolean)

    fun unregister(owner: Plugin, name: String)

    /**
     * Publishes a skill: how to do a kind of task with your plugin (its commands, configs, conventions), in markdown.
     * The AI sees [name] and [description] in its instructions and reads the [markdown] when a request matches. Names
     * are lowercase letters, digits and `-`. Without code, ship `foreman/skills/<name>.md` files in your jar instead
     * (front matter `name`, `description`): Foreman reads them while your plugin is enabled.
     */
    fun registerSkill(owner: Plugin, name: String, description: String, markdown: String)

    fun unregisterSkill(owner: Plugin, name: String)

    /**
     * Adds what your plugin knows of a player to `server_trace` (the server's view of a player tick by tick, for
     * movement and gameplay bugs): each tick a player is traced, [values] gives yours (numbers, texts, booleans,
     * vectors; null or empty when you have nothing), shown in its rows, and each number's range in its summary as
     * `<your plugin>.<key>`. Called on the server thread, only while a trace runs. One per plugin (a second replaces
     * the first), removed when your plugin disables.
     */
    fun registerTraceValues(owner: Plugin, values: Function<Player, Map<String, Any?>?>)

    fun unregisterTraceValues(owner: Plugin)

    /** Every tool with its provider (Foreman's own and other plugins'). */
    fun tools(): List<ToolInfo>

    /** Runs [block] on the server thread and waits for it. */
    fun <T> onMainThread(block: java.util.function.Supplier<T>): T
}

/**
 * A tool: [name] (letters, digits and `_`), what it does and when to use it ([description]: the AI reads it to choose),
 * its arguments as a JSON Schema object ([inputSchema], e.g. `{"type":"object","properties":{"world":{"type":"string"}}}`),
 * whether it changes the world ([modifies]: such calls are announced in game), and the [handler].
 */
class ExternalTool(
    val name: String,
    val description: String,
    val inputSchema: String,
    val modifies: Boolean,
    val handler: Function<ToolCall, ToolReply>,
)

/** A call: its [arguments] as JSON text and parsed ([argument]), and the [player] who asked when they are online. */
class ToolCall(val arguments: String, private val parsed: Map<String, Any?>, val player: Player?, val callerName: String?) {
    /** An argument: String, Double, Boolean, List, Map or null. */
    fun argument(name: String): Any? = parsed[name]
    fun string(name: String): String? = parsed[name]?.toString()
    fun number(name: String): Double? = (parsed[name] as? Number)?.toDouble() ?: parsed[name]?.toString()?.toDoubleOrNull()
}

/** What the AI gets back: text, and images (PNG bytes) it can look at. An error reply tells it the call failed. */
class ToolReply @JvmOverloads constructor(val text: String, val isError: Boolean = false, val images: List<ByteArray> = emptyList()) {
    companion object {
        @JvmStatic fun ok(text: String) = ToolReply(text)
        @JvmStatic fun error(text: String) = ToolReply(text, true)
    }
}

/** A published tool and who provides it. */
class ToolInfo(val name: String, val provider: String, val description: String, val modifies: Boolean)
