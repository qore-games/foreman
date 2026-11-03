package games.qore.foreman.client.mcp

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.NetworkProfiles
import games.qore.foreman.client.link.PacketFilter
import games.qore.foreman.client.packets.PacketTap
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a client_test step (or a `parallel` or `run` of them) is run under, and checked against after: a network
 * profile held around it (`network`: burst, ping_<n>), inbound packets ignored (`drop`), and the packets counted
 * meanwhile (`packets`). `network` and `drop` change what the server sees, so like inventory_actions' they need the
 * server's opt-in: Foreman on it and `foreman.test` for the player.
 */
internal class TestConditions private constructor(step: JsonObject) {
    private val network = step["network"].str()
    private val drop = step["drop"].arr()?.mapNotNull { it.str() }.orEmpty()
    private val packets: JsonObject? = step["packets"].let { it.obj() ?: if (it.bool() == true) JsonObject(emptyMap()) else null }
    /** The server answers a round trip later: drops and the tap stay on that long after */
    private val afterMs = (step["after_ms"].int() ?: 400).coerceIn(0, 10_000).toLong()

    /** The packets' verdict: why they fail the step (null when they hold), the counts checked, and the traffic. */
    class Packets(val failure: String?, val note: String, val traffic: String)

    init {
        if (network != null && NetworkProfiles.get(network) == null) {
            throw ToolFailure("No network profile '$network': ${NetworkProfiles.ids.joinToString().ifEmpty { "none" }}")
        }
        if ((network != null || drop.isNotEmpty()) && !Bridge.testing) {
            throw ToolFailure("`network` and `drop` need a server running Foreman that gives you foreman.test")
        }
    }

    /** Runs [block] under the conditions; the packets' verdict when `packets` asked for one. */
    fun <T> around(block: () -> T): Pair<T, Packets?> {
        val tap = packets?.let { PacketTap.open() }
        if (drop.isNotEmpty()) PacketFilter.drop(drop)
        try {
            val value = NetworkProfiles.around(network) { block() }
            if (drop.isNotEmpty() || tap != null) Thread.sleep(afterMs)
            return value to tap?.let { verdict(it) }
        } finally {
            if (drop.isNotEmpty()) PacketFilter.clear()
            tap?.let { PacketTap.close(it) }
        }
    }

    /** `sent` / `received`: types (name fragments) there at least once, or {type, min, max}; `not_sent` / `not_received`: none. */
    private fun verdict(tap: PacketTap.Tap): Packets {
        val checked = mutableListOf<String>()
        var failure: String? = null
        for ((key, counts, none) in listOf(
            Triple("sent", tap.sent, false), Triple("received", tap.received, false),
            Triple("not_sent", tap.sent, true), Triple("not_received", tap.received, true),
        )) for (spec in packets!![key].arr().orEmpty()) {
            val type = spec.str() ?: spec["type"].str() ?: throw ToolFailure("packets.$key: a type, or {type, min, max}")
            val min = if (none) 0 else spec["min"].int() ?: 1
            val max = if (none) 0 else spec["max"].int()
            val n = count(counts, type)
            val direction = key.removePrefix("not_")
            checked += "$direction $type ×$n"
            if (failure == null && (n < min || (max != null && n > max))) {
                failure = "$direction $type ×$n, wanted " + when {
                    max == 0 -> "none"
                    max == null -> "at least $min"
                    else -> "$min..$max"
                }
            }
        }
        val dropped = tap.dropped.entries.sortedByDescending { it.value.get() }.joinToString { "${it.key} ×${it.value}" }
        val note = "↑${total(tap.sent)} ↓${total(tap.received)}" + (if (checked.isEmpty()) "" else ": " + checked.joinToString()) +
            (if (dropped.isEmpty()) "" else "; dropped $dropped")
        val traffic = "sent: ${top(tap.sent)}\nreceived: ${top(tap.received)}"
        return Packets(failure, note, traffic)
    }

    companion object {
        /** The conditions [step] asks for, null when none. Throws when it asks for what isn't there or allowed. */
        fun of(step: JsonObject): TestConditions? =
            if (step["network"] == null && step["drop"] == null && step["packets"] == null) null else TestConditions(step)

        private fun count(counts: Map<String, AtomicInteger>, fragment: String): Int {
            val f = fragment.lowercase().replace("_", "")
            return counts.entries.sumOf { (name, n) -> if (name.lowercase().replace("_", "").contains(f)) n.get() else 0 }
        }

        private fun total(counts: Map<String, AtomicInteger>) = counts.values.sumOf { it.get() }

        private fun top(counts: Map<String, AtomicInteger>) =
            counts.entries.sortedByDescending { it.value.get() }.take(12).joinToString { "${it.key} ×${it.value}" }.ifEmpty { "nothing" }
    }
}
