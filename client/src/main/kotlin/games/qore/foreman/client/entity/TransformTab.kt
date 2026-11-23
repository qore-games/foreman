package games.qore.foreman.client.entity

import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.ui.kit.Slider
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.minecraft.client.gui.components.EditBox
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.Locale
import java.util.UUID

/**
 * A display's exact pose: the last selected display's values (a value changed here goes to every selected display; its
 * rotation, scale and facing slid, applied as soon as they are let go), its actions, and the gizmo's settings.
 */
class TransformTab(panel: EntityPanel) : PanelTab(panel) {
    /** Position x y z, offset x y z, rotation yaw pitch roll, scale x y z, facing yaw pitch. */
    private val boxes = mutableListOf<EditBox>()
    /** The sliders of the boxes that have one, by index: rotation, scale, facing. */
    private val sliders = SLIDES.mapValues { (_, r) -> Slider(r.start, r.endInclusive, if (r.endInclusive > 10) 1.0 else 0.01, ::num) }
    /** What each box was filled with: a box whose text differs was changed. */
    private val filled = MutableList(FIELDS) { "" }
    private var filledFor: Pair<Int, UUID?>? = null

    /** A box's width: three fit beside the labels. */
    private var boxW = 0

    override fun build(x: Int, y: Int, w: Int, h: Int) {
        boxW = (w - NbtFormTab.LABEL - 8) / 3
        for (row in ROWS.indices) {
            for (col in 0 until ROWS[row].second) boxes += panel.box(x + NbtFormTab.LABEL + col * (boxW + 4), y + 12 + row * ROW, boxW, "", 32)
        }
    }

    /** Fills the boxes with the primary display's values when the selection or its values changed. */
    private fun refresh() {
        val primary = EntityEditor.selectedDisplays().lastOrNull()
        val key = EntityEditor.revision to primary?.uuid
        if (key == filledFor) return
        filledFor = key
        val values = primary?.let { values(DisplayPose.of(it)) } ?: List(FIELDS) { "" }
        values.forEachIndexed { i, v ->
            boxes.getOrNull(i)?.value = v
            filled[i] = v
        }
    }

    private fun values(p: DisplayPose): List<String> {
        val euler = p.left.getEulerAnglesYXZ(Vector3d())
        return listOf(
            num(p.pos.x), num(p.pos.y), num(p.pos.z),
            num(p.translation.x), num(p.translation.y), num(p.translation.z),
            deg(euler.y), deg(euler.x), deg(euler.z),
            num(p.scale.x), num(p.scale.y), num(p.scale.z),
            deg(Math.toRadians(p.yaw.toDouble())), deg(Math.toRadians(p.pitch.toDouble())),
        )
    }

    /** Sends the values changed in the boxes to every selected display. */
    override fun apply() {
        val changed = HashMap<Int, Double>()
        for (i in boxes.indices) {
            val text = boxes[i].value.trim()
            if (text == filled[i]) continue
            changed[i] = text.replace(',', '.').toDoubleOrNull() ?: return panel.say("Not a number: $text", true)
        }
        if (changed.isEmpty()) return
        panel.say("")
        EntityEditor.change(facing = changed.keys.any { it >= 12 }) { _, pose -> edited(pose, changed) }
    }

    private fun edited(p: DisplayPose, v: Map<Int, Double>): DisplayPose {
        val pos = Vec3(v[0] ?: p.pos.x, v[1] ?: p.pos.y, v[2] ?: p.pos.z)
        val translation = Vector3d(v[3] ?: p.translation.x, v[4] ?: p.translation.y, v[5] ?: p.translation.z)
        val left = if ((6..8).any { it in v }) {
            val e = p.left.getEulerAnglesYXZ(Vector3d())
            Quaterniond().rotationYXZ(v[6]?.let(Math::toRadians) ?: e.y, v[7]?.let(Math::toRadians) ?: e.x, v[8]?.let(Math::toRadians) ?: e.z)
        } else p.left
        val scale = Vector3d(v[9] ?: p.scale.x, v[10] ?: p.scale.y, v[11] ?: p.scale.z)
        return p.copy(pos = pos, translation = translation, left = left, scale = scale, yaw = v[12]?.toFloat() ?: p.yaw, pitch = v[13]?.toFloat() ?: p.pitch)
    }

