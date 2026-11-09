package games.qore.foreman.client.mixin;

import games.qore.foreman.client.editor.EditorMode;
import net.minecraft.client.gui.Hud;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Spectator mode hides the crosshair (but over a container): editor mode shows it, its tools aim with it. */
@Mixin(Hud.class)
public class EditorCrosshairMixin {
    @Inject(method = "canRenderCrosshairForSpectator", at = @At("HEAD"), cancellable = true)
    private void foreman$editorCrosshair(HitResult hit, CallbackInfoReturnable<Boolean> cir) {
        if (EditorMode.showsCrosshair()) cir.setReturnValue(true);
    }
}
