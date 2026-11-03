package games.qore.foreman.client.mixin;

import games.qore.foreman.client.reload.ReloadTimer;
import net.minecraft.server.packs.resources.ProfiledReloadInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Every listener of a profiled reload is done: their times, for the reload timer. */
@Mixin(ProfiledReloadInstance.class)
public class ProfiledReloadInstanceMixin {
    @Inject(method = "finish", at = @At("RETURN"))
    private void foreman$finish(List<ProfiledReloadInstance.State> states, CallbackInfoReturnable<List<ProfiledReloadInstance.State>> cir) {
        if (ReloadTimer.isTimed(this)) ReloadTimer.steps(states);
    }
}
