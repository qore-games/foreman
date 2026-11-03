package games.qore.foreman.client.reload

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.platform.TextureUtil
import com.mojang.blaze3d.platform.Transparency
import games.qore.foreman.client.mixin.MipmapGeneratorAccessor
import net.minecraft.client.renderer.texture.MipmapStrategy
import net.minecraft.resources.Identifier
import net.minecraft.util.ARGB
import org.lwjgl.system.MemoryUtil
import kotlin.math.max
import kotlin.math.min

/**
 * Vanilla's mipmap generation, with the same pixels to the bit, faster: on arrays of the whole image rather than a
 * native read or write per pixel, and skipping work whose result is known. Big cutout textures (a model's 2048×2048)
 * took half a second each, on one thread.
 */
object FastMipmaps {
    /** Far beyond the rounding of four products and three sums of values up to 1. */
    private const val SURE = 1.0e-4f

    /** `MipmapGenerator.generateMipLevels`: a sprite's mip levels down to [newMipLevel], step by step as vanilla does. */
    @JvmStatic fun generateMipLevels(
        name: Identifier, currentMips: Array<NativeImage>, newMipLevel: Int, strategy: MipmapStrategy, alphaCutoffBias: Float, transparency: Transparency,
    ): Array<NativeImage> {
        val mipmapStrategy = if (strategy == MipmapStrategy.AUTO) (if (transparency.hasTransparent()) MipmapStrategy.CUTOUT else MipmapStrategy.MEAN) else strategy
        if (currentMips.size == 1 && !name.path.startsWith("item/")) {
            if (mipmapStrategy == MipmapStrategy.CUTOUT || mipmapStrategy == MipmapStrategy.STRICT_CUTOUT) solidify(currentMips[0])
            else if (mipmapStrategy == MipmapStrategy.DARK_CUTOUT) TextureUtil.fillEmptyAreasWithDarkColor(currentMips[0])
        }
        if (newMipLevel + 1 <= currentMips.size) return currentMips
        val result = arrayOfNulls<NativeImage>(newMipLevel + 1)
        result[0] = currentMips[0]
        val isCutoutMip = mipmapStrategy == MipmapStrategy.CUTOUT || mipmapStrategy == MipmapStrategy.STRICT_CUTOUT || mipmapStrategy == MipmapStrategy.DARK_CUTOUT
        val cutoutRef = if (mipmapStrategy == MipmapStrategy.STRICT_CUTOUT) 0.3f else 0.5f
        val originalCoverage = if (isCutoutMip) alphaTestCoverage(currentMips[0], cutoutRef, 1.0f) else 0.0f
        for (level in 1..newMipLevel) {
            result[level] = if (level < currentMips.size) currentMips[level] else halve(result[level - 1]!!, mipmapStrategy == MipmapStrategy.DARK_CUTOUT)
            if (isCutoutMip) scaleAlphaToCoverage(result[level]!!, originalCoverage, cutoutRef, alphaCutoffBias)
        }
        return result.requireNoNulls()
    }

    /** The next mip level: each pixel blends the 2×2 below it, with vanilla's blend ([ARGB] colors, as it gets them). */
    private fun halve(lastData: NativeImage, darkened: Boolean): NativeImage {
        val last = lastData.pixelsABGR
        val lastWidth = lastData.width
        val data = NativeImage(lastWidth shr 1, lastData.height shr 1, false)
        val width = data.width
        val pixels = IntArray(width * data.height)
        for (y in 0 until data.height) {
            for (x in 0 until width) {
                val top = x * 2 + y * 2 * lastWidth
                val color1 = ARGB.fromABGR(last[top])
                val color2 = ARGB.fromABGR(last[top + 1])
                val color3 = ARGB.fromABGR(last[top + lastWidth])
                val color4 = ARGB.fromABGR(last[top + lastWidth + 1])
                val color = if (darkened) MipmapGeneratorAccessor.`foreman$darkenedAlphaBlend`(color1, color2, color3, color4) else ARGB.meanLinear(color1, color2, color3, color4)
                pixels[x + y * width] = ARGB.toABGR(color)
            }
        }
        MemoryUtil.memIntBuffer(data.pointer, pixels.size).put(pixels)
        return data
    }

    /**
     * `MipmapGenerator.alphaTestCoverage`: the share of the texture's area the alpha test keeps, which vanilla measures
     * with 16 bilinear samples per texel. A texel whose four corners are all clearly above or below the cutoff has all
     * its samples on that side (the weights are exact and add up to 1), so only the texels an edge crosses are sampled.
     */
    private fun alphaTestCoverage(image: NativeImage, alphaRef: Float, alphaScale: Float): Float {
        val width = image.width
        val height = image.height
        // ABGR: the alpha is the top byte, as in ARGB.
        val pixels = image.pixelsABGR
        var coverage = 0.0f
        for (y in 0 until height - 1) {
            val row = y * width
            for (x in 0 until width - 1) {
                val alpha00 = Math.clamp(ARGB.alphaFloat(pixels[row + x]) * alphaScale, 0.0f, 1.0f)
                val alpha10 = Math.clamp(ARGB.alphaFloat(pixels[row + x + 1]) * alphaScale, 0.0f, 1.0f)
                val alpha01 = Math.clamp(ARGB.alphaFloat(pixels[row + width + x]) * alphaScale, 0.0f, 1.0f)
                val alpha11 = Math.clamp(ARGB.alphaFloat(pixels[row + width + x + 1]) * alphaScale, 0.0f, 1.0f)
                coverage += when {
                    min(min(alpha00, alpha10), min(alpha01, alpha11)) > alphaRef + SURE -> 1.0f
                    max(max(alpha00, alpha10), max(alpha01, alpha11)) < alphaRef - SURE -> 0.0f
                    else -> texelCoverage(alpha00, alpha10, alpha01, alpha11, alphaRef) / 16.0f
                }
            }
        }
        return coverage / ((width - 1) * (height - 1))
    }

