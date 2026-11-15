package games.qore.foreman.client.regions

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.GamePoint
import games.qore.foreman.client.link.LayoutType
import games.qore.foreman.client.link.Layouts
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Popover
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * The regions tool's panel of a gameplay point, beside the world: the new one's (the tool's draft) or the selected
 * [point]'s. Its type, one the server's plugins declare (picked from the list, which a new point opens first) or free
 * text, and kind (`container:chest`), the build and room it belongs to, where it is exactly and where it faces (yaw, and
 * pitch when it matters; empty: no facing), then the [form][LayoutForm] of its declared type's data; delete removes it.
 * Enter saves. Opened from the [layout check][LayoutCheckPanel] ([back]), it shows the [issues] found on it and goes
 * back there when it closes.
 */
class PointPanel(private val point: GamePoint?, private val back: Screen? = null, private val issues: List<String> = emptyList()) : KitScreen("Point") {
    private val panelW = 270
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20
    private val bodyTop get() = py + 44
    private val bodyBottom get() = py + ph - FOOTER

    private val labels = listOf("Type", "Kind", "Build", "Room")
    private val hints = listOf("Pick one ▾, or your own", "Optional: chest, boss…", "Optional: the build it belongs to", "Optional: its room")
    /** Type, kind, build, room, then x, y, z, yaw and pitch: what the fields hold, kept when the screen is laid out again. */
    private val values = (point ?: RegionsTool.draftPoint).let { p ->
        val at = p?.at
        mutableListOf(
            p?.type.orEmpty(), p?.kind.orEmpty(), p?.build.orEmpty(), p?.room.orEmpty(),
            num(at?.x), num(at?.y), num(at?.z), num(p?.yaw?.toDouble()), num(p?.pitch?.toDouble()),
        )
    }
    /** The boxes of [values], in their order. */
    private val boxes = ArrayList<EditBox>()
    private var form = form(null)
    private var pick: Popover? = null
    /** The type list was offered (a new point asks for its type first). */
    private var asked = point != null
    private var saving = false
    private var deleting = false
    private var message = "" to Theme.FAINT

    /** The [issues] that name no field: shown above the form. */
    private val problems = if (issues.isEmpty()) emptyList() else form.errors(issues)

    override val scrim get() = 0

    override val modal get() = pick != null

    /** Opened from the layout check's list. */
    val fromCheck get() = back is LayoutCheckPanel

    private fun base() = point ?: RegionsTool.draftPoint

    private fun declared(): LayoutType? = Layouts.point(values[0].trim())

    /** The form of the declared type named now, with the point's data when it is of that type ([from]'s values kept). */
    private fun form(from: LayoutForm?): LayoutForm {
        val type = declared()
        val base = base()
        val data = if (base != null && type != null && base.type == type.id) base.data else JsonObject(emptyMap())
        return LayoutForm(listOfNotNull(type), data, base?.world ?: Bridge.world, ::facing, ::openPick, from)
    }

    /** A new form when the type named is another declared one (or the server's declarations changed). */
    private fun retype() {
        if (form.types == listOfNotNull(declared())) return
        form.boxes.forEach(::removeWidget)
        form = form(form)
        form.build(font) { addRenderableWidget(it) }
    }

    /** Where it faces for a yaw field: the Facing box, else the point's, else the player's. */
    private fun facing(): Float? = values[7].trim().replace(',', '.').toFloatOrNull() ?: base()?.yaw ?: Minecraft.getInstance().player?.yRot

    override fun build() {
        boxes.clear()
        labels.indices.forEach { i -> boxes += field(if (i == 0) panelW - 20 - LABEL - 22 else panelW - 20 - LABEL, i, hints[i]).also { it.setMaxLength(64) } }
        listOf("x", "y", "z").forEachIndexed { i, hint -> boxes += field(third(), 4 + i, hint) }
        listOf("yaw: none", "pitch: 0").forEachIndexed { i, hint -> boxes += field(third(), 7 + i, hint) }
        form.build(font) { addRenderableWidget(it) }
        if (point == null) setInitialFocus(boxes[0])
    }

    private fun third() = (panelW - 20 - LABEL - 8) / 3

    private fun field(w: Int, index: Int, hint: String): EditBox =
        addRenderableWidget(EditBox(font, 0, 0, w, 16, Component.literal(hint))).also { box ->
            box.setHint(Component.literal(hint))
            box.value = values[index]
            box.setResponder { values[index] = it }
        }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (pick != null) {
            if (event.isEscape) pick = null
            return true
        }
        if (event.isConfirmation) {
            save()
            return true
        }
        return super.keyPressed(event)
    }

    override fun onClose() {
        if (back != null) minecraft.gui.setScreen(back) else super.onClose()
    }

    private fun openPick(list: Popover) {
        resetScroll("pick")
        pick = list
    }

    /** The declared point types, by the plugin declaring them, then free text. */
    private fun openTypes() {
        val current = values[0].trim()
        val declared = Layouts.points().groupBy { it.owner }.toSortedMap().flatMap { (owner, list) ->
            listOf(PickList.Option(null, owner.ifEmpty { "Declared" })) +
                list.sortedBy { it.name }.map { t -> PickList.Option(t.id, t.name, t.id, t.argb, t.description.ifEmpty { null }, t.id == current) }
        }.ifEmpty { listOf(PickList.Option(null, "No plugin here declares point types")) }
        val free = PickList.Option(FREE, "Free text…", "your own name", tooltip = "A type no plugin declares: type its name")
        openPick(PickList(px + 10, bodyTop + 18, panelW - 20, declared + free) { value ->
            val box = boxes.getOrNull(0) ?: return@PickList
            if (value == FREE) {
                if (Layouts.point(box.value.trim()) != null) box.value = ""
                setFocused(box)
            } else box.value = value
        })
    }

    private fun save() {
        if (saving) return
        val base = base() ?: return say("No point: look at a block and add one", Theme.WARN)
        val (type, kind, build, room) = values.map { it.trim() }
        val numbers = values.drop(4).map { it.trim().replace(',', '.') }
        val xyz = numbers.take(3).map { it.toDoubleOrNull() }
        if (xyz.any { it == null }) return say("x, y and z are numbers", Theme.WARN)
        val yaw = numbers[3].ifEmpty { null }?.let { it.toFloatOrNull() ?: return say("The yaw is a number of degrees, or empty", Theme.WARN) }
        val pitch = numbers[4].ifEmpty { null }?.let { it.toFloatOrNull()?.coerceIn(-90f, 90f) ?: return say("The pitch is a number of degrees, or empty", Theme.WARN) }
        // A declared type's data is the form; a free type's stays as it is.
        val data = if (declared() != null) form.data() ?: return say("A value isn't right: see the fields in red", Theme.WARN) else base.data
        val changed = base.movedTo(Vec3(xyz[0]!!, xyz[1]!!, xyz[2]!!)).copy(type = type, kind = kind, build = build, room = room, yaw = yaw, pitch = pitch, data = data)
        saving = true
        say("Saving…", Theme.MUTED)
        RegionsTool.savePoint(changed) { result ->
            saving = false
            result.onSuccess { onClose() }.onFailure { say(form.refused(it.message ?: "Couldn't save"), Theme.ERROR) }
        }
    }

    private fun say(text: String, color: Int) {
        message = text to color
    }

    /** [box] at [x], [y] of the scrolling body, shown only while all of it is in view. */
    private fun place(box: EditBox?, x: Int, y: Int) {
        box ?: return
        box.setPosition(x, y)
        box.visible = y >= bodyTop && y + 16 <= bodyBottom
    }

    override fun draw(ui: Ui) {
        retype()
        if (!asked && width > 0) {
            asked = true
            if (values[0].isBlank() && Layouts.points().isNotEmpty()) openTypes()
        }
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.scroll("body", px + 1, bodyTop, panelW - 2, bodyBottom - bodyTop) { top -> body(ui, top) }
        // The header and the footer take the clicks over what scrolled under them.
        ui.hit(px, py, panelW, bodyTop - py) {}
        ui.hit(px, bodyBottom, panelW, py + ph - bodyBottom) {}
        ui.title(if (point == null) "New point" else "Point", px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        ui.button(px + panelW - 80, py + 8, 52, 16, "Check", Style.GHOST, Bridge.connected,
            tooltip = "Check the layout: every point and area of the world against the types the server's plugins declare (K)") { LayoutCheck.open() }
        val base = base()
        ui.text(ui.fit(base?.let { "${RegionsTool.describePlace(it)} · ${it.world}" } ?: "No point", panelW - 20), px + 10, py + 30, Theme.MUTED)
        footer(ui, base != null)
    }

    private fun body(ui: Ui, top: Int): Int {
        val x = px + 10
        val w = panelW - 20
        var y = top + 2
        ui.text("Type", x, y + 4, Theme.TEXT)
        place(boxes.getOrNull(0), x + LABEL, y)
        ui.button(x + w - 18, y, 18, 16, "▾", Style.NORMAL, tooltip = "The point types the server's plugins declare") { openTypes() }
        y += 18
        val type = declared()
        val typed = values[0].trim()
        when {
            type != null -> {
                type.argb?.let { ui.swatch(x + LABEL, y + 1, 8, it) }
                ui.text(ui.fit("${type.name} · ${type.owner}", w - LABEL - 12), x + LABEL + 12, y + 1, Theme.MUTED)
                y += 12
            }
            typed.contains(':') -> {
                ui.text(ui.fit("No plugin here declares $typed", w - LABEL), x + LABEL, y + 1, Theme.WARN)
                y += 12
            }
            typed.isNotEmpty() -> {
                ui.text("Free text: no form", x + LABEL, y + 1, Theme.FAINT)
                y += 12
            }
        }
        y += 4
        for (i in 1 until labels.size) {
            ui.text(labels[i], x, y + 4, Theme.TEXT)
            place(boxes.getOrNull(i), x + LABEL, y)
            y += 22
        }
        ui.text("At", x, y + 4, Theme.TEXT)
        (0 until 3).forEach { i -> place(boxes.getOrNull(4 + i), x + LABEL + i * (third() + 4), y) }
        y += 22
        ui.text("Facing", x, y + 4, Theme.TEXT)
        (0 until 2).forEach { i -> place(boxes.getOrNull(7 + i), x + LABEL + i * (third() + 4), y) }
        y += 24
        for (p in problems) y += ui.paragraph(p, x, y, w, Theme.ERROR) + 2
        if (type != null) y += form.draw(ui, x, y, w, bodyTop, bodyBottom) + 4
        else if (base()?.data?.isNotEmpty() == true) {
            y += ui.paragraph("Its data is kept as it is: ${base()!!.data}", x, y, w, Theme.FAINT) + 6
        }
        y += ui.paragraph("Gameplay plugins read points by type and kind through Foreman's API. The arrows move it in the world, the ring turns where it faces.",
            x, y, w, Theme.FAINT)
        return y - top + 6
    }

    private fun footer(ui: Ui, ready: Boolean) {
        var y = bodyBottom + 6
        val w = (panelW - 20 - 4) / 2
        ui.button(px + 10, y, w, 18, if (point == null) "Add it (Enter)" else "Save (Enter)", Style.PRIMARY, ready && !saving && !RegionsTool.busy) { save() }
        ui.button(px + 14 + w, y, w, 18, if (point == null) "Cancel" else if (back != null) "Back" else "Close", Style.GHOST) { onClose() }
        y += 22
        if (point != null) ui.button(px + 10, y, panelW - 20, 16, if (deleting) "Sure? Click again" else "Delete the point", Style.DANGER, !RegionsTool.busy) {
            if (!deleting) deleting = true
            else RegionsTool.delete(confirmed = true) { result -> result.onSuccess { onClose() }.onFailure { say(it.message.orEmpty(), Theme.ERROR) } }
        }
        ui.text(ui.fit(message.first.ifEmpty { "Enter saves · Esc closes" }, panelW - 20), px + 10, py + ph - 14, if (message.first.isEmpty()) Theme.FAINT else message.second)
    }

    override fun drawOverlay(ui: Ui) {
        pick?.draw(ui, width, height) { pick = null }
    }

    private companion object {
        const val LABEL = 44
        const val FOOTER = 62
        const val FREE = "\u0000free"

        fun num(v: Double?) = v?.let { String.format(Locale.ROOT, "%.4f", it).trimEnd('0').trimEnd('.').let { s -> if (s == "-0") "0" else s } }.orEmpty()
    }
}
