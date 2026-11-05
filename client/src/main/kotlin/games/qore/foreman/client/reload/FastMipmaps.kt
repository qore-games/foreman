package games.qore.foreman.client.reload

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.Transparency
import games.qore.fastload.Mipmaps
import net.minecraft.client.renderer.texture.MipmapStrategy
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil

/**
 * Vanilla's mipmap generation, with the same pixels to the bit, faster: fastload's [Mipmaps] on the sprite's pixels,
 * read once from native memory, and written back only where they changed.
 */
object FastMipmaps {
    /** `MipmapGenerator.generateMipLevels`: a sprite's mip levels down to [newMipLevel], as vanilla makes them. */
    @JvmStatic fun generateMipLevels(
        name: Identifier, currentMips: Array<NativeImage>, newMipLevel: Int, strategy: MipmapStrategy, alphaCutoffBias: Float, transparency: Transparency,
    ): Array<NativeImage> {
        val mipmapStrategy = Mipmaps.strategy(strategy.name)
        val hasTransparent = transparency.hasTransparent()
        if (Mipmaps.unchanged(currentMips.size, newMipLevel, mipmapStrategy, hasTransparent, name.path)) return currentMips
        val width = currentMips[0].width
        val height = currentMips[0].height
        val pixels = Array(currentMips.size) { currentMips[it].pixelsABGR }
        val levels = Mipmaps.generateMipLevels(pixels, width, height, newMipLevel, mipmapStrategy, hasTransparent, name.path, alphaCutoffBias)
        return Array(levels.size) { level ->
            when {
                level >= currentMips.size -> NativeImage(width shr level, height shr level, false).also { write(it, levels[level]) }
                levels[level] !== pixels[level] -> currentMips[level].also { write(it, levels[level]) }
                else -> currentMips[level]
            }
        }
    }

    private fun write(image: NativeImage, pixels: IntArray) {
        MemoryUtil.memIntBuffer(image.pointer, pixels.size).put(pixels)
    }
}
