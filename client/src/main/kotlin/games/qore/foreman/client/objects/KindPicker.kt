package games.qore.foreman.client.objects

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.ObjectKind
import games.qore.foreman.client.models.ModelsPanel
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.ui.kit.filterChips
import games.qore.foreman.client.ui.kit.grid
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.util.Locale

/**
 * The objects tool's palette: the kinds of objects the server's plugins declare, as cards drawn like the Models page's
 * (a kind's icon model in 3D, else a drawing of its shape in its colour), by category, filtered by the search (name, id,
 * category, description) and the category chips. A click (or Enter, the first card) starts placing one. The search and
 * the category stay for the next time.
 */
class KindPicker : KitScreen("New object") {
    private var search: EditBox? = null

    private val boxW get() = minOf(width - 40, 720)
    private val bx get() = (width - boxW) / 2
    private val by = 20
    private val boxH get() = height - 40

    override fun build() {
        search = addRenderableWidget(EditBox(font, bx + boxW - 10 - SEARCH_W, by + 8, SEARCH_W, 16, Component.literal("Search")).also {
            it.setMaxLength(128)
            it.setHint(Component.literal("Search: name, category…"))
            it.value = query
            it.setResponder { v ->
                query = v
                resetScroll(SCROLL)
            }
            setInitialFocus(it)
        })
    }

    /** The kinds shown: those of the category picked matching the search, by category then name. */
    private fun shown(): List<ObjectKind> {
        val words = query.trim().lowercase(Locale.ROOT).split(' ').filter { it.isNotEmpty() }
        return Bridge.objectKinds.filter { k ->
            (category == null || k.group == category) && words.all { w -> listOf(k.name, k.id, k.group, k.description).any { it.lowercase(Locale.ROOT).contains(w) } }
        }.sortedWith(compareBy({ it.group.lowercase(Locale.ROOT) }, { it.name.lowercase(Locale.ROOT) }))
    }

    override fun draw(ui: Ui) {
        val kinds = Bridge.objectKinds
        if (category != null && kinds.none { it.group == category }) category = null
        ui.card(bx, by, boxW, boxH, Theme.PANEL, Theme.ACCENT_DARK)
        ui.title("New object", bx + 10, by + 8)
        val shown = shown()
        ui.text("${shown.size} of ${kinds.size} kinds", bx + 10 + (ui.font.width("New object") * 1.5).toInt() + 10, by + 12, Theme.MUTED)
        var top = by + 30
        val categories = kinds.groupingBy { it.group }.eachCount().toList().sortedBy { it.first.lowercase(Locale.ROOT) }
        if (categories.size > 1) {
            val options = listOf(ALL to "All ${kinds.size}") + categories.map { (c, n) -> c to "$c $n" }
            top += ui.filterChips(bx + 10, top, boxW - 20, options, category ?: ALL, 3, { c, on ->
                if (c == ALL || on) "Click: every category" else "Only $c"
            }) { picked ->
                category = picked?.takeIf { it != ALL }
                resetScroll(SCROLL)
            }
        }
        val bottom = by + boxH - 26
        if (shown.isEmpty()) {
            ui.paragraph(if (kinds.isEmpty()) "No plugin on this server declares object kinds." else "No kind matches the search and the category.",
                bx + 10, top + 4, boxW - 20, Theme.MUTED)
        } else ui.scroll(SCROLL, bx + 10, top, boxW - 20, bottom - top) { start ->
            var cy = start
            for ((group, list) in shown.groupBy { it.group }) {
                ui.text(group, bx + 12, cy + 4, Theme.ACCENT)
                ui.text("${list.size}", bx + 18 + ui.font.width(group), cy + 4, Theme.FAINT)
                cy += 16
                cy += ui.grid(bx + 10, cy, boxW - 28, list, 76, 6, top..bottom, { it + 24 }) { k, x, y, w, h -> card(ui, k, x, y, w, h) } + 10
            }
            cy - start
        }
        ui.text("Click one to place it · Enter: the first · Esc closes", bx + 10, by + boxH - 18, Theme.FAINT)
        ui.button(bx + boxW - 70, by + boxH - 22, 60, 16, "Close", Style.GHOST) { onClose() }
    }

    private fun card(ui: Ui, kind: ObjectKind, x: Int, y: Int, w: Int, h: Int) {
        val tooltip = listOf(kind.name, kind.id, kind.description, listOfNotNull(kind.owner.ifEmpty { null }, if (kind.isLine) "line: its two ends" else "point")
            .joinToString(" · ")).filter { it.isNotEmpty() }.joinToString("\n")
        val icon = kind.icon?.let(Identifier::tryParse)
        val color = kind.argb ?: Theme.MUTED
        ModelsPanel.modelCard(ui, x, y, w, h, icon, kind.name, tooltip, fallback = { fx, fy, side -> shape(ui, kind, color, fx, fy, side) }) { pick(kind) }
        ui.swatch(x + w - 12, y + 6, 6, color)
    }

    /** A kind without an icon: a point as a marker on the ground, a line as a line between two knobs, in its colour. */
    private fun shape(ui: Ui, kind: ObjectKind, color: Int, x: Int, y: Int, side: Int) {
        val c = x + side / 2
        val m = y + side / 2
        if (kind.isLine) {
            val r = side / 2 - 10
            for (i in -r..r step 2) ui.rect(c + i - 1, m - i - 1, 2, 2, color)
            for ((kx, ky) in listOf(c - r to m + r, c + r to m - r)) {
                ui.rect(kx - 4, ky - 4, 8, 8, color)
                ui.rect(kx - 2, ky - 2, 4, 4, Theme.FIELD)
            }
        } else {
            val r = side / 5
            ui.rect(c - r * 2, m + r + 2, r * 4, 1, Colors.withAlpha(color, 0.5f))
            ui.rect(c - 1, m - r, 2, r * 2 + 2, Colors.withAlpha(color, 0.7f))
            ui.rect(c - r / 2 - 2, m - r - r / 2 - 2, r + 4, r + 4, color)
            ui.rect(c - r / 2, m - r - r / 2, r, r, Theme.FIELD)
        }
    }

    private fun pick(kind: ObjectKind) {
        onClose()
        ObjectsTool.start(kind)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isConfirmation) {
            shown().firstOrNull()?.let(::pick)
            return true
        }
        return super.keyPressed(event)
    }

    private companion object {
        const val SCROLL = "kinds"
        const val SEARCH_W = 180
        /** The chip of every category. */
        const val ALL = "\u0000all"
        /** The search and the category picked, kept for the next time. */
        var query = ""
        var category: String? = null
    }
}
