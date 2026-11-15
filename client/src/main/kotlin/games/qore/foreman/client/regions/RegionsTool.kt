package games.qore.foreman.client.regions

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.EditorTool
import games.qore.foreman.client.editor.EditorTools
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.gizmo.BoxGizmo
import games.qore.foreman.client.gizmo.GizmoChange
import games.qore.foreman.client.gizmo.GizmoFrame
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.library.Library
import games.qore.foreman.client.library.distanceTo
import games.qore.foreman.client.library.pick
import games.qore.foreman.client.link.AIOverlay
import games.qore.foreman.client.link.Area
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.BridgeLayer
import games.qore.foreman.client.link.GamePoint
import games.qore.foreman.client.link.Layouts
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.util.Mth
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.abs
import kotlin.math.sign

/** An area of the server by its world and name, and which of its boxes ([box]) is meant. */
data class AreaRef(val world: String, val name: String, val box: Int = 0) {
    fun area(): Area? = Bridge.areas.firstOrNull { it.world == world && it.name == name }
}

/**
 * Edits the server's areas and gameplay points where they are, a tool of editor mode. While it is on, the areas of the
 * world show with their names and tags (those of a type a plugin declares in its colour, claims in their kind's, zones
 * violet, plain areas cyan) and the points as markers with their type (a declared type's label and colour) and an arrow
 * the way they face. A click selects one: an area's face knobs resize the box clicked and its arrows move that box, by
 * whole blocks (the area only: what is in the world stays); a point's gizmo moves it as the editor's snapping says and
 * its ring turns where it faces; the [panels][AreaPanel] edit the rest, declared types' data with their forms. A new
 * area is the player's selection ([SelectionTool.current]), a new point where the crosshair meets a block. The
 * [layout check][LayoutCheck] lists what doesn't fit the declared types. Asset areas show but are the asset tool's.
 * Changes go through the server's `set_mark`, `claim` and `points`; nothing runs while the tool is off.
 */
object RegionsTool : EditorTool {
    override val id = "regions"
    override val title = "Areas and points"
    const val REACH = 256.0
    /** Points are picked this close to the crosshair, and labelled this near. */
    private const val POINT_PICK = 0.35
    private const val POINT_LABELS = 24.0
    private const val MARK = 0xFF55DDFF.toInt()
    private const val ZONE = 0xFFC77DFF.toInt()
    private const val ASSET = 0x99FFC857.toInt()
    private const val DRAFT = 0xFF7FD8FF.toInt()
    private const val SELECTED = 0xFF4FB3FF.toInt()
    private val CLAIMS = mapOf("build" to 0xFFFFAA33.toInt(), "road" to 0xFFC8A070.toInt(), "yard" to 0xFF77DD55.toInt())
    /** How long a change is shown before the server's areas or points have it. */
    private const val PREVIEW_MS = 4000L
    private const val NOTE_MS = 6000L
    /** A second Delete within this deletes. */
    private const val CONFIRM_MS = 3000L

    var selectedArea: AreaRef? = null
        private set
    var selectedPoint: GamePoint? = null
        private set
    /** A new point waiting for its panel. */
    var draftPoint: GamePoint? = null
        private set
    /** The selected area's box as changed here, and until when it is shown if the server's areas don't have it. */
    private var preview: Pair<BlockBox, Long>? = null
    /** The selected point as dragged (moved or turned). */
    private var dragged: GamePoint? = null
    var busy = false
        private set
    private var note: Pair<String, Long>? = null
    private var hovered: Any? = null
    private var deleteAsked = 0L
    private var subscribed = false

    private val boxGizmo = BoxGizmo(::gizmoBox, ::boxChanged, ::boxCommitted)
    private val pointGizmo = TransformGizmo(
        frame = ::pointFrame, changed = ::pointDragged, committed = ::pointDropped, cancelled = { dragged = null },
        rings = listOf(0, 1), ringsWithMove = true, grid = { Snapping.Grid.FLOOR },
    )

    val enabled get() = Tools.active === this

