package games.qore.foreman.client.library

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.config.Visibility
import games.qore.foreman.client.link.AIOverlay
import games.qore.foreman.client.link.AssetInstance
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.render.OverlayLayer
import net.minecraft.world.phys.Vec3
import kotlin.math.max

/**
 * The pasted copies of assets ([Bridge.instances]) in the world: each one's box, dimmer than the areas (grey when
 * detached, thinner when nested in another), labelled `asset · turn · mirrored · detached · by`, and the points it
 * carries as small named markers. Drawn as the Pasted copies overlay says, and always while the asset tool is on (which
 * selects them, turns, syncs and deletes them).
 */
object CopyLayer : OverlayLayer {
    private const val COLOR = 0xFFB89CFF.toInt()
    private const val POINT = 0xFFE6D8FF.toInt()
    private const val DETACHED = 0xFF9A96A6.toInt()
    /** Points are drawn this close, named closer. */
    private const val POINTS = 48.0
    private const val POINT_NAMES = 16.0

    /** Whether the copies are drawn (and the bridge keeps them up to date). */
    val shown get() = AssetTool.enabled || Config.settings.overlays.let { it.enabled && it.instances != Visibility.OFF }

    override fun draw(draw: OverlayDraw) {
        val copies = Bridge.instances
        if (copies.isEmpty() || !shown) return
        val o = Config.settings.overlays
        val range = if (AssetTool.enabled || o.instances == Visibility.ALWAYS) o.maxDistance.toDouble() else o.nearDistance.toDouble()
        for (copy in copies) {
            if (!copy.inDimension(draw.dimension)) continue
            val box = copy.blockBox
            val distance = box.distanceTo(draw.camera)
            if (distance > range) continue
            val lit = copy.id == AssetTool.hoveredCopy
            val color = if (lit) Colors.HOVER else Colors.withAlpha(if (copy.detached) DETACHED else COLOR, 0.6f)
            val width = if (lit) 2.5f else if (copy.parent != null) 1f else 1.5f
            AIOverlay.box(draw, box, color, width, if (lit) Colors.withAlpha(color, 0.06f) else 0, o.throughBlocks || lit)
            if (distance <= o.labelDistance + max(box.sizeX, box.sizeZ) / 2.0) draw.label(AIOverlay.topCenter(box), label(copy), color, o.throughBlocks || lit, 0.8f)
            if (distance <= POINTS) points(draw, copy, o.throughBlocks)
        }
    }

    private fun points(draw: OverlayDraw, copy: AssetInstance, onTop: Boolean) {
        for (p in copy.points) {
            val position = p.at
            if (position.size != 3) continue
            val at = Vec3(position[0], position[1], position[2]).let { if (position.all { it % 1.0 == 0.0 }) it.add(0.5, 0.5, 0.5) else it }
            draw.box(at.subtract(0.15, 0.15, 0.15), at.add(0.15, 0.15, 0.15), POINT, 1.5f, Colors.withAlpha(POINT, 0.35f), onTop)
            if (draw.distance(at) <= POINT_NAMES) draw.label(at.add(0.0, 0.35, 0.0), p.name.ifEmpty { p.type } + if (p.kind.isNotEmpty()) " · ${p.kind}" else "", POINT, onTop, 0.6f)
        }
    }

    /** `asset · 90° · mirrored · detached · by`. */
    fun label(copy: AssetInstance) = listOf(
        copy.assetName, "${copy.rotate}°", if (copy.mirror) "mirrored" else "", if (copy.detached) "detached" else "", copy.by,
    ).filter { it.isNotEmpty() }.joinToString(" · ")
}
