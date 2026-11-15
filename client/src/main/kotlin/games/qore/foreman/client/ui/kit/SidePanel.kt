package games.qore.foreman.client.ui.kit

/** The panels shown beside the world, the game still in view (an object's, a plugin's): how wide they are. */
object SidePanel {
    const val MIN = 300
    const val MAX = 520
    /** The share of the screen's width they take, between [MIN] and [MAX]. */
    private const val SHARE = 0.5

    /** Their width on a screen [screenW] wide, its own units. */
    fun width(screenW: Int): Int = (screenW * SHARE).toInt().coerceIn(MIN, MAX).coerceAtMost(screenW - 20)
}
