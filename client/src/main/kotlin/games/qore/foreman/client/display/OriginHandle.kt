package games.qore.foreman.client.display

import games.qore.foreman.client.gizmo.GizmoChange
import games.qore.foreman.client.gizmo.GizmoFrame
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.util.Chat
import net.minecraft.world.entity.Display
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Where the display editor's displays stand as entities: a marker on the position of the selected and hovered ones, a
 * line to where their model starts (their origin) and their coordinates. A display posed like furniture often stands
 * far from what it shows (turned to face down, a translation of blocks: its entity under the floor). In its mode
 * ([on]) the editor's gizmo, arrows only, on the primary's position moves the entities of the selection while their
 * models stay where they are: each translation takes the move back, turned into its display's own axes. It snaps as the
 * editor's snapping says (block snapping: the floor centres of blocks); a furniture's entity moves by whole blocks (the
 * server places it again on its new root block).
 */
object OriginHandle {
    private const val COLOR = 0xFFFF9F40.toInt()
    private const val MARKER = 0.07
    /** Up to this many selected displays have their coordinates shown, else the primary's only. */
    private const val LABELS = 6

    /** Moving positions instead of the editor's gizmo. */
    var on = false
        private set

    private val gizmo = TransformGizmo(
        frame = { position()?.let { GizmoFrame(it, GizmoFrame.WORLD_AXES) } }, changed = ::moved, committed = ::committed, cancelled = { cancel() },
        rings = emptyList(), grid = { Snapping.Grid.FLOOR }, wholeBlocks = { furniture(primary ?: DisplayEditor.selected.lastOrNull()) },
    )
    private var primary: UUID? = null
    /** The dragged displays' poses at the start, and what the drag makes of them. */
    private var starts: Map<UUID, DisplayPose>? = null
    private var previewed: Map<UUID, DisplayPose>? = null

    val dragging get() = gizmo.dragging
    val hovering get() = on && gizmo.hovering

    fun toggle() {
        if (gizmo.dragging) return
        on = !on
    }

    /** Starts a drag when [ray] is on the arrows; true when the click was theirs. */
    fun press(ray: Ray): Boolean {
        if (!on || !gizmo.press(ray)) return false
        if (DisplayEditor.busy) {
            cancel()
            Chat.warn("Wait for the last change to be saved")
            return true
        }
        val displays = DisplayEditor.selectedDisplays()
        primary = displays.lastOrNull()?.uuid
        starts = displays.associate { it.uuid to DisplayPose.of(it) }
        return true
    }

    fun release() {
        gizmo.release()
        end()
    }

    fun update(ray: Ray) {
        if (!on) return
        gizmo.update(ray)
        // Again every frame: the server's position syncs would put them back meanwhile.
        previewed?.forEach { (id, pose) -> DisplayEditor.nearby[id]?.let(pose::applyTo) }
    }

    /** Ends a drag where it started. */
    fun cancel() {
        val starts = starts ?: return gizmo.cancel()
        end()
        gizmo.cancel()
        starts.forEach { (id, pose) -> DisplayEditor.nearby[id]?.let(pose::applyTo) }
    }

    private fun end() {
        primary = null
        starts = null
        previewed = null
    }

    /** The primary's entity position, as the drag shows it. */
    private fun position(): Vec3? {
        val id = primary ?: DisplayEditor.selected.lastOrNull() ?: return null
        return previewed?.get(id)?.pos ?: DisplayEditor.nearby[id]?.position()
    }

    private fun furniture(id: UUID?) = id?.let { DisplayEditor.nearby[it] }?.let { DisplayEditor.kind(it) == "furniture" } == true

    private fun moved(change: GizmoChange) {
        val starts = starts ?: return
        val offset = (change as? GizmoChange.Move)?.offset ?: return
        val poses = starts.mapValues { (id, pose) -> entityMoved(pose, if (furniture(id)) whole(offset) else offset) }
        previewed = poses
        poses.forEach { (id, pose) -> DisplayEditor.nearby[id]?.let(pose::applyTo) }
    }

    /** Saved by the editor from where they were, as one change (`edit_displays`: position and transformation). */
    private fun committed(change: GizmoChange) {
        moved(change)
        val starts = starts ?: return
        val after = previewed ?: return
        end()
        starts.forEach { (id, pose) -> DisplayEditor.nearby[id]?.let(pose::applyTo) }
        DisplayEditor.change { e, pose -> after[e.uuid] ?: pose }
    }

    /** [pose] with its entity [offset] away and its model where it was: its translation takes the move back. */
    fun entityMoved(pose: DisplayPose, offset: Vec3): DisplayPose {
        val back = Quaterniond(pose.orientation).conjugate().transform(Vector3d(-offset.x, -offset.y, -offset.z))
        return pose.copy(pos = pose.pos.add(offset), translation = Vector3d(pose.translation).add(back))
    }

    private fun whole(v: Vec3) = Vec3(v.x.roundToInt().toDouble(), v.y.roundToInt().toDouble(), v.z.roundToInt().toDouble())

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    /** The markers of the selected displays and [hovered], and the arrows in its mode. */
    fun draw(draw: OverlayDraw, hovered: Display?) {
        val selected = DisplayEditor.selectedDisplays()
        val primary = selected.lastOrNull()
        for (e in (selected + listOfNotNull(hovered)).distinct()) {
            val pose = DisplayPose.of(e)
            val p = pose.pos
            draw.point(p, COLOR, 7f, onTop = true)
            draw.box(p.subtract(MARKER, MARKER, MARKER), p.add(MARKER, MARKER, MARKER), COLOR, 1.5f, Colors.withAlpha(COLOR, 0.35f), onTop = true)
            pose.origin.takeIf { it.distanceTo(p) > 0.01 }?.let { draw.line(p, it, Colors.withAlpha(COLOR, 0.7f), 1.5f, onTop = true) }
            if (e === primary || e === hovered || selected.size <= LABELS) {
                val root = if (DisplayEditor.kind(e) == "furniture") " · root" else ""
                draw.label(p.add(0.0, 0.18, 0.0), String.format(Locale.ROOT, "%.3f %.3f %.3f", p.x, p.y, p.z) + root, COLOR, size = 0.7f)
            }
        }
        if (on && primary != null) gizmo.draw(draw)
    }

    /** Its line on the display editor's status card, in its mode. */
    fun status(): String? = if (!on) null else
        "Position mode [${DisplayEditor.keyName("display-position")}]: the arrows move the entity, its model stays" + if (furniture(DisplayEditor.selected.lastOrNull())) " (furniture: whole blocks)" else ""
}
