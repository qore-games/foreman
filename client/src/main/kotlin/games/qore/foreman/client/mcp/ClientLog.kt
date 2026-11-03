package games.qore.foreman.client.mcp

import games.qore.foreman.client.packets.PacketInspector
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The client's recent log lines, kept in memory from the mod's start (an appender on log4j's root logger), for
 * `client_log`: shader, model and resource errors after a resource reload without reading the log file.
 */
object ClientLog {
    class Line(val time: Long, val level: Level, val logger: String, val text: String)

    private const val KEPT = 10_000
    private val lines = ArrayDeque<Line>()
    /** Lines since the start; [reloadLine] is the number of the line the last resource reload started at. */
    private var total = 0L
    private var reloadLine = -1L
    private var reloadTime = 0L
    private var clientThread = ""
    private var capturing = false
    private val time = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun init() {
        clientThread = Thread.currentThread().name
        try {
            val appender = object : AbstractAppender("ForemanClientLog", null, null, true, Property.EMPTY_ARRAY) {
                override fun append(event: LogEvent) = record(event)
            }
            appender.start()
            val context = LogManager.getContext(false) as LoggerContext
            context.configuration.rootLogger.addAppender(appender, null, null)
            context.updateLoggers()
            capturing = true
        } catch (e: Exception) {
            LoggerFactory.getLogger("foreman").warn("Can't keep the client's log lines for client_log", e)
        }

        ClientMcp.register(ClientTool(
            "client_log",
            "The client's recent log lines (kept in memory since the game started), filtered: `level` and above, " +
                "`contains`, the last `lines`. `since_reload`: only since the last resource reload started (the server's pack " +
                "applied, F3+T): after a resource pack change, the shader, model, texture and font errors it caused. Repeated " +
                "lines are folded.",
            ClientTools.schema(
                "level" to "string: lowest level shown: error, warn (default), info, debug",
                "contains" to "string: in the logger's name or the line, case-insensitive",
                "lines" to "integer: the last ones (default 40, max 500)",
                "since_reload" to "boolean: only since the last resource reload (default false)",
                "seconds" to "number: only the last seconds",
                "max_chars" to "integer: cut each line there (default 1000)",
            ),
            acts = { false },
        ) { _, args ->
            val level = when (args["level"].str()?.lowercase()) {
                null, "warn", "warning" -> Level.WARN
                "error" -> Level.ERROR
                "info" -> Level.INFO
                "debug", "all" -> Level.ALL
                else -> throw ToolFailure("level: error, warn, info or debug")
            }
            val since = args["seconds"].double()?.let { System.currentTimeMillis() - (it * 1000).toLong() }
            listOf(ClientMcp.text(report(level, args["contains"].str()?.takeIf { it.isNotBlank() }, (args["lines"].int() ?: 40).coerceIn(1, 500),
                args["since_reload"].bool() == true, since, (args["max_chars"].int() ?: 1000).coerceIn(80, 20_000))))
        })
    }

    private fun record(event: LogEvent) {
        // Read now: log4j reuses its events.
        val text = buildString {
            append(event.message?.formattedMessage ?: "")
            event.thrown?.let { append(" | ").append(thrown(it)) }
        }
        val line = Line(event.timeMillis, event.level, event.loggerName?.substringAfterLast('.') ?: "", text)
        // Vanilla logs this as a resource reload starts; the integrated server's data pack reloads run on its own threads.
        val reload = event.threadName == clientThread && text.startsWith("Reloading ResourceManager")
        synchronized(lines) {
            if (reload) {
                reloadLine = total
                reloadTime = event.timeMillis
            }
            lines += line
            total++
            if (lines.size > KEPT) lines.removeFirst()
        }
    }

    /** `IllegalStateException: message ← caused by IOException: message at Foo.bar(Foo.java:12)`: the causes and where it began. */
    private fun thrown(t: Throwable): String = buildString {
        var cause: Throwable? = t
        var depth = 0
        var last = t
        while (cause != null && depth < 4) {
            if (depth > 0) append(" ← caused by ")
            append(cause.javaClass.simpleName).append(": ").append(cause.message ?: "")
            last = cause
            cause = cause.cause?.takeIf { it !== cause }
            depth++
        }
        last.stackTrace.firstOrNull()?.let { append(" at ").append(it) }
    }

    /** The time the client's last resource reload started, or 0. */
    fun lastReload(): Long = synchronized(lines) { reloadTime }

    fun time(millis: Long): String = time.format(Instant.ofEpochMilli(millis))

    /** The kept lines matching, oldest first. */
    fun matching(level: Level, contains: String?, sinceReload: Boolean = false, since: Long? = null): List<Line> = synchronized(lines) {
        val first = if (sinceReload) (reloadLine - (total - lines.size)).coerceAtLeast(0) else 0L
        (first until lines.size.toLong()).asSequence().map { lines[it.toInt()] }
            .filter { it.level.isMoreSpecificThan(level) && (since == null || it.time >= since) }
            .filter { contains == null || it.text.contains(contains, true) || it.logger.contains(contains, true) }
            .toList()
    }

    /** The last [limit] matching lines, consecutive repeats folded, each cut at [maxChars]. */
    fun report(level: Level, contains: String?, limit: Int, sinceReload: Boolean = false, since: Long? = null, maxChars: Int = 1000): String {
        if (!capturing) return "The client's log lines aren't kept (see the game's log for why)."
        if (sinceReload && lastReload() == 0L) return "No resource reload seen since the game started."
        val all = matching(level, contains, sinceReload, since)
        val oldest = synchronized(lines) { lines.firstOrNull()?.time }
        val head = buildString {
            append("${all.size} line(s)")
            append(if (level == Level.ALL) "" else " at ${level.name().lowercase()} or above")
            contains?.let { append(" containing \"$it\"") }
            append(if (sinceReload) " since the resource reload at ${time(lastReload())}" else oldest?.let { " since ${time(it)}" } ?: "")
            if (!sinceReload && lastReload() != 0L) append(" (last resource reload ${time(lastReload())})")
        }
        if (all.isEmpty()) return "$head."
        // Folded first, so a flood of one line doesn't push the others out of the last [limit].
        val folded = ArrayList<Pair<Line, Int>>()
        for (line in all) {
            val last = folded.lastOrNull()
            if (last != null && last.first.text == line.text && last.first.level == line.level) folded[folded.size - 1] = last.first to last.second + 1
            else folded += line to 1
        }
        val shown = folded.takeLast(limit)
        return head + (if (shown.size < folded.size) ", the last ${shown.size}" else "") + ":\n" + shown.joinToString("\n") { (line, n) ->
            val text = PacketInspector.readable(line.text).replace("\n", " ⏎ ")
            "${time(line.time)} ${line.level.name().padEnd(5)} [${line.logger}] " +
                (if (text.length > maxChars) PacketInspector.cut(text, maxChars) + "…" else text) + if (n > 1) " (×$n)" else ""
        }
    }
}
