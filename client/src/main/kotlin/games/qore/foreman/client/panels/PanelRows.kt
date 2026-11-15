package games.qore.foreman.client.panels

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.regions.FieldInput
import games.qore.foreman.client.ui.kit.Popover
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox

/**
 * Draws a plugin's [Panel], whatever it shows: its status line and tabs ([header]: the sections of the tab picked, those
 * without a tab on every tab; the last tab picked is kept per [tabKey]), a section per title, then its rows. Text as a
 * paragraph, a value as its label and its value in its colour (its hint on hover), a field as the [FieldInput] of its
 * type, buttons side by side (asking first when they [confirm][PanelRow.confirm]), a list as its items with their own
 * buttons (an item that is a target opens its panel), conditions with whether each holds and what it reads (a subject
 * opens its panel) and their [builder][ConditionBuilder], a kind it doesn't know as its text form; a target field shows
 * what its provider calls it. A field is sent (`panel_edit`) when it is left or Enter pressed (picked ones at once);
 * buttons run their action (`panel_action`). The server's answers show under the fields they name and on the [host]'s
 * status line.
 */
class PanelRows(private val host: Host) {
    /** The screen the rows are drawn in. */
    interface Host {
        fun widgetFont(): Font
        val popoverOpen: Boolean
        fun add(box: EditBox): EditBox
        fun remove(box: EditBox)
        fun focused(box: EditBox): Boolean
        fun open(popover: Popover)
        /** Closes the screen while the builder picks a target in the world, then gives it (null: none) with the screen back. */
        fun pickTarget(what: String, done: (JsonObject?) -> Unit)
        /** Shows the panel of [target] ([name] for people), a list item's. */
        fun openTarget(target: JsonObject, name: String)
        fun say(text: String, color: Int)
    }

    /** A field of the panel as edited: what the server last had ([server]), the text last sent ([sent]), on its way ([saving]). */
    private class Field(val input: FieldInput, var server: JsonElement?) {
        var sent = ""
        var saving = false
        val key get() = input.field.name
    }

    private val fields = LinkedHashMap<String, Field>()
    private var panel: Panel? = null
    private var target: JsonObject? = null
    /** Actions on their way, by id and arguments (a list's items share ids). */
    private val running = HashSet<String>()
    /** What the last tab picked is kept under: the object's kind, else the provider. */
    var tabKey: String? = null
    private var tab: String? = null
    /** The condition builder open, under its row. */
    private var builder: ConditionBuilder? = null

    /** Follows the panel shown: new fields get their boxes, values the server changed show unless being edited. */
    fun sync(state: PanelState?) {
        val target = state?.target
        if (target == null || this.target == null || !Targets.same(target, this.target!!)) {
            clear()
            this.target = target
        }
        val panel = state?.panel
        if (panel === this.panel) return
        this.panel = panel
        prefill(panel)
        val rows = panel?.fields.orEmpty().associateBy { it.field!!.name }
        fields.values.removeIf { f -> (rows[f.key]?.field?.type != f.input.field.type).also { gone -> if (gone) f.input.box?.let(host::remove) } }
        for ((key, row) in rows) {
            val old = fields[key]
            if (old == null || old.input.field != row.field) {
                val field = Field(input(row), row.value)
                if (old != null && editing(old)) field.input.copy(old.input) else field.input.load(row.value)
                old?.input?.box?.let(host::remove)
                field.input.build(host.widgetFont(), host::add)
                field.sent = if (old != null && editing(old)) old.sent else field.input.text
                field.saving = old?.saving ?: false
                fields[key] = field
            } else if (row.value != old.server) {
                old.server = row.value
                if (!editing(old)) {
                    old.input.load(row.value)
                    old.sent = old.input.text
                }
            }
        }
    }

    private fun input(row: PanelRow): FieldInput {
        val f = row.field!!
        return FieldInput(f, Bridge.world, { Minecraft.getInstance().player?.yRot }, host::open, { done -> host.pickTarget(f.label.ifEmpty { f.name }, done) }, Targets::named)
            .also { input -> input.onPicked = { fields[f.name]?.takeIf { it.input === input }?.let(::send) } }
    }

