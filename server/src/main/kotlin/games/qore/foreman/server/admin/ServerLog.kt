package games.qore.foreman.server.admin

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.bukkit.Bukkit
import org.bukkit.plugin.Plugin
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The server's recent log lines, kept in memory from Foreman's load (an appender on log4j's root logger), for
 * `server_log` and the tools that report what a command or a reload logged: no log file to tail, nothing truncated.
 * Stack traces printed line by line (printStackTrace through System.err) are folded into the line they belong to, and
 * plugins enabled or disabled once the server runs mark a reload.
 */
object ServerLog {
    class Line(val seq: Long, val time: Long, val level: Level, val logger: String, val thread: String, val text: String) {
        /** The exception: its causes and frames, one per line (`Caused by: …`, `at …`), or null. */
        var trace: String? = null
    }

    /** A place in the log: [seq] is the number of the first line after it. */
    class Mark(val name: String, val seq: Long, val time: Long, var note: String = "")

    private const val KEPT = 10_000
    private const val NAME = "ForemanServerLog"
    private const val TRACE_CHARS = 16_000
    /** Plugins enabled or disabled less than this apart are one reload (a /reload, PlugMan's disable then enable). */
    private const val RELOAD_GAP = 5_000L
    private val lines = ArrayDeque<Line>()
    private var total = 0L
    private val marks = LinkedHashMap<String, Mark>()
    private var appender: AbstractAppender? = null
    private var startTime = 0L
    private var lastActivity = 0L
    private var reload: Mark? = null

    /** Set once the server ticks: plugins enabled or disabled after that are reloaded. */
    @Volatile
    var started = false

    private val ANSI = Regex("""\u001B\[[0-9;]*[A-Za-z]|§[0-9a-fk-orxA-FK-ORX]""")
    private val BOUNDARY = Regex("""^(Enabling|Disabling) (.+?) v\S+\*?$""")
    private val STACK = Regex("""^(\s+at |\s*Caused by: |\s+\.\.\. \d+ (more|common frames omitted)|\s+Suppressed: )""")
    private val STD_PREFIX = Regex("""^\[STD(ERR|OUT)] """)
    private val CLASS_PREFIX = Regex("""^(?:[\w$]+\.)+([\w$]+)(?=:|$)""")
    private val DIGITS = Regex("""\d+""")
    private val PLATFORM = listOf(
        "java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "net.minecraft.", "com.mojang.", "io.papermc.", "org.bukkit.",
        "org.spigotmc.", "com.destroystokyo.", "ca.spottedleaf.", "io.netty.", "it.unimi.", "com.google.", "org.apache.logging.",
        // Foreman's own plumbing around the commands its tools run.
        "games.qore.foreman.server.world.Sync", "games.qore.foreman.server.tools.ServerTools",
    )
    private val clock = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private val seconds = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
    private val day = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault())

    fun start() {
        if (appender != null) return
        startTime = System.currentTimeMillis()
        try {
            val a = object : AbstractAppender(NAME, null, null, true, Property.EMPTY_ARRAY) {
                override fun append(event: LogEvent) = record(event)
            }
            a.start()
            appender = a
            attach(a)
        } catch (e: Throwable) {
            appender = null
            Bukkit.getLogger().warning("[Foreman] Can't keep the server's log lines for server_log: $e")
        }
    }

    fun stop() {
        val a = appender ?: return
        runCatching {
            context().configuration.rootLogger.removeAppender(NAME)
            context().updateLoggers()
        }
        a.stop()
        appender = null
    }

    /** A reconfiguration of log4j drops the appender: put it back (called before reading). */
    fun ensure() {
        val a = appender ?: return
        runCatching { if (!context().configuration.rootLogger.appenders.containsKey(NAME)) attach(a) }
    }

    val capturing get() = appender != null

    private fun attach(a: AbstractAppender) {
        val context = context()
        context.configuration.rootLogger.addAppender(a, null, null)
        context.updateLoggers()
    }

    // The server's context, whichever class loader asks.
    private fun context() = LogManager.getContext(Bukkit::class.java.classLoader, false) as LoggerContext

    private fun record(event: LogEvent) {
        try {
            // Read now: log4j reuses its events.
            val text = plain(event.message?.formattedMessage ?: "")
            val logger = event.loggerName ?: ""
            val thread = event.threadName ?: ""
            val trace = event.thrown?.let { trace(it) }
            val boundary = if (started) BOUNDARY.find(text) else null
            synchronized(lines) {
                val last = lines.lastOrNull()
                val bare = text.replace(STD_PREFIX, "")
                if (trace == null && last != null && last.thread == thread && last.logger == logger && event.timeMillis - last.time < 2000 && STACK.containsMatchIn(bare)) {
                    if ((last.trace?.length ?: 0) < TRACE_CHARS) last.trace = (last.trace?.let { "$it\n" } ?: "") + bare.trim()
                    return
                }
                if (boundary != null) {
                    val what = "${boundary.groupValues[2]} ${if (boundary.groupValues[1] == "Enabling") "enabled" else "disabled"}"
                    val r = reload
                    if (r == null || event.timeMillis - lastActivity > RELOAD_GAP) reload = Mark("reload", total, event.timeMillis, what)
                    else if (r.note.length < 400) r.note += ", $what"
                    lastActivity = event.timeMillis
                }
                lines += Line(total++, event.timeMillis, event.level, logger, thread, text).also { it.trace = trace }
                if (lines.size > KEPT) lines.removeFirst()
            }
        } catch (_: Throwable) {
        }
    }

    /** A plugin finished enabling or disabling: a reload goes on while they follow each other. */
    fun activity() = synchronized(lines) { if (reload != null) lastActivity = System.currentTimeMillis() }

    fun plain(text: String) = text.replace(ANSI, "")

    /** Causes and frames, the way printStackTrace writes them, at most 40 frames a cause, each cut before [stop]. */
    fun trace(t: Throwable, stop: (StackTraceElement) -> Boolean = { false }): String = buildString {
        var cause: Throwable? = t
        val seen = HashSet<Throwable>()
        while (cause != null && seen.size < 6 && seen.add(cause)) {
            if (seen.size > 1) append("Caused by: ")
            append(cause.javaClass.name).append(": ").append(plain(cause.message ?: "")).append('\n')
            val frames = cause.stackTrace.takeWhile { !stop(it) }
            frames.take(40).forEach { append("at ").append(frame(it)).append('\n') }
            if (frames.size > 40) append("... ${frames.size - 40} more\n")
            cause = cause.cause
        }
    }.trimEnd()

    private fun frame(e: StackTraceElement) = "${e.className}.${e.methodName}(${e.fileName ?: "?"}${if (e.lineNumber >= 0) ":${e.lineNumber}" else ""})"

    /** The number the next line will have. */
    fun position(): Long = synchronized(lines) { total }

    /** The kept lines from [seq] on, oldest first. */
    fun since(seq: Long): List<Line> = synchronized(lines) {
        val first = (seq - (total - lines.size)).coerceAtLeast(0)
        (first until lines.size.toLong()).map { lines[it.toInt()] }
    }

    fun mark(name: String): Mark = synchronized(lines) { Mark(name, total, System.currentTimeMillis()).also { marks[name] = it } }

    fun marks(): List<Mark> = synchronized(lines) { marks.values.toList() }

    /** Where `since` starts: `start`, `reload` or a mark's name. */
    fun resolve(since: String): Mark? = synchronized(lines) {
        when (since) {
            "start" -> Mark("start", 0, startTime)
            "reload" -> reload
            else -> marks[since]
        }
    }

    fun time(millis: Long): String = clock.format(Instant.ofEpochMilli(millis))

    /** HH:mm:ss today, MM-dd HH:mm before. */
    fun shortTime(millis: Long): String {
        val instant = Instant.ofEpochMilli(millis)
        return if (instant.atZone(ZoneId.systemDefault()).toLocalDate() == LocalDate.now()) seconds.format(instant) else day.format(instant)
    }

    fun platform(frame: String) = PLATFORM.any { frame.startsWith(it) }

    /** Lines about [plugin]: from its logger or loggers under its packages, naming it, or with its code in their exception. */
    fun about(plugin: Plugin): (Line) -> Boolean {
        val name = plugin.name
        val segments = plugin.pluginMeta.mainClass.split('.').dropLast(1)
        val prefix = segments.take(3).joinToString(".").takeIf { it.isNotEmpty() }?.let { "$it." }
        val word = Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE)
        return { l ->
            l.logger.equals(name, true) || (prefix != null && (l.logger.startsWith(prefix) || l.trace?.contains(prefix) == true)) || word.containsMatchIn(l.text)
        }
    }

    /** `IllegalStateException: message ← caused by IOException: message at Foo.bar(Foo.java:12) (from com.plugin.Baz.run(Baz.kt:3))`. */
    fun summary(line: Line): String? {
        val trace = line.trace ?: return null
        val headers = ArrayList<String>()
        val frames = ArrayList<MutableList<String>>()
        for (raw in trace.lineSequence()) {
            val l = raw.trim()
            when {
                l.startsWith("at ") -> (frames.lastOrNull() ?: mutableListOf<String>().also { frames += it; headers += "" }).add(bareFrame(l.removePrefix("at ")))
                l.startsWith("Caused by: ") -> { headers += l.removePrefix("Caused by: "); frames += mutableListOf<String>() }
                l.startsWith("...") || l.startsWith("Suppressed: ") -> {}
                frames.isEmpty() -> { headers += l; frames += mutableListOf<String>() }
            }
        }
        if (frames.isEmpty()) return null
        val deepest = frames.last()
        val top = deepest.firstOrNull()
        val own = deepest.firstOrNull { !platform(it) } ?: frames.asReversed().asSequence().flatten().firstOrNull { !platform(it) }
        return buildString {
            append(headers.filter { it.isNotEmpty() && it != line.text }.joinToString(" ← caused by ") { it.replaceFirst(CLASS_PREFIX, "$1") })
            top?.let { append(" at ").append(it) }
            if (own != null && own != top) append(" (from ").append(own).append(")")
        }.trim()
    }

    // Stack lines printed by the JVM carry their class loader or module: `app//com.foo.Bar.baz(…)`, `java.base/java.lang…`.
    private fun bareFrame(frame: String): String {
        val call = frame.substringBefore('(')
        return if ('/' in call) call.substringAfterLast('/') + frame.substring(call.length) else frame
    }

    /** The trace with runs of server and JDK frames folded, for `stack`. */
    fun compactTrace(trace: String, maxLines: Int = 60): List<String> {
        val out = ArrayList<String>()
        var hidden = 0
        for (raw in trace.lineSequence()) {
            val l = raw.trim()
            val frame = if (l.startsWith("at ")) bareFrame(l.removePrefix("at ")) else null
            if (frame != null && platform(frame)) {
                hidden++
                continue
            }
            if (hidden > 0) out += "… $hidden server/JDK frame(s)".also { hidden = 0 }
            out += when {
                frame != null -> "at $frame"
                l.startsWith("Caused by: ") -> "Caused by: " + l.removePrefix("Caused by: ").replaceFirst(CLASS_PREFIX, "$1")
                else -> l.replaceFirst(CLASS_PREFIX, "$1")
            }
        }
        if (hidden > 0) out += "… $hidden server/JDK frame(s)"
        return if (out.size > maxLines) out.take(maxLines) + "… ${out.size - maxLines} more line(s)" else out
    }

    /** `net.minecraft.server.MinecraftServer` → `n.m.s.MinecraftServer`; plugin loggers are their plugin's name. */
    fun loggerName(logger: String): String {
        if (logger.isEmpty()) return "Server"
        val parts = logger.split('.')
        return if (parts.size == 1) logger else parts.dropLast(1).joinToString(".") { it.take(1) } + "." + parts.last()
    }

    /**
     * Lines folded: the ones differing only by numbers count as one, shown where it last appeared (its latest text) with
     * how many times and since when. The last [limit] entries, each line cut at [maxChars], the whole at [budget].
     */
    fun format(all: List<Line>, limit: Int, maxChars: Int, stack: Boolean, budget: Int = 60_000): String {
        class Entry(val first: Line, var last: Line, var count: Int)
        val folded = LinkedHashMap<String, Entry>()
        for (l in all) {
            val key = key(l)
            val e = folded.remove(key)?.also { it.last = l; it.count++ } ?: Entry(l, l, 1)
            folded[key] = e
        }
        val entries = folded.values.toList().takeLast(limit)
        val rendered = entries.map { e ->
            val l = e.last
            val text = l.text.replace("\n", " ⏎ ")
            buildString {
                append(time(l.time)).append(' ').append(l.level.name().padEnd(5)).append(" [").append(loggerName(l.logger)).append("] ")
                append(if (text.length > maxChars) text.take(maxChars) + "…" else text)
                summary(l)?.let { s -> append(" | ").append(if (s.length > maxChars) s.take(maxChars) + "…" else s) }
                if (e.count > 1) append(" (×${e.count}, first ${shortTime(e.first.time)})")
                if (stack) l.trace?.let { t -> compactTrace(t).forEach { append("\n    ").append(it) } }
            }
        }
        // Over the budget, the oldest go first.
        var size = 0
        val kept = rendered.asReversed().takeWhile { size += it.length + 1; size <= budget }.asReversed()
        val dropped = folded.size - kept.size
        return (if (dropped > 0) "($dropped older entr${if (dropped == 1) "y" else "ies"} not shown)\n" else "") + kept.joinToString("\n")
    }

    private fun key(l: Line) = "${l.level}|${l.logger}|${l.text.replace(DIGITS, "#")}|${(summary(l) ?: "").replace(DIGITS, "#")}"

    class Query(
        val level: Level,
        val contains: String?,
        val filter: ((Line) -> Boolean)?,
        val filterName: String?,
        val since: String?,
        val seconds: Double?,
        val limit: Int,
        val maxChars: Int,
        val stack: Boolean,
    )

    /** `server_log`: a head line saying what was kept and selected, then the folded lines. */
    fun report(q: Query): String {
        if (!capturing) return "The server's log lines aren't kept (see the server log for why)."
        ensure()
        val from = q.since?.let { resolve(it) }
        if (q.since != null && from == null) {
            return if (q.since == "reload") "No plugin was enabled or disabled since the server started (at ${shortTime(startTime)}): use since=start."
            else "No mark '${q.since}': set one with server_log_mark. Marks: ${marks().joinToString { it.name }.ifEmpty { "none" }}."
        }
        val after = q.seconds?.let { System.currentTimeMillis() - (it * 1000).toLong() }
        val kept = since(0)
        val all = since(from?.seq ?: 0).filter { l ->
            l.level.isMoreSpecificThan(q.level) && (after == null || l.time >= after) &&
                (q.contains == null || l.text.contains(q.contains, true) || l.logger.contains(q.contains, true) || l.trace?.contains(q.contains, true) == true) &&
                (q.filter == null || q.filter(l))
        }
        val head = buildString {
            append("${all.size} line(s)")
            all.map { key(it) }.distinct().size.takeIf { it < all.size }?.let { append(" ($it once folded)") }
            if (q.level != Level.ALL) append(" at ${q.level.name().lowercase()} or above")
            q.filterName?.let { append(" about $it") }
            q.contains?.let { append(" containing \"$it\"") }
            when (from?.name) {
                null -> {}
                "start" -> append(" since Foreman started (${shortTime(from.time)})")
                "reload" -> append(" since the reload at ${shortTime(from.time)} (${from.note})")
                else -> append(" since mark '${from.name}' (${shortTime(from.time)})")
            }
            q.seconds?.let { append(" in the last ${it}s") }
            append(" · kept: ${kept.size} lines since ${kept.firstOrNull()?.let { shortTime(it.time) } ?: shortTime(startTime)}")
            if (from?.name != "reload") reload?.let { append(" · last reload ${shortTime(it.time)} (${it.note.take(120)})") }
            if (from != null && kept.isNotEmpty() && from.seq < kept.first().seq) append(" · the lines just after it are gone (only the last $KEPT are kept)")
        }
        if (all.isEmpty()) return "$head."
        return "$head:\n" + format(all, q.limit, q.maxChars, q.stack)
    }
}
