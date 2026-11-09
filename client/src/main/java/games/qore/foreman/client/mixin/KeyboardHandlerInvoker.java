package games.qore.foreman.client.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** client_key: a key pressed on the keyboard, as the game gets it from the window (private on some versions). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {
    @Invoker("keyPress")
    void foreman$keyPress(long handle, int action, KeyEvent event);
}
