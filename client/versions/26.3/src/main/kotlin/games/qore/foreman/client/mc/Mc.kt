package games.qore.foreman.client.mc

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.mc.mixin.GameRendererAccessor
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
import net.minecraft.resources.Identifier

/**
 * What differs between the Minecraft versions Foreman is built for, here for 26.3. Every client/versions/<minecraft>
 * has an Mc with the same members; the shared code calls them instead of the API that differs.
 */
object Mc {
    /** KeyboardHandler's method for the text an input method (IME) is composing, for ReloadKeyboardMixin. */
    const val TEXT_EDITING = "textEditing"

    /** The input type of keyboard key mappings. */
    val keyboard: InputConstants.Type = InputConstants.Type.KEYBOARD

    /** Whether [key] (an InputConstants key) is held down. */
    fun isKeyDown(key: Int): Boolean = InputConstants.isKeyDown(key)

    /** What the player typed after the slash, for a command suggestion request. */
    fun command(packet: ServerboundCommandSuggestionPacket): String = packet.command()

    /** The post effects the client applies now, and what asked for them. On the client thread. */
    fun postEffects(): PostEffectState {
        val minecraft = Minecraft.getInstance()
        val renderer = minecraft.gameRenderer
        return PostEffectState(
            requested = renderer.requestedPostEffects.toList(),
            fromServer = minecraft.player?.activePostEffects?.toList().orEmpty(),
            spectated = renderer.spectatedEntityPostEffect(),
            applied = renderer.appliedPostEffects.toList(),
            failed = (renderer as GameRendererAccessor).`foreman$failedPostEffects`().toList(),
        )
    }

    /** The post effect ids the loaded resource packs declare. On the client thread. */
    fun availablePostEffects(): List<Identifier> = Minecraft.getInstance().shaderManager.availablePostEffects.toList()
}
