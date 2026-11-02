package games.qore.foreman.client.packets

import games.qore.foreman.client.config.Config
import net.minecraft.client.Minecraft
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The recording as text, for the AI tools, the export file and the viewer: a summary (totals, the types, entities and
 * chunks with the most bytes, repeats, slow handling), packet lists and a timeline.
 */
object PacketReports {
    /** What [list] keeps. */
    class Filter(
        /** Parts of type names: a packet matches if its type contains any. */
        val types: List<String> = emptyList(),
        /** Parts of type names left out. */
        val excluded: List<String> = emptyList(),
        val outbound: Boolean? = null,
        val entity: Int? = null,
        val chunk: Pair<Int, Int>? = null,
        val minBytes: Int = 0,
        val fromNanos: Long = Long.MIN_VALUE,
        val toNanos: Long = Long.MAX_VALUE,
        val repeatsOnly: Boolean = false,
        /** In the packet's [text], any case. */
        val contains: String? = null,
    ) {
        fun matches(e: PacketRecorder.Event) = (types.isEmpty() || types.any { e.type.contains(it, true) }) &&
            excluded.none { e.type.contains(it, true) } &&
            (outbound == null || e.outbound == outbound) &&
            (entity == null || (Subject.isEntity(e.subject) && Subject.entityId(e.subject) == entity)) &&
            (chunk == null || (Subject.isChunk(e.subject) && Subject.chunkX(e.subject) == chunk.first && Subject.chunkZ(e.subject) == chunk.second)) &&
            e.wire >= minBytes && e.t in fromNanos..toNanos && (!repeatsOnly || e.repeat) &&
            (contains == null || text(e).contains(contains, true))
    }

    /** What a packet's line says, without its time and sizes: type, entity or chunk, fields. */
    fun text(e: PacketRecorder.Event): String = e.type + "  " + Subject.describe(e.subject) + (if (e.repeat) "  repeat" else "") + (e.detail?.let { " · $it" } ?: "")

    // ---- Formatting -------------------------------------------------------------------------------------------------

    fun bytes(n: Long): String = when {
        n < 1024 -> "$n B"
        n < 1024 * 1024 -> fmt("%.1f KB", n / 1024.0)
        else -> fmt("%.2f MB", n / (1024.0 * 1024))
    }

    fun count(n: Long): String = String.format(Locale.ROOT, "%,d", n)

    fun seconds(nanos: Long): String = fmt("%.1fs", nanos / 1e9)

    fun ms(nanos: Long): String = if (nanos < 10_000_000) fmt("%.2f ms", nanos / 1e6) else fmt("%.0f ms", nanos / 1e6)

