package games.qore.foreman.server.mcp

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Runs [action] on [scheduler] [quietMs] after the last [poke], and at most [maxMs] after the first of a burst: many
 * changes in a row (a plugin registering 20 tools) make one call.
 */
class Debounce(
    private val scheduler: ScheduledExecutorService,
    private val quietMs: Long,
    private val maxMs: Long,
    private val action: () -> Unit,
) {
    private var pending: ScheduledFuture<*>? = null
    private var burstStart = 0L
    /** The latest run scheduled: an earlier one that started before it could be cancelled does nothing. */
    private var latest = 0

    @Synchronized
    fun poke() {
        val now = System.nanoTime()
        if (pending == null) burstStart = now
        pending?.cancel(false)
        val run = ++latest
        val left = maxMs - (now - burstStart) / 1_000_000
        pending = scheduler.schedule({ fire(run) }, quietMs.coerceAtMost(left).coerceAtLeast(0), TimeUnit.MILLISECONDS)
    }

    private fun fire(run: Int) {
        synchronized(this) {
            if (run != latest) return
            pending = null
        }
        action()
    }
}
