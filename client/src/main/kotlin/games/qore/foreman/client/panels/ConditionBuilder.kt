package games.qore.foreman.client.panels

import games.qore.foreman.client.regions.PickList
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component

/**
 * The builder of a plugin's conditions [row] in [target]'s panel, open under it: a condition made of terms of which one
 * must hold (one term, or an OR group), each a subject (picked in the world, or one of those its plugin lists: this
 * thing, named ones, world facts), one of its facts (typed: true or false, a number, text, one of its values), a
 * comparison for that type and a value; or an expression typed in its plugin's syntax. What it would read is asked of the
 * plugin as it changes (`panel_facts` check): how it writes it, whether it holds now, or why it can't be read. Add sends it
 * to the row's add button (`panel_action`, `condition` in its args). Foreman knows nothing of the syntax.
 */
class ConditionBuilder(private val host: PanelRows.Host, private val target: JsonObject, private val row: PanelRow, private val close: (String?) -> Unit) {
    /** A comparison being built: its [subject] (with [facts]), the [fact] read, the [op] and the value ([value] picked, [text] typed). */
    private class Term {
        var subject: SubjectData? = null
        var facts: List<FactData> = emptyList()
        var fact: FactData? = null
        var op = "=="
        var value: JsonElement? = null
        var text = ""
        var box: EditBox? = null
        var loading = false
        /** The fact and value a prefill asks for, set once its facts came. */
        var wanted: JsonObject? = null

        val typed get() = fact?.type == "number" || fact?.type == "text"
    }

    private var typing = false
    private val terms = mutableListOf(Term())
    private var raw: EditBox? = null
    private var rawText = ""
    private var subjects: List<SubjectData>? = null
    private var check: CheckData? = null
    /** The condition last checked, and the one last drawn with when it changed (checks wait for it to settle). */
    private var checked: JsonObject? = null
    private var drawn: JsonObject? = null
    private var changedAt = 0L
    private var checking = false
    private var adding = false
    private var error: String? = null
    /** The relation a link being changed leaves ([prefill]): unlinked once this condition is added. */
    private var replaces: Relation? = null

    val rowId get() = row.id

    init {
        Panels.subjects(target, row.id) { result -> result.onSuccess { subjects = it }.onFailure { error = it.message } }
    }

    // ---- What it builds ---------------------------------------------------------------------------------------------

    /** The condition as the plugin takes it, null while it lacks a part. */
    private fun condition(): JsonObject? {
        if (typing) return rawText.trim().takeIf { it.isNotEmpty() }?.let { text -> buildJsonObject { put("expression", text) } }
        val built = terms.map { term(it) ?: return null }
        return buildJsonObject { put("any", JsonArray(built)) }
    }

    private fun term(t: Term): JsonObject? {
        val subject = t.subject ?: return null
        val fact = t.fact ?: return null
        val value: JsonElement = when (fact.type) {
            "number" -> t.text.trim().replace(',', '.').toDoubleOrNull()?.let(::JsonPrimitive) ?: return null
            "text" -> JsonPrimitive(t.text)
            "bool" -> t.value ?: JsonPrimitive(true)
            else -> t.value ?: return null
        }
        return buildJsonObject {
            put("subject", subject.id)
            put("fact", fact.name)
            put("op", t.op)
            put("value", value)
            subject.target?.let { put("target", it) }
        }
    }

    /**
     * Fills it in with [condition] (a link's `build`): a typed expression, or terms whose subjects' facts are asked for;
     * a relation being changed [replaces] the one it leaves, unlinked once this is added.
     */
    fun prefill(condition: JsonObject, replaces: Relation? = null) {
        this.replaces = replaces
        condition["expression"].str()?.let {
            typing = true
            rawText = it
            raw?.value = it
            return
        }
        val wanted = condition["any"].arr()?.mapNotNull { it.obj() } ?: listOf(condition)
        clearTerms()
        for (w in wanted) {
            val t = Term()
            terms += t
            val id = w["subject"].str() ?: continue
            t.wanted = w
            choose(t, SubjectData(id, id, target = w["target"].obj()), w["target"].obj())
        }
        if (terms.isEmpty()) terms += Term()
    }

    private fun clearTerms() {
        terms.forEach { it.box?.let(host::remove) }
        terms.clear()
    }

    // ---- Choosing ---------------------------------------------------------------------------------------------------

