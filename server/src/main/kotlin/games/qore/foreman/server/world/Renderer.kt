package games.qore.foreman.server.world

import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.World
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.EnumMap
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.min

/**
 * Block renders the model can look at. Colors are the vanilla map colors of the blocks: coarse (all planks look alike)
 * but enough to judge shapes, layout, terrain and palette contrast.
 */
object Renderer {
    private val colors = EnumMap<Material, Color>(Material::class.java)
    private val SKY = Color(206, 226, 240)
    private val HIGHLIGHT = Color(230, 30, 30)

    private fun color(m: Material): Color = synchronized(colors) {
        colors.getOrPut(m) {
            val c = runCatching { m.createBlockData().mapColor }.getOrNull()
            if (c == null || (c.red == 0 && c.green == 0 && c.blue == 0)) Color(200, 215, 225) else Color(c.red, c.green, c.blue)
        }
    }

    private fun shade(c: Color, f: Double) = Color(
        min(255, (c.red * f).toInt()), min(255, (c.green * f).toInt()), min(255, (c.blue * f).toInt()), c.alpha
    )

    // ---- Top-down -----------------------------------------------------------------------------------------------

    class Top(val minX: Int, val minZ: Int, val heights: Array<IntArray>, val materials: Array<Array<Material>>, val water: Array<BooleanArray>)

