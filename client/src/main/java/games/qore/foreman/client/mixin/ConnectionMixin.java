package games.qore.foreman.client.mixin;

import games.qore.foreman.client.debug.PluginMessageDebug;
import games.qore.foreman.client.link.OutboundGate;
import games.qore.foreman.client.link.PacketFilter;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Plugin message debugging: sees every custom payload, both ways. Also the two packet-manipulation hook points: the
 * inbound dispatch (a test can drop a selected packet) and the outbound funnel (an armed network profile can hold it).
 */
@Mixin(Connection.class)
public class ConnectionMixin {
    @Shadow private Channel channel;

    @Inject(method = "genericsFtw", at = @At("HEAD"), cancellable = true)
    private static <T extends PacketListener> void foreman$received(Packet<T> packet, PacketListener listener, CallbackInfo ci) {
        if (packet instanceof ClientboundCustomPayloadPacket custom) PluginMessageDebug.onPayload(custom.payload(), false);
        if (PacketFilter.shouldDrop(packet)) ci.cancel();
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void foreman$sent(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundCustomPayloadPacket custom) PluginMessageDebug.onPayload(custom.payload(), true);
    }

    /** Every public `send` overload funnels through here, before the packet is handed to the event loop. */
    @Inject(method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"), cancellable = true)
    private void foreman$park(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (OutboundGate.park((Connection) (Object) this, this.channel, packet)) ci.cancel();
    }
}