    override fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int): Int {
        refresh()
        val any = EntityEditor.selectedDisplays().isNotEmpty()
        listOf("x · yaw", "y · pitch", "z · roll").forEachIndexed { i, head -> ui.text(head, x + NbtFormTab.LABEL + 2 + i * (boxW + 4), y, Theme.FAINT) }
        ROWS.forEachIndexed { row, (label, _) -> ui.text(label, x, y + 17 + row * ROW, if (any) Theme.TEXT else Theme.FAINT) }
        boxes.forEach { it.active = any }
        sliders.forEach { (i, slider) -> boxes.getOrNull(i)?.let { box -> slider.draw(ui, box.x, box.y, box.width, box, true, h = box.height) { if (any) apply() } } }
        val actions = y + 12 + ROWS.size * ROW + 4
        return gizmo(ui, x, actions(ui, x, actions, w, any) + 6) - y
    }

    private fun actions(ui: Ui, x: Int, top: Int, width: Int, any: Boolean): Int {
        val on = any && !EntityEditor.busy
        val w = (width - 9) / 4
        ui.button(x, top, w * 2 + 3, 18, "Apply (Enter)", Style.PRIMARY, on) { apply() }
        ui.button(x + (w + 3) * 2, top, w, 18, "No turn", enabled = on, tooltip = "Reset the rotation") { EntityEditor.resetRotation() }
        ui.button(x + (w + 3) * 3, top, w, 18, "Scale 1", enabled = on, tooltip = "Reset the scale") { EntityEditor.resetScale() }
        val y = top + ROW
        ui.button(x, y, w, 18, "Duplicate", enabled = on, tooltip = "A copy in place, selected (${EntityEditor.keyName("display-duplicate")})") { EntityEditor.duplicate() }
        ui.button(x + (w + 3), y, w, 18, "Drop", enabled = on, tooltip = "Onto the surface below (${EntityEditor.keyName("display-drop")})") { EntityEditor.drop() }
        ui.button(x + (w + 3) * 2, y, w, 18, "Undo", enabled = !EntityEditor.busy, tooltip = "Your last change on the server (${EntityEditor.keyName("display-undo")})") { panel.undo() }
        ui.button(x + (w + 3) * 3, y, w, 18, "Delete", Style.DANGER, on, tooltip = "Remove them (${EntityEditor.keyName("display-delete")})") { EntityEditor.delete() }
        return y + ROW
    }

    /** The gizmo's mode, then its axes and pivot; returns where it ends. */
    private fun gizmo(ui: Ui, x: Int, top: Int): Int {
        val modes = TransformGizmo.Mode.entries
        ui.segmented(x, top, modes.map { it.label }, EntityEditor.mode.ordinal) { EntityEditor.mode = modes[it] }
        val ax = x + ui.segmented(x, top + 20, listOf("Own axes", "World axes"), if (EntityEditor.localAxes) 0 else 1) { EntityEditor.localAxes = it == 0 } + 8
        ui.segmented(ax, top + 20, listOf("Origin", "Center"), EntityEditor.pivot.ordinal) { EntityEditor.pivot = EntityEditor.Pivot.entries[it] }
        return top + 38
    }

    private companion object {
        const val FIELDS = 14
        const val ROW = NbtFormTab.ROW
        val ROWS = listOf("Position" to 3, "Offset" to 3, "Rotation °" to 3, "Scale" to 3, "Facing °" to 2)
        /** The boxes slid, by index, and their ranges: rotation yaw pitch roll, scale x y z, facing yaw pitch. */
        val SLIDES = mapOf(
            6 to -180.0..180.0, 7 to -90.0..90.0, 8 to -180.0..180.0,
            9 to 0.0..4.0, 10 to 0.0..4.0, 11 to 0.0..4.0,
            12 to -180.0..180.0, 13 to -90.0..90.0,
        )

        fun num(v: Double) = trim("%.4f".format(Locale.ROOT, v))
        fun deg(radians: Double) = trim("%.2f".format(Locale.ROOT, Math.toDegrees(radians)))
        private fun trim(s: String) = s.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    }
}
