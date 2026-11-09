package games.qore.foreman.client.display

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Display
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.Locale
import java.util.UUID

/**
 * The display editor's panel, beside the world: the selection's exact values (the last selected one's; a value changed
 * here goes to every selected display), its actions, the gizmo's settings, and the displays nearby to pick the hidden
 * ones. Enter applies.
 */
class DisplayPanel : KitScreen("Displays") {
    private val panelW = 280
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20
    private val fieldsY get() = py + 58

    /** Position x y z, offset x y z, rotation yaw pitch roll, scale x y z, facing yaw pitch. */
    private val boxes = mutableListOf<EditBox>()
    /** What each box was filled with: a box whose text differs was changed. */
    private val filled = MutableList(FIELDS) { "" }
    private var filledFor: Pair<Int, UUID?>? = null
    private var message = ""

    override val scrim get() = 0

    override fun build() {
        boxes.clear()
        for (row in ROWS.indices) {
            for (col in 0 until ROWS[row].second) {
                boxes += addRenderableWidget(EditBox(font, px + 76 + col * 66, fieldsY + 12 + row * 20, 62, 16, Component.literal(ROWS[row].first)).also { it.setMaxLength(32) })
            }
        }
        filledFor = null
    }

    /** Fills the boxes with the primary's values when the selection or its values changed. */
    private fun refresh() {
        val primary = DisplayEditor.selectedDisplays().lastOrNull()
        val key = DisplayEditor.revision to primary?.uuid
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
    private fun apply() {
        val changed = HashMap<Int, Double>()
        for (i in boxes.indices) {
            val text = boxes[i].value.trim()
            if (text == filled[i]) continue
            changed[i] = text.replace(',', '.').toDoubleOrNull() ?: run {
                message = "Not a number: $text"
                return
            }
        }
        if (changed.isEmpty()) return
        message = ""
        DisplayEditor.change(facing = changed.keys.any { it >= 12 }) { _, pose -> edited(pose, changed) }
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

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isConfirmation) {
            apply()
            return true
        }
        return super.keyPressed(event)
    }

