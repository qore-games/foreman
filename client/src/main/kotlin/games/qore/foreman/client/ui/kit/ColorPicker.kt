package games.qore.foreman.client.ui.kit

import java.util.Locale

/**
 * A colour picker below [x], [y], kept on screen: saturation and brightness in a square, hue in a bar beside it (both
 * dragged), a row of common colours, and [default] when the field has one. It starts at [start] (RGB, else [default],
 * else white) and gives every change to [onChange] (RGB) as it goes; clicking beside it closes it.
 */
class ColorPicker(
    private val x: Int, private val y: Int, start: Int?, private val default: Int?, private val onChange: (Int) -> Unit,
) : Popover {
    private var hue = 0f
    private var sat = 0f
    private var value = 0f
    /** The colour as given (a preset, the default) or made by dragging: exact, not through hue, saturation and value. */
    private var rgb = 0

    init {
        set(start ?: default ?: 0xFFFFFF, tell = false)
    }

    /** The colour from the dragged hue, saturation and value. */
    private fun dragged() {
        rgb = toRgb(hue, sat, value)
        onChange(rgb)
    }

    override fun draw(ui: Ui, screenW: Int, screenH: Int, close: () -> Unit) {
        ui.backdrop(screenW, screenH, close)
        val left = x.coerceAtMost(screenW - W - 10).coerceAtLeast(10)
        val top = y.coerceAtMost(screenH - H - 10).coerceAtLeast(10)
        ui.card(left, top, W, H, Theme.PANEL, Theme.ACCENT_DARK)
        val sx = left + PAD
        val sy = top + PAD
        square(ui, sx, sy)
        bar(ui, sx + SQUARE_W + GAP, sy)
        val py = sy + SQUARE_H + GAP
        palette(ui, sx, py)
        val ry = py + SWATCH + GAP
        ui.swatch(sx, ry, SWATCH + 2, rgb)
        ui.text(hex(rgb), sx + SWATCH + 8, ry + 3, Theme.TEXT)
        default?.let { d ->
            val label = "Default"
            val bw = ui.font.width(label) + 22
            val bx = left + W - PAD - bw
            ui.button(bx, ry - 1, bw, 16, "", Style.GHOST, tooltip = "Back to the default: ${hex(d)}") { set(d) }
            ui.swatch(bx + 4, ry + 3, 8, d)
            ui.text(label, bx + 16, ry + 3, Theme.MUTED)
        }
    }

    /** Saturation left to right, brightness bottom to top, at the current hue. */
    private fun square(ui: Ui, x: Int, y: Int) {
        ui.rect(x - 1, y - 1, SQUARE_W + 2, SQUARE_H + 2, Theme.BORDER)
        for (i in 0 until SQUARE_W) ui.gradient(x + i, y, 1, SQUARE_H, opaque(toRgb(hue, i / (SQUARE_W - 1f), 1f)), BLACK)
        val mx = x + (sat * (SQUARE_W - 1)).toInt()
        val my = y + ((1 - value) * (SQUARE_H - 1)).toInt()
        val ring = if (value > 0.6f && sat < 0.5f) BLACK else WHITE
        ui.rect(mx - 3, my - 3, 7, 1, ring)
        ui.rect(mx - 3, my + 3, 7, 1, ring)
        ui.rect(mx - 3, my - 2, 1, 5, ring)
        ui.rect(mx + 3, my - 2, 1, 5, ring)
        ui.drag(x, y, SQUARE_W, SQUARE_H) { px, py ->
            sat = ((px - x) / (SQUARE_W - 1)).toFloat().coerceIn(0f, 1f)
            value = (1 - (py - y) / (SQUARE_H - 1)).toFloat().coerceIn(0f, 1f)
            dragged()
        }
    }

    /** The hues top to bottom, red to red. */
    private fun bar(ui: Ui, x: Int, y: Int) {
        ui.rect(x - 1, y - 1, BAR_W + 2, SQUARE_H + 2, Theme.BORDER)
        for (i in 0 until 6) {
            val y0 = y + SQUARE_H * i / 6
            val y1 = y + SQUARE_H * (i + 1) / 6
            ui.gradient(x, y0, BAR_W, y1 - y0, opaque(toRgb(i / 6f, 1f, 1f)), opaque(toRgb((i + 1) / 6f, 1f, 1f)))
        }
        val my = y + (hue * (SQUARE_H - 1)).toInt()
        ui.rect(x - 2, my - 1, BAR_W + 4, 3, WHITE)
        ui.rect(x, my, BAR_W, 1, opaque(toRgb(hue, 1f, 1f)))
        ui.drag(x - 2, y, BAR_W + 4, SQUARE_H) { _, py ->
            hue = ((py - y) / (SQUARE_H - 1)).toFloat().coerceIn(0f, 1f)
            dragged()
        }
    }

    private fun palette(ui: Ui, x: Int, y: Int) {
        PRESETS.forEachIndexed { i, c ->
            val px = x + i * (SWATCH + 2)
            val on = c == rgb
            if (on || ui.hovered(px, y, SWATCH, SWATCH)) ui.rect(px - 1, y - 1, SWATCH + 2, SWATCH + 2, Theme.ACCENT)
            ui.swatch(px, y, SWATCH, c)
            ui.hit(px, y, SWATCH, SWATCH, hex(c)) { set(c) }
        }
    }

    private fun set(c: Int, tell: Boolean = true) {
        val hsv = toHsv(c)
        // A grey has no hue: the bar stays where it was.
        if (hsv[1] > 0f) hue = hsv[0]
        sat = hsv[1]
        value = hsv[2]
        rgb = c and 0xFFFFFF
        if (tell) onChange(rgb)
    }

    companion object {
        private const val PAD = 8
        private const val GAP = 6
        private const val SQUARE_W = 136
        private const val SQUARE_H = 96
        private const val BAR_W = 12
        private const val SWATCH = 13
        private const val W = PAD + SQUARE_W + GAP + BAR_W + PAD
        private const val H = PAD + SQUARE_H + GAP + SWATCH + GAP + SWATCH + 2 + PAD
        private const val BLACK = 0xFF000000.toInt()
        private const val WHITE = 0xFFFFFFFF.toInt()
        /** Common colours: white, warm white, candle, red, yellow, green, cyan, blue, purple, pink. */
        private val PRESETS = listOf(0xFFFFFF, 0xFFDDB0, 0xFF9A3C, 0xFF4040, 0xFFD84A, 0x5CFF6A, 0x4AE3FF, 0x4A7BFF, 0xB04AFF, 0xFF5EC8)

        /** `#rrggbb` of [rgb]. */
        fun hex(rgb: Int): String = "#" + String.format(Locale.ROOT, "%06x", rgb and 0xFFFFFF)

        private fun opaque(rgb: Int) = rgb or BLACK

        /** RGB of hue, saturation and value, each 0 to 1. */
        fun toRgb(h: Float, s: Float, v: Float): Int {
            val f = (h % 1f) * 6f
            val i = f.toInt()
            val r = f - i
            val p = v * (1 - s)
            val q = v * (1 - s * r)
            val t = v * (1 - s * (1 - r))
            val (red, green, blue) = when (i) {
                0 -> Triple(v, t, p)
                1 -> Triple(q, v, p)
                2 -> Triple(p, v, t)
                3 -> Triple(p, q, v)
                4 -> Triple(t, p, v)
                else -> Triple(v, p, q)
            }
            return (Math.round(red * 255) shl 16) or (Math.round(green * 255) shl 8) or Math.round(blue * 255)
        }

        /** Hue, saturation and value (each 0 to 1) of [rgb]. */
        fun toHsv(rgb: Int): FloatArray {
            val r = (rgb shr 16 and 255) / 255f
            val g = (rgb shr 8 and 255) / 255f
            val b = (rgb and 255) / 255f
            val max = maxOf(r, g, b)
            val d = max - minOf(r, g, b)
            val h = when {
                d == 0f -> 0f
                max == r -> ((g - b) / d).let { if (it < 0) it + 6 else it }
                max == g -> (b - r) / d + 2
                else -> (r - g) / d + 4
            } / 6f
            return floatArrayOf(h, if (max == 0f) 0f else d / max, max)
        }
    }
}
