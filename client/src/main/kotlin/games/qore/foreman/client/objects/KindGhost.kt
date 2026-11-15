package games.qore.foreman.client.objects

import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.ObjectKind
import games.qore.foreman.client.models.ItemModels
import games.qore.foreman.client.models.ModelGhost
import games.qore.foreman.client.models.ModelPreview
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A new object of a kind placed like furniture ([ObjectKind.furniture]) as it will stand, before it is placed: the
 * furniture's model where the builder aims, posed as the server's furniture goes there (its poses from `list_furniture`,
 * as the Place model tool shows furniture: [ModelGhost]), on the face looked at when it goes on it (a wall, a ceiling,
 * a floor), turned in its rotation steps by the scroll wheel. It gives the anchor (the middle of the floor of the block
 * before that face) and the facing and surface fields the object is placed with.
 */
internal object KindGhost {
    private const val COLOR = 0xFF7FD8FF.toInt()
    private const val REFUSED = 0xFFF87171.toInt()

    private val ghost = ModelGhost(-0x464D4D32, ObjectsTool.REACH)
    /** The kind shown, and its furniture's poses once the server gave them. */
    private var kind: ObjectKind? = null
    private var poses: ItemModels.Poses? = null
    /** Counts the starts: the poses asked for an earlier kind are dropped. */
    private var asked = 0
    /** As a player's yaw: the furniture's front faces a player looking this way (on floors and ceilings). */
    private var yaw = 0f
    /** The face aimed at and the block before it, while the crosshair meets one. */
    private var face: Direction? = null
    private var root: BlockPos? = null

    /** Shows new objects of [kind] from now (its furniture's poses asked for), when it is placed like furniture. */
    fun start(kind: ObjectKind) {
        clear()
        val furniture = kind.furniture ?: return
        this.kind = kind
        yaw = Minecraft.getInstance().player?.let { (it.yRot / 90f).roundToInt() * 90f }?.mod(360f) ?: 0f
        val ask = ++asked
        if (Bridge.connected) ItemModels.furniturePoses(furniture.id) { result -> if (ask == asked) poses = result.getOrNull() }
    }

    /** Whether it shows [kind]'s new objects. */
    fun shows(kind: ObjectKind?) = kind != null && kind.furniture != null && kind.id == this.kind?.id

    /** Where the anchor goes for [ray]: the middle of the floor of the block before the face it meets. */
    fun aim(ray: Ray): Vec3? {
        val hit = ray.block(ObjectsTool.REACH, solid = true)
        face = hit?.direction
        root = hit?.let { it.blockPos.relative(it.direction) }
        return root?.let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
    }

    /** Whether it goes on the face aimed at (unknown until the server gave its poses: yes). */
    val fits get() = face?.let { f -> poses?.goesOn(f) } ?: true

    /** Why it can't go where it is aimed, when it can't. */
    fun refusal(): String? {
        val p = poses ?: return null
        if (fits) return null
        val on = listOfNotNull("floors".takeIf { p.goesOn(Direction.UP) }, "walls".takeIf { Direction.Plane.HORIZONTAL.any(p::goesOn) },
            "ceilings".takeIf { p.goesOn(Direction.DOWN) })
        return "${kind?.name} goes on ${on.joinToString(" or ").ifEmpty { "nothing here" }}"
    }

    /** Turns it a rotation step ([up]: back). */
    fun turn(up: Boolean) {
        val step = 360f / (poses?.rotations ?: 4)
        yaw = (placedYaw + if (up) -step else step).mod(360f)
    }

    private val placedYaw get() = poses?.let { it.step(yaw) * 360f / it.rotations } ?: yaw

    /** The face it is attached to: a wall's or the ceiling's, null on a floor. */
    private val attached get() = face?.takeIf { it != Direction.UP && poses?.faces?.containsKey(it) == true }

    /** Where its front faces as it is shown (a yaw field's value: 0 south, 90 west): away from its wall, else the turn. */
    private val facing: Float get() = attached?.takeIf { it.axis.isHorizontal }?.toYRot() ?: (placedYaw + 180f).mod(360f)

    /** The kind's facing and surface fields as it is shown, for its form. */
    fun data(): JsonObject {
        val p = kind?.furniture ?: return JsonObject(emptyMap())
        val values = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        p.facing?.let { values[it] = JsonPrimitive(facing.roundToInt()) }
        p.surface?.let { values[it] = JsonPrimitive(when (attached) { null -> "floor"; Direction.DOWN -> "ceiling"; else -> "wall" }) }
        return JsonObject(values)
    }

    /** Its model: the furniture's, else the kind's icon. */
    private val model: Identifier? get() = poses?.model ?: kind?.icon?.let(Identifier::tryParse)

    private fun pose(): Matrix4f? = poses?.pose(attached, yaw)

    /** The ghost at the root block whose floor's middle is [anchor] (null: none shown), as the face aimed at last had it. */
    fun update(anchor: Vec3?) {
        val model = model?.takeIf { anchor != null && fits && ItemModels.has(it) }
        val pose = pose()
        if (model == null || pose == null) return ghost.remove()
        val at = corner(anchor!!)
        ghost.show(model, at, pose, box(model, pose, at))
    }

    private fun corner(anchor: Vec3) = Vec3(anchor.x - 0.5, anchor.y, anchor.z - 0.5)

    private fun box(model: Identifier, pose: Matrix4f, at: Vec3): AABB? = ModelPreview.bounds(model, ItemDisplayContext.FIXED)?.let { ModelGhost.box(it, pose, at) }

    /**
     * Its box, its front (an arrow on the ground, on floors and ceilings) and how it goes, at [anchor]; refused in red
     * on a face it doesn't go on. False when it has nothing to draw (no poses yet): the tool's marker shows instead.
     */
    fun draw(draw: OverlayDraw, anchor: Vec3, label: String): Boolean {
        val model = model ?: return false
        val pose = pose() ?: return false
        val box = box(model, pose, corner(anchor)) ?: return false
        if (!fits) {
            draw.box(Vec3(box.minX, box.minY, box.minZ), Vec3(box.maxX, box.maxY, box.maxZ), REFUSED, 2f, Colors.withAlpha(REFUSED, 0.1f), onTop = true)
            draw.label(Vec3(box.center.x, box.maxY + 0.5, box.center.z), listOf(label to REFUSED, (refusal() ?: "") to Colors.WHITE), size = 0.7f)
            return true
        }
        draw.box(Vec3(box.minX, box.minY, box.minZ), Vec3(box.maxX, box.maxY, box.maxZ), COLOR, 2f, Colors.withAlpha(COLOR, 0.05f), onTop = true)
        if (attached?.axis?.isHorizontal != true) {
            val radians = Math.toRadians(facing.toDouble())
            val front = Vec3(-sin(radians), 0.0, cos(radians))
            val base = Vec3(box.center.x, box.minY + 0.05, box.center.z).add(front.scale(maxOf(box.xsize, box.zsize) / 2))
            draw.arrow(base, base.add(front), COLOR, onTop = true)
        }
        val how = when (attached) {
            null -> "on the floor · ${facing.roundToInt()}°"
            Direction.DOWN -> "under the ceiling · ${facing.roundToInt()}°"
            else -> "on the wall"
        }
        draw.label(Vec3(box.center.x, box.maxY + 0.5, box.center.z), listOf(label to COLOR, how to Colors.WHITE), size = 0.7f)
        return true
    }

    /** Hides it and forgets the kind. */
    fun clear() {
        ghost.remove()
        kind = null
        poses = null
        face = null
        root = null
        asked++
    }
}
