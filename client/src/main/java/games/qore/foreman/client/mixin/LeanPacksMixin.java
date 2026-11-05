package games.qore.foreman.client.mixin;

import games.qore.foreman.client.lean.Lean;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lean mode with `foreman.lean.packs=skip`: a resource pack the server pushes is answered accepted, downloaded and
 * loaded at once, without downloading nor applying it, so a server that requires its packs still lets the client in.
 * On the client thread (after the handler moved there), once per pack.
 */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class LeanPacksMixin {
    @Shadow @Final protected Connection connection;

    @Inject(method = "handleResourcePackPush", cancellable = true, at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V"))
    private void foreman$skipPack(ClientboundResourcePackPushPacket packet, CallbackInfo ci) {
        if (!Lean.skipsPacks()) return;
        connection.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.ACCEPTED));
        connection.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.DOWNLOADED));
        connection.send(new ServerboundResourcePackPacket(packet.id(), ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED));
        Lean.skippedPack(packet.id(), packet.url(), packet.required());
        ci.cancel();
    }
}
