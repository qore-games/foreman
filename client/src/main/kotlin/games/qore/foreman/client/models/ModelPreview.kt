package games.qore.foreman.client.models

import com.mojang.blaze3d.platform.Lighting
import com.mojang.blaze3d.vertex.PoseStack
import net.fabricmc.fabric.api.client.rendering.v1.PictureInPictureRendererRegistry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.pip.PictureInPictureRenderer
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.item.TrackingItemStackRenderState
import net.minecraft.client.renderer.state.gui.pip.PictureInPictureRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.AABB
import org.joml.Matrix3x2f
import org.joml.Matrix3x2fc
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Quaternionfc
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Item models drawn in 3D in the GUI, any size and turned any way: a picture-in-picture element per model, rendered
 * with vanilla item rendering into its own texture, fitted in its box. A texture is drawn again only when what it shows
 * changes (another model, angle or size, an animated model), so a page of still models costs a blit each per frame.
 */
object ModelPreview {
    private const val FULL_BRIGHT = 0xF000F0
    /** The share of the box the model fills. */
    private const val MARGIN = 0.9f

    fun init() = PictureInPictureRendererRegistry.register { Renderer() }

    /** [id] as the client draws it in [context] (GUI: as in an inventory; FIXED: as a placed display shows it). */
    fun resolve(id: Identifier, context: ItemDisplayContext): TrackingItemStackRenderState {
        val minecraft = Minecraft.getInstance()
        return TrackingItemStackRenderState().also { minecraft.itemModelResolver.updateForTopItem(it, ItemModels.stack(id), context, minecraft.level, null, 0) }
    }

    /** The box the model of [id] fills in [context], in blocks around the point it is drawn at; null when it draws nothing. */
    fun bounds(id: Identifier, context: ItemDisplayContext): AABB? = resolve(id, context).takeUnless { it.isEmpty }?.modelBoundingBox

    /**
     * Draws [id] in 3D fitted in the box at ([x], [y]), [size] square, seen in [context] and turned by [rotation] (around
     * its center). [turning]: fitted for any rotation, so it keeps its size while it turns. Clipped like the rest of [g]
     * (a scroll area). False when the model draws nothing.
     */
    fun draw(
        g: GuiGraphicsExtractor, id: Identifier, context: ItemDisplayContext, x: Int, y: Int, size: Int,
        rotation: Quaternionfc = NO_ROTATION, turning: Boolean = false,
    ): Boolean {
        val item = resolve(id, context)
        if (item.isEmpty) return false
        val box = item.modelBoundingBox
        val extent = if (turning) sqrt(box.xsize * box.xsize + box.ysize * box.ysize + box.zsize * box.zsize) else max(box.xsize, box.ysize)
        val scale = size * MARGIN / extent.toFloat().coerceAtLeast(1e-3f)
        val pose = Matrix3x2f(g.pose())
        val scissor = g.scissorStack.peek()
        val center = box.center
        val key = listOf(item.modelIdentity, context, rotation.x(), rotation.y(), rotation.z(), rotation.w(), scale)
        val state = State(item, Matrix4f().rotation(rotation).translate(-center.x.toFloat(), -center.y.toFloat(), -center.z.toFloat()), scale,
            x, y, x + size, y + size, pose, scissor, key.takeUnless { item.isAnimated })
        g.guiRenderState.addPicturesInPictureState(state)
        return true
    }

    val NO_ROTATION: Quaternionfc = Quaternionf()

    /** [pitch] then [yaw] in degrees: a model on a turntable, tilted toward the viewer. */
    fun turntable(yaw: Float, pitch: Float): Quaternionfc = Quaternionf().rotationX(Math.toRadians(pitch.toDouble()).toFloat()).rotateY(Math.toRadians(yaw.toDouble()).toFloat())

    /** A model to draw: [item] moved by [transform] (its center to the origin, then turned), [key] what it shows. */
    private class State(
        val item: TrackingItemStackRenderState, val transform: Matrix4f, private val scale: Float,
        private val left: Int, private val top: Int, private val right: Int, private val bottom: Int,
        private val pose: Matrix3x2fc, private val scissor: ScreenRectangle?, val key: Any?,
    ) : PictureInPictureRenderState {
        override fun x0() = left
        override fun y0() = top
        override fun x1() = right
        override fun y1() = bottom
        override fun scale() = scale
        override fun pose() = pose
        override fun scissorArea() = scissor
        override fun bounds(): ScreenRectangle? {
            val box = ScreenRectangle(left, top, right - left, bottom - top).transformMaxBounds(pose)
            return if (scissor == null) box else scissor.intersection(box)
        }
    }

    private class Renderer : PictureInPictureRenderer<State>() {
        /** What its texture holds: the key of the last model drawn, null when it must be drawn again. */
        private var drawn: Any? = null

        override fun getRenderStateClass() = State::class.java

        override fun textureIsReadyToBlit(state: State) = state.key != null && state.key == drawn

        /** Centered: the model's center is the box's. */
        override fun getTranslateY(height: Int, guiScale: Int) = height / 2f

        override fun renderToTexture(state: State, pose: PoseStack, collector: SubmitNodeCollector) {
            Minecraft.getInstance().gameRenderer.lighting().setupFor(if (state.item.usesBlockLight()) Lighting.Entry.ITEMS_3D else Lighting.Entry.ITEMS_FLAT)
            // As vanilla draws items in the GUI: y up, toward the viewer.
            pose.scale(1f, -1f, -1f)
            pose.mulPose(state.transform)
            state.item.submit(pose, collector, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, 0)
            drawn = state.key
        }

        override fun getTextureLabel() = "foreman model preview"
    }
}
