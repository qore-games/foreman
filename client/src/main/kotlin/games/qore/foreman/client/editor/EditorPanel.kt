package games.qore.foreman.client.editor

import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * Editor mode's panel, at the right edge of the screen: the tools with their numbers (the current one lit, those not
 * available here greyed), the current tool's keys, the snap key with the step of what the gizmo does now (and what
 * the tool keeps to whole blocks), the overlay keys, then the editor's own. Hidden with the HUD (F1) and under screens.
 */
object EditorPanel {
    private const val PAD = 6
    private const val TOOL_ROW = 11
    private const val KEY_ROW = 13
    private const val MIN_W = 110
    private const val MAX_W = 190
    private const val BACKGROUND = 0xD812151C.toInt()
    /** The hotbar: half its width, and the height it takes at the bottom (with the hearts above it). */
    private const val HOTBAR_HALF = 91
    private const val HOTBAR_HEIGHT = 42

    /** How much of the screen's right side it took at its last frame (the status card stays left of it). */
    var drawnWidth = 0
        private set

    fun draw(g: GuiGraphicsExtractor) {
        drawnWidth = 0
        val tool = EditorMode.current ?: return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.screen() != null || minecraft.gui.hud.isHidden) return
        val ui = Ui(g, minecraft.font, -1, -1, mutableListOf(), HashMap())
        val font = ui.font
        val tools = EditorTools.all
        val keys = tool.keys()
        val snaps = EditorMode.keys()
        val snapNote = tool.snapNote()
        val own = listOfNotNull(EditorMode.binding.takeIf { !it.isUnbound }?.let { it.translatedKeyMessage.string to "leave" }, "Esc" to "back")
        val note = EditorMode.noteShown()

        fun chipW(caption: String) = font.width(caption) + 8
        val wanted = listOf(font.width("Editor")) +
            tools.map { 12 + font.width(it.title) } +
            (keys + snaps).map { chipW(it.caption) + 4 + font.width(it.label) } +
            listOf(own.sumOf { (k, l) -> chipW(k) + 3 + font.width(l) + 8 }) +
            listOfNotNull(note?.let(font::width))
        val inner = wanted.max().coerceIn(MIN_W, MAX_W)
        val w = inner + 2 * PAD
        val h = PAD + 12 + tools.size * TOOL_ROW + 4 + (if (keys.isEmpty()) 0 else keys.size * KEY_ROW + 4) + snaps.size * KEY_ROW + (if (snapNote != null) 11 else 0) + 4 +
            KEY_ROW + (if (note != null) 11 else 0) + PAD - 2

        // Above the hotbar when it reaches it; too tall for a small window or a big GUI scale: drawn smaller.
        val bottom = if (g.guiWidth() - 4 - w > g.guiWidth() / 2 + HOTBAR_HALF) g.guiHeight() - 4 else g.guiHeight() - HOTBAR_HEIGHT
        val room = bottom - 4
        val scale = if (h > room) room.toFloat() / h else 1f
        g.pose().pushMatrix()
        g.pose().translate(g.guiWidth() - 4 - w * scale, 4 + (room - h * scale) / 2f)
        drawnWidth = (w * scale).toInt() + 4
        g.pose().scale(scale, scale)

        ui.card(0, 0, w, h, BACKGROUND, Theme.BORDER)
        var y = PAD
        ui.text("Editor", PAD, y, Theme.ACCENT)
        y += 12
        tools.forEachIndexed { i, t ->
            val lit = t === tool
            val usable = t.available()
            if (lit) ui.rect(2, y - 2, w - 4, TOOL_ROW, Theme.PANEL_HOVER)
            if (i < 10) ui.text("${(i + 1) % 10}", PAD, y, if (lit) Theme.ACCENT else Theme.FAINT)
            ui.text(ui.fit(t.title, inner - 12), PAD + 12, y, when {
                lit -> Theme.TEXT
                usable -> Theme.MUTED
                else -> Theme.FAINT
            })
            y += TOOL_ROW
        }
        y = divider(ui, y, w)
        if (keys.isNotEmpty()) {
            for (key in keys) y += row(ui, y, inner, key.caption, key.label)
            y = divider(ui, y, w)
        }
        // The snap key, what it doesn't change for this tool under it, then the overlays.
        snaps.forEachIndexed { i, key ->
            y += row(ui, y, inner, key.caption, key.label)
            if (i == 0) snapNote?.let {
                ui.text(ui.fit(it, inner), PAD, y, Theme.FAINT)
                y += 11
            }
        }
        y = divider(ui, y, w)
        var x = PAD
        for ((key, label) in own) {
            x += ui.chip(x, y, key, Theme.ACCENT) + 3
            ui.text(label, x, y + 2, Theme.MUTED)
            x += font.width(label) + 8
        }
        y += KEY_ROW
        note?.let { ui.text(ui.fit(it, inner), PAD, y, Theme.WARN) }
        g.pose().popMatrix()
    }

    /** A key's row: its chip, then what it does cut to the room left. */
    private fun row(ui: Ui, y: Int, inner: Int, caption: String, label: String): Int {
        val cw = ui.chip(PAD, y, caption, Theme.ACCENT)
        ui.text(ui.fit(label, inner - cw - 4), PAD + cw + 4, y + 2, Theme.MUTED)
        return KEY_ROW
    }

    private fun divider(ui: Ui, y: Int, w: Int): Int {
        ui.rect(PAD, y, w - 2 * PAD, 1, Theme.BORDER)
        return y + 4
    }
}
