package games.qore.foreman.server.api

/**
 * Conditions a thing of yours works by, shown and edited in its panel: each with whether it holds now and the values it
 * read, and a builder adding new ones (pick a subject, one of its facts, a comparison and a value; or type an expression
 * in your own syntax). Foreman knows none of your syntax: the builder sends a [Condition] (built, or typed), you write
 * it and say what it reads ([PanelProvider.check]); the subjects and facts it offers are yours
 * ([PanelProvider.subjects], [PanelProvider.facts]). Immutable: each call returns a changed copy.
 *
 * ```java
 * Row.conditions("requires").label("Works when")
 *     .item(ConditionItem.of("power", "generator.running").label("Power").holds(true).reads("generator.running = true")
 *         .subject(ConditionSubject.of("generator", "Generator 'north'").target(generatorTarget))
 *         .actions(Row.button("disable", "Turn off")))
 *     .add(Row.button("add_requirement", "Add a condition"))
 *     .pick(true);
 * ```
 */
class ConditionsRow internal constructor(
    val id: String,
    val label: String?,
    val items: List<ConditionItem>,
    val add: ButtonRow?,
    val pick: Boolean,
    val syntax: String?,
) : Row() {
    override val kind get() = "conditions"

    fun label(label: String) = ConditionsRow(id, label, items, add, pick, syntax)

    fun item(item: ConditionItem) = ConditionsRow(id, label, items + item, add, pick, syntax)

    fun items(items: List<ConditionItem>) = ConditionsRow(id, label, this.items + items, add, pick, syntax)

    /**
     * The builder's Add: [button]'s action gets `condition` in its args, a [Condition]'s map ([Condition.of] reads it).
     * Without it, the row only shows its conditions.
     */
    fun add(button: ButtonRow) = ConditionsRow(id, label, items, button, pick, syntax)

    /** Whether the builder offers to pick a subject in the world (its target then reaches [PanelProvider.facts]). */
    fun pick(pick: Boolean) = ConditionsRow(id, label, items, add, pick, syntax)

    /** What an expression typed in the builder looks like, in a few words: `<name>.<fact> == <value>, and, or, not`. */
    fun syntax(syntax: String) = ConditionsRow(id, label, items, add, pick, syntax)

    /** Its buttons as pressed: the add button, then each item's (with `item`). */
    fun buttons(): List<ButtonRow> = listOfNotNull(add) + items.flatMap { it.buttons() }

    override fun text() = (label?.let { "$it:" } ?: "Conditions:") +
        (if (items.isEmpty()) " none" else items.joinToString("") { "\n- " + it.text() }) +
        (add?.let { "\n" + it.text() } ?: "")
}

/**
 * A condition of a [ConditionsRow]: its [id], its [text] as your syntax writes it, what it is for ([label]), whether it
 * [holds] now (null: unknown, it isn't read here), the values it [reads] (`switch.on = false`), why it can't be read
 * ([error]), where it comes from ([origin]: `definition`, `slot power`, `added`), whether it is [enabled] for this thing
 * (a default turned off shows struck), the [subjects] it reads (clickable: their panel opens), an OR group's
 * alternatives ([any]: it holds when one does) and its [actions] (buttons getting `item`, this id, in their args).
 */
