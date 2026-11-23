package games.qore.foreman.client.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The entity editor's picking: the layers of an item as the client draws it. */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("activeLayerCount")
    int foreman$activeLayerCount();

    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] foreman$layers();
}
