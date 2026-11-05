package games.qore.foreman.client.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.device.GpuSurface;
import games.qore.foreman.client.lean.Lean;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Lean mode with a hidden window: frames are drawn into the game's render target only (screenshots read it), never
 * presented, so nothing waits on a compositor for a window it doesn't show.
 */
@Mixin(Minecraft.class)
public class LeanSurfaceMixin {
    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;configure(Lcom/mojang/renderpearl/api/device/GpuSurface$Configuration;)V"))
    private void foreman$configure(GpuSurface surface, GpuSurface.Configuration configuration, Operation<Void> original) {
        if (!Lean.hidesWindow()) original.call(surface, configuration);
    }

    @WrapOperation(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;acquireNextTexture()V"))
    private void foreman$acquire(GpuSurface surface, Operation<Void> original) {
        if (!Lean.hidesWindow()) original.call(surface);
    }
}
