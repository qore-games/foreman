package games.qore.foreman.client.packets

import games.qore.foreman.client.config.Config
import io.netty.buffer.ByteBuf
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.local.LocalChannel
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap
import net.minecraft.client.Minecraft
import net.minecraft.network.ConnectionProtocol
import net.minecraft.network.HandlerNames
import net.minecraft.network.PacketListener
import net.minecraft.network.ProtocolInfo
import net.minecraft.network.VarInt
import net.minecraft.network.protocol.BundlePacket
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.PacketFlow
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.world.entity.EntityType

/**
 * Records the game connection's packets, both ways, while it is on: for each packet its type, size decoded and on the
 * wire (after compression), whether it repeats one just seen, and what it is about (an entity, a chunk) and says; and
 * how long the client thread took to handle each type. Totals cover the whole recording; the last
 * [games.qore.foreman.client.config.PacketSettings.capacity] packets are kept one by one.
 *
 * The mixins (`PacketDecoderMixin`…) call in from Netty's thread and the client thread. Off, they read [recording]
 * and nothing else runs.
 */
object PacketRecorder {
    @JvmField
    @Volatile
    var recording = false

    /** A packet identical to one of the same type received this recently is a repeat. */
    private const val REPEAT_WINDOW = 2_000_000_000L
    private const val MAX_SUBJECTS = 20_000
    private const val SLOW_KEPT = 30
    /** Handlings faster than this are never listed among the slowest. */
    private const val SLOW_MIN = 1_000_000L

    /** Per second: packets and bytes each way, client-thread time, the longest frame spent on packets. */
    const val IN_COUNT = 0
    const val IN_WIRE = 1
    const val IN_PAYLOAD = 2
    const val OUT_COUNT = 3
    const val OUT_WIRE = 4
    const val HANDLE = 5
    const val WORST_FRAME = 6
    private const val SECOND_FIELDS = 7

    class TypeTotals(
        val name: String, val outbound: Boolean, val count: Long, val payload: Long, val wire: Long, val maxWire: Int,
        val repeats: Long, val repeatWire: Long, val handled: Long, val handleNanos: Long, val maxHandleNanos: Long,
    )

    /** An entity's or chunk's packets, with the types that make them (name, count, wire bytes), most bytes first. */
    class SubjectTotals(val subject: Long, val count: Long, val wire: Long, val types: List<Triple<String, Long, Long>>)

    class Event(
        val seq: Long, val t: Long, val outbound: Boolean, val type: String, val payload: Int, val wire: Int,
        val repeat: Boolean, val subject: Long, val detail: String?,
    )

    class Slow(val t: Long, val type: String, val nanos: Long, val subject: Long, val detail: String)

    /** The longest frame spent handling packets: when, how long, how many. */
    class Frame(val t: Long, val nanos: Long, val packets: Int)

    private class TypeStat(val name: String, val outbound: Boolean) {
        var count = 0L
        var payload = 0L
        var wire = 0L
        var maxWire = 0
        var repeats = 0L
        var repeatWire = 0L
        var handled = 0L
        var handleNanos = 0L
        var maxHandleNanos = 0L
        /** Recent contents (hash) → when last seen. */
        val recent = Long2LongOpenHashMap()

        fun totals() = TypeTotals(name, outbound, count, payload, wire, maxWire, repeats, repeatWire, handled, handleNanos, maxHandleNanos)

        fun isRepeat(hash: Long, now: Long): Boolean {
            val last = recent.put(hash, now)
            if (recent.size > 2048) {
                recent.values.removeIf { now - it > REPEAT_WINDOW }
                if (recent.size > 2048) recent.clear()
            }
            return last != 0L && now - last <= REPEAT_WINDOW
        }
    }

    private class SubjectStat {
        var count = 0L
        var wire = 0L
        /** Type index → [count, wire]. */
        val types = Int2ObjectOpenHashMap<LongArray>()

        fun add(type: Int, wire: Int) {
            count++
            this.wire += wire
            val t = types.get(type) ?: LongArray(2).also { types.put(type, it) }
            t[0]++
            t[1] += wire.toLong()
        }
    }

    // ---- State (guarded by this object) -----------------------------------------------------------------------------

