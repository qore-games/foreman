package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import games.qore.foreman.client.reload.ReloadTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The client's resource manager made a reload: the one Foreman times, not a data pack load. */
@Mixin(ReloadableResourceManager.class)
public class ReloadableResourceManagerMixin {
    @ModifyReturnValue(method = "createReload", at = @At("RETURN"))
    private ReloadInstance foreman$created(ReloadInstance reload) {
        if ((Object) this == Minecraft.getInstance().getResourceManager()) ReloadTimer.created(reload);
        return reload;
    }
}
