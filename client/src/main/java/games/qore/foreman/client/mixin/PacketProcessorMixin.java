package games.qore.foreman.client.mixin;

import games.qore.foreman.client.packets.PacketRecorder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.PacketProcessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Packet recording: how long each frame of the client spends handling the packets received since the last one. */
@Mixin(PacketProcessor.class)
public class PacketProcessorMixin {
    @Inject(method = "processQueuedPackets", at = @At("HEAD"))
    private void foreman$start(CallbackInfo ci) {
        if (PacketRecorder.recording && (Object) this == Minecraft.getInstance().packetProcessor()) PacketRecorder.frameStart();
    }

    @Inject(method = "processQueuedPackets", at = @At("RETURN"))
    private void foreman$end(CallbackInfo ci) {
        if (PacketRecorder.recording && (Object) this == Minecraft.getInstance().packetProcessor()) PacketRecorder.frameEnd();
    }
}
