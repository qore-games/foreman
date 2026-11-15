package games.qore.foreman.server.api

/**
 * What a [PanelProvider] shows about a target, as data Foreman draws (the mod beside the world, the AI as text): a
 * [title], a [subtitle] and [sections] of [Row]s. Immutable: each call returns a changed copy.
 *
 * ```java
 * Panel.of("Generator").subtitle("Running")
 *     .section(Section.of("Power")
 *         .row(Row.value("Fuel", "12 / 20").color(0xE0B040))
 *         .row(Row.field(LayoutField.number("throttle").range(0, 1), 0.5))
 *         .row(Row.button("stop", "Stop").style(ButtonRow.Style.DANGER).confirm("Stop it?")));
 * ```
 */
class Panel private constructor(val title: String, val subtitle: String?, val sections: List<Section>, val status: Status?) {
    fun subtitle(subtitle: String?) = Panel(title, subtitle?.takeIf { it.isNotEmpty() }, sections, status)

    fun section(section: Section) = Panel(title, subtitle, sections + section, status)

    fun sections(sections: List<Section>) = Panel(title, subtitle, this.sections + sections, status)

    /** Its status line, shown under its title above every tab (`Blocked: the switch is off`), in its level's colour. */
    fun status(status: Status?) = Panel(title, subtitle, sections, status)

    /** Its rows, section after section. */
    fun rows(): List<Row> = sections.flatMap { it.rows }

    /** Its tabs, in the order its sections name them first; empty when none does (one page). */
    fun tabs(): List<String> = sections.mapNotNull { it.tab }.distinct()

    /** It as text: its title, status, subtitle, and each section's title and rows' [Row.text]. */
    fun text(): String = buildString {
        append(title)
        status?.let { append('\n').append(it.text()) }
        subtitle?.let { append('\n').append(it) }
        sections.forEach { s ->
            append('\n')
            if (s.title.isNotEmpty()) append('\n').append(s.title)
            s.rows.forEach { append('\n').append(it.text()) }
        }
    }

    companion object {
        @JvmStatic fun of(title: String) = Panel(title, null, emptyList(), null)

        @JvmStatic fun of(title: String, vararg sections: Section) = Panel(title, null, sections.toList(), null)
    }
}

/**
 * A part of a [Panel]: a [title] (empty: none), an [id] of yours, its [rows], and the [tab] it shows on: a panel whose
 * sections name tabs shows a tab bar, each tab its sections, and the sections without a tab on every tab. Without tabs,
 * a panel is one page. Immutable: each call returns a changed copy.
 */
class Section private constructor(val title: String, val id: String?, val rows: List<Row>, val tab: String?) {
    fun id(id: String) = Section(title, id, rows, tab)

    fun row(row: Row) = Section(title, id, rows + row, tab)

    fun rows(rows: List<Row>) = Section(title, id, this.rows + rows, tab)

    /** The tab it shows on (`Status`, `Setup`): a few words, the same for every section of that tab. */
    fun tab(tab: String?) = Section(title, id, rows, tab?.trim()?.takeIf { it.isNotEmpty() })

    companion object {
        @JvmStatic fun of(title: String) = Section(title, null, emptyList(), null)

        @JvmStatic fun of(title: String, vararg rows: Row) = Section(title, null, rows.toList(), null)
    }
}

/**
 * A line of a [Section]: a paragraph ([text]), a read-only [value], an editable [field], a [button], a [list] of items
 * with their own buttons or [conditions] (with their builder). Each has a text form ([text]), what the AI reads and what a mod that doesn't know its [kind]
 * draws. Immutable: each call returns a changed copy.
 */
sealed class Row {
    /** `text`, `value`, `field`, `button`, `list` or `conditions`. */
    abstract val kind: String

    /** It as text. */
    abstract fun text(): String

    companion object {
        /** A paragraph. */
        @JvmStatic fun text(text: String) = TextRow(text)

        /** A label and a read-only value: `Fuel: 12 / 20`. */
        @JvmStatic fun value(label: String, value: String) = ValueRow(label, value, null, null)

        /**
         * An editable value: [field] (a [LayoutField]: its name is the key [PanelProvider.edit] gets, its label, type,
         * choices and range what the builder sees) holding [value] now: a String, Number, Boolean or [PanelTarget] as its
         * type holds, a colour as `0xRRGGBB` or `"#rrggbb"`, null when unset.
         */
        @JvmStatic fun field(field: LayoutField, value: Any?) = FieldRow(field, value, null)

        /** A button: pressing it calls [PanelProvider.action] with [id]. */
        @JvmStatic fun button(id: String, label: String): ButtonRow {
            require(id.isNotBlank()) { "A button needs an id" }
            return ButtonRow(id, label, ButtonRow.Style.NORMAL, null, emptyMap(), true, null)
        }

        /** Items, each with its own buttons (the lamps a generator powers, each with an unlink button). */
        @JvmStatic fun list(items: List<ListItem>) = ListRow(items.toList())

        @JvmStatic fun list(vararg items: ListItem) = ListRow(items.toList())

        /** Conditions with whether each holds now, and a builder adding new ones ([ConditionsRow]); [id] names it to your provider. */
        @JvmStatic fun conditions(id: String): ConditionsRow {
            require(id.isNotBlank()) { "A conditions row needs an id" }
            return ConditionsRow(id, null, emptyList(), null, false, null)
        }
    }
}

