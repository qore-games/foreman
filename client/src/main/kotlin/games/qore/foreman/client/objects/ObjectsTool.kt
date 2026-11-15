package games.qore.foreman.client.objects

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.EditorMode
import games.qore.foreman.client.editor.EditorTool
import games.qore.foreman.client.editor.EditorTools
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.Gizmo
import games.qore.foreman.client.gizmo.GizmoChange
import games.qore.foreman.client.gizmo.GizmoFrame
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.link.AIOverlay
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.BridgeLayer
import games.qore.foreman.client.link.GameObject
import games.qore.foreman.client.link.ObjectKind
import games.qore.foreman.client.panels.Inspector
import games.qore.foreman.client.panels.Panels
import games.qore.foreman.client.panels.Targets
import games.qore.foreman.client.regions.LayoutForm
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * Edits the objects the server's plugins draw with entities and declare (cables, props: `ForemanObjects`), a tool of
 * editor mode that knows nothing of what they are. While it is on, the server sends the objects near the player: each
 * shows as its outline (a cable's curve as its plugin computes it, else the line through its anchors; a point's marker)
 * in its kind's colour, named when near or aimed at. A click selects one: its anchors show as knobs, the active one
 * with the gizmo's arrows, which move it as the editor's snapping says (a click on another knob makes it the active
 * one). A click on the selected object or N opens its [panel][ObjectPanel]: its kind's form, its plugins' panel (N with
 * nothing selected: the panel of what the crosshair is on). The server's plugins add keys of their own ([ObjectActions]).
 * C starts a new object of a kind picked from the list: click its anchors (two for a line), then its form, with the
 * defaults, places it. An object with a status shows its badge (a word in its level's colour) under its name. Changes go through the server's `place_objects`, `edit_objects` and `remove_objects`, one undo step each;
 * nothing runs while the tool is off. With panels on the server, the [Inspector] adds the plugins' panels, their
 * relations as arrows and the link mode (K).
 */
object ObjectsTool : EditorTool {
    override val id = "objects"
    override val title = "World objects"
    const val REACH = 256.0
    /** Lines are picked this close to the crosshair, point markers a bit further; names show this near. */
    private const val PICK = 0.3
    private const val POINT_PICK = 0.4
    private const val LABELS = 24.0
    private const val DRAFT = 0xFF7FD8FF.toInt()
    private const val SELECTED = 0xFF4FB3FF.toInt()
    /** How long a change is shown before the server's objects have it. */
    private const val PREVIEW_MS = 5000L
    private const val NOTE_MS = 6000L
    /** A second Delete within this deletes. */
    private const val CONFIRM_MS = 3000L

    /** A new object of [kind], with the anchors clicked so far and the fields where it goes fills in ([data]: [KindGhost]'s). */
    class Placing(val kind: ObjectKind, val anchors: MutableList<Vec3> = mutableListOf(), var data: JsonObject = JsonObject(emptyMap()))

    /** The selected object, as last seen in the server's objects. */
    var selected: GameObject? = null
        private set
    /** The anchor of the selected object that the gizmo moves. */
    private var active = 0
    /** A new object whose anchors are being clicked. */
    var placing: Placing? = null
        private set
    /** A new object with all its anchors, waiting for its panel. */
    var draft: Placing? = null
        private set
    /** The active anchor as dragged. */
    private var dragged: Vec3? = null
    /** The selected object as a change on its way shows it. */
    private var pending: GameObject? = null
    /** Objects changed here, shown until the server's objects come after the change (or a while): null when removed. */
    private val recent = LinkedHashMap<String, Pair<GameObject?, Long>>()
    var busy = false
        private set
    private var note: Pair<String, Long>? = null
    private var hovered: GameObject? = null
    /** Where an anchor would go: where the crosshair meets a block, snapped. */
    private var target: Vec3? = null
    private var deleteAsked = 0L
    private var subscribed = false

    private val gizmo = TransformGizmo(
        frame = ::anchorFrame, changed = ::anchorDragged, committed = ::anchorDropped, cancelled = { dragged = null },
        rings = emptyList(), grid = ::grid,
    )

    val enabled get() = Tools.active === this

    fun init() {
        EditorTools.register(this)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { forget() } }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> forget() }
    }

    override fun keys(): List<EditorKey> {
        if (Inspector.picking) return Inspector.keys()
        val own = listOf(
            EditorKey(InputConstants.KEY_C, "New object: pick its kind") { newObject() },
            EditorKey(InputConstants.KEY_N, "Its panel: the selected object's, else what you aim at") { openPanel() },
            EditorKey(InputConstants.KEY_V, "Next anchor") { nextAnchor() },
            EditorKey(InputConstants.KEY_P, "The anchor to where you aim") { anchorToAim() },
            EditorKey(InputConstants.KEY_DELETE, "Delete it, or unlink the relation (press twice)") { if (!Inspector.delete()) delete() },
            EditorKey(InputConstants.KEY_Z, "Undo the server's last change") { undo() },
        ) + Inspector.keys()
        val taken = (own + EditorMode.keys()).map { it.key }.toSet() + InputConstants.KEY_B
        return own + ObjectActions.keys(taken)
    }

    override fun available() = Bridge.connected

    // ---- Selection --------------------------------------------------------------------------------------------------

    fun select(obj: GameObject?) {
        gizmo.cancel()
        dragged = null
        pending = null
        if (obj?.id != selected?.id) active = 0
        selected = obj
        if (obj != null) Inspector.deselect()
    }

    /** Forgets everything (the game left the server). */
    private fun forget() {
        if (enabled) Tools.deactivate()
        select(null)
        recent.clear()
        busy = false
    }

    /** The objects of [dimension] as shown: the server's, with the changes made here it hasn't sent yet. */
    fun objects(dimension: String): List<GameObject> {
        val now = System.currentTimeMillis()
        recent.entries.removeIf { (_, v) -> Bridge.objectsAt > v.second || now - v.second > PREVIEW_MS }
        return Bridge.objects.filter { it.dim == dimension && it.id !in recent } + recent.values.mapNotNull { it.first }.filter { it.dim == dimension }
    }

    /** [obj] is now [now] (null: removed), until the server's objects say so. */
    private fun shown(obj: GameObject, now: GameObject?) {
        val at = System.currentTimeMillis()
        recent[obj.id] = null to at
        now?.let { recent[it.id] = it to at }
    }

    /** What [obj] is, for people: its kind's name. */
    fun name(obj: GameObject) = obj.type?.name ?: obj.kind

    private fun color(obj: GameObject) = obj.type?.argb ?: AIOverlay.hashColor(obj.kind)

    // ---- The tool ---------------------------------------------------------------------------------------------------

    override fun press(button: MouseButton, ray: Ray) {
        if (Inspector.press(button, ray)) return
        placing?.let { p ->
            if (button == MouseButton.RIGHT) {
                if (p.anchors.isEmpty()) placing = null else p.anchors.removeAt(p.anchors.lastIndex)
                return
            }
            val at = target ?: return note("§eAim at a block")
            if (KindGhost.shows(p.kind)) {
                KindGhost.refusal()?.let { return note("§e$it") }
                p.data = KindGhost.data()
            }
            p.anchors += at
            if (p.anchors.size >= p.kind.anchors) {
                placing = null
                draft = p
                Screens.open(ObjectPanel(null))
            }
            return
        }
        if (button != MouseButton.LEFT || gizmo.press(ray)) return
        val current = selected
        if (current != null && !busy) knob(ray, current)?.let {
            active = it
            return
        }
        val dimension = playerDimension() ?: return
        val hit = pick(ray, dimension)
        if (hit == null && Inspector.click(ray)) return select(null)
        if (hit != null && hit.id == current?.id) openPanel() else select(hit)
    }

    override fun release(button: MouseButton) {
        if (button == MouseButton.LEFT) gizmo.release()
    }

    override fun scroll(amount: Double): Boolean {
        if (placing?.kind?.let(KindGhost::shows) == true) return true.also { KindGhost.turn(amount > 0) }
        return gizmo.dragging && gizmo.nudge(kotlin.math.sign(amount).toInt())
    }

    override fun update(ray: Ray) {
        if (!subscribed) {
            Bridge.need(BridgeLayer.OBJECTS, true)
            subscribed = true
        }
        val dimension = playerDimension()
        if (!busy) selected?.let { s -> dimension?.let { objects(it) }?.firstOrNull { it.id == s.id }?.let { selected = it } }
        target = (placing?.kind ?: selected?.type)?.let { if (placing != null && KindGhost.shows(it)) KindGhost.aim(ray) else target(ray, it) }
        // The new object as it will stand: where it goes while placing, where it was put while its form is open.
        val ghosted = (placing ?: draft)?.takeIf { KindGhost.shows(it.kind) }
        KindGhost.update(if (ghosted == null) null else if (ghosted === placing) target else ghosted.anchors.firstOrNull())
        gizmo.update(ray)
        hovered = if (gizmo.dragging || gizmo.hovering || placing != null || Inspector.takesClicks || dimension == null) null else pick(ray, dimension)
        Inspector.update(ray, hovered != null)
    }

    /** Whether the selected object is among those the server sends now (it is near and still there). */
    private fun inView(obj: GameObject) = objects(obj.dim).any { it.id == obj.id }

    /** The object under the crosshair, the nearest along it: its outline's segments (a point: its marker). */
    private fun pick(ray: Ray, dimension: String): GameObject? = aimedObject(ray, dimension)?.first

    /**
     * The object under [ray] in [dimension] and how far along it, the nearest: the selected one anywhere along its
     * outline, the others where they show ([ObjectFade]).
     */
    fun aimedObject(ray: Ray, dimension: String): Pair<GameObject, Double>? = objects(dimension).mapNotNull { o ->
        if (o.id != selected?.id && !Inspector.shows(o)) return@mapNotNull null
        val line = o.line
        if (line.isEmpty()) return@mapNotNull null
        val (distance, along) = when {
            line.size == 1 -> ray.toPoint(line[0]).takeIf { o.id == selected?.id || ObjectFade.byDistance(ray.origin.distanceTo(line[0])) > 0f }
            o.id == selected?.id -> line.zipWithNext { a, b -> ray.toSegment(a, b) }.minBy { it.first }
            else -> ObjectFade.pieces(line, o.anchorsAt, ray.origin).filter { it.pickable }.map { ray.toSegment(it.from, it.to) }.minByOrNull { it.first }
        } ?: return@mapNotNull null
        if (distance <= (if (line.size == 1) POINT_PICK else PICK) && along <= REACH) o to along else null
    }.minByOrNull { it.second }

    /** The anchor of [obj] whose knob is under [ray], the nearest. */
    private fun knob(ray: Ray, obj: GameObject): Int? = obj.anchorsAt.withIndex().mapNotNull { (i, a) ->
        val (distance, along) = ray.toPoint(a)
        if (distance <= knobSize(ray.origin.distanceTo(a)) * 1.6) i to along else null
    }.minByOrNull { it.second }?.first

    /** A knob's half size at [distance] from the camera: the same size on screen. */
    private fun knobSize(distance: Double) = Gizmo.size(distance) * 0.1

    /**
     * Where an anchor of [kind] goes for [ray]: a line's on the face the crosshair meets (its centre on the block grid),
     * a point's on the floor of the block before it (its middle on the block grid); else right there, to the move step.
     */
    private fun target(ray: Ray, kind: ObjectKind): Vec3? {
        val hit = ray.block(REACH, solid = !kind.isLine) ?: return null
        val d = hit.direction
        if (!Snapping.onGrid) return Snapping.position(hit.location)
        if (!kind.isLine) return hit.blockPos.relative(d).let { Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5) }
        val p = hit.blockPos
        return Vec3(p.x + 0.5 + 0.5 * d.stepX, p.y + 0.5 + 0.5 * d.stepY, p.z + 0.5 + 0.5 * d.stepZ)
    }

    override fun escape(): Boolean {
        if (Inspector.escape()) return true
        when {
            gizmo.dragging -> gizmo.cancel()
            placing != null -> placing = null.also { KindGhost.clear() }
            draft != null -> draft = null.also { KindGhost.clear() }
            selected != null -> select(null)
            else -> return false
        }
        return true
    }

    override fun deactivated() {
        KindGhost.clear()
        gizmo.cancel()
        dragged = null
        placing = null
        draft = null
        hovered = null
        if (subscribed) {
            Bridge.need(BridgeLayer.OBJECTS, false)
            subscribed = false
        }
        Inspector.deactivated()
    }

    // ---- New objects ------------------------------------------------------------------------------------------------

    /** A new object: the kinds to pick from (straight to placing when there is one). */
    fun newObject() {
        val kinds = Bridge.objectKinds
        when (kinds.size) {
            0 -> Chat.warn("No plugin on this server declares object kinds")
            1 -> start(kinds[0])
            else -> Screens.open(KindPicker())
        }
    }

    /** Starts placing an object of [kind]: the clicks put its anchors. */
    fun start(kind: ObjectKind) {
        KindGhost.start(kind)
        select(null)
        Inspector.leaveModes()
        Inspector.deselect()
        draft = null
        placing = Placing(kind)
    }

    // ---- Anchors ----------------------------------------------------------------------------------------------------

    private fun grid() = if (selected?.type?.isLine == true) Snapping.Grid.CENTRES else Snapping.Grid.FLOOR

    /** The gizmo on the selected object's active anchor, while nothing is on its way. */
    private fun anchorFrame(): GizmoFrame? {
        val obj = selected?.takeIf { !busy && placing == null && it.dim == playerDimension() } ?: return null
        val at = obj.anchorsAt.getOrNull(active) ?: return null
        return GizmoFrame(dragged ?: at, GizmoFrame.WORLD_AXES)
    }

    private fun anchorDragged(change: GizmoChange) {
        val at = selected?.anchorsAt?.getOrNull(active) ?: return
        if (change is GizmoChange.Move) dragged = at.add(change.offset)
    }

    private fun anchorDropped(change: GizmoChange) {
        anchorDragged(change)
        val obj = selected ?: return
        val to = dragged ?: return
        dragged = null
        moveAnchor(obj, active, to)
    }

    private fun nextAnchor() {
        val obj = selected ?: return Chat.warn("Select an object first: click it")
        active = (active + 1) % obj.anchors.size.coerceAtLeast(1)
    }

    private fun anchorToAim() {
        val obj = selected ?: return Chat.warn("Select an object first: click it")
        val at = target ?: return Chat.warn("Aim at a block")
        moveAnchor(obj, active, at)
    }

    /** Moves [obj]'s anchor [index] to [to]: shown with straight lines meanwhile, its plugin draws the rest. */
    private fun moveAnchor(obj: GameObject, index: Int, to: Vec3) {
        val anchors = obj.anchorsAt.toMutableList().also { if (index in it.indices) it[index] = to }
        val what = obj.type?.anchorName(index)?.lowercase()?.let { "the $it of the ${name(obj)}" } ?: "the ${name(obj)}"
        pending = obj.copy(anchors = anchors.map { listOf(it.x, it.y, it.z) }, outline = emptyList())
        run("move $what", "Moved $what", after = { result: Result<GameObject> ->
            pending = null
            result.onSuccess { now ->
                shown(obj, now)
                select(now)
            }
        }) { ObjectCalls.edit(obj, anchors, null, it) }
    }

    // ---- Changes ----------------------------------------------------------------------------------------------------

    /**
     * Saves the new object at [anchors] with [data] (the form's, all of it), or the selected one changed: at [anchors],
     * with [data] the fields the builder changed (null: back to the default); the others stay as the server has them.
     */
    fun save(anchors: List<Vec3>, data: JsonObject, done: (Result<Unit>) -> Unit) {
        val draft = draft
        val obj = selected
        if (draft != null) {
            val dimension = playerDimension() ?: return done(Result.failure(IllegalStateException("Not in a world")))
            return run("place the ${draft.kind.name}", "Placed the ${draft.kind.name}", after = { result: Result<GameObject> ->
                result.onSuccess { placed ->
                    this.draft = null
                    recent[placed.id] = placed to System.currentTimeMillis()
                    select(placed)
                }
                done(result.map { })
            }) { ObjectCalls.place(draft.kind.id, Bridge.world, dimension, anchors, data, it) }
        }
        if (obj == null) return done(Result.failure(IllegalStateException("Nothing selected")))
        val moved = !same(anchors, obj.anchorsAt)
        val changed = data.isNotEmpty()
        if (!moved && !changed) return done(Result.success(Unit))
        run("save the ${name(obj)}", "Saved the ${name(obj)}", after = { result: Result<GameObject> ->
            result.onSuccess { now ->
                shown(obj, now)
                select(now)
            }
            done(result.map { })
        }) { ObjectCalls.edit(obj, anchors.takeIf { moved }, data.takeIf { changed }, it) }
    }

    private fun same(a: List<Vec3>, b: List<Vec3>) = a.size == b.size && a.indices.all { a[it].distanceToSqr(b[it]) < 1e-8 }

    /** Deletes the selected object; the key asks twice. */
    fun delete(confirmed: Boolean = false, done: (Result<Unit>) -> Unit = {}) {
        val obj = selected ?: return Chat.warn("Select an object first: click it")
        val now = System.currentTimeMillis()
        if (!confirmed && now - deleteAsked > CONFIRM_MS) {
            deleteAsked = now
            return note("§eDelete the ${name(obj)}? Press Delete again")
        }
        deleteAsked = 0
        run("delete the ${name(obj)}", "Deleted the ${name(obj)}", after = { result: Result<Unit> ->
            result.onSuccess {
                shown(obj, null)
                select(null)
            }
            done(result)
        }) { ObjectCalls.remove(obj, it) }
    }

    private fun undo() = run("undo", "Undone", after = { result: Result<String> ->
        result.onSuccess {
            recent.clear()
            note("§a✔ " + it.lineSequence().firstOrNull().orEmpty().take(60))
        }
    }) { ObjectCalls.undo(it) }

    /** Runs [call] unless a change is on its way; [after] gets the answer. */
    private fun <T> run(what: String, done: String, after: (Result<T>) -> Unit, call: ((Result<T>) -> Unit) -> Unit) {
        val refused = when {
            busy -> "Wait for the last change to be saved"
            !Bridge.connected -> "No Foreman plugin on this server: objects are its plugins'"
            else -> null
        }
        if (refused != null) {
            Chat.warn(refused)
            return after(Result.failure(IllegalStateException(refused)))
        }
        busy = true
        call { result ->
            busy = false
            result.onSuccess { note("§a✔ $done") }.onFailure {
                val message = it.message ?: it.javaClass.simpleName
                note("§c✖ ${message.lineSequence().first().take(60)}")
                Chat.error("Couldn't $what: $message")
            }
            after(result)
        }
    }

    private fun note(text: String) {
        note = text to System.currentTimeMillis()
    }

    // ---- The panel --------------------------------------------------------------------------------------------------

    /** The object [target] names, when the server sends it and declares its kind: its panel is [inspect]'s. */
    fun objectOf(target: JsonObject): GameObject? {
        if (target["type"].str() != "object") return null
        val id = target["id"].str() ?: return null
        val kind = target["kind"].str()
        return playerDimension()?.let(::objects)?.firstOrNull { it.id == id && it.kind == kind && it.type != null }
    }

    /** Selects [obj] and opens its panel: its form, its plugins' panel under it. */
    fun inspect(obj: GameObject) {
        if (!enabled) Tools.activate(this)
        Inspector.leaveModes()
        placing = null
        draft = null
        select(obj)
        Screens.open(ObjectPanel(obj))
    }

    /** The form of the new or selected object, with its plugins' panel. */
    fun openPanel() {
        val obj = selected
        when {
            draft != null -> Screens.open(ObjectPanel(null))
            obj == null -> if (Panels.supported) Inspector.inspect() else Chat.warn("Select an object (click it), or make one: C")
            obj.type == null -> Chat.warn("No plugin here declares ${obj.kind} any more")
            else -> Screens.open(ObjectPanel(obj))
        }
    }

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    override fun draw(draw: OverlayDraw) {
        val current = selected
        for (obj in objects(draw.dimension)) {
            if (obj.id == current?.id || !Inspector.shows(obj)) continue
            val line = obj.line.ifEmpty { continue }
            if (obj.id == hovered?.id) {
                drawObject(draw, obj, line, Colors.HOVER, 3f)
                draw.label(labelAt(line), listOfNotNull(name(obj) to Colors.HOVER, badge(obj, 1f)), size = 0.7f)
            } else drawFaded(draw, obj, line)
        }
        current?.takeIf { it.dim == draw.dimension }?.let { drawSelected(draw, it) }
        drawNew(draw)
        Inspector.draw(draw)
    }

    /**
     * [obj], neither selected nor aimed at, as [line]: lighter, fading with distance ([ObjectFade]); named when near. A
     * point's marker only when its plugin gives no outline: one shape per object, the one builders see.
     */
    private fun drawFaded(draw: OverlayDraw, obj: GameObject, line: List<Vec3>) {
        val color = color(obj)
        val point = line.size == 1
        val anchor = obj.anchorsAt.firstOrNull()
        val pieces = if (line.size > 1) ObjectFade.pieces(line, obj.anchorsAt, draw.camera) else emptyList()
        for (p in pieces) draw.line(p.from, p.to, Colors.withAlpha(color, ObjectFade.ALPHA * p.alpha), ObjectFade.WIDTH, onTop = true)
        val distance = line.minOf { draw.distance(it) }
        val near = ObjectFade.byDistance(distance)
        if (point && anchor != null && near > 0f) marker(draw, anchor, Colors.withAlpha(color, ObjectFade.ALPHA * near), 0.15, ObjectFade.WIDTH)
        // Text this faint would be drawn opaque.
        if (near > 0.1f && distance <= LABELS) draw.label(labelAt(line), listOfNotNull(name(obj) to Colors.withAlpha(color, near), badge(obj, near)), size = 0.7f)
    }

    /** [obj]'s status badge as a label line (its mark and word in its level's colour), [alpha] as its name's; null without one. */
    private fun badge(obj: GameObject, alpha: Float): Pair<String, Int>? = obj.status?.let { "${it.mark} ${it.word}" to Colors.withAlpha(it.argb, alpha) }

    /** [obj] as [line] in [color]: its segments, or a point's marker at its anchor when it has no outline. */
    private fun drawObject(draw: OverlayDraw, obj: GameObject, line: List<Vec3>, color: Int, width: Float) {
        for (i in 0 until line.size - 1) draw.line(line[i], line[i + 1], color, width, onTop = true)
        if (line.size == 1) obj.anchorsAt.firstOrNull()?.let { marker(draw, it, color, 0.15, width) }
    }

    private fun marker(draw: OverlayDraw, at: Vec3, color: Int, r: Double, width: Float = 2f) =
        draw.box(at.subtract(r, r, r), at.add(r, r, r), color, width, Colors.withAlpha(color, 0.35f), onTop = true)

    private fun labelAt(line: List<Vec3>) = line[line.size / 2].add(0.0, 0.4, 0.0)

    private fun drawSelected(draw: OverlayDraw, obj: GameObject) {
        val color = if (busy) Colors.YELLOW else SELECTED
        val shown = pending ?: obj
        val anchors = shown.anchorsAt.toMutableList()
        dragged?.let { if (active in anchors.indices) anchors[active] = it }
        if (dragged != null || pending != null) {
            // Where it was, and its anchors' new place: its plugin draws the rest once it has it.
            drawObject(draw, obj, obj.line, Colors.withAlpha(Colors.GRAY, 0.6f), 1.5f)
            for (i in 0 until anchors.size - 1) draw.line(anchors[i], anchors[i + 1], color, 3f, onTop = true)
        } else drawObject(draw, obj, obj.line, color, 3f)
        anchors.forEachIndexed { i, a ->
            val c = if (i == active) Colors.WHITE else color
            if (obj.anchors.size > 1 || dragged != null) marker(draw, a, c, knobSize(draw.distance(a)), if (i == active) 3f else 2f)
        }
        val where = obj.type?.let { t -> anchors.getOrNull(active)?.let { "${t.anchorName(active)} ${place(it)}" } }
        draw.label(labelAt(obj.line.ifEmpty { anchors }), listOfNotNull("◆ " + name(obj) to color, badge(obj, 1f), where?.let { it to Colors.WHITE }), size = 0.7f)
        if (!busy) gizmo.draw(draw)
    }

    /** The new object: the anchors clicked, and the line to where the next goes. */
    private fun drawNew(draw: OverlayDraw) {
        val p = placing ?: draft ?: return
        val anchors = p.anchors + listOfNotNull(target.takeIf { placing != null })
        if (KindGhost.shows(p.kind) && anchors.isNotEmpty() && KindGhost.draw(draw, anchors.last(), p.kind.name)) return
        for (i in 0 until anchors.size - 1) draw.line(anchors[i], anchors[i + 1], DRAFT, 2.5f, onTop = true)
        anchors.forEachIndexed { i, a -> marker(draw, a, if (i >= p.anchors.size) Colors.HOVER else DRAFT, knobSize(draw.distance(a)) * 0.8) }
        val next = target?.takeIf { placing != null }
        if (next != null) draw.label(next.add(0.0, 0.5, 0.0), "${p.kind.name}: ${p.kind.anchorName(p.anchors.size).lowercase()}", DRAFT, size = 0.7f)
    }

    fun place(v: Vec3) = String.format(Locale.ROOT, "%s %s %s", num(v.x), num(v.y), num(v.z))

    private fun num(v: Double) = LayoutForm.num(v)

    override fun status(): ToolStatus {
        val obj = selected
        val placing = placing
        val draft = draft
        val shown = when {
            placing != null -> Shown("New", placing.kind.name, "${placing.anchors.size} of ${placing.kind.anchors} anchors")
            draft != null -> Shown("New", draft.kind.name, "its form: N")
            obj != null -> Shown("Object", name(obj), obj.id)
            else -> null
        }
        val details = mutableListOf<String>()
        val hints = mutableListOf<Hint>()
        when {
            placing != null -> {
                hints += Hint(ToolStatus.LEFT, "put its ${placing.kind.anchorName(placing.anchors.size).lowercase()} here")
                if (KindGhost.shows(placing.kind)) hints += Hint(ToolStatus.SCROLL, "turn it")
                hints += Hint(ToolStatus.RIGHT, if (placing.anchors.isEmpty()) "cancel" else "take back the last anchor")
            }
            draft != null -> hints += Hint("N", "its form: place it")
            obj != null -> {
                obj.status?.let { details += "${it.mark} ${it.text}" }
                obj.type?.let { t -> obj.anchorsAt.forEachIndexed { i, a -> details += "${t.anchorName(i)} ${place(a)}" + if (i == active && obj.anchors.size > 1) "  ◂" else "" } }
                if (obj.data.isNotEmpty()) details += obj.data.entries.joinToString(" · ") { (k, v) -> "$k ${(v as? JsonPrimitive)?.content ?: (v as? JsonObject)?.let(Targets::named) ?: v}" }.take(80)
                if (obj.type == null) details += "No plugin here declares ${obj.kind} any more"
                if (!inView(obj)) details += "Out of the server's view: come closer"
                gizmo.describe()?.takeIf { gizmo.dragging }?.let { details += "Dragged: $it" }
                hints += if (gizmo.dragging) Hint("Ctrl", "drag freely") else Hint(ToolStatus.DRAG, "an arrow moves the active anchor, the center anywhere")
                if (gizmo.dragging && gizmo.movingFreely) hints += Hint(ToolStatus.SCROLL, "farther, nearer")
                if (obj.anchors.size > 1) hints += Hint(ToolStatus.LEFT, "on a knob: make it the active anchor")
                hints += Hint("N", "its panel")
            }
        }
        if (placing == null && !gizmo.dragging) hints.add(0, Hint(ToolStatus.LEFT, when {
            hovered != null && hovered?.id == obj?.id -> "its panel"
            hovered != null -> "select it"
            else -> "on an object: select it"
        }))
        if (placing == null && draft == null && obj == null) hints += Hint("C", if (Bridge.objectKinds.isEmpty()) "new object (no kinds here)" else "new object: ${Bridge.objectKinds.size} kinds")
        val hover = hovered?.takeIf { it.id != obj?.id }?.let { Shown("Object", name(it), it.id) }
        val asking = System.currentTimeMillis() - deleteAsked <= CONFIRM_MS
        val notes = listOfNotNull(
            if (busy) Note("Saving…", Note.Tone.BUSY) else note?.takeIf { !asking && System.currentTimeMillis() - it.second < NOTE_MS }?.let { Note.coloured(it.first) },
            if (!Bridge.connected) Note("No Foreman plugin here: objects are its plugins'", Note.Tone.ERROR) else null,
        ) + ObjectActions.notes()
        val prompt = if (asking && obj != null) "Press Delete again to delete the ${name(obj)}" else null
        return Inspector.status(ToolStatus(shown, "Click an object to select it, or C for a new one", details, hover, hints, prompt, notes))
    }
}