    /**
     * `MipmapGenerator.scaleAlphaToCoverage`: the alpha scale (searched in 5 halvings) that keeps [desiredCoverage],
     * applied to every pixel with the cutoff bias.
     */
    private fun scaleAlphaToCoverage(image: NativeImage, desiredCoverage: Float, alphaRef: Float, alphaCutoffBias: Float) {
        var minAlphaScale = 0.0f
        var maxAlphaScale = 4.0f
        var alphaScale = 1.0f
        var bestAlphaScale = 1.0f
        var bestError = Float.MAX_VALUE
        for (i in 0 until 5) {
            val currentCoverage = alphaTestCoverage(image, alphaRef, alphaScale)
            val error = Math.abs(currentCoverage - desiredCoverage)
            if (error < bestError) {
                bestError = error
                bestAlphaScale = alphaScale
            }
            if (currentCoverage < desiredCoverage) minAlphaScale = alphaScale
            else if (currentCoverage > desiredCoverage) maxAlphaScale = alphaScale
            else break
            alphaScale = (minAlphaScale + maxAlphaScale) * 0.5f
        }
        scaleAlpha(image, bestAlphaScale, alphaCutoffBias)
    }

    /** Every pixel's alpha scaled and biased, as vanilla computes it ([ARGB.color] keeps the other channels, which ABGR has in the same bits). */
    private fun scaleAlpha(image: NativeImage, alphaScale: Float, alphaCutoffBias: Float) {
        val pixels = image.pixelsABGR
        for (i in pixels.indices) {
            val pixel = pixels[i]
            var alpha = ARGB.alphaFloat(pixel)
            alpha = alpha * alphaScale + alphaCutoffBias + 0.025f
            alpha = Math.clamp(alpha, 0.0f, 1.0f)
            pixels[i] = ARGB.color(alpha, pixel)
        }
        MemoryUtil.memIntBuffer(image.pointer, pixels.size).put(pixels)
    }

    /**
     * `TextureUtil.solidify`: every transparent pixel takes the color of its nearest opaque one (for mipmaps), found by
     * a breadth-first search from all opaque pixels, which reaches each pixel once. Same scan order, same neighbors in
     * the same order, so the same pixel wins every tie; opaque pixels are left as they are (vanilla writes them back).
     */
    private fun solidify(image: NativeImage) {
        val width = image.width
        val height = image.height
        val pixels = image.pixelsABGR
        val nearestColor = IntArray(width * height)
        val distances = IntArray(width * height).also { it.fill(Int.MAX_VALUE) }
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0
        for (x in 0 until width) {
            for (y in 0 until height) {
                val packed = x + y * width
                if (pixels[packed] ushr 24 != 0) {
                    distances[packed] = 0
                    nearestColor[packed] = pixels[packed]
                    queue[tail++] = packed
                }
            }
        }
        while (head < tail) {
            val packed = queue[head++]
            val x = packed % width
            val y = packed / width
            for (direction in 0 until 4) {
                val neighborX = x + DIRECTION_X[direction]
                val neighborY = y + DIRECTION_Y[direction]
                val neighbor = neighborX + neighborY * width
                if (neighborX >= 0 && neighborY >= 0 && neighborX < width && neighborY < height && distances[neighbor] > distances[packed] + 1) {
                    distances[neighbor] = distances[packed] + 1
                    nearestColor[neighbor] = nearestColor[packed]
                    queue[tail++] = neighbor
                }
            }
        }
        for (i in pixels.indices) {
            if (pixels[i] ushr 24 == 0) pixels[i] = nearestColor[i] and 0xFFFFFF
        }
        MemoryUtil.memIntBuffer(image.pointer, pixels.size).put(pixels)
    }

    /** `TextureUtil.DIRECTIONS`, in its order. */
    private val DIRECTION_X = intArrayOf(1, -1, 0, 0)
    private val DIRECTION_Y = intArrayOf(0, 0, 1, -1)

    /** Vanilla's 16 samples of a texel, in its order and arithmetic. */
    private fun texelCoverage(alpha00: Float, alpha10: Float, alpha01: Float, alpha11: Float, alphaRef: Float): Float {
        var texelCoverage = 0.0f
        for (subsampleY in 0 until 4) {
            val fy = (subsampleY + 0.5f) / 4.0f
            for (subsampleX in 0 until 4) {
                val fx = (subsampleX + 0.5f) / 4.0f
                val alpha = alpha00 * (1.0f - fx) * (1.0f - fy) + alpha10 * fx * (1.0f - fy) + alpha01 * (1.0f - fx) * fy + alpha11 * fx * fy
                if (alpha > alphaRef) texelCoverage++
            }
        }
        return texelCoverage
    }
}