class ConditionItem private constructor(
    val id: String,
    val text: String,
    val label: String?,
    val holds: Boolean?,
    val reads: String?,
    val error: String?,
    val origin: String?,
    val enabled: Boolean,
    val subjects: List<ConditionSubject>,
    val any: List<ConditionItem>,
    val actions: List<ButtonRow>,
) {
    private fun copy(
        label: String? = this.label, holds: Boolean? = this.holds, reads: String? = this.reads, error: String? = this.error, origin: String? = this.origin,
        enabled: Boolean = this.enabled, subjects: List<ConditionSubject> = this.subjects, any: List<ConditionItem> = this.any,
        actions: List<ButtonRow> = this.actions,
    ) = ConditionItem(id, text, label, holds, reads, error, origin, enabled, subjects, any, actions)

    fun label(label: String) = copy(label = label)

    fun holds(holds: Boolean?) = copy(holds = holds)

    fun reads(reads: String) = copy(reads = reads)

    fun error(error: String) = copy(error = error)

    fun origin(origin: String) = copy(origin = origin)

    fun enabled(enabled: Boolean) = copy(enabled = enabled)

    fun subject(subject: ConditionSubject) = copy(subjects = subjects + subject)

    fun subjects(subjects: List<ConditionSubject>) = copy(subjects = this.subjects + subjects)

    /** An OR group: it holds when one of [alternatives] does (shown under it). */
    fun any(alternatives: List<ConditionItem>) = copy(any = any + alternatives)

    fun actions(vararg buttons: ButtonRow) = copy(actions = actions + buttons)

    fun actions(buttons: List<ButtonRow>) = copy(actions = actions + buttons)

    /** Its buttons as pressed: their args with `item` (its id) added. */
    fun buttons(): List<ButtonRow> = actions.map { b -> if ("item" in b.args) b else b.args(b.args + ("item" to id)) }

    /** It in a line: `✓ switch.on (Power, slot power; reads switch.on = true)` and its buttons. */
    fun text(): String {
        val mark = when {
            !enabled -> "off"
            error != null -> "!"
            holds == true -> "✓"
            holds == false -> "✗"
            else -> "?"
        }
        val about = listOfNotNull(label, origin, reads?.let { "reads $it" }, error?.let { "error: $it" }).joinToString("; ")
        return "$mark $text" + (if (about.isEmpty()) "" else " ($about)") +
            any.joinToString("") { "\n    or " + it.text() } + buttons().joinToString("") { " " + it.text() }
    }

    companion object {
        @JvmStatic fun of(id: String, text: String): ConditionItem {
            require(id.isNotBlank()) { "A condition needs an id" }
            return ConditionItem(id, text, null, null, null, null, null, true, emptyList(), emptyList(), emptyList())
        }
    }
}

/**
 * Something conditions read: its [id] (what your syntax names it by: `generator`, `this`, `@world`), its [label] and
 * [detail] for builders, the [group] the builder lists it under (`This`, `Named`, `World`), and its [target] in the
 * world when it has one (a click selects it).
 */
class ConditionSubject private constructor(val id: String, val label: String, val detail: String?, val group: String?, val target: PanelTarget?) {
    fun detail(detail: String) = ConditionSubject(id, label, detail, group, target)

    fun group(group: String) = ConditionSubject(id, label, detail, group, target)

    fun target(target: PanelTarget?) = ConditionSubject(id, label, detail, group, target)

    companion object {
        @JvmStatic fun of(id: String, label: String) = ConditionSubject(id, label.ifEmpty { id }, null, null, null)
    }
}

/**
 * A fact of a [ConditionSubject] a condition compares: its [name] (`running`, `fuel`), [label], [type] (which
 * comparisons and values the builder offers), a choice's [values], a number's [min] and [max], its value [now] (as
 * text) and a [description].
 */
class ConditionFact private constructor(
    val name: String,
    val type: Type,
    val label: String,
    val values: List<String>,
    val min: Double?,
    val max: Double?,
    val now: String?,
    val description: String,
) {
    enum class Type {
        /** True or false: is, isn't. */
        BOOL,

        /** A number: ==, !=, <, <=, >, >=. */
        NUMBER,

        /** Text: ==, !=. */
        TEXT,

        /** One of [values] (a state): ==, !=. */
        CHOICE;

        val key: String get() = name.lowercase()
    }

    private fun copy(label: String = this.label, min: Double? = this.min, max: Double? = this.max, now: String? = this.now, description: String = this.description) =
        ConditionFact(name, type, label, values, min, max, now, description)

    fun label(label: String) = copy(label = label)

    fun range(min: Double?, max: Double?) = copy(min = min, max = max)

    fun now(now: String?) = copy(now = now)

    fun description(description: String) = copy(description = description)

    companion object {
        private fun of(name: String, type: Type, values: List<String>): ConditionFact {
            require(name.isNotBlank()) { "A fact needs a name" }
            return ConditionFact(name, type, name, values, null, null, null, "")
        }

        @JvmStatic fun bool(name: String) = of(name, Type.BOOL, emptyList())

        @JvmStatic fun number(name: String) = of(name, Type.NUMBER, emptyList())

        @JvmStatic fun text(name: String) = of(name, Type.TEXT, emptyList())

        @JvmStatic fun choice(name: String, values: List<String>) = of(name, Type.CHOICE, values.toList())

        @JvmStatic fun choice(name: String, vararg values: String) = of(name, Type.CHOICE, values.toList())
    }
}

/** A [subject] as conditions name it (a thing picked in the world: under the id your syntax will use) and its [facts]. */
class ConditionFacts private constructor(val subject: ConditionSubject, val facts: List<ConditionFact>) {
    companion object {
        @JvmStatic fun of(subject: ConditionSubject, facts: List<ConditionFact>) = ConditionFacts(subject, facts.toList())
    }
}

