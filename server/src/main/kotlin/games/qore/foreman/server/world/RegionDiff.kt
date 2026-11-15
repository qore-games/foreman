package games.qore.foreman.server.world

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.transform.BlockTransformExtent
import games.qore.foreman.server.Foreman
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.TextDisplay
import org.joml.Matrix4f
import org.joml.Quaternionf
import org.joml.Vector3f
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.TreeMap
import java.util.UUID
import kotlin.math.abs

/**
 * A box of a world compared with where it was copied or moved: [placement] takes its blocks to the target's, mirrored
 * and turned. Compared: block states (flipped and turned as WorldEdit does), the block data entries Foreman copies ([ChunkBlockData]:
 * their content), entities (type, position and yaw turned, pitch, name, a fixed display's pose: its yaw, pitch and
 * transformation together, turned, as the model shows; what it shows,
 * an interaction's size, a hanging entity's facing and item, their persistent data), furniture as its provider sees it
 * (type, root, yaw turned, state, cells turned) and Foreman's points and areas lying inside (an area spanning the whole
 * box, the asset's own, aside). An interaction's yaw and pitch aren't compared (its box is along the axes). In data, a
 * source entity's UUID matches its counterpart's, a number that is the source's turned by the rotation and whole turns
 * (a yaw) and a face name turned with it count as the same, and Foreman's own instance tag is left out. Read-only. Main
 * thread.
 */
