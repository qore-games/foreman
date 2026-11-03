package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.qore.foreman.client.input.Tools;
import games.qore.foreman.client.mcp.AgentActivity;
import games.qore.foreman.client.mcp.InputTools;
import games.qore.foreman.client.mcp.PerfTools;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a tool is active, it gets the attack and use clicks instead of the game (no block broken or placed). While an
 * AI session uses the client, losing focus doesn't pause the game (the player's option is left as it is). Frames are
 * timed while client_perf measures.
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

    @Inject(method = "runTick", at = @At("RETURN"))
    private void foreman$frame(boolean advanceGameTime, CallbackInfo ci) {
        PerfTools.frame();
    }

    @Inject(method = "pauseIfInactive", at = @At("HEAD"), cancellable = true)
    private void foreman$pauseIfInactive(CallbackInfo ci) {
        if (AgentActivity.active()) ci.cancel();
    }

    /** A held attack (client_input) keeps mining with the window in the background, where the mouse isn't grabbed. */
    @WrapOperation(method = "handleKeybinds", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/MouseHandler;isMouseGrabbed()Z"))
    private boolean foreman$heldAttack(MouseHandler handler, Operation<Boolean> original) {
        return original.call(handler) || InputTools.holdsAttack();
    }

    /** The pause screen focus loss opens, for a session to close when it comes back. */
    @WrapOperation(method = "pauseIfInactive", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;pauseGame(Z)V"))
    private void foreman$pauseOnLostFocus(Minecraft minecraft, boolean suppressMenu, Operation<Void> original) {
        Screen before = minecraft.gui.screen();
        original.call(minecraft, suppressMenu);
        if (before == null) AgentActivity.pausedOnLostFocus(minecraft.gui.screen());
    }
}
