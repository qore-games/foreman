package games.qore.foreman.client.regions

import games.qore.foreman.client.editor.EditorMode
import games.qore.foreman.client.link.Area
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.LayoutType
import games.qore.foreman.client.link.Layouts
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

/**
 * The regions tool's panel of an area, beside the world: a new one's (its box the player's selection) or the selected
 * [ref]'s.
 * Its name (a new name makes it again under that name), note, tags, zone rules (its theme) and claim kind (the space it
 * reserves); the types the server's plugins declare that it is (a tag each, or its claim kind), with the
 * [form][LayoutForm] of their data; lock keeps others' Foreman edits out of it while the player builds there; delete
 * takes the area away, what is in the world stays. Enter saves. Opened from the [layout check][LayoutCheckPanel]
 * ([back]), it shows the [issues] found on it and goes back there when it closes.
 */
class AreaPanel(private val ref: AreaRef?, private val back: Screen? = null, private val issues: List<String> = emptyList()) : KitScreen("Area") {
    private val panelW = 280
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20
    private val bodyTop get() = py + 44
    private val bodyBottom get() = py + ph - FOOTER

    private val labels = listOf("Name", "Note", "Tags", "Zone", "Claim")
    private val hints = listOf(
        "Its name, for builders and plugins", "What it is", "Tags, by spaces: spawn safe", "Zone rules: palette, era, what goes here",
        "Not a claim · build, road, yard, terrain…",
    )
    /** The tags that make it of a declared type, shown as types rather than in the Tags box. */
    private val typeTags = ref?.area()?.tags.orEmpty().filter { Layouts.area(it) != null }.toMutableList()
    /** What the fields hold, kept when the screen is laid out again. */
    private val values = initial()
    private val boxes = ArrayList<EditBox>()
    private var form = form(null)
    private var pick: PickList? = null
    private var saving = false
    private var deleting = false
    private var message = "" to Theme.FAINT

    /** The [issues] that name no field: shown above the form. */
    private val problems = if (issues.isEmpty()) emptyList() else form.errors(issues)

    override val scrim get() = 0

    override val modal get() = pick != null

    /** Opened from the layout check's list. */
    val fromCheck get() = back is LayoutCheckPanel

    private fun initial(): MutableList<String> {
        val a = ref?.area() ?: return MutableList(5) { "" }
        return mutableListOf(a.name, a.note, (a.tags - typeTags.toSet()).joinToString(" "), a.theme, a.kind.orEmpty())
    }

    /** Its tags: the Tags box's and its types'. */
    private fun tags() = (values[2].split(' ', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() } + typeTags).distinct()

    /** The declared types it is as edited: its claim kind's, then its tags'. */
    private fun types(): List<LayoutType> = (listOf(values[4].trim().lowercase()) + tags()).mapNotNull(Layouts::area).distinct()

    private fun form(from: LayoutForm?): LayoutForm {
        val area = ref?.area()
        return LayoutForm(types(), area?.data ?: JsonObject(emptyMap()), area?.world ?: Bridge.world, ::facing, ::openPick, from)
    }

    /** A new form when its types changed (a type added or taken away, the server's declarations). */
    private fun retype() {
        if (form.types == types()) return
        form.boxes.forEach(::removeWidget)
        form = form(form)
        form.build(font) { addRenderableWidget(it) }
    }

    /** An area has no facing: a yaw field takes the player's. */
    private fun facing(): Float? = Minecraft.getInstance().player?.yRot

