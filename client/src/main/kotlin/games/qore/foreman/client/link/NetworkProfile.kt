package games.qore.foreman.client.link

/**
 * A network condition held around a dupe-test burst: added ping, or outgoing packets held and released together
 * ("blink"). They are what an attacker's lag switch does, and belong only on a server that allows testing. Foreman
 * ships `burst` and `ping_<n>` ([OutboundProfiles]); implement another against the client's connection and register it
 * with [NetworkProfiles.register], and `inventory_actions` holds it around the actions when a test names it.
 *
 * The desync dupes (two actions the server counts as one because the client lagged between them) need this; without a
 * profile registered, Foreman's tests still run the input bursts, just at normal timing.
 */
interface NetworkProfile {
    /** A short id a test names it by (`burst`, `ping_300`…). */
    val id: String

    /** Start applying the condition. */
    fun apply()

    /** Stop: the connection returns to normal and any held packets are sent. A delay's packets finish on their time. */
    fun release()
}

/** The registered network profiles: the stock ones ([OutboundProfiles.init]) plus any a dev build adds. */
object NetworkProfiles {
    private val byId = mutableMapOf<String, NetworkProfile>()

    /** Releases any held profile when leaving a server, so a test's held packets don't follow you. */
    fun init() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            byId.values.forEach { runCatching { it.release() } }
        }
    }

    fun register(profile: NetworkProfile) {
        byId[profile.id] = profile
    }

    fun get(id: String): NetworkProfile? = byId[id]

    val ids: Set<String> get() = byId.keys

    /** Holds [id] around [block]; without that profile, runs [block] at normal timing (the input burst still happens). */
    fun <T> around(id: String?, block: () -> T): T {
        val profile = id?.let(::get)
        profile?.apply()
        try {
            return block()
        } finally {
            profile?.release()
        }
    }
}
