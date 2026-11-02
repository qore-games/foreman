package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.qore.foreman.client.packets.PacketRecorder;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Packet recording: the packets of a bundle are timed one by one (the bundle itself is not). */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {
    @WrapOperation(method = "handleBundlePacket", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/Packet;handle(Lnet/minecraft/network/PacketListener;)V"))
    private void foreman$bundled(Packet<?> packet, PacketListener listener, Operation<Void> original) {
        if (!PacketRecorder.recording) {
            original.call(packet, listener);
            return;
        }
        long start = System.nanoTime();
        original.call(packet, listener);
        PacketRecorder.handled(ConnectionProtocol.PLAY, packet, System.nanoTime() - start);
    }
}
