package games.qore.foreman.client.mixin;

import com.mojang.blaze3d.font.GlyphProvider;
import games.qore.foreman.client.reload.FastReload;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.client.gui.font.FontOption;
import net.minecraft.client.gui.font.FontSet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Set;

/** A font set takes the providers selected while its font was prepared (FontManagerMixin) instead of selecting them now. */
@Mixin(FontSet.class)
public class FontSetMixin {
    @Shadow @Final private Int2ObjectMap<IntList> glyphsByWidth;

    @Inject(method = "selectProviders", at = @At("HEAD"), cancellable = true)
    private void foreman$prepared(List<GlyphProvider.Conditional> providers, Set<FontOption> options, CallbackInfoReturnable<List<GlyphProvider>> cir) {
        FastReload.FontSelection prepared = FastReload.preparedFont(providers, options);
        if (prepared == null) return;
        glyphsByWidth.putAll(prepared.getByWidth());
        cir.setReturnValue(prepared.getProviders());
    }
}