    private var startNanos = 0L
    private var stopNanos = 0L
    private var stopAt = 0L
    var startedAt = 0L
        private set
    var server = ""
        private set
    private var contents = true

    private val types = ArrayList<TypeStat>()
    /** Per direction and protocol: packet type (or plugin channel) → index in [types]. */
    private val typeIndexes = Array(2 * ConnectionProtocol.values().size) { HashMap<Any, Int>() }
    private val entities = Int2ObjectOpenHashMap<SubjectStat>()
    private val chunks = Long2ObjectOpenHashMap<SubjectStat>()
    /** Entity id → type, from the spawn packets seen (the types themselves: a long recording sees many spawns). */
    private val entityTypes = Int2ObjectOpenHashMap<EntityType<*>>()
    private val seconds = ArrayList<LongArray>()
    private val slowest = ArrayList<Slow>()
    private var worstFrame: Frame? = null

    // The kept packets, a ring of parallel arrays.
    private var capacity = 0
    private var times = LongArray(0)
    private var typeOf = IntArray(0)
    private var payloads = IntArray(0)
    private var wires = IntArray(0)
    private var flags = ByteArray(0)
    private var subjects = LongArray(0)
    private var details = arrayOfNulls<String>(0)
    /** Packets recorded since the start (the ring keeps the last [capacity]). */
    var total = 0L
        private set

    val hasData get() = startNanos != 0L

    /**
     * Starts a new recording (the previous one is dropped); it stops by itself after [seconds] when given. [contents]:
     * keep each packet's fields (the player's setting by default).
     */
    fun start(seconds: Int = 0, contents: Boolean? = null) {
        synchronized(this) { begin(seconds, contents) }
    }

    private fun begin(seconds: Int, keepContents: Boolean?) {
        reset()
        val settings = Config.settings.packets
        capacity = settings.capacity.coerceIn(1_000, 2_000_000)
        times = LongArray(capacity)
        typeOf = IntArray(capacity)
        payloads = IntArray(capacity)
        wires = IntArray(capacity)
        flags = ByteArray(capacity)
        subjects = LongArray(capacity)
        details = arrayOfNulls(capacity)
        contents = keepContents ?: settings.contents
        startNanos = System.nanoTime()
        startedAt = System.currentTimeMillis()
        server = Minecraft.getInstance().currentServer?.ip ?: "singleplayer"
        stopAt = if (seconds > 0) startNanos + seconds * 1_000_000_000L else 0L
        recording = true
    }

    /** Stops; what was recorded stays until the next start or [clear]. */
    fun stop() {
        synchronized(this) {
            if (!recording) return
            recording = false
            stopNanos = System.nanoTime()
            stopAt = 0L
        }
    }

    fun clear() {
        synchronized(this) {
            recording = false
            reset()
            capacity = 0
            times = LongArray(0); typeOf = IntArray(0); payloads = IntArray(0); wires = IntArray(0)
            flags = ByteArray(0); subjects = LongArray(0); details = arrayOfNulls(0)
        }
    }

    private fun reset() {
        startNanos = 0L; stopNanos = 0L; stopAt = 0L; total = 0L
        types.clear(); typeIndexes.forEach { it.clear() }
        entities.clear(); chunks.clear(); entityTypes.clear(); seconds.clear(); slowest.clear()
        worstFrame = null; slowFloor = SLOW_MIN
    }

    /** Stops a timed recording once its time is up (each client tick); true when it just stopped. */
    fun tick(): Boolean {
        if (!recording || stopAt == 0L || System.nanoTime() < stopAt) return false
        stop()
        return true
    }

    fun elapsedNanos(): Long = synchronized(this) {
        if (startNanos == 0L) 0L else (if (recording) System.nanoTime() else stopNanos) - startNanos
    }

    // ---- Capture ----------------------------------------------------------------------------------------------------

    /** The frame the decompressor just read on this thread: its size on the wire and the buffer it inflated to. */
    private class WireFrame {
        var size = 0
        var buf: Any? = null
    }

    /** A sent packet waiting for the compressor that runs right after its encoder, on the same thread. */
    private class Sent {
        var packet: Packet<*>? = null
        var protocol = ConnectionProtocol.PLAY
        var payload = 0
        var hash = 0L
    }

