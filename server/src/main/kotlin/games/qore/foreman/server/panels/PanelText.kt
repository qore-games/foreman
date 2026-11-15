package games.qore.foreman.server.panels

import games.qore.foreman.server.api.ButtonRow
import games.qore.foreman.server.api.ConditionItem
import games.qore.foreman.server.api.ConditionsRow
import games.qore.foreman.server.api.FieldRow
import games.qore.foreman.server.api.LayoutField
import games.qore.foreman.server.api.LinkResult
import games.qore.foreman.server.api.ListRow
import games.qore.foreman.server.api.Panel
import games.qore.foreman.server.api.PanelResult
import games.qore.foreman.server.api.PanelTarget
import games.qore.foreman.server.api.Row
import games.qore.foreman.server.api.TextRow
import games.qore.foreman.server.api.ValueRow

/** Panels and what was done with them as the AI reads them: each row a line, fields with their type and range. */
object PanelText {
    /** [p] for the AI: its title, provider and target, then its sections' rows. */
    fun panel(p: Panel, provider: Panels.Entry, target: PanelTarget): String = buildString {
        append(p.title).append(" (").append(provider.label).append(", ").append(provider.id).append(")\n")
        append("target: ").append(PanelJson.target(target))
        p.status?.let { append("\nstatus: ").append(it.level.key).append(" — ").append(it.text()) }
        p.subtitle?.let { append('\n').append(it) }
        p.sections.forEach { s ->
            append("\n\n")
            if (s.title.isNotEmpty() || s.tab != null) {
                append("## ").append(listOfNotNull(s.tab, s.title.ifEmpty { null }).joinToString(" › ")).append(s.id?.let { " ($it)" }.orEmpty()).append('\n')
            }
            append(s.rows.joinToString("\n") { row(it) })
        }
    }

    fun row(r: Row): String = when (r) {
        is TextRow -> r.content
        is ValueRow -> "${r.label}: ${r.value}" + (r.hint?.let { " ($it)" }.orEmpty())
        is FieldRow -> "${r.field.label}: ${value(r.field, r.value)} [field ${field(r.field)}]" + (r.hint?.let { " ($it)" }.orEmpty())
        is ButtonRow -> button(r)
        is ListRow -> if (r.items.isEmpty()) "(none)" else r.items.joinToString("\n") { i ->
            "- ${i.label}" + (i.detail?.let { ": $it" }.orEmpty()) + (i.target?.let { " → $it" }.orEmpty()) +
                i.buttons().joinToString("") { "  " + button(it) }
        }
        is ConditionsRow -> conditions(r)
    }

    /** Its conditions a line each (✓ holds, ✗ doesn't, ! can't be read, off: turned off), then how to add one. */
    private fun conditions(r: ConditionsRow): String = buildString {
        append(r.label ?: "Conditions").append(" [conditions ").append(r.id).append("]:")
        if (r.items.isEmpty()) append(" none")
        r.items.forEach { append("\n").append(condition(it, "- ")) }
        r.add?.let { b ->
            append("\nAdd one: panel_action {id: \"${b.id}\", args: {condition: {expression: \"…\"} or {any: [{subject, fact, op, value}]}}}")
            if (!b.enabled) append(" (disabled${b.hint?.let { ": $it" }.orEmpty()})")
            append("; panel_facts {row: \"${r.id}\"} lists what it can read and checks one")
            r.syntax?.let { append(". Syntax: ").append(it) }
        }
    }

    private fun condition(c: ConditionItem, prefix: String): String {
        val mark = when {
            !c.enabled -> "off"
            c.error != null -> "!"
            c.holds == true -> "✓"
            c.holds == false -> "✗"
            else -> "?"
        }
        val about = listOfNotNull(c.label, c.origin, c.reads?.let { "reads $it" }, c.error?.let { "error: $it" }).joinToString("; ")
        return "$prefix$mark ${c.text}" + (if (about.isEmpty()) "" else " ($about)") +
            c.any.joinToString("") { "\n  " + condition(it, "or ") } + c.buttons().joinToString("") { "  " + button(it) }
    }

    private fun button(b: ButtonRow) = "[button ${b.id}" + (if (b.args.isEmpty()) "" else " args ${PanelJson.json(b.args)}") + "] ${b.label}" +
        (b.confirm?.let { " (asks: $it)" }.orEmpty()) + (if (b.enabled) "" else " (disabled${b.hint?.let { ": $it" }.orEmpty()})") +
        (if (b.enabled) b.hint?.let { " ($it)" }.orEmpty() else "")

