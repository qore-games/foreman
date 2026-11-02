package games.qore.foreman.client.mcp

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import java.util.concurrent.atomic.AtomicInteger

/**
 * Whether an AI session is using the client: a tool call running, or one in the last [IDLE_MS]. Meanwhile the game
 * doesn't pause when its window loses focus (MinecraftMixin; the player's option stays as it is), so screenshots and
 * inputs reach the game while the player is in another window, and the client isn't throttled as if the player were
 * away. Idle again, it pauses on focus loss as usual.
 */
object AgentActivity {
    /** A session without a call for this long is done (or thinking for long): the game pauses on focus loss again. */
    private const val IDLE_MS = 5 * 60_000L

    @Volatile
    private var lastCall = 0L
    private val running = AtomicInteger()
    /** The pause screen focus loss opened, closed when a session comes back. */
    @Volatile
    private var focusPause: Screen? = null

    @JvmStatic
    fun active() = running.get() > 0 || System.currentTimeMillis() - lastCall < IDLE_MS

    private fun touch() {
        lastCall = System.currentTimeMillis()
    }

    /** MinecraftMixin: losing focus just opened [screen] over the game. */
    @JvmStatic
    fun pausedOnLostFocus(screen: Screen?) {
        if (screen is PauseScreen) focusPause = screen
    }

    /** Runs a tool call (on the MCP server's threads), the game resumed first if focus loss paused it. */
    fun <T> call(block: () -> T): T {
        running.incrementAndGet()
        touch()
        try {
            resume()
            return block()
        } finally {
            touch()
            running.decrementAndGet()
        }
    }

    private fun resume() {
        val minecraft = Minecraft.getInstance()
        val pause = focusPause
        val step = {
            minecraft.framerateLimitTracker.onInputReceived()
            if (pause != null && minecraft.gui.screen() === pause) minecraft.gui.setScreen(null)
            focusPause = null
        }
        // Closed before the tool runs, so its screenshot or clicks don't land on the pause menu.
        if (pause != null) ClientTools.onClient(step) else minecraft.execute(step)
    }
}
