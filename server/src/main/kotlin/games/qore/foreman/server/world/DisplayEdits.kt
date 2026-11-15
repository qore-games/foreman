package games.qore.foreman.server.world

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.mcp.ToolError
import io.papermc.paper.entity.TeleportFlag
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.EntitySnapshot
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.inventory.ItemStack
import org.bukkit.util.BoundingBox
import org.bukkit.util.Transformation
import org.joml.Matrix3d
import org.joml.Matrix4d
import org.joml.Matrix4f
import org.joml.Quaterniond
import org.joml.Quaternionf
import org.joml.Vector3d
import org.joml.Vector3f
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Display entities (item, block, text) one by one, to perfect furniture and decor: read with their pose, moved, turned,
 * scaled, spawned and removed, each call one undo step. A furniture's root display stays its provider's: it stays on
 * its root block and keeps its yaw and pitch (a sub-block move or a turn goes into its transformation), a move by whole
 * blocks places it again through the provider, a removal goes through the provider. Main thread.
 */
object DisplayEdits {
    /** A display, and the furniture whose root it is. */
    class Found(val display: Display, val furniture: Furniture.Placed?)

    /** The displays whose position is in [box], with their furniture, bottom up. */
    fun inBox(world: World, box: Box): List<Found> {
        val bounds = BoundingBox(box.min.x().toDouble(), box.min.y().toDouble(), box.min.z().toDouble(), box.max.x() + 1.0, box.max.y() + 1.0, box.max.z() + 1.0)
        Chunks.keepLoaded(world, box)
        val roots = Furniture.inBox(world, box.grow(2, 2, 2)).filter { it.entity != null }.associateBy { it.entity }
        return world.getNearbyEntities(bounds) { it is Display && bounds.contains(it.location.toVector()) }
            .map { Found(it as Display, roots[it.uniqueId]) }
            .sortedWith(compareBy({ it.display.location.y }, { it.display.location.x }, { it.display.location.z }))
    }

    /**
     * The loaded display [id] (or what it became) of [world] (null: of any world, when only one has it) with its
     * furniture; null when there is no such display.
     */
    fun find(world: World?, id: UUID): Found? {
        val entity = Edits.loaded(world, id) ?: Edits.loaded(world, Edits.current(id)) ?: return null
        val display = entity as? Display ?: throw ToolError("it is a ${entity.type.key.key}, not a display")
        return Found(display, furnitureOf(display))
    }

    private fun furnitureOf(display: Display): Furniture.Placed? {
        val provider = Furniture.provider ?: return null
        return provider.find(display.world, around(display.location)).firstOrNull { it.entity == display.uniqueId }?.let(Furniture::of)
    }

    private fun around(l: Location) = BoundingBox(l.x - 2, l.y - 2, l.z - 2, l.x + 2, l.y + 2, l.z + 2)

    // ---- Reading ------------------------------------------------------------------------------------------------------

    fun kind(d: Display) = when (d) {
        is ItemDisplay -> "item"
        is BlockDisplay -> "block"
        else -> "text"
    }

    /** What an item display shows: its item's `item_model`, else its material; null when empty. */
    fun model(d: ItemDisplay): String? {
        val stack = d.itemStack
        if (stack.type.isAir) return null
        val meta = stack.itemMeta
        return if (meta != null && meta.hasItemModel()) meta.itemModel.toString() else stack.type.key.toString()
    }

    private fun text(d: TextDisplay) = PlainTextComponentSerializer.plainText().serialize(d.text())

    fun json(f: Found): JsonObject = JsonObject().apply {
        val d = f.display
        val l = d.location
        addProperty("uuid", d.uniqueId.toString())
        addProperty("type", kind(d))
        addProperty("world", d.world.name)
        add("position", numbers(l.x, l.y, l.z))
        addProperty("yaw", round(l.yaw.toDouble()))
        addProperty("pitch", round(l.pitch.toDouble()))
        addProperty("billboard", d.billboard.name.lowercase())
        add("transformation", json(d.transformation))
        when (d) {
            is ItemDisplay -> model(d)?.let { addProperty("model", it) }
            is BlockDisplay -> addProperty("block", d.block.asString)
            is TextDisplay -> addProperty("text", text(d))
        }
        f.furniture?.let { p ->
            add("furniture", JsonObject().apply {
                addProperty("id", p.id)
                p.handle?.let { addProperty("handle", it.toString()) }
            })
        }
        if (!d.isPersistent) addProperty("persistent", false)
    }