    private val frames = ThreadLocal.withInitial { WireFrame() }
    private val sent = ThreadLocal.withInitial { Sent() }

    @JvmStatic
    fun frameRead(size: Int) {
        val frame = frames.get()
        frame.size = size
        frame.buf = null
    }

    @JvmStatic
    fun frameInflated(buf: Any) {
        frames.get().buf = buf
    }

    /** A packet the client decoded ([payload] bytes); its wire size is the frame the decompressor read for [input]. */
    @JvmStatic
    fun decoded(ctx: ChannelHandlerContext, input: ByteBuf, protocol: ProtocolInfo<*>, packet: Packet<*>, payload: Int) {
        if (protocol.flow() != PacketFlow.CLIENTBOUND || protocol.id() == ConnectionProtocol.STATUS) return
        val frame = frames.get()
        // Not the frame just inflated: frames held back while the protocol switched (a few packets at join), estimated.
        val wire = if (frame.buf === input) frame.size + VarInt.getByteSize(frame.size) else framed(ctx, payload)
        frame.buf = null
        record(false, protocol.id(), packet, payload, wire, hash(input, input.writerIndex() - payload, payload))
    }

    /** A packet the client encoded; with compression on, it is recorded once compressed ([compressed]). */
    @JvmStatic
    fun encoded(ctx: ChannelHandlerContext, output: ByteBuf, protocol: ProtocolInfo<*>, packet: Packet<*>) {
        val id = protocol.id()
        if (protocol.flow() != PacketFlow.SERVERBOUND || id == ConnectionProtocol.HANDSHAKING || id == ConnectionProtocol.STATUS) return
        val payload = output.readableBytes()
        val hash = hash(output, output.readerIndex(), payload)
        if (ctx.pipeline().get(HandlerNames.COMPRESS) == null) return record(true, id, packet, payload, framed(ctx, payload), hash)
        val waiting = sent.get()
        waiting.packet = packet
        waiting.protocol = id
        waiting.payload = payload
        waiting.hash = hash
    }

    @JvmStatic
    fun compressed(size: Int) {
        val waiting = sent.get()
        val packet = waiting.packet ?: return
        waiting.packet = null
        record(true, waiting.protocol, packet, waiting.payload, size + VarInt.getByteSize(size), waiting.hash)
    }

    /** Uncompressed: the packet and its length prefix (nothing on an in-memory connection). */
    private fun framed(ctx: ChannelHandlerContext, payload: Int) = if (ctx.channel() is LocalChannel) payload else payload + VarInt.getByteSize(payload)

    private fun hash(buf: ByteBuf, from: Int, length: Int): Long {
        var h = -0x340d631b7bdddcdbL
        var i = from
        val end = from + length
        while (i + 8 <= end) {
            h = (h xor buf.getLong(i)) * 0x100000001b3L
            h = h xor (h ushr 29)
            i += 8
        }
        while (i < end) {
            h = (h xor buf.getByte(i).toLong()) * 0x100000001b3L
            i++
        }
        return h xor length.toLong()
    }

    private fun record(outbound: Boolean, protocol: ConnectionProtocol, packet: Packet<*>, payload: Int, wire: Int, hash: Long) {
        val now = System.nanoTime()
        val subject = if (outbound) Subject.NONE else PacketInspector.subject(packet)
        val detail = if (contents) PacketInspector.describe(packet) else null
        val spawned = packet as? ClientboundAddEntityPacket
        synchronized(this) {
            if (!recording) return
            val t = now - startNanos
            val type = typeIndex(outbound, protocol, packet)
            val stat = types[type]
            stat.count++
            stat.payload += payload
            stat.wire += wire
            if (wire > stat.maxWire) stat.maxWire = wire
            val repeat = stat.isRepeat(hash, now)
            if (repeat) {
                stat.repeats++
                stat.repeatWire += wire
            }
            val second = second(t)
            if (outbound) {
                second[OUT_COUNT]++
                second[OUT_WIRE] += wire.toLong()
            } else {
                second[IN_COUNT]++
                second[IN_WIRE] += wire.toLong()
                second[IN_PAYLOAD] += payload.toLong()
            }
            if (subject != Subject.NONE) subjectStat(subject)?.add(type, wire)
            if (spawned != null) entityTypes.put(spawned.id, spawned.type)

            val i = (total % capacity).toInt()
            times[i] = t
            typeOf[i] = type
            payloads[i] = payload
            wires[i] = wire
            flags[i] = ((if (outbound) 1 else 0) or (if (repeat) 2 else 0)).toByte()
            subjects[i] = subject
            details[i] = detail
            total++
        }
    }

