package games.qore.foreman.client.mc.mixin;

import com.mojang.blaze3d.platform.Window;
import games.qore.foreman.client.lean.Lean;
import org.lwjgl.sdl.SDLVideo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Lean mode: the window is created hidden or minimized (SDL flags), so it never shows on screen first. */
@Mixin(Window.class)
public class LeanWindowMixin {
    @ModifyArg(method = "createWindow", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuBackend;createWindow(Ljava/lang/String;IIJ)J"), index = 3)
    private long foreman$flags(long flags) {
        if (Lean.hidesWindow()) return flags | SDLVideo.SDL_WINDOW_HIDDEN;
        if (Lean.minimizesWindow()) return flags | SDLVideo.SDL_WINDOW_MINIMIZED;
        return flags;
    }
}
