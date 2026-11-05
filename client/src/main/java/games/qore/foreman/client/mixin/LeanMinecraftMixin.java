package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.qore.foreman.client.lean.Lean;
import games.qore.foreman.client.mcp.FrameStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lean mode: each loop of the game draws a frame only when Lean says so (a tool needs one, a loading screen, the capped
 * rate); the others do what a frame does besides drawing, then sleep. Frames are counted for tools waiting on one, and
 * read back for the live streams open (lean or not).
 */
@Mixin(Minecraft.class)
public class LeanMinecraftMixin {
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
    private void foreman$leanFrame(Minecraft minecraft, boolean advanceGameTime, Operation<Void> original) {
        if (Lean.drawsFrame(minecraft)) {
            Lean.frameStarts();
            original.call(minecraft, advanceGameTime);
        } else {
            Lean.skipFrame(minecraft, advanceGameTime);
        }
    }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void foreman$frameDrawn(boolean advanceGameTime, CallbackInfo ci) {
        Lean.frameEnds();
        FrameStream.frameDrawn();
    }

    /** Animated textures upload a frame each tick: only for loops that draw one. */
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/texture/TextureManager;tick()V"))
    private void foreman$textures(TextureManager textures, Operation<Void> original) {
        if (Lean.ticksTextures((Minecraft) (Object) this)) original.call(textures);
    }
}
