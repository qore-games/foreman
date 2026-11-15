package games.qore.foreman.server.world

import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.Vector3
import com.sk89q.worldedit.math.transform.AffineTransform
import games.qore.foreman.server.api.RegionCopyEvent
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.structure.Mirror
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.util.BlockVector
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.UUID

/**
 * Where a copy or a move puts things: the block [origin] of the source goes to [position], everything mirrored
 * east–west about it first when [mirror] (x negated), then turned [rotate]° clockwise seen from above about it, as
 * WorldEdit flips and turns a clipboard pasted there; entities mirror and turn about the centre of that block.
 */
class Placement(val origin: BlockVector3, val rotate: Int, val position: BlockVector3, val mirror: Boolean = false) {
    /** WorldEdit's rotateY turns counter-clockwise seen from above; its transforms apply the last one given first. */
    val transform: AffineTransform = AffineTransform().rotateY(-rotate.toDouble()).let { if (mirror) it.scale(-1.0, 1.0, 1.0) else it }

    /** Whether it changes orientations (a turn or a mirror), not only positions. */
    val turns get() = rotate != 0 || mirror

    fun block(p: BlockVector3): BlockVector3 = transform.apply(p.subtract(origin).toVector3()).toBlockPoint().add(position)

    fun block(x: Int, y: Int, z: Int): BlockVector3 = block(BlockVector3.at(x, y, z))

    fun box(b: Box): Box {
        val corners = listOf(b.min, b.max, BlockVector3.at(b.min.x(), b.min.y(), b.max.z()), BlockVector3.at(b.max.x(), b.max.y(), b.min.z())).map(::block)
        return Box(corners.reduce { a, c -> a.getMinimum(c) }, corners.reduce { a, c -> a.getMaximum(c) })
    }

    /** Where an entity at [l] goes in [world], its yaw turned too. */
    fun location(l: Location, world: World): Location {
        val at = position(l.x, l.y, l.z)
        return Location(world, at.x(), at.y(), at.z(), yaw(l.yaw), l.pitch)
    }

    /** Where the exact position [x], [y], [z] goes: turned about the centre of the [origin] block, as entities are. */
    fun position(x: Double, y: Double, z: Double): Vector3 {
        val turned = transform.apply(Vector3.at(x - origin.x() - 0.5, y - origin.y(), z - origin.z() - 0.5))
        return Vector3.at(position.x() + 0.5 + turned.x(), position.y() + turned.y(), position.z() + 0.5 + turned.z())
    }

    /** A yaw mirrored and turned, in [-180, 180). */
    fun yaw(yaw: Float): Float = normalize((if (mirror) -yaw else yaw) + rotate)

    /** Point [p] where this puts it, in [world]: its position turned like an entity's, its yaw and the yaw fields of its data too. */
    fun point(p: Point, world: String = p.world): Point = position(p.x, p.y, p.z).let { at ->
        val layout = Layout.instance
        p.copy(world = world, x = at.x(), y = at.y(), z = at.z(), yaw = p.yaw?.let(::yaw), data = layout.turned(layout.of(p), p.data, rotate, mirror))
    }

    /** Area [a] where this puts it, in [world]: its boxes, and the yaw fields of its data turned. */
    fun area(a: Area, world: String = a.world): Area {
        val layout = Layout.instance
        return a.copy(world = world, boxes = a.boxes.map(::box), data = layout.turned(layout.of(a), a.data, rotate, mirror))
    }

    /** A horizontal face mirrored and turned (item frames, paintings); up and down stay. */
    fun face(face: BlockFace): BlockFace {
        val turns = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)
        val i = turns.indexOf(if (mirror && (face == BlockFace.EAST || face == BlockFace.WEST)) face.oppositeFace else face)
        return if (i < 0) face else turns[(i + rotate / 90) % 4]
    }

    /** The way back: a mirror undoes itself, its turn the same. */
    fun inverse(): Placement = if (mirror) Placement(position, rotate, origin, true) else Placement(position, (360 - rotate) % 360, origin)

    /**
     * What this does to something placed [inner] (turned [rotate]°, mirrored [mirror]) in the source: the turn and mirror
     * it has once placed (a nested copy, an instance carried by a move).
     */
    fun compose(rotate: Int, mirror: Boolean): Pair<Int, Boolean> =
        Math.floorMod(this.rotate + (if (this.mirror) -rotate else rotate), 360) to (this.mirror != mirror)

    /**
     * Mirrors what [display] shows, when this mirrors: its transformation conjugated by the mirror (its translation's x
     * and its rotations' turns about y and z negated), a block display's block state mirrored with it, as the blocks
     * around it are (and shifted back onto its block: a block's model mirrors about its centre). Its position and yaw are
     * [location]'s. A model itself can't be mirrored (its faces would turn inside out): an asymmetric item model shows its
     * unmirrored side.
     */
    fun mirror(display: Display) {
        if (!mirror) return
        val t = display.transformation
        val left = mirrored(t.leftRotation)
        val right = mirrored(t.rightRotation)
        val translation = Vector3f(-t.translation.x, t.translation.y, t.translation.z)
        if (display is BlockDisplay) {
            translation.add(left.transform(right.transform(Vector3f(-1f, 0f, 0f)).mul(t.scale)))
            display.block = display.block.clone().also { it.mirror(Mirror.FRONT_BACK) }
        }
        display.transformation = Transformation(translation, left, Vector3f(t.scale), right)
    }

    /** A rotation seen in a mirror that negates x: its turns about y and z go the other way. */
    private fun mirrored(q: Quaternionf) = Quaternionf(q.x, -q.y, -q.z, q.w)

    fun event(kind: RegionCopyEvent.Kind, sourceWorld: World, source: Box, world: World, entities: Map<UUID, UUID>, clipboard: Boolean = false) = RegionCopyEvent(
        kind, sourceWorld, bounds(source), world, bounds(box(source)), BlockVector(origin.x(), origin.y(), origin.z()),
        position.subtract(origin).let { BlockVector(it.x(), it.y(), it.z()) }, rotate, entities, mirror, clipboard,
    )

    companion object {
        /** A move by [offset], turned [rotate]° about the bottom centre of [box]. */
        fun around(box: Box, rotate: Int, offset: BlockVector3 = BlockVector3.ZERO): Placement =
            AreaAssets.origin(box).let { Placement(it, rotate, it.add(offset)) }

        /** [yaw] in [-180, 180). */
        fun normalize(yaw: Float): Float = ((yaw % 360f + 540f) % 360f) - 180f

        fun bounds(b: Box) = BoundingBox(b.min.x().toDouble(), b.min.y().toDouble(), b.min.z().toDouble(), b.max.x() + 1.0, b.max.y() + 1.0, b.max.z() + 1.0)
    }
}
