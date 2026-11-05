package games.qore.foreman.client.packets

import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.mcp.ClientTool
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.mcp.ToolFailure
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonElement

/** The AI's packet tools: record the connection, then read the summary, lists and timeline to find what floods it. */
object PacketTools {
    fun init() {
        ClientMcp.register(ClientTool(
            "packets_record",
            "Records the packets between the player's client and the server, both ways, to find what lags the connection " +
                "or stutters the client. `start` with `seconds` records that long (max 120), waits, and returns the summary; " +
                "without it, it records until `stop`. `clear` drops the recording, `status` says where it is. A new start drops " +
                "the previous recording. Ask the player to do what lags (walk into the area, open the menu…) while it records.",
            ClientTools.schema("action" to "string: start, stop, clear or status", "seconds" to "integer: for start, how long to record (then stop)",
                "contents" to "boolean: for start, keep each packet's fields (default: the player's setting)"),
            acts = { (it["action"].str() ?: "status") != "status" },
        ) { _, args ->
            when (args["action"].str() ?: "status") {
                "start" -> {
                    val seconds = args["seconds"].int()?.coerceIn(1, 120) ?: 0
                    PacketRecorder.start(seconds, args["contents"].bool())
                    if (seconds == 0) return@ClientTool listOf(ClientMcp.text("Recording until packets_record stop."))
                    val until = System.currentTimeMillis() + seconds * 1000L + 2000
                    while (PacketRecorder.recording && System.currentTimeMillis() < until) Thread.sleep(200)
                    PacketRecorder.stop()
                    listOf(ClientMcp.text(PacketReports.summary(names())))
                }
                "stop" -> { PacketRecorder.stop(); listOf(ClientMcp.text(status())) }
                "clear" -> { PacketRecorder.clear(); listOf(ClientMcp.text("Recording dropped.")) }
                "status" -> listOf(ClientMcp.text(status()))
                else -> throw ToolFailure("action: start, stop, clear or status")
            }
        })

        ClientMcp.register(ClientTool(
            "packets_summary",
            "The recording at a glance: totals each way (packets, bytes on the wire, compression), the busiest second, the " +
                "client thread's time on packets, then the packet types by bytes (count, rate, sizes, share of repeats, client time), " +
                "the entities and chunks that get the most, the types mostly repeated unchanged, and the slowest packets to handle. " +
                "Start here, then dig with packets_timeline and packets_list.",
            ClientTools.schema("top" to "integer: types listed (default 15)"),
            acts = { false },
        ) { _, args -> listOf(ClientMcp.text(PacketReports.summary(names(), (args["top"].int() ?: 15).coerceIn(1, 100)))) })

        ClientMcp.register(ClientTool(
            "packets_suspects",
            "The received packets grouped by what likely sends them: one kind of entity (a model's parts at one place, every " +
                "zombie…), one boss bar or action bar text, a plugin channel, chunk updates. For each: packets, bytes, share, " +
                "repeats, how often it is resent (every tick…), the entity fields sent, a sample, a hint, and what to search for " +
                "(item models, custom data keys, translation keys, texts, names, channels). The client can't see which plugin " +
                "sent a packet: search these clues in the plugins' sources and the server configs (the chat's folders) to find it.",
            ClientTools.schema("limit" to "integer: suspects listed (default 15)"),
            acts = { false },
        ) { _, args ->
            if (!PacketRecorder.hasData) throw ToolFailure("Nothing recorded")
            val infos = ClientTools.onClient { PacketSuspects.capture() }
            listOf(ClientMcp.text(PacketSuspects.report(PacketSuspects.analyze(infos, (args["limit"].int() ?: 15).coerceIn(1, 60)), PacketReports.receivedWire())))
        })

        ClientMcp.register(ClientTool(
            "packets_list",
            "Kept packets one by one, filtered: time since the start, direction, type, size on the wire (and decoded), the " +
                "entity or chunk it is about, whether it repeats one just received, and its fields. `order: size` lists the biggest. " +
                "To see what comes with one packet, `near` it: e.g. type `entity_data` near `sound` lists the entity data sent " +
                "within `window` of each sound packet, those included.",
            ClientTools.schema(
                "type" to "string: parts of type names, comma-separated (any of them), e.g. `entity_data`, `custom_payload/`, `chunk,light`",
                "exclude" to "string: leave out the types containing these, comma-separated, e.g. `move_entity,keep_alive`",
                "direction" to "string: in (received) or out (sent)",
                "entity" to "integer: an entity id", "chunk" to "array: [x, z] chunk coordinates",
                "min_bytes" to "integer: at least this many bytes on the wire",
                "from" to "number: seconds since the recording's start (the +times)", "to" to "number: seconds since the start",
                "contains" to "string: in the packet's line (type, entity or chunk, fields), case-insensitive",
                "near" to "string: only packets within `window` of one whose line contains this (listed too, marked *)",
                "window" to "number: seconds either side of each `near` packet (default 0.25)",
                "repeats" to "boolean: only the repeats", "fields" to "boolean: each packet's fields (default true)",
                "limit" to "integer: default 100, max 1000", "order" to "string: time (default) or size",
            ),
            acts = { false },
        ) { _, args ->
            fun parts(key: String) = args[key].str()?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            val chunk = args["chunk"].arr()?.mapNotNull { it.int() }?.takeIf { it.size == 2 }?.let { it[0] to it[1] }
            val filter = PacketReports.Filter(
                types = parts("type"), excluded = parts("exclude"),
                outbound = when (args["direction"].str()) { "in" -> false; "out" -> true; else -> null },
                entity = args["entity"].int(), chunk = chunk, minBytes = args["min_bytes"].int() ?: 0,
                fromNanos = args["from"].double()?.let { (it * 1e9).toLong() } ?: Long.MIN_VALUE,
                toNanos = args["to"].double()?.let { (it * 1e9).toLong() } ?: Long.MAX_VALUE,
                repeatsOnly = args["repeats"].bool() == true, contains = args["contains"].str()?.takeIf { it.isNotBlank() },
            )
            listOf(ClientMcp.text(PacketReports.list(filter, (args["limit"].int() ?: 100).coerceIn(1, 1000), args["order"].str() == "size",
                fields = args["fields"].bool() != false, near = args["near"].str()?.takeIf { it.isNotBlank() },
                windowNanos = ((args["window"].double() ?: 0.25).coerceIn(0.0, 60.0) * 1e9).toLong())))
        })

        ClientMcp.register(ClientTool(
            "packets_timeline",
            "Packets and bytes per time bucket, both ways, with the type that weighs most in each and the peaks marked; per " +
                "second or more, the client thread's time on packets and its longest frame. Small buckets (50 ms = a tick) show bursts.",
            ClientTools.schema("bucket_ms" to "integer: bucket length (default 1000, min 50)", "type" to "string: only types containing this"),
            acts = { false },
        ) { _, args -> listOf(ClientMcp.text(PacketReports.timeline(args["bucket_ms"].int() ?: 1000, args["type"].str()?.takeIf { it.isNotBlank() }))) })

        ClientMcp.register(ClientTool(
            "packets_stats",
            "The recording's numbers as compact JSON, for test reports and scripts: packets and bytes on the wire each way " +
                "(totals, per second avg/min/max, a series over time), per type, the silences from the server of `gap_ms` or " +
                "more (count, the longest with when they began, ms since the start; `started_at` is the start's epoch ms), and " +
                "keep-alives (how long the client took to answer, time between them, the ping the server measured and sent for " +
                "the tab list). Over TCP nothing is lost on the way: a bad link or a stalled server shows as silences then " +
                "bursts, stretched keep-alive intervals, a higher ping or a timeout, never as missing packets.",
            ClientTools.schema(
                "since_ms" to "integer: only from this many ms after the start; negative: the last that many ms",
                "gap_ms" to "integer: shortest silence counted (default 250, min 100)",
                "top" to "integer: types each way and silences listed (default 15)",
            ),
            acts = { false },
        ) { _, args -> listOf(ClientMcp.text(stats(args["since_ms"].int() ?: 0, (args["gap_ms"].int() ?: 250).coerceAtLeast(100), (args["top"].int() ?: 15).coerceIn(1, 100)))) })

        ClientMcp.register(ClientTool(
            "packets_export",
            "Writes the recording (summary, timeline per second, every kept packet) to a text file and returns its path.",
            ClientTools.schema(),
            acts = { false },
        ) { _, _ ->
            if (!PacketRecorder.hasData) throw ToolFailure("Nothing recorded")
            listOf(ClientMcp.text("Written to ${PacketReports.export(names())}"))
        })
    }

