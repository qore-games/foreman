package games.qore.foreman.client.mixin;

import games.qore.foreman.client.mcp.MetricTools;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** client_sounds: the sounds the client plays, while it records them. */
@Mixin(SoundManager.class)
public class SoundManagerMixin {
    @Inject(method = "play", at = @At("RETURN"))
    private void foreman$play(SoundInstance sound, CallbackInfoReturnable<SoundEngine.PlayResult> cir) {
        MetricTools.played(sound, cir.getReturnValue());
    }
}
