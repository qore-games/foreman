package games.qore.foreman.client.locations

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.config.Visibility
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.render.OverlayLayer
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.data.LocationType
import games.qore.foreman.api.data.RenderShape
import net.minecraft.world.phys.Vec3
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** Where a location is drawn and grabbed: a block location at the bottom center of its block, a precise one where it is. */
fun LocationEntry.anchor(): Vec3 =
    if (type == LocationType.BLOCK) Vec3(floor(x) + 0.5, floor(y), floor(z) + 0.5) else Vec3(x, y, z)

/** The inverse of [anchor]: the entry moved so its anchor is at [anchor]. */
fun LocationEntry.atAnchor(anchor: Vec3): LocationEntry =
    if (type == LocationType.BLOCK) copy(x = floor(anchor.x), y = floor(anchor.y), z = floor(anchor.z))
    else copy(x = anchor.x, y = anchor.y, z = anchor.z)

/** Draws the visible locations of the current dimension with their shape, color, label and facing. */
object LocationLayer : OverlayLayer {
    private const val MAX_DISTANCE = 512.0
    private const val DETAIL_DISTANCE = 64.0

    override fun draw(draw: OverlayDraw) {
        val settings = Config.settings.locations
        val o = Config.settings.overlays
        // The editor needs to see what it edits, whatever the overlay settings.
        val editing = LocationEditor.enabled
        if (!editing && (!o.enabled || o.locations == Visibility.OFF)) return
        val range = if (editing || o.locations == Visibility.ALWAYS) MAX_DISTANCE else o.nearDistance * 3.0
        val onTop = settings.throughBlocks
        for (located in LocationStore.visible(draw.dimension)) {
            val entry = located.entry
            val anchor = entry.anchor()
            val distance = draw.distance(anchor)
            if (distance > range) continue
            val color = Colors.argb(LocationStore.color(located))
            val far = distance > DETAIL_DISTANCE

            if (far) draw.point(anchor.add(0.0, 0.5, 0.0), color, 8f, onTop)
            else shape(draw, entry, anchor, color, onTop)

            if (!far && (entry.type == LocationType.PRECISE || entry.yaw != 0f || entry.pitch != 0f)) {
                draw.line(anchor, anchor.add(facing(entry.yaw, entry.pitch).scale(1.5)), color, 2f, onTop)
            }
            if (editing || distance <= o.labelDistance) draw.label(anchor.add(0.0, height(entry) + 0.4, 0.0), labelLines(located, far, color))
        }
    }

    fun height(entry: LocationEntry): Double {
        val s = entry.display.size.toDouble()
        return when {
            entry.type == LocationType.BLOCK && entry.display.shape == RenderShape.CUBE -> 1.0
            entry.display.shape == RenderShape.FLAG -> s * 2
            entry.display.shape == RenderShape.BEACON -> s * 4
            else -> s
        }
    }

    private fun labelLines(located: Located, far: Boolean, color: Int): List<Pair<String, Int>> {
        val entry = located.entry
        val template = LocationStore.displayText(located.source, located.category)
        val name = when {
            template != null -> entry.metadata.entries.fold(template) { text, (k, v) -> text.replace("{$k}", v) }
            far && entry.display.name == null -> "${located.source}:${located.category}"
            else -> entry.display.name
        }
        val lines = mutableListOf<Pair<String, Int>>()
        if (!name.isNullOrBlank()) lines += name to color
        if (Config.settings.locations.showSourceCategoryLabels) lines += "${located.source} > ${located.category}" to Colors.GRAY
        if (!far) entry.metadata.forEach { (k, v) -> lines += "$k: $v" to Colors.GRAY }
        return lines
    }

    fun facing(yaw: Float, pitch: Float): Vec3 {
        val y = Math.toRadians(yaw.toDouble())
        val p = Math.toRadians(pitch.toDouble())
        return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
    }

    private fun shape(draw: OverlayDraw, entry: LocationEntry, a: Vec3, color: Int, onTop: Boolean) {
        val s = entry.display.size.toDouble()
        val h = s / 2
        fun line(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) =
            draw.line(a.add(x0, y0, z0), a.add(x1, y1, z1), color, 2f, onTop)

        when (entry.display.shape) {
            RenderShape.CUBE ->
                if (entry.type == LocationType.BLOCK) draw.box(a.add(-0.5, 0.0, -0.5), a.add(0.5, 1.0, 0.5), color, 2f, onTop = onTop)
                else draw.box(a.add(-h, 0.0, -h), a.add(h, s, h), color, 2f, onTop = onTop)

            RenderShape.SPHERE -> {
                val center = a.add(0.0, h, 0.0)
                val n = 16
                for (i in 0 until n) {
                    val t0 = 2 * PI * i / n
                    val t1 = 2 * PI * (i + 1) / n
                    for (plane in 0..2) {
                        fun p(t: Double) = when (plane) {
                            0 -> Vec3(cos(t) * h, 0.0, sin(t) * h)
                            1 -> Vec3(cos(t) * h, sin(t) * h, 0.0)
                            else -> Vec3(0.0, sin(t) * h, cos(t) * h)
                        }
                        draw.line(center.add(p(t0)), center.add(p(t1)), color, 2f, onTop)
                    }
                }
            }

            RenderShape.DIAMOND -> {
                val ring = listOf(Vec3(0.0, h, h), Vec3(h, h, 0.0), Vec3(0.0, h, -h), Vec3(-h, h, 0.0))
                ring.forEachIndexed { i, p ->
                    val next = ring[(i + 1) % ring.size]
                    line(p.x, p.y, p.z, next.x, next.y, next.z)
                    line(0.0, s, 0.0, p.x, p.y, p.z)
                    line(0.0, 0.0, 0.0, p.x, p.y, p.z)
                }
            }

            RenderShape.FLAG -> {
                val top = s * 2
                val w = s * 0.8
                line(0.0, 0.0, 0.0, 0.0, top, 0.0)
                line(0.0, top, 0.0, w, top, 0.0)
                line(w, top, 0.0, w, top * 0.6, 0.0)
                line(w, top * 0.6, 0.0, 0.0, top * 0.6, 0.0)
            }

            RenderShape.CROSS -> {
                line(-h, h, 0.0, h, h, 0.0)
                line(0.0, 0.0, 0.0, 0.0, s, 0.0)
                line(0.0, h, -h, 0.0, h, h)
            }

            RenderShape.BEACON -> {
                val b = s * 0.3
                line(0.0, 0.0, 0.0, 0.0, s * 4, 0.0)
                draw.box(a.add(-b, 0.0, -b), a.add(b, 0.02, b), color, 2f, onTop = onTop)
            }
        }
    }
}
