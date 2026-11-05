package games.qore.foreman.client.reload

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.util.Chat
import net.minecraft.server.packs.resources.PreparableReloadListener
import net.minecraft.server.packs.resources.ReloadInstance
import org.slf4j.LoggerFactory
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Times the client's resource reloads (F3+T, the options' packs, a server's pack): how long each step prepared (off
 * the main thread) and applied (on it, freezing the game), the longest frame, and the time the loading screen stayed up
 * after the packs were loaded. Only works during a reload, and only with fast reloads or the chat report on: the
 * overlay's frames and the reload's listeners drive it ([ListenerTimes]).
 */
object ReloadTimer {
    private val logger = LoggerFactory.getLogger("Foreman")

    /**
     * A reload listener's share: [prepareMs] on the background threads, [applyMs] on the main thread, both summed over
     * its tasks; [preparedMs] when it was prepared, from the reload's start (then it waits for the others to apply).
     */
    class Step(val name: String, val prepareMs: Long, val applyMs: Long, val preparedMs: Long)

    class Report(
        /** When the reload started ([System.nanoTime]). */
        val startedNanos: Long,
        /** From the reload's start until its loading screen closed. */
        val totalMs: Long,
        /** Until every pack was loaded (the rest is the loading screen fading out). */
        val loadMs: Long,
        val steps: List<Step>,
        val longestFrameMs: Long,
        /** Frames longer than [STALL_MS], added up: the time the game showed no progress. */
        val stalledMs: Long,
    ) {
        val blockingMs get() = steps.sumOf { it.applyMs }

        fun summary(): String = "Resources reloaded in ${seconds(totalMs)}: loading ${seconds(loadMs)}" +
            (if (totalMs - loadMs > 50) ", loading screen ${seconds(totalMs - loadMs)} more" else "") +
            ", longest freeze ${seconds(longestFrameMs)}"

        fun details(): String = buildString {
            appendLine(summary() + ".")
            appendLine("Main thread: ${seconds(blockingMs)} applying, ${seconds(stalledMs)} in frames over $STALL_MS ms.")
            append("Steps, last prepared first (prepared at, from the start / prepare time off the main thread / apply time on it):")
            steps.sortedByDescending { it.preparedMs }.forEach { append("\n  ${it.name}: ${it.preparedMs} ms / ${it.prepareMs} ms / ${it.applyMs} ms") }
        }
    }

    private const val STALL_MS = 100L

    @Volatile private var started = 0L
    private var loaded = 0L
    private var lastFrame = 0L
    private var longestFrame = 0L
    private var stalled = 0L
    private var steps = emptyList<Step>()
    private val prepared = HashMap<String, Long>()

    /** The client's reload being timed, to tell it from the integrated server's data pack loads. */
    @Volatile private var reload: ReloadInstance? = null

    /** When a loading screen last closed (the game's start, a reload; timed or not), [System.currentTimeMillis]. */
    @Volatile var closedMillis = 0L
        private set

    /** The last reload's report, once its loading screen closed. */
    @Volatile var last: Report? = null
        private set

    /** Reloads are timed while Foreman takes part in them: with fast reloads or the chat report on. */
    @JvmStatic fun times(): Boolean = Config.settings.reload.let { it.fast || it.report }

    /** A reload starts: its listeners are timed. */
    @JvmStatic fun start() {
        if (!times()) {
            started = 0L
            return
        }
        started = System.nanoTime()
        loaded = 0L
        lastFrame = started
        longestFrame = 0L
        stalled = 0L
        steps = emptyList()
        prepared.clear()
        reload = null
    }

    @JvmStatic fun timing(): Boolean = started != 0L

    /** The client's resource manager made the reload being timed. */
    @JvmStatic fun created(instance: ReloadInstance) {
        if (timing()) reload = instance
    }

    /** [instance] is the client's reload being timed (not a data pack load on the same thread). */
    @JvmStatic fun isTimed(instance: Any): Boolean = reload === instance

    /** Every listener of [instance] is done: if it's the reload being timed, the packs are loaded (the loading screen may still be up). */
    @JvmStatic fun steps(instance: ReloadInstance, times: ListenerTimes) {
        if (!timing() || !isTimed(instance)) return
        loaded = System.nanoTime()
        steps = times.steps()
    }

    /**
     * A reload's listeners' time, as vanilla's profiled reload measures it (that one isn't used: mods change it, and
     * ModernFix's change never lets its listeners apply on 26.x): each listener's tasks run through executors that add
     * up their time, off the main thread and on it.
     */
    class ListenerTimes {
        private class Times(val name: String) {
            val prepareNanos = AtomicLong()
            val applyNanos = AtomicLong()
        }

        /** By listener, and [all] in the reload's order: filled on the thread that starts the reload, as it does. */
        private val byListener = IdentityHashMap<PreparableReloadListener, Times>()
        private val all = ArrayList<Times>()

        /** [executor], timed for [listener]: its preparing tasks, or applying ones with [apply]. */
        fun executor(listener: PreparableReloadListener, executor: Executor, apply: Boolean): Executor {
            val times = byListener.getOrPut(listener) { Times(listener.name).also { all.add(it) } }
            val total = if (apply) times.applyNanos else times.prepareNanos
            return Executor { task ->
                executor.execute {
                    val start = System.nanoTime()
                    task.run()
                    total.addAndGet(System.nanoTime() - start)
                }
            }
        }

        fun steps(): List<Step> = all.map {
            Step(it.name, TimeUnit.NANOSECONDS.toMillis(it.prepareNanos.get()), TimeUnit.NANOSECONDS.toMillis(it.applyNanos.get()), prepared[it.name] ?: -1)
        }
    }

    /** A listener of the reload is prepared (on the main thread, as the barrier hears of it). */
    @JvmStatic fun prepared(name: String) {
        if (timing()) prepared.putIfAbsent(name, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started))
    }

    /** A frame of the loading screen: the time since the last one is how long the game froze. */
    @JvmStatic fun frame() {
        if (!timing()) return
        val now = System.nanoTime()
        val frame = TimeUnit.NANOSECONDS.toMillis(now - lastFrame)
        if (frame > longestFrame) longestFrame = frame
        if (frame > STALL_MS) stalled += frame
        lastFrame = now
    }

    /** The loading screen closed: the game takes input again. */
    @JvmStatic fun closed() {
        closedMillis = System.currentTimeMillis()
        if (!timing()) return
        frame()
        val now = System.nanoTime()
        val report = Report(
            started,
            TimeUnit.NANOSECONDS.toMillis(now - started),
            TimeUnit.NANOSECONDS.toMillis((if (loaded == 0L) now else loaded) - started),
            steps, longestFrame, stalled,
        )
        started = 0L
        reload = null
        last = report
        logger.debug(report.details())
        if (Config.settings.reload.report) Chat.info(report.summary())
    }

    private fun seconds(ms: Long) = "%.2f s".format(Locale.ROOT, ms / 1000.0)
}
