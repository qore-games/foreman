package games.qore.foreman.server.world

import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.BlockFace
import org.bukkit.block.data.Bisected
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.image.BufferedImage
import kotlin.math.max
import kotlin.math.min

/**
 * Floor plans: one storey of a build seen from above, the way an architect draws it. Walls, floors, windows, stairs
 * with their climbing direction, doors with their hinge and swing, furniture with its model outline and the side it
 * faces, and the spots players can't reach.
 */
object Plans {
    private val WALL = Color(52, 52, 58)
    private val VOID = Color(18, 18, 22)
    private val OUTSIDE = Color(150, 160, 150)
    private val GLASS = Color(110, 170, 230)
    private val DOOR = Color(170, 110, 50)
    private val FURNITURE = Color(235, 140, 30)
    private val UNREACHABLE = Color(230, 40, 40, 110)
    private val LOW = Color(120, 120, 128)

    private fun mapColor(m: Material): Color = runCatching { m.createBlockData().mapColor }.getOrNull()
        ?.let { Color(it.red, it.green, it.blue) } ?: Color(200, 200, 200)

    private fun lighten(c: Color, f: Double) = Color(
        (c.red + (255 - c.red) * f).toInt(), (c.green + (255 - c.green) * f).toInt(), (c.blue + (255 - c.blue) * f).toInt()
    )

    /**
     * Draws storey [y] (the feet level: the floor is y - 1) of [grid]. [reached] marks the walk nodes reachable from
     * outside, null when not computed.
     */
    fun render(grid: Grid, walk: Walk, reached: BooleanArray?, y: Int, furniture: List<Furniture.Placed>, visuals: Map<java.util.UUID, Baked>): BufferedImage {
        val b = grid.box
        val w = b.sizeX
        val d = b.sizeZ
        val s = max(10, min(36, 1100 / max(w, d)))
        val margin = 28
        val legend = 40
        val image = BufferedImage(w * s + margin, d * s + margin + legend, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, image.width, image.height)
        fun px(x: Double) = (margin + (x - b.min.x()) * s).toInt()
        fun pz(z: Double) = (margin + (z - b.min.z()) * s).toInt()

        for (x in b.min.x()..b.max.x()) for (z in b.min.z()..b.max.z()) {
            val cx = px(x.toDouble())
            val cz = pz(z.toDouble())
            val feet = grid.solid(x, y, z)
            val head = grid.solid(x, y + 1, z)
            val node = walk.column(x, z).firstOrNull { walk.nodes[it].y == y }
            val near = walk.column(x, z).firstOrNull { kotlin.math.abs(walk.nodes[it].y - y) <= 1 }
            val headType = grid.type(x, y + 1, z)
            g.color = when {
                feet.full && head.full -> if (Tag.IMPERMEABLE.isTagged(headType) || headType.name.contains("GLASS")) GLASS else WALL
                (headType.name.endsWith("_PANE") || headType == Material.IRON_BARS) && (feet.full || !feet.empty) -> GLASS
                node != null -> lighten(mapColor(walk.nodes[node].on), 0.35)
                near != null -> lighten(mapColor(walk.nodes[near].on), 0.1)
                !feet.empty || !head.empty -> LOW
                grid.solid(x, y - 1, z).empty && grid.solid(x, y - 2, z).empty -> VOID
                else -> OUTSIDE
            }
            g.fillRect(cx, cz, s, s)
            if (!feet.empty && !feet.full || (!head.empty && !head.full && feet.empty)) {
                // Fences, panes, low walls: a cross.
                g.color = LOW
                g.stroke = BasicStroke(max(1f, s / 8f))
                g.drawLine(cx + s / 2, cz + 2, cx + s / 2, cz + s - 2)
                g.drawLine(cx + 2, cz + s / 2, cx + s - 2, cz + s / 2)
            }
            if (reached != null && node != null && !reached[node]) {
                g.color = UNREACHABLE
                g.fillRect(cx, cz, s, s)
            }
            // Stairs: an arrow toward the side they climb to.
            val stairs = (grid.data(x, y, z) as? Stairs ?: grid.data(x, y - 1, z) as? Stairs)?.takeIf { it.half == Bisected.Half.BOTTOM }
            if (stairs != null) arrow(g, cx + s / 2.0, cz + s / 2.0, stairs.facing, s * 0.38, Color(40, 40, 40))
            g.color = Color(0, 0, 0, 25)
            g.stroke = BasicStroke(1f)
            g.drawRect(cx, cz, s, s)
        }

        // Doors: the leaf on its hinge side and its swing.
        for (x in b.min.x()..b.max.x()) for (z in b.min.z()..b.max.z()) {
            val door = grid.data(x, y, z) as? Door ?: continue
            if (door.half != Bisected.Half.BOTTOM) continue
            drawDoor(g, px(x.toDouble()), pz(z.toDouble()), s, door)
        }

        // Furniture: barrier cells hatched, the model's outline, the side it faces.
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, max(9, min(12, s / 2)))
        for (f in furniture) {
            val v = visuals[f.entity]
            val onStorey = f.y in y - 1..y + 1 || (v != null && v.min.y < y + 2 && v.max.y > y)
            if (!onStorey) continue
            g.color = Color(FURNITURE.red, FURNITURE.green, FURNITURE.blue, 120)
            f.cells.filter { it.y() in y..y + 1 }.forEach { c -> g.fillRect(px(c.x().toDouble()) + 1, pz(c.z().toDouble()) + 1, s - 2, s - 2) }
            g.color = FURNITURE
            g.stroke = BasicStroke(2f)
            if (v != null) g.drawRect(px(v.min.x), pz(v.min.z), max(2, px(v.max.x) - px(v.min.x)), max(2, pz(v.max.z) - pz(v.min.z)))
            else g.drawRect(px(f.x.toDouble()) + 2, pz(f.z.toDouble()) + 2, s - 4, s - 4)
            arrow(g, px(f.x + 0.5).toDouble(), pz(f.z + 0.5).toDouble(), Checks.front(f.yaw), s * 0.45, Color(160, 70, 0))
            labels(g) { outlined(g, f.id, px(f.x.toDouble()) + 2, pz(f.z.toDouble()) + s - 2) }
        }

