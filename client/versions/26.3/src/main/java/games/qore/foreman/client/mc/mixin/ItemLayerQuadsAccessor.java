package games.qore.foreman.client.mc.mixin;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The entity editor's picking: the quads of a layer of an item. */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerQuadsAccessor {
    @Accessor("quads")
    ItemQuads foreman$quads();
}
