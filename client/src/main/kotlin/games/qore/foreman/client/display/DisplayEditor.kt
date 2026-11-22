package games.qore.foreman.client.display

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.EditorTool
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.GizmoChange
import games.qore.foreman.client.gizmo.GizmoFrame
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.commands.arguments.blocks.BlockStateParser
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.UUID
import kotlin.math.sign

/**
 * Edits display entities (item, block and text displays, furniture's included) where they stand. The crosshair picks
 * what they draw (an item's model, a block's shape, a text's box); left click selects, Shift adds or removes, right
 * click takes the one behind. A gizmo moves, turns and scales the selection around its pivot, shown at once on the
 * client and saved by the server's `edit_displays` on release (taken back when the server refuses). Keys switch the
 * gizmo's mode, axes and pivot, and duplicate, delete, drop or undo; the panel has the exact values and the displays
 * nearby. Nothing runs while the tool is off.
 */
object DisplayEditor : EditorTool {
    override val title = "Display editor"
    override val id = "displays"
    /** Displays this close to the camera are picked and listed. */
    const val REACH = 64.0
    private const val SELECTED = 0xFF4FB3FF.toInt()
    private const val FURNITURE_RADIUS = 32
    private const val FURNITURE_AGAIN_MS = 3000L
    private const val NOTE_MS = 6000L

    enum class Pivot(val label: String) { ORIGIN("origin"), CENTER("center") }

    /** The selected displays, the last one the primary: its values in the panel, its axes on the gizmo. */
    private val selection = LinkedHashSet<UUID>()
    val selected: List<UUID> get() = selection.toList()

    /** This frame's displays within reach of the camera, and the selected ones wherever they are, by uuid. */
    var nearby: Map<UUID, Display> = emptyMap()
        private set
    private var hits: List<DisplayHit> = emptyList()
    var localAxes = true
    var pivot = Pivot.ORIGIN
    /** Bumped when the selection or its values changed (a save, an undo): the panel reads them again. */
    var revision = 0
        private set
    /** A change is on its way to the server: the next waits for its answer. */
    var busy = false
        private set
    private var note: Pair<String, Long>? = null

    private var dragFrame: GizmoFrame? = null
    private var starts: Map<UUID, DisplayPose>? = null
    private var previewed: Map<UUID, DisplayPose>? = null

    /** Display uuid to the furniture it belongs to, from the server's `list_displays` around the player. */
    private var furniture: Map<UUID, String> = emptyMap()
    /** The displays the last list had: one hovered that it hadn't (placed since) asks again. */
    private var listed: Set<UUID> = emptySet()
    /** The displays this client had in the last list's box when it asked, and the box (a block in from its edges). */
    private var listedHere: Set<UUID> = emptySet()
    private var listedBox: AABB? = null
    private var furnitureAt: Vec3? = null
    private var furnitureAsked = 0L
    private var furnitureLoading = false
    private var furnitureFailed = false

    private val gizmo = TransformGizmo(::frame, ::preview, ::commitDrag, ::revertDrag, ::scaleBase, grid = ::grid)

    /** The gizmo's mode; not changed during a drag. */
    var mode: TransformGizmo.Mode
        get() = gizmo.mode
        set(value) {
            if (!gizmo.dragging) gizmo.mode = value
        }
    val enabled get() = Tools.active === this

