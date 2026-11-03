package games.qore.foreman.client.mixin;

import games.qore.foreman.client.reload.FastReload;
import games.qore.foreman.client.reload.ReloadOverlay;
import games.qore.foreman.client.reload.ReloadTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.LoadingOverlay;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.server.packs.resources.ReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The loading screen's frames and its closing, for the reload timer. With fast reloads it closes as soon as the packs
 * are loaded (vanilla keeps it at least a second, then fades it out for two more, taking no input meanwhile); with keep
 * playing, a reload's (not the game's start) is only a thin progress bar over the game, which goes on. Both are the
 * settings the reload started with: changing them midway would leave it open for good.
 */
@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin extends Overlay implements ReloadOverlay {
    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private ReloadInstance reload;
    @Shadow private long fadeOutStart;
    @Unique private boolean foreman$fast;
    @Unique private boolean foreman$background;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void foreman$init(Minecraft minecraft, ReloadInstance reload, Consumer<Optional<Throwable>> onFinish, boolean fadeIn, CallbackInfo ci) {
        foreman$fast = FastReload.enabled();
        foreman$background = fadeIn && FastReload.keepsPlaying();
    }

    @Override
    public boolean foreman$fast() {
        return foreman$fast;
    }

    @Override
    public boolean foreman$inBackground() {
        return foreman$background;
    }

    /** In the background, singleplayer doesn't pause either. */
    @Override
    public boolean isPausing() {
        return !foreman$background;
    }

    @Inject(method = "extractRenderState", at = @At("HEAD"), cancellable = true)
    private void foreman$frame(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a, CallbackInfo ci) {
        ReloadTimer.frame();
        if (!foreman$background) return;
        FastReload.extractBackgroundReload(graphics, mouseX, mouseY, a, reload.getActualProgress());
        ci.cancel();
    }

    @Inject(method = "extractRenderState", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;setOverlay(Lnet/minecraft/client/gui/screens/Overlay;)V"))
    private void foreman$closed(CallbackInfo ci) {
        ReloadTimer.closed();
    }

    @Inject(method = "isReadyToFadeOut", at = @At("HEAD"), cancellable = true)
    private void foreman$noWait(CallbackInfoReturnable<Boolean> cir) {
        if (foreman$fast) cir.setReturnValue(true);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void foreman$closeAtOnce(CallbackInfo ci) {
        if (fadeOutStart == -1L || !foreman$fast || minecraft.gui.overlay() != (Object) this) return;
        FastReload.reloadEnded();
        ReloadTimer.closed();
        minecraft.gui.setOverlay(null);
    }
}