    /** Being changed here: typed and not sent yet, focused, or on its way. */
    private fun editing(f: Field) = f.saving || f.input.box?.let(host::focused) == true || f.input.boxed && f.input.text != f.sent

    private fun clear() {
        fields.values.forEach { f -> f.input.box?.let(host::remove) }
        fields.clear()
        running.clear()
        closeBuilder()
        panel = null
    }

    /** Makes the fields' boxes again (the screen was laid out again: its widgets are new). */
    fun build() {
        fields.values.forEach { it.input.build(host.widgetFont(), host::add) }
        builder?.build(host.widgetFont())
    }

    /** Enter: sends the field being typed in, or adds the condition being built; true when there was one. */
    fun enter(): Boolean {
        if (builder?.enter() == true) return true
        val f = fields.values.firstOrNull { f -> f.input.box?.let(host::focused) == true } ?: return false
        f.input.box?.isFocused = false
        send(f)
        return true
    }

    /** Sends [f]'s value (`panel_edit`) unless it isn't one its field takes. */
    private fun send(f: Field) {
        val target = target ?: return
        f.sent = f.input.text
        val value = f.input.value()
        if (f.input.error != null) return
        f.saving = true
        Panels.edit(target, f.key, value) { result ->
            f.saving = false
            answered(result, f)
        }
    }

    /** Shows how [result] went: on the fields it names ([f] the one sent), on the status line. */
    private fun answered(result: PanelResult, f: Field?) {
        val named = HashSet<String>()
        result.errors.forEach { (key, error) -> fields[key]?.let { it.input.error = error; named += key } }
        // A refusal in plain text: its "key: reason" lines go under those fields.
        if (!result.ok && result.errors.isEmpty()) result.message.orEmpty().lines().forEach { line ->
            fields.values.firstOrNull { line.startsWith(it.key + ":") }?.let { it.input.error = line.substringAfter(':').trim().replaceFirstChar(Char::uppercase); named += it.key }
        }
        if (f != null) {
            if (!result.ok && f.key !in named) f.input.error = result.message?.lineSequence()?.firstOrNull() ?: "Refused"
            if (!result.ok && !f.input.boxed) {
                // A switch showing what the server refused would lie: back to its value, the reason under it.
                val error = f.input.error
                f.input.load(f.server)
                f.input.error = error
            }
            if (result.ok && f.key !in named) f.input.error = null
        }
        val message = result.message?.lineSequence()?.firstOrNull { it.isNotBlank() }
        when {
            !result.ok -> host.say("✖ " + (message ?: "Refused"), Theme.ERROR)
            message != null -> host.say("✔ $message", Theme.OK)
            f != null -> host.say("✔ Saved ${f.input.field.label.ifEmpty { f.key }}", Theme.OK)
        }
    }

    /**
     * Draws the panel from [y] in [w] at [x], boxes only between [top] and [bottom]; returns the height taken. [before]
     * draws what the host shows at the top of a section, under its title (an object's form fields of that group), at
     * the y it gets, and returns the height it took.
     */
    fun draw(ui: Ui, panel: Panel, x: Int, y: Int, w: Int, top: Int, bottom: Int, before: (PanelSection, Int) -> Int = { _, _ -> 0 }): Int {
        sendLeft()
        var ry = y
        val tab = tab(panel)
        val shown = panel.sections.filter { it.tab == null || it.tab == tab }
        // The boxes of fields on other tabs aren't drawn: hidden, or they'd stay where they were last.
        val drawn = shown.flatMap { s -> s.rows.mapNotNull { it.field?.name } }.toSet()
        fields.values.forEach { f -> if (f.key !in drawn) f.input.box?.visible = false }
        for ((i, section) in shown.withIndex()) {
            if (i > 0 || section.title.isNotEmpty()) {
                ui.rect(x, ry, w, 1, Theme.BORDER)
                ry += 6
            }
            if (section.title.isNotEmpty()) {
                ui.text(ui.fit(section.title, w), x, ry, Theme.ACCENT)
                ry += 13
            }
            ry += before(section, ry)
            var r = 0
            val rows = section.rows
            while (r < rows.size) {
                val row = rows[r]
                if (row.kind == "button") {
                    // Buttons side by side, three at most, fewer when their labels wouldn't fit.
                    val run = rows.drop(r).takeWhile { it.kind == "button" }.take(3)
                    val n = (run.size downTo 2).firstOrNull { n -> run.take(n).all { ui.font.width(label(it)) <= (w - (n - 1) * 4) / n - 8 } } ?: 1
                    ry += buttons(ui, run.take(n), x, ry, w)
                    r += n
                    continue
                }
                ry += row(ui, row, x, ry, w, top, bottom)
                r++
            }
            ry += 4
        }
        if (panel.sections.isEmpty()) ry += ui.paragraph("Its plugin shows nothing here.", x, ry, w, Theme.FAINT)
        return ry - y
    }

