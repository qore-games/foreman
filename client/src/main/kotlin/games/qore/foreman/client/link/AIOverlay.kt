package games.qore.foreman.client.link

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.config.Visibility
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.render.OverlayLayer
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Draws what the Foreman server pushes: its areas, points, the last check's issues, the last AI change.
 * Each layer is off, nearby or always (settings and hub). Nearby areas show from the nearby distance, stay drawn while
 * the player is within the inside buffer of their edge and fade out deeper in; the ones the player is in are named on
 * the HUD ([areasHere]), and entering or leaving one shows a notice ([notices]).
 */
object AIOverlay : OverlayLayer {
    private const val MARK = 0xFF55DDFF.toInt()
    private const val CHANGE_SECONDS = 20.0
    private val CLAIM_COLORS = mapOf("build" to 0xFFFFAA33.toInt(), "road" to 0xFFC8A070.toInt(), "yard" to 0xFF77DD55.toInt())

    /** The areas the player stands in, for the HUD (updated while drawing). */
    var areasHere: List<String> = emptyList()
        private set

    /** "Entered x" / "Left x", with when (the HUD shows them a few seconds). */
    val notices = ArrayDeque<Pair<String, Long>>()

    override fun draw(draw: OverlayDraw) {
        val o = Config.settings.overlays
        if (!o.enabled || !Bridge.connected) {
            areasHere = emptyList()
            return
        }
        val onTop = o.throughBlocks
        val dim = draw.dimension
        val here = mutableListOf<String>()

        /**
         * How visible an area is. Outside: fading in over the nearby distance. Inside: full within the inside buffer of
         * its edge, so it stays in view while crossing into it, then fading out over 6 more blocks.
         */
        fun presence(box: BlockBox, mode: Visibility): Float {
            val outside = box.distanceTo(draw.camera)
            val depth = box.depthOf(draw.camera)
            return when (mode) {
                Visibility.OFF -> 0f
                Visibility.ALWAYS -> if (outside <= o.maxDistance) 1f else 0f
                Visibility.NEARBY -> when {
                    outside > 0.0 -> (1 - outside / o.nearDistance).toFloat().coerceIn(0f, 1f)
                    depth <= o.insideBuffer -> 1f
                    else -> (1 - (depth - o.insideBuffer) / 6.0).toFloat().coerceIn(0f, 1f)
                }
            }
        }

        fun area(box: BlockBox, color: Int, name: String, mode: Visibility) {
            val inside = box.distanceTo(draw.camera) <= 0.0
            if (inside) here += name
            val p = presence(box, mode)
            if (p <= 0f) return
            box(draw, box, Colors.withAlpha(color, 0.2f + 0.65f * p), if (inside) 1.5f else 2f, Colors.withAlpha(color, if (inside) 0f else 0.05f * p), onTop)
            if (draw.distance(box.center) <= o.labelDistance + max(box.sizeX, box.sizeZ) / 2.0) {
                draw.label(if (inside) nearestTop(box, draw.camera) else topCenter(box), name, Colors.withAlpha(color, 0.4f + 0.6f * p), onTop)
            }
        }

        if (o.areas != Visibility.OFF) for (a in Bridge.areas) {
            if (a.dim != dim) continue
            val color = a.kind?.let { CLAIM_COLORS[it] ?: hashColor(it) } ?: MARK
            if (a.isPoint) {
                val b = a.blockBoxes[0]
                if (presence(b, o.areas) <= 0f) continue
                box(draw, b, color, 3f, 0, onTop)
                if (draw.distance(b.center) <= o.labelDistance) draw.label(topCenter(b), a.name, color, onTop)
                continue
            }
            val label = a.name + (a.kind?.let { " ($it)" } ?: "") + if (a.theme.isNotEmpty()) " · zone" else ""
            a.blockBoxes.forEach { area(it, color, label, o.areas) }
        }
        if (o.zoneNotices) {
            val now = System.currentTimeMillis()
            (here - areasHere.toSet()).forEach { notices += "→ Entered $it" to now }
            (areasHere - here.toSet()).forEach { notices += "← Left $it" to now }
            while (notices.size > 4 || notices.firstOrNull()?.let { now - it.second > 4000 } == true) notices.removeFirst()
        }
        areasHere = here

        if (o.points != Visibility.OFF) {
            val range = if (o.points == Visibility.NEARBY) o.nearDistance * 2.0 else o.maxDistance.toDouble()
            for (p in Bridge.points) {
                if (p.dim != dim) continue
                val center = Vec3(p.pos[0] + 0.5, p.pos[1] + 0.5, p.pos[2] + 0.5)
                val distance = draw.distance(center)
                if (distance > range) continue
                val color = hashColor(p.type)
                draw.box(center.subtract(0.2, 0.2, 0.2), center.add(0.2, 0.2, 0.2), color, 2f, Colors.withAlpha(color, 0.4f), onTop)
                if (distance <= 12) draw.label(center.add(0.0, 0.45, 0.0), "● ${p.type}${if (p.kind.isNotEmpty()) ":${p.kind}" else ""}", color, onTop, 0.7f)
            }
        }

        if (o.issues != Visibility.OFF) Bridge.issues?.takeIf { it.dim == dim }?.let { report ->
            val range = if (o.issues == Visibility.NEARBY) o.nearDistance * 2.0 else o.maxDistance.toDouble()
            val ray = Ray.crosshair()
            val aimed = report.issues.minByOrNull { ray.toPoint(it.center()).first }?.takeIf { ray.toPoint(it.center()).first < 0.8 }
            report.issues.forEachIndexed { i, issue ->
                val c = issue.center()
                val distance = draw.distance(c)
                if (distance > range) return@forEachIndexed
                val color = if (issue.error) Colors.RED else Colors.YELLOW
                draw.box(c.subtract(0.52, 0.52, 0.52), c.add(0.52, 0.52, 0.52), color, 2.5f, Colors.withAlpha(color, 0.2f), onTop = true)
                if (distance > o.labelDistance && issue !== aimed) return@forEachIndexed
                val lines = mutableListOf("${i + 1}. ${issue.check}" to color)
                if (issue === aimed || distance < 6) issue.text.chunked(48).forEach { lines += it to Colors.WHITE }
                draw.label(c.add(0.0, 0.8, 0.0), lines, onTop = true, size = 0.8f)
            }
        }

        if (o.changes != Visibility.OFF) Bridge.change?.takeIf { it.dim == dim }?.let { change ->
            val age = (System.currentTimeMillis() - change.received) / 1000.0
            if (age > CHANGE_SECONDS) return@let
            val alpha = (1 - age / CHANGE_SECONDS).toFloat()
            val b = change.box?.toBlockBox() ?: return@let
            if (o.changes == Visibility.NEARBY && b.distanceTo(draw.camera) > o.nearDistance * 4) return@let
            box(draw, b, Colors.withAlpha(Colors.GREEN, alpha), 3f, Colors.withAlpha(Colors.GREEN, alpha * 0.12f), onTop = true)
            draw.label(topCenter(b), "AI: ${change.label}" + if (change.by.isNotEmpty()) " (${change.by})" else "", Colors.withAlpha(Colors.GREEN, alpha.coerceAtLeast(0.1f)))
        }
    }

