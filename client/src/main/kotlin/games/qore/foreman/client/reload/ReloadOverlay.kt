package games.qore.foreman.client.reload

/** What LoadingOverlayMixin adds to the loading screen: the settings its reload started with. */
interface ReloadOverlay {
    /** Fast reloads were on: it closes as soon as the packs are loaded. */
    fun `foreman$fast`(): Boolean

    /** Keep playing too: a reload's (not the game's start) lets the game go on. */
    fun `foreman$inBackground`(): Boolean
}
