package games.qore.foreman.server.admin

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.ChannelDuplexHandler
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.ChannelInboundHandlerAdapter
import io.netty.channel.ChannelOutboundHandlerAdapter
import io.netty.channel.ChannelPromise
import org.bukkit.entity.Player
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.LongAdder

/**
 * What the server sends its players and gets from them while a recording runs: handlers on each recorded player's
 * connection, added by [attach] and removed by [stop], so nothing runs otherwise. Per player and packet type (the
 * client's packet viewer's names), packets and bytes before compression; per player, bytes on the wire; per second,
 * the bytes sent; and what each packet sent is about ([Subject]: an entity, a plugin channel, a team, an objective),
 * resolved into what it is when the summary is made. Reached through the server's classes by reflection.
 */
class PacketRecording(val seconds: Int, val only: Set<String>?) {
    class Counter {
        val packets = LongAdder()
        val bytes = LongAdder()

        fun add(size: Int) {
            packets.increment()
            bytes.add(size.toLong())
        }
    }

    /** What one player's connection carried. */
    class PlayerStats(val name: String) {
        val sent = ConcurrentHashMap<String, Counter>()
        val received = ConcurrentHashMap<String, Counter>()
        val wireSent = LongAdder()
        val wireReceived = LongAdder()
        /** Bytes before compression by subject (`entity:<id>`, `channel:<id>`…), then by packet type. */
        val subjects = ConcurrentHashMap<String, ConcurrentHashMap<String, Counter>>()
    }

    /** A packet on its way to the encoder: its type and what it's about, waiting for its encoded size. */
    private class Pending(val type: String, val subject: String?)

    val started = System.currentTimeMillis()
    @Volatile
    var stopped: Long? = null
        private set
    val players = ConcurrentHashMap<UUID, PlayerStats>()
    /** Bytes sent on the wire, every player, per second since the start. */
    val perSecond = AtomicLongArray(seconds + 2)
    /** Players whose connection the handlers couldn't be put on (another server's pipeline), with why. */
    val failed = ConcurrentHashMap<String, String>()
    private val channels = ConcurrentHashMap<UUID, Channel>()

    fun wants(player: Player) = only == null || player.name.lowercase() in only

    /** Puts the handlers on [player]'s connection (from any thread: the pipeline is changed on its event loop). */
    fun attach(player: Player) {
        if (stopped != null || channels.containsKey(player.uniqueId) || !wants(player)) return
        val channel = Channels.of(player) ?: return run { failed[player.name] = "its connection isn't reachable" }
        val stats = players.getOrPut(player.uniqueId) { PlayerStats(player.name) }
        channels[player.uniqueId] = channel
        channel.eventLoop().execute {
            val pipeline = channel.pipeline()
            val names = pipeline.names()
            if ("encoder" !in names || "decoder" !in names) {
                failed[player.name] = "no encoder/decoder in its pipeline (${names.joinToString()})"
                return@execute
            }
            val queue = ArrayDeque<Pending>()
            val sizes = ArrayDeque<Int>()
            runCatching {
                pipeline.addFirst(WIRE, Wire(stats))
                pipeline.addAfter("encoder", SENT, Sent(queue))
                pipeline.addBefore("encoder", SENT_SIZE, SentSize(stats, queue))
                pipeline.addBefore("decoder", RECEIVED_SIZE, ReceivedSize(sizes))
                pipeline.addAfter("decoder", RECEIVED, Received(stats, sizes))
            }.onFailure { failed[player.name] = "${it.javaClass.simpleName}: ${it.message}"; remove(pipeline) }
        }
    }

    /** Takes the handlers off every connection: the counts stay for the summary. */
    fun stop() {
        if (stopped != null) return
        stopped = System.currentTimeMillis()
        channels.values.forEach { channel -> channel.eventLoop().execute { remove(channel.pipeline()) } }
        channels.clear()
    }

    val elapsedMs get() = (stopped ?: System.currentTimeMillis()) - started

    private fun remove(pipeline: io.netty.channel.ChannelPipeline) {
        listOf(WIRE, SENT, SENT_SIZE, RECEIVED_SIZE, RECEIVED).forEach { name -> runCatching { if (pipeline.get(name) != null) pipeline.remove(name) } }
    }

