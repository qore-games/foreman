package games.qore.foreman.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** client_key: a key pressed on the keyboard and the character it types, as the game gets them from the window (private on some versions). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void foreman$keyPress(long handle, int action, KeyEvent event);

    @Invoker("charTyped")
    void foreman$charTyped(long handle, CharacterEvent event);
}
