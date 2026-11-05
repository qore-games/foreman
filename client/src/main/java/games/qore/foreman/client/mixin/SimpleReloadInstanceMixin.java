package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import games.qore.foreman.client.reload.ReloadTimer;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.SimpleReloadInstance;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * While a reload is timed, each listener's tasks are, for the reload timer: their time preparing off the main thread and
 * applying on it, then the steps once every listener is done. Vanilla's profiled reload measures the same but isn't
 * switched on: with ModernFix, a profiled reload never applies its listeners on 26.x.
 */
@Mixin(SimpleReloadInstance.class)
public class SimpleReloadInstanceMixin {
    @Unique private static final String CREATE_STEP = "Lnet/minecraft/server/packs/resources/SimpleReloadInstance$StateFactory;create(Lnet/minecraft/server/packs/resources/PreparableReloadListener$SharedState;Lnet/minecraft/server/packs/resources/PreparableReloadListener$PreparationBarrier;Lnet/minecraft/server/packs/resources/PreparableReloadListener;Ljava/util/concurrent/Executor;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;";

    @Unique private ReloadTimer.@Nullable ListenerTimes foreman$times;

    @Inject(method = "prepareTasks", at = @At("HEAD"))
    private void foreman$timed(CallbackInfoReturnable<?> cir) {
        foreman$times = ReloadTimer.timing() ? new ReloadTimer.ListenerTimes() : null;
    }

    @ModifyArg(method = "prepareTasks", at = @At(value = "INVOKE", target = CREATE_STEP), index = 3)
    private Executor foreman$timePreparing(PreparableReloadListener.SharedState state, PreparableReloadListener.PreparationBarrier barrier,
                                           PreparableReloadListener listener, Executor tasks, Executor applies) {
        return foreman$times == null ? tasks : foreman$times.executor(listener, tasks, false);
    }

    @ModifyArg(method = "prepareTasks", at = @At(value = "INVOKE", target = CREATE_STEP), index = 4)
    private Executor foreman$timeApplying(PreparableReloadListener.SharedState state, PreparableReloadListener.PreparationBarrier barrier,
                                          PreparableReloadListener listener, Executor tasks, Executor applies) {
        return foreman$times == null ? applies : foreman$times.executor(listener, applies, true);
    }

    @ModifyReturnValue(method = "prepareTasks", at = @At("RETURN"))
    private CompletableFuture<?> foreman$done(CompletableFuture<?> done) {
        ReloadTimer.ListenerTimes times = foreman$times;
        if (times == null) return done;
        return done.thenApply(result -> {
            ReloadTimer.steps((ReloadInstance) this, times);
            return result;
        });
    }
}
