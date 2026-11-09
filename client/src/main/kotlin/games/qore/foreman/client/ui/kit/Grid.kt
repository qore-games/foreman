package games.qore.foreman.client.ui.kit

/**
 * [items] as cards in rows: as many columns of at least [minWidth] as fit in [w], stretched to fill it, each card
 * [height] (of its width) tall, [gap] apart. Only the cards crossing [visible] (screen rows, a scroll area's) are drawn
 * by [cell]. Returns the grid's height.
 */
fun <T> Ui.grid(
    x: Int, y: Int, w: Int, items: List<T>, minWidth: Int, gap: Int, visible: IntRange, height: (Int) -> Int,
    cell: (item: T, x: Int, y: Int, w: Int, h: Int) -> Unit,
): Int {
    if (items.isEmpty()) return 0
    val columns = ((w + gap) / (minWidth + gap)).coerceAtLeast(1)
    val cw = (w - gap * (columns - 1)) / columns
    val ch = height(cw)
    items.forEachIndexed { i, item ->
        val cy = y + (i / columns) * (ch + gap)
        if (cy + ch >= visible.first && cy <= visible.last) cell(item, x + (i % columns) * (cw + gap), cy, cw, ch)
    }
    val rows = (items.size + columns - 1) / columns
    return rows * ch + (rows - 1) * gap
}