    /** [t] reads [subject] (or what was [picked] in the world): its facts are asked for, the first one taken. */
    private fun choose(t: Term, subject: SubjectData?, picked: JsonObject?) {
        t.subject = subject
        t.fact = null
        t.facts = emptyList()
        t.loading = true
        changed()
        Panels.facts(target, row.id, subject?.id?.takeIf { picked == null }, picked) { result ->
            t.loading = false
            result.onSuccess { answer ->
                t.subject = answer.subject.let { s -> if (s.target == null && picked != null) s.copy(target = picked) else s }
                t.facts = answer.facts
                val wanted = t.wanted
                t.wanted = null
                fact(t, answer.facts.firstOrNull { it.name == wanted?.get("fact").str() } ?: answer.facts.firstOrNull())
                if (wanted != null) {
                    t.op = wanted["op"].str() ?: t.op
                    when (t.fact?.type) {
                        "number", "text" -> wanted["value"]?.let { v -> t.text = (v as? JsonPrimitive)?.let { p -> p.double()?.takeIf { !p.isString }?.let(::num) ?: p.content }.orEmpty(); t.box?.value = t.text }
                        else -> wanted["value"]?.let { t.value = it }
                    }
                }
            }.onFailure { error = it.message ?: "Its plugin has no facts there" }
        }
    }

    /** [t] reads [fact]: a comparison and value that fit its type. */
    private fun fact(t: Term, fact: FactData?) {
        t.fact = fact
        t.op = "=="
        t.value = when (fact?.type) {
            "bool" -> JsonPrimitive(true)
            "choice" -> (fact.now?.takeIf { it in fact.values } ?: fact.values.firstOrNull())?.let(::JsonPrimitive)
            else -> null
        }
        t.text = if (fact?.type == "number") fact.now?.takeIf { it.toDoubleOrNull() != null } ?: "" else ""
        t.box?.let(host::remove)
        t.box = null
        if (t.typed) t.box = box(t)
        changed()
    }

    private fun changed() {
        changedAt = System.currentTimeMillis()
        error = null
    }

    // ---- Boxes ------------------------------------------------------------------------------------------------------

    private fun box(t: Term): EditBox = host.add(EditBox(host.widgetFont(), 0, 0, 60, 16, Component.literal("value"))).also { box ->
        box.setMaxLength(200)
        box.setHint(Component.literal(if (t.fact?.type == "number") "a number" else "text"))
        box.value = t.text
        box.setResponder {
            t.text = it
            changed()
        }
        box.visible = false
    }

    private fun rawBox(): EditBox = host.add(EditBox(host.widgetFont(), 0, 0, 100, 16, Component.literal("expression"))).also { box ->
        box.setMaxLength(500)
        box.setHint(Component.literal(row.syntax ?: "an expression"))
        box.value = rawText
        box.setResponder {
            rawText = it
            changed()
        }
        box.visible = false
    }

    /** Makes its boxes again (the screen's widgets are new). */
    fun build(font: Font) {
        raw = if (typing || raw != null) rawBox() else null
        terms.forEach { t -> t.box = if (t.typed) box(t) else null }
    }

    /** Takes its boxes away: it closes. */
    fun dispose() {
        raw?.let(host::remove)
        raw = null
        terms.forEach { t -> t.box?.let(host::remove); t.box = null }
    }