/** A paragraph. */
class TextRow internal constructor(val content: String) : Row() {
    override val kind get() = "text"

    override fun text() = content
}

/** A [label] and a read-only [value], in a [color] (`0xRRGGBB`), with a [hint] shown on hover. */
class ValueRow internal constructor(val label: String, val value: String, val color: Int?, val hint: String?) : Row() {
    override val kind get() = "value"

    fun color(rgb: Int) = ValueRow(label, value, rgb and 0xFFFFFF, hint)

    fun hint(hint: String) = ValueRow(label, value, color, hint)

    override fun text() = "$label: $value"
}

/** An editable [value] of [field], with a [hint] shown on hover. */
class FieldRow internal constructor(val field: LayoutField, val value: Any?, val hint: String?) : Row() {
    override val kind get() = "field"

    fun hint(hint: String) = FieldRow(field, value, hint)

    override fun text() = "${field.label}: ${value(value)} (field ${field.name})"

    private fun value(v: Any?): String = when {
        v == null -> "unset"
        v is Number && field.type == LayoutField.Type.COLOR -> "#%06x".format(v.toInt() and 0xFFFFFF)
        v is Double && v == Math.floor(v) && !v.isInfinite() -> v.toLong().toString()
        else -> v.toString()
    }
}

/**
 * A button: [id] is what [PanelProvider.action] gets, with [args]; [label] what it reads, its [style], a question to
 * [confirm] first, [enabled] (false: shown greyed, its [hint] saying why), a [hint] shown on hover.
 */
class ButtonRow internal constructor(
    val id: String,
    val label: String,
    val style: Style,
    val confirm: String?,
    val args: Map<String, Any?>,
    val enabled: Boolean,
    val hint: String?,
) : Row() {
    enum class Style {
        NORMAL, PRIMARY, DANGER, GHOST;

        val key: String get() = name.lowercase()
    }

    override val kind get() = "button"

    fun style(style: Style) = ButtonRow(id, label, style, confirm, args, enabled, hint)

    /** Asks [question] before pressing it. */
    fun confirm(question: String) = ButtonRow(id, label, style, question, args, enabled, hint)

    /** What [PanelProvider.action] gets with its id (an item's id, an amount): plain values, as JSON has them. */
    fun args(args: Map<String, Any?>) = ButtonRow(id, label, style, confirm, LinkedHashMap(args), enabled, hint)

    fun enabled(enabled: Boolean) = ButtonRow(id, label, style, confirm, args, enabled, hint)

    fun hint(hint: String) = ButtonRow(id, label, style, confirm, args, enabled, hint)

    override fun text() = "[$label] (button $id" + (if (args.isEmpty()) "" else ", args ${args.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: $v" }}") +
        (if (!enabled) ", disabled" else "") + ")" + (if (!enabled && hint != null) " $hint" else "")
}

/** [items], each with its own buttons. */
class ListRow internal constructor(val items: List<ListItem>) : Row() {
    override val kind get() = "list"

    override fun text() = if (items.isEmpty()) "(none)" else items.joinToString("\n") { "- " + it.text() }
}

/**
 * An item of a [ListRow]: its [id], its [label], a [detail] beside it in a [color], the [target] it is about (another
 * thing in the world: the mod shows where it is, the AI may open its panel), and its [actions]: buttons whose
 * [PanelProvider.action] gets `item` (this id) in its args besides their own.
 */
class ListItem private constructor(
    val id: String,
    val label: String,
    val detail: String?,
    val color: Int?,
    val target: PanelTarget?,
    val actions: List<ButtonRow>,
) {
    fun detail(detail: String) = ListItem(id, label, detail, color, target, actions)

    fun color(rgb: Int) = ListItem(id, label, detail, rgb and 0xFFFFFF, target, actions)

    fun target(target: PanelTarget) = ListItem(id, label, detail, color, target, actions)

    fun actions(vararg buttons: ButtonRow) = ListItem(id, label, detail, color, target, actions + buttons)

    fun actions(buttons: List<ButtonRow>) = ListItem(id, label, detail, color, target, actions + buttons)

    /** Its buttons as pressed: their args with `item` (its id) added. */
    fun buttons(): List<ButtonRow> = actions.map { b -> if ("item" in b.args) b else b.args(b.args + ("item" to id)) }

    fun text() = label + (detail?.let { ": $it" } ?: "") + (target?.let { " ($it)" } ?: "") +
        buttons().joinToString("") { " " + it.text() }

    companion object {
        @JvmStatic fun of(id: String, label: String): ListItem {
            require(id.isNotBlank()) { "A list item needs an id" }
            return ListItem(id, label, null, null, null, emptyList())
        }
    }
}
