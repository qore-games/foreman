package games.qore.foreman.server.admin

import io.netty.channel.Channel
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.bukkit.entity.Player
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The chat messages the server sends a player while something runs: a handler on their connection's pipeline, removed
 * after, reads the system chat packets (not the action bar). What a command run as them replied, which no API returns.
 * Reached through the server's own classes by reflection: null when they differ.
 */
object PlayerChat {
    class Capture(private val channel: Channel, private val name: String) : AutoCloseable {
        override fun close() {
            runCatching { if (channel.pipeline().get(name) != null) channel.pipeline().remove(name) }
        }
    }

    private val counter = AtomicInteger()
    private val methods = ConcurrentHashMap<Pair<Class<*>, String>, Method>()

    fun capture(player: Player, into: MutableList<String>): Capture? = try {
        val channel = Channels.of(player) ?: return null
        val name = "foreman_chat_${counter.incrementAndGet()}"
        // Packets written by the server start at the pipeline's tail: the last handler sees them first, still as packets.
        channel.pipeline().addLast(name, object : ChannelOutboundHandlerAdapter() {
            override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
                runCatching { texts(msg, into) }
                ctx.write(msg, promise)
            }
        })
        Capture(channel, name)
    } catch (e: Exception) {
        null
    }

    private fun call(target: Any, name: String): Any? =
        methods.getOrPut(target.javaClass to name) { target.javaClass.getMethod(name).also { it.trySetAccessible() } }.invoke(target)

    private fun texts(packet: Any, into: MutableList<String>) {
        when (packet.javaClass.simpleName) {
            "ClientboundSystemChatPacket" -> if (call(packet, "overlay") != true) call(packet, "content")?.let { into += ServerLog.plain(call(it, "getString").toString()) }
            "ClientboundBundlePacket" -> (call(packet, "subPackets") as? Iterable<*>)?.forEach { p -> p?.let { texts(it, into) } }
        }
    }
}
