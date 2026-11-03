package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import games.qore.foreman.client.reload.FastReload;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.screens.Overlay;
import org.jspecify.annotations.Nullable;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The command key works through a reload that keeps playing: its loading screen counts as none. */
@Mixin(Gui.class)
public class ReloadGuiMixin {
    @ModifyExpressionValue(method = "handleKeybinds",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/gui/Gui;overlay:Lnet/minecraft/client/gui/screens/Overlay;", opcode = Opcodes.GETFIELD))
    private @Nullable Overlay foreman$playing(@Nullable Overlay overlay) {
        return FastReload.inBackground(overlay) ? null : overlay;
    }
}