    fun json(t: Transformation) = JsonObject().apply {
        add("translation", numbers(t.translation.x.toDouble(), t.translation.y.toDouble(), t.translation.z.toDouble()))
        add("left_rotation", numbers(t.leftRotation.x.toDouble(), t.leftRotation.y.toDouble(), t.leftRotation.z.toDouble(), t.leftRotation.w.toDouble()))
        add("scale", numbers(t.scale.x.toDouble(), t.scale.y.toDouble(), t.scale.z.toDouble()))
        add("right_rotation", numbers(t.rightRotation.x.toDouble(), t.rightRotation.y.toDouble(), t.rightRotation.z.toDouble(), t.rightRotation.w.toDouble()))
    }

    /** One line for the AI. */
    fun describe(f: Found): String {
        val d = f.display
        val l = d.location
        val t = d.transformation
        val what = when (d) {
            is ItemDisplay -> "item ${model(d) ?: "(empty)"}"
            is BlockDisplay -> "block ${d.block.asString}"
            is TextDisplay -> "text \"${text(d).replace('\n', ' ').take(40)}\""
            else -> "display"
        }
        fun n(x: Float) = "%.3f".format(Locale.ROOT, x).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
        fun v(vararg x: Float) = x.joinToString(", ", "[", "]") { n(it) }
        return "${d.uniqueId} $what at ${v(l.x.toFloat(), l.y.toFloat(), l.z.toFloat())} yaw ${n(l.yaw)} pitch ${n(l.pitch)}" +
            (if (d.billboard != Display.Billboard.FIXED) " billboard ${d.billboard.name.lowercase()}" else "") +
            " translation ${v(t.translation.x, t.translation.y, t.translation.z)}" +
            " left ${v(t.leftRotation.x, t.leftRotation.y, t.leftRotation.z, t.leftRotation.w)}" +
            " scale ${v(t.scale.x, t.scale.y, t.scale.z)}" +
            " right ${v(t.rightRotation.x, t.rightRotation.y, t.rightRotation.z, t.rightRotation.w)}" +
            (f.furniture?.let { " — furniture ${it.id} rooted at [${it.x}, ${it.y}, ${it.z}]" } ?: "") +
            (if (!d.isPersistent) " (not saved)" else "")
    }

    private fun round(v: Double) = Math.round(v * 1e6) / 1e6

    private fun numbers(vararg v: Double) = JsonArray().apply { v.forEach { add(round(it)) } }

    // ---- Editing ------------------------------------------------------------------------------------------------------

    /** What an edit sets: absolute values, null for what stays. */
    class Request(
        val position: Vector3d? = null, val yaw: Float? = null, val pitch: Float? = null,
        val translation: Vector3f? = null, val left: Quaternionf? = null, val scale: Vector3f? = null, val right: Quaternionf? = null,
        val billboard: Display.Billboard? = null,
    ) {
        fun over(t: Transformation) = Transformation(
            Vector3f(translation ?: t.translation), Quaternionf(left ?: t.leftRotation), Vector3f(scale ?: t.scale), Quaternionf(right ?: t.rightRotation),
        )
    }

    /**
     * Applies [request] to [f], recorded in [change] for undo. A furniture moved by whole blocks becomes another entity:
     * [replaced] gets its old and new UUIDs (also when it could not move and was put back).
     */
    fun edit(f: Found, request: Request, change: Change, replaced: MutableMap<UUID, UUID>) {
        val d = f.display
        val from = d.location
        val target = Location(d.world, request.position?.x ?: from.x, request.position?.y ?: from.y, request.position?.z ?: from.z, request.yaw ?: from.yaw, request.pitch ?: from.pitch)
        val wanted = request.over(d.transformation)
        if (f.furniture != null) return editFurniture(f, target, wanted, request.billboard, change, replaced)

        val old = d.transformation
        val billboard = d.billboard
        if (target.distanceSquared(from) > 1e-12 || target.yaw != from.yaw || target.pitch != from.pitch) {
            if (d.isInsideVehicle) throw ToolError("it rides another entity: move that one")
            if (!d.teleport(target, TeleportFlag.EntityState.RETAIN_PASSENGERS)) throw ToolError("it could not be moved there")
        }
        d.transformation = wanted
        request.billboard?.let { d.billboard = it }
        change.touch(from)
        change.touch(target)
        val id = d.uniqueId
        change.restores += Runnable {
            (Edits.entity(id, target) as? Display)?.let { e ->
                e.teleport(from, TeleportFlag.EntityState.RETAIN_PASSENGERS)
                e.transformation = old
                e.billboard = billboard
            }
        }
    }

