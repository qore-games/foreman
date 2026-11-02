package games.qore.foreman.client.net

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.minecraft.client.Minecraft
import net.minecraft.network.RegistryFriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.resources.Identifier
import org.slf4j.LoggerFactory

/** A plugin message: the channel and its raw bytes. Every channel of the mod uses it; typed messages are decoded from [data]. */
class RawPayload(private val type: CustomPacketPayload.Type<RawPayload>, val data: ByteArray) : CustomPacketPayload {
    override fun type() = type
}

/**
 * The mod's only networking layer. A channel is declared once with how its messages are encoded or decoded; incoming
 * messages are handled on the client thread, outgoing ones are sent only when the server listens on the channel.
 */
object Net {
    private val logger = LoggerFactory.getLogger("foreman")

    class Outgoing<T>(private val type: CustomPacketPayload.Type<RawPayload>, private val encode: (T) -> ByteArray) {
        val available: Boolean get() = Minecraft.getInstance().connection != null && ClientPlayNetworking.canSend(type)

        /** Sends [value]; false when the server does not listen on this channel. */
        fun send(value: T): Boolean {
            if (!available) return false
            ClientPlayNetworking.send(RawPayload(type, encode(value)))
            return true
        }
    }

    private fun type(channel: String) = CustomPacketPayload.Type<RawPayload>(Identifier.parse(channel))

    private fun codec(type: CustomPacketPayload.Type<RawPayload>): StreamCodec<RegistryFriendlyByteBuf, RawPayload> = StreamCodec.ofMember(
        { payload, buf -> buf.writeBytes(payload.data) },
        { buf -> RawPayload(type, ByteArray(buf.readableBytes()).also { buf.readBytes(it) }) },
    )

    fun <T> incoming(channel: String, decode: (ByteArray) -> T, handle: (T) -> Unit) {
        val type = type(channel)
        PayloadTypeRegistry.clientboundPlay().register(type, codec(type))
        ClientPlayNetworking.registerGlobalReceiver(type) { payload, context ->
            val message = try {
                decode(payload.data)
            } catch (e: Exception) {
                logger.warn("Bad message on {}", channel, e)
                return@registerGlobalReceiver
            }
            context.client().execute { handle(message) }
        }
    }

    fun <T> outgoing(channel: String, encode: (T) -> ByteArray): Outgoing<T> {
        val type = type(channel)
        PayloadTypeRegistry.serverboundPlay().register(type, codec(type))
        return Outgoing(type, encode)
    }
}
