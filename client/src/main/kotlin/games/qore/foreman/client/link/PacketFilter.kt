package games.qore.foreman.client.link

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import java.util.concurrent.ConcurrentHashMap

/**
 * Which inbound (server → client) packets a dupe test is dropping. This holds the selection and answers [shouldDrop];
 * it does **not** drop anything by itself — the inbound packet hook (a mixin on the client's receive path) calls
 * [shouldDrop] and cancels the packet when it's true. Dropping server packets causes the client/server desync a dupe
 * needs, the same vector an attacker uses, so arm it only against servers you run.
 *
 * Keepalive, ping, login, disconnect and chat-session packets are never dropped or held (doing so just gets you kicked).
 */
object PacketFilter {
    private val dropped = ConcurrentHashMap.newKeySet<String>()

    /** Type-name fragments that always pass: they keep the connection alive and speak for it. */
    private val NEVER = listOf("keepalive", "ping", "pong", "disconnect", "login", "kick", "chatsession", "configuration")

    /** Clears the selection when leaving a server, so a test's drops don't follow you. */
    fun init() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear() }
    }

    /** Drops inbound packets whose type name contains any of [types] (case-insensitive), e.g. `setslot`, `containerset`. */
    fun drop(types: Collection<String>) {
        dropped.clear()
        dropped.addAll(types.map { it.normalised() })
    }

    fun clear() = dropped.clear()

    val types: Set<String> get() = dropped

    /** True when [packet] must pass untouched, whatever a test armed: same list for drops and for holding sends. */
    @JvmStatic
    fun exempt(packet: Any): Boolean {
        val name = packet.name()
        return NEVER.any { name.contains(it) }
    }

    /** True when [packet]'s type is selected (and safe) to drop. The inbound hook cancels the packet when this is true. */
    @JvmStatic
    fun shouldDrop(packet: Any): Boolean {
        if (dropped.isEmpty()) return false
        val name = packet.name()
        if (NEVER.any { name.contains(it) }) return false
        return dropped.any { name.contains(it) }
    }

    /** The simple class name without separators, so `set_slot` and `SetSlot` match the same fragment. */
    private fun Any.name() = javaClass.simpleName.lowercase().replace("_", "")

    private fun String.normalised() = lowercase().replace("_", "")
}
