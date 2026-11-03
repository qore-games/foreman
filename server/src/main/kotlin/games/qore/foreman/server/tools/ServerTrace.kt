package games.qore.foreman.server.tools

import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.Vector
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.function.Function

/**
 * `server_trace`: the server's view of a player tick by tick, with what plugins know of them ([values], registered
 * through `ForemanTools.registerTraceValues`). Its rows and summary match the client's `client_trace`, to be read side
 * by side. A trace is a task of [MAX_TICKS] ticks at most; nothing runs, nor are plugins asked, without one.
 */
object ServerTrace {
    const val MAX_TICKS = 1200

    /** Plugins' trace values, by plugin name. */
    val values = ConcurrentHashMap<String, Function<Player, Map<String, Any?>?>>()

    /** A trace running: [player], the [followed] entities, a row every [every] ticks. */
    private class Trace(val player: Player, val ticks: Int, val every: Int, val followed: List<Entity>, val showRows: Boolean) {
        var tick = 0
        val rows = mutableListOf<String>()
        val done = CompletableFuture<String>()
        val started = System.currentTimeMillis()
        var last: Vector? = null
        var ground = 0
        var changes = 0
        var wasGround: Boolean? = null
        var start: Vector? = null
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val feetMin = DoubleArray(followed.size) { Double.MAX_VALUE }
        val feetMax = DoubleArray(followed.size) { -Double.MAX_VALUE }
        val lowestAt = IntArray(followed.size)
        /** Each plugin number's range, by `plugin.key`, in the order first seen */
        val ranges = LinkedHashMap<String, DoubleArray>()
        lateinit var task: BukkitTask
    }

