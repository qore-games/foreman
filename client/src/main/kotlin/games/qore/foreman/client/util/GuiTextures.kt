package games.qore.foreman.client.util

import com.mojang.blaze3d.platform.NativeImage
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier

/** An image uploaded for the GUI, [width] × [height] pixels: `Ui.image(id, …, width, height)` draws it. */
class GuiTexture(val id: Identifier, val width: Int, val height: Int)

/**
 * Images made at run time (renders a server sends) as GUI textures, `foreman:dynamic/<name>/<n>`. On the client thread;
 * each stays in the game's memory until it is [release]d.
 */
object GuiTextures {
    private var next = 0

    /** Uploads [image], which the texture owns from now on. */
    fun register(image: NativeImage, name: String): GuiTexture {
        val id = Identifier.fromNamespaceAndPath("foreman", "dynamic/$name/${next++}")
        Minecraft.getInstance().textureManager.register(id, DynamicTexture({ id.toString() }, image))
        return GuiTexture(id, image.width, image.height)
    }

    fun release(texture: GuiTexture) = Minecraft.getInstance().textureManager.release(texture.id)
}
