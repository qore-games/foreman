package games.qore.foreman.client.mixin;

import games.qore.foreman.client.reload.FastReload;
import games.qore.foreman.client.reload.ReloadTimer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A listener of the client's timed reload is prepared (it reached the barrier where every listener waits to apply),
 * for the timeline; the last one is, so the listeners start applying. Data pack loads use the same barrier.
 */
@Mixin(targets = "net.minecraft.server.packs.resources.SimpleReloadInstance$1")
public class ReloadBarrierMixin {
    @Shadow @Final SimpleReloadInstance<?> this$0;

    @Inject(method = "lambda$wait$0", at = @At("HEAD"))
    private void foreman$prepared(PreparableReloadListener listener, CallbackInfo ci) {
        if (ReloadTimer.isTimed(this$0)) ReloadTimer.prepared(listener.getName());
    }

    @Inject(method = "lambda$wait$0", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/CompletableFuture;complete(Ljava/lang/Object;)Z"))
    private void foreman$allPrepared(PreparableReloadListener listener, CallbackInfo ci) {
        if (ReloadTimer.isTimed(this$0)) FastReload.applying();
    }
}
