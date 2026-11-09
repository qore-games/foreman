package games.qore.foreman.client.regions

import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui

/**
 * A pop-over list of the regions panels to pick one of [options] from, below [x], [y] and kept on screen: declared
 * types, choices, area names. Picking one runs [onPick] with its value; clicking beside it closes it.
 */
class PickList(private val x: Int, private val y: Int, private val w: Int, private val options: List<Option>, private val onPick: (String) -> Unit) {
    /**
     * A row: [value] picks it ([label] shown, [detail] faint on the right, [color] a swatch, [tooltip] on hover,
     * [current] lit); without a value it is a heading.
     */
    class Option(val value: String?, val label: String, val detail: String = "", val color: Int? = null, val tooltip: String? = null, val current: Boolean = false)

    /** Draws it over the panel; [close] closes it (a pick or a click beside it). */
    fun draw(ui: Ui, screenW: Int, screenH: Int, close: () -> Unit) {
        ui.backdrop(screenW, screenH, close)
        val h = (options.size * ROW + 6).coerceAtMost(screenH - 20)
        val top = y.coerceAtMost(screenH - h - 10).coerceAtLeast(10)
        val left = x.coerceAtMost(screenW - w - 10).coerceAtLeast(10)
        ui.card(left, top, w, h, Theme.PANEL, Theme.ACCENT_DARK)
        ui.scroll("pick", left + 1, top + 3, w - 2, h - 6) { start ->
            var ry = start
            for (o in options) {
                val shown = ry >= top && ry + ROW <= top + h
                if (o.value == null) ui.text(ui.fit(o.label, w - 12), left + 6, ry + 4, Theme.FAINT)
                else {
                    val hover = shown && ui.hovered(left + 1, ry, w - 2, ROW)
                    if (hover || o.current) ui.rect(left + 1, ry, w - 2, ROW, if (hover) Theme.PANEL_HOVER else Theme.ACCENT_DARK)
                    var tx = left + 8
                    o.color?.let {
                        ui.swatch(tx, ry + 3, 8, it)
                        tx += 12
                    }
                    val detailW = if (o.detail.isEmpty()) 0 else ui.font.width(o.detail).coerceAtMost(w / 2)
                    ui.text(ui.fit(o.label, left + w - 8 - detailW - 6 - tx), tx, ry + 3, Theme.TEXT)
                    if (detailW > 0) ui.text(ui.fit(o.detail, detailW), left + w - 8 - detailW, ry + 3, Theme.FAINT)
                    if (shown) ui.hit(left + 1, ry, w - 2, ROW, o.tooltip) {
                        close()
                        onPick(o.value)
                    }
                }
                ry += ROW
            }
            ry - start
        }
    }

    private companion object {
        const val ROW = 14
    }
}
