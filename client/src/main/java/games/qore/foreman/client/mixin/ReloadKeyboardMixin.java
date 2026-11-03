package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.qore.foreman.client.reload.FastReload;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.gui.screens.Overlay;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Typing in screens works through a reload that keeps playing: its loading screen counts as none. */
@Mixin(KeyboardHandler.class)
public class ReloadKeyboardMixin {
    @ModifyExpressionValue(method = {"charTyped", "textEditing"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;"))
    private @Nullable Overlay foreman$playing(@Nullable Overlay overlay) {
        return FastReload.inBackground(overlay) ? null : overlay;
    }
}