    private fun typeIndex(outbound: Boolean, protocol: ConnectionProtocol, packet: Packet<*>): Int {
        val index = typeIndexes[(if (outbound) ConnectionProtocol.values().size else 0) + protocol.ordinal]
        val key: Any = PacketInspector.channel(packet) ?: packet.type()
        return index.getOrPut(key) {
            types += TypeStat(PacketInspector.typeName(protocol, packet), outbound)
            types.size - 1
        }
    }

    private fun second(t: Long): LongArray {
        val i = (t / 1_000_000_000L).toInt().coerceAtLeast(0)
        while (seconds.size <= i) seconds += LongArray(SECOND_FIELDS)
        return seconds[i]
    }

    private fun subjectStat(subject: Long): SubjectStat? = if (Subject.isEntity(subject)) {
        val id = Subject.entityId(subject)
        entities.get(id) ?: if (entities.size < MAX_SUBJECTS) SubjectStat().also { entities.put(id, it) } else null
    } else {
        chunks.get(subject) ?: if (chunks.size < MAX_SUBJECTS) SubjectStat().also { chunks.put(subject, it) } else null
    }

    // ---- Client thread ----------------------------------------------------------------------------------------------

    private var handleStart = 0L
    private var frameStart = 0L
    private var framePackets = 0
    /** The slowest kept handling (or [SLOW_MIN]): faster ones are not described. */
    @Volatile
    private var slowFloor = SLOW_MIN

    /** The client thread starts handling the packets queued since the last frame. */
    @JvmStatic
    fun frameStart() {
        frameStart = System.nanoTime()
        framePackets = 0
    }

    @JvmStatic
    fun frameEnd() {
        if (frameStart == 0L) return
        val end = System.nanoTime()
        val nanos = end - frameStart
        frameStart = 0L
        if (framePackets == 0) return
        synchronized(this) {
            if (!recording) return
            val t = end - startNanos
            val second = second(t)
            if (nanos > second[WORST_FRAME]) second[WORST_FRAME] = nanos
            if (nanos > (worstFrame?.nanos ?: 0L)) worstFrame = Frame(t - nanos, nanos, framePackets)
        }
    }

    // The integrated server's thread handles its packets here too: only the client's listeners are timed.

    @JvmStatic
    fun handleStart(listener: PacketListener, packet: Packet<*>) {
        if (listener.flow() != PacketFlow.CLIENTBOUND) return
        // A bundle is timed packet by packet (ClientPacketListenerMixin).
        handleStart = if (packet !is BundlePacket<*>) System.nanoTime() else 0L
    }

    @JvmStatic
    fun handleEnd(listener: PacketListener, packet: Packet<*>) {
        if (listener.flow() != PacketFlow.CLIENTBOUND || handleStart == 0L) return
        val nanos = System.nanoTime() - handleStart
        handleStart = 0L
        handled(listener.protocol(), packet, nanos)
    }

    /** The client thread handled [packet] in [nanos]. */
    @JvmStatic
    fun handled(protocol: ConnectionProtocol, packet: Packet<*>, nanos: Long) {
        val now = System.nanoTime()
        framePackets++
        val slow = nanos >= slowFloor
        val subject = if (slow) PacketInspector.subject(packet) else Subject.NONE
        val detail = if (slow) PacketInspector.describe(packet) else null
        synchronized(this) {
            if (!recording) return
            val type = typeIndex(false, protocol, packet)
            val stat = types[type]
            stat.handled++
            stat.handleNanos += nanos
            if (nanos > stat.maxHandleNanos) stat.maxHandleNanos = nanos
            second(now - startNanos)[HANDLE] += nanos
            if (detail != null) {
                slowest += Slow(now - startNanos - nanos, stat.name, nanos, subject, detail)
                slowest.sortByDescending { it.nanos }
                if (slowest.size > SLOW_KEPT) slowest.removeLast()
                if (slowest.size == SLOW_KEPT) slowFloor = slowest.last().nanos
            }
        }
    }

