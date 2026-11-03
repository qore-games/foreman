package games.qore.foreman.client.packets

import games.qore.foreman.client.link.OutboundGate
import net.minecraft.network.protocol.Packet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Packets counted by type while a client_test step holds a [Tap] (`packets`): sent as the game sends them (before a
 * network profile holds them), received as they arrive, and which of those a test's drop ignored. Separate from the
 * [PacketRecorder] a player may be running; nothing is counted without a tap.
 */
object PacketTap {
    class Tap {
        val sent = ConcurrentHashMap<String, AtomicInteger>()
        val received = ConcurrentHashMap<String, AtomicInteger>()
        val dropped = ConcurrentHashMap<String, AtomicInteger>()
    }

    private val taps = CopyOnWriteArrayList<Tap>()

    /** Read by ConnectionMixin before anything else. */
    @JvmField
    @Volatile
    var on = false

    fun open(): Tap = Tap().also { taps += it; on = true }

    fun close(tap: Tap) {
        taps -= tap
        on = taps.isNotEmpty()
    }

    @JvmStatic
    fun sent(packet: Packet<*>) {
        // a held packet sent again on release was counted when the game sent it
        if (OutboundGate.replaying()) return
        val name = name(packet)
        taps.forEach { count(it.sent, name) }
    }

    @JvmStatic
    fun received(packet: Packet<*>, dropped: Boolean) {
        val name = name(packet)
        taps.forEach { count(it.received, name); if (dropped) count(it.dropped, name) }
    }

    /** As the packet viewer names them: `container_click`, `custom_payload/<channel>` */
    private fun name(packet: Packet<*>) = PacketInspector.channel(packet)?.let { "custom_payload/$it" } ?: PacketInspector.short(packet.type().id())

    private fun count(counts: ConcurrentHashMap<String, AtomicInteger>, name: String) = counts.computeIfAbsent(name) { AtomicInteger() }.incrementAndGet()
}
