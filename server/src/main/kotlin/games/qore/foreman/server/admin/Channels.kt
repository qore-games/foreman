package games.qore.foreman.server.admin

import io.netty.channel.Channel
import org.bukkit.entity.Player

/** A player's network connection, reached through the server's own classes by reflection: null when they differ. */
object Channels {
    fun of(player: Player): Channel? = runCatching {
        val handle = player.javaClass.getMethod("getHandle").invoke(player)
        val listener = handle.javaClass.getField("connection").get(handle)
        val connection = listener.javaClass.getField("connection").get(listener)
        connection.javaClass.getField("channel").get(connection) as Channel
    }.getOrNull()
}
