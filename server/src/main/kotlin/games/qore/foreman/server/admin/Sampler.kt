package games.qore.foreman.server.admin

import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.util.Locale

/**
 * A sampling profiler: the stacks of the server thread (or others) every few milliseconds for some seconds. A busy
 * sample counts for the plugin whose code it was in (the innermost frame of a class from a plugin's jar, so the work the
 * server did for it counts too), for the server otherwise; samples waiting between ticks are idle.
 */
object Sampler {
    /** The server thread, set at Foreman's enable. */
    @Volatile
    var mainThread: Thread? = null

    private val IDLE_TOP = setOf(
        "epollWait", "epollWait0", "poll0", "select", "doSelect", "accept", "accept0", "park", "parkNanos", "sleep", "sleep0",
        "sleepNanos", "sleepNanos0", "wait", "wait0", "kevent", "kevent0", "socketRead0", "read0",
    )
    private val LIBRARY = listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "it.unimi.", "com.google.")

    fun profile(threads: List<Thread>, millis: Long, interval: Long, focus: Plugin?): String {
        val owners = owners()
        val main = mainThread
        val busy = ArrayList<Array<StackTraceElement>>()
        var total = 0
        val start = System.nanoTime()
        val end = System.currentTimeMillis() + millis
        while (System.currentTimeMillis() < end) {
            for (t in threads) {
                val stack = t.stackTrace
                val state = t.state
                if (stack.isEmpty()) continue
                total++
                // The server thread parks between ticks (waiting inside a tick is lag); other threads idle in their waits.
                val idle = if (t === main) state != Thread.State.RUNNABLE && stack.none { it.methodName == "tickServer" }
                else state != Thread.State.RUNNABLE || stack[0].methodName in IDLE_TOP
                if (!idle) busy += stack
            }
            Thread.sleep(interval)
        }
        val seconds = (System.nanoTime() - start) / 1e9
        if (total == 0) return "No samples: the thread(s) ended."

        fun owner(e: StackTraceElement) = owners[e.className.substringBeforeLast('.', "")]
        fun pct(n: Int) = "${Math.round(n * 100.0 / total)}%"
        fun top(counts: Map<String, Int>, n: Int) = counts.entries.sortedByDescending { it.value }.take(n).joinToString(", ") { "${it.key} ${pct(it.value)}" }

        class Attributed(val plugin: String, val stack: Array<StackTraceElement>, val inner: Int, val outer: Int)
        val attributed = busy.map { stack ->
            val inner = stack.indexOfFirst { owner(it) != null }
            if (inner < 0) Attributed("server", stack, -1, -1)
            else owner(stack[inner])!!.let { p -> Attributed(p, stack, inner, stack.indexOfLast { owner(it) == p }) }
        }
        val byPlugin = attributed.groupingBy { it.plugin }.eachCount()
        val names = if (threads.size == 1) threads[0].name else "${threads.size} threads (${threads.take(3).joinToString { it.name }}${if (threads.size > 3) ", …" else ""})"
        val out = StringBuilder()
        val tps = Bukkit.getTPS()
        out.append("$names: $total samples over ${"%.1f".format(Locale.ROOT, seconds)} s (every $interval ms): busy ${pct(busy.size)}")
        if (threads.size == 1 && threads[0] === main) out.append(", idle between ticks ${pct(total - busy.size)} · TPS ${"%.1f".format(Locale.ROOT, tps[0])} · MSPT ${"%.1f".format(Locale.ROOT, Bukkit.getAverageTickTime())}")
        out.append("\nBusy by plugin (share of the time): ").append(top(byPlugin, 12).ifEmpty { "nothing" })
        val shown = byPlugin.entries.sortedByDescending { it.value }.map { it.key }.filter { focus == null || it == focus.name }.take(if (focus != null) 1 else 6)
        for (plugin in shown) {
            val mine = attributed.filter { it.plugin == plugin }
            val limit = if (focus != null) 10 else 3
            if (plugin == "server") {
                val inner = mine.mapNotNull { a -> a.stack.firstOrNull { e -> LIBRARY.none { e.className.startsWith(it) } } }.groupingBy { frame(it, true) }.eachCount()
                out.append("\nserver ${pct(mine.size)}: in ").append(top(inner, if (focus != null) 10 else 5))
                continue
            }
            val entered = mine.groupingBy { frame(it.stack[it.outer], false) }.eachCount()
            val inner = mine.groupingBy { frame(it.stack[it.inner], true) }.eachCount()
            out.append("\n$plugin ${pct(mine.size)}: entered through ").append(top(entered, limit)).append("; in ").append(top(inner, limit))
            if (focus != null) {
                // The plugin's own frames from where the server called it to where it was, at most 4.
                val paths = mine.groupingBy { a -> (a.outer downTo a.inner).map { a.stack[it] }.filter { owner(it) == plugin }.let { f -> if (f.size > 4) f.take(2) + f.takeLast(2) else f }.joinToString(" → ") { frame(it, true) } }.eachCount()
                out.append("\nHottest paths in $plugin:")
                paths.entries.sortedByDescending { it.value }.take(8).forEach { out.append("\n  ${pct(it.value)} ${it.key}") }
            }
        }
        if (focus != null && focus.name !in byPlugin) out.append("\n${focus.name}: no busy sample in its code.")
        return out.toString()
    }

    /** Package → plugin, from the classes in each plugin's jar (a library shaded without relocation goes to the first). */
    private fun owners(): Map<String, String> {
        val map = HashMap<String, String>()
        for (p in Bukkit.getPluginManager().plugins) {
            val entries = PluginJars.loadedEntries(p) ?: PluginJars.jar(p)?.let { PluginJars.disk(it)?.entries } ?: continue
            entries.keys.asSequence().filter { it.endsWith(".class") && '/' in it && !it.startsWith("META-INF/") }
                .map { it.substringBeforeLast('/').replace('/', '.') }.distinct().forEach { map.putIfAbsent(it, p.name) }
        }
        return map
    }

    /** `c.f.Bar.baz:12`: packages to their initials, the line when asked. */
    private fun frame(e: StackTraceElement, line: Boolean): String {
        val parts = e.className.split('.')
        val cls = parts.dropLast(1).joinToString("") { "${it.take(1)}." } + parts.last()
        return "$cls.${e.methodName}" + if (line && e.lineNumber >= 0) ":${e.lineNumber}" else ""
    }
}