    override fun build() {
        boxes.clear()
        labels.indices.forEach { i ->
            val box = addRenderableWidget(EditBox(font, 0, 0, panelW - 20 - LABEL, 16, Component.literal(hints[i])))
            box.setMaxLength(if (i == 1 || i == 3) 500 else 64)
            box.setHint(Component.literal(hints[i]))
            box.value = values[i]
            box.setResponder { values[i] = it }
            boxes += box
            if (i == 0 && ref == null) setInitialFocus(box)
        }
        form.build(font) { addRenderableWidget(it) }
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

    private fun openPick(list: PickList) {
        resetScroll("pick")
        pick = list
    }

    /** The declared area types it isn't yet, by the plugin declaring them: one picked becomes a tag. */
    private fun openTypes(x: Int, y: Int) {
        val have = types().map { it.id }.toSet()
        val options = Layouts.areas().filter { it.id !in have }.groupBy { it.owner }.toSortedMap().flatMap { (owner, list) ->
            listOf(PickList.Option(null, owner.ifEmpty { "Declared" })) +
                list.sortedBy { it.name }.map { t -> PickList.Option(t.id, t.name, t.id, t.argb, t.description.ifEmpty { null }) }
        }.ifEmpty { listOf(PickList.Option(null, if (Layouts.areas().isEmpty()) "No plugin here declares area types" else "It is of every declared type")) }
        openPick(PickList(x, y, panelW - 20, options) { id -> if (id !in typeTags) typeTags += id })
    }

    /** Takes [type] away: its tag (from the types or the Tags box). */
    private fun untype(type: LayoutType) {
        typeTags.remove(type.id)
        val rest = values[2].split(' ', ',').filter { it.isNotBlank() && it.trim().lowercase() != type.id }.joinToString(" ")
        if (rest != values[2].trim()) boxes.getOrNull(2)?.value = rest
    }

    private fun save() {
        if (saving) return
        val area = ref?.area()
        val shape = if (ref == null) listOfNotNull(SelectionTool.current()) else area?.blockBoxes.orEmpty()
        val types = types()
        val data = when {
            types.isNotEmpty() -> form.data() ?: return say("A value isn't right: see the fields in red", Theme.WARN)
            // Its last declared type taken away: their data goes with it.
            area != null && Layouts.of(area).isNotEmpty() -> JsonObject(emptyMap())
            else -> area?.data ?: JsonObject(emptyMap())
        }
        val target = AreaSpec(
            area?.world ?: Bridge.world, values[0].trim(), shape, values[4].trim().lowercase().ifEmpty { null }, values[1].trim(), values[3].trim(), tags(), data,
        )
        saving = true
        say("Saving…", Theme.MUTED)
        RegionsTool.saveArea(area?.let(AreaSpec::of), target) { result ->
            saving = false
            result.onSuccess {
                if (ref == null || ref.name != target.name) onClose() else say("Saved", Theme.OK)
            }.onFailure { say(form.refused(it.message ?: "Couldn't save"), Theme.ERROR) }
        }
    }

    /** Who holds the area's lock, and Lock or Unlock: while it is locked, only its holder's Foreman edits go in. */
    private fun lockRow(ui: Ui, area: Area, y: Int) {
        val lock = area.lock
        val (text, color) = when {
            lock == null -> "Not locked: anyone's edits go in" to Theme.FAINT
            lock.mine -> "⚿ Locked to you: others' edits are refused" to Theme.OK
            else -> "⚿ Locked by ${lock.name}: only their edits go in" to Theme.WARN
        }
        ui.text(ui.fit(text, panelW - 20 - 74), px + 10, y + 4, color)
        val tooltip = when {
            lock == null -> "Lock it to you: while you build in it, other players' Foreman edits inside it are refused (and, on most servers, their own block changes)"
            lock.mine -> "Unlock it: everyone's edits go in again"
            else -> "Only ${lock.name} unlocks it (an admin can: /foreman unlock ${area.name} force)"
        }
        ui.button(px + panelW - 80, y, 70, 16, if (lock == null) "Lock" else "Unlock", Style.NORMAL, !RegionsTool.busy && (lock == null || lock.mine), tooltip) {
            RegionsTool.lock(area, lock == null) { result ->
                result.onSuccess { say(if (lock == null) "Locked to you" else "Unlocked", Theme.OK) }.onFailure { say(it.message.orEmpty(), Theme.ERROR) }
            }
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
        ui.card(px, py, panelW, ph, Theme.RAIL)
        val area = ref?.area()
        val box = if (ref == null) SelectionTool.current() else RegionsTool.selectedBox().takeIf { RegionsTool.selectedArea == ref } ?: area?.blockBoxes?.getOrNull(ref.box)
        ui.scroll("body", px + 1, bodyTop, panelW - 2, bodyBottom - bodyTop) { top -> body(ui, top) }
        // The header and the footer take the clicks over what scrolled under them.
        ui.hit(px, py, panelW, bodyTop - py) {}
        ui.hit(px, bodyBottom, panelW, py + ph - bodyBottom) {}
        ui.title(if (ref == null) "New area" else "Area", px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        val where = box?.let { "${it.sizeX}×${it.sizeY}×${it.sizeZ} at ${it.minX} ${it.minY} ${it.minZ}" }
        val head = when {
            ref == null && box == null -> "No selection: make one with the selection tool"
            ref == null -> "${SelectionTool.currentLabel()} · ${Bridge.world}"
            area == null -> "${ref.name} isn't in the server's areas (any more)"
            else -> "${area.world} · $where" + if (area.boxes.size > 1) " · ${area.boxes.size} boxes" else ""
        }
        ui.text(ui.fit(head, panelW - 20), px + 10, py + 30, if (ref == null && box == null) Theme.WARN else Theme.MUTED)
        if (ref == null && box == null) ui.button(px + panelW - 112, py + 8, 84, 16, "Selection tool", Style.PRIMARY,
            tooltip = "Make the box with the selection tool (${EditorMode.caption(SelectionTool)}), then come back (${EditorMode.caption(RegionsTool)}) and press M") {
            onClose()
            EditorMode.open(SelectionTool)
        }
        else ui.button(px + panelW - 80, py + 8, 52, 16, "Check", Style.GHOST, Bridge.connected,
            tooltip = "Check the layout: every point and area of the world against the types the server's plugins declare (K)") { LayoutCheck.open() }
        footer(ui, area, !saving && !RegionsTool.busy && (ref == null && box != null || area != null))
    }

    private fun body(ui: Ui, top: Int): Int {
        val x = px + 10
        val w = panelW - 20
        var y = top + 2
        labels.forEachIndexed { i, label ->
            ui.text(label, x, y + 4, Theme.TEXT)
            place(boxes.getOrNull(i), x + LABEL, y)
            y += 22
        }
        y += ui.paragraph("A zone gives builders its own rules. A claim reserves its space: other builds may not go into it. Areas are whole blocks.", x, y, w, Theme.FAINT) + 8
        for (p in problems) y += ui.paragraph(p, x, y, w, Theme.ERROR) + 2
        y = typeRow(ui, x, y, w)
        y += form.draw(ui, x, y, w, bodyTop, bodyBottom)
        return y - top + 6
    }

    /** Its declared types as chips (× takes one away; a claim kind's stays), and the button adding one. */
    private fun typeRow(ui: Ui, x: Int, y: Int, w: Int): Int {
        ui.text("Types", x, y + 2, Theme.TEXT)
        ui.hit(x, y, LABEL - 4, 12, "The types the server's plugins declare that it is: a tag each (or its claim kind), their fields below")
        val kind = values[4].trim().lowercase()
        var cx = x + LABEL
        var cy = y
        fun wrap(width: Int) {
            if (cx + width > x + w && cx > x + LABEL) {
                cx = x + LABEL
                cy += 15
            }
        }
        for (type in types()) {
            val byKind = type.id == kind && type.id !in tags()
            val label = type.name + if (byKind) " (claim)" else " ×"
            val cw = ui.font.width(label) + 8 + if (type.argb != null) 10 else 0
            wrap(cw)
            ui.card(cx, cy, cw, 12, Theme.FIELD, if (ui.hovered(cx, cy, cw, 12) && !byKind) Theme.ERROR else Theme.BORDER)
            type.argb?.let { ui.swatch(cx + 3, cy + 2, 8, it) }
            ui.text(label, cx + 4 + if (type.argb != null) 10 else 0, cy + 2, Theme.TEXT)
            ui.hit(cx, cy, cw, 12, if (byKind) "${type.id}: its claim kind makes it one" else "${type.id}: click to take it away (its tag)", if (byKind) null else ({ untype(type) }))
            cx += cw + 4
        }
        wrap(56)
        ui.button(cx, cy - 2, 56, 16, "+ Type", Style.NORMAL, tooltip = "Make it of a type a plugin declares: adds its tag") { openTypes(x, cy + 16) }
        return cy + 20
    }

    private fun footer(ui: Ui, area: Area?, ready: Boolean) {
        var y = bodyBottom + 6
        val w = (panelW - 20 - 4) / 2
        ui.button(px + 10, y, w, 18, if (ref == null) "Make it (Enter)" else "Save (Enter)", Style.PRIMARY, ready) { save() }
        ui.button(px + 14 + w, y, w, 18, if (ref == null) "Cancel" else if (back != null) "Back" else "Close", Style.GHOST) { onClose() }
        y += 22
        if (area != null) {
            lockRow(ui, area, y)
            y += 20
        }
        if (ref != null) ui.button(px + 10, y, panelW - 20, 16, if (deleting) "Sure? Click again" else "Delete the area", Style.DANGER, area != null && !RegionsTool.busy,
            tooltip = "Deletes the area only: what is in the world stays") {
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
        const val FOOTER = 84
    }
}