class RegionDiff(
    private val source: World, private val box: Box, private val world: World, private val placement: Placement, private val limit: Int,
) {
    /** What one kind of thing gave. */
    class Count {
        var compared = 0
        var same = 0
        var differ = 0
        var missing = 0
        var extra = 0
    }

    /** A difference: what ([kind]), where (target coordinates, or source ones for what is missing), and how. */
    class Difference(val kind: String, val at: List<Double>, val text: String)

    val target: Box = placement.box(box)
    val counts = LinkedHashMap<String, Count>()
    val differences = ArrayList<Difference>()
    var total = 0
        private set
    /** Data values that matched as turned (yaws, faces), and entity UUIDs matched through their counterparts. */
    var turned = 0
        private set
    var mapped = 0
        private set

    private val uuids = HashMap<UUID, UUID>()

    private fun count(kind: String) = counts.getOrPut(kind) { Count() }

    private fun differ(kind: String, x: Number, y: Number, z: Number, text: String) {
        total++
        if (differences.size < limit) differences += Difference(kind, listOf(x.toDouble(), y.toDouble(), z.toDouble()), text)
    }

    fun run(): RegionDiff {
        entities()
        blocks()
        blockData()
        furniture()
        points()
        areas()
        return this
    }

    // ---- Blocks -------------------------------------------------------------------------------------------------------

    private fun blocks() {
        val count = count("blocks")
        val from = Snapshots(source, box.min.x(), box.min.z(), box.max.x(), box.max.z()).load()
        val to = Snapshots(world, target.min.x(), target.min.z(), target.max.x(), target.max.z()).load()
        val turned = HashMap<BlockData, String>()
        for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) for (x in box.min.x()..box.max.x()) {
            val expected = turned.getOrPut(from.data(x, y, z)) { turn(from.data(x, y, z)) }
            val p = placement.block(x, y, z)
            val found = to.data(p.x(), p.y(), p.z()).getAsString(false)
            count.compared++
            if (found == expected) count.same++
            else {
                count.differ++
                differ("block", p.x(), p.y(), p.z(), "expected $expected, found $found")
            }
        }
    }

    /** [data] flipped and turned as WorldEdit flips and turns a clipboard's blocks. */
    private fun turn(data: BlockData): String =
        if (!placement.turns) data.getAsString(false)
        else BukkitAdapter.adapt(BlockTransformExtent.transform(BukkitAdapter.adapt(data), placement.transform)).getAsString(false)

    // ---- Block data ---------------------------------------------------------------------------------------------------

    private fun blockData() {
        val count = count("block_data")
        val found = ChunkBlockData.read(world, target).associateBy { Triple(it.namespace, it.x, it.y) to it.z }.toMutableMap()
        for (e in ChunkBlockData.read(source, box)) {
            val p = placement.block(e.x, e.y, e.z)
            val there = found.remove(Triple(e.namespace, p.x(), p.y()) to p.z())
            count.compared++
            if (there == null) {
                count.missing++
                differ("block_data", p.x(), p.y(), p.z(), "${e.namespace} entry missing")
                continue
            }
            val diffs = ArrayList<String>()
            compare("", Nbt.read(e.data), Nbt.read(there.data), diffs)
            if (diffs.isEmpty()) count.same++
            else {
                count.differ++
                differ("block_data", p.x(), p.y(), p.z(), "${e.namespace}: " + diffs.joinToString("; "))
            }
        }
        for (e in found.values) {
            count.extra++
            differ("block_data", e.x, e.y, e.z, "${e.namespace} entry not in the source")
        }
    }

    // ---- Entities -----------------------------------------------------------------------------------------------------

    private fun entities() {
        val count = count("entities")
        val left = Library.entities(world, target).toMutableList()
        val pairs = ArrayList<Pair<Entity, Entity>>()
        for (e in Library.entities(source, box)) {
            val at = placement.location(e.location, world)
            val near = left.filter { it.type == e.type && it.location.distanceSquared(at) < EPS * EPS }
            // Several at one spot (a display on a root): the one that is the same, else the nearest.
            val match = near.firstOrNull { entityDiffs(e, it, null).isEmpty() } ?: near.minByOrNull { it.location.distanceSquared(at) }
            count.compared++
            if (match == null) {
                count.missing++
                differ("entity", at.x, at.y, at.z, "${e.type.key.key} ${e.uniqueId} missing")
                continue
            }
            left.remove(match)
            pairs += e to match
            uuids[e.uniqueId] = match.uniqueId
        }
        // UUIDs in data are known once every entity has its counterpart.
        for ((e, there) in pairs) {
            val diffs = entityDiffs(e, there, this)
            if (diffs.isEmpty()) count.same++
            else {
                count.differ++
                differ("entity", there.location.x, there.location.y, there.location.z, "${e.type.key.key} ${there.uniqueId}: " + diffs.joinToString("; "))
            }
        }
        for (e in left) {
            count.extra++
            differ("entity", e.location.x, e.location.y, e.location.z, "${e.type.key.key} ${e.uniqueId} not in the source")
        }
    }

    /** How [there] differs from [e] turned; [diff] counts mapped and turned data values (null: a trial). */
    private fun entityDiffs(e: Entity, there: Entity, diff: RegionDiff?): List<String> {
        val out = ArrayList<String>()
        // An interaction is a box along the axes: its yaw and pitch show nothing. A fixed display's are in its pose.
        if (e !is Interaction && !(fixed(e) && fixed(there))) {
            if (angle(placement.yaw(e.location.yaw), there.location.yaw) > ANGLE) out += "yaw ${placement.yaw(e.location.yaw)} expected, ${there.location.yaw} found"
            if (angle(e.location.pitch, there.location.pitch) > ANGLE) out += "pitch ${e.location.pitch} → ${there.location.pitch}"
        }
        if (e.customName() != there.customName()) out += "name differs"
        if (e is Display && there is Display) display(e, there, out)
        if (e is Interaction && there is Interaction &&
            (e.interactionWidth != there.interactionWidth || e.interactionHeight != there.interactionHeight || e.isResponsive != there.isResponsive)
        ) out += "interaction ${e.interactionWidth}x${e.interactionHeight} → ${there.interactionWidth}x${there.interactionHeight}"
        if (e is Hanging && there is Hanging && placement.face(e.facing) != there.facing) out += "facing ${placement.face(e.facing)} expected, ${there.facing} found"
        if (e is ItemFrame && there is ItemFrame && (e.item != there.item || e.rotation != there.rotation)) out += "item frame content differs"
        val data = ArrayList<String>()
        val before = turned to mapped
        compare("", pdc(e), pdc(there), data)
        if (diff == null) {
            turned = before.first
            mapped = before.second
        }
        data.forEach { out += "data $it" }
        return out
    }

    private fun display(e: Display, there: Display, out: MutableList<String>) {
        val a = e.transformation
        val b = there.transformation
        val pose = if (fixed(e) && fixed(there)) close(pose(e, placement.yaw(e.location.yaw)), pose(there, there.location.yaw))
        else close(a.translation, b.translation) && close(a.leftRotation, b.leftRotation) && close(a.scale, b.scale) && close(a.rightRotation, b.rightRotation)
        if (!pose) {
            out += "pose: yaw ${placement.yaw(e.location.yaw)} pitch ${e.location.pitch} ${fmt(a.translation)} ${fmt(a.leftRotation)} ${fmt(a.scale)} ${fmt(a.rightRotation)} expected, " +
                "yaw ${there.location.yaw} pitch ${there.location.pitch} ${fmt(b.translation)} ${fmt(b.leftRotation)} ${fmt(b.scale)} ${fmt(b.rightRotation)} found"
        }
        if (e.billboard != there.billboard) out += "billboard ${e.billboard} → ${there.billboard}"
        if (e.brightness != there.brightness || e.viewRange != there.viewRange || e.displayWidth != there.displayWidth || e.displayHeight != there.displayHeight ||
            e.shadowRadius != there.shadowRadius || e.shadowStrength != there.shadowStrength || e.glowColorOverride != there.glowColorOverride
        ) out += "display settings differ (brightness, view range, size, shadow or glow)"
        when {
            e is ItemDisplay && there is ItemDisplay -> {
                if (e.itemStack != there.itemStack) out += "item ${e.itemStack.type.key.key} → ${there.itemStack.type.key.key} (components differ)"
                if (e.itemDisplayTransform != there.itemDisplayTransform) out += "item transform ${e.itemDisplayTransform} → ${there.itemDisplayTransform}"
            }
            e is BlockDisplay && there is BlockDisplay -> if (e.block.getAsString(false) != there.block.getAsString(false)) out += "block ${e.block.getAsString(false)} → ${there.block.getAsString(false)}"
            e is TextDisplay && there is TextDisplay -> if (e.text() != there.text() || e.lineWidth != there.lineWidth || e.backgroundColor != there.backgroundColor ||
                e.alignment != there.alignment || e.textOpacity != there.textOpacity || e.isShadowed != there.isShadowed || e.isSeeThrough != there.isSeeThrough
            ) out += "text differs"
        }
    }

    /** A display that turns with its yaw and pitch (not toward viewers). */
    private fun fixed(e: Entity) = e is Display && e.billboard == Display.Billboard.FIXED

    /**
     * Where a fixed display's model goes about its position, seen at [yaw]: its orientation times its transformation. Two
     * poses that are the same show the same model, however the turn is split between the yaw and the transformation.
     */
    private fun pose(d: Display, yaw: Float): Matrix4f {
        val t = d.transformation
        return Matrix4f().rotate(DisplayEdits.rotation(yaw, d.location.pitch)).translate(t.translation).rotate(t.leftRotation).scale(t.scale).rotate(t.rightRotation)
    }

    /** An entity's persistent data as a tree, Foreman's instance tag aside. */
    private fun pdc(e: Entity): Any? = (Nbt.read(e.persistentDataContainer.serializeToBytes()) as? Map<*, *>)?.filterKeys { it != AreaAssets.INSTANCE_KEY.toString() }

    // ---- Furniture ----------------------------------------------------------------------------------------------------

    private fun furniture() {
        if (!Furniture.available) return
        val count = count("furniture")
        val left = Furniture.inBox(world, target).toMutableList()
        for (f in Furniture.inBox(source, box)) {
            val root = placement.block(f.x, f.y, f.z)
            val there = left.firstOrNull { it.id == f.id && it.x == root.x() && it.y == root.y() && it.z == root.z() }
            count.compared++
            if (there == null) {
                count.missing++
                differ("furniture", root.x(), root.y(), root.z(), "${f.id} missing")
                continue
            }
            left.remove(there)
            val out = ArrayList<String>()
            if (angle(placement.yaw(f.yaw), there.yaw) > ANGLE) out += "yaw ${placement.yaw(f.yaw)} expected, ${there.yaw} found"
            if (f.state != there.state) out += "state ${f.state} → ${there.state}"
            val cells = f.cells.map(placement::block).toSet()
            if (cells != there.cells.toSet()) out += "cells ${cells.map(Box::fmt).sorted()} expected, ${there.cells.map(Box::fmt).sorted()} found"
            if (f.interactive != there.interactive) out += "interactive ${f.interactive} → ${there.interactive}"
            if (out.isEmpty()) count.same++
            else {
                count.differ++
                differ("furniture", root.x(), root.y(), root.z(), "${f.id}: " + out.joinToString("; "))
            }
        }
        for (f in left) {
            count.extra++
            differ("furniture", f.x, f.y, f.z, "${f.id} not in the source")
        }
    }

    // ---- Points and areas ---------------------------------------------------------------------------------------------

    private fun points() {
        val worlds = Foreman.instance.worlds
        val count = count("points")
        val left = worlds.points(world.name).filter { it.inside(target) }.toMutableList()
        for (p in worlds.points(source.name).filter { it.inside(box) }) {
            val at = placement.point(p, world.name)
            val there = left.firstOrNull { it.type == p.type && it.kind == p.kind && abs(it.x - at.x) < EPS && abs(it.y - at.y) < EPS && abs(it.z - at.z) < EPS }
            count.compared++
            if (there == null) {
                count.missing++
                differ("point", at.x, at.y, at.z, "${p.type}${if (p.kind.isEmpty()) "" else ":${p.kind}"} missing")
                continue
            }
            left.remove(there)
            val out = ArrayList<String>()
            if ((at.yaw == null) != (there.yaw == null) || at.yaw != null && angle(at.yaw, there.yaw!!) > ANGLE) out += "yaw ${at.yaw} expected, ${there.yaw} found"
            if (p.pitch != there.pitch) out += "pitch ${p.pitch} → ${there.pitch}"
            if (p.room != there.room) out += "room ${p.room} → ${there.room}"
            if (at.data != there.data) out += "data ${at.data} → ${there.data}"
            if (out.isEmpty()) count.same++
            else {
                count.differ++
                differ("point", there.x, there.y, there.z, "${p.type}: " + out.joinToString("; "))
            }
        }
        for (p in left) {
            count.extra++
            differ("point", p.x, p.y, p.z, "${p.type}${if (p.kind.isEmpty()) "" else ":${p.kind}"} not in the source")
        }
    }

    private fun areas() {
        val worlds = Foreman.instance.worlds
        val count = count("areas")
        val left = worlds.areas(world.name).filter { target.containsBox(it.bounds) && it.bounds != target }.toMutableList()
        for (a in worlds.areas(source.name).filter { box.containsBox(it.bounds) && it.bounds != box }) {
            val boxes = a.boxes.map(placement::box).toSet()
            val there = left.firstOrNull { it.boxes.toSet() == boxes }
            val at = boxes.first().min
            count.compared++
            if (there == null) {
                count.missing++
                differ("area", at.x(), at.y(), at.z(), "${a.name} missing (${boxes.joinToString(" + ") { it.describe() }})")
                continue
            }
            left.remove(there)
            val out = ArrayList<String>()
            if (a.kind != there.kind) out += "kind ${a.kind} → ${there.kind}"
            if (a.note != there.note) out += "note differs"
            if (a.theme != there.theme) out += "theme differs"
            if ((a.tags - AreaAssets.TAG).toSet() != (there.tags - AreaAssets.TAG).toSet()) out += "tags ${a.tags} → ${there.tags}"
            if (out.isEmpty()) count.same++
            else {
                count.differ++
                differ("area", at.x(), at.y(), at.z(), "${a.name} → ${there.name}: " + out.joinToString("; "))
            }
        }
        for (a in left) {
            count.extra++
            differ("area", a.bounds.min.x(), a.bounds.min.y(), a.bounds.min.z(), "${a.name} not in the source")
        }
    }

    // ---- Data trees ---------------------------------------------------------------------------------------------------

    /** Adds to [out] how tree [b] differs from tree [a] (from [Nbt.read]), under [path]. */
    private fun compare(path: String, a: Any?, b: Any?, out: MutableList<String>) {
        when {
            a is Map<*, *> && b is Map<*, *> -> for (k in (a.keys + b.keys).map { it.toString() }.toSortedSet()) {
                val p = if (path.isEmpty()) k else "$path.$k"
                when {
                    k !in a -> out += "$p only in the target"
                    k !in b -> out += "$p only in the source"
                    else -> compare(p, a[k], b[k], out)
                }
            }
            a is List<*> && b is List<*> ->
                if (a.size != b.size) out += "$path: ${a.size} values → ${b.size}"
                else a.indices.forEach { compare("$path[$it]", a[it], b[it], out) }
            a is String && b is String -> when {
                a == b -> {}
                uuid(a)?.let { uuids[it]?.toString() == b } == true -> mapped++
                placement.turns && face(a)?.let { placement.face(it).name.equals(b, true) } == true -> turned++
                else -> out += "$path ${quote(a)} → ${quote(b)}"
            }
            a is IntArray && b is IntArray -> when {
                a.contentEquals(b) -> {}
                a.size == 4 && uuids[uuidOf(a)]?.let { uuidOf(b) == it } == true -> mapped++
                else -> out += "$path ${a.toList()} → ${b.toList()}"
            }
            (a is Float || a is Double) && (b is Float || b is Double) -> {
                val x = (a as Number).toDouble()
                val y = (b as Number).toDouble()
                when {
                    abs(x - y) <= 1e-6 * maxOf(1.0, abs(x)) -> {}
                    angle(placement.yaw(x.toFloat()), y.toFloat()) <= ANGLE -> turned++
                    else -> out += "$path $x → $y"
                }
            }
            a is ByteArray && b is ByteArray -> if (!a.contentEquals(b)) out += "$path bytes differ"
            a is LongArray && b is LongArray -> if (!a.contentEquals(b)) out += "$path longs differ"
            a != b -> out += "$path ${quote(a)} → ${quote(b)}"
        }
    }

    private fun quote(v: Any?) = if (v is String) "\"${v.take(80)}\"" else v.toString()

    private fun uuid(s: String): UUID? = if (s.length == 36) runCatching { UUID.fromString(s) }.getOrNull() else null

    private fun uuidOf(a: IntArray) = UUID((a[0].toLong() shl 32) or (a[1].toLong() and 0xffffffffL), (a[2].toLong() shl 32) or (a[3].toLong() and 0xffffffffL))

    private fun face(s: String): BlockFace? = runCatching { BlockFace.valueOf(s.uppercase()) }.getOrNull()?.takeIf { it in HORIZONTAL }

    /** Reads the uncompressed NBT that persistent data containers serialize to: compounds as sorted maps. */
    internal object Nbt {
        fun read(bytes: ByteArray): Any? = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val type = input.readByte().toInt()
            if (type == 0) return null
            input.readUTF()
            payload(input, type)
        }

        private fun payload(input: DataInputStream, type: Int): Any? = when (type) {
            1 -> input.readByte()
            2 -> input.readShort()
            3 -> input.readInt()
            4 -> input.readLong()
            5 -> input.readFloat()
            6 -> input.readDouble()
            7 -> ByteArray(input.readInt()).also { input.readFully(it) }
            8 -> input.readUTF()
            9 -> {
                val element = input.readByte().toInt()
                List(input.readInt()) { payload(input, element) }
            }
            10 -> TreeMap<String, Any?>().also { map ->
                while (true) {
                    val t = input.readByte().toInt()
                    if (t == 0) break
                    map[input.readUTF()] = payload(input, t)
                }
            }
            11 -> IntArray(input.readInt()) { input.readInt() }
            12 -> LongArray(input.readInt()) { input.readLong() }
            else -> throw IllegalStateException("NBT tag type $type")
        }
    }

    companion object {
        /** How far apart positions may be (blocks), and angles (degrees), to count as the same. */
        const val EPS = 1e-3
        const val ANGLE = 0.01f
        private val HORIZONTAL = setOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

        /** The difference of two angles in degrees, 0 to 180. */
        fun angle(a: Float, b: Float): Float = abs(Placement.normalize(a - b))

        private fun close(a: Matrix4f, b: Matrix4f): Boolean {
            val x = FloatArray(16).also { a.get(it) }
            val y = FloatArray(16).also { b.get(it) }
            return x.indices.all { abs(x[it] - y[it]) < 1e-4f }
        }

        private fun close(a: Vector3f, b: Vector3f) = abs(a.x - b.x) < 1e-4f && abs(a.y - b.y) < 1e-4f && abs(a.z - b.z) < 1e-4f

        /** The same rotation (q and -q are). */
        private fun close(a: Quaternionf, b: Quaternionf) = abs(a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w) > 1 - 1e-6f

        private fun fmt(v: Vector3f) = "[%.4g, %.4g, %.4g]".format(v.x, v.y, v.z)

        private fun fmt(q: Quaternionf) = "[%.4g, %.4g, %.4g, %.4g]".format(q.x, q.y, q.z, q.w)
    }
}
