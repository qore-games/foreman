package games.qore.foreman.client.mixin;

import games.qore.foreman.client.input.Tools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Lets the active tool use the scroll wheel (nudging a drag) instead of the hotbar. */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void foreman$scroll(long handle, double xoffset, double yoffset, CallbackInfo ci) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gui.screen() == null && minecraft.player != null && yoffset != 0 && Tools.onScroll(yoffset)) ci.cancel();
    }
}
