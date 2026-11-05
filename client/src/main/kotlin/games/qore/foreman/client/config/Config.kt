package games.qore.foreman.client.config

import games.qore.foreman.client.util.PRETTY_JSON
import games.qore.foreman.client.util.writePrivateFile
import kotlinx.serialization.SerialName
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

/**
 * An AI backend: the [models] its chats' picker suggests (any other can be typed) and the [efforts] it takes: Claude
 * Code's `--effort`, opencode's model variant (`provider/model#variant`), OpenRouter's reasoning effort.
 */
enum class AgentBackend(val label: String, val models: List<String>, val efforts: List<String>) {
    CLAUDE_CODE("Claude Code", listOf("opus", "sonnet", "haiku", "fable"), listOf("low", "medium", "high", "xhigh", "max")),
    OPENCODE("opencode", emptyList(), listOf("low", "medium", "high", "max")),
    OPENROUTER("OpenRouter", listOf("anthropic/claude-sonnet-5.5", "anthropic/claude-opus-5.5", "anthropic/claude-haiku-5.5"), listOf("low", "medium", "high")),
}

@Serializable
data class AgentSettings(
    var backend: AgentBackend = AgentBackend.CLAUDE_CODE,
    var claudeCommand: String = "claude",
    var claudeModel: String = "",
    var claudeEffort: String = "",
    var opencodeCommand: String = "opencode",
    var opencodeModel: String = "",
    var opencodeEffort: String = "",
    var openRouterKey: String = "",
    var openRouterModel: String = "anthropic/claude-sonnet-5.5",
    var openRouterEffort: String = "",
    /** Tool-call rounds before the OpenRouter loop stops. */
    var maxTurns: Int = 60,
    /** Overrides the MCP URL the server announces (when its public-url is wrong for this machine). */
    var mcpUrlOverride: String = "",
    /** Added to every prompt (house rules, language…). */
    var extraInstructions: String = "",
) {
    /** The model of chats on [backend] that don't choose one (empty: the backend's own default). */
    fun model(backend: AgentBackend) = when (backend) {
        AgentBackend.CLAUDE_CODE -> claudeModel
        AgentBackend.OPENCODE -> opencodeModel
        AgentBackend.OPENROUTER -> openRouterModel
    }

    /** The effort of chats on [backend] that don't choose one (empty: the backend's own default). */
    fun effort(backend: AgentBackend) = when (backend) {
        AgentBackend.CLAUDE_CODE -> claudeEffort
        AgentBackend.OPENCODE -> opencodeEffort
        AgentBackend.OPENROUTER -> openRouterEffort
    }

    /** Makes [model] and [effort] the defaults of [backend]'s chats. */
    fun setDefaults(backend: AgentBackend, model: String, effort: String) = when (backend) {
        AgentBackend.CLAUDE_CODE -> { claudeModel = model; claudeEffort = effort }
        AgentBackend.OPENCODE -> { opencodeModel = model; opencodeEffort = effort }
        AgentBackend.OPENROUTER -> { openRouterModel = model; openRouterEffort = effort }
    }
}

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

/** Where a lean client's window goes: nowhere to be seen, in the taskbar, or on screen (frames on demand all the same). */
@Serializable
enum class LeanWindow { @SerialName("hidden") HIDDEN, @SerialName("minimized") MINIMIZED, @SerialName("shown") SHOWN }

/** What a lean client does with the resource packs a server sends: load them, or answer as loaded without them. */
@Serializable
enum class LeanPacks { @SerialName("load") LOAD, @SerialName("skip") SKIP }

/**
 * Lean mode, for clients that scripts and launchers start for automated tests (docs/ai/lean-clients.md): no window,
 * frames drawn only when a tool needs them, no sound device, light settings kept in memory. Read at start; the
 * `foreman.lean*` system properties win over it. Not in the hub: a hidden window couldn't turn it off again.
 */
@Serializable
data class LeanSettings(
    var enabled: Boolean = false,
    /** Frames drawn per second besides the ones tools ask for; 0: only those. */
    var fps: Int = 1,
    var window: LeanWindow = LeanWindow.HIDDEN,
    /** The render distance in chunks (the server sends no farther): 2 is the least; more for screenshots of a build. */
    var renderDistance: Int = 2,
    /** SKIP: the server's resource packs are answered as loaded, never downloaded (a bot doesn't draw them, a big set doesn't fit a small heap). */
    var packs: LeanPacks = LeanPacks.LOAD,
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
    var lean: LeanSettings = LeanSettings(),
)

/** `config/foreman/settings.json`, read once at start and written on every change (this user only: it holds a token). */
object Config {
    private val logger = LoggerFactory.getLogger("foreman")
    val dir: Path = FabricLoader.getInstance().configDir.resolve("foreman")
    private val file = dir.resolve("settings.json")

    var settings = Settings()
        private set
    private var loaded = false

    /** Reads the file, once (lean mode reads the settings early, while the game is still being built). */
    fun load() {
        if (loaded) return
        loaded = true
        if (Files.exists(file)) {
            try {
                settings = PRETTY_JSON.decodeFromString(Settings.serializer(), Files.readString(file))
            } catch (e: Exception) {
                // The defaults are saved over it next: keep what the player had, to fix by hand.
                val kept = file.resolveSibling("${file.fileName}.unreadable")
                runCatching { Files.copy(file, kept, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES) }
                logger.warn("Could not read {}, using defaults (the file is kept as {})", file, kept.fileName, e)
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