    // ---- Tabs and status ----------------------------------------------------------------------------------------------

    /** The tab shown of [panel]: the one picked, else the last one picked for [tabKey], else its first; null without tabs. */
    fun tab(panel: Panel?): String? {
        val tabs = panel?.tabs.orEmpty()
        if (tabs.isEmpty()) return null
        tab?.takeIf { it in tabs }?.let { return it }
        return (tabKey?.let { lastTabs[it] }?.takeIf { it in tabs } ?: tabs.first()).also { tab = it }
    }

    /** Shows [name], kept as the last tab picked for [tabKey]. */
    fun pick(name: String) {
        if (name == tab) return
        tab = name
        tabKey?.let { lastTabs[it] = name }
        closeBuilder()
        // Boxes of the tab left are drawn no more: hide them until theirs is shown again.
        fields.values.forEach { it.input.box?.visible = false }
    }

    /** The height [header] takes for [panel]. */
    fun headerHeight(panel: Panel?): Int = (if (panel?.status != null) STATUS else 0) + (if (panel?.tabs.orEmpty().isNotEmpty()) TABS else 0)

    /** [panel]'s status line (in its level's colour) and its tabs, at [x], [y] in [w]; returns the height taken. */
    fun header(ui: Ui, panel: Panel?, x: Int, y: Int, w: Int): Int {
        panel ?: return 0
        var ry = y
        panel.status?.let { s ->
            val text = "${s.mark} ${s.text}"
            ui.text(ui.fit(text, w), x, ry + 1, s.argb)
            if (ui.font.width(text) > w) ui.hit(x, ry, w, 11, s.text)
            ry += STATUS
        }
        val tabs = panel.tabs
        if (tabs.isNotEmpty()) {
            val current = tab(panel)
            var tx = x
            for (name in tabs) {
                val tw = ui.font.width(name) + 12
                val on = name == current
                val hover = ui.hovered(tx, ry, tw, 15)
                if (on || hover) ui.rect(tx, ry, tw, 15, if (on) Theme.PANEL_HOVER else Theme.PANEL)
                if (on) ui.rect(tx, ry + 14, tw, 2, Theme.ACCENT)
                ui.text(name, tx + 6, ry + 4, if (on) Theme.TEXT else Theme.MUTED)
                ui.hit(tx, ry, tw, 15, null) { pick(name) }
                tx += tw + 2
            }
            ui.rect(x, ry + 16, w, 1, Theme.BORDER)
            ry += TABS
        }
        return ry - y
    }

    // ---- Conditions -------------------------------------------------------------------------------------------------

    /** Opens the builder a link asked for (`build`) once [panel] shows its row: on its tab, filled in. */
    private fun prefill(panel: Panel?) {
        val p = Panels.prefill ?: return
        val target = target ?: return
        if (panel == null || !Targets.same(p.target, target)) return
        val section = panel.sections.firstOrNull { s -> s.rows.any { it.kind == "conditions" && it.id == p.row } } ?: return
        Panels.prefill = null
        section.tab?.let(::pick)
        val row = section.rows.first { it.kind == "conditions" && it.id == p.row }
        openBuilder(row).prefill(p.condition, p.replaces)
    }