    /**
     * A furniture's root display shown at [target] with [wanted]: whole blocks of the move (rounded) place the furniture
     * again there through its provider, the rest of the move and a turn go into the transformation of its display, which
     * keeps its own position and orientation.
     */
    private fun editFurniture(f: Found, target: Location, wanted: Transformation, billboard: Display.Billboard?, change: Change, replaced: MutableMap<UUID, UUID>) {
        val from = f.display.location
        val k = BlockVector3.at((target.x - from.x).roundToInt(), (target.y - from.y).roundToInt(), (target.z - from.z).roundToInt())
        val d = if (k == BlockVector3.ZERO) f.display else reroot(f, k, change, replaced)
        val at = d.location
        val inverse = rotation(at.yaw, at.pitch).conjugate()
        val turn = if (target.yaw == at.yaw && target.pitch == at.pitch) Quaternionf() else Quaternionf(inverse).mul(rotation(target.yaw, target.pitch))
        val offset = Vector3f((target.x - at.x).toFloat(), (target.y - at.y).toFloat(), (target.z - at.z).toFloat())
        if (offset.lengthSquared() < 1e-8f) offset.zero()
        val old = d.transformation
        val oldBillboard = d.billboard
        d.transformation = Transformation(
            inverse.transform(offset).add(turn.transform(Vector3f(wanted.translation))),
            Quaternionf(turn).mul(wanted.leftRotation).normalize(), Vector3f(wanted.scale), Quaternionf(wanted.rightRotation),
        )
        billboard?.let { d.billboard = it }
        change.touch(at)
        val id = d.uniqueId
        change.restores += Runnable {
            (Edits.entity(id, at) as? Display)?.let { e ->
                e.transformation = old
                e.billboard = oldBillboard
            }
        }
    }

    /** The orientation of a display entity at [yaw] and [pitch], as the client turns it. */
    fun rotation(yaw: Float, pitch: Float): Quaternionf =
        Quaternionf().rotationYXZ(Math.toRadians(-yaw.toDouble()).toFloat(), Math.toRadians(pitch.toDouble()).toFloat(), 0f)

    /** Places furniture [f] again [k] blocks away, as it is (its display copied). Returns its new root display. */
    private fun reroot(f: Found, k: BlockVector3, change: Change, replaced: MutableMap<UUID, UUID>): Display {
        val placed = f.furniture!!
        val provider = Furniture.require()
        val handle = placed.handle ?: throw ToolError("its provider gives no handle to move it by")
        val display = f.display
        val world = display.world
        val root = BlockVector3.at(placed.x, placed.y, placed.z)
        val newRoot = root.add(k)
        val type = provider.type(placed.id) ?: throw ToolError("${placed.id} is not a furniture type any more")
        Chunks.keepLoaded(world, Box(newRoot.subtract(4, 0, 4), newRoot.add(4, 0, 4)))
        val taken = type.footprint().map { Arrange.cell(newRoot, it, placed.yaw) }
            .filter { c -> c !in placed.cells && !world.getBlockAt(c.x(), c.y(), c.z()).isReplaceable }
        if (taken.isNotEmpty()) throw ToolError(
            "${placed.id} can't move to root ${Box.fmt(newRoot)}: " + taken.take(4).joinToString(", ") { "${Box.fmt(it)} is ${world.getType(it.x(), it.y(), it.z()).key.key}" },
        )
        val snapshot = display.createSnapshot() ?: throw ToolError("its display can't be copied")
        val from = display.location
        val to = from.clone().add(k.x().toDouble(), k.y().toDouble(), k.z().toDouble())
        if (!provider.remove(world, handle)) throw ToolError("its provider refused to remove it")
        val moved = put(world, snapshot, to, placed, newRoot)
        if (moved == null) {
            put(world, snapshot, from, placed, root)?.let { back -> remap(handle, display.uniqueId, back, replaced) }
                ?: throw ToolError("its provider refused to place it at ${Box.fmt(newRoot)}, and to put it back: it is gone (undo can't bring it back)")
            throw ToolError("its provider refused to place it at ${Box.fmt(newRoot)}")
        }
        remap(handle, display.uniqueId, moved, replaced)
        change.touch(from)
        change.touch(to)
        change.restores += Runnable {
            Furniture.provider?.remove(world, Furniture.current(moved.handle))
            put(world, snapshot, from, placed, root)?.let { back -> remap(moved.handle, moved.display.uniqueId, back, null) }
        }
        return moved.display
    }

    /** A furniture placed again: its handle and root display. */
    private class Put(val handle: UUID, val display: Display)

