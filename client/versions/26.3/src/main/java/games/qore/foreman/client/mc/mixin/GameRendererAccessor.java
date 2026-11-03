package games.qore.foreman.client.mc.mixin;

import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** client_post_effects: the post effects that failed to load (skipped until the next resource reload). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
    @Accessor("failedPostEffects")
    List<Identifier> foreman$failedPostEffects();
}
