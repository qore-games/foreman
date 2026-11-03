package games.qore.foreman.client.mc

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.mc.mixin.GameRendererAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.LevelTargetBundle
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket
import net.minecraft.resources.FileToIdConverter
import net.minecraft.resources.Identifier

/**
 * What differs between the Minecraft versions Foreman is built for, here for 26.2. Every client/versions/<minecraft>
 * has an Mc with the same members; the shared code calls them instead of the API that differs.
 */
object Mc {
    /** KeyboardHandler's method for the text an input method (IME) is composing, for ReloadKeyboardMixin. */
    const val TEXT_EDITING = "preeditCallback"

    /** The input type of keyboard key mappings. */
    val keyboard: InputConstants.Type = InputConstants.Type.KEYSYM

    /** Whether [key] (an InputConstants key) is held down. */
    fun isKeyDown(key: Int): Boolean = InputConstants.isKeyDown(Minecraft.getInstance().window, key)

    /** What the player typed after the slash, for a command suggestion request. */
    fun command(packet: ServerboundCommandSuggestionPacket): String = packet.command

    /** The post effect the client applies now (26.2 has one at a time: the spectated entity's). On the client thread. */
    fun postEffects(): PostEffectState {
        val minecraft = Minecraft.getInstance()
        val renderer = minecraft.gameRenderer
        val spectated = renderer.currentPostEffect()
        val active = spectated?.takeIf { (renderer as GameRendererAccessor).`foreman$effectActive`() }
        val loaded = active != null && minecraft.shaderManager.getPostChain(active, LevelTargetBundle.MAIN_TARGETS) != null
        return PostEffectState(
            requested = null,
            fromServer = emptyList(),
            spectated = spectated,
            applied = listOfNotNull(active.takeIf { loaded }),
            failed = listOfNotNull(active.takeUnless { loaded }),
        )
    }

    /** The post effect ids the loaded resource packs declare. On the client thread. */
    fun availablePostEffects(): List<Identifier> {
        val files = FileToIdConverter.json("post_effect")
        return files.listMatchingResources(Minecraft.getInstance().resourceManager).keys.map(files::fileToId)
    }
}
