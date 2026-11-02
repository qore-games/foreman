package games.qore.foreman.client.mixin;

import net.minecraft.client.gui.Font;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** UI inspector: the font's glyph sources, to measure each character as the client draws it. */
@Mixin(Font.class)
public interface FontAccessor {
    @Accessor("provider")
    Font.Provider foreman$provider();
}
