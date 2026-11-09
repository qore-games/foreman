package games.qore.foreman.client.editor

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.display.DisplayEditor
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.library.AssetTool
import games.qore.foreman.client.library.PlacementTool
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.link.WorldEditTool
import games.qore.foreman.client.locations.LocationEditor
import games.qore.foreman.client.measure.MeasureTool
import games.qore.foreman.client.models.ModelPlacementTool
import games.qore.foreman.client.overlays.DisplayBudget
import games.qore.foreman.client.overlays.HiddenBlocks
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier

/**
 * Editor mode: Foreman's building tools behind one key. B turns it on with the last tool used and a panel on the right
 * of the screen lists the tools (1-9 pick one) and the keys of the current one. While it is on, those keys are the
 * editor's: the keyboard mixin hands every key press here before the game's key bindings see it, and the editor's keys
 * go no further (F doesn't swap hands, 1-9 don't change the hotbar); every other key plays as usual. The editor's own
 * [keys] (the gizmos' [Snapping], two overlays) work with every tool. The current tool is [Tools.active], which gets
 * the clicks and the scroll wheel. The crosshair shows in spectator mode while it is on.
 */
object EditorMode {
    private val DIGITS = listOf(
        InputConstants.KEY_1, InputConstants.KEY_2, InputConstants.KEY_3, InputConstants.KEY_4, InputConstants.KEY_5,
        InputConstants.KEY_6, InputConstants.KEY_7, InputConstants.KEY_8, InputConstants.KEY_9,
    )
    private const val NOTE_MS = 3000L

    /** `key.foreman.editor`: on and off. */
    lateinit var binding: KeyMapping
        private set

    /** The current tool; null while editor mode is off. */
    val current: EditorTool? get() = Tools.active as? EditorTool

    val on: Boolean get() = current != null

    /** A line the panel shows a few seconds (a tool that can't be used here), and when it was set. */
    var note: Pair<String, Long>? = null
        private set

    fun init() {
        binding = Keys.bind("editor", Keys.Category.GENERAL, InputConstants.KEY_B, "Editor mode: the building tools and their keys") { toggle() }
        listOf(SelectionTool, DisplayEditor, ModelPlacementTool, PlacementTool, AssetTool, MeasureTool, LocationEditor, WorldEditTool).forEach(EditorTools::register)
        MeasureTool.init()
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath("foreman", "editor"), HudElement { g, _ -> EditorPanel.draw(g) })
    }

    /**
     * The editor's keys with every tool: the snap key, the step of what the tool's gizmo does now (moving, turning or
     * scaling) with its value; the hidden blocks and the display budget overlays, on or off.
     */
    fun keys(): List<EditorKey> {
        val snap = current?.snap()
        return listOf(
            EditorKey(InputConstants.KEY_G, snap?.let(Snapping::caption) ?: "Snap: the step of what the gizmo does") { Snapping.next(snap ?: Snapping.Kind.MOVE) },
            EditorKey(InputConstants.KEY_X, "Hidden blocks: " + if (HiddenBlocks.on) "on" else "off") { HiddenBlocks.toggle() },
            EditorKey(InputConstants.KEY_L, "Displays per chunk: " + if (DisplayBudget.on) "on" else "off") { DisplayBudget.toggle() },
        )
    }

    fun toggle() = if (on) leave() else open()

    /** Turns editor mode on with [tool], else with the last tool used (the first one available when it isn't). */
    fun open(tool: EditorTool? = null) {
        val last = EditorTools.byId(Config.settings.editor.tool)?.takeIf { it.available() }
        Tools.activate(tool ?: last ?: EditorTools.all.firstOrNull { it.available() } ?: return)
    }

    fun leave() = Tools.deactivate()

    /** [tool] became the current one: B opens it again next time, after a restart too. */
    fun used(tool: EditorTool) {
        note = null
        if (Config.settings.editor.tool != tool.id) Config.update { editor.tool = tool.id }
    }

    /** The keys that open [tool] for the hub's cards: the editor's key, then its number. */
    fun caption(tool: EditorTool): String {
        val number = EditorTools.all.indexOf(tool).takeIf { it in DIGITS.indices }?.let { "${it + 1}" }
        val key = binding.takeIf { !it.isUnbound }?.translatedKeyMessage?.string
        return listOfNotNull(key, number).joinToString(" ")
    }

    private fun pick(index: Int) {
        val tool = EditorTools.all.getOrNull(index) ?: return
        if (tool.available()) Tools.activate(tool)
        else note = "${tool.title}: not available here" to System.currentTimeMillis()
    }

    fun noteShown(): String? = note?.takeIf { System.currentTimeMillis() - it.second < NOTE_MS }?.first

    /**
     * A key [event] in game, before the game's bindings: true when editor mode takes it. Esc goes to the tool first,
     * else leaves; F3 combinations stay the game's; releases always go on (a held key the game saw is let go).
     */
    @JvmStatic
    fun onKey(action: Int, event: KeyEvent): Boolean {
        val tool = current ?: return false
        val minecraft = Minecraft.getInstance()
        if (action == InputConstants.RELEASE || minecraft.gui.screen() != null || minecraft.player == null) return false
        val press = action == InputConstants.PRESS
        if (event.isEscape) {
            if (press && !tool.escape()) leave()
            return true
        }
        if (minecraft.options.keyDebugModifier.isDown) return false
        when {
            binding.matches(event) -> if (press) leave()
            event.key() in DIGITS -> if (press) pick(DIGITS.indexOf(event.key()))
            else -> {
                val key = match(tool.keys() + keys(), event) ?: return false
                if (press) key.action()
            }
        }
        return true
    }

    /**
     * The key [event] presses: the one asking for exactly the modifiers held, else one asking for fewer of them; the
     * tool's before the editor's.
     */
    private fun match(keys: List<EditorKey>, event: KeyEvent): EditorKey? {
        val shift = event.hasShiftDown()
        val ctrl = event.hasControlDown()
        return keys.filter { it.key == event.key() && (!it.shift || shift) && (!it.ctrl || ctrl) }
            .maxByOrNull { (if (it.shift == shift) 1 else 0) + (if (it.ctrl == ctrl) 1 else 0) }
    }

    /** Spectator mode hides the crosshair: the editor aims with it. */
    @JvmStatic
    fun showsCrosshair(): Boolean = on
}
