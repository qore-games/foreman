package games.qore.foreman.client.debug

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.util.Chat
import net.minecraft.client.Minecraft

/** F6 / F7: copy the player's exact / block position to the clipboard. */
object CopyLocationHotkeys {
    fun init() {
        Keys.bind("copy-exact-location", Keys.Category.WORLD, InputConstants.KEY_F6, "Copy your exact location") {
            val p = Minecraft.getInstance().player ?: return@bind
            copy("exact location", "${p.x} ${p.y} ${p.z}")
        }
        Keys.bind("copy-block-location", Keys.Category.WORLD, InputConstants.KEY_F7, "Copy your block location") {
            val pos = Minecraft.getInstance().player?.blockPosition() ?: return@bind
            copy("block location", "${pos.x} ${pos.y} ${pos.z}")
        }
    }

    private fun copy(what: String, text: String) {
        Minecraft.getInstance().keyboardHandler.setClipboard(text)
        Chat.success("Copied $what: $text")
    }
}