    fun init() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { forget() } }
    }

    /** Its keys in editor mode, by name (the panels' tooltips say them). */
    private val editorKeys = linkedMapOf(
        "display-mode" to EditorKey(InputConstants.KEY_V, "Move, rotate or scale") { nextMode() },
        "display-axes" to EditorKey(InputConstants.KEY_O, "Own axes or the world's") { localAxes = !localAxes },
        "display-pivot" to EditorKey(InputConstants.KEY_PERIOD, "Pivot: origin or center") { pivot = Pivot.entries[(pivot.ordinal + 1) % Pivot.entries.size] },
        "display-values" to EditorKey(InputConstants.KEY_N, "Values, displays nearby") { Screens.open(DisplayPanel()) },
        "display-duplicate" to EditorKey(InputConstants.KEY_EQUALS, "Duplicate") { duplicate() },
        "display-delete" to EditorKey(InputConstants.KEY_DELETE, "Delete") { delete() },
        "display-undo" to EditorKey(InputConstants.KEY_Z, "Undo the server's last change") { undo() },
        "display-drop" to EditorKey(InputConstants.KEY_END, "Drop onto the surface below") { drop() },
        "display-in-box" to EditorKey(InputConstants.KEY_I, "Select in the selection box") { selectInBox() },
        "display-position" to EditorKey(InputConstants.KEY_P, "Move the entity, not its model") { OriginHandle.toggle() },
    )

    override fun keys() = editorKeys.values.toList()

    /** The key `<name>` is in editor mode (`display-mode`…). */
    fun keyName(name: String) = editorKeys[name]?.caption ?: "unbound"

    fun toggle() {
        Tools.toggle(this)
    }

    /** Turns the editor on with [uuids] selected (the last one the primary), for displays just placed. */
    fun select(uuids: List<UUID>) {
        gizmo.cancel()
        selection.clear()
        selection += uuids
        revision++
        if (!enabled) Tools.toggle(this)
    }

    fun selectOnly(uuid: UUID) = select(listOf(uuid))

    /** Adds [uuid] to the selection (as the primary), or takes it out. */
    fun toggleSelected(uuid: UUID) {
        gizmo.cancel()
        if (!selection.remove(uuid)) selection += uuid
        revision++
    }

    fun clearSelection() {
        gizmo.cancel()
        selection.clear()
        revision++
    }

    fun nextMode() {
        mode = TransformGizmo.Mode.entries[(mode.ordinal + 1) % TransformGizmo.Mode.entries.size]
    }

    fun selectedDisplays(): List<Display> = selection.mapNotNull { nearby[it] }

    /** Shift, or the sneak key wherever it is bound: adds to the selection. */
    fun shiftDown() = Mc.isKeyDown(InputConstants.KEY_LSHIFT) || Minecraft.getInstance().options.keyShift.isDown

    // ---- The tool ---------------------------------------------------------------------------------------------------

    override fun press(button: MouseButton, ray: Ray) {
        val shift = shiftDown()
        if (button == MouseButton.LEFT && OriginHandle.press(ray)) return
        if (button == MouseButton.LEFT && gizmo.press(ray)) {
            if (!busy) return beginDrag()
            gizmo.cancel()
            return Chat.warn("Wait for the last change to be saved")
        }
        // The crosshair as it is now: the last frame's may be older on a slow client.
        hits = DisplayShapes.pick(ray, nearby.values)
        when (button) {
            MouseButton.LEFT -> {
                val hit = hits.firstOrNull()?.display?.uuid
                when {
                    hit == null -> if (!shift) clearSelection()
                    shift -> toggleSelected(hit)
                    else -> selectOnly(hit)
                }
            }
            MouseButton.RIGHT -> {
                // The next one along the crosshair: what hides behind the first.
                if (hits.isEmpty()) return
                val current = hits.indexOfFirst { it.display.uuid == selection.lastOrNull() }
                val next = hits[(current + 1) % hits.size].display.uuid
                if (shift) toggleSelected(next) else selectOnly(next)
            }
        }
    }

    override fun release(button: MouseButton) {
        if (button != MouseButton.LEFT) return
        gizmo.release()
        OriginHandle.release()
    }

    override fun scroll(amount: Double): Boolean = gizmo.dragging && gizmo.nudge(sign(amount).toInt())

    override fun update(ray: Ray) {
        val level = Minecraft.getInstance().level ?: return
        val found = HashMap<UUID, Display>()
        for (e in level.entitiesForRendering()) {
            if (e is Display && editable(e) && (e.uuid in selection || e.distanceToSqr(ray.origin) <= REACH * REACH)) found[e.uuid] = e
        }
        nearby = found
        gizmo.update(ray)
        OriginHandle.update(ray)
        if (gizmo.dragging || OriginHandle.dragging) {
            // Again every frame: the server's position syncs would put them back meanwhile.
            previewed?.forEach { (id, pose) -> found[id]?.let(pose::applyTo) }
            hits = emptyList()
            return
        }
        hits = if (gizmo.hovering || OriginHandle.hovering) emptyList() else DisplayShapes.pick(ray, found.values)
        fetchFurniture(ray.origin)
    }

    override fun deactivated() {
        gizmo.cancel()
        OriginHandle.cancel()
        hits = emptyList()
        nearby = emptyMap()
        DisplayShapes.clear()
        // Asked again next time: the server may have got its display tools meanwhile.
        furnitureAt = null
        furnitureFailed = false
    }

    /** Forgets the server's displays (the game left it). */
    private fun forget() {
        if (enabled) Tools.deactivate()
        selection.clear()
        furniture = emptyMap()
        listed = emptySet()
        listedHere = emptySet()
        listedBox = null
        furnitureAt = null
        furnitureFailed = false
        busy = false
    }

    // ---- The gizmo --------------------------------------------------------------------------------------------------

    /** The gizmo on the selection: at the pivot, along the primary's axes (scaling always is) or the world's. */
    private fun frame(): GizmoFrame? {
        if (OriginHandle.on) return null
        val displays = selectedDisplays()
        val primary = displays.lastOrNull() ?: return null
        val axes = if (localAxes || gizmo.mode == TransformGizmo.Mode.SCALE) DisplayPose.of(primary).axes() else GizmoFrame.WORLD_AXES
        return GizmoFrame(pivotOf(displays), axes)
    }

    fun pivotOf(displays: List<Display>): Vec3 = when (pivot) {
        Pivot.ORIGIN -> displays.map { DisplayPose.of(it).origin }.let { o -> Vec3(o.sumOf { it.x } / o.size, o.sumOf { it.y } / o.size, o.sumOf { it.z } / o.size) }
        Pivot.CENTER -> displays.map(DisplayShapes::bounds).reduce(AABB::minmax).center
    }

    /** Block snapping: a block display's origin on block corners, another's on floor centres, the centre pivot on block centres. */
    private fun grid(): Snapping.Grid = when {
        pivot == Pivot.CENTER -> Snapping.Grid.CENTRES
        selectedDisplays().lastOrNull() is Display.BlockDisplay -> Snapping.Grid.CORNERS
        else -> Snapping.Grid.FLOOR
    }

    private fun scaleBase(): Vec3 = selectedDisplays().lastOrNull()?.let { DisplayPose.of(it).scale }?.let { Vec3(it.x, it.y, it.z) } ?: Vec3(1.0, 1.0, 1.0)

    private fun beginDrag() {
        dragFrame = frame()
        starts = selectedDisplays().associate { it.uuid to DisplayPose.of(it) }
        previewed = null
    }

    private fun transformed(pose: DisplayPose, change: GizmoChange, frame: GizmoFrame) = when (change) {
        is GizmoChange.Move -> pose.moved(change.offset)
        is GizmoChange.Rotate -> pose.rotated(change.axis, change.angle, frame.pivot)
        is GizmoChange.Scale -> pose.scaled(change.factors, frame.axes, frame.pivot)
    }

    private fun preview(change: GizmoChange) {
        val starts = starts ?: return
        val frame = dragFrame ?: return
        val poses = starts.mapValues { (_, pose) -> transformed(pose, change, frame) }
        previewed = poses
        poses.forEach { (id, pose) -> nearby[id]?.let(pose::applyTo) }
    }

    private fun commitDrag(change: GizmoChange) {
        preview(change)
        val before = starts
        val after = previewed
        endDrag()
        if (before != null && after != null) save(before, after)
    }

    private fun revertDrag() {
        starts?.forEach { (id, pose) -> nearby[id]?.let(pose::applyTo) }
        endDrag()
    }

    private fun endDrag() {
        dragFrame = null
        starts = null
        previewed = null
    }

    // ---- Changes ----------------------------------------------------------------------------------------------------

    /** Saves [after] (shown at once), [before] shown again where the server refuses; yaw and pitch for [facing]. */
    private fun save(before: Map<UUID, DisplayPose>, after: Map<UUID, DisplayPose>, facing: Set<UUID> = emptySet()) {
        if (after.isEmpty()) return
        after.forEach { (id, pose) -> nearby[id]?.let(pose::applyTo) }
        busy = true
        DisplayServer.edit(after, facing) { result ->
            busy = false
            result.onSuccess { edited ->
                val failed = edited.failed
                failed.forEach { f -> before[f.uuid]?.let { pose -> nearby[f.uuid]?.let(pose::applyTo) } }
                // The server's own values: nothing resends a furniture's position, so the preview's would stay.
                edited.poses.forEach { (id, pose) -> nearby[id]?.let(pose::applyTo) }
                if (edited.replaced.isNotEmpty()) {
                    val now = selection.map { edited.replaced[it] ?: it }
                    selection.clear()
                    selection += now
                    furnitureAt = null
                }
                if (failed.isEmpty()) note("§a✔ Saved ${count(after.size)}")
                else {
                    note("§c✖ ${failed.size} refused: ${failed.first().error}")
                    Chat.error("The server didn't change ${count(failed.size)}: ${failed.first().error}")
                }
            }.onFailure {
                before.forEach { (id, pose) -> nearby[id]?.let(pose::applyTo) }
                val message = it.message ?: it.javaClass.simpleName
                note("§c✖ ${message.take(60)}")
                Chat.error("Couldn't save the change: $message")
            }
            revision++
        }
    }

    /** Changes every selected display with [edit] and saves it; [facing]: their yaw and pitch changed too. */
    fun change(facing: Boolean = false, edit: (Display, DisplayPose) -> DisplayPose) {
        if (busy) return Chat.warn("Wait for the last change to be saved")
        val displays = selectedDisplays().ifEmpty { return }
        val before = displays.associate { it.uuid to DisplayPose.of(it) }
        val after = displays.associate { it.uuid to edit(it, before.getValue(it.uuid)) }.filter { (id, pose) -> pose != before[id] }
        save(before, after, if (facing) after.keys else emptySet())
    }

    fun resetRotation() = change { _, pose -> pose.copy(left = Quaterniond()) }

    fun resetScale() = change { _, pose -> pose.copy(scale = Vector3d(1.0, 1.0, 1.0)) }

    /** Moves each selected display down onto what is under it (the highest surface under its box's bottom). */
    fun drop() = change { e, pose ->
        val box = DisplayShapes.bounds(e)
        surfaceUnder(box)?.let { pose.moved(Vec3(0.0, it - box.minY, 0.0)) } ?: pose
    }

    private fun surfaceUnder(box: AABB): Double? {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return null
        val player = minecraft.player ?: return null
        val y = box.minY + 0.01
        val points = listOf(box.center.x to box.center.z, box.minX to box.minZ, box.maxX to box.minZ, box.minX to box.maxZ, box.maxX to box.maxZ)
        return points.mapNotNull { (x, z) ->
            val hit = level.clip(ClipContext(Vec3(x, y, z), Vec3(x, y - 64, z), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
            hit.takeIf { it.type == HitResult.Type.BLOCK && !(it as BlockHitResult).isInside }?.location?.y
        }.maxOrNull()
    }

    /** Spawns a copy of each selected display in its place and selects the copies, to move them away. */
    fun duplicate() {
        if (busy) return Chat.warn("Wait for the last change to be saved")
        val displays = selectedDisplays().ifEmpty { return }
        val specs = displays.mapNotNull(::spec)
        if (specs.isEmpty()) return Chat.warn("Nothing to copy: those displays show nothing")
        busy = true
        DisplayServer.spawn(specs) { result ->
            busy = false
            result.onSuccess { ids ->
                select(ids)
                furnitureAt = null
                note("§a✔ Copied ${count(ids.size)}: drag them away")
            }.onFailure { failed("copy", it) }
        }
    }

    /**
     * `spawn_display`'s arguments for a copy of [e]: the server copies the entity itself (every item component, its
     * look); a furniture is its plugin's, so its copy is a display of its model.
     */
    private fun spec(e: Display): JsonObject? {
        if (e.uuid !in furniture) return buildJsonObject { put("copy_of", e.uuid.toString()) }
        val pose = DisplayPose.of(e)
        val what = when (e) {
            is Display.ItemDisplay -> e.itemStack.takeUnless { it.isEmpty }?.let { "model" to (it.get(DataComponents.ITEM_MODEL) ?: BuiltInRegistries.ITEM.getKey(it.item)).toString() }
            is Display.BlockDisplay -> "block" to BlockStateParser.serialize(e.blockState)
            is Display.TextDisplay -> "text" to e.text.string
            else -> null
        } ?: return null
        // spawn_display takes whole numbers for a block's floor centre: a copy stays exactly where the original is.
        val position = listOf(pose.pos.x, pose.pos.y, pose.pos.z).map { if (it % 1.0 == 0.0) it + 1e-5 else it }
        return buildJsonObject {
            put(what.first, what.second)
            put("position", JsonArray(position.map { JsonPrimitive(it) }))
            put("yaw", pose.yaw)
            put("pitch", pose.pitch)
            put("billboard", e.billboardConstraints.serializedName)
            put("transformation", pose.transformationJson())
        }
    }

    fun delete() {
        if (busy) return Chat.warn("Wait for the last change to be saved")
        val ids = selection.toList().ifEmpty { return }
        busy = true
        DisplayServer.remove(ids) { result ->
            busy = false
            result.onSuccess { failed ->
                val kept = failed.map { it.uuid }.toSet()
                selection.removeAll { it !in kept }
                revision++
                furnitureAt = null
                if (failed.isEmpty()) note("§a✔ Deleted ${count(ids.size)}")
                else {
                    note("§c✖ ${failed.size} not deleted: ${failed.first().error}")
                    Chat.error("The server didn't delete ${count(failed.size)}: ${failed.first().error}")
                }
            }.onFailure { failed("delete", it) }
        }
    }

    fun undo() {
        if (busy) return Chat.warn("Wait for the last change to be saved")
        busy = true
        DisplayServer.undo { result ->
            busy = false
            result.onSuccess { text ->
                revision++
                note("§a✔ ${text.lineSequence().firstOrNull().orEmpty().take(60).ifEmpty { "Undone" }}")
            }.onFailure { failed("undo", it) }
        }
    }

    /**
     * Whether the editor offers [display]: not when it rides a player (a hat, a held model), which isn't part of a build,
     * nor when it only exists on this client (a plugin's packet entity: the server didn't list it), which the server can't
     * edit.
     */
    private fun editable(display: Display) = display.rootVehicle !is Player && !clientOnly(display)

    private fun clientOnly(display: Display) =
        display.uuid in listedHere && display.uuid !in listed && listedBox?.contains(display.position()) == true

    /** Selects every display whose anchor is in the selection tool's box. */
    fun selectInBox() {
        val box = SelectionTool.box ?: return Chat.warn("No selection box: make one with the selection tool first")
        val level = Minecraft.getInstance().level ?: return
        if (SelectionTool.dimension != level.dimension().identifier().toString()) return Chat.warn("The selection box is in another dimension")
        val inside = level.entitiesForRendering().filterIsInstance<Display>().filter(::editable).filter {
            val p = it.position()
            p.x >= box.minX && p.x < box.maxX + 1 && p.y >= box.minY && p.y < box.maxY + 1 && p.z >= box.minZ && p.z < box.maxZ + 1
        }
        select(inside.map { it.uuid })
        Chat.info("${count(inside.size)} selected in ${box.describe()}")
    }

    private fun failed(what: String, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        note("§c✖ ${message.take(60)}")
        Chat.error("Couldn't $what: $message")
    }

    private fun note(text: String) {
        note = text to System.currentTimeMillis()
    }

    private fun count(n: Int) = if (n == 1) "1 display" else "$n displays"

    // ---- Furniture --------------------------------------------------------------------------------------------------

    /** Asks the server which furniture the displays around belong to, again once the player has moved away or looks at a new one. */
    private fun fetchFurniture(at: Vec3) {
        if (furnitureLoading || furnitureFailed || !Bridge.connected) return
        val now = System.currentTimeMillis()
        val near = furnitureAt?.let { it.distanceTo(at) < FURNITURE_RADIUS / 2 } == true
        val unknown = hits.firstOrNull()?.display?.uuid?.let { it !in listed } == true && now - furnitureAsked > FURNITURE_AGAIN_MS
        if (near && !unknown) return
        furnitureLoading = true
        furnitureAt = at
        furnitureAsked = now
        val center = BlockPos.containing(at)
        val from = center.offset(-FURNITURE_RADIUS, -FURNITURE_RADIUS, -FURNITURE_RADIUS)
        val to = center.offset(FURNITURE_RADIUS, FURNITURE_RADIUS, FURNITURE_RADIUS)
        // Inside by a block: the client's positions may lag the server's at the edges.
        val box = AABB(Vec3.atLowerCornerOf(from), Vec3.atLowerCornerOf(to.offset(1, 1, 1))).deflate(1.0)
        val here = Minecraft.getInstance().level?.entitiesForRendering()
            ?.filter { it is Display && box.contains(it.position()) }?.map { it.uuid }?.toSet().orEmpty()
        DisplayServer.furniture(from, to) { result ->
            furnitureLoading = false
            // A server without the tool: labels show models only, without asking again.
            result.onSuccess {
                furniture = it.furniture
                listed = it.seen
                listedHere = here
                listedBox = box
            }.onFailure { furnitureFailed = true }
        }
    }

    /** What [e] is: its furniture, item model, block or text. */
    fun label(e: Display): String = furniture[e.uuid] ?: what(e)

    fun what(e: Display): String = when (e) {
        is Display.ItemDisplay -> e.itemStack.takeUnless { it.isEmpty }?.let { short((it.get(DataComponents.ITEM_MODEL) ?: BuiltInRegistries.ITEM.getKey(it.item)).toString()) } ?: "empty item"
        is Display.BlockDisplay -> short(BuiltInRegistries.BLOCK.getKey(e.blockState.block).toString())
        is Display.TextDisplay -> "\"" + e.text.string.replace('\n', ' ').let { if (it.length > 24) it.take(23) + "…" else it } + "\""
        else -> e.type.description.string
    }

    fun kind(e: Display): String = when (e) {
        is Display.ItemDisplay -> if (e.uuid in furniture) "furniture" else "item"
        is Display.BlockDisplay -> "block"
        is Display.TextDisplay -> "text"
        else -> "display"
    }

    private fun short(id: String) = id.removePrefix("minecraft:")

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    override fun draw(draw: OverlayDraw) {
        val hovered = hits.firstOrNull()?.display
        for (e in nearby.values) {
            val selected = e.uuid in selection
            val color = if (e === hovered) Colors.HOVER else if (selected) SELECTED else Colors.GRAY
            val corners = DisplayShapes.corners(e)
            if (corners == null) {
                // No shape to pick: a small box on its anchor, near the camera only.
                val anchor = DisplayShapes.anchor(e)
                if (!selected && e !== hovered && anchor.distanceTo(draw.camera) > DisplayShapes.MARKERS) continue
                val r = DisplayShapes.MARKER
                draw.box(anchor.subtract(r, r, r), anchor.add(r, r, r), color, 2f, Colors.withAlpha(color, 0.3f), onTop = true)
            } else if (selected || e === hovered) {
                outline(draw, corners, color, if (e === hovered) 3f else 2f)
            }
        }
        hovered?.let { e ->
            val box = DisplayShapes.bounds(e)
            draw.label(Vec3(box.center.x, box.maxY + 0.3, box.center.z), listOf(label(e) to Colors.HOVER, (kind(e) + if (e.uuid in selection) " · selected" else "") to Colors.WHITE))
        }
        OriginHandle.draw(draw, hovered)
        if (selectedDisplays().isNotEmpty()) gizmo.draw(draw)
    }

    /** The box through [corners] (x, then y, then z of each: min and max). */
    private fun outline(draw: OverlayDraw, corners: List<Vec3>, color: Int, width: Float) {
        for (i in 0..7) for (bit in intArrayOf(1, 2, 4)) {
            if (i and bit == 0) draw.line(corners[i], corners[i or bit], color, width, onTop = true)
        }
    }

    override fun snap() = if (OriginHandle.on) Snapping.Kind.MOVE else gizmo.snapKind

    override fun status(): ToolStatus {
        val displays = selectedDisplays()
        val primary = displays.lastOrNull()
        val selected = when {
            selection.isEmpty() -> null
            displays.isEmpty() -> Shown("Displays", "${selection.size} selected", "out of sight")
            displays.size == 1 -> Shown(kindLabel(displays[0]), label(displays[0]))
            else -> Shown("Displays", "${displays.size} selected", "last: ${label(primary!!)}")
        }
        val axes = if (localAxes || gizmo.mode == TransformGizmo.Mode.SCALE) "own" else "world"
        val details = listOfNotNull(
            if (selected == null) null else "Gizmo: ${gizmo.mode.label} [${keyName("display-mode")}] · $axes axes [${keyName("display-axes")}] · pivot ${pivot.label}",
            OriginHandle.status(),
            gizmo.describe()?.takeIf { gizmo.dragging }?.let { "Dragged: $it" },
        )
        val hovered = hits.firstOrNull()?.display?.takeIf { it.uuid !in selection }?.let { Shown(kindLabel(it), label(it)) }
        val hints = when {
            gizmo.dragging || OriginHandle.dragging -> listOf(Hint("Ctrl", "drag freely"), Hint(ToolStatus.SCROLL, if (gizmo.movingFreely) "farther, nearer" else "one step more"))
            gizmo.hovering || OriginHandle.hovering -> listOf(Hint(ToolStatus.DRAG, when {
                OriginHandle.on -> "move the entity, not its model"
                gizmo.movingFreely -> "move the selection anywhere"
                else -> gizmo.mode.label.lowercase() + " the selection"
            }))
            else -> listOfNotNull(
                Hint(ToolStatus.LEFT, when {
                    hits.isNotEmpty() -> "select it (Shift: add or remove)"
                    selection.isNotEmpty() -> "on nothing: deselect"
                    else -> "on a display: select it"
                }),
                Hint(ToolStatus.RIGHT, "the one behind it"),
                if (primary != null) Hint(ToolStatus.DRAG, "the gizmo: ${gizmo.mode.label.lowercase()}") else null,
            )
        }
        val notes = listOfNotNull(
            if (busy) Note("Saving…", Note.Tone.BUSY) else note?.takeIf { System.currentTimeMillis() - it.second < NOTE_MS }?.let { Note.coloured(it.first) },
            if (!Bridge.connected) Note(Bridge.unavailable("changes can't be saved"), Note.Tone.ERROR) else null,
        )
        return ToolStatus(selected, "Click a display to select it", details, hovered, hints, notes = notes)
    }

    /** What [e] is, for the status card: `Furniture`, `Item display`… */
    private fun kindLabel(e: Display) = kind(e).let { if (it == "furniture") "Furniture" else it.replaceFirstChar(Char::uppercaseChar) + " display" }
}