    /** Enter in one of its boxes adds it; true when it was one of them. */
    fun enter(): Boolean {
        val focused = (listOfNotNull(raw) + terms.mapNotNull { it.box }).any(host::focused)
        if (focused) add()
        return focused
    }

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    /** Draws it at [x], [y] in [w], boxes shown only between [top] and [bottom]; returns its height. */
    fun draw(ui: Ui, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int {
        settle()
        var ry = y
        ui.card(x - 4, y - 2, w + 8, 1, Theme.ACCENT_DARK)
        ry += 4
        ui.segmented(x, ry, listOf("Build", "Type it"), if (typing) 1 else 0) { i -> mode(i == 1) }
        ry += 20
        if (typing) ry += typed(ui, x, ry, w, top, bottom)
        else {
            terms.toList().forEachIndexed { i, t ->
                if (i > 0) {
                    ui.text("or", x, ry + 1, Theme.ACCENT)
                    ry += 11
                }
                ry += term(ui, t, x, ry, w, top, bottom)
            }
            ui.button(x, ry, 60, 14, "+ or…", Style.GHOST, tooltip = "Another way it may hold: it holds when one of them does") {
                terms += Term()
                changed()
            }
            ry += 18
        }
        ry += preview(ui, x, ry, w)
        val ready = condition() != null && !adding && row.add?.enabled != false
        val bw = (w - 4) / 2
        ui.button(x, ry, bw, 16, if (adding) "Adding…" else row.add?.label?.ifEmpty { null } ?: "Add", Style.PRIMARY, ready,
            tooltip = if (condition() == null) "Pick a subject, a fact and a value first" else "Adds it (Enter)") { add() }
        ui.button(x + bw + 4, ry, bw, 16, "Cancel", Style.GHOST) { close(null) }
        ry += 20
        error?.let { ry += ui.paragraph(it, x, ry, w, Theme.ERROR) + 4 }
        return ry - y + 2
    }

    private fun mode(type: Boolean) {
        if (type == typing) return
        typing = type
        if (type) {
            // What was built becomes the expression to start from.
            check?.expression?.takeIf { rawText.isBlank() && checked?.get("any") != null }?.let { rawText = it }
            if (raw == null) raw = rawBox()
            raw?.value = rawText
        } else raw?.visible = false
        terms.forEach { it.box?.visible = false }
        changed()
    }

    private fun typed(ui: Ui, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int {
        val box = raw ?: rawBox().also { raw = it }
        box.setWidth(w)
        box.setPosition(x, y)
        box.visible = y >= top && y + 16 <= bottom
        var h = 20
        row.syntax?.let { h += ui.paragraph(it, x, y + h, w, Theme.FAINT) + 4 }
        return h
    }

    /** A term: its subject and fact, then its comparison and value; × takes it away (when there are others). */
    private fun term(ui: Ui, t: Term, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int {
        val remove = if (terms.size > 1) 16 else 0
        val half = (w - remove - 4) / 2
        val subject = t.subject?.name ?: "Subject…"
        ui.button(x, y, half, 16, ui.fit("$subject ▾", half - 8), Style.NORMAL, tooltip = t.subject?.let { s -> listOfNotNull(s.label, s.detail, s.target?.let(Targets::named)).joinToString("\n") } ?: "What it reads") {
            subjectList(t, x, y + 18, half.coerceAtLeast(180))
        }
        val factLabel = when {
            t.loading -> "…"
            t.subject == null -> "Fact"
            else -> (t.fact?.shown ?: "Fact…") + " ▾"
        }
        ui.button(x + half + 4, y, half, 16, ui.fit(factLabel, half - 8), Style.NORMAL, t.facts.isNotEmpty(),
            tooltip = t.fact?.let { f -> listOfNotNull(f.description, f.now?.let { "Now: $it" }).joinToString("\n").ifEmpty { null } }) {
            factList(t, x + half + 4, y + 18, half.coerceAtLeast(180))
        }
        if (remove > 0) ui.button(x + w - 14, y, 14, 16, "×", Style.GHOST, tooltip = "Take this one away") {
            t.box?.let(host::remove)
            terms -= t
            changed()
        }
        var ry = y + 20
        val fact = t.fact
        if (fact != null) {
            ry += comparison(ui, t, fact, x, ry, w - remove, top, bottom)
        } else t.box?.visible = false
        return ry - y + 2
    }

    /** The comparison and value of [t] reading [fact], as its type allows. */
    private fun comparison(ui: Ui, t: Term, fact: FactData, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int {
        when (fact.type) {
            "bool" -> {
                val no = t.value?.let { (it as? JsonPrimitive)?.bool() == false } == true
                ui.segmented(x, y, listOf("is true", "is false"), if (no) 1 else 0) { i ->
                    t.op = "=="
                    t.value = JsonPrimitive(i == 0)
                    changed()
                }
            }
            "number", "text" -> {
                val ops = if (fact.type == "number") NUMBER_OPS else EQUALITY
                val used = ui.segmented(x, y, ops.map { it.second }, ops.indexOfFirst { it.first == t.op }.coerceAtLeast(0)) { i ->
                    t.op = ops[i].first
                    changed()
                }
                val box = t.box ?: box(t).also { t.box = it }
                val bw = (w - used - 6).coerceAtLeast(40)
                box.setWidth(bw)
                box.setPosition(x + used + 6, y)
                box.visible = y >= top && y + 16 <= bottom
            }
            else -> {
                val used = ui.segmented(x, y, EQUALITY.map { it.second }, EQUALITY.indexOfFirst { it.first == t.op }.coerceAtLeast(0)) { i ->
                    t.op = EQUALITY[i].first
                    changed()
                }
                val current = t.value.str()
                val bw = (w - used - 6).coerceAtLeast(40)
                ui.button(x + used + 6, y, bw, 16, ui.fit((current ?: "Value…") + " ▾", bw - 8), Style.NORMAL) {
                    host.open(PickList(x + used + 6, y + 18, bw.coerceAtLeast(140), fact.values.map { v ->
                        PickList.Option(v, v, if (v == fact.now) "now" else "", current = v == current)
                    }) { v ->
                        t.value = JsonPrimitive(v)
                        changed()
                    })
                }
            }
        }
        val now = fact.now?.let { "now $it" }
        if (now != null && fact.type == "bool") ui.text(ui.fit(now, w - 120), x + 120, y + 4, Theme.FAINT)
        return 20
    }

    /** What [t] may read: picked in the world, then the plugin's subjects under their groups. */
    private fun subjectList(t: Term, x: Int, y: Int, w: Int) {
        val options = ArrayList<PickList.Option>()
        if (row.pick) options += PickList.Option(PICK, "Pick in the world…", tooltip = "Aim at it and click (right click: back)")
        val list = subjects
        if (list == null) options += PickList.Option(null, "Asking its plugin…")
        else list.groupBy { it.group.orEmpty() }.forEach { (group, items) ->
            if (group.isNotEmpty()) options += PickList.Option(null, group)
            items.forEach { s -> options += PickList.Option(s.id, s.name, s.detail.orEmpty(), current = s.id == t.subject?.id) }
        }
        host.open(PickList(x, y, w, options) { id ->
            if (id == PICK) host.pickTarget("What the condition reads") { picked -> if (picked != null) choose(t, null, picked) }
            else choose(t, list?.firstOrNull { it.id == id }, null)
        })
    }

    private fun factList(t: Term, x: Int, y: Int, w: Int) {
        host.open(PickList(x, y, w, t.facts.map { f ->
            PickList.Option(f.name, f.shown, listOfNotNull(f.type.takeIf { it != "choice" }, f.now?.let { "= $it" }).joinToString(" "),
                tooltip = f.description, current = f.name == t.fact?.name)
        }) { name -> fact(t, t.facts.firstOrNull { it.name == name }) })
    }

    /** What the plugin says of the condition as it is: how it writes it, whether it holds now, or why it can't be read. */
    private fun preview(ui: Ui, x: Int, y: Int, w: Int): Int {
        val c = check
        val now = condition()
        val text: String
        val color: Int
        when {
            now == null -> return 0
            c == null || checked != now -> {
                text = "Reading it…"
                color = Theme.FAINT
            }
            c.error != null -> {
                text = "✖ " + c.error
                color = Theme.ERROR
            }
            else -> {
                val mark = when (c.holds) {
                    true -> "✓ holds now"
                    false -> "✗ doesn't hold now"
                    null -> "not read here"
                }
                text = listOfNotNull(c.expression, mark, c.reads?.let { "reads $it" }).joinToString(" · ")
                color = when (c.holds) {
                    true -> Theme.OK
                    false -> Theme.ERROR
                    null -> Theme.MUTED
                }
            }
        }
        return ui.paragraph(text, x, y, w, color) + 6
    }

    /** Asks the plugin to check the condition once it stopped changing for a moment, one ask at a time. */
    private fun settle() {
        val now = condition()
        if (now != drawn) {
            drawn = now
            changedAt = System.currentTimeMillis()
        }
        if (now == null || now == checked || checking || System.currentTimeMillis() - changedAt < SETTLE_MS) return
        checking = true
        Panels.check(target, row.id, now) { result ->
            checking = false
            checked = now
            check = result.getOrElse { CheckData(false, error = it.message ?: "Couldn't check it") }
        }
    }

    /** Sends it to the row's add button. */
    private fun add() {
        val condition = condition() ?: return
        val add = row.add ?: return
        if (adding) return
        adding = true
        Panels.action(target, add.id, JsonObject((add.args ?: JsonObject(emptyMap())) + ("condition" to condition))) { result ->
            if (!result.ok) {
                adding = false
                error = result.message?.lineSequence()?.firstOrNull() ?: "Refused"
                return@action
            }
            val added = result.message ?: "Added"
            val old = replaces
            if (old == null) {
                adding = false
                return@action close(added)
            }
            // The link it changes goes now that the new one is made.
            Panels.unlink(old) { unlinked ->
                adding = false
                replaces = null
                close(if (unlinked.ok) "$added · the old link is gone" else "$added · the old link stays: ${unlinked.message?.lineSequence()?.firstOrNull() ?: "refused"}")
            }
        }
    }

    private companion object {
        const val PICK = "\u0000pick"
        const val SETTLE_MS = 350L
        val EQUALITY = listOf("==" to "=", "!=" to "≠")
        val NUMBER_OPS = listOf("==" to "=", "!=" to "≠", "<" to "<", "<=" to "≤", ">" to ">", ">=" to "≥")

        fun num(d: Double) = if (d == Math.floor(d) && !d.isInfinite()) d.toLong().toString() else d.toString()
    }
}
