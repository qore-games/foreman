package games.qore.foreman.client.mixin;

import net.minecraft.client.renderer.texture.MipmapGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Vanilla's blend for dark cutout mipmaps, for FastMipmaps. */
@Mixin(MipmapGenerator.class)
public interface MipmapGeneratorAccessor {
    @Invoker("darkenedAlphaBlend")
    static int foreman$darkenedAlphaBlend(int color1, int color2, int color3, int color4) {
        throw new AssertionError();
    }
}