        labels(g) {
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 11)
            for (x in b.min.x()..b.max.x()) if (x % 5 == 0) outlined(g, "$x", px(x.toDouble()), 12)
            for (z in b.min.z()..b.max.z()) if (z % 5 == 0) outlined(g, "$z", 2, pz(z.toDouble()) + 10)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
            outlined(
                g, "Storey y=$y (floor y=${y - 1}), north up. Dark = wall, blue = window, light = floor, grey = other level/outside, " +
                    "black = no floor. Arrows: stairs climb / furniture front.", 4, image.height - legend + 16,
            )
            outlined(g, "Orange = furniture (filled: collision, outline: model). Red tint = can't be reached. Doors: leaf on the hinge, arc = swing.", 4, image.height - legend + 32)
        }
        g.dispose()
        return image
    }

    private fun drawDoor(g: Graphics2D, cx: Int, cz: Int, s: Int, door: Door) {
        // A closed door is a thin leaf on the side of the block opposite to its facing.
        val f = door.facing
        val t = max(3, s / 6)
        g.color = DOOR
        val (lx, lz, lw, lh) = when (f) {
            BlockFace.NORTH -> listOf(cx, cz + s - t, s, t)
            BlockFace.SOUTH -> listOf(cx, cz, s, t)
            BlockFace.EAST -> listOf(cx, cz, t, s)
            else -> listOf(cx + s - t, cz, t, s)
        }
        g.fillRect(lx, lz, lw, lh)
        // Hinge: left/right as seen by someone facing the same way as the door.
        val right = when (f) {
            BlockFace.NORTH -> BlockFace.EAST; BlockFace.EAST -> BlockFace.SOUTH; BlockFace.SOUTH -> BlockFace.WEST; else -> BlockFace.NORTH
        }
        val hingeSide = if (door.hinge == Door.Hinge.RIGHT) right else right.oppositeFace
        val hx = lx + lw / 2.0 + hingeSide.modX * (s / 2.0 - t / 2.0) * (if (lw > lh) 1 else 0)
        val hz = lz + lh / 2.0 + hingeSide.modZ * (s / 2.0 - t / 2.0) * (if (lh > lw) 1 else 0)
        g.color = Color(90, 50, 10)
        g.fillOval((hx - t).toInt(), (hz - t).toInt(), t * 2, t * 2)
        g.color = Color(170, 110, 50, 160)
        g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 3f), 0f)
        // The leaf swings into the block, toward its facing side.
        val r = s - t / 2.0
        val start = Math.toDegrees(kotlin.math.atan2(-(lz + lh / 2.0 - hz), (lx + lw / 2.0 - hx)))
        val toward = Math.toDegrees(kotlin.math.atan2(f.modZ.toDouble(), f.modX.toDouble()).let { -it })
        var extent = toward - start
        while (extent > 180) extent -= 360
        while (extent < -180) extent += 360
        g.draw(Arc2D.Double(hx - r, hz - r, r * 2, r * 2, start, extent, Arc2D.OPEN))
        g.stroke = BasicStroke(1f)
    }

    private fun arrow(g: Graphics2D, x: Double, z: Double, face: BlockFace, length: Double, color: Color) {
        val ex = x + face.modX * length
        val ez = z + face.modZ * length
        val sx = x - face.modX * length * 0.6
        val sz = z - face.modZ * length * 0.6
        g.color = color
        g.stroke = BasicStroke(2f)
        g.drawLine(sx.toInt(), sz.toInt(), ex.toInt(), ez.toInt())
        val px = -face.modZ * length * 0.4
        val pz = face.modX * length * 0.4
        val bx = ex - face.modX * length * 0.45
        val bz = ez - face.modZ * length * 0.45
        g.drawLine(ex.toInt(), ez.toInt(), (bx + px).toInt(), (bz + pz).toInt())
        g.drawLine(ex.toInt(), ez.toInt(), (bx - px).toInt(), (bz - pz).toInt())
    }

    private inline fun labels(g: Graphics2D, block: () -> Unit) {
        try {
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            block()
        } catch (_: Throwable) {
        }
    }

    private fun outlined(g: Graphics2D, text: String, x: Int, y: Int) {
        g.color = Color.WHITE
        for (dx in -1..1) for (dy in -1..1) g.drawString(text, x + dx, y + dy)
        g.color = Color.BLACK
        g.drawString(text, x, y)
    }
}
