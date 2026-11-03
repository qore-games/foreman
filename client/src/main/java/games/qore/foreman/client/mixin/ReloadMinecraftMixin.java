package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.qore.foreman.client.reload.FastReload;
import games.qore.foreman.client.reload.ReloadTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.resources.server.DownloadedPackSource;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/**
 * A resource reload starts (F3+T, the options' packs, a server's pack), past the check for one already running. With
 * fast reloads its end is looked for every frame, as the main thread's tasks (the last applied listeners) are done:
 * before a frame draws the new textures on chunks built with the old ones. With keep playing, the game's keys work, and
 * no frame is drawn while the listeners apply.
 */
@Mixin(Minecraft.class)
public class ReloadMinecraftMixin {
    @Shadow private @Nullable CompletableFuture<Void> pendingReload;

    @Inject(method = "reloadResourcePacks(ZLnet/minecraft/client/GameLoadCookie;)Ljava/util/concurrent/CompletableFuture;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/packs/repository/PackRepository;reload()V"))
    private void foreman$reloadStarts(CallbackInfoReturnable<?> cir) {
        ReloadTimer.start();
    }

    @Inject(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runAllTasks()V", shift = At.Shift.AFTER))
    private void foreman$tasksRan(CallbackInfo ci) {
        FastReload.tasksRan();
    }

    /** Frames wait while a reload that keeps playing applies its listeners: they would show it half done. */
    @WrapOperation(method = "runTick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
    private void foreman$renderFrame(Minecraft minecraft, boolean advanceGameTime, Operation<Void> original) {
        if (!FastReload.holdsFrames()) original.call(minecraft, advanceGameTime);
    }

    /**
     * A reload ended while another waits (a server's pack asked for mid-reload, as keep playing lets you join one): the
     * server's packs are loaded by the waiting one, which reports them; vanilla reports them loaded now.
     */
    @WrapOperation(method = "lambda$reloadResourcePacks$2", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/resources/server/DownloadedPackSource;onReloadSuccess()V"))
    private void foreman$serverPacksLoaded(DownloadedPackSource source, Operation<Void> original) {
        if (pendingReload == null || !FastReload.enabled()) original.call(source);
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/Gui;overlay()Lnet/minecraft/client/gui/screens/Overlay;"))
    private @Nullable Overlay foreman$playing(@Nullable Overlay overlay) {
        return FastReload.inBackground(overlay) ? null : overlay;
    }
}
