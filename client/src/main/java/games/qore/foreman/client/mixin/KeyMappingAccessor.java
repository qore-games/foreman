package games.qore.foreman.client.mixin;

import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Held inputs (client_key, client_input): a binding's state as is (sprint and sneak's setDown toggles), its presses. */
@Mixin(KeyMapping.class)
public interface KeyMappingAccessor {
    @Accessor("isDown")
    void foreman$setDown(boolean down);

    @Accessor("clickCount")
    int foreman$clickCount();

    @Accessor("clickCount")
    void foreman$setClickCount(int count);
}
