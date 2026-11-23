package games.qore.foreman.client.ui.kit

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong

/**
 * A number slid between [min] and [max]: dragged from one end to the other in whole [step]s, or clicked to type its raw
 * value (any value: the bar shows it within the range) in its [EditBox], which turns back into the slider when it loses
 * the focus (Enter, a click beside it). The value is the box's text; empty, the slider shows its default faintly. Drawn
 * every frame where the box would be ([draw]).
 */
class Slider(val min: Double, val max: Double, val step: Double, private val format: (Double) -> String) {
    /** Being typed in: its box shows in its place. */
    var typing = false
        private set
    /** Where the button went down while it is held, and whether the mouse has moved enough since to slide. */
    private var press: Double? = null
    private var slid = false

    /**
     * Draws it at [x], [y] in [w] by [h] in place of [box], both hidden when not [shown] (out of a scrolling body):
     * empty, its [default] faintly, else [none]. [onSlide] gets each value slid to, as the text put in the box;
     * [onSlid] runs when a slide ends (the button let go): what applies a form that waits for Enter.
     */
    fun draw(
        ui: Ui, x: Int, y: Int, w: Int, box: EditBox, shown: Boolean, default: Double? = null, none: String = "", h: Int = 16,
        onSlide: (String) -> Unit = {}, onSlid: () -> Unit = {},
    ) {
        if (typing && !box.isFocused) {
            typing = false
            if (typed === box) typed = null
        }
        box.setPosition(x, y)
        box.setWidth(w)
        box.visible = shown && typing
        if (typing || !shown) return
        val value = box.value.trim().replace(',', '.').toDoubleOrNull()
        val current = value ?: default
        val hover = ui.hovered(x, y, w, h)
        ui.card(x, y, w, h, Theme.FIELD, if (hover || press != null) Theme.ACCENT else Theme.BORDER)
        if (current != null) {
            val fill = ((w - 2) * ((current - min) / (max - min)).coerceIn(0.0, 1.0)).toInt()
            ui.rect(x + 1, y + 1, fill, h - 2, if (value != null) Theme.ACCENT_DARK else Theme.PANEL_HOVER)
            ui.rect(x + fill, y + 1, 2, h - 2, if (value != null) Theme.ACCENT else Theme.MUTED)
        }
        val label = ui.fit(current?.let { format(it) + if (value == null) "  default" else "" } ?: none, w - 8)
        ui.text(label, x + (w - ui.font.width(label)) / 2, y + (h - 8) / 2, if (value != null) Theme.TEXT else Theme.FAINT)
        ui.drag(x, y, w, h, "${format(min)} – ${format(max)}: drag it, or click it to type any value", onRelease = {
            if (slid) onSlid() else type(box)
            press = null
            slid = false
        }) { mx, _ ->
            val from = press
            if (from == null) {
                press = mx
                slid = false
            } else if (slid || abs(mx - from) >= DEAD_ZONE) {
                slid = true
                val text = format(snap(min + (mx - x - 1) / (w - 2).coerceAtLeast(1) * (max - min)))
                if (box.value != text) {
                    box.value = text
                    onSlide(text)
                }
            }
        }
    }

    /** [v] in whole steps, within the range. */
    private fun snap(v: Double) = ((v / step).roundToLong() * step).coerceIn(min, max)

    /** Shows its box in its place, focused, its text selected. */
    private fun type(box: EditBox) {
        typing = true
        typed = box
        box.visible = true
        Minecraft.getInstance().gui.screen()?.focused = box
        box.moveCursorToEnd(false)
        box.setHighlightPos(0)
    }

    companion object {
        /** How far the mouse moves (GUI px) before a click becomes a slide. */
        private const val DEAD_ZONE = 3.0

        /** The box of the slider being typed in: a click beside it turns it back into the slider ([KitScreen]). */
        var typed: EditBox? = null
            private set

        /** A step for a slider over [min]..[max]: a round power of ten, about a pixel's worth; at least 1 for [whole] numbers. */
        fun step(min: Double, max: Double, whole: Boolean): Double {
            val step = 10.0.pow(floor(log10(((max - min) / 200.0).coerceAtLeast(1e-6))))
            return if (whole) step.coerceAtLeast(1.0).roundToLong().toDouble() else step
        }
    }
}