    /**
     * Furniture [f] as [snapshot] at [at] (its root [root]): the copy of its display taken in by the provider, else the
     * furniture placed again by it (the copy removed). Null when the provider refuses both.
     */
    private fun put(world: World, snapshot: EntitySnapshot, at: Location, f: Furniture.Placed, root: BlockVector3): Put? {
        val provider = Furniture.provider ?: return null
        val copy = snapshot.createEntity(at)
        copy.setRotation(at.yaw, at.pitch)
        provider.find(world, around(at)).firstOrNull { it.entity == copy.uniqueId }?.let { return Put(it.handle, copy as Display) }
        copy.remove()
        val type = provider.type(f.id) ?: return null
        val placed = provider.spawn(type, Location(world, root.x().toDouble(), root.y().toDouble(), root.z().toDouble()), f.yaw, f.state) ?: return null
        val display = placed.entity?.let { world.getEntity(it) } as? Display
        if (display == null) {
            provider.remove(world, placed.handle)
            return null
        }
        display.transformation = snapshotTransformation(snapshot, world) ?: display.transformation
        return Put(placed.handle, display)
    }

    /** The transformation a display snapshot holds. */
    private fun snapshotTransformation(snapshot: EntitySnapshot, world: World): Transformation? =
        (snapshot.createEntity(world) as? Display)?.transformation

    private fun remap(handle: UUID, entity: UUID, now: Put, replaced: MutableMap<UUID, UUID>?) {
        Furniture.replaced(Furniture.current(handle), now.handle)
        Edits.replaced(Edits.current(entity), now.display.uniqueId)
        replaced?.put(entity, now.display.uniqueId)
    }

    // ---- Removing -----------------------------------------------------------------------------------------------------

    /** Removes [f] (a furniture through its provider), recorded in [change] so undo puts it back as it was. */
    fun remove(f: Found, change: Change) {
        val d = f.display
        val at = d.location
        val snapshot = d.createSnapshot() ?: throw ToolError("its display can't be copied for undo")
        val placed = f.furniture
        if (placed != null) {
            val handle = placed.handle ?: throw ToolError("its provider gives no handle to remove it by")
            if (!Furniture.require().remove(d.world, handle)) throw ToolError("its provider refused to remove it")
            val root = BlockVector3.at(placed.x, placed.y, placed.z)
            change.restores += Runnable { put(d.world, snapshot, at, placed, root)?.let { remap(handle, d.uniqueId, it, null) } }
        } else {
            d.remove()
            change.restores += Runnable {
                val copy = snapshot.createEntity(at)
                copy.setRotation(at.yaw, at.pitch)
                Edits.replaced(Edits.current(d.uniqueId), copy.uniqueId)
            }
        }
        change.touch(at)
    }

    // ---- Spawning -----------------------------------------------------------------------------------------------------

    /** What a new display shows: an item model, a block or a text. */
    class Shows(val model: String? = null, val block: BlockData? = null, val text: Component? = null)

    /**
     * How far an item model's display lifts it so its lowest point is at the display's height (its `fixed` transform
     * applied), for a scale of 1; null when the model isn't in the server's resources. Off the main thread is fine.
     */
    fun lift(model: String): Double? = Models.item(model)?.let { m ->
        val baked = m.baked.transformed(m.fixed)
        if (baked.empty) null else -baked.min.y
    }

    /**
     * A new display of [shows] at [position], recorded in [change]: a whole-number position is a block, the display
     * standing at the centre of its floor; otherwise exactly there. [yaw] and [pitch] turn it, [scale] multiplies its
     * default size, [request] then sets the parts of its transformation it gives. An item model shows as the furniture
     * that uses it would, else stands on the floor ([lift]) with its front (the north side of its model) toward a player
     * looking at [yaw]; a block fills the block, turning about its bottom centre; a text faces every viewer.
     */
    fun spawn(world: World, shows: Shows, position: Vector3d, yaw: Float, pitch: Float, scale: Float, lift: Double?, request: Request?, change: Change): Display {
        val block = position.x % 1.0 == 0.0 && position.y % 1.0 == 0.0 && position.z % 1.0 == 0.0
        val at = Location(world, position.x + if (block) 0.5 else 0.0, position.y, position.z + if (block) 0.5 else 0.0, yaw, pitch)
        Chunks.keepLoaded(world, BlockVector3.at(at.blockX, at.blockY, at.blockZ).let { Box(it, it) })
        val display: Display = when {
            shows.model != null -> {
                val key = NamespacedKey.fromString(shows.model) ?: throw ToolError("Invalid model id '${shows.model}' (namespace:path)")
                val transform = modelTransform(shows.model, yaw, pitch, scale, lift)
                world.spawn(at, ItemDisplay::class.java) { e ->
                    e.setItemStack(ItemStack(Material.PAPER).apply { editMeta { it.itemModel = key } })
                    e.itemDisplayTransform = ItemDisplay.ItemDisplayTransform.FIXED
                    apply(e, transform)
                }
            }

            shows.block != null -> world.spawn(at, BlockDisplay::class.java) { e ->
                e.block = shows.block
                e.transformation = Transformation(Vector3f(-0.5f * scale, 0f, -0.5f * scale), Quaternionf(), Vector3f(scale), Quaternionf())
            }

            else -> world.spawn(at, TextDisplay::class.java) { e ->
                e.text(shows.text ?: Component.empty())
                e.billboard = Display.Billboard.CENTER
                e.transformation = Transformation(Vector3f(), Quaternionf(), Vector3f(scale), Quaternionf())
            }
        }
        display.isPersistent = true
        if (request != null) {
            display.transformation = request.over(display.transformation)
            request.billboard?.let { display.billboard = it }
        }
        change.spawnedEntities += display.uniqueId to display.location
        return display
    }

