package games.qore.foreman.client.mixin;

import games.qore.foreman.client.editor.EditorMode;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Editor mode gets each key press in game before the game's key bindings: its keys (the current tool's, 1-9, Esc, its
 * own key) go no further; the others play as usual.
 */
@Mixin(KeyboardHandler.class)
public class EditorKeyboardMixin {
    @Inject(method = "keyPress", at = @At("HEAD"), cancellable = true)
    private void foreman$editorKeys(long handle, int action, KeyEvent event, CallbackInfo ci) {
        if (EditorMode.onKey(action, event)) ci.cancel();
    }
}
