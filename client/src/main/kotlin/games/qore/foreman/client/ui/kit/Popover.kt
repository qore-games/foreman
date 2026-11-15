package games.qore.foreman.client.ui.kit

/** What a screen draws over itself until it closes (a list to pick from, a colour picker), in its overlay. */
interface Popover {
    /** Draws it over the screen ([screenW] x [screenH]), from [Ui.backdrop]; [close] closes it. */
    fun draw(ui: Ui, screenW: Int, screenH: Int, close: () -> Unit)
}
