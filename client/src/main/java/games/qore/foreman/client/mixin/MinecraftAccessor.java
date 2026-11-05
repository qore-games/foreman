package games.qore.foreman.client.mixin;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Lean mode: what the player looks at, found each frame (Lean.skipFrame does it without drawing). */
@Mixin(Minecraft.class)
public interface MinecraftAccessor {
    @Invoker("pick")
    void foreman$pick(float partialTicks);
}
