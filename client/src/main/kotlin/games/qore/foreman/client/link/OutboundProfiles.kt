package games.qore.foreman.client.link

/**
 * The outbound network profiles Foreman ships, behind the [NetworkProfile] seam: `burst` holds every outbound packet
 * and sends them together on release (the client goes silent, then blinks), and `ping_<n>` adds n ms of latency to each
 * packet. Both pass keepalive, ping, login, disconnect, chat-session and configuration packets through untouched (see
 * [PacketFilter.exempt]), so a test is not kicked for lagging.
 *
 * They are what an attacker's lag switch does; a test names one as `network` on `inventory_actions`, which arms it
 * around the burst only. Register them once with [init] at client startup.
 */
object BurstProfile : NetworkProfile {
    override val id = "burst"

    override fun apply() = OutboundGate.hold(this)

    override fun release() = OutboundGate.release(this)
}

/** Adds [millis] of latency to outbound packets while applied; packets already on their way keep their time. */
class PingProfile(private val millis: Int) : NetworkProfile {
    override val id = "ping_$millis"

    override fun apply() = OutboundGate.delay(this, millis)

    override fun release() = OutboundGate.release(this)
}

/** The stock profiles, registered by [init] after [NetworkProfiles.init]. */
object OutboundProfiles {
    private val LATENCIES = listOf(100, 300, 500, 1000)

    val all: List<NetworkProfile> = listOf(BurstProfile) + LATENCIES.map(::PingProfile)

    fun init() = all.forEach(NetworkProfiles::register)
}
