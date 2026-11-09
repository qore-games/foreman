package games.qore.foreman.client.display

import com.mojang.blaze3d.vertex.PoseStack
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.mixin.DisplayAccessor
import games.qore.foreman.client.mixin.ItemLayerAccessor
import games.qore.foreman.client.mixin.ItemStackRenderStateAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.item.ItemStackRenderState
import net.minecraft.client.resources.model.geometry.BakedQuad
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.Display
import net.minecraft.world.level.EmptyBlockGetter
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Quaterniond
import org.joml.Vector3d
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * What a display draws, in its own space (after its position, orientation and transformation): to pick it under the
 * crosshair and outline it. [box] is null when it draws nothing the editor can find (no item, an empty text, a block
 * without shape): an anchor marks it then.
 */
class DisplayShape(
    val box: AABB?,
    /** The distance along a ray (origin, direction, in the display's space) to what it draws; null: the box is the shape. */
    val exact: ((Vector3d, Vector3d) -> Double?)?,
)

/** A display under the crosshair: [exact] when the ray meets what it draws, not only its box. */
class DisplayHit(val display: Display, val distance: Double, val exact: Boolean)

/**
 * The shapes of displays, from what the client draws: an item display's model (its quads, through the client's item
 * model and its display context's transform), a block display's block shape, a text display's text box. Kept per
 * display while what it shows stays the same, a few seconds at most (models change with resource packs).
 */
object DisplayShapes {
    private const val KEEP_MS = 3000L
    /** Displays without shape are pickable by their anchor within this many blocks. */
    const val MARKERS = 8.0
    const val MARKER = 0.12

    private class Kept(val key: Any?, val shape: DisplayShape, val at: Long)

    private val kept = HashMap<Int, Kept>()
    private var swept = 0L
    private val EMPTY = DisplayShape(null, null)

    fun clear() = kept.clear()

    fun shape(e: Display): DisplayShape {
        val now = System.currentTimeMillis()
        if (now - swept > KEEP_MS) {
            kept.values.removeIf { now - it.at > KEEP_MS }
            swept = now
        }
        val key = key(e)
        kept[e.id]?.takeIf { it.key === key || it.key == key }?.let { return it.shape }
        val shape = runCatching { build(e) }.getOrDefault(EMPTY)
        kept[e.id] = Kept(key, shape, now)
        return shape
    }

    /** What the shape is made from: built again when it changes. */
    private fun key(e: Display): Any? = when (e) {
        is Display.ItemDisplay -> e.itemStack to e.itemTransform
        is Display.BlockDisplay -> e.blockState
        is Display.TextDisplay -> e.text to e.lineWidth
        else -> null
    }

    private fun build(e: Display): DisplayShape = when (e) {
        is Display.ItemDisplay -> item(e)
        is Display.BlockDisplay -> block(e)
        is Display.TextDisplay -> text(e)
        else -> EMPTY
    }

    private fun item(e: Display.ItemDisplay): DisplayShape {
        val stack = e.itemStack
        if (stack.isEmpty) return EMPTY
        val state = ItemStackRenderState()
        Minecraft.getInstance().itemModelResolver.updateForNonLiving(state, stack, e.itemTransform, e)
        if (state.isEmpty) return EMPTY
        // The renderer turns items half a turn around y before drawing them in their display context.
        val turn = Matrix4d().rotationY(Math.PI)
        val bounds = state.modelBoundingBox
        if (bounds.xsize <= 0 && bounds.ysize <= 0 && bounds.zsize <= 0) return EMPTY
        val box = AABB(-bounds.maxX, bounds.minY, -bounds.maxZ, -bounds.minX, bounds.maxY, -bounds.minZ)
        val accessor = state as ItemStackRenderStateAccessor
        val layers = (0 until accessor.`foreman$activeLayerCount`()).map { accessor.`foreman$layers`()[it] }
        val parts = layers.mapNotNull { layer ->
            val quads = Mc.itemQuads(layer)
            if (quads.isEmpty()) return@mapNotNull null
            val pose = PoseStack.Pose()
            (layer as ItemLayerAccessor).`foreman$applyTransform`(pose)
            val matrix = Matrix4d(turn).mul(Matrix4d(pose.pose()))
            if (abs(matrix.determinant()) < 1e-12) null else Matrix4d(matrix).invert() to quads
        }
        // Special models (heads, chests…) have no quads: their box is their shape.
        if (parts.size < layers.size) return DisplayShape(box, null)
        return DisplayShape(box) { origin, dir -> parts.minOfOrNull { (inverse, quads) -> quads(inverse, quads, origin, dir) ?: Double.MAX_VALUE }?.takeIf { it < Double.MAX_VALUE } }
    }

