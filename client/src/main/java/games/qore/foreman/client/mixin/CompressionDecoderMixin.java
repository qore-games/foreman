package games.qore.foreman.client.mixin;

import games.qore.foreman.client.packets.PacketRecorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import java.util.List;
import net.minecraft.network.CompressionDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet recording: the size of each received frame on the wire, before it is inflated. */
@Mixin(CompressionDecoder.class)
public class CompressionDecoderMixin {
    @Inject(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V", at = @At("HEAD"))
    private void foreman$read(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        if (PacketRecorder.recording) PacketRecorder.frameRead(in.readableBytes());
    }

    @Inject(method = "decode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Ljava/util/List;)V", at = @At("RETURN"))
    private void foreman$inflated(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        if (PacketRecorder.recording && !out.isEmpty()) PacketRecorder.frameInflated(out.getLast());
    }
}
