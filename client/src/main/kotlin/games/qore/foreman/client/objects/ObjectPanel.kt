package games.qore.foreman.client.objects

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.GameObject
import games.qore.foreman.client.link.ObjectKind
import games.qore.foreman.client.panels.Inspector
import games.qore.foreman.client.panels.PanelRows
import games.qore.foreman.client.panels.PanelState
import games.qore.foreman.client.panels.Panels
import games.qore.foreman.client.panels.Targets
import games.qore.foreman.client.regions.FieldInput
import games.qore.foreman.client.regions.LayoutForm
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Popover
import games.qore.foreman.client.ui.kit.SidePanel
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3

/**
 * The objects tool's panel of an object, beside the world: the new one's (the tool's draft) or the selected [obj]'s, one
 * scrolling body as wide as [SidePanel] says. First its **Location**: its anchors (exact positions, typed) and the
 * facing fields (yaws without a group, the `Location` group); then the [form][LayoutForm] of its kind's other fields,
 * empty ones reading their defaults (a target field picked in the world), merged with the panel a plugin of the server
 * shows for the object ([Panels], live while it is open, as its [rows][PanelRows]): a group of fields shows at the top
 * of the panel's section of the same title (its anchors in a `Location` section, when the panel has one), the groups no
 * section has after them; a panel in tabs shows its status line and tabs on top, what no section takes on its first tab. Fields the builder didn't touch follow
 * the object as the server sends it; fields whose choices depend on others ask the server for them as those change.
 * Enter places it (the whole form) or saves the fields changed; the panel's own fields are sent as they are left, its
 * buttons run at once. The server's refusals show on the fields they name.
 */
class ObjectPanel(private val obj: GameObject?) : KitScreen("Object"), PanelRows.Host {
    private val panelW get() = SidePanel.width(width)
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20
    private val bodyTop get() = py + 44 + rows.headerHeight(shown()?.panel)
    private val bodyBottom get() = py + ph - FOOTER

    private val kind: ObjectKind? = obj?.type ?: ObjectsTool.draft?.kind
    /** x, y, z of each anchor, as typed: kept when the screen is laid out again. */
    private val values = (obj?.anchorsAt ?: ObjectsTool.draft?.anchors.orEmpty()).flatMap { listOf(it.x, it.y, it.z) }.map(LayoutForm::num).toMutableList()
    private val boxes = ArrayList<EditBox>()
    private val form = LayoutForm(
        listOfNotNull(kind?.layoutType), obj?.data ?: ObjectsTool.draft?.data ?: JsonObject(emptyMap()), obj?.world ?: Bridge.world, ::facing, ::open,
        pickTarget = ::pickTarget, describe = Targets::named,
    )
    /** The fields shown with its position: yaws without a group, and the [LOCATION] group. */
    private val located = form.fields.filter { it.group == null && it.type == "yaw" || it.group.equals(LOCATION, ignoreCase = true) }.map { it.name }.toSet()
    /** The fields others' choices depend on, with their values when the server was last asked: asked again as they change. */
    private val depended = kind?.fields?.flatMap { it.dependsOn }?.distinct().orEmpty()
    private var asked: Map<String, JsonElement?>? = null
    private var asking = false
    /** The object's data as the server sent it last, which the untouched fields follow. */
    private var followed: JsonObject? = obj?.data
    /** The object as a panel target, when the server offers panels: its plugins' panel shows under the form. */
    private val target: JsonObject? = obj?.takeIf { Panels.supported }?.let { Targets.of(it).target }
    private val rows = PanelRows(this)
    private var pop: Popover? = null
    private var saving = false
    private var deleting = false
    private var message = "" to Theme.FAINT
    /** Closed while the builder picks a target in the world: the panel stays open on the server. */
    private var away = false
    /** Closed for another thing's panel, which the server shows now. */
    private var leaving = false

    init {
        target?.let { Panels.open(it, ObjectsTool.name(obj!!)) }
    }

    override val scrim get() = 0

    override val modal get() = pop != null

    override val popoverOpen get() = pop != null

    private fun facing(): Float? = Minecraft.getInstance().player?.yRot

