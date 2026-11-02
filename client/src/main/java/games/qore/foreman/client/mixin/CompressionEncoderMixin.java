package games.qore.foreman.client.mixin;

import games.qore.foreman.client.packets.PacketRecorder;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet recording: the size on the wire of the packet just encoded, once compressed. */
@Mixin(CompressionEncoder.class)
public class CompressionEncoderMixin {
    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V", at = @At("RETURN"))
    private void foreman$compressed(ChannelHandlerContext ctx, ByteBuf uncompressed, ByteBuf out, CallbackInfo ci) {
        if (PacketRecorder.recording) PacketRecorder.compressed(out.readableBytes());
    }
}
