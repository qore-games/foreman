package games.qore.foreman.client.mcp

import games.qore.foreman.client.lean.Lean
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import net.minecraft.client.Minecraft
import java.util.Locale

/**
 * `client_perf`: the client's frame times over a few seconds, to see what something costs (an effect, entities, a
 * build): measure, change, measure again. Frames are timed by MinecraftMixin only while a measure runs.
 */
object PerfTools {
    private const val MAX_SECONDS = 30

    /** Frame intervals (ns) of the measure running, null when none */
    @Volatile
    private var frames: LongArray? = null
    private var count = 0
    private var last = 0L

    fun init() {
        ClientMcp.register(ClientTool(
            "client_perf",
            "Times the client's frames for `seconds` (default 5, max $MAX_SECONDS) and returns the average FPS, the median " +
                "frame, the 1% and 0.1% lows and the worst frame, with whether the window had the focus (unfocused, " +
                "frame rate limiters slow the game down) and the post effects applied. `command` (as the player, " +
                "without the slash) is sent first, `settle_ms` (default 1000) before measuring: measure, change something, " +
                "measure again to see its cost.",
            ClientTools.schema(
                "seconds" to "integer: how long (default 5)",
                "command" to "string: optional, sent first",
                "settle_ms" to "integer: after the command, before measuring (default 1000)",
            ),
            acts = { it["command"] != null },
        ) { _, args -> listOf(ClientMcp.text(measure(args))) })
    }

    /** MinecraftMixin, after each frame. */
    @JvmStatic
    fun frame() {
        val samples = frames ?: return
        val now = System.nanoTime()
        if (last != 0L && count < samples.size) samples[count++] = now - last
        last = now
    }

    private fun measure(args: kotlinx.serialization.json.JsonObject): String {
        val seconds = (args["seconds"].int() ?: 5).coerceIn(1, MAX_SECONDS)
        args["command"].str()?.removePrefix("/")?.takeIf { it.isNotBlank() }?.let { command ->
            ClientTools.onClient { (Minecraft.getInstance().connection ?: throw ToolFailure("Not connected")).sendCommand(command) }
            Thread.sleep((args["settle_ms"].int() ?: 1000).coerceIn(0, 10_000).toLong())
        }
        if (frames != null) throw ToolFailure("A measure is running")
        ClientTools.onClient {
            count = 0
            last = 0L
            frames = LongArray(seconds * 2000)
        }
        // lean: every frame drawn while it measures
        Lean.everyFrame { Thread.sleep(seconds * 1000L) }
        val (samples, focused, effects) = ClientTools.onClient {
            val minecraft = Minecraft.getInstance()
            val taken = frames!!.copyOf(count)
            frames = null
            Triple(taken, minecraft.isWindowActive, Mc.postEffects().applied)
        }
        if (samples.isEmpty()) return "No frame in $seconds s"
        val sorted = samples.sorted()
        fun ms(ns: Long) = ns / 1_000_000.0
        fun low(fraction: Double): Double {
            val worst = sorted.takeLast((sorted.size * fraction).toInt().coerceAtLeast(1))
            return 1000.0 / ms(worst.average().toLong())
        }
        return "%d frames in %d s: %.1f FPS on average, median frame %.2f ms, 1%% low %.1f FPS, 0.1%% low %.1f FPS, worst frame %.1f ms. Window %s. Post effects: %s".format(
            Locale.ROOT, samples.size, seconds, samples.size / seconds.toDouble(), ms(sorted[sorted.size / 2]), low(0.01), low(0.001),
            ms(sorted.last()), if (focused) "focused" else "not focused (a frame rate limiter may apply)",
            effects.joinToString().ifEmpty { "none" },
        )
    }
}
