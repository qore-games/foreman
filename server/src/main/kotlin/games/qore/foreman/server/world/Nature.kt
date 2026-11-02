package games.qore.foreman.server.world

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.util.TreeGenerator
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.block.data.Bisected
import kotlin.math.floor

/** Plants, trees and noise for the terrain tools. */
object Nature {
    const val PLAINS_PLANTS = "80%short_grass,6%tall_grass,4%fern,3%dandelion,3%poppy,2%oxeye_daisy,2%cornflower"

    fun treeType(name: String): TreeGenerator.TreeType =
        TreeGenerator.lookup(name) ?: throw ToolError("Unknown tree type '$name' (oak, birch, spruce, dark_oak, cherry, acacia, jungle_bush, pine…)")

    /** Places one block of [pattern] at [pos] if it is air; a double plant (tall_grass, sunflower…) gets its upper half. */
    fun plant(session: EditSession, pos: BlockVector3, pattern: Pattern): Boolean {
        if (!session.getBlock(pos).blockType.material.isAir) return false
        val block = pattern.applyBlock(pos)
        if (block.blockType.material.isAir) return false
        val data = BukkitAdapter.adapt(block.toImmutableState())
        if (data is Bisected) {
            val up = pos.add(0, 1, 0)
            if (!session.getBlock(up).blockType.material.isAir) return false
            data.half = Bisected.Half.BOTTOM
            session.setBlock(pos, BukkitAdapter.adapt(data))
            val upper = data.clone() as Bisected
            upper.half = Bisected.Half.TOP
            session.setBlock(up, BukkitAdapter.adapt(upper))
        } else {
            session.setBlock(pos, block)
        }
        return true
    }

    /** Grows a tree whose trunk starts at [pos] (the block above the ground). */
    fun tree(session: EditSession, pos: BlockVector3, type: TreeGenerator.TreeType): Boolean = type.generate(session, pos)

    /** Smooth 2D value noise in 0..1, [scale] blocks per cell. */
    fun noise(seed: Long, x: Double, z: Double, scale: Double): Double {
        val fx = x / scale
        val fz = z / scale
        val x0 = floor(fx).toInt()
        val z0 = floor(fz).toInt()
        val tx = smooth(fx - x0)
        val tz = smooth(fz - z0)
        fun v(ix: Int, iz: Int) = hash(seed, ix, iz)
        val a = v(x0, z0) + (v(x0 + 1, z0) - v(x0, z0)) * tx
        val b = v(x0, z0 + 1) + (v(x0 + 1, z0 + 1) - v(x0, z0 + 1)) * tx
        return a + (b - a) * tz
    }

    /** Two octaves of [noise]. */
    fun fractal(seed: Long, x: Double, z: Double, scale: Double) =
        (noise(seed, x, z, scale) * 2 + noise(seed + 1, x, z, scale / 2)) / 3

    fun random(seed: Long, x: Int, z: Int) = hash(seed + 7, x, z)

    /** Smooth 3D-ish noise in 0..1 for clustered patches on walls and floors (weathering, moss). */
    fun noise3(seed: Long, x: Int, y: Int, z: Int, scale: Double) =
        (noise(seed, x.toDouble(), z.toDouble() + y * 0.37, scale) + noise(seed + 11, x.toDouble() + z * 0.21, y.toDouble(), scale) +
            noise(seed + 23, z.toDouble(), y.toDouble() - x * 0.13, scale)) / 3

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private fun hash(seed: Long, x: Int, z: Int): Double {
        var h = seed xor (x.toLong() * 0x9E3779B97F4A7C15uL.toLong()) xor (z.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong())
        h = (h xor (h ushr 33)) * -0xae502812aa7333L
        h = (h xor (h ushr 33)) * -0x3b314601e57a13adL
        h = h xor (h ushr 33)
        return (h ushr 11).toDouble() / (1L shl 53).toDouble()
    }
}
