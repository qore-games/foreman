package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.admin.PlayerChat
import games.qore.foreman.server.admin.PluginJars
import games.qore.foreman.server.admin.Sampler
import games.qore.foreman.server.admin.ServerLog
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Sync
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.apache.logging.log4j.Level
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.command.CommandException
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.lang.management.ManagementFactory
import java.lang.reflect.Method
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tools for developing plugins on the server: run a command and read all it printed, the server's log, whether the
 * running plugins are the jars on disk, the server's health, a sampling profiler, an event's listeners, and reloading a
 * plugin through PlugMan when it is there. With a per-player token, the player needs [PERMISSION].
 */
object ServerTools {
    /** What a per-player token's player needs for these tools (ops have it); the main token needs nothing. */
    const val PERMISSION = "foreman.admin"

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.SERVER_COMMAND,
            "Runs a server command on the server thread and returns everything it printed, untruncated: the replies sent " +
                "to its sender and the log lines logged meanwhile (exceptions with their frames). As the console by default, " +
                "or as an online player (`as_player`: their permissions, their replies read from what the server sends " +
                "them). Says when the command is unknown (with close names) or its syntax is wrong. For commands that " +
                "finish later (async work), `wait_for` a text they print. Prefer it to RCON.",
            schema {
                string("command", "The command line, without the slash, e.g. `time set day`")
                string("as_player", "An online player's name: run it as them instead of the console", required = false)
                int("wait_ms", "How long to keep collecting replies and log lines after it ran (default 250); with wait_for, the most to wait (default 10000, max 120000)", required = false)
                string("wait_for", "Return as soon as a reply or a log line contains this (case-insensitive)", required = false)
            },
            modifies = true,
        ) { args -> admin(args); command(args) },

        Tool(
            ForemanTool.SERVER_LOG,
            "The server's recent log lines, kept in memory since Foreman started (the last 10000): `level` and above, " +
                "about a `plugin` (its logger, its packages, exceptions through its code, lines naming it), `contains`, " +
                "the last `lines`. `since`: start, reload (since the last plugins were enabled or disabled: a PlugMan " +
                "reload, /reload) or a mark set with server_log_mark (before a deploy). Lines differing only by numbers are " +
                "folded (×N, first time); exceptions show their causes and the frame they came from, `stack` their frames.",
            schema {
                enum("level", listOf("error", "warn", "info", "all"), "The lowest level shown (default warn)", required = false)
                string("plugin", "A plugin's name, or a part of a logger's name", required = false)
                string("contains", "In the line, its logger or its exception, case-insensitive", required = false)
                int("lines", "The last ones, after folding (default 40, max 500)", required = false)
                string("since", "start, reload or a mark's name (default: everything kept)", required = false)
                number("seconds", "Only the last seconds", required = false)
                bool("stack", "Exceptions' frames too, the server's and the JDK's folded (default false)", required = false)
                int("max_chars", "Cut each line there (default 1000)", required = false)
            },
        ) { args -> admin(args); log(args) },

        Tool(
            ForemanTool.SERVER_LOG_MARK,
            "Marks the end of the server log now, to read only what follows later: server_log since=<name>. Set one " +
                "before deploying or testing something. Without a name, lists the marks.",
            schema { string("name", "The mark's name, e.g. `deploy`", required = false) },
        ) { args ->
            admin(args)
            val name = args.string("name", null)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@Tool ToolResult.text(ServerLog.marks().joinToString("\n") { "${it.name}: ${ServerLog.time(it.time)}" }.ifEmpty { "No marks." })
            if (name == "start" || name == "reload") throw ToolError("'$name' is taken: pick another name")
            val mark = ServerLog.mark(name)
            ToolResult.text("Mark '$name' at ${ServerLog.time(mark.time)}: server_log since=$name reads what the server logs from now on.")
        },

        Tool(
            ForemanTool.SERVER_PLUGINS,
            "The server's plugins: name, version, enabled, jar (path, size), build info from the jar's manifest, " +
                "dependencies, and whether the server runs the jar that is on disk: STALE when the jar changed since the " +
                "plugin loaded (with the classes that differ), GONE when it was deleted. Then the jars in plugins/ no " +
                "running plugin came from (a new version under another name, one that failed to load). One plugin " +
                "(or `detail`): its link target, load time, commands, listeners and tasks too.",
            schema {
                string("contains", "Only plugins (or jar files) whose name contains this", required = false)
                bool("stale_only", "Only the stale, gone and disabled plugins and the jars not loaded", required = false)
                bool("detail", "More about each plugin (default: when only one matches)", required = false)
            },
        ) { args -> admin(args); plugins(args) },

        Tool(
            ForemanTool.SERVER_STATUS,
            "The server's health: version, uptime, TPS (1, 5, 15 min), MSPT (average and worst of the last 100 ticks), " +
                "memory, CPU, garbage collection, online players (world, position, game mode, ping), worlds (chunks, " +
                "entities and the most common types, block entities) and scheduled tasks by plugin.",
            schema {},
        ) { args -> admin(args); status() },

        Tool(
            ForemanTool.SERVER_PROFILE,
            "Where the server thread spends its time: samples its stack every `interval_ms` for `seconds` and reports " +
                "how busy it was, the share of each plugin (work the server did for a plugin's code counts for it), how " +
                "the server entered each (listener, task) and its hottest lines. `plugin` details one plugin's paths; " +
                "`thread` profiles other threads by name (async pools, Netty).",
            schema {
                number("seconds", "How long (default 5, max 60)", required = false)
                int("interval_ms", "Between samples (default 10)", required = false)
                string("plugin", "Detail this plugin's hottest paths", required = false)
                string("thread", "Threads whose name contains this, instead of the server thread", required = false)
            },
        ) { args -> admin(args); profile(args) },

        Tool(
            ForemanTool.SERVER_LISTENERS,
            "Who handles an event: with `event` (a class name like PlayerInteractEvent), its listeners in the order " +
                "they are called (priority, plugin, class and method, ignores cancelled): to find who cancels or changes it. " +
                "With only `plugin`, the events it handles. Neither: the events with the most listeners.",
            schema {
                string("event", "An event class's name, or part of it", required = false)
                string("plugin", "Only this plugin's listeners", required = false)
                int("limit", "Events listed without `event` (default 40)", required = false)
            },
        ) { args -> admin(args); listeners(args) },
    )

    /** Published while PlugMan or PlugManX runs (ServerWatch). */
    fun reload(plugin: Foreman) = Tool(
        ForemanTool.SERVER_RELOAD_PLUGIN,
        "Reloads a plugin through PlugMan (from its jar on disk now) and says how it went: what PlugMan replied, the " +
            "warnings and errors logged during the reload and just after (`wait_ms`), the plugin's version before and " +
            "after, and whether the server now runs the jar on disk. Plugins that depend on it keep its old classes: " +
            "reload them too, or restart.",
        schema {
            string("plugin", "The plugin's name")
            int("wait_ms", "How long to keep collecting log lines after the reload (default 1500, max 60000)", required = false)
        },
        modifies = true,
    ) { args ->
        admin(args)
        val name = args.string("plugin")
        val target = find(name) ?: throw ToolError("No plugin '$name'" + close(name, Bukkit.getPluginManager().plugins.map { it.name }))
        if (target === plugin) throw ToolError("Foreman can't reload itself through its own MCP server (the call would never return): reload it from the console, or restart.")
        val before = target.pluginMeta.version
        val r = run("plugman reload ${target.name}", null, args.int("wait_ms", 1500).coerceIn(0, 60_000).toLong(), null)
        val now = find(target.name)
        val out = StringBuilder("plugman reload ${target.name} (${r.ms} ms): ")
        out.append(when {
            now == null -> "${target.name} isn't loaded any more"
            !now.isEnabled -> "${target.name} ${now.pluginMeta.version} is DISABLED (its enable failed: see below)"
            else -> {
                val status = PluginJars.status(now)
                "${target.name} " + (if (before != now.pluginMeta.version) "$before → ${now.pluginMeta.version}" else now.pluginMeta.version) + ", enabled" +
                    (status.text?.let { ", $it" } ?: ", the server runs its jar on disk")
            }
        })
        r.error?.let { out.append("\n").append(it) }
        if (r.replies.isNotEmpty()) out.append("\nPlugMan replied:\n").append(r.replies.joinToString("\n") { "  $it" })
        val problems = r.logged.filter { it.level.isMoreSpecificThan(Level.WARN) }
        out.append(if (problems.isEmpty()) "\nNo warning or error logged." else "\nWarnings and errors logged (${problems.size}):\n" + ServerLog.format(problems, 100, 2000, true).prependIndent("  "))
        val dependents = Bukkit.getPluginManager().plugins.filter { p -> p.isEnabled && p !== now && (target.name in p.pluginMeta.pluginDependencies || target.name in p.pluginMeta.pluginSoftDependencies) }
        if (dependents.isNotEmpty()) out.append("\nPlugins that depend on it, still on its old classes: ${dependents.joinToString { it.name }} (reload them too, or restart).")
        ToolResult.text(out.toString())
    }

    /** With a per-player token: the player needs [PERMISSION]. */
    fun admin(args: Args) {
        val caller = args.caller ?: return
        val allowed = Bukkit.getPlayer(caller.id)?.hasPermission(PERMISSION) ?: Bukkit.getOfflinePlayer(caller.id).isOp
        if (!allowed) throw ToolError("This needs $PERMISSION, which ${caller.name} doesn't have")
    }

    private fun find(name: String): Plugin? = Bukkit.getPluginManager().plugins.firstOrNull { it.name.equals(name, true) }

    /** `: did you mean a, b?` from the names close to [name]. */
    private fun close(name: String, names: Collection<String>): String {
        val near = names.distinct().filter { it.contains(name, true) || (it.length > 2 && name.contains(it, true)) || distance(it.lowercase(), name.lowercase()) <= 2 }.sorted().take(10)
        return if (near.isEmpty()) "" else ": did you mean ${near.joinToString(", ")}?"
    }

    private fun distance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val row = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) row[j] = minOf(previous[j] + 1, row[j - 1] + 1, previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            previous = row
        }
        return previous[b.length]
    }

    private fun f1(v: Double) = "%.1f".format(Locale.ROOT, v)

    // Commands

    class Ran(val known: Boolean, val error: String?, val replies: List<String>, val logged: List<ServerLog.Line>, val ms: Long, val seen: Boolean?, val captured: Boolean)

    /**
     * Runs [line] on the server thread as [player], or as a console sender that keeps its replies ([sender] instead:
     * the real console, whose replies are logged), then keeps collecting replies and log lines for [waitMs] (or until
     * one contains [waitFor]).
     */
    fun run(line: String, player: Player?, waitMs: Long, waitFor: String?, sender: CommandSender? = null): Ran {
        ServerLog.ensure()
        val replies = CopyOnWriteArrayList<String>()
        val from = ServerLog.position()
        val capture = player?.let { PlayerChat.capture(it, replies) }
        val start = System.nanoTime()
        var error: String? = null
        val known = try {
            Sync.main {
                val by = player ?: sender ?: Bukkit.createCommandSender { replies += ServerLog.plain(PlainTextComponentSerializer.plainText().serialize(it)) }
                try {
                    Bukkit.dispatchCommand(by, line)
                } catch (e: CommandException) {
                    error = failure(e)
                    true
                }
            }
        } catch (e: Exception) {
            capture?.close()
            throw e
        }
        val ms = (System.nanoTime() - start) / 1_000_000
        val deadline = System.currentTimeMillis() + waitMs
        var seen: Boolean? = if (waitFor == null) null else false
        while (true) {
            if (waitFor != null && (replies.any { it.contains(waitFor, true) } || ServerLog.since(from).any { it.text.contains(waitFor, true) })) {
                seen = true
                break
            }
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) break
            Thread.sleep(minOf(left, 25))
        }
        capture?.close()
        // Commands that inform the admins log their reply again as `[FeedbackForwardingSender: …]`.
        val logged = ServerLog.since(from).filter { !it.text.startsWith("[FeedbackForwardingSender: ") }
        return Ran(known, error, replies.toList(), logged, ms, seen, player == null || capture != null)
    }

    private fun failure(e: CommandException): String {
        val root = generateSequence<Throwable>(e) { t -> t.cause?.takeIf { it !== t } }.last()
        if (root.javaClass.simpleName == "CommandSyntaxException") return "Syntax error: ${root.message}"
        // The command's own frames: from where it threw to the server's dispatch.
        val thrown = e.cause ?: e
        val trace = ServerLog.trace(thrown) { it.className == "org.bukkit.craftbukkit.CraftServer" && it.methodName == "dispatchCommand" }
        return "It threw ${thrown.javaClass.simpleName}: ${thrown.message}" + (if (root !== thrown) " ← caused by ${root.javaClass.simpleName}: ${root.message}" else "") +
            "\n" + ServerLog.compactTrace(trace, 30).drop(1).joinToString("\n") { "  $it" }
    }

    private fun command(args: Args): ToolResult {
        val line = args.string("command").trim().removePrefix("/").trim()
        if (line.isEmpty()) throw ToolError("Empty command")
        val player = args.string("as_player", null)?.let { name ->
            Bukkit.getPlayerExact(name) ?: throw ToolError("$name isn't online. Online: ${Bukkit.getOnlinePlayers().joinToString { it.name }.ifEmpty { "nobody" }}")
        }
        val waitFor = args.string("wait_for", null)?.takeIf { it.isNotEmpty() }
        val waitMs = args.int("wait_ms", if (waitFor != null) 10_000 else 250).coerceIn(0, 120_000).toLong()
        return report(line, player, run(line, player, waitMs, waitFor), waitFor, waitMs)
    }

    /** What [run] saw: replies, log lines, an unknown command or its failure (an error result then). */
    fun report(line: String, player: Player?, r: Ran, waitFor: String? = null, waitMs: Long = 0): ToolResult {
        val out = StringBuilder("/$line (${player?.name ?: "console"}, ${r.ms} ms)")
        if (!r.known) {
            val label = line.substringBefore(' ').lowercase()
            out.append("\nUnknown command '$label'").append(Sync.main { close(label, Bukkit.getCommandMap().knownCommands.keys.filter { ':' !in it }) })
        }
        r.error?.let { out.append("\n").append(it) }
        r.seen?.let { out.append(if (it) "\nSaw \"$waitFor\"." else "\n\"$waitFor\" didn't show within $waitMs ms.") }
        if (r.replies.isNotEmpty()) out.append("\nReplied:\n").append(r.replies.joinToString("\n") { "  " + it.replace("\n", "\n  ") })
        else if (!r.captured) out.append("\nIts replies went to ${player?.name}'s chat (they can't be read on this server version).")
        if (r.logged.isNotEmpty()) out.append("\nLogged (${r.logged.size} line(s)):\n").append(ServerLog.format(r.logged, 300, 4000, true).prependIndent("  "))
        if (r.known && r.error == null && r.replies.isEmpty() && r.logged.isEmpty()) out.append("\nNo reply, nothing logged.")
        val text = out.toString()
        val result = if (text.length > 100_000) text.take(100_000) + "\n… (cut at 100000 characters)" else text
        return if (!r.known || r.error != null) ToolResult.error(result) else ToolResult.text(result)
    }

    // Log

    private fun log(args: Args): ToolResult {
        val level = when (args.string("level", "warn")!!.lowercase()) {
            "error" -> Level.ERROR
            "warn", "warning" -> Level.WARN
            "info" -> Level.INFO
            "all", "debug" -> Level.ALL
            else -> throw ToolError("level: error, warn, info or all")
        }
        val name = args.string("plugin", null)?.takeIf { it.isNotBlank() }
        val target = name?.let { find(it) }
        val filter: ((ServerLog.Line) -> Boolean)? = when {
            target != null -> ServerLog.about(target)
            name != null -> { l -> l.logger.contains(name, true) }
            else -> null
        }
        return ToolResult.text(ServerLog.report(ServerLog.Query(
            level,
            args.string("contains", null)?.takeIf { it.isNotEmpty() },
            filter,
            target?.name ?: name?.let { "loggers containing \"$it\"" },
            args.string("since", null)?.takeIf { it.isNotEmpty() },
            if (args.has("seconds")) args.double("seconds", 60.0) else null,
            args.int("lines", 40).coerceIn(1, 500),
            args.int("max_chars", 1000).coerceIn(80, 20_000),
            args.bool("stack", false),
        )))
    }

    // Plugins

    private fun plugins(args: Args): ToolResult {
        val contains = args.string("contains", null)?.takeIf { it.isNotEmpty() }
        val staleOnly = args.bool("stale_only", false)
        val all = Bukkit.getPluginManager().plugins.sortedBy { it.name.lowercase() }
        val matching = all.filter { p -> contains == null || p.name.contains(contains, true) || PluginJars.jar(p)?.fileName?.toString()?.contains(contains, true) == true }
        val statuses = matching.associateWith { PluginJars.status(it) }
        val problem = { p: Plugin -> !p.isEnabled || statuses[p]!!.state == PluginJars.State.STALE || statuses[p]!!.state == PluginJars.State.GONE }
        val shown = if (staleOnly) matching.filter(problem) else matching
        val detail = args.bool("detail", shown.size == 1)
        val unloaded = PluginJars.unloaded().filter { (path, disk) -> contains == null || path.fileName.toString().contains(contains, true) || disk?.name?.contains(contains, true) == true }
        val out = StringBuilder()
        out.append("${all.size} plugins (${all.count { it.isEnabled }} enabled)")
        val stale = statuses.values.count { it.state == PluginJars.State.STALE || it.state == PluginJars.State.GONE }
        if (stale > 0) out.append(", $stale running an old jar")
        if (contains != null) out.append(", ${matching.size} matching \"$contains\"")
        if (shown.isEmpty()) out.append(if (staleOnly) ": none stale or disabled." else ": none matching.")
        else out.append(":")
        val extra = if (detail) Sync.main { shown.associateWith { details(it) } } else emptyMap()
        shown.forEach { p -> out.append("\n").append(describe(p, statuses[p]!!, detail)); extra[p]?.let { out.append(it) } }
        if (unloaded.isNotEmpty()) {
            out.append("\nJars in plugins/ that no running plugin was loaded from:")
            unloaded.forEach { (path, disk) ->
                out.append("\n  ${path.fileName}: ")
                val running = disk?.name?.let { find(it) }
                out.append(when {
                    disk == null -> "can't be read"
                    disk.name == null -> "not a plugin (no plugin.yml)"
                    running != null -> "${disk.name} ${disk.version}, not loaded: the server runs ${running.name} ${running.pluginMeta.version} from ${PluginJars.jar(running)?.let { display(it) }} (reload it with PlugMan, or restart)"
                    else -> "${disk.name} ${disk.version}, not loaded: it failed to load or came after the start (server_log since=start plugin=${disk.name})"
                })
            }
        }
        return ToolResult.text(out.toString())
    }

    private fun display(path: Path): String {
        val root = Path.of("").toAbsolutePath()
        val absolute = path.toAbsolutePath().normalize()
        return if (absolute.startsWith(root)) root.relativize(absolute).toString() else absolute.toString()
    }

    private fun describe(p: Plugin, status: PluginJars.Status, detail: Boolean): String = buildString {
        val meta = p.pluginMeta
        append(p.name).append(' ').append(meta.version)
        if (!p.isEnabled) append(" DISABLED")
        val path = PluginJars.jar(p)
        if (path != null) {
            append(" · ").append(display(path))
            runCatching { Files.size(path) }.getOrNull()?.let { append(" (").append(PluginJars.size(it)).append(")") }
        }
        val loaded = PluginJars.loadedManifest(p)
        PluginJars.build(loaded)?.let { append(" · build ").append(it) }
        fun names(list: List<String>) = list.take(6).joinToString(", ") { n -> n + if (find(n)?.isEnabled != true) " (absent)" else "" } + if (list.size > 6) ", +${list.size - 6}" else ""
        if (meta.pluginDependencies.isNotEmpty()) append(" · needs ").append(names(meta.pluginDependencies))
        if (meta.pluginSoftDependencies.isNotEmpty()) append(" · soft ").append(names(meta.pluginSoftDependencies))
        status.text?.let { append("\n  ").append(it) }
        if (status.state == PluginJars.State.STALE && path != null) {
            PluginJars.build(PluginJars.disk(path)?.manifest)?.takeIf { it != PluginJars.build(loaded) }?.let { append("\n  on disk: build ").append(it) }
            val n = if (detail) 30 else 5
            if (status.changed.isNotEmpty()) append("\n  differs: ").append(status.changed.take(n).joinToString(", ")).append(if (status.changed.size > n) ", +${status.changed.size - n}" else "")
        }
        if (detail) {
            if (path != null && Files.isSymbolicLink(path)) append("\n  link to ").append(runCatching { path.toRealPath() }.getOrNull() ?: Files.readSymbolicLink(path))
            if (status.state != PluginJars.State.STALE) {
                path?.let { runCatching { Files.getLastModifiedTime(it).toMillis() }.getOrNull() }?.let { append("\n  jar written ").append(ServerLog.shortTime(it)) }
                PluginJars.seen(p)?.let { append(", loaded ").append(ServerLog.shortTime(it.at)) }
            }
            append("\n  main ").append(meta.mainClass).append(" · api ").append(meta.apiVersion ?: "none").append(" · data ").append(display(p.dataFolder.toPath()))
        }
    }

    // Commands, listeners and tasks of a plugin (server thread).
    private fun details(p: Plugin): String = buildString {
        val commands = Bukkit.getCommandMap().knownCommands.entries
            .filter { (label, c) -> ':' !in label && c is PluginIdentifiableCommand && c.plugin === p }.map { it.key }.sorted()
        if (commands.isNotEmpty()) append("\n  commands: ").append(commands.take(40).joinToString(", ")).append(if (commands.size > 40) ", +${commands.size - 40}" else "")
        append("\n  listeners: ${HandlerList.getRegisteredListeners(p).size} · scheduled tasks: ${Bukkit.getScheduler().pendingTasks.count { it.owner === p }}")
    }

    // Status

    private fun status(): ToolResult = Sync.main {
        val out = StringBuilder()
        val runtime = ManagementFactory.getRuntimeMXBean()
        out.append("${Bukkit.getName()} ${Bukkit.getVersion()} · up ${duration(runtime.uptime)}")
        val tps = Bukkit.getTPS()
        val ticks = Bukkit.getTickTimes().filter { it > 0 }
        out.append("\nTPS ${tps.joinToString(" / ") { f1(it.coerceAtMost(20.0)) }} (1, 5, 15 min)")
        if (ticks.isNotEmpty()) out.append(" · MSPT ${f1(ticks.average() / 1e6)} average, ${f1(ticks.max() / 1e6)} worst of the last ${ticks.size} ticks")
        val tickManager = Bukkit.getServerTickManager()
        if (tickManager.isFrozen) out.append(" · ticks FROZEN")
        if (tickManager.tickRate != 20f) out.append(" · tick rate ${f1(tickManager.tickRate.toDouble())}")

        val memory = Runtime.getRuntime()
        val mb = { b: Long -> "${b / 1048576} MB" }
        out.append("\nMemory ${mb(memory.totalMemory() - memory.freeMemory())} used of ${mb(memory.totalMemory())} allocated (max ${mb(memory.maxMemory())})")
        (ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean)?.processCpuLoad?.takeIf { it >= 0 }
            ?.let { out.append(" · CPU ${Math.round(it * 100)}% (process)") }
        out.append(" · ${ManagementFactory.getThreadMXBean().threadCount} threads")
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        out.append(" · GC ${gcs.sumOf { it.collectionCount.coerceAtLeast(0) }} collections, ${f1(gcs.sumOf { it.collectionTime.coerceAtLeast(0) } / 1000.0)} s")

        val players = Bukkit.getOnlinePlayers()
        out.append("\nPlayers (${players.size}/${Bukkit.getMaxPlayers()})" + if (players.isEmpty()) ": none" else ":")
        players.forEach { p ->
            val l = p.location
            out.append("\n  ${p.name} · ${l.world.name} ${f1(l.x)} ${f1(l.y)} ${f1(l.z)} · ${p.gameMode.name.lowercase()} · ${p.ping} ms" + if (p.isOp) " · op" else "")
        }

        val worlds = Bukkit.getWorlds()
        out.append("\nWorlds (${worlds.size}):")
        val (active, idle) = worlds.partition { it.chunkCount > 0 || it.playerCount > 0 }
        active.forEach { w ->
            val types = w.entities.groupingBy { it.type.name.lowercase() }.eachCount().entries.sortedByDescending { it.value }.take(3)
            out.append("\n  ${w.name} (${w.environment.name.lowercase()}): ${w.chunkCount} chunks, ${w.entityCount} entities")
            if (types.isNotEmpty()) out.append(" (${types.joinToString(", ") { "${it.key} ${it.value}" }})")
            out.append(", ${w.tileEntityCount} block entities (${w.tickableTileEntityCount} ticking), ${w.playerCount} players")
        }
        if (idle.isNotEmpty()) out.append("\n  ${idle.size} more without loaded chunks: ${idle.take(12).joinToString(", ") { it.name }}${if (idle.size > 12) ", …" else ""}")

        val tasks = Bukkit.getScheduler().pendingTasks
        out.append("\nScheduled tasks: ${tasks.size}")
        if (tasks.isNotEmpty()) out.append(" (" + tasks.groupingBy { it.owner.name }.eachCount().entries.sortedByDescending { it.value }.take(8).joinToString(", ") { "${it.key} ${it.value}" } + ")")
        out.append(" · async running: ${Bukkit.getScheduler().activeWorkers.size}")
        ToolResult.text(out.toString())
    }

    private fun duration(millis: Long): String {
        val minutes = millis / 60_000
        return when {
            minutes >= 1440 -> "${minutes / 1440}d ${minutes % 1440 / 60}h"
            minutes >= 60 -> "${minutes / 60}h ${minutes % 60}m"
            else -> "${minutes}m ${millis / 1000 % 60}s"
        }
    }

    // Profile

    private fun profile(args: Args): ToolResult {
        val seconds = args.double("seconds", 5.0).coerceIn(0.5, 60.0)
        val interval = args.int("interval_ms", 10).coerceIn(1, 1000)
        val query = args.string("thread", null)?.takeIf { it.isNotEmpty() }
        val threads = if (query == null) listOfNotNull(Sampler.mainThread ?: Thread.getAllStackTraces().keys.firstOrNull { it.name == "Server thread" })
        else Thread.getAllStackTraces().keys.filter { it.name.contains(query, true) && it !== Thread.currentThread() }
        if (threads.isEmpty()) {
            val names = Thread.getAllStackTraces().keys.map { it.name.replace(Regex("\\d+"), "#") }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
            throw ToolError("No thread named like '$query'. Threads: ${names.take(30).joinToString(", ") { if (it.value > 1) "${it.key} ×${it.value}" else it.key }}")
        }
        val focus = args.string("plugin", null)?.takeIf { it.isNotEmpty() }?.let { find(it) ?: throw ToolError("No plugin '$it'") }
        return ToolResult.text(Sampler.profile(threads, (seconds * 1000).toLong(), interval.toLong(), focus))
    }

    // Listeners

    private class Handler(val event: Class<*>, val plugin: Plugin, val listener: Listener, val method: String, val priority: String)

    // As Bukkit registers them: the public methods, inherited ones too, and the class's own.
    private fun methods(type: Class<*>): Set<Method> = (type.methods.asList() + type.declaredMethods.asList()).filter { !it.isBridge && !it.isSynthetic }.toSet()

    private fun listeners(args: Args): ToolResult = Sync.main {
        val query = args.string("event", null)?.takeIf { it.isNotEmpty() }
        val focus = args.string("plugin", null)?.takeIf { it.isNotEmpty() }?.let { find(it) ?: throw ToolError("No plugin '$it'") }
        val limit = args.int("limit", 40).coerceIn(1, 500)
        val handlers = ArrayList<Handler>()
        for (p in Bukkit.getPluginManager().plugins) {
            val listeners = HandlerList.getRegisteredListeners(p).mapTo(Collections.newSetFromMap(IdentityHashMap<Listener, Boolean>())) { it.listener }
            for (l in listeners) for (m in methods(l.javaClass)) {
                val h = m.getAnnotation(EventHandler::class.java) ?: continue
                if (m.parameterCount != 1 || !Event::class.java.isAssignableFrom(m.parameterTypes[0])) continue
                handlers += Handler(m.parameterTypes[0], p, l, "${l.javaClass.simpleName}.${m.name}", h.priority.name)
            }
        }
        if (query == null) {
            if (focus != null) {
                val mine = handlers.filter { it.plugin === focus }.groupBy { it.event.simpleName }.toSortedMap()
                return@main ToolResult.text("${focus.name} handles ${mine.size} event(s)" + (if (mine.isEmpty()) "." else ":\n") +
                    mine.entries.joinToString("\n") { (event, list) -> "  $event: " + list.joinToString(", ") { "${it.method} (${it.priority})" } })
            }
            val byEvent = handlers.groupBy { it.event }.entries.sortedByDescending { it.value.size }
            return@main ToolResult.text("${byEvent.size} events have @EventHandler listeners" + (if (byEvent.size > limit) ", the $limit with the most" else "") + ":\n" +
                byEvent.take(limit).joinToString("\n") { (event, list) ->
                    val plugins = list.map { it.plugin.name }.distinct()
                    "  ${event.simpleName} ${list.size} (${plugins.take(5).joinToString(", ")}${if (plugins.size > 5) ", +${plugins.size - 5}" else ""})"
                })
        }
        val known = handlers.map { it.event }.distinct()
        val classes = known.filter { it.simpleName.equals(query, true) || it.name == query }
            .ifEmpty { listOfNotNull(event(query)) }
            .ifEmpty { known.filter { it.simpleName.contains(query, true) } }
        if (classes.isEmpty()) return@main ToolResult.text("No listener handles an event named like '$query' (events go by class name, e.g. PlayerInteractEvent; a full class name finds one nobody listens to).")
        if (classes.size > 4) return@main ToolResult.text("${classes.size} events match '$query': ${classes.joinToString(", ") { it.simpleName }}. Name one.")
        ToolResult.text(classes.joinToString("\n") { event -> calls(event, handlers, focus) })
    }

    private val EVENT_PACKAGES = listOf("org.bukkit.event", "io.papermc.paper.event", "com.destroystokyo.paper.event").flatMap { base ->
        listOf("", ".block", ".entity", ".player", ".inventory", ".server", ".world", ".weather", ".vehicle", ".hanging", ".enchantment", ".raid", ".command", ".packet")
            .map { base + it }
    }

    /** An event class nobody may listen to directly (a subclass sharing its parent's list): by full name, or in the API's event packages. */
    private fun event(name: String): Class<*>? = (listOf(name) + EVENT_PACKAGES.map { "$it.$name" }).firstNotNullOfOrNull { n ->
        runCatching { Class.forName(n, false, Bukkit::class.java.classLoader) }.getOrNull()?.takeIf { Event::class.java.isAssignableFrom(it) }
    }

    /** The handler list Bukkit registers [event]'s listeners in: its own, or the nearest superclass's. */
    private fun handlerList(event: Class<*>): HandlerList? = generateSequence<Class<*>>(event) { it.superclass }.firstNotNullOfOrNull { c ->
        runCatching { c.getDeclaredMethod("getHandlerList").also { it.isAccessible = true }.invoke(null) as HandlerList }.getOrNull()
    }

    /** The event's listeners in the order Bukkit calls them. */
    private fun calls(event: Class<*>, handlers: List<Handler>, focus: Plugin?): String {
        val list = handlerList(event) ?: return "${event.name}: no handler list."
        val lists = HashMap<Class<*>, HandlerList?>()
        val here = handlers.filter { lists.getOrPut(it.event) { handlerList(it.event) } === list }
        val rows = list.registeredListeners.filter { focus == null || it.plugin === focus }.mapNotNull { r ->
            val mine = here.filter { it.listener === r.listener && it.priority == r.priority.name && it.plugin === r.plugin }
            // A list shared with other events (subclasses of the one that owns it): each method runs only for the events it takes.
            val methods = mine.filter { it.event.isAssignableFrom(event) }
            if (methods.isEmpty() && mine.isNotEmpty()) return@mapNotNull null
            val what = methods.joinToString(", ") { it.method }.ifEmpty { "${r.listener.javaClass.name} (registered directly)" }
            "  ${r.priority.name.padEnd(7)} ${r.plugin.name}: $what" + if (r.isIgnoringCancelled) " · skips cancelled" else ""
        }
        return "${event.simpleName} (${event.packageName}): ${rows.size} listener(s)" + (focus?.let { " of ${it.name}" } ?: "") +
            if (rows.isEmpty()) "." else ", in call order:\n" + rows.joinToString("\n")
    }
}
