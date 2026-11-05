package games.qore.foreman.client.mixin;

import games.qore.foreman.client.lean.LeanOptions;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lean mode: its options set once options.txt is read, and the player's own written back to it (and only there). */
@Mixin(Options.class)
public class LeanOptionsMixin {
    @Inject(method = "load()V", at = @At("RETURN"))
    private void foreman$loaded(CallbackInfo ci) {
        LeanOptions.loaded((Options) (Object) this);
    }

    @Inject(method = "save", at = @At("HEAD"))
    private void foreman$beforeSave(CallbackInfo ci) {
        LeanOptions.beforeSave((Options) (Object) this);
    }

    // Before the options are sent to the server, which would otherwise get the player's view distance, not the lean one.
    @Inject(method = "save", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Options;broadcastOptions()V"))
    private void foreman$afterSave(CallbackInfo ci) {
        LeanOptions.afterSave((Options) (Object) this);
    }
}
