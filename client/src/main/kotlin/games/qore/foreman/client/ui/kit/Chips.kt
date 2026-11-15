package games.qore.foreman.client.ui.kit

/**
 * [options] (a key and its label) as chips filling rows of [w], [rows] at most: the [selected] one lit. A click picks
 * one ([onPick]), or none when it was the one picked (null); [tooltip] says what a click does, for a key and whether it
 * is picked. Returns their height, with the gap under them.
 */
fun Ui.filterChips(
    x: Int, y: Int, w: Int, options: List<Pair<String, String>>, selected: String?, rows: Int,
    tooltip: (key: String, on: Boolean) -> String, onPick: (String?) -> Unit,
): Int {
    var cx = x
    var cy = y
    for ((key, label) in options) {
        val cw = font.width(label) + 8
        if (cx + cw > x + w && cx > x) {
            if ((cy - y) / 16 + 1 >= rows) break
            cx = x
            cy += 16
        }
        val on = key == selected
        chip(cx, cy, fit(label, w - 8), if (on) Theme.TEXT else Theme.MUTED, if (on) Theme.ACCENT_DARK else Theme.FIELD)
        hit(cx, cy, cw, 12, tooltip(key, on)) { onPick(if (on) null else key) }
        cx += cw + 4
    }
    return cy - y + 20
}
