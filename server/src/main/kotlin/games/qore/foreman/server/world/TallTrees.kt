package games.qore.foreman.server.world

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

/**
 * Trees for playable areas: tall trunks and no leaves below 5 blocks, so players walk under the canopies without
 * snagging. Leaves are persistent. Vanilla trees are for the margins people can't reach.
 */
object TallTrees {
    val KINDS = listOf("tall_spruce", "tall_birch", "tall_oak", "snag")

    /** Writes one tree, trunk base at [x], [y], [z] (the block above the ground), through [set] (x, y, z, block state). */
    fun grow(kind: String, x: Int, y: Int, z: Int, rng: Random, set: (Int, Int, Int, String) -> Unit) {
        when (kind) {
            "tall_spruce" -> {
                val h = rng.nextInt(10, 16)
                for (yy in y until y + h) set(x, yy, z, "spruce_log")
                val bottom = y + rng.nextInt(5, 7)
                val top = y + h + 1
                for (yy in bottom..top) {
                    val t = (yy - bottom).toDouble() / max(1, top - bottom)
                    var r = 3.2 * (1 - t) + 0.6
                    if ((yy - bottom) % 3 == 2) r *= 0.6 // the notches of a spruce silhouette
                    disc(x, yy, z, r, "spruce_leaves[persistent=true]", rng, 0.12, yy < y + h, set)
                }
            }
            "tall_birch" -> {
                val h = rng.nextInt(9, 13)
                for (yy in y until y + h) set(x, yy, z, "birch_log")
                val bottom = y + rng.nextInt(5, 7)
                blob(x, (bottom + y + h) / 2 + 1, z, 2.6, (y + h + 1 - bottom) / 2.0 + 0.5, "birch_leaves[persistent=true]", rng, 0.15, null, y + h - 1, set)
            }
            "tall_oak" -> {
                val dark = rng.nextBoolean()
                val log = if (dark) "dark_oak_log" else "oak_log"
                val leaf = if (dark) "dark_oak_leaves[persistent=true]" else "oak_leaves[persistent=true]"
                val h = rng.nextInt(7, 10)
                for (yy in y until y + h) set(x, yy, z, log)
                val crowns = arrayListOf(listOf(x, y + h, z) to 3.2)
                repeat(rng.nextInt(2, 4)) {
                    val a = rng.nextDouble(0.0, Math.PI * 2)
                    val bx = x + (cos(a) * 2.5).roundToInt()
                    val bz = z + (sin(a) * 2.5).roundToInt()
                    val by = y + h - rng.nextInt(1, 3)
                    branch(x, by - 1, z, bx, by, bz, log, set)
                    crowns += listOf(bx, by + 1, bz) to 2.4
                }
                for ((c, r) in crowns) blob(c[0], c[1], c[2], r, r * 0.75, leaf, rng, 0.18, y + 5, null, set)
            }
            "snag" -> {
                val log = listOf("spruce_log", "dark_oak_log", "stripped_spruce_log", "stripped_oak_log").random(rng)
                val h = rng.nextInt(6, 12)
                for (yy in y until y + h) set(x, yy, z, log)
                repeat(rng.nextInt(1, 4)) {
                    val a = rng.nextDouble(0.0, Math.PI * 2)
                    val by = y + rng.nextInt(4, h)
                    branch(x, by, z, x + (cos(a) * 2).roundToInt(), by + 1, z + (sin(a) * 2).roundToInt(), log, set)
                }
            }
            else -> throw IllegalArgumentException(kind)
        }
    }

    private fun disc(x: Int, y: Int, z: Int, r: Double, block: String, rng: Random, holes: Double, skipCenter: Boolean, set: (Int, Int, Int, String) -> Unit) {
        val ri = ceil(r).toInt()
        for (dx in -ri..ri) for (dz in -ri..ri) {
            if (skipCenter && dx == 0 && dz == 0) continue
            val d = hypot(dx.toDouble(), dz.toDouble())
            if (d <= r && !(d > r - 1 && rng.nextDouble() < holes + 0.25)) set(x + dx, y, z + dz, block)
        }
    }

    private fun blob(
        x: Int, y: Int, z: Int, r: Double, ry: Double, block: String, rng: Random, holes: Double, minY: Int?, trunkTop: Int?,
        set: (Int, Int, Int, String) -> Unit,
    ) {
        val ri = ceil(r).toInt()
        val ryi = ceil(ry).toInt()
        for (dy in -ryi..ryi) {
            if (minY != null && y + dy < minY) continue
            for (dx in -ri..ri) for (dz in -ri..ri) {
                if (trunkTop != null && dx == 0 && dz == 0 && y + dy <= trunkTop) continue
                val e = (dx / r) * (dx / r) + (dz / r) * (dz / r) + (dy / ry) * (dy / ry)
                if (e <= 1 && !(e > 0.6 && rng.nextDouble() < holes + 0.2)) set(x + dx, y + dy, z + dz, block)
            }
        }
    }

    private fun branch(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, log: String, set: (Int, Int, Int, String) -> Unit) {
        val n = max(max(abs(x1 - x0), abs(z1 - z0)), 1)
        val axis = if (abs(x1 - x0) >= abs(z1 - z0)) "x" else "z"
        for (i in 1..n) {
            set(x0 + ((x1 - x0) * i.toDouble() / n).roundToInt(), y0 + ((y1 - y0) * i.toDouble() / n).roundToInt(), z0 + ((z1 - z0) * i.toDouble() / n).roundToInt(), "$log[axis=$axis]")
        }
    }
}
