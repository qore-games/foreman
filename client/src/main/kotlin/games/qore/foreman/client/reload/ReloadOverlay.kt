package games.qore.foreman.client.reload

/** What LoadingOverlayMixin adds to the loading screen: the settings its reload started with. */
interface ReloadOverlay {
    /** Foreman closes it as soon as the packs are loaded: fast reloads were on, and the loading screen Foreman's or in the background. */
    fun `foreman$fast`(): Boolean

    /** Keep playing too: a reload's (not the game's start) lets the game go on. */
    fun `foreman$inBackground`(): Boolean
}
