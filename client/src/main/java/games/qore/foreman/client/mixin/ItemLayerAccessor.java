package games.qore.foreman.client.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The display editor's picking: where a layer of an item puts its quads (its display context's transform, then its own). */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface ItemLayerAccessor {
    @Invoker("applyTransform")
    void foreman$applyTransform(PoseStack.Pose pose);
}