    /** Bytes on the wire, both ways: the first handler, after compression and framing. */
    private inner class Wire(private val stats: PlayerStats) : ChannelDuplexHandler() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) stats.wireReceived.add(msg.readableBytes().toLong())
            ctx.fireChannelRead(msg)
        }

        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (msg is ByteBuf) {
                stats.wireSent.add(msg.readableBytes().toLong())
                val second = ((System.currentTimeMillis() - started) / 1000).toInt().coerceIn(0, perSecond.length() - 1)
                perSecond.addAndGet(second, msg.readableBytes().toLong())
            }
            ctx.write(msg, promise)
        }
    }

    /** Packets on their way to the encoder (bundles already split): their type and subject, in order. */
    private class Sent(private val queue: ArrayDeque<Pending>) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            if (queue.size > MAX_QUEUE) queue.clear()
            queue.add(if (msg is ByteBuf) Pending("raw", null) else Pending(Packets.type(msg), runCatching { Packets.subject(msg) }.getOrNull()))
            ctx.write(msg, promise)
        }
    }

    /** What the encoder made of each, in the same order: its size before compression. */
    private class SentSize(private val stats: PlayerStats, private val queue: ArrayDeque<Pending>) : ChannelOutboundHandlerAdapter() {
        override fun write(ctx: ChannelHandlerContext, msg: Any, promise: ChannelPromise) {
            val pending = queue.poll()
            if (pending != null && msg is ByteBuf) {
                val size = msg.readableBytes()
                stats.sent.getOrPut(pending.type) { Counter() }.add(size)
                pending.subject?.let { s ->
                    if (stats.subjects.size < MAX_SUBJECTS || stats.subjects.containsKey(s)) stats.subjects.getOrPut(s) { ConcurrentHashMap() }.getOrPut(pending.type) { Counter() }.add(size)
                }
            }
            ctx.write(msg, promise)
        }
    }

    /** Frames coming to the decoder, decompressed: their sizes, in order. */
    private class ReceivedSize(private val sizes: ArrayDeque<Int>) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            if (msg is ByteBuf) {
                if (sizes.size > MAX_QUEUE) sizes.clear()
                sizes.add(msg.readableBytes())
            }
            ctx.fireChannelRead(msg)
        }
    }

    /** The packets the decoder made of them: their type. */
    private class Received(private val stats: PlayerStats, private val sizes: ArrayDeque<Int>) : ChannelInboundHandlerAdapter() {
        override fun channelRead(ctx: ChannelHandlerContext, msg: Any) {
            stats.received.getOrPut(Packets.type(msg)) { Counter() }.add(sizes.poll() ?: 0)
            ctx.fireChannelRead(msg)
        }
    }

    companion object {
        private const val WIRE = "foreman_packets_wire"
        private const val SENT = "foreman_packets_sent"
        private const val SENT_SIZE = "foreman_packets_sent_size"
        private const val RECEIVED = "foreman_packets_received"
        private const val RECEIVED_SIZE = "foreman_packets_received_size"
        private const val MAX_QUEUE = 10_000
        /** Distinct subjects kept per player: past it, only the types count. */
        private const val MAX_SUBJECTS = 20_000
    }
}

/**
 * Packets read by reflection: their type's id (`set_entity_data`, as the client names them) and what they're about,
 * the way to read either found once per packet class.
 */
object Packets {
    private val types = ConcurrentHashMap<Class<*>, String>()
    private val subjects = ConcurrentHashMap<Class<*>, (Any) -> String?>()
    private val NONE: (Any) -> String? = { null }

    fun type(packet: Any): String = types.getOrPut(packet.javaClass) {
        runCatching {
            val type = packet.javaClass.getMethod("type").invoke(packet)
            type.javaClass.getMethod("id").invoke(type).toString().removePrefix("minecraft:")
        }.getOrElse { packet.javaClass.simpleName }
    }

    /** `entity:<id>`, `channel:<id>`, `team:<name>`, `objective:<name>`, or null. */
    fun subject(packet: Any): String? = subjects.getOrPut(packet.javaClass) { reader(packet.javaClass) }(packet)

    private fun reader(c: Class<*>): (Any) -> String? {
        val name = c.simpleName
        return when {
            name == "ClientboundCustomPayloadPacket" -> method(c, "payload")?.let { m ->
                { p -> m.invoke(p)?.let { payload -> runCatching { "channel:" + payload.javaClass.getMethod("type").invoke(payload).let { t -> t.javaClass.getMethod("id").invoke(t) } }.getOrNull() } }
            } ?: NONE
            name == "ClientboundSetPlayerTeamPacket" -> method(c, "getName")?.let { m -> { p -> "team:${m.invoke(p)}" } } ?: NONE
            name == "ClientboundSetObjectivePacket" -> method(c, "getObjectiveName")?.let { m -> { p -> "objective:${m.invoke(p)}" } } ?: NONE
            name == "ClientboundSetScorePacket" || name == "ClientboundResetScorePacket" ->
                method(c, "objectiveName")?.let { m -> { p -> m.invoke(p)?.let { "objective:$it" } } } ?: NONE
            else -> entityId(c)?.let { read -> { p -> read(p)?.let { "entity:$it" } } } ?: NONE
        }
    }

    /** How to read an entity packet's entity id: an `id`/`entityId` accessor or field. */
    private fun entityId(c: Class<*>): ((Any) -> Int?)? {
        // The full name: moves are nested classes (ClientboundMoveEntityPacket.Pos).
        if (!c.name.contains("Entity") && c.simpleName !in ENTITY_PACKETS) return null
        for (n in listOf("id", "entityId", "getId", "getEntityId", "getEntity", "getVehicle")) {
            val m = method(c, n)?.takeIf { it.returnType == Int::class.javaPrimitiveType } ?: continue
            return { p -> m.invoke(p) as Int }
        }
        val field = generateSequence<Class<*>>(c) { it.superclass }.flatMap { it.declaredFields.asSequence() }
            .firstOrNull { (it.name == "entityId" || it.name == "id") && it.type == Int::class.javaPrimitiveType }
            ?.takeIf(Field::trySetAccessible) ?: return null
        return { p -> field.getInt(p) }
    }

    private fun method(c: Class<*>, name: String): Method? =
        runCatching { c.getMethod(name) }.getOrNull()?.takeIf { it.parameterCount == 0 && it.trySetAccessible() }

    /** Entity packets whose name doesn't say so. */
    private val ENTITY_PACKETS = setOf("ClientboundSetEquipmentPacket", "ClientboundAnimatePacket", "ClientboundRotateHeadPacket", "ClientboundSetPassengersPacket", "ClientboundUpdateAttributesPacket", "ClientboundHurtAnimationPacket", "ClientboundDamageEventPacket", "ClientboundTakeItemEntityPacket")
}
