package games.qore.foreman.client.packets

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** packets_stats' numbers from a made-up recording. */
class PacketStatsTest {
    private val s = 1_000_000_000L
    private val ms = 1_000_000L

    /**
     * 10.5 s: 100 packets in a second (10 KB), 10 out (500 B), but second 4 is silent (a 1.2 s gap from 3.9 s); the last
     * half second has 50 in, 5 out.
     */
    private fun recording(
        gaps: List<LongArray> = listOf(longArrayOf(3_900 * ms, 1_200 * ms), longArrayOf(7_000 * ms, 300 * ms), longArrayOf(8_000 * ms, 150 * ms)),
        lastReceived: Long = 10_400 * ms,
    ) = PacketStats.Recording(
        server = "localhost", startedAt = 1_000L, recording = false, elapsed = 10_500 * ms,
        types = listOf(
            PacketStats.Type("move_entity_pos", false, 600, 6_000),
            PacketStats.Type("level_chunk_with_light", false, 10, 80_000),
            PacketStats.Type("keep_alive", false, 1, 9),
            PacketStats.Type("move_player_pos", true, 90, 4_500),
        ),
        typesFrom = 0,
        seconds = List(10) { if (it == 4) PacketStats.Second(0, 0, 10, 500) else PacketStats.Second(100, 10_000, 10, 500) } +
            PacketStats.Second(50, 5_000, 5, 250),
        gaps = gaps, gapsLeftOut = 0, lastReceived = lastReceived,
        keepAlives = listOf(longArrayOf(1, 1 * s, 1 * s + 2 * ms), longArrayOf(2, 6 * s, 6 * s + 40 * ms), longArrayOf(3, 9 * s, -1)),
        pings = listOf(longArrayOf(2 * s, 30), longArrayOf(8 * s, 50)),
    )

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject
    private fun JsonObject.num(key: String) = getValue(key).jsonPrimitive.double
    private fun JsonObject.long(key: String) = getValue(key).jsonPrimitive.long

    @Test
    fun totalsAndRates() {
        val json = PacketStats.json(recording(), since = 0, gapMs = 250)
        assertEquals(10_500L, json.long("ms"))
        val incoming = json.obj("in")
        // totals with the last half second, rates over the ten full ones
        assertEquals(950L, incoming.long("packets"))
        assertEquals(95_000L, incoming.long("bytes"))
        assertEquals(90.0, incoming.obj("per_s").num("avg"))
        assertEquals(0L, incoming.obj("per_s").long("min"))
        assertEquals(100L, incoming.obj("per_s").long("max"))
        // types by bytes, each way
        val types = incoming.getValue("types").jsonArray.map { it.jsonObject.getValue("type").jsonPrimitive.content }
        assertEquals(listOf("level_chunk_with_light", "move_entity_pos", "keep_alive"), types)
        assertEquals(105L, json.obj("out").long("packets"))
        assertEquals("move_player_pos", json.obj("out").getValue("types").jsonArray[0].jsonObject.getValue("type").jsonPrimitive.content)
    }

    @Test
    fun topLimitsTypes() {
        val incoming = PacketStats.json(recording(), since = 0, gapMs = 250, top = 1).obj("in")
        assertEquals(1, incoming.getValue("types").jsonArray.size)
        assertEquals(2, incoming.getValue("types_more").jsonPrimitive.int)
    }

    @Test
    fun seriesBucketsSeconds() {
        val seconds = List(300) { PacketStats.Second(it.toLong(), 0, 0, 0) }
        val series = PacketStats.series(seconds, 120)
        assertEquals(3, series.getValue("bucket_s").jsonPrimitive.int)
        val values = series.getValue("in_packets").jsonArray
        assertEquals(100, values.size)
        assertEquals(1L, values[0].jsonPrimitive.long)
        assertEquals(298L, values[99].jsonPrimitive.long)
    }

    @Test
    fun runningSecondLeftOut() {
        // 10.5 s: ten full seconds, the running one left out
        val r = recording()
        assertEquals(10, PacketStats.seconds(r, 0).size)
        assertEquals(5, PacketStats.seconds(r, 5_200 * ms).size)
    }

    @Test
    fun gapsOverThreshold() {
        val gaps = PacketStats.gaps(recording(), since = 0, min = 250 * ms, top = 15)
        assertEquals(2, gaps.getValue("count").jsonPrimitive.int)
        assertEquals(1_500L, gaps.long("total_ms"))
        val longest = gaps.getValue("longest").jsonArray[0].jsonObject
        assertEquals(3_900L, longest.long("at_ms"))
        assertEquals(1_200L, longest.long("ms"))
        assertFalse("silent_at_end_ms" in gaps)
        // a silence going on at the end
        val ended = PacketStats.gaps(recording(lastReceived = 9_000 * ms), since = 0, min = 250 * ms, top = 15)
        assertEquals(1_500L, ended.long("silent_at_end_ms"))
        // from 6 s on, only the one at 7 s
        val later = PacketStats.gaps(recording(), since = 6 * s, min = 100 * ms, top = 15)
        assertEquals(2, later.getValue("count").jsonPrimitive.int)
        assertEquals(listOf(7_000L, 8_000L), later.getValue("longest").jsonArray.map { it.jsonObject.long("at_ms") })
    }

    @Test
    fun keepAliveRoundTrips() {
        val keepAlive = PacketStats.keepAlives(recording(), since = 0)
        assertEquals(3, keepAlive.getValue("received").jsonPrimitive.int)
        assertEquals(2, keepAlive.getValue("answered").jsonPrimitive.int)
        val answer = keepAlive.obj("answer_ms")
        assertEquals(2.0, answer.num("min"))
        assertEquals(21.0, answer.num("median"))
        assertEquals(40.0, answer.num("max"))
        val interval = keepAlive.obj("interval_ms")
        assertEquals(3_000.0, interval.num("min"))
        assertEquals(5_000.0, interval.num("max"))
        val ping = keepAlive.obj("server_ping_ms")
        assertEquals(2, ping.getValue("samples").jsonPrimitive.int)
        assertEquals(50.0, ping.num("last"))
        // since: the later ones only
        assertEquals(2, PacketStats.keepAlives(recording(), since = 5 * s).getValue("received").jsonPrimitive.int)
    }

    @Test
    fun medianOfEvenAndOdd() {
        assertEquals(2.0, PacketStats.median(listOf(1.0, 2.0, 9.0)))
        assertEquals(2.5, PacketStats.median(listOf(1.0, 2.0, 3.0, 9.0)))
    }

    @Test
    fun compactJson() {
        val json = PacketStats.json(recording(), since = 0, gapMs = 250)
        assertTrue(json.getValue("series").jsonObject.getValue("in_packets") is JsonArray)
        assertTrue(json.toString().length < 2_000, json.toString())
    }
}
