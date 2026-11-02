package games.qore.foreman.client.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** UI inspector: the action bar and the titles the HUD shows. */
@Mixin(Hud.class)
public interface HudAccessor {
    @Accessor("overlayMessageString")
    Component foreman$actionBar();

    @Accessor("overlayMessageTime")
    int foreman$actionBarTime();

    @Accessor("title")
    Component foreman$title();

    @Accessor("subtitle")
    Component foreman$subtitle();

    @Accessor("titleTime")
    int foreman$titleTime();
}