    override fun draw(ui: Ui) {
        refresh()
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.title("Displays", px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        val displays = DisplayEditor.selectedDisplays()
        val head = when {
            !DisplayEditor.enabled -> "The editor is off: turn it on to pick displays"
            displays.isEmpty() -> "Nothing selected: click one in the world or below"
            displays.size == 1 -> DisplayEditor.label(displays[0]) + " · " + DisplayEditor.kind(displays[0])
            else -> "${displays.size} selected · values of ${DisplayEditor.label(displays.last())}, changes go to all"
        }
        ui.text(ui.fit(head, panelW - 20), px + 10, py + 30, if (displays.isEmpty()) Theme.FAINT else Theme.MUTED)
        fields(ui, displays.isNotEmpty())
        var y = fieldsY + 12 + ROWS.size * 20 + 6
        y = actions(ui, y, displays.isNotEmpty())
        y = gizmo(ui, y + 6)
        nearby(ui, y + 8)
        ui.text(ui.fit(message.ifEmpty { "Enter applies · Esc closes · each change is one undo step" }, panelW - 20), px + 10, py + ph - 14, if (message.isEmpty()) Theme.FAINT else Theme.ERROR)
    }

    private fun fields(ui: Ui, any: Boolean) {
        listOf("x · yaw", "y · pitch", "z · roll").forEachIndexed { i, h -> ui.text(h, px + 78 + i * 66, fieldsY, Theme.FAINT) }
        ROWS.forEachIndexed { row, (label, _) -> ui.text(label, px + 10, fieldsY + 16 + row * 20, if (any) Theme.TEXT else Theme.FAINT) }
        boxes.forEach { it.active = any; it.visible = true }
    }

    private fun actions(ui: Ui, top: Int, any: Boolean): Int {
        val on = any && !DisplayEditor.busy
        val w = (panelW - 20 - 9) / 4
        ui.button(px + 10, top, w * 2 + 3, 16, "Apply (Enter)", Style.PRIMARY, on) { apply() }
        ui.button(px + 10 + (w + 3) * 2, top, w, 16, "No turn", enabled = on, tooltip = "Reset the rotation") { DisplayEditor.resetRotation() }
        ui.button(px + 10 + (w + 3) * 3, top, w, 16, "Scale 1", enabled = on, tooltip = "Reset the scale") { DisplayEditor.resetScale() }
        val y = top + 20
        ui.button(px + 10, y, w, 16, "Duplicate", enabled = on, tooltip = "A copy in place, selected (${DisplayEditor.keyName("display-duplicate")})") { DisplayEditor.duplicate() }
        ui.button(px + 10 + (w + 3), y, w, 16, "Drop", enabled = on, tooltip = "Onto the surface below (${DisplayEditor.keyName("display-drop")})") { DisplayEditor.drop() }
        ui.button(px + 10 + (w + 3) * 2, y, w, 16, "Undo", enabled = !DisplayEditor.busy, tooltip = "The server's last change (${DisplayEditor.keyName("display-undo")})") { DisplayEditor.undo() }
        ui.button(px + 10 + (w + 3) * 3, y, w, 16, "Delete", Style.DANGER, on, tooltip = "Remove them (${DisplayEditor.keyName("display-delete")})") { DisplayEditor.delete() }
        return y + 20
    }

    /** The gizmo's mode, then its axes and pivot. */
    private fun gizmo(ui: Ui, top: Int): Int {
        val modes = TransformGizmo.Mode.entries
        ui.segmented(px + 10, top, modes.map { it.label }, DisplayEditor.mode.ordinal) { DisplayEditor.mode = modes[it] }
        val x = px + 10 + ui.segmented(px + 10, top + 20, listOf("Own axes", "World axes"), if (DisplayEditor.localAxes) 0 else 1) { DisplayEditor.localAxes = it == 0 } + 8
        ui.segmented(x, top + 20, listOf("Origin", "Center"), DisplayEditor.pivot.ordinal) { DisplayEditor.pivot = DisplayEditor.Pivot.entries[it] }
        return top + 38
    }

    /** The displays within reach, nearest first: click selects one, Shift+click adds or removes it. */
    private fun nearby(ui: Ui, top: Int) {
        val player = minecraft.player ?: return
        val list = DisplayEditor.nearby.values.sortedBy { it.distanceToSqr(player) }
        ui.text("Nearby (${list.size})", px + 10, top, Theme.ACCENT)
        val selected = DisplayEditor.selected.toSet()
        ui.scroll("nearby", px + 6, top + 12, panelW - 12, py + ph - 20 - (top + 12)) { y0 ->
            var y = y0
            for (e in list) {
                row(ui, e, y, e.uuid in selected, player.distanceTo(e))
                y += 16
            }
            if (list.isEmpty()) ui.text("No display within ${DisplayEditor.REACH.toInt()} blocks", px + 10, y + 2, Theme.FAINT)
            maxOf(y - y0, 14)
        }
    }

    private fun row(ui: Ui, e: Display, y: Int, selected: Boolean, distance: Float) {
        val x = px + 6
        val w = panelW - 18
        if (selected || ui.hovered(x, y, w, 15)) ui.rect(x, y, w, 15, if (selected) Theme.ACCENT_DARK else Theme.PANEL_HOVER)
        val chip = ui.chip(x + 4, y + 1, DisplayEditor.kind(e))
        val dist = "%.1f".format(Locale.ROOT, distance)
        ui.text(ui.fit(DisplayEditor.label(e), w - chip - 14 - ui.font.width(dist)), x + chip + 8, y + 4, Theme.TEXT)
        ui.text(dist, x + w - 4 - ui.font.width(dist), y + 4, Theme.MUTED)
        ui.hit(x, y, w, 15, null) {
            if (Mc.isKeyDown(InputConstants.KEY_LSHIFT)) DisplayEditor.toggleSelected(e.uuid) else DisplayEditor.selectOnly(e.uuid)
        }
    }

    private companion object {
        const val FIELDS = 14
        val ROWS = listOf("Position" to 3, "Offset" to 3, "Rotation °" to 3, "Scale" to 3, "Facing °" to 2)

        fun num(v: Double) = trim("%.4f".format(Locale.ROOT, v))
        fun deg(radians: Double) = trim("%.2f".format(Locale.ROOT, Math.toDegrees(radians)))
        private fun trim(s: String) = s.trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    }
}
