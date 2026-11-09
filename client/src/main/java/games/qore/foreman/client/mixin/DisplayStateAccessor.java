package games.qore.foreman.client.mixin;

import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The display editor's preview: a display drawn with new values at once, not at its next tick. */
@Mixin(Display.class)
public interface DisplayStateAccessor {
    @Invoker("createFreshRenderState")
    Display.RenderState foreman$freshRenderState();

    @Accessor("renderState")
    void foreman$setRenderState(Display.RenderState state);
}
