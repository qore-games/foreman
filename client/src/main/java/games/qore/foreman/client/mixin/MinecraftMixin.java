package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.qore.foreman.client.input.Tools;
import games.qore.foreman.client.mcp.AgentActivity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a tool is active, it gets the attack and use clicks instead of the game (no block broken or placed). While an
 * AI session uses the client, losing focus doesn't pause the game (the player's option is left as it is).
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void foreman$startAttack(CallbackInfoReturnable<Boolean> cir) {
        if (Tools.onAttack()) cir.setReturnValue(false);
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void foreman$continueAttack(boolean down, CallbackInfo ci) {
        if (Tools.capturesAttack()) ci.cancel();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void foreman$startUseItem(CallbackInfo ci) {
        if (Tools.onUse()) ci.cancel();
    }

    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void foreman$pauseIfInactive(CallbackInfo ci) {
        if (AgentActivity.active()) ci.cancel();
    }

    /** The pause screen focus loss opens, for a session to close when it comes back. */
    @WrapOperation(method = "pauseIfInactive", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"))
    private void foreman$pauseOnLostFocus(Minecraft minecraft, boolean suppressMenu, Operation<Void> original) {
        Screen before = minecraft.gui.screen();
        original.call(minecraft, suppressMenu);
        if (before == null) AgentActivity.pausedOnLostFocus(minecraft.gui.screen());
    }
}
