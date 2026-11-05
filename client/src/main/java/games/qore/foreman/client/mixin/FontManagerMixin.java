package games.qore.foreman.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import games.qore.fastload.Claims;
import games.qore.foreman.client.reload.FastReload;
import net.minecraft.client.gui.font.FontManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * Each font's providers are selected while it is prepared, on a background thread, rather than as fonts are applied.
 * The selection replaces vanilla's pass over the font's glyphs there, which loads them ahead in quadratic time; it loads
 * those of the providers the font options select (a change of options loads the others' as they're drawn).
 */
@Mixin(FontManager.class)
public class FontManagerMixin {
    @Inject(method = "finalizeProviderLoading", at = @At("HEAD"), cancellable = true)
    private void foreman$selectProviders(List<GlyphProvider.Conditional> list, GlyphProvider.Conditional fallback, CallbackInfo ci) {
        if (!FastReload.handles(Claims.FONTS)) return;
        list.add(0, fallback);
        FastReload.prepareFont(list);
        ci.cancel();
    }

    @Inject(method = "prepare", at = @At("HEAD"))
    private void foreman$preparing(CallbackInfoReturnable<?> cir) {
        FastReload.forgetFonts();
    }

    @Inject(method = "apply", at = @At("RETURN"))
    private void foreman$applied(CallbackInfo ci) {
        FastReload.forgetFonts();
    }
}
