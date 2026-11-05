package games.qore.foreman.client.mixin;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Transparency;
import games.qore.fastload.Claims;
import games.qore.foreman.client.reload.FastMipmaps;
import games.qore.foreman.client.reload.FastReload;
import net.minecraft.client.renderer.texture.MipmapGenerator;
import net.minecraft.client.renderer.texture.MipmapStrategy;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Sprites' mipmaps are made the fast way (FastMipmaps), with the same pixels. */
@Mixin(MipmapGenerator.class)
public class MipmapGeneratorMixin {
    @Inject(method = "generateMipLevels", at = @At("HEAD"), cancellable = true)
    private static void foreman$generateMipLevels(Identifier name, NativeImage[] currentMips, int newMipLevel, MipmapStrategy mipmapStrategy,
                                                  float alphaCutoffBias, Transparency transparency, CallbackInfoReturnable<NativeImage[]> cir) {
        if (FastReload.handles(Claims.MIPMAPS)) cir.setReturnValue(FastMipmaps.generateMipLevels(name, currentMips, newMipLevel, mipmapStrategy, alphaCutoffBias, transparency));
    }
}
