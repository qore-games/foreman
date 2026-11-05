package games.qore.foreman.client.mc.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import games.qore.foreman.client.lean.Lean;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Lean mode: the window, created invisible, stays hidden or shows minimized. */
@Mixin(Minecraft.class)
public class LeanWindowMixin {
    @WrapOperation(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/glfw/GLFW;glfwShowWindow(J)V"))
    private void foreman$show(long window, Operation<Void> original) {
        if (Lean.hidesWindow()) return;
        original.call(window);
        if (Lean.minimizesWindow()) GLFW.glfwIconifyWindow(window);
    }
}
