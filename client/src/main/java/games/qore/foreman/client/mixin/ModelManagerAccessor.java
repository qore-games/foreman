package games.qore.foreman.client.mixin;

import java.util.Map;
import net.minecraft.client.renderer.item.ItemModel;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The model browser: the item models the loaded resource packs define (a new map after each reload). */
@Mixin(ModelManager.class)
public interface ModelManagerAccessor {
    @Accessor("bakedItemStackModels")
    Map<Identifier, ItemModel> foreman$itemModels();
}
