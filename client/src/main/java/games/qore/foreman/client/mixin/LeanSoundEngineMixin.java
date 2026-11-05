package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.audio.DeviceTracker;
import games.qore.foreman.client.lean.Lean;
import net.minecraft.client.sounds.SoundEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Lean mode: no sound. OpenAL is never asked for its devices nor opens one, and the engine doesn't tick; sounds the
 * game plays aren't started (client_sounds still lists them).
 */
@Mixin(SoundEngine.class)
public class LeanSoundEngineMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/audio/Library;createDeviceTracker()Lcom/mojang/blaze3d/audio/DeviceTracker;"))
    private DeviceTracker foreman$devices(Operation<DeviceTracker> original) {
        return Lean.enabled ? Lean.NoSoundDevices.INSTANCE : original.call();
    }

    @Inject(method = "loadLibrary", at = @At("HEAD"), cancellable = true)
    private void foreman$noDevice(CallbackInfo ci) {
        if (Lean.enabled) ci.cancel();
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true)
    private void foreman$noTick(boolean paused, CallbackInfo ci) {
        if (Lean.enabled) ci.cancel();
    }
}