    fun trace(plugin: Foreman, args: Args): ToolResult {
        val ticks = args.int("ticks", 60).coerceIn(1, MAX_TICKS)
        val every = args.int("every", 1).coerceIn(1, ticks)
        val types = args.string("entities", null)?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }.orEmpty()
        val radius = args.double("radius", 8.0).coerceIn(1.0, 64.0)
        val limit = args.int("limit", 4).coerceIn(0, 16)
        val trace = Sync.main {
            val player = args.string("player", null)?.let { Bukkit.getPlayerExact(it) ?: throw ToolError("$it isn't online") }
                ?: args.caller?.let { Bukkit.getPlayer(it.id) } ?: throw ToolError("Name a player, or call as one in game")
            val followed = if (types.isEmpty()) emptyList() else player.getNearbyEntities(radius, radius, radius)
                .filter { e -> types.any { e.type.key.key.contains(it) } }
                .sortedBy { it.location.distanceSquared(player.location) }.take(limit)
            Trace(player, ticks, every, followed, args.bool("rows", true)).also { t ->
                t.task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick(t) }, 0L, 1L)
            }
        }
        return try {
            ToolResult.text(trace.done.get(ticks * 50L + 10_000, TimeUnit.MILLISECONDS))
        } catch (_: TimeoutException) {
            Sync.main { trace.task.cancel() }
            throw ToolError("The server stopped ticking")
        }
    }

    private fun tick(trace: Trace) {
        val player = trace.player
        if (!player.isOnline) {
            trace.task.cancel()
            trace.done.complete(summary(trace) + "\nStopped: ${player.name} left")
            return
        }
        val at = player.location.toVector()
        val moved = trace.last?.let { at.clone().subtract(it) } ?: Vector()
        trace.last = at
        // what the client last said, which is the point: deprecated for being only the client's
        @Suppress("DEPRECATION")
        val ground = player.isOnGround
        if (ground) trace.ground++
        if (trace.wasGround != null && trace.wasGround != ground) trace.changes++
        trace.wasGround = ground
        if (trace.start == null) trace.start = at
        trace.minY = minOf(trace.minY, at.y)
        trace.maxY = maxOf(trace.maxY, at.y)
        val feet = player.boundingBox.minY
        // the summary's numbers every tick, a row's text only every `every`
        val row = trace.tick % trace.every == 0
        val cells = StringBuilder()
        trace.followed.forEachIndexed { i, e ->
            if (!e.isValid) {
                if (row) cells.append("  | #${e.entityId} gone")
                return@forEachIndexed
            }
            val box = e.boundingBox
            val f = feet - box.maxY
            if (f < trace.feetMin[i]) { trace.feetMin[i] = f; trace.lowestAt[i] = trace.tick }
            trace.feetMax[i] = maxOf(trace.feetMax[i], f)
            if (row) cells.append("  | #%d %.3f..%.3f feet%+.3f".format(Locale.ROOT, e.entityId, box.minY, box.maxY, f))
        }
        for ((name, provider) in values) {
            val map = try { provider.apply(player) } catch (e: Exception) { mapOf("error" to (e.message ?: e.javaClass.simpleName)) }
            if (map.isNullOrEmpty()) continue
            for ((k, v) in map) if (v is Number) trace.ranges.getOrPut("$name.$k") { doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE) }.let {
                it[0] = minOf(it[0], v.toDouble()); it[1] = maxOf(it[1], v.toDouble())
            }
            if (row) cells.append("  | $name ").append(map.entries.joinToString(" ") { (k, v) -> "$k=${value(v)}" })
        }
        if (row) {
            val input = player.currentInput
            val keys = listOfNotNull("fwd".takeIf { input.isForward }, "back".takeIf { input.isBackward }, "left".takeIf { input.isLeft },
                "right".takeIf { input.isRight }, "jump".takeIf { input.isJump }, "sneak".takeIf { input.isSneak }, "sprint".takeIf { input.isSprint })
            trace.rows += "%3d  %.3f %.3f %.3f  moved %+.3f %+.3f %+.3f  %s %s keys %s".format(
                Locale.ROOT, trace.tick, at.x, at.y, at.z, moved.x, moved.y, moved.z, if (ground) "ground" else "air",
                player.pose.name.lowercase(Locale.ROOT), keys.joinToString("+").ifEmpty { "-" },
            ) + cells
        }
        if (++trace.tick >= trace.ticks) {
            trace.task.cancel()
            val header = "tick  x y z  moved since the tick before  flags pose keys" +
                trace.followed.joinToString("") { "  | #${it.entityId} ${it.type.key.key} box bottom..top, the player's feet against its top" } +
                values.keys.joinToString("") { "  | $it's values" }
            trace.done.complete(summary(trace) + if (trace.showRows) "\n" + header + "\n" + trace.rows.joinToString("\n") else "")
        }
    }

    /** The trace in a line, as client_trace's, with each plugin number's range. */
    private fun summary(trace: Trace): String {
        val start = trace.start ?: return "Summary: no tick recorded"
        val end = trace.last ?: start
        val time = LocalTime.ofInstant(Instant.ofEpochMilli(trace.started), ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
        val ticks = trace.tick
        return "Summary: %d ticks from %s: ground %d, air %d (%d changes); y %.3f..%.3f; moved %.2f horizontally to %.2f %.2f %.2f".format(
            Locale.ROOT, ticks, time, trace.ground, ticks - trace.ground, trace.changes, trace.minY, trace.maxY,
            Math.hypot(end.x - start.x, end.z - start.z), end.x, end.y, end.z,
        ) + trace.followed.indices.joinToString("") { i ->
            val e = trace.followed[i]
            if (trace.feetMin[i] == Double.MAX_VALUE) "; #${e.entityId} gone"
            else "; #%d %s feet %+.3f..%+.3f (lowest at tick %d)".format(Locale.ROOT, e.entityId, e.type.key.key, trace.feetMin[i], trace.feetMax[i], trace.lowestAt[i])
        } + trace.ranges.entries.joinToString("") { (k, r) -> "; $k ${value(r[0])}..${value(r[1])}" }
    }

    private fun value(v: Any?): String = when (v) {
        null -> "-"
        is Float, is Double -> "%.3f".format(Locale.ROOT, (v as Number).toDouble())
        is Vector -> "%.3f,%.3f,%.3f".format(Locale.ROOT, v.x, v.y, v.z)
        else -> v.toString()
    }
}
