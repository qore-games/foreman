package games.qore.foreman.server.world

import games.qore.foreman.server.Foreman
import org.bukkit.Bukkit
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

object Sync {
    /** Runs [block] on the server thread and waits for it. MCP requests arrive on HTTP threads. */
    fun <T> main(block: () -> T): T {
        if (Bukkit.isPrimaryThread()) return block()
        try {
            return Bukkit.getScheduler().callSyncMethod(Foreman.instance, Callable { block() }).get(120, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }
}