    /** Main thread: the visible top block of each column (plants are skipped, leaves and water are kept). */
    fun readTop(world: World, minX: Int, minZ: Int, maxX: Int, maxZ: Int): Top {
        val w = maxX - minX + 1
        val d = maxZ - minZ + 1
        val heights = Array(w) { IntArray(d) }
        val materials = Array(w) { Array(d) { Material.AIR } }
        val water = Array(w) { BooleanArray(d) }
        for (i in 0 until w) for (j in 0 until d) {
            val x = minX + i
            val z = minZ + j
            var y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)
            var m = world.getType(x, y, z)
            while (y > world.minHeight && Blocks.isPlant(m)) m = world.getType(x, --y, z)
            if (Blocks.isLiquid(m)) {
                water[i][j] = true
                var floor = y
                while (floor > world.minHeight && Blocks.isLiquid(world.getType(x, floor, z))) floor--
                materials[i][j] = m
                // Water depth is kept in the low digits: it darkens the water like on maps.
                heights[i][j] = y * 1000 + min(10, y - floor)
                continue
            }
            heights[i][j] = y * 1000
            materials[i][j] = m
        }
        return Top(minX, minZ, heights, materials, water)
    }

    /** A numbered point drawn on a top view (issues of `check`). */
    class Marker(val x: Int, val z: Int, val label: String, val error: Boolean)

    /** [slope]: tint the ground by steepness (orange 2 blocks between neighbours, red 3+) to find cliffs. */
    fun renderTop(top: Top, highlights: List<Box>, markers: List<Marker> = emptyList(), slope: Boolean = false): BufferedImage {
        val w = top.heights.size
        val d = top.heights[0].size
        val s = max(1, min(16, 1024 / max(w, d)))
        val image = BufferedImage(w * s, d * s, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()

        for (i in 0 until w) for (j in 0 until d) {
            val base = color(top.materials[i][j])
            val h = top.heights[i][j] / 1000
            val f = if (top.water[i][j]) {
                1.0 - (top.heights[i][j] % 1000) * 0.04
            } else {
                // Classic map relief: lit from the north-west.
                val north = if (j > 0) top.heights[i][j - 1] / 1000 else h
                val west = if (i > 0) top.heights[i - 1][j] / 1000 else h
                val diff = (h - north) + (h - west)
                (1.0 + diff * 0.08).coerceIn(0.6, 1.25)
            }
            g.color = shade(base, f)
            if (slope && !top.water[i][j] && Blocks.isNaturalGround(top.materials[i][j])) {
                // Terrain only: steps between natural ground columns (builds keep their colors).
                val steep = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1).maxOf { (di, dj) ->
                    val a = i + di
                    val b = j + dj
                    if (a !in 0 until w || b !in 0 until d || top.water[a][b] || !Blocks.isNaturalGround(top.materials[a][b])) 0
                    else kotlin.math.abs(h - top.heights[a][b] / 1000)
                }
                g.color = when {
                    steep >= 3 -> Color(225, 30, 30)
                    steep == 2 -> Color(245, 150, 30)
                    else -> shade(Color((base.red + 200) / 2, (base.green + 200) / 2, (base.blue + 200) / 2), f)
                }
            }
            g.fillRect(i * s, j * s, s, s)
        }

        // Grid every 10 blocks, stronger every 50, on world coordinates.
        for (i in 0 until w) {
            val x = top.minX + i
            if (x % 10 == 0) {
                g.color = if (x % 50 == 0) Color(0, 0, 0, 110) else Color(0, 0, 0, 45)
                g.fillRect(i * s, 0, 1, d * s)
            }
        }
        for (j in 0 until d) {
            val z = top.minZ + j
            if (z % 10 == 0) {
                g.color = if (z % 50 == 0) Color(0, 0, 0, 110) else Color(0, 0, 0, 45)
                g.fillRect(0, j * s, w * s, 1)
            }
        }

        g.color = HIGHLIGHT
        g.stroke = BasicStroke(2f)
        highlights.forEach { b ->
            g.drawRect((b.min.x() - top.minX) * s, (b.min.z() - top.minZ) * s, b.sizeX * s, b.sizeZ * s)
        }

        val radius = max(5, min(9, s * 2))
        markers.forEach { m ->
            val cx = (m.x - top.minX) * s + s / 2
            val cz = (m.z - top.minZ) * s + s / 2
            g.color = if (m.error) HIGHLIGHT else Color(240, 160, 0)
            g.stroke = BasicStroke(2.5f)
            g.drawOval(cx - radius, cz - radius, radius * 2, radius * 2)
        }

        labels(g) {
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            markers.forEach { m -> outlined(g, m.label, (m.x - top.minX) * s + s / 2 + radius, (m.z - top.minZ) * s + s / 2 - radius + 2) }
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            for (i in 0 until w) {
                val x = top.minX + i
                if (x % 10 == 0) outlined(g, "x$x", i * s + 2, 12)
            }
            for (j in 0 until d) {
                val z = top.minZ + j
                if (z % 10 == 0 && j > 2) outlined(g, "z$z", 2, j * s - 2)
            }
        }
        g.dispose()
        return image
    }

    // ---- Isometric ----------------------------------------------------------------------------------------------

    enum class Corner(val description: String) {
        SE("camera at the south-east corner looking north-west: right-facing sides are east (+x), left-facing sides are south (+z)"),
        SW("camera at the south-west corner looking north-east: right-facing sides are south (+z), left-facing sides are west (-x)"),
        NW("camera at the north-west corner looking south-east: right-facing sides are west (-x), left-facing sides are north (-z)"),
        NE("camera at the north-east corner looking south-west: right-facing sides are north (-z), left-facing sides are east (+x)");
    }

    class Volume(val box: Box, val blocks: Array<Material>) {
        operator fun get(x: Int, y: Int, z: Int): Material =
            if (!box.contains(x, y, z)) Material.AIR
            else blocks[((x - box.min.x()) * box.sizeY + (y - box.min.y())) * box.sizeZ + (z - box.min.z())]
    }

    /** Main thread. */
    fun readVolume(world: World, box: Box): Volume {
        val blocks = Array(box.volume.toInt()) { Material.AIR }
        var n = 0
        for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
            blocks[n++] = world.getType(x, y, z)
        }
        return Volume(box, blocks)
    }

    private enum class Shape(val height: Double, val inset: Double) {
        FULL(1.0, 0.0), SLAB(0.5, 0.0), THIN(0.07, 0.0), SMALL(0.6, 0.3), SKIP(0.0, 0.0)
    }

    private fun shape(m: Material): Shape = when {
        m.isAir -> Shape.SKIP
        m == Material.SHORT_GRASS || m == Material.TALL_GRASS || m == Material.FERN || m == Material.LARGE_FERN ||
            m == Material.BARRIER || m == Material.LIGHT || m == Material.STRUCTURE_VOID -> Shape.SKIP
        m.name.endsWith("_SLAB") -> Shape.SLAB
        m.name.endsWith("_CARPET") || m == Material.SNOW || m.name.endsWith("_PRESSURE_PLATE") || m.name.endsWith("RAIL") -> Shape.THIN
        !m.isSolid && !Blocks.isLiquid(m) -> Shape.SMALL
        else -> Shape.FULL
    }

    fun renderIso(volume: Volume, corner: Corner, highlights: List<Box>): BufferedImage {
        val box = volume.box
        // (a, b) is (x, z) turned so that the camera always sits at +a, +b; nearer blocks have a larger a + b + y.
        val (sizeA, sizeB) = when (corner) {
            Corner.SE, Corner.NW -> box.sizeX to box.sizeZ
            Corner.SW, Corner.NE -> box.sizeZ to box.sizeX
        }
        val sizeY = box.sizeY
        fun world(a: Int, b: Int): Pair<Int, Int> = when (corner) {
            Corner.SE -> (box.min.x() + a) to (box.min.z() + b)
            Corner.NW -> (box.max.x() - a) to (box.max.z() - b)
            Corner.SW -> (box.max.x() - b) to (box.min.z() + a)
            Corner.NE -> (box.min.x() + b) to (box.max.z() - a)
        }
        fun local(x: Double, z: Double): Pair<Double, Double> = when (corner) {
            Corner.SE -> (x - box.min.x()) to (z - box.min.z())
            Corner.NW -> (box.max.x() + 1 - x) to (box.max.z() + 1 - z)
            Corner.SW -> (z - box.min.z()) to (box.max.x() + 1 - x)
            Corner.NE -> (box.max.z() + 1 - z) to (x - box.min.x())
        }

        val s = max(2, min(16, min(1400 / (sizeA + sizeB), 1400 / (sizeY + (sizeA + sizeB) / 2))))
        val width = (sizeA + sizeB) * s
        val height = (sizeA + sizeB) * s / 2 + sizeY * s
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF)
        g.color = SKY
        g.fillRect(0, 0, width, height)

        fun px(a: Double, b: Double) = ((a - b + sizeB) * s).toInt()
        fun py(a: Double, b: Double, y: Double) = ((a + b) * s / 2 + (sizeY - y) * s).toInt()
        fun quad(points: List<Triple<Double, Double, Double>>) =
            Polygon(points.map { px(it.first, it.second) }.toIntArray(), points.map { py(it.first, it.second, it.third) }.toIntArray(), 4)

        fun at(a: Int, b: Int, y: Int): Material {
            if (a >= sizeA || b >= sizeB || y >= sizeY) return Material.AIR
            val (x, z) = world(a, b)
            return volume[x, box.min.y() + y, z]
        }

        val outline = s >= 6
        for (sum in 0..(sizeA + sizeB + sizeY - 3)) {
            for (y in 0 until sizeY) for (a in 0 until sizeA) {
                val b = sum - a - y
                if (b < 0 || b >= sizeB) continue
                val m = at(a, b, y)
                val shape = shape(m)
                if (shape == Shape.SKIP) continue

                val liquid = Blocks.isLiquid(m)
                val up = at(a, b, y + 1)
                val coveredUp = up.isOccluding || (liquid && Blocks.isLiquid(up))
                val coveredA = at(a + 1, b, y).isOccluding || liquid
                val coveredB = at(a, b + 1, y).isOccluding || liquid
                if (shape == Shape.FULL && coveredUp && coveredA && coveredB) continue

                val base = color(m)
                val a0 = a + shape.inset
                val a1 = a + 1 - shape.inset
                val b0 = b + shape.inset
                val b1 = b + 1 - shape.inset
                val y0 = y.toDouble()
                val y1 = y + if (liquid) 0.9 else shape.height
                // Faint height tint so stacked layers read apart.
                val tint = 0.9 + 0.1 * y / max(1, sizeY - 1)

                val faces = buildList {
                    if (!coveredUp || shape != Shape.FULL) add(quad(listOf(Triple(a0, b0, y1), Triple(a1, b0, y1), Triple(a1, b1, y1), Triple(a0, b1, y1))) to 1.0)
                    if (!liquid && (!coveredA || shape != Shape.FULL)) add(quad(listOf(Triple(a1, b0, y0), Triple(a1, b1, y0), Triple(a1, b1, y1), Triple(a1, b0, y1))) to 0.78)
                    if (!liquid && (!coveredB || shape != Shape.FULL)) add(quad(listOf(Triple(a0, b1, y0), Triple(a1, b1, y0), Triple(a1, b1, y1), Triple(a0, b1, y1))) to 0.62)
                }
                faces.forEach { (polygon, light) ->
                    g.color = shade(base, light * tint)
                    g.fillPolygon(polygon)
                    if (outline) {
                        g.color = shade(base, light * tint * 0.82)
                        g.drawPolygon(polygon)
                    }
                }
            }
        }

        g.color = HIGHLIGHT
        g.stroke = BasicStroke(2f)
        highlights.forEach { h ->
            val (la, lb) = local(h.min.x().toDouble(), h.min.z().toDouble())
            val (ha, hb) = local(h.max.x() + 1.0, h.max.z() + 1.0)
            val a0 = min(la, ha); val a1 = max(la, ha)
            val b0 = min(lb, hb); val b1 = max(lb, hb)
            val y0 = (h.min.y() - box.min.y()).toDouble()
            val y1 = (h.max.y() + 1 - box.min.y()).toDouble()
            val corners = listOf(a0 to b0, a1 to b0, a1 to b1, a0 to b1)
            for (k in 0 until 4) {
                val (ca, cb) = corners[k]
                val (na, nb) = corners[(k + 1) % 4]
                g.drawLine(px(ca, cb), py(ca, cb, y0), px(na, nb), py(na, nb, y0))
                g.drawLine(px(ca, cb), py(ca, cb, y1), px(na, nb), py(na, nb, y1))
                g.drawLine(px(ca, cb), py(ca, cb, y0), px(ca, cb), py(ca, cb, y1))
            }
        }

        labels(g) {
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 12)
            val (x0, z0) = world(0, 0)
            val (x1, z1) = world(sizeA - 1, sizeB - 1)
            outlined(g, "far corner x$x0 z$z0 (top)", 4, 14)
            outlined(g, "near corner x$x1 z$z1 (bottom)", 4, height - 6)
        }
        g.dispose()
        return image
    }

    // ---- Helpers ------------------------------------------------------------------------------------------------

    /** Text needs fonts, which a stripped-down JVM may not have: renders without labels then. */
    private inline fun labels(g: java.awt.Graphics2D, block: () -> Unit) {
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            block()
        } catch (_: Throwable) {
        }
    }

    private fun outlined(g: java.awt.Graphics2D, text: String, x: Int, y: Int) {
        g.color = Color.WHITE
        for (dx in -1..1) for (dy in -1..1) g.drawString(text, x + dx, y + dy)
        g.color = Color.BLACK
        g.drawString(text, x, y)
    }

    fun png(image: BufferedImage): ByteArray = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
}