    override fun open(popover: Popover) {
        resetScroll("pick")
        pop = popover
    }

    override fun widgetFont(): Font = font

    override fun add(box: EditBox): EditBox = addRenderableWidget(box)

    override fun remove(box: EditBox) = removeWidget(box)

    override fun focused(box: EditBox) = box.isFocused

    override fun pickTarget(what: String, done: (JsonObject?) -> Unit) {
        away = true
        Inspector.pickTarget(what) { picked ->
            away = false
            Screens.open(this)
            done(picked)
        }
        minecraft.gui.setScreen(null)
    }

    override fun openTarget(target: JsonObject, name: String) {
        leaving = true
        Inspector.open(target, name)
    }

    override fun say(text: String, color: Int) {
        message = text to color
    }

    override fun build() {
        boxes.clear()
        values.indices.forEach { i -> boxes += field(third(), i, listOf("x", "y", "z")[i % 3]) }
        form.build(font) { addRenderableWidget(it) }
        rows.build()
    }

    private fun labelW() = FieldInput.labelWidth(panelW - 20)

    private fun third() = (panelW - 20 - labelW() - 8) / 3

    private fun field(w: Int, index: Int, hint: String): EditBox =
        addRenderableWidget(EditBox(font, 0, 0, w, 16, Component.literal(hint))).also { box ->
            box.setHint(Component.literal(hint))
            box.setMaxLength(32)
            box.value = values[index]
            box.setResponder { values[index] = it }
        }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (pop != null) {
            if (event.isEscape) pop = null
            return true
        }
        if (event.isConfirmation) {
            // In a field of the plugin's panel, Enter sends that field; elsewhere it saves the form.
            if (!rows.enter()) save()
            return true
        }
        return super.keyPressed(event)
    }

    /** A click beside the box being typed in leaves it (a field of the plugin's panel is sent). */
    override fun mouseClicked(real: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val box = focused as? EditBox
        if (box != null && !box.isMouseOver(real.x() / zoom, real.y() / zoom)) focused = null
        return super.mouseClicked(real, doubleClick)
    }

    override fun removed() {
        if (away) return
        rows.flush()
        if (target != null && !leaving) Panels.close()
    }

    /** Places the new object with the whole form, or saves the selected one's anchors and the fields the builder changed. */
    private fun save() {
        if (saving) return
        if (kind == null) return say("No plugin here declares this kind any more", Theme.WARN)
        val numbers = values.map { it.trim().replace(',', '.').toDoubleOrNull() }
        if (numbers.any { it == null }) return say("The anchors' x, y and z are numbers", Theme.WARN)
        val anchors = numbers.chunked(3).map { (x, y, z) -> Vec3(x!!, y!!, z!!) }
        val data = (if (obj == null) form.data() else form.changes()) ?: return say("A value isn't right: see the fields in red", Theme.WARN)
        saving = true
        say("Saving…", Theme.MUTED)
        ObjectsTool.save(anchors, data) { result ->
            saving = false
            result.onSuccess { onClose() }.onFailure { say(form.refused(refusal(it.message ?: "Couldn't save")), Theme.ERROR) }
        }
    }

    /** The server's refusal without the line pointing the AI to `object_kinds`. */
    private fun refusal(message: String) = message.lines().filter { !it.startsWith("object_kinds") }.joinToString("\n")

    /** [box] at [x], [y] of the scrolling body, shown only while all of it is in view. */
    private fun place(box: EditBox?, x: Int, y: Int) {
        box ?: return
        box.setPosition(x, y)
        box.visible = y >= bodyTop && y + 16 <= bodyBottom
    }

    /** The plugins' panel of the object, once asked for: null when the server offers none, or shows another now. */
    private fun shown(): PanelState? = target?.let { t -> Panels.shown?.takeIf { Targets.same(it.aimed, t) } }

    /** The fields the builder hasn't touched take the object's values as the server sends them. */
    private fun follow() {
        val now = obj?.let { o -> Bridge.objects.firstOrNull { it.id == o.id && it.kind == o.kind } }?.data ?: return
        if (now == followed) return
        followed = now
        form.follow(now)
    }

    /**
     * Asks the server for the choices of the fields that depend on others when those changed (once at a time); the first
     * answer keeps what the fields hold, later ones put back to its default a field whose value isn't offered any more.
     */
    private fun dependencies() {
        val kind = kind ?: return
        if (depended.isEmpty() || asking) return
        val now = depended.associateWith { form.peek(it) }
        if (now == asked) return
        val first = asked == null
        asked = now
        asking = true
        ObjectCalls.choices(kind.id, obj?.world ?: Bridge.world, obj?.id, form.peeked()) { result ->
            asking = false
            result.onSuccess { form.choices(it, reset = !first) }
        }
    }

    override fun draw(ui: Ui) {
        val state = shown()
        rows.tabKey = kind?.id ?: obj?.kind
        rows.sync(state)
        follow()
        dependencies()
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.scroll("body", px + 1, bodyTop, panelW - 2, bodyBottom - bodyTop) { top -> body(ui, top, state) }
        val name = kind?.name ?: obj?.kind ?: "Object"
        val panelTitle = state?.panel?.title?.ifEmpty { null }
        val title = if (obj == null) "New ${name.lowercase()}" else panelTitle ?: name
        val sub = listOfNotNull(name.takeIf { it != title && obj != null }, obj?.world ?: Bridge.world, kind?.owner?.ifEmpty { null }).joinToString(" · ")
        val about = listOfNotNull(title, sub, obj?.id?.let { "Id $it" }, state?.let { it.providerLabel ?: it.provider }?.let { "Panel: $it" }).joinToString("\n")
        // The header and the footer cover what scrolled under them; the header's whole text shows on hover.
        ui.hit(px, py, panelW - 26, bodyTop - py, about) {}
        ui.hit(px, bodyBottom, panelW, py + ph - bodyBottom) {}
        if (scrolled("body")) ui.rect(px + 10, bodyTop - 1, panelW - 20, 1, Theme.BORDER)
        ui.title(ui.fit(title, ((panelW - 44) / 1.5).toInt()), px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        ui.text(ui.fit(sub, panelW - 20), px + 10, py + 30, Theme.MUTED)
        rows.header(ui, state?.panel?.takeIf { state.answered && state.target != null }, px + 10, py + 42, panelW - 20)
        footer(ui, state)
    }

    private fun body(ui: Ui, top: Int, state: PanelState?): Int {
        val x = px + 10
        val w = panelW - 20
        var y = top + 2
        val panel = state?.panel?.takeIf { state.answered && state.target != null }
        // With tabs, the form's parts go with the sections of their titles (the anchors with `Location`), the rest on the
        // first tab; boxes not drawn this frame are hidden.
        val tab = rows.tab(panel)
        val first = tab == null || tab == panel?.tabs?.firstOrNull()
        val titled = panel?.sections.orEmpty().map { it.title.lowercase() }.toSet()
        (boxes + form.boxes).forEach { it.visible = false }
        // What the plugin says of it first: setup or live, its error.
        if (panel != null && panel.subtitle.isNotEmpty()) y += ui.paragraph(panel.subtitle, x, y, w, Theme.MUTED) + 4
        state?.error?.let { y += ui.paragraph(it, x, y, w, Theme.ERROR) + 4 }
        if (state != null && !state.answered) y += ui.paragraph("Asking its plugins for their panel…", x, y, w, Theme.FAINT) + 4
        val locatedHere = LOCATION.lowercase() in titled
        if (first && !locatedHere) y += location(ui, x, y, w)
        val own = form.fields.filter { it.group == null && it.name !in located }
        if (first && kind != null && own.isNotEmpty()) {
            y += rule(ui, x, y, w)
            y += LayoutForm.heading(ui, kind.name, x, y, w)
            if (kind.description.isNotEmpty()) ui.hit(x, y - 13, minOf(w, ui.font.width(kind.name)), 10, kind.description)
            for (f in own) y += form.drawField(ui, f.name, x, y, w, bodyTop, bodyBottom)
        }
        val groups = form.fields.filter { it.group != null && it.name !in located }.groupBy { it.group!! }
        val drawn = HashSet<String>()
        if (panel != null) {
            y += 2
            y += rows.draw(ui, panel, x, y, w, bodyTop, bodyBottom) { section, sy ->
                var gy = sy
                if (locatedHere && section.title.equals(LOCATION, ignoreCase = true)) gy += location(ui, x, gy, w, heading = false)
                val group = groups.keys.firstOrNull { it.equals(section.title, ignoreCase = true) && it !in drawn } ?: return@draw gy - sy
                drawn += group
                for (f in groups.getValue(group)) gy += form.drawField(ui, f.name, x, gy, w, bodyTop, bodyBottom)
                gy - sy
            }
        }
        for ((group, fields) in groups) {
            if (group in drawn || !first || group.lowercase() in titled) continue
            y += rule(ui, x, y, w)
            y += LayoutForm.heading(ui, group, x, y, w)
            for (f in fields) y += form.drawField(ui, f.name, x, y, w, bodyTop, bodyBottom)
        }
        y += 4
        if (first) y += ui.paragraph(
            "Its plugin draws it and keeps it: Foreman only asks it. The gizmo's arrows move the active anchor in the world; " +
                "V picks the next one, P puts it where you aim.", x, y, w, Theme.FAINT,
        )
        return y - top + 6
    }

    /** Where it is: its anchors, typed, then its facing fields; under its own [heading] unless a section of the panel gives it one. */
    private fun location(ui: Ui, x: Int, y: Int, w: Int, heading: Boolean = true): Int {
        var ry = y + if (heading) LayoutForm.heading(ui, LOCATION, x, y, w) else 0
        val labelW = labelW()
        for (i in 0 until values.size / 3) {
            val label = if (values.size == 3) "Position" else kind?.anchorName(i) ?: "Anchor ${i + 1}"
            ui.text(ui.fit(label, labelW - 10), x, ry + 4, Theme.TEXT)
            (0 until 3).forEach { j -> place(boxes.getOrNull(i * 3 + j), x + labelW + j * (third() + 4), ry) }
            ry += 20
        }
        for (name in located) ry += form.drawField(ui, name, x, ry, w, bodyTop, bodyBottom)
        return ry - y
    }

    /** The line between two sections. */
    private fun rule(ui: Ui, x: Int, y: Int, w: Int): Int {
        ui.rect(x, y + 2, w, 1, Theme.BORDER)
        return 8
    }

    private fun footer(ui: Ui, state: PanelState?) {
        var y = bodyBottom + 6
        val w = (panelW - 20 - 4) / 2
        ui.button(px + 10, y, w, 18, if (obj == null) "Place it (Enter)" else "Save (Enter)", Style.PRIMARY, kind != null && !saving && !ObjectsTool.busy) { save() }
        ui.button(px + 14 + w, y, w, 18, if (obj == null) "Cancel" else "Close", Style.GHOST) { onClose() }
        y += 22
        if (obj != null) ui.button(px + 10, y, panelW - 20, 16, if (deleting) "Sure? Click again" else "Delete it", Style.DANGER, !ObjectsTool.busy) {
            if (!deleting) deleting = true
            else ObjectsTool.delete(confirmed = true) { result -> result.onSuccess { onClose() }.onFailure { say(it.message.orEmpty(), Theme.ERROR) } }
        }
        val idle = when {
            obj == null -> "Enter places it · Esc closes"
            state?.panel != null -> "Enter saves what you changed · the panel's own fields are sent as you leave them"
            else -> "Enter saves what you changed · Esc closes"
        }
        val (text, color) = message.first.ifEmpty { idle } to if (message.first.isEmpty()) Theme.FAINT else message.second
        ui.text(ui.fit(text, panelW - 20), px + 10, py + ph - 14, color)
        if (ui.font.width(text) > panelW - 20) ui.hit(px + 10, py + ph - 16, panelW - 20, 12, text)
    }

    override fun drawOverlay(ui: Ui) {
        pop?.draw(ui, width, height) { pop = null }
    }

    private companion object {
        const val FOOTER = 62
        /** The heading of where it is, and the group of fields shown there. */
        const val LOCATION = "Location"
    }
}
