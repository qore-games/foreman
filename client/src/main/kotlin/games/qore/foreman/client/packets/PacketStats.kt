package games.qore.foreman.client.packets

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.math.ceil

/**
 * `packets_stats`: a recording's numbers as compact JSON for reports and scripts (totals, types, rates over time,
 * silences from the server, keep-alives), from plain values. Times are nanoseconds since the recording's start.
 */
object PacketStats {
    private const val SECOND = 1_000_000_000L
    private const val MS = 1_000_000L

    class Type(val name: String, val outbound: Boolean, val packets: Long, val bytes: Long)

    /** One second of the recording: packets and bytes on the wire each way. */
    class Second(val inPackets: Long, val inBytes: Long, val outPackets: Long, val outBytes: Long)

    class Recording(
        val server: String,
        /** Wall clock (epoch ms) of the start. */
        val startedAt: Long,
        val recording: Boolean,
        val elapsed: Long,
        /** Per type and direction over [typesFrom] on (the whole recording when 0). */
        val types: List<Type>,
        val typesFrom: Long,
        /** Second by second from the start, the running one last; seconds without packets may be missing at the end. */
        val seconds: List<Second>,
        /** Silences from the server: [start, length]. */
        val gaps: List<LongArray>,
        /** Silences that weren't kept (too many). */
        val gapsLeftOut: Long,
        /** When the last packet was received (0: none). */
        val lastReceived: Long,
        /** The server's keep-alives: [id, received, answered or -1]. */
        val keepAlives: List<LongArray>,
        /** The player's latency the server sent: [when, ms]. */
        val pings: List<LongArray>,
    )

    /**
     * [r] from [since] on: totals, the [top] types each way by bytes, rates per second (a series of [points] values at
     * most), silences from the server of [gapMs] or more (the longest [top]), keep-alives.
     */
    fun json(r: Recording, since: Long, gapMs: Int, top: Int = 15, points: Int = 120): JsonObject = buildJsonObject {
        put("server", r.server)
        put("started_at", r.startedAt)
        put("recording", r.recording)
        put("ms", r.elapsed / MS)
        if (since > 0) put("from_ms", since / MS)
        val seconds = seconds(r, since)
        // Totals take the running (or last) second too: rates only full ones.
        val all = r.seconds.drop((since / SECOND).toInt())
        put("in", direction(all.sumOf { it.inPackets }, all.sumOf { it.inBytes }, seconds.map { it.inPackets }, seconds.map { it.inBytes }, r.types.filter { !it.outbound }, top))
        put("out", direction(all.sumOf { it.outPackets }, all.sumOf { it.outBytes }, seconds.map { it.outPackets }, seconds.map { it.outBytes }, r.types.filter { it.outbound }, top))
        if (r.typesFrom > since) put("types_from_ms", r.typesFrom / MS)
        put("series", series(seconds, points))
        put("gaps", gaps(r, since, gapMs * MS, top))
        put("keep_alive", keepAlives(r, since))
    }

    /** The full seconds from [since] on (the one running now left out), empty ones included. */
    fun seconds(r: Recording, since: Long): List<Second> {
        val first = (since / SECOND).toInt()
        val full = (r.elapsed / SECOND).toInt()
        val empty = Second(0, 0, 0, 0)
        return (first until full).map { r.seconds.getOrNull(it) ?: empty }
    }

    private fun direction(packets: Long, bytes: Long, perSecond: List<Long>, bytesPerSecond: List<Long>, types: List<Type>, top: Int) = buildJsonObject {
        put("packets", packets)
        put("bytes", bytes)
        put("per_s", spread(perSecond))
        put("bytes_per_s", spread(bytesPerSecond))
        val sorted = types.sortedByDescending { it.bytes }
        put("types", JsonArray(sorted.take(top).map { buildJsonObject { put("type", it.name); put("packets", it.packets); put("bytes", it.bytes) } }))
        if (sorted.size > top) put("types_more", sorted.size - top)
    }

    /** Average, lowest and highest of per-second [values]. */
    private fun spread(values: List<Long>) = buildJsonObject {
        put("avg", if (values.isEmpty()) 0.0 else round(values.average()))
        put("min", values.minOrNull() ?: 0)
        put("max", values.maxOrNull() ?: 0)
    }

    /** Per second each way, averaged over buckets of whole seconds so there are [points] values at most. */
    fun series(seconds: List<Second>, points: Int) = buildJsonObject {
        val bucket = maxOf(1, ceil(seconds.size / points.toDouble()).toInt())
        put("bucket_s", bucket)
        fun of(value: (Second) -> Long) = JsonArray(seconds.chunked(bucket).map { b -> JsonPrimitive(Math.round(b.sumOf(value) / b.size.toDouble())) })
        put("in_packets", of { it.inPackets })
        put("in_bytes", of { it.inBytes })
        put("out_packets", of { it.outPackets })
        put("out_bytes", of { it.outBytes })
    }

    /** Silences from the server of [min] or more that end after [since], the longest first; and the one going on. */
    fun gaps(r: Recording, since: Long, min: Long, top: Int) = buildJsonObject {
        val gaps = r.gaps.filter { it[1] >= min && it[0] + it[1] > since }
        put("over_ms", min / MS)
        put("count", gaps.size)
        put("total_ms", gaps.sumOf { it[1] } / MS)
        put("longest", JsonArray(gaps.sortedByDescending { it[1] }.take(top).map { g ->
            buildJsonObject { put("at_ms", g[0] / MS); put("ms", g[1] / MS) }
        }))
        val silent = r.elapsed - maxOf(r.lastReceived, since)
        if (silent >= min) put("silent_at_end_ms", silent / MS)
        if (r.gapsLeftOut > 0) put("not_kept", r.gapsLeftOut)
    }

    /**
     * The server's keep-alives received after [since]: how long the client took to answer, the time between them, and
     * the latency the server measured with them and sent for the tab list.
     */
    fun keepAlives(r: Recording, since: Long) = buildJsonObject {
        val received = r.keepAlives.filter { it[1] >= since }
        val answered = received.filter { it[2] >= 0 }
        put("received", received.size)
        put("answered", answered.size)
        if (answered.isNotEmpty()) put("answer_ms", spreadMs(answered.map { (it[2] - it[1]) / 1e6 }))
        if (received.size > 1) put("interval_ms", spreadMs(received.zipWithNext { a, b -> (b[1] - a[1]) / 1e6 }))
        val pings = r.pings.filter { it[0] >= since }.map { it[1].toDouble() }
        if (pings.isNotEmpty()) put("server_ping_ms", buildJsonObject {
            put("samples", pings.size)
            spreadMs(pings).forEach { (k, v) -> put(k, v) }
            put("last", pings.last())
        })
    }

    /** Lowest, median and highest of [ms]. */
    fun spreadMs(ms: List<Double>) = buildJsonObject {
        val sorted = ms.sorted()
        put("min", round(sorted.first()))
        put("median", round(median(sorted)))
        put("max", round(sorted.last()))
    }

    /** The middle of [sorted] values (the mean of the two middle ones for an even count). */
    fun median(sorted: List<Double>): Double {
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun round(v: Double) = Math.round(v * 10) / 10.0
}