    private fun Issue.center() = Vec3(pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)

    /** How deep [p] is inside the box: its distance to the nearest side (0 outside). */
    private fun BlockBox.depthOf(p: Vec3): Double = minOf(p.x - minX, maxX + 1 - p.x, p.z - minZ, maxZ + 1 - p.z, p.y - minY, maxY + 1 - p.y).coerceAtLeast(0.0)

    /** Above the point of the box's side nearest to [p] (where the player is about to leave it). */
    private fun nearestTop(b: BlockBox, p: Vec3): Vec3 {
        val sides = listOf(
            Vec3(b.minX.toDouble(), 0.0, p.z) to p.x - b.minX, Vec3(b.maxX + 1.0, 0.0, p.z) to b.maxX + 1 - p.x,
            Vec3(p.x, 0.0, b.minZ.toDouble()) to p.z - b.minZ, Vec3(p.x, 0.0, b.maxZ + 1.0) to b.maxZ + 1 - p.z,
        )
        val side = sides.minBy { it.second }.first
        return Vec3(side.x, p.y + 2.5, side.z)
    }

    /** Distance from [p] to the box (0 inside). */
    private fun BlockBox.distanceTo(p: Vec3): Double {
        val dx = max(max(minX - p.x, p.x - (maxX + 1)), 0.0)
        val dy = max(max(minY - p.y, p.y - (maxY + 1)), 0.0)
        val dz = max(max(minZ - p.z, p.z - (maxZ + 1)), 0.0)
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    fun box(draw: OverlayDraw, b: BlockBox, color: Int, width: Float, fill: Int, onTop: Boolean) =
        draw.blockBox(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, color, width, fill, onTop)

    fun topCenter(b: BlockBox) = Vec3(b.center.x, b.maxY + 1.4, b.center.z)

    private fun hashColor(key: String): Int {
        val hue = (key.hashCode() and 0xFFFF) / 65535f
        return 0xFF000000.toInt() or (java.awt.Color.HSBtoRGB(hue, 0.6f, 1f) and 0xFFFFFF)
    }
}
