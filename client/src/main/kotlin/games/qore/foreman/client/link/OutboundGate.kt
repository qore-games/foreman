package games.qore.foreman.client.link

import io.netty.channel.Channel
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import java.util.concurrent.TimeUnit

/**
 * The outbound side of a [NetworkProfile]: while a profile is armed, the send hook offers every outbound packet here,
 * and the gate either parks it for a burst or schedules it that many milliseconds later. One profile holds the gate at
 * a time, so a release for a profile that is not armed — [NetworkProfiles] releases all of them on disconnect — does
 * nothing.
 *
 * [park] runs on the connection's thread and the profiles are armed from the MCP thread, so the burst queue is behind
 * one lock; replay is submitted to the connection's event loop, never written from the arming thread.
 */
object OutboundGate {
    private enum class Mode { OFF, HOLD, DELAY }

    private val lock = Any()

    /** Guards our own replay: a replayed packet must go through vanilla's send, not back into the gate. */
    private val replaying: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }

    /** Whether this thread is sending packets a profile held (they went through the outbound hooks once already). */
    fun replaying(): Boolean = replaying.get()

    /** Packets parked by a burst, in send order. Guarded by [lock]. */
    private val held = ArrayDeque<Packet<*>>()

    @Volatile
    private var mode = Mode.OFF
    private var owner: NetworkProfile? = null
    private var delay = 0
    private var connection: Connection? = null
    private var channel: Channel? = null

    /** Holds outbound packets until [release]: the client goes silent, then sends them together. */
    fun hold(profile: NetworkProfile) = arm(profile, Mode.HOLD, 0)

    /** Delays each outbound packet by [millis] while [profile] is applied. */
    fun delay(profile: NetworkProfile, millis: Int) = arm(profile, Mode.DELAY, millis)

    private fun arm(profile: NetworkProfile, next: Mode, millis: Int) {
        synchronized(lock) {
            // Arming twice would strand the first batch: drop it rather than send it out of order later.
            held.clear()
            owner = profile
            delay = millis
            mode = next
        }
    }

    /**
     * Called at the head of the client's send. True means the packet was taken and the vanilla send must be cancelled;
     * exempt packets, and everything while no profile is armed, return false and go out normally.
     */
    @JvmStatic
    fun park(connection: Connection, channel: Channel?, packet: Packet<*>): Boolean {
        if (mode == Mode.OFF || replaying.get()) return false
        if (channel == null || !channel.isOpen || PacketFilter.exempt(packet)) return false
        synchronized(lock) {
            if (mode == Mode.OFF) return false
            this.connection = connection
            this.channel = channel
            when (mode) {
                Mode.HOLD -> held.addLast(packet)
                Mode.DELAY -> channel.eventLoop().schedule(
                    { send(connection, listOf(packet)) }, delay.toLong(), TimeUnit.MILLISECONDS,
                )
                else -> return false
            }
            return true
        }
    }

    /**
     * Stops [profile]. A burst is submitted to the connection now, in order; a delay's already scheduled packets keep
     * their time, so the latency a test asked for is never fast-forwarded at the end of the burst.
     */
    fun release(profile: NetworkProfile) {
        synchronized(lock) {
            if (owner !== profile) return
            mode = Mode.OFF
            owner = null
            val burst = held.toList()
            held.clear()
            val connection = connection ?: return
            val channel = channel ?: return
            if (burst.isEmpty() || !channel.isOpen) return
            // Submitted under the lock: a send racing with the release either joins the burst or is queued after it.
            runCatching { channel.eventLoop().execute { send(connection, burst) } }
        }
    }

    private fun send(connection: Connection, packets: List<Packet<*>>) {
        if (!connection.isConnected) return
        replaying.set(true)
        try {
            packets.forEach { connection.send(it) }
        } finally {
            replaying.set(false)
        }
    }
}
