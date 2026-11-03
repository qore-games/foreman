package games.qore.foreman.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import games.qore.foreman.client.reload.ReloadTimer;
import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** The client's reloads Foreman times are profiled (each listener's time), as vanilla does with debug logging. */
@Mixin(ReloadableResourceManager.class)
public class ReloadableResourceManagerMixin {
    @ModifyExpressionValue(method = "createReload", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;isDebugEnabled()Z"))
    private boolean foreman$profile(boolean debug) {
        return debug || ReloadTimer.timing() && (Object) this == Minecraft.getInstance().getResourceManager();
    }

    @ModifyReturnValue(method = "createReload", at = @At("RETURN"))
    private ReloadInstance foreman$created(ReloadInstance reload) {
        if ((Object) this == Minecraft.getInstance().getResourceManager()) ReloadTimer.created(reload);
        return reload;
    }
}
