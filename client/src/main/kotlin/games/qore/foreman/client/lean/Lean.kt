package games.qore.foreman.client.lean

import com.mojang.blaze3d.audio.DeviceList
import com.mojang.blaze3d.audio.DeviceTracker
import com.mojang.blaze3d.systems.RenderSystem
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.config.LeanPacks
import games.qore.foreman.client.config.LeanWindow
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.mixin.MinecraftAccessor
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.LevelLoadingScreen
import net.minecraft.util.Util
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.LockSupport

/**
 * Lean mode, for clients that scripts and launchers start for automated tests next to the developer's own game: the
 * window out of the way, frames drawn only when a tool needs pixels or a screen's layout (and at a low capped rate, or
 * a live stream's while one is watched), no sound device, light settings in memory only ([LeanOptions]). Ticks, networking, input and screens run as usual, so
 * what tools test is the real client. On with `-Dforeman.lean=true` or the `lean` setting, read once at start.
 *
 * A loop of the game without a frame does what a frame does besides drawing ([skipFrame]: what the player looks at,
 * the camera, toasts, light updates, GPU readbacks), then sleeps until the next tick or task.
 */
object Lean {
    private val logger = LoggerFactory.getLogger("foreman")
    private val settings = Config.apply { load() }.settings.lean

    @JvmField val enabled: Boolean = prop("foreman.lean")?.toBooleanStrictOrNull() ?: settings.enabled
    /** Frames per second drawn besides the ones tools ask for; 0: only those. */
    val fps: Int = (prop("foreman.lean.fps")?.toIntOrNull() ?: settings.fps).coerceIn(0, 60)
    val window: LeanWindow = prop("foreman.lean.window")?.let { w -> LeanWindow.entries.firstOrNull { it.name.equals(w, true) } } ?: settings.window
    val renderDistance: Int = (prop("foreman.lean.renderDistance")?.toIntOrNull() ?: settings.renderDistance).coerceIn(2, 32)
    /** The server's resource packs: answered as loaded without downloading them (LeanPacksMixin). */
    private val packs: LeanPacks = prop("foreman.lean.packs")?.let { p -> LeanPacks.entries.firstOrNull { it.name.equals(p, true) } } ?: settings.packs

    /** Frames started and finished since the game started (every frame, lean or not). Client thread writes them. */
    @Volatile private var started = 0L
    @Volatile private var finished = 0L
    /** Loops of the game that drew no frame. */
    @Volatile var skipped = 0L
        private set
    /** The frame tools wait for: drawn as soon as possible. */
    private val wanted = AtomicLong()
    /** Tools that need every frame drawn meanwhile (client_perf). */
    private val holds = AtomicInteger()
    private var lastFrame = 0L
    /** Frames per second the open live streams want drawn (the highest of them), 0 while none is open. */
    @Volatile private var watched = 0
    @Volatile private var gameThread: Thread? = null
    private val lock = Object()

    init {
        if (enabled) logger.info("Lean mode: window {}, frames on demand{}, render distance {}, no sound device{}",
            window.name.lowercase(), if (fps > 0) " and $fps per second" else " only", renderDistance,
            if (packs == LeanPacks.SKIP) ", server resource packs skipped" else "")
    }

    /** LeanPacksMixin: whether the server's resource packs are skipped. */
    @JvmStatic fun skipsPacks() = enabled && packs == LeanPacks.SKIP

    /** LeanPacksMixin: a pack the server sent, answered as loaded without being downloaded. */
    @JvmStatic fun skippedPack(id: java.util.UUID, url: String, required: Boolean) {
        logger.info("Lean mode: skipped the server's resource pack {} ({}{}), answered as loaded", id, url, if (required) ", required" else "")
    }

    val frames get() = finished

    /** When the last frame started to be drawn, in [System.nanoTime] (every frame, lean or not). */
    val frameStarted get() = lastFrame

    /** Frames per second drawn besides the ones tools ask for: [fps], or more while a live stream is watched. */
    private val rate get() = maxOf(fps, watched)

    /** Draws at least [fps] frames per second while live streams watch the client; 0 once the last one closed. */
    fun watch(fps: Int) {
        watched = fps
        gameThread?.let(LockSupport::unpark)
    }

    /** The window stays hidden: nothing is ever shown, so frames are drawn for the tools only, never presented. */
    @JvmStatic fun hidesWindow() = enabled && window == LeanWindow.HIDDEN

    @JvmStatic fun minimizesWindow() = enabled && window == LeanWindow.MINIMIZED

