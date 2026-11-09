package games.qore.foreman.client.mixin;

import games.qore.foreman.client.overlays.HiddenBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** A block the client changed (the server's updates, chunk section updates): the hidden blocks overlay scans its section again. */
@Mixin(ClientLevel.class)
public class HiddenBlocksMixin {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void foreman$blockChanged(BlockPos pos, BlockState old, BlockState now, int flags, CallbackInfo ci) {
        HiddenBlocks.blockChanged(pos);
    }
}