    fun init() {
        EditorTools.register(this)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { forget() } }
    }

    override fun keys() = listOf(
        EditorKey(InputConstants.KEY_N, "Panel: name, tags, zone, claim") { openPanel() },
        EditorKey(InputConstants.KEY_M, "New area from the selection") { newArea() },
        EditorKey(InputConstants.KEY_P, "New point where you aim") { newPoint() },
        EditorKey(InputConstants.KEY_DELETE, "Delete it (press twice)") { delete() },
        EditorKey(InputConstants.KEY_K, "Check the layout") { LayoutCheck.open() },
    )

    override fun available() = Bridge.connected

    /** Areas are blocks: their boxes move by whole blocks whatever the snapping. */
    override fun snapNote() = if (selectedArea != null) "Areas: whole blocks" else null

    // ---- Selection --------------------------------------------------------------------------------------------------

    fun selectArea(ref: AreaRef?) {
        cancelDrags()
        draftPoint = null
        selectedPoint = null
        preview = null
        selectedArea = ref
    }

    fun selectPoint(point: GamePoint?) {
        cancelDrags()
        draftPoint = null
        selectedArea = null
        selectedPoint = point
    }

    private fun cancelDrags() {
        boxGizmo.cancel()
        pointGizmo.cancel()
        dragged = null
    }

    private fun isAsset(a: Area?) = a != null && Library.TAG in a.tags

    /** Forgets the server's areas (the game left it). */
    private fun forget() {
        if (enabled) Tools.deactivate()
        selectArea(null)
        busy = false
    }

    // ---- The tool ---------------------------------------------------------------------------------------------------

    override fun press(button: MouseButton, ray: Ray) {
        if (button != MouseButton.LEFT || boxGizmo.press(ray) || pointGizmo.press(ray)) return
        val dimension = playerDimension() ?: return
        when (val hit = pick(ray, dimension)) {
            is GamePoint -> selectPoint(hit)
            is AreaRef -> selectArea(hit)
            else -> selectArea(null)
        }
    }

    override fun scroll(amount: Double): Boolean = pointGizmo.dragging && pointGizmo.nudge(sign(amount).toInt())

    override fun release(button: MouseButton) {
        if (button != MouseButton.LEFT) return
        boxGizmo.release()
        pointGizmo.release()
    }

    override fun update(ray: Ray) {
        if (!subscribed) {
            Bridge.need(BridgeLayer.AREAS, true)
            Bridge.need(BridgeLayer.POINTS, true)
            Bridge.need(BridgeLayer.LAYOUT, true)
            subscribed = true
        }
        boxGizmo.update(ray)
        pointGizmo.update(ray)
        val still = boxGizmo.dragging || pointGizmo.dragging || boxGizmo.hovered != null || pointGizmo.hovering
        hovered = if (still) null else playerDimension()?.let { pick(ray, it) }
    }

    /** The point near the crosshair, else the box of an area it points at (the nearest, else the smallest it is in). */
    private fun pick(ray: Ray, dimension: String): Any? {
        points(dimension).mapNotNull { p ->
            val (distance, along) = ray.toPoint(p.at)
            if (distance <= POINT_PICK && along <= REACH) p to along else null
        }.minByOrNull { it.second }?.let { return it.first }
        return ray.pick(areas(dimension).flatMap { a -> a.blockBoxes.mapIndexed { i, b -> AreaRef(a.world, a.name, i) to b } }, REACH)
    }

    private fun areas(dimension: String) = Bridge.areas.filter { it.dim == dimension }

    private fun points(dimension: String) = Bridge.points.filter { it.dim == dimension }

    override fun escape(): Boolean {
        when {
            boxGizmo.dragging || pointGizmo.dragging -> cancelDrags()
            draftPoint != null -> draftPoint = null
            selectedArea != null || selectedPoint != null -> selectArea(null)
            else -> return false
        }
        return true
    }

    override fun deactivated() {
        cancelDrags()
        draftPoint = null
        hovered = null
        if (subscribed) {
            Bridge.need(BridgeLayer.AREAS, false)
            Bridge.need(BridgeLayer.POINTS, false)
            Bridge.need(BridgeLayer.LAYOUT, false)
            subscribed = false
        }
    }

    // ---- New areas and points ---------------------------------------------------------------------------------------

    /** A new area: the player's selection (Foreman's, else WorldEdit's) in its panel; the panel says how to make one. */
    fun newArea() {
        selectArea(null)
        Screens.open(AreaPanel(null))
    }

    /**
     * A new point where the crosshair meets a solid block, named in its panel: on the floor of the block before the face
     * with block snapping, else right there (to the move step); facing away from a wall, else where the player looks.
     */
    fun newPoint() {
        val hit = Ray.crosshair().block(REACH, solid = true) ?: return Chat.warn("Look at a block")
        val dimension = playerDimension() ?: return
        val block = hit.blockPos.relative(hit.direction)
        val at = if (Snapping.onGrid) Vec3(block.x + 0.5, block.y.toDouble(), block.z + 0.5) else Snapping.position(hit.location)
        selectArea(null)
        val yaw = Ray.facing(hit.direction, Minecraft.getInstance().player?.yRot ?: 0f)
        draftPoint = GamePoint(Bridge.world, dimension, type = "", yaw = yaw).movedTo(at)
        Screens.open(PointPanel(null))
    }

    // ---- The gizmos -------------------------------------------------------------------------------------------------

    /** The selected box when it is in this dimension, isn't an asset's and nothing is on its way. */
    private fun gizmoBox(): BlockBox? {
        val area = selectedArea?.area()
        if (busy || area == null || area.dim != playerDimension() || isAsset(area)) return null
        return selectedBox()
    }

    /** The selected box: the change shown here until the server's areas have it, else theirs. */
    fun selectedBox(): BlockBox? {
        val ref = selectedArea ?: return null
        val actual = ref.area()?.blockBoxes?.getOrNull(ref.box)
        val (box, until) = preview ?: return actual
        if (boxGizmo.dragging || busy) return box
        if (actual == box || System.currentTimeMillis() > until) {
            preview = null
            return actual
        }
        return box
    }

    private fun boxChanged(box: BlockBox) {
        preview = box to Long.MAX_VALUE
    }

    /** A box dragged: the area keeps its other boxes, this one changed (what is in the world stays). */
    private fun boxCommitted(box: BlockBox) {
        val ref = selectedArea
        val area = ref?.area()
        if (ref == null || area == null) {
            preview = null
            return
        }
        val old = AreaSpec.of(area)
        val target = old.copy(boxes = old.boxes.toMutableList().also { it[ref.box] = box })
        run("change ${area.name}", "Saved ${area.name}'s box", box) { RegionCalls.write(old, target, it) }
    }

    /** The selected point where it is shown: dragged, else as last changed. */
    private fun shownPoint(): GamePoint? = dragged ?: selectedPoint

    /**
     * The point's gizmo, on the selected point in this dimension while nothing is on its way: world axes to move it, a
     * ring around its right (its pitch) and one around y (its yaw).
     */
    private fun pointFrame(): GizmoFrame? {
        val point = shownPoint()?.takeIf { !busy && it.dim == playerDimension() } ?: return null
        val forward = AIOverlay.facing(point.yaw ?: 0f, 0f)
        val up = Vec3(0.0, 1.0, 0.0)
        return GizmoFrame(point.at, GizmoFrame.WORLD_AXES, listOf(forward.cross(up), up, forward))
    }

    /**
     * [change] from where the drag started: moved, turned around y (its yaw; right-handed, so the other way) or around its
     * right (its pitch, up to straight up or down; a point facing nowhere gets the yaw it was shown with).
     */
    private fun pointDragged(change: GizmoChange) {
        val point = selectedPoint ?: return
        dragged = when (change) {
            is GizmoChange.Move -> point.movedTo(point.at.add(change.offset))
            is GizmoChange.Rotate -> {
                val degrees = Math.toDegrees(change.angle).toFloat()
                if (abs(change.axis.y) > 0.5) point.copy(yaw = Mth.wrapDegrees((point.yaw ?: 0f) - degrees))
                else point.copy(yaw = point.yaw ?: 0f, pitch = ((point.pitch ?: 0f) - degrees).coerceIn(-90f, 90f))
            }
            is GizmoChange.Scale -> point
        }
    }

    private fun pointDropped(change: GizmoChange) {
        pointDragged(change)
        val old = selectedPoint ?: return
        val moved = dragged ?: return
        dragged = null
        if (moved == old) return
        selectedPoint = moved
        run("move the point", "Moved the point", after = { if (it.isFailure) selectedPoint = old }) { RegionCalls.replace(old, moved, it) }
    }

    // ---- Changes ----------------------------------------------------------------------------------------------------

    /**
     * Saves [old] (as the server has it; null for a new area) as [target] ([done] on the answer): renamed, it is made
     * again under its new name.
     */
    fun saveArea(old: AreaSpec?, target: AreaSpec, done: (Result<Unit>) -> Unit) {
        val ref = selectedArea
        val refused = when {
            target.name.isBlank() -> "Give it a name"
            target.boxes.isEmpty() -> "Nothing to save: make a selection with the selection tool"
            target.name != old?.name && Bridge.areas.any { it.world == target.world && it.name == target.name } -> "There is an area ${target.name} already"
            target.tags.any { !RegionCalls.isTag(it) } -> "Tags are lowercase a-z, 0-9, _ and -, or a type's ns:name"
            else -> null
        }
        if (refused != null) return done(Result.failure(IllegalArgumentException(refused)))
        run(if (old == null) "make ${target.name}" else "save ${target.name}", if (old == null) "Made the area ${target.name}" else "Saved ${target.name}", after = { result ->
            if (result.isSuccess) {
                val box = if (old == null) 0 else ref?.box ?: 0
                selectArea(AreaRef(target.world, target.name, box))
            }
            done(result)
        }) { RegionCalls.write(old, target, it) }
    }

    /** Locks [area] to the player ([on]), or unlocks it: while locked, others' Foreman edits inside it are refused. */
    fun lock(area: Area, on: Boolean, done: (Result<Unit>) -> Unit = {}) =
        run(if (on) "lock ${area.name}" else "unlock ${area.name}", if (on) "Locked ${area.name} to you" else "Unlocked ${area.name}", after = done) { RegionCalls.lock(area, on, it) }

    /** Saves the new point, or the selected one changed, as [point]. */
    fun savePoint(point: GamePoint, done: (Result<Unit>) -> Unit) {
        val old = if (draftPoint != null) null else selectedPoint
        if (point.type.isBlank()) return done(Result.failure(IllegalArgumentException("Give it a type")))
        run(if (old == null) "add the point" else "save the point", if (old == null) "Added ${label(point)}" else "Saved ${label(point)}", after = { result ->
            if (result.isSuccess) selectPoint(point)
            done(result)
        }) { if (old == null) RegionCalls.add(point, it) else RegionCalls.replace(old, point, it) }
    }

    /** Deletes the selected area (its content stays) or point; the key asks twice. */
    fun delete(confirmed: Boolean = false, done: (Result<Unit>) -> Unit = {}) {
        val area = selectedArea?.area()
        val point = selectedPoint
        if (area == null && point == null) return Chat.warn("Select an area or a point first: click it")
        if (isAsset(area)) return Chat.warn("${area!!.name} is an asset: the asset tool deletes it")
        val now = System.currentTimeMillis()
        if (!confirmed && now - deleteAsked > CONFIRM_MS) {
            deleteAsked = now
            return note("§eDelete ${area?.name ?: label(point!!)}? Press Delete again")
        }
        deleteAsked = 0
        if (area != null) run("delete ${area.name}", "Deleted the area ${area.name}: what is in it stays", after = { result ->
            if (result.isSuccess) selectArea(null)
            done(result)
        }) { RegionCalls.delete(AreaSpec.of(area), it) }
        else run("delete the point", "Deleted ${label(point!!)}", after = { result ->
            if (result.isSuccess) selectPoint(null)
            done(result)
        }) { RegionCalls.remove(point, it) }
    }

    /** Runs [call] unless a change is on its way; [shown] is the box shown meanwhile, [after] gets the answer. */
    private fun run(what: String, done: String, shown: BlockBox? = null, after: (Result<Unit>) -> Unit = {}, call: ((Result<Unit>) -> Unit) -> Unit) {
        val refused = when {
            busy -> "Wait for the last change to be saved"
            !Bridge.connected -> "No Foreman plugin on this server: areas and points are its"
            else -> null
        }
        if (refused != null) {
            Chat.warn(refused)
            preview = null
            return after(Result.failure(IllegalStateException(refused)))
        }
        busy = true
        preview = shown?.let { it to 0L }
        call { result ->
            busy = false
            result.onSuccess {
                LayoutCheck.changed()
                note("§a✔ $done")
                preview = preview?.let { (box, _) -> box to System.currentTimeMillis() + PREVIEW_MS }
            }.onFailure {
                preview = null
                val message = it.message ?: it.javaClass.simpleName
                note("§c✖ ${message.take(60)}")
                Chat.error("Couldn't $what: $message")
            }
            after(result)
        }
    }

    private fun note(text: String) {
        note = text to System.currentTimeMillis()
    }

    /** What [p] is: its declared type's label, else its `type:kind`. */
    fun label(p: GamePoint) = (Layouts.point(p.type)?.name ?: p.type.ifEmpty { "?" }) + if (p.kind.isNotEmpty()) ":${p.kind}" else ""

    // ---- The panels -------------------------------------------------------------------------------------------------

    /** The panel of the new or selected area or point. */
    fun openPanel() {
        val area = selectedArea?.area()
        when {
            draftPoint != null || selectedPoint != null -> Screens.open(PointPanel(selectedPoint.takeIf { draftPoint == null }))
            isAsset(area) -> Chat.warn("${area!!.name} is an asset: the asset tool edits it")
            area != null -> Screens.open(AreaPanel(selectedArea))
            else -> Chat.warn("Select an area or a point (click it), or make one first")
        }
    }

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    override fun draw(draw: OverlayDraw) {
        val o = Config.settings.overlays
        val selected = selectedArea
        for (area in areas(draw.dimension)) {
            val color = color(area)
            area.blockBoxes.forEachIndexed { i, box ->
                val ref = AreaRef(area.world, area.name, i)
                if (ref == selected) return@forEachIndexed
                val distance = box.distanceTo(draw.camera)
                if (distance > o.maxDistance) return@forEachIndexed
                val lit = ref == hovered
                val c = if (lit) Colors.HOVER else LayoutCheck.tint(area) ?: color
                AIOverlay.box(draw, box, c, if (lit) 3f else 2f, Colors.withAlpha(c, if (lit) 0.08f else 0.03f), onTop = true)
                if (lit || distance <= o.labelDistance + maxOf(box.sizeX, box.sizeZ) / 2.0) draw.label(AIOverlay.topCenter(box), listOfNotNull(describe(area) to c, AIOverlay.lockLine(area.lock)))
            }
        }
        selected?.let { ref -> ref.area()?.takeIf { it.dim == draw.dimension }?.let { area -> selectedBox()?.let { drawSelected(draw, area, it) } } }
        drawPoints(draw)
    }

    /** What an area is, on its label: its name, claim kind, zone and tags (a declared type's by its label). */
    private fun describe(a: Area) = a.name + (a.kind?.let { " (${Layouts.area(it)?.name ?: it})" } ?: "") + (if (a.theme.isNotEmpty()) " · zone" else "") +
        a.tags.joinToString("") { t -> Layouts.area(t)?.let { " [${it.name}]" } ?: " #$t" }

    private fun color(a: Area) = when {
        isAsset(a) -> ASSET
        Layouts.of(a).any { it.argb != null } -> Layouts.of(a).firstNotNullOf { it.argb }
        a.kind != null -> CLAIMS[a.kind] ?: AIOverlay.hashColor(a.kind)
        a.theme.isNotEmpty() -> ZONE
        else -> MARK
    }

    private fun drawSelected(draw: OverlayDraw, area: Area, box: BlockBox) {
        val color = if (busy) Colors.YELLOW else SELECTED
        AIOverlay.box(draw, box, color, 3f, Colors.withAlpha(color, 0.06f), onTop = true)
        // Where it was, while a change is shown before the server has it.
        selectedArea?.let { area.blockBoxes.getOrNull(it.box) }?.takeIf { it != box }?.let { AIOverlay.box(draw, it, Colors.withAlpha(Colors.GRAY, 0.6f), 1.5f, 0, onTop = true) }
        val boxes = if (area.boxes.size > 1) " · box ${selectedArea!!.box + 1} of ${area.boxes.size}" else ""
        draw.label(AIOverlay.topCenter(box), listOfNotNull(describe(area) to color, AIOverlay.lockLine(area.lock), "${box.sizeX}×${box.sizeY}×${box.sizeZ} · ${box.minX} ${box.minY} ${box.minZ}$boxes" to Colors.WHITE))
        if (!busy && !isAsset(area)) boxGizmo.draw(draw)
    }

    private fun drawPoints(draw: OverlayDraw) {
        val o = Config.settings.overlays
        val shown = shownPoint()
        val list = points(draw.dimension).filter { !it.same(selectedPoint) } + listOfNotNull(draftPoint?.takeIf { it.dim == draw.dimension })
        for (p in list) {
            val distance = draw.distance(p.at)
            if (distance > o.maxDistance) continue
            val lit = p == hovered
            val color = if (p === draftPoint) DRAFT else if (lit) Colors.HOVER else LayoutCheck.tint(p) ?: Layouts.point(p.type)?.argb ?: AIOverlay.hashColor(p.type)
            AIOverlay.point(draw, p, color, width = if (lit) 3f else 2f, onTop = true)
            if (lit || p === draftPoint || distance <= POINT_LABELS) draw.label(p.at.add(0.0, 0.45, 0.0), "● " + (if (p === draftPoint) "new point" else label(p)), color, size = 0.7f)
        }
        if (shown == null || shown.dim != draw.dimension) return
        val color = if (busy) Colors.YELLOW else SELECTED
        AIOverlay.point(draw, shown, color, r = 0.25, width = 3f, onTop = true)
        val where = listOfNotNull(shown.build.ifEmpty { null }, shown.room.ifEmpty { null }).joinToString(" · ")
        draw.label(shown.at.add(0.0, 0.55, 0.0), listOfNotNull("● " + label(shown) to color, where.ifEmpty { null }?.let { it to Colors.WHITE }), size = 0.7f)
        pointGizmo.draw(draw)
    }

    /** Where [p] is and faces, for the HUD and the panel: `12.5 64 -3.5 · facing 90°`. */
    fun describePlace(p: GamePoint): String =
        p.at.let { String.format(Locale.ROOT, "%s %s %s", num(it.x), num(it.y), num(it.z)) } + (p.yaw?.let { " · facing ${num(it.toDouble())}°" } ?: "")

    private fun num(v: Double) = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

    override fun snap() = if (selectedPoint != null) pointGizmo.snapKind else Snapping.Kind.MOVE

    override fun status(): ToolStatus {
        val area = selectedArea?.area()
        val point = selectedPoint
        val draft = draftPoint
        val shown = when {
            draft != null -> Shown("New point", "", "at ${describePlace(draft)}")
            area != null -> Shown("Area", area.name, kinds(area))
            selectedArea != null -> Shown("Area", selectedArea!!.name, "not in the server's areas")
            point != null -> Shown("Point", label(point), describePlace(shownPoint()!!))
            else -> null
        }
        val details = mutableListOf<String>()
        val hints = mutableListOf<Hint>()
        when {
            draft != null -> hints += Hint("N", "its panel: give it a type")
            area != null -> {
                selectedBox()?.let { b -> details += "${b.sizeX}×${b.sizeY}×${b.sizeZ} from ${b.minX} ${b.minY} ${b.minZ}" + (if (area.boxes.size > 1) " · box ${selectedArea!!.box + 1} of ${area.boxes.size}" else "") }
                if (area.tags.isNotEmpty()) details += area.tags.joinToString(" ") { "#$it" }
                if (area.note.isNotEmpty()) details += area.note.take(80)
                when {
                    isAsset(area) -> details += "An asset: the asset tool edits it"
                    area.dim != playerDimension() -> details += "In ${area.world}"
                    boxGizmo.hovered != null || boxGizmo.dragging -> {
                        val face = (boxGizmo.dragged ?: boxGizmo.hovered)!!.face >= 0
                        hints += Hint(ToolStatus.DRAG, if (face) "move this face (the area only)" else "move the box (the area only)")
                        if (face) hints += Hint("Ctrl", "both sides at once")
                    }
                    else -> hints += Hint(ToolStatus.DRAG, "an arrow moves the box, a knob resizes it")
                }
            }
            selectedArea != null -> details += if (System.currentTimeMillis() - (note?.second ?: 0) < PREVIEW_MS) "Waiting for the server's areas…" else "It isn't in the server's areas (any more)"
            point != null -> {
                listOfNotNull(point.build.ifEmpty { null }, point.room.ifEmpty { null }).takeIf { it.isNotEmpty() }?.let { details += it.joinToString(" · ") }
                if (point.data.isNotEmpty()) details += point.data.entries.joinToString(" · ") { (k, v) -> "$k ${(v as? JsonPrimitive)?.content ?: v}" }.take(80)
                pointGizmo.describe()?.takeIf { pointGizmo.dragging }?.let { details += "Dragged: $it" }
                hints += if (pointGizmo.dragging) Hint("Ctrl", "drag freely") else Hint(ToolStatus.DRAG, "an arrow moves it, the center anywhere, the rings turn it and tilt it")
                if (pointGizmo.dragging && pointGizmo.movingFreely) hints += Hint(ToolStatus.SCROLL, "farther, nearer")
            }
        }
        if (!boxGizmo.dragging && !pointGizmo.dragging) hints.add(0, Hint(ToolStatus.LEFT, if (hovered != null) "select it" else "on an area or a point: select it"))
        if (area != null && !isAsset(area) || point != null) hints += Hint("N", "its panel")
        if (draft == null && area == null && point == null) {
            hints += if (SelectionTool.currentLabel() != null) Hint("M", "new area from the selection") else Hint("Tool 1", "make a selection, then M: a new area")
            hints += Hint("P", "new point where you aim")
            if (Bridge.layout.isNotEmpty()) hints += Hint("K", "check the layout")
        }
        val hover = when (val h = hovered) {
            is AreaRef -> h.takeIf { it != selectedArea }?.area()?.let { Shown("Area", it.name, kinds(it)) }
            is GamePoint -> if (h != selectedPoint) Shown("Point", label(h)) else null
            else -> null
        }
        val asking = System.currentTimeMillis() - deleteAsked <= CONFIRM_MS
        val notes = listOfNotNull(
            if (busy) Note("Saving…", Note.Tone.BUSY) else note?.takeIf { !asking && System.currentTimeMillis() - it.second < NOTE_MS }?.let { Note.coloured(it.first) },
            if (!Bridge.connected) Note("No Foreman plugin here: areas and points are its", Note.Tone.ERROR) else null,
        )
        val prompt = if (asking && shown != null) "Press Delete again to delete ${area?.name ?: point?.let(::label) ?: "it"}" else null
        return ToolStatus(shown, "Click an area or a point to select it", details, hover, hints, prompt, notes)
    }

    /** What an area is besides its name: `Spawn zone · build claim · zone`, empty for a plain one. */
    private fun kinds(a: Area) = (Layouts.of(a).map { it.name } + listOfNotNull(a.kind?.let { "$it claim" }, if (a.theme.isNotEmpty()) "zone" else null)).joinToString(" · ")
}
