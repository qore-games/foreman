package games.qore.foreman.api.codec

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

/** Big-endian writer using Minecraft's conventions (VarInt lengths, UTF-8 strings), so both sides read the same bytes. */
class PacketWriter {
    private val out = ByteArrayOutputStream(256)
    private val scratch = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN)

    fun writeUtf(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        writeVarInt(bytes.size)
        out.write(bytes)
    }

    fun writeVarInt(value: Int) {
        var v = value
        while ((v and -0x80) != 0) {
            out.write((v and 0x7F) or 0x80)
            v = v ushr 7
        }
        out.write(v)
    }

    fun writeInt(value: Int) = fixed { putInt(value) }
    fun writeDouble(value: Double) = fixed { putDouble(value) }
    fun writeFloat(value: Float) = fixed { putFloat(value) }
    fun writeBoolean(value: Boolean) = out.write(if (value) 1 else 0)

    fun <T : Any> writeOptional(value: T?, write: PacketWriter.(T) -> Unit) {
        writeBoolean(value != null)
        if (value != null) write(value)
    }

    fun writeStringMap(map: Map<String, String>) {
        writeVarInt(map.size)
        map.forEach { (k, v) -> writeUtf(k); writeUtf(v) }
    }

    private inline fun fixed(put: ByteBuffer.() -> Unit) {
        scratch.clear()
        scratch.put()
        out.write(scratch.array(), 0, scratch.position())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

class PacketReader(data: ByteArray) {
    private val buffer = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)

    fun readUtf(): String {
        val bytes = ByteArray(readVarInt())
        buffer.get(bytes)
        return String(bytes, StandardCharsets.UTF_8)
    }

    fun readVarInt(): Int {
        var result = 0
        var shift = 0
        var b: Int
        do {
            b = buffer.get().toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            shift += 7
        } while ((b and 0x80) != 0)
        return result
    }

    fun readInt(): Int = buffer.getInt()
    fun readDouble(): Double = buffer.getDouble()
    fun readFloat(): Float = buffer.getFloat()
    fun readBoolean(): Boolean = buffer.get() != 0.toByte()

    fun <T> readOptional(read: PacketReader.() -> T): T? = if (readBoolean()) read() else null

    fun readStringMap(): Map<String, String> = (0 until readVarInt()).associate { readUtf() to readUtf() }
}

/** Reads and writes one message type; the same instance is used on both sides of a channel. */
interface Codec<T> {
    fun write(writer: PacketWriter, value: T)
    fun read(reader: PacketReader): T

    fun encode(value: T): ByteArray = PacketWriter().also { write(it, value) }.toByteArray()
    fun decode(data: ByteArray): T = read(PacketReader(data))
}
