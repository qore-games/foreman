package games.qore.foreman.client.ui.kit

/**
 * A table column: a fixed [width] (0: takes what is left), right-aligned unless [left]. When the table is too narrow
 * for the flexible column to get its share, the columns of lowest [keep] are left out first. A column with [sort] sorts
 * the table when its header is clicked.
 */
class Col<T>(val label: String, val width: Int, val keep: Int = 0, val left: Boolean = false, val sort: Int? = null, val color: (T) -> Int = { Theme.MUTED }, val text: (T) -> String)

private const val ROW = 12
private const val MIN_FLEX = 100

/**
 * A scrolling table with a header row: only the rows in view are drawn. [sorted]: the sort of the column headed with
 * "▾"; [onSort] gets a clicked header's sort. Each row has [tooltip] and, when given, [onClick].
 */
fun <T> Ui.table(
    id: String, x: Int, y: Int, w: Int, h: Int, rows: List<T>, all: List<Col<T>>,
    sorted: Int? = null, onSort: ((Int) -> Unit)? = null, tooltip: (T) -> String, onClick: ((T) -> Unit)?,
) {
    var cols = all
    while (cols.count { it.width > 0 } > 1 && w - 8 - cols.sumOf { it.width + 6 } < MIN_FLEX) cols = cols - cols.filter { it.width > 0 }.minBy { it.keep }
    val flex = (w - 8 - cols.sumOf { it.width + 6 }).coerceAtLeast(40)
    val widths = cols.map { if (it.width == 0) flex else it.width }
    var cx = x + 4
    cols.forEachIndexed { i, c ->
        val cw = widths[i]
        val on = c.sort != null && c.sort == sorted
        val label = fit(if (on) "${c.label} ▾" else c.label, cw)
        text(label, if (c.left) cx else cx + cw - font.width(label), y, if (on) Theme.TEXT else Theme.FAINT)
        if (c.sort != null && onSort != null) hit(cx, y - 2, cw, 12, "Sort by ${c.label.lowercase()}") { onSort(c.sort) }
        cx += cw + 6
    }
    val top = y + 14
    scroll(id, x, top, w, h - 14) { start ->
        rows.forEachIndexed { r, row ->
            val ry = start + r * ROW
            if (ry + ROW < top || ry > top + h) return@forEachIndexed
            if (hovered(x, ry, w - 6, ROW)) rect(x, ry - 1, w - 6, ROW, Theme.PANEL_HOVER)
            var rx = x + 4
            cols.forEachIndexed { i, c ->
                val cw = widths[i]
                val t = fit(c.text(row), cw)
                text(t, if (c.left) rx else rx + cw - font.width(t), ry + 1, c.color(row))
                rx += cw + 6
            }
            hit(x, ry - 1, w - 6, ROW, tooltip(row), onClick?.let { click -> { click(row) } })
        }
        rows.size * ROW
    }
}