    private fun quads(inverse: Matrix4d, quads: List<BakedQuad>, origin: Vector3d, dir: Vector3d): Double? {
        val o = inverse.transformPosition(Vector3d(origin))
        val d = inverse.transformDirection(Vector3d(dir))
        var best = Double.MAX_VALUE
        for (q in quads) {
            val a = q.position(0); val b = q.position(1); val c = q.position(2); val e = q.position(3)
            triangle(o, d, a.x(), a.y(), a.z(), b.x(), b.y(), b.z(), c.x(), c.y(), c.z())?.let { best = min(best, it) }
            triangle(o, d, a.x(), a.y(), a.z(), c.x(), c.y(), c.z(), e.x(), e.y(), e.z())?.let { best = min(best, it) }
        }
        return best.takeIf { it < Double.MAX_VALUE }
    }

    private fun block(e: Display.BlockDisplay): DisplayShape {
        val shape = e.blockState.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)
        if (shape.isEmpty) return EMPTY
        val boxes = shape.toAabbs()
        return DisplayShape(shape.bounds()) { origin, dir -> boxes.mapNotNull { slab(it, origin, dir) }.minOrNull() }
    }

    /** The text and its background, as the renderer lays them out (a line is 10 units, 40 units a block). */
    private fun text(e: Display.TextDisplay): DisplayShape {
        val font = Minecraft.getInstance().font
        val lines = font.split(e.text, e.lineWidth)
        if (lines.isEmpty()) return EMPTY
        val width = lines.maxOf { font.width(it) }
        val height = lines.size * 10 - 1
        return DisplayShape(AABB(-width / 2.0 * TEXT, 0.0, -0.005, (width / 2.0 + 1) * TEXT, (height + 1) * TEXT, 0.005), null)
    }

    private const val TEXT = 0.025

    /** The rotation of [e]'s yaw and pitch as drawn: its billboard turns it toward the camera. */
    fun orientation(e: Display): Quaterniond {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val yaw = Math.toRadians(e.yRot.toDouble())
        val pitch = Math.toRadians(e.xRot.toDouble())
        val cameraYaw = Math.toRadians(camera.yRot() - 180.0)
        val cameraPitch = Math.toRadians(-camera.xRot().toDouble())
        return when (e.billboardConstraints) {
            Display.BillboardConstraints.FIXED, null -> Quaterniond().rotationYXZ(-yaw, pitch, 0.0)
            Display.BillboardConstraints.HORIZONTAL -> Quaterniond().rotationYXZ(-yaw, cameraPitch, 0.0)
            Display.BillboardConstraints.VERTICAL -> Quaterniond().rotationYXZ(-cameraYaw, pitch, 0.0)
            Display.BillboardConstraints.CENTER -> Quaterniond().rotationYXZ(-cameraYaw, cameraPitch, 0.0)
        }
    }

    /** From [e]'s space to the world: position, orientation, transformation (as last synced). */
    fun matrix(e: Display): Matrix4d {
        val t = DisplayAccessor.`foreman$transformation`(e.getEntityData())
        return Matrix4d().translation(e.x, e.y, e.z).rotate(orientation(e)).mul(Matrix4d(t.matrix))
    }

    /** The corners of [e]'s box in the world, or null when it has none. */
    fun corners(e: Display): List<Vec3>? {
        val box = shape(e).box ?: return null
        val m = matrix(e)
        return listOf(box.minX, box.maxX).flatMap { x -> listOf(box.minY, box.maxY).flatMap { y -> listOf(box.minZ, box.maxZ).map { z -> point(m, x, y, z) } } }
    }

    /** Where [e]'s transformation starts in the world (its anchor). */
    fun anchor(e: Display): Vec3 = point(matrix(e), 0.0, 0.0, 0.0)

    /** [e]'s box in the world, axis aligned; a point at its anchor when it has none. */
    fun bounds(e: Display): AABB = corners(e)?.let { c ->
        AABB(c.minOf { it.x }, c.minOf { it.y }, c.minOf { it.z }, c.maxOf { it.x }, c.maxOf { it.y }, c.maxOf { it.z })
    } ?: anchor(e).let { AABB(it, it) }

    private fun point(m: Matrix4d, x: Double, y: Double, z: Double) = m.transformPosition(Vector3d(x, y, z)).let { Vec3(it.x, it.y, it.z) }

    /** The displays [ray] meets, what it meets exactly first, nearest first; anchors of displays without shape nearby. */
    fun pick(ray: Ray, displays: Collection<Display>): List<DisplayHit> {
        val hits = ArrayList<DisplayHit>()
        for (e in displays) {
            val shape = shape(e)
            val m = matrix(e)
            val box = shape.box
            if (box == null || abs(m.determinant()) < 1e-12) {
                val anchor = point(m, 0.0, 0.0, 0.0)
                if (anchor.distanceTo(ray.origin) > MARKERS) continue
                val (distance, along) = ray.toPoint(anchor)
                if (distance <= MARKER * 1.5) hits += DisplayHit(e, along, true)
                continue
            }
            val inverse = Matrix4d(m).invert()
            val origin = inverse.transformPosition(Vector3d(ray.origin.x, ray.origin.y, ray.origin.z))
            val dir = inverse.transformDirection(Vector3d(ray.dir.x, ray.dir.y, ray.dir.z))
            val entered = slab(box, origin, dir) ?: continue
            val exact = shape.exact
            if (exact == null) hits += DisplayHit(e, entered, true)
            else hits += exact(origin, dir)?.let { DisplayHit(e, it, true) } ?: DisplayHit(e, entered, false)
        }
        return hits.sortedWith(compareBy({ !it.exact }, { it.distance }))
    }

    /** How far along the ray it enters [box] (0 from inside), or null when it misses. */
    fun slab(box: AABB, o: Vector3d, d: Vector3d): Double? {
        var near = 0.0
        var far = Double.MAX_VALUE
        for (axis in 0..2) {
            val origin = o.get(axis)
            val dir = d.get(axis)
            val lo = box.min(axis)
            val hi = box.max(axis)
            if (abs(dir) < 1e-12) {
                if (origin < lo || origin > hi) return null
                continue
            }
            val t1 = (lo - origin) / dir
            val t2 = (hi - origin) / dir
            near = max(near, min(t1, t2))
            far = min(far, max(t1, t2))
            if (near > far) return null
        }
        return near
    }

    private fun AABB.min(axis: Int) = when (axis) { 0 -> minX; 1 -> minY; else -> minZ }
    private fun AABB.max(axis: Int) = when (axis) { 0 -> maxX; 1 -> maxY; else -> maxZ }

    /** Möller–Trumbore, both sides: how far along the ray it meets the triangle, or null. */
    private fun triangle(o: Vector3d, d: Vector3d, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float): Double? {
        val e1x = (bx - ax).toDouble(); val e1y = (by - ay).toDouble(); val e1z = (bz - az).toDouble()
        val e2x = (cx - ax).toDouble(); val e2y = (cy - ay).toDouble(); val e2z = (cz - az).toDouble()
        val px = d.y * e2z - d.z * e2y; val py = d.z * e2x - d.x * e2z; val pz = d.x * e2y - d.y * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-12) return null
        val inv = 1 / det
        val sx = o.x - ax; val sy = o.y - ay; val sz = o.z - az
        val u = (sx * px + sy * py + sz * pz) * inv
        if (u < 0 || u > 1) return null
        val qx = sy * e1z - sz * e1y; val qy = sz * e1x - sx * e1z; val qz = sx * e1y - sy * e1x
        val v = (d.x * qx + d.y * qy + d.z * qz) * inv
        if (v < 0 || u + v > 1) return null
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        return t.takeIf { it >= 0 }
    }
}
