package games.qore.foreman.client.mc.mixin;

import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** client_post_effects: whether the spectated entity's post effect is on (spectators toggle it). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("effectActive")
    boolean foreman$effectActive();
}
