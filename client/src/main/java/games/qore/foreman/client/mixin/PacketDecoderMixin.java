package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import games.qore.foreman.client.packets.PacketRecorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.PacketDecoder;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet recording: each packet received, with its decoded size (the frame it was read from). */
@Mixin(PacketDecoder.class)
public class PacketDecoderMixin {
    @Shadow @Final private ProtocolInfo<?> protocolInfo;

    @Inject(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V", at = @At("RETURN"))
    private void foreman$decoded(ChannelHandlerContext ctx, ByteBuf input, List<Object> out, CallbackInfo ci, @Local(ordinal = 0) int readableBytes) {
        if (PacketRecorder.recording && !out.isEmpty() && out.getLast() instanceof Packet<?> packet) PacketRecorder.decoded(ctx, input, protocolInfo, packet, readableBytes);
    }
}