    private fun names() = ClientTools.onClient { PacketReports.Names.capture() }

    /** packets_stats: types over the whole recording, or from the kept packets when [sinceMs] narrows it. */
    private fun stats(sinceMs: Int, gapMs: Int, top: Int): String {
        if (!PacketRecorder.hasData) throw ToolFailure("Nothing recorded: packets_record start first")
        val elapsed = PacketRecorder.elapsedNanos()
        val since = (if (sinceMs >= 0) sinceMs * 1_000_000L else elapsed + sinceMs * 1_000_000L).coerceIn(0L, elapsed)
        var typesFrom = 0L
        val types = if (since == 0L) PacketRecorder.types().map { PacketStats.Type(it.name, it.outbound, it.count, it.wire) } else {
            val counts = LinkedHashMap<Pair<String, Boolean>, LongArray>()
            var oldest = Long.MAX_VALUE
            for (e in PacketRecorder.events()) {
                oldest = minOf(oldest, e.t)
                if (e.t < since) continue
                val c = counts.getOrPut(e.type to e.outbound) { LongArray(2) }
                c[0]++
                c[1] += e.wire.toLong()
            }
            if (oldest != Long.MAX_VALUE && oldest > since) typesFrom = oldest
            counts.map { (key, c) -> PacketStats.Type(key.first, key.second, c[0], c[1]) }
        }
        val seconds = PacketRecorder.seconds().map {
            PacketStats.Second(it[PacketRecorder.IN_COUNT], it[PacketRecorder.IN_WIRE], it[PacketRecorder.OUT_COUNT], it[PacketRecorder.OUT_WIRE])
        }
        val (gaps, gapsLeftOut) = PacketRecorder.gaps()
        val recording = PacketStats.Recording(
            PacketRecorder.server, PacketRecorder.startedAt, PacketRecorder.recording, elapsed, types, typesFrom, seconds,
            gaps, gapsLeftOut, PacketRecorder.lastReceived(), PacketRecorder.keepAlives(), PacketRecorder.pings(),
        )
        return JSON.encodeToString(JsonElement.serializer(), PacketStats.json(recording, since, gapMs, top))
    }

    fun status(): String = when {
        PacketRecorder.recording -> "Recording for ${PacketReports.clock(PacketRecorder.elapsedNanos())}: ${PacketReports.count(PacketRecorder.total)} packets."
        PacketRecorder.hasData -> "Stopped after ${PacketReports.clock(PacketRecorder.elapsedNanos())}: ${PacketReports.count(PacketRecorder.total)} packets."
        else -> "Not recording."
    }
}
