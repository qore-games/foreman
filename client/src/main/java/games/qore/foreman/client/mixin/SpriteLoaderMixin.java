package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.qore.fastload.Claims;
import games.qore.fastload.Mipmaps;
import games.qore.foreman.client.reload.FastReload;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.function.Consumer;

/** An atlas's mipmaps, sprite by sprite on every background thread, the biggest first: vanilla makes them one after the other on one. */
@Mixin(SpriteLoader.class)
public class SpriteLoaderMixin {
    @WrapOperation(method = "lambda$stitch$2", at = @At(value = "INVOKE", target = "Ljava/util/Collection;forEach(Ljava/util/function/Consumer;)V"))
    private static void foreman$parallelMipmaps(Collection<TextureAtlasSprite> sprites, Consumer<TextureAtlasSprite> increaseMipLevel, Operation<Void> original) {
        if (FastReload.handles(Claims.MIPMAPS)) Mipmaps.inParallel(sprites, sprite -> (long) sprite.contents().width() * sprite.contents().height(), increaseMipLevel);
        else original.call(sprites, increaseMipLevel);
    }
}