/**
 * A condition as the builder sends it: typed in your syntax ([expression]), or built: [terms] of which one must hold
 * (one term: a plain condition; several: an OR group), what it is for ([label], optional). [toMap] and [of] are its JSON.
 */
class Condition private constructor(val expression: String?, val terms: List<Term>, val label: String?) {
    /** A comparison: [subject]'s (a [ConditionSubject] id; with [target] when it was picked in the world) [fact] [op] [value]. */
    class Term(val subject: String, val fact: String, val op: Op, val value: Any?, val target: PanelTarget?) {
        fun toMap(): Map<String, Any?> = linkedMapOf("subject" to subject, "fact" to fact, "op" to op.symbol, "value" to value).apply {
            target?.let { put("target", it.toMap()) }
        }

        override fun toString() = "$subject.$fact ${op.symbol} $value"
    }

    enum class Op(val symbol: String) {
        EQ("=="), NE("!="), LT("<"), LE("<="), GT(">"), GE(">=");

        companion object {
            @JvmStatic fun of(symbol: String): Op = entries.firstOrNull { it.symbol == symbol || it.name.equals(symbol, ignoreCase = true) }
                ?: throw IllegalArgumentException("op is one of ${entries.joinToString { it.symbol }}, not '$symbol'")
        }
    }

    val isTyped get() = expression != null

    fun label(label: String?) = Condition(expression, terms, label?.takeIf { it.isNotBlank() })

    fun toMap(): Map<String, Any?> = LinkedHashMap<String, Any?>().apply {
        if (expression != null) put("expression", expression) else put("any", terms.map { it.toMap() })
        label?.let { put("label", it) }
    }

    override fun toString() = expression ?: terms.joinToString(" or ")

    companion object {
        /** An expression in your syntax, as typed. */
        @JvmStatic fun expression(text: String): Condition {
            require(text.isNotBlank()) { "An empty expression" }
            return Condition(text.trim(), emptyList(), null)
        }

        /** Built: one of [terms] must hold. */
        @JvmStatic fun any(terms: List<Term>): Condition {
            require(terms.isNotEmpty()) { "A condition needs a term" }
            return Condition(null, terms.toList(), null)
        }

        @JvmStatic fun of(vararg terms: Term) = any(terms.toList())

        @JvmStatic @JvmOverloads fun term(subject: String, fact: String, op: Op, value: Any?, target: PanelTarget? = null) = Term(subject, fact, op, value, target)

        /**
         * Its JSON as plain values: `{expression}`, `{any: [{subject, fact, op, value, target?}], label?}`, or one term
         * `{subject, fact, op, value}`. An [IllegalArgumentException] says what is wrong.
         */
        @JvmStatic fun of(map: Map<String, *>): Condition {
            val label = map["label"] as? String
            (map["expression"] as? String)?.let { return expression(it).label(label) }
            val terms = when (val any = map["any"]) {
                is List<*> -> any.map { t -> term(t as? Map<*, *> ?: throw IllegalArgumentException("any holds terms {subject, fact, op, value}")) }
                null -> listOf(term(map))
                else -> throw IllegalArgumentException("any is a list of terms {subject, fact, op, value}")
            }
            return any(terms).label(label)
        }

        private fun term(map: Map<*, *>): Term {
            val subject = map["subject"] as? String ?: throw IllegalArgumentException("A condition is {expression} or {any: [{subject, fact, op, value}]}: subject missing")
            val fact = map["fact"] as? String ?: throw IllegalArgumentException("A term needs its fact")
            val op = Op.of(map["op"] as? String ?: "==")
            @Suppress("UNCHECKED_CAST")
            val target = (map["target"] as? Map<String, *>)?.let { PanelTarget.of(it) }
            return Term(subject, fact, op, map["value"], target)
        }
    }
}

/**
 * What a [Condition] reads now ([PanelProvider.check]): how your syntax writes it ([expression]), whether it [holds]
 * (null: it isn't read here), the values it [reads], or why it can't be ([error]: it doesn't parse, names nothing).
 */
class ConditionCheck private constructor(val expression: String?, val holds: Boolean?, val reads: String?, val error: String?) {
    val isOk get() = error == null

    companion object {
        @JvmStatic @JvmOverloads fun of(expression: String, holds: Boolean?, reads: String? = null) = ConditionCheck(expression, holds, reads, null)

        @JvmStatic @JvmOverloads fun error(error: String, expression: String? = null) = ConditionCheck(expression, null, null, error)
    }
}
