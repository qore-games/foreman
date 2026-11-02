package games.qore.foreman.client.render

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionEvents
import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory

/** Something drawn in the world every frame (locations, marks, a gizmo…). */
fun interface OverlayLayer {
    fun draw(draw: OverlayDraw)
}

/**
 * The mod's only world render layer: layers describe boxes, lines, points and labels each frame and the game's gizmo
 * renderer draws them (depth-tested, or on top of everything with [onTop]).
 */
object Overlays {
    private val logger = LoggerFactory.getLogger("foreman")
    private val layers = mutableListOf<OverlayLayer>()

    fun add(layer: OverlayLayer) {
        layers += layer
    }

    fun init() {
        LevelExtractionEvents.END_EXTRACTION.register { context ->
            val minecraft = Minecraft.getInstance()
            val level = minecraft.level ?: return@register
            val draw = OverlayDraw(context.camera().position(), level.dimension().identifier().toString())
            minecraft.levelRenderer.collectPerFrameRenderThreadGizmos().use {
                for (layer in layers) {
                    try {
                        layer.draw(draw)
                    } catch (e: Exception) {
                        logger.error("Overlay layer {} failed", layer.javaClass.simpleName, e)
                    }
                }
            }
        }
    }
}

/** Drawing calls for one frame. Colors are ARGB. */
class OverlayDraw(val camera: Vec3, val dimension: String) {
    private companion object {
        const val EPS = 0.004
    }

    fun distance(pos: Vec3) = pos.distanceTo(camera)

    fun line(from: Vec3, to: Vec3, argb: Int, width: Float = 2f, onTop: Boolean = false) {
        Gizmos.line(from, to, argb, width).top(onTop)
    }

    fun arrow(from: Vec3, to: Vec3, argb: Int, width: Float = 2.5f, onTop: Boolean = false) {
        Gizmos.arrow(from, to, argb, width).top(onTop)
    }

    /** An outlined box from [min] to [max] (world coordinates), optionally filled with [fill]. */
    fun box(min: Vec3, max: Vec3, argb: Int, width: Float = 2f, fill: Int = 0, onTop: Boolean = false) {
        Gizmos.cuboid(AABB(min, max), GizmoStyle(argb, width, fill)).top(onTop)
    }

    /** The box of whole blocks [x0..x1] × [y0..y1] × [z0..z1] (inclusive), a hair outside their faces (no flicker). */
    fun blockBox(x0: Int, y0: Int, z0: Int, x1: Int, y1: Int, z1: Int, argb: Int, width: Float = 2f, fill: Int = 0, onTop: Boolean = false) =
        box(Vec3(x0 - EPS, y0 - EPS, z0 - EPS), Vec3(x1 + 1 + EPS, y1 + 1 + EPS, z1 + 1 + EPS), argb, width, fill, onTop)

    fun point(pos: Vec3, argb: Int, size: Float = 6f, onTop: Boolean = false) {
        Gizmos.point(pos, argb, size).top(onTop)
    }

    /**
     * Text lines facing the camera, the first one at [pos]. One size in the world, like a sign: labels that grew with
     * distance shifted and wobbled as the player moved; callers hide the far ones instead.
     */
    fun label(pos: Vec3, lines: List<Pair<String, Int>>, onTop: Boolean = true, size: Float = 1f) {
        if (lines.isEmpty()) return
        val scale = TextGizmo.Style.DEFAULT_SCALE * size * 1.5f
        val step = 11 * scale / 16.0
        lines.forEachIndexed { i, (text, argb) ->
            Gizmos.billboardText(text, pos.add(0.0, (lines.size - 1 - i) * step, 0.0), TextGizmo.Style.forColorAndCentered(argb).withScale(scale)).top(onTop)
        }
    }

    fun label(pos: Vec3, text: String, argb: Int, onTop: Boolean = true, size: Float = 1f) = label(pos, listOf(text to argb), onTop, size)

    private fun net.minecraft.gizmos.GizmoProperties.top(onTop: Boolean) {
        if (onTop) setAlwaysOnTop()
    }
}

object Colors {
    fun argb(rgb: Int, alpha: Int = 255) = (alpha shl 24) or (rgb and 0xFFFFFF)
    fun withAlpha(argb: Int, alpha: Float) = ((((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).toInt() shl 24) or (argb and 0xFFFFFF)

    const val WHITE = 0xFFFFFFFF.toInt()
    const val GRAY = 0xFFAAAAAA.toInt()
    const val RED = 0xFFFF5555.toInt()
    const val YELLOW = 0xFFFFD84A.toInt()
    const val GREEN = 0xFF55FF55.toInt()
    const val CYAN = 0xFF55FFFF.toInt()
    const val AXIS_X = 0xFFFF4040.toInt()
    const val AXIS_Y = 0xFF40FF40.toInt()
    const val AXIS_Z = 0xFF4080FF.toInt()
    const val HOVER = 0xFFFFFF60.toInt()
}
