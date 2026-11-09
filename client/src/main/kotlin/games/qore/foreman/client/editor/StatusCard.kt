package games.qore.foreman.client.editor

import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The current tool's [ToolStatus] at the top left of the screen, in the editor panel's look: its title, what is
 * selected (its kind in a chip, then its name and detail), what the crosshair is on, a question waiting for an answer
 * on a highlighted row, what the mouse and keys do now, then the notes. Drawn by the HUD, hidden with it (F1).
 */
object StatusCard {
    private const val PAD = 6
    private const val ROW = 11
    private const val HINT_ROW = 13
    private const val MIN_W = 150
    private const val MAX_W = 260
    private const val BACKGROUND = 0xD812151C.toInt()
    private const val PROMPT = 0x50FBBF24

    /** Draws [tool]'s card at ([x], [y]), within [room] wide and [roomHigh] high (drawn smaller beyond); returns the height it took. */
    fun draw(g: GuiGraphicsExtractor, ui: Ui, x: Int, y: Int, room: Int, roomHigh: Int, tool: EditorTool): Int {
        val s = tool.status()
        val font = ui.font
        fun chipW(text: String) = font.width(text) + 8
        fun shownW(label: String?, it: Shown) = (label?.let { font.width(it) + 4 } ?: 0) + chipW(it.kind) + 4 + font.width(it.name) + 4 + font.width(it.detail)
        val wanted = listOfNotNull(
            font.width(tool.title),
            s.selected?.let { shownW(null, it) },
            s.empty?.let(font::width),
            s.hovered?.let { shownW("Looking at", it) },
            s.prompt?.let { font.width(it) + 8 },
        ) + s.details.map(font::width) + s.hints.map { chipW(it.input) + 4 + font.width(it.does) } + s.notes.map { font.width(it.text) }
        val inner = wanted.max().coerceIn(MIN_W, MAX_W)
        val w = inner + 2 * PAD
        val h = height(s)
        val scale = minOf(1f, roomHigh.toFloat() / h, room.toFloat() / w)
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), y.toFloat())
        g.pose().scale(scale, scale)

        ui.card(0, 0, w, h, BACKGROUND, Theme.BORDER)
        var cy = PAD
        ui.text(ui.fit(tool.title, inner), PAD, cy, Theme.ACCENT)
        cy += ROW + 2
        val selected = s.selected
        if (selected != null) {
            shown(ui, cy, inner, null, selected, Theme.TEXT)
            cy += HINT_ROW
        } else s.empty?.let {
            ui.text(ui.fit(it, inner), PAD, cy, Theme.MUTED)
            cy += ROW
        }
        for (line in s.details) {
            ui.text(ui.fit(line, inner), PAD, cy, Theme.MUTED)
            cy += ROW
        }
        s.hovered?.let {
            shown(ui, cy, inner, "Looking at", it, Theme.MUTED)
            cy += HINT_ROW
        }
        if (s.prompt != null || s.hints.isNotEmpty()) {
            cy = divider(ui, cy, w)
            s.prompt?.let {
                ui.rect(2, cy - 2, w - 4, ROW + 3, PROMPT)
                ui.text(ui.fit(it, inner), PAD, cy, Theme.WARN)
                cy += ROW + 3
            }
            for (hint in s.hints) {
                val cw = ui.chip(PAD, cy, hint.input, Theme.ACCENT)
                ui.text(ui.fit(hint.does, inner - cw - 4), PAD + cw + 4, cy + 2, Theme.MUTED)
                cy += HINT_ROW
            }
        }
        if (s.notes.isNotEmpty()) {
            cy = divider(ui, cy, w)
            for (note in s.notes) {
                ui.text(ui.fit(note.text, inner), PAD, cy, color(note.tone))
                cy += ROW
            }
        }
        g.pose().popMatrix()
        return (h * scale).toInt()
    }

    private fun height(s: ToolStatus): Int {
        var h = PAD + ROW + 2
        h += if (s.selected != null) HINT_ROW else if (s.empty != null) ROW else 0
        h += s.details.size * ROW + (if (s.hovered != null) HINT_ROW else 0)
        if (s.prompt != null || s.hints.isNotEmpty()) h += 4 + (if (s.prompt != null) ROW + 3 else 0) + s.hints.size * HINT_ROW
        if (s.notes.isNotEmpty()) h += 4 + s.notes.size * ROW
        return h + PAD - 2
    }

    /** A thing's row: [label] first when given, its kind in a chip, its name in [nameColor], its detail. */
    private fun shown(ui: Ui, y: Int, inner: Int, label: String?, it: Shown, nameColor: Int) {
        var x = PAD
        label?.let {
            ui.text(it, x, y + 2, Theme.FAINT)
            x += ui.font.width(it) + 4
        }
        x += ui.chip(x, y, it.kind, if (label == null) Theme.ACCENT else Theme.MUTED) + 4
        val left = PAD + inner - x
        val name = ui.fit(it.name, left)
        ui.text(name, x, y + 2, nameColor)
        x += ui.font.width(name) + 4
        if (it.detail.isNotEmpty() && PAD + inner - x > 12) ui.text(ui.fit(it.detail, PAD + inner - x), x, y + 2, Theme.FAINT)
    }

    private fun color(tone: Note.Tone) = when (tone) {
        Note.Tone.OK -> Theme.OK
        Note.Tone.ERROR -> Theme.ERROR
        Note.Tone.BUSY -> Theme.WARN
        Note.Tone.INFO -> Theme.MUTED
    }

    private fun divider(ui: Ui, y: Int, w: Int): Int {
        ui.rect(PAD, y, w - 2 * PAD, 1, Theme.BORDER)
        return y + 4
    }
}