    // ---- Reading ----------------------------------------------------------------------------------------------------

    fun types(): List<TypeTotals> = synchronized(this) { types.map { it.totals() } }

    fun seconds(): List<LongArray> = synchronized(this) { seconds.map { it.clone() } }

    /** The last full second (for the HUD), or null. */
    fun lastSecond(): LongArray? = synchronized(this) {
        if (!recording) null else seconds.getOrNull(((System.nanoTime() - startNanos) / 1_000_000_000L).toInt() - 1)?.clone()
    }

    fun slowest(): List<Slow> = synchronized(this) { slowest.toList() }

    fun worstFrame(): Frame? = synchronized(this) { worstFrame }

    /** Packets kept one by one (the last [capacity]). */
    fun kept(): Long = synchronized(this) { minOf(total, capacity.toLong()) }

    /** The type of an entity whose spawn was recorded (`zombie`, `mymod:thing`). */
    fun entityType(id: Int): String? = synchronized(this) { entityTypes.get(id) }?.let { PacketInspector.short(EntityType.getKey(it)) }

    /** The entities with the most bytes. */
    fun entities(limit: Int): List<SubjectTotals> = synchronized(this) {
        top(entities.int2ObjectEntrySet().asSequence().map { Subject.entity(it.intKey) to it.value }, limit)
    }

    /** The chunks with the most bytes. */
    fun chunks(limit: Int): List<SubjectTotals> = synchronized(this) {
        top(chunks.long2ObjectEntrySet().asSequence().map { it.longKey to it.value }, limit)
    }

    /** The [limit] subjects with the most bytes, without sorting them all (the capture waits meanwhile). */
    private fun top(all: Sequence<Pair<Long, SubjectStat>>, limit: Int): List<SubjectTotals> {
        val heap = java.util.PriorityQueue<Pair<Long, SubjectStat>>(compareBy { it.second.wire })
        for (entry in all) {
            if (heap.size < limit) heap += entry
            else if (entry.second.wire > heap.peek().second.wire) { heap.poll(); heap += entry }
        }
        return heap.sortedByDescending { it.second.wire }.map { (subject, stat) -> subjectTotals(subject, stat) }
    }

    private fun subjectTotals(subject: Long, stat: SubjectStat) = SubjectTotals(subject, stat.count, stat.wire,
        stat.types.int2ObjectEntrySet().map { Triple(types[it.intKey].name, it.value[0], it.value[1]) }.sortedByDescending { it.third })

    /**
     * The kept packets of the recording as it is now, oldest first (or newest first), read a batch at a time so the
     * capture never waits long.
     */
    fun events(newestFirst: Boolean = false): Sequence<Event> = sequence {
        val end = synchronized(this@PacketRecorder) { total }
        var cursor = if (newestFirst) end else 0L
        while (true) {
            val batch = synchronized(this@PacketRecorder) {
                // Packets overwritten since are skipped; a new recording ends the read.
                val top = minOf(if (newestFirst) cursor else end, total)
                val oldest = total - minOf(total, capacity.toLong())
                val from = if (newestFirst) maxOf(top - 4096, oldest) else maxOf(cursor, oldest)
                val to = if (newestFirst) top else minOf(from + 4096, top)
                val list = when {
                    from >= to -> emptyList()
                    newestFirst -> (to - 1 downTo from).map { event(it) }
                    else -> (from until to).map { event(it) }
                }
                cursor = if (newestFirst) from else to
                list
            }
            if (batch.isEmpty()) break
            yieldAll(batch)
        }
    }

    private fun event(seq: Long): Event {
        val i = (seq % capacity).toInt()
        val f = flags[i].toInt()
        return Event(seq, times[i], f and 1 != 0, types[typeOf[i]].name, payloads[i], wires[i], f and 2 != 0, subjects[i], details[i])
    }
}
