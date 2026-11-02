package games.qore.foreman.client.mixin;

import com.mojang.math.Transformation;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** client_entities: a display entity's transformation as the server last sent it (not the interpolated one). */
@Mixin(Display.class)
public interface DisplayAccessor {
    @Invoker("createTransformation")
    static Transformation foreman$transformation(SynchedEntityData entityData) {
        throw new AssertionError();
    }
}