    private fun openBuilder(row: PanelRow): ConditionBuilder {
        closeBuilder()
        return ConditionBuilder(host, target!!, row) { message ->
            closeBuilder()
            message?.let { host.say("✔ $it", Theme.OK) }
        }.also { builder = it }
    }

    private fun closeBuilder() {
        builder?.dispose()
        builder = null
    }

    /**
     * A conditions row: each condition with ✓ / ✗ (or why it can't be read, or off), what it is for and where it comes
     * from, the subjects it reads (a click opens their panel) and the values read, its buttons on the right; an OR
     * group's alternatives under it; then its add button, or its builder while open.
     */
    private fun conditions(ui: Ui, row: PanelRow, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int {
        var ry = y
        if (row.label.isNotEmpty()) {
            ui.text(ui.fit(row.label, w), x, ry, Theme.MUTED)
            ry += 12
        }
        if (row.conditions.isEmpty()) {
            ui.text("None: nothing else to meet", x + 4, ry, Theme.FAINT)
            ry += 12
        }
        for (c in row.conditions) ry += condition(ui, c, x, ry, w, true)
        val open = builder?.takeIf { it.rowId == row.id }
        val add = row.add
        when {
            open != null -> ry += 4 + open.draw(ui, x, ry + 4, w, top, bottom)
            add != null -> {
                ui.button(x, ry + 2, minOf(w, ui.font.width(add.label) + 30), 16, "+ " + add.label.ifEmpty { "Add a condition" }, Style.NORMAL,
                    add.enabled && target != null, add.hint.ifEmpty { null }) { openBuilder(row) }
                ry += 22
            }
        }
        return ry - y + 4
    }

    /** One condition (an alternative of an OR group when not [top]): its mark and text, then what it is and reads. */
    private fun condition(ui: Ui, c: ConditionData, x: Int, y: Int, w: Int, top: Boolean): Int {
        val (mark, color) = when {
            !c.enabled -> "–" to Theme.FAINT
            c.error != null -> "!" to Theme.WARN
            c.holds == true -> "✓" to Theme.OK
            c.holds == false -> "✗" to Theme.ERROR
            else -> "?" to Theme.MUTED
        }
        var right = x + w
        if (top) for (a in c.actions.reversed()) {
            val label = a.label.ifEmpty { a.id }
            val bw = ui.font.width(label) + 10
            right -= bw
            button(ui, a, right, y, bw, 14)
            right -= 3
        }
        val indent = if (top) 0 else 12
        if (!top) ui.text("or", x + 2, y + 2, Theme.ACCENT)
        ui.text(mark, x + indent + 2, y + 2, color)
        val tx = x + indent + 12
        val textW = right - tx - 4
        val text = ui.fit(c.text, textW)
        ui.text(text, tx, y + 2, if (c.enabled) Theme.TEXT else Theme.FAINT)
        if (!c.enabled) ui.rect(tx, y + 6, ui.font.width(text), 1, Theme.FAINT)
        if (text != c.text) ui.hit(tx, y, textW, 12, c.text)
        var ry = y + 13
        // What it is for and where it comes from, then the subjects it reads (links) and what they read.
        val about = listOfNotNull(c.label, c.origin, if (!c.enabled) "off here" else null).joinToString(" · ")
        var lx = tx
        if (about.isNotEmpty()) {
            val shown = ui.fit(about, x + w - lx)
            ui.text(shown, lx, ry, Theme.FAINT)
            lx += ui.font.width(shown) + 6
        }
        for (s in c.subjects) {
            val target = s.target ?: continue
            val name = "→ " + s.name
            val nw = ui.font.width(name)
            if (lx + nw > x + w) break
            val hover = ui.hovered(lx, ry - 1, nw, 10)
            ui.text(name, lx, ry, if (hover) Theme.TEXT else Theme.ACCENT)
            ui.hit(lx, ry - 1, nw, 10, "Its panel: walk the chain") { host.openTarget(target, s.name) }
            lx += nw + 6
        }
        if (lx > tx || about.isNotEmpty()) ry += 11
        val detail = c.error ?: c.reads
        if (detail != null) ry += ui.paragraph(detail, tx, ry, x + w - tx, if (c.error != null) Theme.WARN else Theme.MUTED)
        for (alt in c.any) ry += condition(ui, alt, x + 4, ry, w - 4, false)
        return ry - y + 4
    }

    /** Sends the typed fields that lost the focus (a click elsewhere, the colour picker closed, Facing). */
    private fun sendLeft() {
        if (!host.popoverOpen) flush(focused = false)
    }

    /** Sends every field typed in and not sent yet, the [focused] one too or not (the screen closes). */
    fun flush(focused: Boolean = true) {
        for (f in fields.values) {
            val box = f.input.box ?: continue
            if (!f.saving && f.input.text != f.sent && (focused || !host.focused(box))) send(f)
        }
    }

    private fun row(ui: Ui, row: PanelRow, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int = when (row.kind) {
        "text" -> ui.paragraph(row.text, x, y, w, Theme.MUTED) + 4
        "value" -> value(ui, row, x, y, w)
        "field" -> fields[row.field?.name]?.let { it.input.draw(ui, x, y, w, top, bottom, it.saving) } ?: text(ui, row, x, y, w)
        "list" -> list(ui, row, x, y, w)
        "conditions" -> conditions(ui, row, x, y, w, top, bottom)
        else -> text(ui, row, x, y, w)
    }

    private fun text(ui: Ui, row: PanelRow, x: Int, y: Int, w: Int): Int =
        if (row.text.isEmpty()) 0 else ui.paragraph(row.text, x, y, w, Theme.MUTED) + 4

    /** A label and its value, the value wrapped beside it in its colour; the hint on hover. */
    private fun value(ui: Ui, row: PanelRow, x: Int, y: Int, w: Int): Int {
        val labelW = FieldInput.labelWidth(w)
        val label = ui.fit(row.label, labelW - 10)
        ui.text(label, x, y + 2, Theme.TEXT)
        if (row.hint.isNotEmpty()) ui.text("?", x + ui.font.width(label) + 2, y + 2, Theme.FAINT)
        val shown = (row.value as? kotlinx.serialization.json.JsonPrimitive)?.content ?: row.value?.toString() ?: row.text
        val h = ui.paragraph(shown, x + labelW, y + 2, w - labelW, Panels.color(row.color) ?: Theme.TEXT).coerceAtLeast(10)
        // A label cut short shows whole on hover, above the hint.
        val tooltip = listOfNotNull(row.label.takeIf { label != it }, row.hint.ifEmpty { null }).joinToString("\n").ifEmpty { null }
        ui.hit(x, y, w, h + 4, tooltip)
        return h + 6
    }

    /** [run] of buttons side by side in [w]. */
    private fun buttons(ui: Ui, run: List<PanelRow>, x: Int, y: Int, w: Int): Int {
        val each = (w - (run.size - 1) * 4) / run.size
        run.forEachIndexed { i, b -> button(ui, b, x + i * (each + 4), y, each, 18) }
        return 22
    }

    private fun button(ui: Ui, b: PanelRow, x: Int, y: Int, w: Int, h: Int) {
        val style = when (b.style.lowercase()) {
            "primary" -> Style.PRIMARY
            "danger" -> Style.DANGER
            "ghost" -> Style.GHOST
            else -> Style.NORMAL
        }
        val label = label(b)
        val going = key(b) in running
        // A label cut short shows whole in the tooltip, above the hint.
        val tooltip = listOfNotNull(label.takeIf { ui.font.width(it) > w - 8 }, b.hint.ifEmpty { null }).joinToString("\n").ifEmpty { null }
        ui.button(x, y, w, h, if (going) "$label…" else label, style, b.enabled && !going && target != null, tooltip) {
            val confirm = b.confirm
            if (confirm.isNullOrBlank()) act(b) else host.open(Confirm(x, y + h + 2, confirm, label, style) { act(b) })
        }
    }

    private fun act(b: PanelRow) {
        val target = target ?: return
        val key = key(b)
        running += key
        Panels.action(target, b.id, b.args) { result ->
            running -= key
            answered(result, null)
        }
    }

    private fun key(b: PanelRow) = "${b.id} ${b.args}"

    private fun label(b: PanelRow) = b.label.ifEmpty { b.text.ifEmpty { b.id } }

    /** A list's items: a swatch, the label (a target's opens its panel), the detail, its buttons on the right. */
    private fun list(ui: Ui, row: PanelRow, x: Int, y: Int, w: Int): Int {
        var ry = y
        if (row.label.isNotEmpty()) {
            ui.text(ui.fit(row.label, w), x, ry, Theme.MUTED)
            ry += 12
        }
        if (row.items.isEmpty()) {
            ui.text("None", x + 4, ry, Theme.FAINT)
            return ry + 12 - y
        }
        for (item in row.items) {
            val hover = ui.hovered(x, ry, w, ITEM)
            if (hover) ui.rect(x - 2, ry, w + 4, ITEM, Theme.PANEL_HOVER)
            var right = x + w
            for (a in item.actions.reversed()) {
                val label = a.label.ifEmpty { a.id }
                val bw = ui.font.width(label) + 10
                right -= bw
                button(ui, a, right, ry + 1, bw, ITEM - 2)
                right -= 3
            }
            var tx = x + 2
            Panels.color(item.color)?.let {
                ui.swatch(tx, ry + 4, 8, it)
                tx += 12
            }
            val label = item.label.ifEmpty { item.text.ifEmpty { item.id } }
            val detailW = if (item.detail.isEmpty()) 0 else ui.font.width(item.detail).coerceAtMost((right - tx) / 2)
            val labelW = right - tx - detailW - 8
            ui.text(ui.fit(label, labelW), tx, ry + 4, if (item.target != null && ui.hovered(tx, ry, labelW, ITEM)) Theme.ACCENT else Theme.TEXT)
            if (detailW > 0) ui.text(ui.fit(item.detail, detailW), right - detailW - 4, ry + 4, Theme.MUTED)
            // What was cut short shows whole on hover.
            val whole = listOfNotNull(label.takeIf { ui.font.width(it) > labelW }, item.detail.takeIf { ui.font.width(it) > detailW }).joinToString("\n").ifEmpty { null }
            val target = item.target
            if (target != null) ui.hit(tx, ry, labelW, ITEM, listOfNotNull(whole, "Its panel").joinToString("\n")) { host.openTarget(target, label) }
            else if (whole != null) ui.hit(tx, ry, right - tx, ITEM, whole)
            ry += ITEM
        }
        return ry - y + 4
    }

    /** Asks before running a button that wants it: [question], then [label] or Cancel. */
    private class Confirm(private val x: Int, private val y: Int, private val question: String, private val label: String, private val style: Style, private val yes: () -> Unit) : Popover {
        override fun draw(ui: Ui, screenW: Int, screenH: Int, close: () -> Unit) {
            ui.backdrop(screenW, screenH, close)
            val w = 200
            val lines = ui.font.split(net.minecraft.network.chat.Component.literal(question), w - 16).size
            val h = lines * 10 + 40
            val left = x.coerceAtMost(screenW - w - 10).coerceAtLeast(10)
            val top = y.coerceAtMost(screenH - h - 10).coerceAtLeast(10)
            ui.card(left, top, w, h, Theme.PANEL, Theme.ACCENT_DARK)
            ui.paragraph(question, left + 8, top + 8, w - 16, Theme.TEXT)
            val bw = (w - 20) / 2
            ui.button(left + 8, top + h - 24, bw, 16, label, if (style == Style.DANGER) Style.DANGER else Style.PRIMARY) {
                close()
                yes()
            }
            ui.button(left + 12 + bw, top + h - 24, bw, 16, "Cancel", Style.GHOST) { close() }
        }
    }

    private companion object {
        const val ITEM = 16
        const val STATUS = 14
        const val TABS = 20
        /** The last tab picked, by [tabKey]: kept while the game runs. */
        val lastTabs = HashMap<String, String>()
    }
}
