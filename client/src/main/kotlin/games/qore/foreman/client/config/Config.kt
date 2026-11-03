package games.qore.foreman.client.config

import games.qore.foreman.client.util.PRETTY_JSON
import games.qore.foreman.client.util.writePrivateFile
import kotlinx.serialization.Serializable
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/** Where logged plugin messages are printed besides the hub's list. */
enum class MessageOutput(val label: String) { NONE("Nowhere"), CHAT("Chat"), CONSOLE("Console") }

@Serializable
data class PluginMessageSettings(
    /** Log plugin messages (custom payloads) both ways, in the hub's list. */
    var enabled: Boolean = false,
    var print: MessageOutput = MessageOutput.NONE,
)

@Serializable
data class PacketSettings(
    /** Packets a recording keeps (the oldest go first); totals always cover the whole recording. */
    var capacity: Int = 100_000,
    /** Keep what each packet says (its fields), not only its type and size. */
    var contents: Boolean = true,
)

@Serializable
data class LocationSettings(
    var throughBlocks: Boolean = false,
    var showSourceCategoryLabels: Boolean = false,
    /** Gizmo moves snap to this step (blocks); hold Ctrl to move freely. */
    var snap: Double = 0.25,
)

/** How much of a layer is drawn: nothing, what is around the player, or everything in range. */
enum class Visibility(val label: String) { OFF("off"), NEARBY("nearby"), ALWAYS("always") }

@Serializable
data class OverlaySettings(
    /** Every overlay at once (the hub's master switch). */
    var enabled: Boolean = true,
    /** The server's named areas: places, reserved space (claims), zones. */
    var areas: Visibility = Visibility.NEARBY,
    /** Spots builds recorded for gameplay (containers, spawns…). */
    var points: Visibility = Visibility.NEARBY,
    var issues: Visibility = Visibility.ALWAYS,
    var changes: Visibility = Visibility.ALWAYS,
    var locations: Visibility = Visibility.NEARBY,
    /** The WorldEdit selection, in every shape (when the server's WorldEdit sends it). */
    var worldEdit: Boolean = true,
    var throughBlocks: Boolean = false,
    /** Nearby: areas show from this far from their edge; points, issues and locations within twice it. */
    var nearDistance: Int = 32,
    /** Nearby: an area stays drawn until the player is this deep inside it, then fades out. */
    var insideBuffer: Int = 6,
    /** A notice on the HUD when the player enters or leaves an area. */
    var zoneNotices: Boolean = true,
    /** Labels show up to this distance (they keep one size: no shifting as you move). */
    var labelDistance: Int = 40,
    /** Always: nothing beyond this distance. */
    var maxDistance: Int = 192,
    /** The HUD lists the areas the player is in. */
    var hudAreas: Boolean = true,
)

enum class AgentBackend(val label: String) { CLAUDE_CODE("Claude Code"), OPENCODE("opencode"), OPENROUTER("OpenRouter") }

@Serializable
data class AgentSettings(
    var backend: AgentBackend = AgentBackend.CLAUDE_CODE,
    var claudeCommand: String = "claude",
    var claudeModel: String = "",
    var opencodeCommand: String = "opencode",
    var opencodeModel: String = "",
    var openRouterKey: String = "",
    var openRouterModel: String = "anthropic/claude-sonnet-4.5",
    /** Tool-call rounds before the OpenRouter loop stops. */
    var maxTurns: Int = 60,
    /** Overrides the MCP URL the server announces (when its public-url is wrong for this machine). */
    var mcpUrlOverride: String = "",
    /** Added to every prompt (house rules, language…). */
    var extraInstructions: String = "",
)

/**
 * AI sessions outside the game (Claude Code in a terminal, another agent on this computer) may use the client's tools
 * (UI inspector, packets, screenshots, commands as the player) at `http://127.0.0.1:<port>/mcp/external` with [token].
 * The address and token are written to `config/foreman/client-mcp.json`, readable by this user only.
 */
@Serializable
data class ExternalAccessSettings(
    var enabled: Boolean = false,
    var token: String = "",
    /** The client MCP server's port (chats use it too); another free one when it is taken. */
    var port: Int = 25601,
    var allowCommands: Boolean = true,
    var allowScreenshots: Boolean = true,
)

@Serializable
data class ReloadSettings(
    /** Resource reloads do their slow steps in parallel and off the main thread, and the loading screen closes as they end. */
    var fast: Boolean = true,
    /** With [fast]: the game stays on screen and playable while packs load; only the short apply at the end freezes it. */
    var keepPlaying: Boolean = true,
    /** A chat line after each reload: how long it took, and how long it froze the game. */
    var report: Boolean = true,
)

/** A named set of folders chats can use (a plugin's configs, a datapack, a repo…). Default ones go to every new chat. */
@Serializable
data class Workspace(
    var name: String,
    var folders: List<String> = emptyList(),
    var default: Boolean = false,
    /** Optional: how the AI works in this workspace, added to the prompt of every chat using it. */
    var instructions: String = "",
)

@Serializable
data class Settings(
    var workspaces: MutableList<Workspace> = mutableListOf(),
    var pluginMessages: PluginMessageSettings = PluginMessageSettings(),
    var packets: PacketSettings = PacketSettings(),
    var locations: LocationSettings = LocationSettings(),
    var overlays: OverlaySettings = OverlaySettings(),
    var agent: AgentSettings = AgentSettings(),
    var external: ExternalAccessSettings = ExternalAccessSettings(),
    var reload: ReloadSettings = ReloadSettings(),
)

/** `config/foreman/settings.json`, read once at start and written on every change (this user only: it holds a token). */
object Config {
    private val logger = LoggerFactory.getLogger("foreman")
    val dir: Path = FabricLoader.getInstance().configDir.resolve("foreman")
    private val file = dir.resolve("settings.json")

    var settings = Settings()
        private set

    fun load() {
        if (Files.exists(file)) {
            try {
                settings = PRETTY_JSON.decodeFromString(Settings.serializer(), Files.readString(file))
            } catch (e: Exception) {
                logger.warn("Could not read {}, using defaults", file, e)
            }
        }
        save()
    }

    fun save() {
        try {
            writePrivateFile(file, PRETTY_JSON.encodeToString(Settings.serializer(), settings))
        } catch (e: Exception) {
            logger.warn("Could not write {}", file, e)
        }
    }

    /** Changes settings and saves them. */
    inline fun update(change: Settings.() -> Unit) {
        settings.change()
        save()
    }
}