    /** A field in a few words: `fuel*: number 0..20 — what it is`. */
    fun field(f: LayoutField): String {
        val type = when (f.type) {
            LayoutField.Type.ENUM -> f.values.joinToString("|")
            LayoutField.Type.TARGET -> "target {type, world, …}"
            else -> f.type.name.lowercase() + when {
                f.min != null && f.max != null -> " ${PanelJson.fmt(f.min!!)}..${PanelJson.fmt(f.max!!)}"
                f.min != null -> " >= ${PanelJson.fmt(f.min!!)}"
                f.max != null -> " <= ${PanelJson.fmt(f.max!!)}"
                else -> ""
            }
        }
        return "${f.name}${if (f.isRequired) "*" else ""}: $type" + (if (f.description.isNotEmpty()) " — ${f.description}" else "")
    }

    private fun value(f: LayoutField, v: Any?): String = when {
        v == null -> "unset"
        v is PanelTarget -> PanelJson.target(v).toString()
        else -> PanelJson.value(f, v).let { if (it.isJsonPrimitive && it.asJsonPrimitive.isString) it.asString else it.toString() }
    }

    /** An edit's, action's or unlink's result: done or refused, its message and field errors, then the panel as it is now. */
    fun result(what: String, o: Panels.Outcome): String = buildString {
        val r: PanelResult = o.result
        append(if (r.isOk) "Done" else "Refused").append(": ").append(what)
        r.message?.let { append(". ").append(it) }
        r.errors.forEach { (k, v) -> append("\n  ").append(k).append(": ").append(v) }
        o.panel?.let { append("\n\n").append(panel(it, o.served.entry, o.served.target)) }
    }

    fun link(from: PanelTarget, to: PanelTarget, r: LinkResult): String = when (r.status) {
        LinkResult.Status.DONE -> "Linked $from → $to" + (r.message?.let { ": $it" }.orEmpty())
        LinkResult.Status.REFUSED -> "Refused to link $from → $to" + (r.message?.let { ": $it" }.orEmpty())
        LinkResult.Status.CHOOSE -> "${r.message}\nCall link_targets again with choice, one of:\n" + r.options.joinToString("\n") { o ->
            "- ${o.id}: ${o.label}" + (o.hint?.let { " ($it)" }.orEmpty())
        }
        LinkResult.Status.BUILD -> "Not linked: " + (r.message ?: "no link of $from fits") + ". In the mod its condition builder opens with " +
            "${r.condition?.let { PanelJson.json(it.toMap()) }}: add it (or another) through its conditions row ${r.row} (panel_get ${r.target ?: from})"
    }

    /**
     * What a relink did: done, refused, a question, or the condition to add (then the old relation to unlink, [unlink]
     * its arguments).
     */
    fun relink(what: String, r: LinkResult, unlink: String): String = when (r.status) {
        LinkResult.Status.DONE -> "Done: $what" + (r.message?.let { ". $it" }.orEmpty())
        LinkResult.Status.REFUSED -> "Refused: $what" + (r.message?.let { ". $it" }.orEmpty())
        LinkResult.Status.CHOOSE -> "${r.message}\nCall relink again with choice, one of:\n" + r.options.joinToString("\n") { o ->
            "- ${o.id}: ${o.label}" + (o.hint?.let { " ($it)" }.orEmpty())
        }
        LinkResult.Status.BUILD -> "Not changed yet: " + (r.message ?: "no link fits as it is") + ". Add the condition " +
            "${r.condition?.let { PanelJson.json(it.toMap()) }} through the conditions row ${r.row} of ${r.target ?: "its panel"} " +
            "(panel_action with args {condition}), then unlink $unlink removes the old relation."
    }

    /** A relation in one line: `provider id: from → to "label"`, what it reads when that says more, what may change of it. */
    fun relation(f: Panels.Found) = "${f.entry.id} ${f.relation.id}: ${f.relation.from} → ${f.relation.to}" + (f.relation.label?.let { " \"$it\"" }.orEmpty()) +
        (f.relation.detail?.takeIf { it != f.relation.label }?.let { " ($it)" }.orEmpty()) + (f.relation.state?.let { " [${it.key}]" }.orEmpty()) +
        (if (f.relation.changes.isEmpty()) "" else " relink: " + f.relation.changes.sortedBy { it.ordinal }.joinToString("|") { it.key })
}