    /**
     * A copy of [source] (its item with every component, its block or text, its look) at [at], else in its place, with
     * [request] over it. A furniture is its provider's: another one is placed with the furniture tools.
     */
    fun copy(source: Found, at: Location?, request: Request?, change: Change): Display {
        source.furniture?.let { throw ToolError("it is a furniture (${it.id}): place another one with place_furniture") }
        val d = source.display
        val snapshot = d.createSnapshot() ?: throw ToolError("its display can't be copied")
        val where = at ?: d.location
        Chunks.keepLoaded(where.world, BlockVector3.at(where.blockX, where.blockY, where.blockZ).let { Box(it, it) })
        val copy = snapshot.createEntity(where) as? Display ?: throw ToolError("its copy isn't a display")
        copy.isPersistent = true
        if (request != null) {
            copy.transformation = request.over(copy.transformation)
            request.billboard?.let { copy.billboard = it }
        }
        change.spawnedEntities += copy.uniqueId to copy.location
        return copy
    }

    /**
     * The transformation of a display of item [model] at [yaw]/[pitch], relative to the display: what a furniture showing
     * it has (its provider's pose), else standing [lift] up, half a turn so its front faces a player looking at [yaw].
     */
    private fun modelTransform(model: String, yaw: Float, pitch: Float, scale: Float, lift: Double?): Matrix4d {
        val type = Furniture.provider?.types()?.firstOrNull { runCatching { it.model() }.getOrNull() == model }
        if (type != null) {
            // The pose is relative to the root block's corner and ends with the renderer's half turn: the display stands
            // at the bottom centre, and turns itself.
            val pose = Matrix4d(type.pose(yaw)).rotateY(-Math.PI)
            pose.setTranslation(pose.getTranslation(Vector3d()).sub(0.5, 0.0, 0.5))
            val inverse = Quaterniond().rotationYXZ(Math.toRadians(-yaw.toDouble()), Math.toRadians(pitch.toDouble()), 0.0).conjugate()
            return Matrix4d().scaling(scale.toDouble()).rotate(inverse).mul(pose)
        }
        return Matrix4d().translation(0.0, (lift ?: 0.5) * scale, 0.0).rotateY(Math.PI).scale(scale.toDouble())
    }

    /** Sets [m] as [display]'s transformation: as a rotation and a scale when it is one, else decomposed by the server. */
    private fun apply(display: Display, m: Matrix4d) {
        val scale = m.getScale(Vector3d())
        val r = Matrix3d(m).scale(1 / scale.x, 1 / scale.y, 1 / scale.z)
        if (r.determinant() < 0) {
            scale.x = -scale.x
            r.scale(-1.0, 1.0, 1.0)
        }
        val check = Matrix3d(r).transpose().mul(r)
        val orthogonal = abs(check.m00 - 1) < 1e-4 && abs(check.m11 - 1) < 1e-4 && abs(check.m22 - 1) < 1e-4 &&
            abs(check.m01) < 1e-4 && abs(check.m02) < 1e-4 && abs(check.m12) < 1e-4
        if (!orthogonal) return display.setTransformationMatrix(Matrix4f(m))
        val t = m.getTranslation(Vector3d())
        display.transformation = Transformation(
            Vector3f(t.x.toFloat(), t.y.toFloat(), t.z.toFloat()), Quaternionf(Quaterniond().setFromNormalized(r)).normalize(),
            Vector3f(scale.x.toFloat(), scale.y.toFloat(), scale.z.toFloat()), Quaternionf(),
        )
    }
}
