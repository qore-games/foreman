package games.qore.foreman.client.packets

import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.mcp.ClientTool
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.mcp.ToolFailure
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str

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
        ) { _, args -> listOf(ClientMcp.text(PacketReports.summary(names(), (args["top"].int() ?: 15).coerceIn(1, 100)))) })

        ClientMcp.register(ClientTool(
            "packets_suspects",
            "The received packets grouped by what likely sends them: one kind of entity (a model's parts at one place, every " +
                "zombie…), one boss bar or action bar text, a plugin channel, chunk updates. For each: packets, bytes, share, " +
                "repeats, how often it is resent (every tick…), the entity fields sent, a sample, a hint, and what to search for " +
                "(item models, custom data keys, translation keys, texts, names, channels). The client can't see which plugin " +
                "sent a packet: search these clues in the plugins' sources and the server configs (the chat's folders) to find it.",
            ClientTools.schema("limit" to "integer: suspects listed (default 15)"),
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
        ) { _, args -> listOf(ClientMcp.text(PacketReports.timeline(args["bucket_ms"].int() ?: 1000, args["type"].str()?.takeIf { it.isNotBlank() }))) })

        ClientMcp.register(ClientTool(
            "packets_export",
            "Writes the recording (summary, timeline per second, every kept packet) to a text file and returns its path.",
            ClientTools.schema(),
        ) { _, _ ->
            if (!PacketRecorder.hasData) throw ToolFailure("Nothing recorded")
            listOf(ClientMcp.text("Written to ${PacketReports.export(names())}"))
        })
    }

    private fun names() = ClientTools.onClient { PacketReports.Names.capture() }

    fun status(): String = when {
        PacketRecorder.recording -> "Recording for ${PacketReports.clock(PacketRecorder.elapsedNanos())}: ${PacketReports.count(PacketRecorder.total)} packets."
        PacketRecorder.hasData -> "Stopped after ${PacketReports.clock(PacketRecorder.elapsedNanos())}: ${PacketReports.count(PacketRecorder.total)} packets."
        else -> "Not recording."
    }
}