    /** LeanMinecraftMixin, each loop of the game: whether it draws a frame. Loading screens are drawn: they end in a frame. */
    @JvmStatic fun drawsFrame(minecraft: Minecraft): Boolean {
        if (!enabled) return true
        gameThread = Thread.currentThread()
        val rate = rate
        return wanted.get() > finished || holds.get() > 0 || minecraft.gui.overlay() != null ||
            minecraft.gui.screen() is LevelLoadingScreen || (rate > 0 && System.nanoTime() - lastFrame >= 1_000_000_000L / rate)
    }

    @JvmStatic fun frameStarts() {
        started++
        lastFrame = System.nanoTime()
    }

    @JvmStatic fun frameEnds() {
        finished = started
        if (enabled) synchronized(lock) { lock.notifyAll() }
    }

    /**
     * Instead of a frame: what `Minecraft.renderFrame` updates besides drawing (the same calls, in its order), the GPU
     * readbacks that finished (screenshots), then a sleep until the next tick, a task or a frame a tool asks for.
     */
    @JvmStatic fun skipFrame(minecraft: Minecraft, advanceGameTime: Boolean) {
        skipped++
        val delta = minecraft.deltaTracker as DeltaTracker.Timer
        minecraft.levelExtractor.collectPerFrameMainThreadGizmos().use {
            delta.advanceRealTime(Util.getMillis())
            minecraft.gui.update()
            if (minecraft.isGameLoadFinished && advanceGameTime) minecraft.level?.update()
            minecraft.gameRenderer.update(delta)
            (minecraft as MinecraftAccessor).`foreman$pick`(delta.getGameTimeDeltaPartialTick(false))
        }
        RenderSystem.executePendingTasks()
        if (wanted.get() > finished || holds.get() > 0) return
        val mspt = minecraft.level?.tickRateManager()?.millisecondsPerTick() ?: 50f
        var nanos = ((1f - delta.getGameTimeDeltaPartialTick(true)) * mspt * 1_000_000).toLong()
        val rate = rate
        if (rate > 0) nanos = minOf(nanos, lastFrame + 1_000_000_000L / rate - System.nanoTime())
        // tasks (tool calls, the server's packets handled on this thread) unpark it
        LockSupport.parkNanos(this, nanos.coerceIn(200_000L, 50_000_000L))
    }

    /** TextureManager ticks (animated textures) only on loops that draw: they upload frames for nobody otherwise. */
    @JvmStatic fun ticksTextures(minecraft: Minecraft) = drawsFrame(minecraft)

    /**
     * Draws a frame that starts after this call and waits for it, at most [timeoutMs]: what a screen lays out while
     * drawing (lists place their widgets then, containers find the hovered slot), post effects applied, a readback
     * asked for just before sent to the GPU. Nothing when not lean (frames are drawn anyway) or on the client thread.
     */
    fun awaitFrame(timeoutMs: Long = 5000) {
        if (!enabled || Minecraft.getInstance().isSameThread) return
        val target = started + 1
        wanted.accumulateAndGet(target, ::maxOf)
        gameThread?.let(LockSupport::unpark)
        val deadline = System.currentTimeMillis() + timeoutMs
        synchronized(lock) {
            while (finished < target) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) return
                lock.wait(left)
            }
        }
    }

    /**
     * Draws frames until the world around the player is built (no section waiting to be compiled, twice in a row), at
     * most [timeoutMs]: a screenshot after a while without frames would show chunks missing.
     */
    fun awaitPixels(timeoutMs: Long = 3000) {
        if (!enabled) return
        val deadline = System.currentTimeMillis() + timeoutMs
        var ready = 0
        while (ready < 2 && System.currentTimeMillis() < deadline) {
            awaitFrame(deadline - System.currentTimeMillis())
            ready = if (ClientTools.onClient { Minecraft.getInstance().let { it.level == null || it.levelRenderer.hasRenderedAllSections() } }) ready + 1 else 0
        }
    }

    /** Draws every frame while [block] runs (client_perf times them). */
    fun <T> everyFrame(block: () -> T): T {
        holds.incrementAndGet()
        gameThread?.let(LockSupport::unpark)
        try {
            return block()
        } finally {
            holds.decrementAndGet()
        }
    }

    private fun prop(name: String): String? = System.getProperty(name)?.trim()?.takeIf { it.isNotEmpty() }

    /** The sound engine's device tracker in lean mode: no device, and OpenAL never asked for them (nor loaded). */
    object NoSoundDevices : DeviceTracker {
        override fun currentDevices(): DeviceList = DeviceList.EMPTY
        override fun tick() {}
        override fun forceRefresh() {}
    }
}