    fun clock(nanos: Long): String {
        val s = nanos / 1_000_000_000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun fmt(format: String, vararg args: Any) = String.format(Locale.ROOT, format, *args)

    /** `42%` of [whole]. */
    fun share(part: Long, whole: Long) = if (whole <= 0) "0%" else fmt("%.0f%%", part * 100.0 / whole)

    /** One packet: `+12.345s IN  set_entity_data  50 B (48)  entity 1234  repeat · #8=…` ([fields]: with the part after `·`). */
    fun line(e: PacketRecorder.Event, fields: Boolean = true): String = buildString {
        append(fmt("+%.3fs ", e.t / 1e9))
        append(if (e.outbound) "OUT " else "IN  ")
        append(e.type).append("  ").append(bytes(e.wire.toLong()))
        if (e.payload != e.wire) append(" (").append(bytes(e.payload.toLong())).append(" decoded)")
        if (e.subject != Subject.NONE) append("  ").append(Subject.describe(e.subject))
        if (e.repeat) append("  repeat")
        if (fields) e.detail?.let { append(" · ").append(it) }
    }

    // ---- Subjects ---------------------------------------------------------------------------------------------------

    /**
     * What the reports know of the world, read on the client thread ([capture]) and used anywhere: the entities with the
     * most packets as they are now, and where the player is.
     */
    class Names(val entities: Map<Int, PacketSuspects.EntityInfo>, private val playerChunk: Pair<Int, Int>?) {
        fun entity(id: Int) = entities[id]?.label ?: gone(id)

        fun chunk(x: Int, z: Int) = "chunk $x,$z (blocks ${x * 16}..${x * 16 + 15}, ${z * 16}..${z * 16 + 15})" +
            (playerChunk?.let { (px, pz) -> ", ${maxOf(Math.abs(px - x), Math.abs(pz - z))} chunks away" } ?: "")

        companion object {
            fun capture(): Names {
                val player = Minecraft.getInstance().player
                return Names(PacketSuspects.capture(), player?.let { (it.blockX shr 4) to (it.blockZ shr 4) })
            }
        }
    }

    private const val ENTITIES = 10

    /** On the client thread: an entity's type, what it shows, its name, position and distance, or its type when gone. */
    fun entityName(id: Int): String = Minecraft.getInstance().level?.getEntity(id)?.let { PacketSuspects.info(it).label } ?: gone(id)

    /** An entity the client no longer has: its type, from its spawn packet. */
    private fun gone(id: Int) = (PacketRecorder.entityType(id) ?: "type unknown") + " (gone)"

    // ---- Summary ----------------------------------------------------------------------------------------------------

    /** Everything at a glance; [names] from [Names.capture] on the client thread. */
    fun summary(names: Names, top: Int = 15): String {
        if (!PacketRecorder.hasData) return "Nothing recorded: start a recording first (packets_record, /packets start or the hub's Packets section)."
        val elapsed = PacketRecorder.elapsedNanos().coerceAtLeast(1)
        val secs = elapsed / 1e9
        val types = PacketRecorder.types()
        val received = types.filter { !it.outbound }
        val sent = types.filter { it.outbound }
        val seconds = PacketRecorder.seconds()
        return buildString {
            appendLine("Packet recording of ${PacketRecorder.server}: ${seconds(elapsed)}, ${if (PacketRecorder.recording) "still recording" else "stopped"}. " +
                "${count(PacketRecorder.total)} packets, the last ${count(PacketRecorder.kept())} kept one by one.")
            fun direction(label: String, list: List<PacketRecorder.TypeTotals>) {
                val n = list.sumOf { it.count }
                val wire = list.sumOf { it.wire }
                val payload = list.sumOf { it.payload }
                append("$label: ${count(n)} packets (${fmt("%.1f", n / secs)}/s), ${bytes(wire)} on the wire (${bytes((wire / secs).toLong())}/s)")
                if (payload > wire && wire > 0) append(", ${bytes(payload)} decoded (compressed ×${fmt("%.1f", payload.toDouble() / wire)})")
                appendLine()
            }
            direction("Received", received)
            direction("Sent", sent)
            seconds.withIndex().maxByOrNull { it.value[PacketRecorder.IN_WIRE] }?.let { (i, s) ->
                appendLine("Busiest second received: +${i}s, ${count(s[PacketRecorder.IN_COUNT])} packets, ${bytes(s[PacketRecorder.IN_WIRE])}.")
            }
            val handle = received.sumOf { it.handleNanos }
            append("Client thread: ${ms(handle)} handling packets (${fmt("%.1f%%", handle * 100.0 / elapsed)} of the time)")
            PacketRecorder.worstFrame()?.let { append("; the longest frame spent ${ms(it.nanos)} on ${it.packets} packets at +${seconds(it.t)}") }
            appendLine(".")

            appendLine()
            appendLine("Received, by bytes on the wire (repeat = identical to one of the same type in the 2 s before; client = time the client thread spent):")
            table(this, received.sortedByDescending { it.wire }.take(top), secs, withHandle = true)
            if (sent.isNotEmpty()) {
                appendLine()
                appendLine("Sent, by bytes on the wire:")
                table(this, sent.sortedByDescending { it.wire }.take(top.coerceAtMost(8)), secs, withHandle = false)
            }

            val suspects = PacketSuspects.analyze(names.entities, 5)
            if (suspects.isNotEmpty()) {
                appendLine()
                appendLine("Top suspects (packets grouped by what likely sends them; packets_suspects lists more, with what to search for):")
                suspects.forEachIndexed { i, s ->
                    appendLine("  ${i + 1}. ${s.title}: ${bytes(s.wire)} (${fmt("%.0f%%", s.wire * 100.0 / received.sumOf { it.wire }.coerceAtLeast(1))}), " +
                        "${s.types.take(2).joinToString(", ") { it.first }}${s.cadence?.let { ", $it" } ?: ""}")
                }
            }

            val entities = PacketRecorder.entities(ENTITIES)
            if (entities.isNotEmpty()) {
                appendLine()
                appendLine("Entities with the most bytes:")
                entities.forEach { s -> appendLine("  #${Subject.entityId(s.subject)} ${names.entity(Subject.entityId(s.subject))}: ${subjectLine(s, secs)}") }
            }
            val chunks = PacketRecorder.chunks(8)
            if (chunks.isNotEmpty()) {
                appendLine()
                appendLine("Chunks with the most bytes:")
                chunks.forEach { s -> appendLine("  ${names.chunk(Subject.chunkX(s.subject), Subject.chunkZ(s.subject))}: ${subjectLine(s, secs)}") }
            }
            val repeated = received.filter { it.repeats > 0 && it.repeats * 5 >= it.count }.sortedByDescending { it.repeatWire }.take(8)
            if (repeated.isNotEmpty()) {
                appendLine()
                appendLine("Mostly repeats (sent again unchanged):")
                repeated.forEach { appendLine("  ${it.name}: ${count(it.repeats)} of ${count(it.count)} (${share(it.repeats, it.count)}), ${bytes(it.repeatWire)}") }
            }
            val slowest = PacketRecorder.slowest().take(8)
            if (slowest.isNotEmpty()) {
                appendLine()
                appendLine("Slowest to handle on the client thread:")
                slowest.forEach { appendLine("  ${ms(it.nanos)} +${seconds(it.t)} ${it.type}${if (it.subject != Subject.NONE) " " + Subject.describe(it.subject) else ""} · ${it.detail}") }
            }
        }.trimEnd()
    }

    private fun table(out: StringBuilder, rows: List<PacketRecorder.TypeTotals>, secs: Double, withHandle: Boolean) {
        val w = (rows.maxOfOrNull { it.name.length } ?: 4).coerceIn(4, 48)
        out.append("  ").append("type".padEnd(w)).append("     count      /s      wire       avg       max  repeat")
        if (withHandle) out.append("    client (max)")
        out.appendLine()
        for (r in rows) {
            out.append("  ").append(r.name.take(w).padEnd(w))
            out.append(count(r.count).padStart(10)).append(fmt("%.1f", r.count / secs).padStart(8))
            out.append(bytes(r.wire).padStart(10)).append(bytes(r.wire / r.count.coerceAtLeast(1)).padStart(10)).append(bytes(r.maxWire.toLong()).padStart(10))
            out.append(share(r.repeats, r.count).padStart(8))
            if (withHandle) out.append(if (r.handled > 0) "    ${ms(r.handleNanos)} (${ms(r.maxHandleNanos)})" else "    -")
            out.appendLine()
        }
    }

    private fun subjectLine(s: PacketRecorder.SubjectTotals, secs: Double) =
        "${count(s.count)} packets (${fmt("%.1f", s.count / secs)}/s), ${bytes(s.wire)} · " +
            s.types.take(4).joinToString(", ") { (name, n, wire) -> "$name ${count(n)} (${bytes(wire)})" }

    /** Bytes received on the wire, the whole recording. */
    fun receivedWire(): Long = PacketRecorder.types().filter { !it.outbound }.sumOf { it.wire }

    // ---- Lists and timeline -----------------------------------------------------------------------------------------

    /**
     * The kept packets matching [filter]: the first [limit] in time order, or the biggest when [bySize]. [near]: only those
     * within [windowNanos] of a packet whose [text] contains it (those listed too, marked `*`).
     */
    fun list(filter: Filter, limit: Int, bySize: Boolean = false, fields: Boolean = true, near: String? = null, windowNanos: Long = 250_000_000L): String {
        if (!PacketRecorder.hasData) return "Nothing recorded."
        val anchors = near?.let { n ->
            PacketRecorder.events().filter { it.t in filter.fromNanos..filter.toNanos && text(it).contains(n, true) }.map { it.seq to it.t }.toList()
        }
        if (anchors != null && anchors.isEmpty()) return "No kept packet contains \"$near\"."
        val anchorSeqs = anchors?.mapTo(HashSet()) { it.first }
        val anchorTimes = anchors?.map { it.second }?.toLongArray()?.apply { sort() }
        /** Whether a packet at [t] is within the window of an anchor: the nearest anchors either side. */
        fun nearAnchor(t: Long): Boolean {
            val times = anchorTimes ?: return true
            var i = java.util.Arrays.binarySearch(times, t)
            if (i >= 0) return true
            i = -i - 1
            return (i < times.size && times[i] - t <= windowNanos) || (i > 0 && t - times[i - 1] <= windowNanos)
        }
        val keep = { e: PacketRecorder.Event -> anchorSeqs?.contains(e.seq) == true || (filter.matches(e) && nearAnchor(e.t)) }
        var matched = 0L
        val picked: List<PacketRecorder.Event> = if (bySize) {
            val heap = java.util.PriorityQueue<PacketRecorder.Event>(compareBy { it.wire })
            PacketRecorder.events().filter(keep).forEach { e ->
                matched++
                if (heap.size < limit) heap += e else if (e.wire > heap.peek().wire) { heap.poll(); heap += e }
            }
            heap.sortedByDescending { it.wire }
        } else {
            val out = ArrayList<PacketRecorder.Event>()
            PacketRecorder.events().filter(keep).forEach { e -> matched++; if (out.size < limit) out += e }
            out
        }
        if (picked.isEmpty()) return "No kept packet matches."
        val head = "${count(matched)} kept packets match" +
            (anchors?.let { " (* = the ${count(it.size.toLong())} containing \"$near\", the others within ${ms(windowNanos)} of one)" } ?: "") +
            if (matched > picked.size) ", showing ${if (bySize) "the ${picked.size} biggest" else "the first ${picked.size}"}" else ""
        return head + ":\n" + picked.joinToString("\n") { (if (anchorSeqs?.contains(it.seq) == true) "* " else if (anchorSeqs != null) "  " else "") + line(it, fields) }
    }

    /** Packets and bytes per [bucketMs] (both ways), the type with the most bytes in each, peaks marked. */
    fun timeline(bucketMs: Int, type: String? = null): String {
        if (!PacketRecorder.hasData) return "Nothing recorded."
        val bucket = bucketMs.coerceAtLeast(50) * 1_000_000L
        class Bucket { var inCount = 0L; var inWire = 0L; var outCount = 0L; var outWire = 0L; val types = HashMap<String, Long>() }
        val buckets = sortedMapOf<Long, Bucket>()
        var first = Long.MAX_VALUE
        PacketRecorder.events().filter { type == null || it.type.contains(type, true) }.forEach { e ->
            first = minOf(first, e.t)
            val b = buckets.getOrPut(e.t / bucket) { Bucket() }
            if (e.outbound) { b.outCount++; b.outWire += e.wire } else { b.inCount++; b.inWire += e.wire; b.types.merge(e.type, e.wire.toLong(), Long::plus) }
        }
        if (buckets.isEmpty()) return "No kept packet" + (type?.let { " of type $it" } ?: "") + "."
        val seconds = PacketRecorder.seconds()
        val peak = buckets.values.maxOf { it.inWire }
        val shown = buckets.entries.toList().takeLast(400)
        return buildString {
            append("Per ${bucketMs} ms" + (type?.let { ", type $it" } ?: "") + ": packets and bytes received / sent, the type with the most bytes received")
            if (bucketMs >= 1000) append(", client-thread time (longest frame)")
            appendLine(".")
            if (PacketRecorder.total > PacketRecorder.kept()) appendLine("Only the kept packets: from +${seconds(first)} (older ones were dropped; raise the log size in the settings to keep more).")
            if (buckets.size > shown.size) appendLine("The last ${shown.size} of ${buckets.size} buckets.")
            for ((i, b) in shown) {
                append(fmt("+%7.2fs", i * bucket / 1e9))
                append("  in ").append(count(b.inCount).padStart(6)).append(bytes(b.inWire).padStart(10))
                append("  out ").append(count(b.outCount).padStart(5)).append(bytes(b.outWire).padStart(9))
                b.types.maxByOrNull { it.value }?.let { (name, wire) -> append("  ").append(name).append(' ').append(share(wire, b.inWire)) }
                if (bucketMs >= 1000) {
                    val from = (i * bucket / 1_000_000_000L).toInt()
                    val to = (((i + 1) * bucket - 1) / 1_000_000_000L).toInt()
                    val range = seconds.subList(from.coerceAtMost(seconds.size), (to + 1).coerceAtMost(seconds.size))
                    if (range.isNotEmpty()) append("  client ${ms(range.sumOf { it[PacketRecorder.HANDLE] })} (${ms(range.maxOf { it[PacketRecorder.WORST_FRAME] })})")
                }
                if (peak > 0 && b.inWire == peak) append("  ◀ peak")
                appendLine()
            }
        }.trimEnd()
    }

    // ---- Export -----------------------------------------------------------------------------------------------------

    /** Writes the summary, the per-second timeline and every kept packet to `config/foreman/packets/`. */
    fun export(names: Names): Path {
        val dir = Config.dir.resolve("packets")
        Files.createDirectories(dir)
        val name = PacketRecorder.server.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt"
        val file = dir.resolve(name)
        // Text cut elsewhere (a mod's own toString) may still end in half a surrogate pair: replaced, not fatal.
        val utf8 = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
        java.io.BufferedWriter(java.io.OutputStreamWriter(Files.newOutputStream(file), utf8)).use { w ->
            w.write(summary(names, 30)); w.write("\n\n")
            w.write("Suspects (received packets grouped by what likely sends them):\n")
            w.write(PacketSuspects.report(PacketSuspects.analyze(names.entities), receivedWire())); w.write("\n\n")
            w.write(timeline(1000)); w.write("\n\nEvery kept packet:\n")
            PacketRecorder.events().forEach { w.write(line(it)); w.write("\n") }
        }
        return file
    }
}
