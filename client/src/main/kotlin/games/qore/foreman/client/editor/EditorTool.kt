package games.qore.foreman.client.editor

import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.input.Tool
import games.qore.foreman.client.mc.Mc

/**
 * A key of a tool in editor mode, shown in the editor's panel: [key] is a GLFW key code (`InputConstants.KEY_*`),
 * pressed with [shift] / [ctrl] held or not. While editor mode is on, editor keys win over the game's bindings.
 */
class EditorKey(
    val key: Int,
    val label: String,
    val shift: Boolean = false,
    val ctrl: Boolean = false,
    val action: () -> Unit,
) {
    /** The key as the panel shows it: `V`, `Delete`, `Shift+Enter`. */
    val caption: String
        get() = (if (ctrl) "Ctrl+" else "") + (if (shift) "Shift+" else "") + Mc.keyboard.getOrCreate(key).displayName.string
}

/**
 * A tool of editor mode (displays, assets, areas and points, models, selections, locations…): an in-world [Tool] that
 * editor mode switches to, with the keys it takes while it is the current one.
 */
interface EditorTool : Tool {
    /** Stable id, for settings: `displays`, `assets`, `regions`, `models`, `selection`, `locations`… */
    val id: String

    /** Its keys while it is the current tool, in the order the panel shows them. */
    fun keys(): List<EditorKey> = emptyList()

    /** Whether it can be used here (a server tool it needs, a plugin…); the panel greys it out otherwise. */
    fun available(): Boolean = true

    /** What the snap key doesn't change for what it shows now (`Boxes: whole blocks`), under it in the panel. */
    fun snapNote(): String? = null

    /** Whose step the snap key cycles now: what its gizmo does (moves, unless it turns or scales). */
    fun snap(): Snapping.Kind = Snapping.Kind.MOVE

    /** What it says at the top left of the screen: what is selected, what to do now ([StatusCard]). */
    fun status(): ToolStatus = ToolStatus()
}

/** The tools of editor mode, in the order its panel lists them (the number keys pick them). */
object EditorTools {
    /** The panel's order by id; tools with another id come after these, in the order they were registered. */
    private val ORDER = listOf("selection", "displays", "models", "library", "assets", "regions", "measure", "locations", "worldedit")

    private val registered = mutableListOf<EditorTool>()

    val all: List<EditorTool> get() = registered

    fun register(tool: EditorTool) {
        if (registered.any { it.id == tool.id }) return
        registered += tool
        // Stable: unknown ids keep their registration order.
        registered.sortBy { ORDER.indexOf(it.id).takeIf { i -> i >= 0 } ?: ORDER.size }
    }

    fun byId(id: String): EditorTool? = registered.firstOrNull { it.id == id }
}
