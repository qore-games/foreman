package games.qore.foreman.client.mixin;

import games.qore.foreman.client.packets.PacketRecorder;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet recording: how long the client thread takes to handle each packet. */
@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public class QueuedPacketMixin {
    @Shadow @Final private PacketListener listener;
    @Shadow @Final private Packet<?> packet;

    @Inject(method = "handle", at = @At("HEAD"))
    private void foreman$start(CallbackInfo ci) {
        if (PacketRecorder.recording) PacketRecorder.handleStart(listener, packet);
    }

    @Inject(method = "handle", at = @At("RETURN"))
    private void foreman$end(CallbackInfo ci) {
        if (PacketRecorder.recording) PacketRecorder.handleEnd(listener, packet);
    }
}
