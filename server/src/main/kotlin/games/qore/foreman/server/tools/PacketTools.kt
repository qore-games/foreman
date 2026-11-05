package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.admin.PacketRecording
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Sync
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.scheduler.BukkitTask
import java.util.Locale

/**
 * [ForemanTool.SERVER_PACKETS]: what the server sends its players and gets from them, recorded on its side for a while
 * ([PacketRecording]), to find what spends bandwidth: per player, per packet type, and what the packets are about.
 */
object PacketTools {
    @Volatile
    private var recording: PacketRecording? = null
    private var joins: Listener? = null
    private var end: BukkitTask? = null

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.SERVER_PACKETS,
            "Records what the server sends its players and gets from them, on the server's side, every player at once: " +
                "packets and bytes per player and packet type (before compression and on the wire), bytes per second, " +
                "and what the packets sent are about (entities by kind and plugin, plugin channels, teams, objectives), " +
                "to find what spends bandwidth. `start` records for `seconds` (by default waiting, then answering the " +
                "summary), `stop` ends early, `summary` answers it again (`player` for one player's). Nothing runs " +
                "between recordings. The client's packets_* tools see one player's side.",
            schema {
                enum("action", listOf("start", "stop", "summary"), "What to do (default start)", required = false)
                int("seconds", "start: how long (default 30, max 600)", required = false)
                strings("players", "start: only these players (default: everyone online, and those joining)", required = false)
                bool("wait", "start: wait until it ends and answer the summary (default true); false to do something meanwhile", required = false)
                string("player", "summary: this player's own lists", required = false)
                int("limit", "summary: rows per list (default 15)", required = false)
            },
        ) { args ->
            ServerTools.admin(args)
            when (args.string("action", "start")) {
                "start" -> start(plugin, args.int("seconds", 30).coerceIn(1, 600), args.strings("players"), args.bool("wait", true), args.int("limit", 15))
                "stop" -> (recording ?: throw ToolError("Nothing recorded yet: action start")).let { stop(); ToolResult.text(summary(it, null, args.int("limit", 15))) }
                "summary" -> ToolResult.text(summary(recording ?: throw ToolError("Nothing recorded yet: action start"), args.string("player", null), args.int("limit", 15)))
                else -> throw ToolError("Unknown action: start, stop or summary")
            }
        },
    )

    private fun start(plugin: Foreman, seconds: Int, players: List<String>, wait: Boolean, limit: Int): ToolResult {
        stop()
        val rec = PacketRecording(seconds, players.map { it.lowercase() }.toSet().ifEmpty { null })
        recording = rec
        val joined = object : Listener {
            @EventHandler
            fun onJoin(event: PlayerJoinEvent) = rec.attach(event.player)
        }
        Sync.main {
            Bukkit.getOnlinePlayers().forEach(rec::attach)
            Bukkit.getPluginManager().registerEvents(joined, plugin)
            joins = joined
            end = Bukkit.getScheduler().runTaskLater(plugin, Runnable { if (recording === rec) stop() }, seconds * 20L)
        }
        if (rec.players.isEmpty() && rec.failed.isEmpty()) {
            stop()
            throw ToolError("No player to record" + if (players.isNotEmpty()) ": ${players.joinToString()} aren't online" else " online")
        }
        if (!wait) return ToolResult.text("Recording ${rec.players.size} player(s) for $seconds s (and those joining): action summary afterwards, or stop to end now.")
        while (rec.stopped == null) Thread.sleep(200)
        Thread.sleep(100)
        return ToolResult.text(summary(rec, null, limit))
    }

    /** Ends the recording (its handlers off every connection); its counts stay for the summary. */
    fun stop() {
        val rec = recording ?: return
        Sync.main {
            end?.cancel()
            joins?.let { HandlerList.unregisterAll(it) }
            joins = null
            rec.stop()
        }
    }

    /** The recording in text: totals, rates and peaks, players, packet types, subjects, the players it couldn't record. */
    private fun summary(rec: PacketRecording, player: String?, limit: Int): String {
        val all = rec.players.values.toList()
        val chosen = if (player == null) all else all.filter { it.name.equals(player, true) }.ifEmpty { throw ToolError("$player wasn't recorded: ${all.joinToString { it.name }}") }
        val seconds = rec.elapsedMs / 1000.0
        fun sum(f: (PacketRecording.PlayerStats) -> Map<String, PacketRecording.Counter>) = chosen.flatMap { f(it).entries }
            .groupBy({ it.key }, { it.value }).mapValues { (_, c) -> c.sumOf { it.packets.sum() } to c.sumOf { it.bytes.sum() } }
        val sent = sum { it.sent }
        val received = sum { it.received }
        val wireSent = chosen.sumOf { it.wireSent.sum() }
        val wireReceived = chosen.sumOf { it.wireReceived.sum() }
        val sentBytes = sent.values.sumOf { it.second }
        val out = StringBuilder()
        out.append("Packets ").append(if (rec.stopped == null) "for ${f1(seconds)} s so far (recording)" else "recorded for ${f1(seconds)} s")
            .append(", ${chosen.size} player(s)").append(player?.let { " ($it)" }.orEmpty()).append(": sent ${n(sent.values.sumOf { it.first })} packets, ")
            .append("${size(sentBytes)} before compression, ${size(wireSent)} on the wire")
            .append(if (sentBytes > 0) " (${(wireSent * 100 / sentBytes)} %)" else "")
            .append("; received ${n(received.values.sumOf { it.first })} packets, ${size(wireReceived)} on the wire.\n")
        if (player == null) {
            val per = (0 until rec.perSecond.length()).map { rec.perSecond.get(it) }.take(maxOf(1, Math.ceil(seconds).toInt()))
            val peak = per.indices.maxByOrNull { per[it] } ?: 0
            out.append("Sent on the wire per second: ${size(wireSent / maxOf(seconds, 1.0).toLong())} on average, ${size(per.getOrElse(peak) { 0 })} at most (second $peak).\n")
            out.append("Players (sent on the wire, per second; received):\n")
            all.sortedByDescending { it.wireSent.sum() }.take(limit).forEach {
                out.append("  ${it.name}: ${size(it.wireSent.sum())}, ${size((it.wireSent.sum() / maxOf(seconds, 1.0)).toLong())}/s; ${size(it.wireReceived.sum())}\n")
            }
        }
        out.append("Sent by type (before compression):\n")
        sent.entries.sortedByDescending { it.value.second }.take(limit).forEach { (type, v) ->
            out.append("  $type: ${n(v.first)} (${f1(v.first / maxOf(seconds, 0.1))}/s), ${size(v.second)}, ${v.second / maxOf(v.first, 1)} B each\n")
        }
        subjects(chosen, limit).takeIf { it.isNotEmpty() }?.let { out.append("What they're about (before compression):\n").append(it) }
        out.append("Received by type:\n")
        received.entries.sortedByDescending { it.value.first }.take(minOf(limit, 8)).forEach { (type, v) ->
            out.append("  $type: ${n(v.first)} (${f1(v.first / maxOf(seconds, 0.1))}/s), ${size(v.second)}\n")
        }
        rec.failed.forEach { (name, why) -> out.append("Not recorded: $name ($why)\n") }
        return out.toString().trimEnd()
    }

    /** Subjects by bytes, entities grouped by what they are (resolved now, on the server thread). */
    private fun subjects(stats: List<PacketRecording.PlayerStats>, limit: Int): String {
        class Group(var count: Int = 0, var packets: Long = 0, var bytes: Long = 0, val types: HashMap<String, Long> = HashMap(), var example: String? = null)
        val bySubject = HashMap<String, HashMap<String, Pair<Long, Long>>>()
        stats.forEach { s ->
            s.subjects.forEach { (subject, types) ->
                val m = bySubject.getOrPut(subject) { HashMap() }
                types.forEach { (type, c) -> m.merge(type, c.packets.sum() to c.bytes.sum()) { a, b -> (a.first + b.first) to (a.second + b.second) } }
            }
        }
        val ids = bySubject.keys.mapNotNull { it.removePrefix("entity:").takeIf { _ -> it.startsWith("entity:") }?.toIntOrNull() }.toSet()
        val entities = if (ids.isEmpty()) emptyMap() else Sync.main {
            Bukkit.getWorlds().flatMap { w -> w.entities.filter { it.entityId in ids } }.associate { it.entityId to (kind(it) to example(it)) }
        }
        val groups = HashMap<String, Group>()
        bySubject.forEach { (subject, types) ->
            val (key, example) = subject.removePrefix("entity:").toIntOrNull()?.takeIf { subject.startsWith("entity:") }?.let { id -> entities[id] ?: ("entity gone since" to null) }
                ?: (subject.replaceFirst(":", " ") to null)
            val g = groups.getOrPut(key) { Group() }
            g.count++
            types.forEach { (type, v) -> g.packets += v.first; g.bytes += v.second; g.types.merge(type, v.first, Long::plus) }
            if (g.example == null) g.example = example
        }
        return groups.entries.sortedByDescending { it.value.bytes }.take(limit).joinToString("") { (key, g) ->
            "  $key" + (if (g.count > 1) " ×${g.count}" else "") + ": ${size(g.bytes)}, ${n(g.packets)} packets (" +
                g.types.entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} ${n(it.value)}" } + ")" +
                (g.example?.let { ", e.g. $it" } ?: "") + "\n"
        }
    }

    /** What an entity is, to group the like: a player by name, else its type, the plugins that mark it (its data's namespaces) and what it rides. */
    private fun kind(e: Entity): String {
        if (e is Player) return "player ${e.name}"
        val plugins = e.persistentDataContainer.keys.map { it.namespace }.filter { it != "minecraft" }.distinct().sorted()
        return e.type.key.key + (if (plugins.isNotEmpty()) " [${plugins.joinToString()}]" else "") + (e.vehicle?.let { " on ${it.type.key.key}" } ?: "")
    }

    private fun example(e: Entity): String? = e.customName()?.let { PlainTextComponentSerializer.plainText().serialize(it) }?.takeIf { it.isNotBlank() }?.let { "\"${it.take(40)}\"" }
        ?: e.scoreboardTags.firstOrNull()?.let { "tag $it" }

    private fun n(v: Long) = String.format(Locale.ROOT, "%,d", v)

    private fun f1(v: Double) = String.format(Locale.ROOT, "%.1f", v)

    private fun size(bytes: Long) = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)
    }
}
