package games.qore.foreman.server.panels

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import games.qore.foreman.server.api.ButtonRow
import games.qore.foreman.server.api.Condition
import games.qore.foreman.server.api.ConditionCheck
import games.qore.foreman.server.api.ConditionFact
import games.qore.foreman.server.api.ConditionFacts
import games.qore.foreman.server.api.ConditionItem
import games.qore.foreman.server.api.ConditionSubject
import games.qore.foreman.server.api.ConditionsRow
import games.qore.foreman.server.api.FieldRow
import games.qore.foreman.server.api.LayoutField
import games.qore.foreman.server.api.LinkResult
import games.qore.foreman.server.api.ListItem
import games.qore.foreman.server.api.ListRow
import games.qore.foreman.server.api.Panel
import games.qore.foreman.server.api.PanelResult
import games.qore.foreman.server.api.PanelTarget
import games.qore.foreman.server.api.Row
import games.qore.foreman.server.api.Status
import games.qore.foreman.server.api.TextRow
import games.qore.foreman.server.api.ValueRow
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.util.Vector

/**
 * Panels, targets, results and relations as JSON, what the tools and the bridge give (docs/developers/panels.md has the
 * format), and field values from JSON, checked against their field.
 */
object PanelJson {
    private val gson: Gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val COLOR = Regex("#[0-9a-fA-F]{6}")

    /** A value checked against its field: the [value] the provider gets, or what is wrong ([error]). */
    class Parsed(val value: Any?, val error: String?)

    // ---- Targets ----------------------------------------------------------------------------------------------------

    /** [t]'s JSON, with `dim` (its world's key) when its world is loaded: what the mod matches its own by. */
    fun target(t: PanelTarget): JsonObject = (gson.toJsonTree(t.toMap()) as JsonObject).apply {
        t.worldName?.let { name -> runCatching { Bukkit.getWorld(name) }.getOrNull()?.let { addProperty("dim", it.key.toString()) } }
    }

    /** The target [e] names (its JSON object), else a [ToolError] saying what is wrong. */
    fun target(e: JsonElement?, what: String = "target"): PanelTarget {
        if (e == null || !e.isJsonObject) throw ToolError("$what is a target object: {\"type\": \"entity\", \"world\": …, \"uuid\": …}, a block's {type, world, x, y, z}, an object's {type, world, kind, id} or {\"type\": \"global\", \"id\": \"ns:name\"}")
        @Suppress("UNCHECKED_CAST")
        return try {
            PanelTarget.of(plain(e) as Map<String, *>)
        } catch (x: IllegalArgumentException) {
            throw ToolError("$what: ${x.message}")
        }
    }

    // ---- Panels -----------------------------------------------------------------------------------------------------

    /** [p] as JSON; rows of a kind not in [kinds] (what the mod draws, when it said) are sent as their text. */
    fun panel(p: Panel, kinds: Set<String>? = null): JsonObject = JsonObject().apply {
        addProperty("title", p.title)
        p.subtitle?.let { addProperty("subtitle", it) }
        p.status?.let { add("status", status(it)) }
        add("sections", JsonArray().apply {
            p.sections.forEach { s ->
                add(JsonObject().apply {
                    addProperty("title", s.title)
                    s.id?.let { addProperty("id", it) }
                    s.tab?.let { addProperty("tab", it) }
                    add("rows", JsonArray().apply { s.rows.forEach { add(row(it, kinds)) } })
                })
            }
        })
    }

    fun row(r: Row, kinds: Set<String>? = null): JsonObject {
        if (kinds != null && r.kind !in kinds) return JsonObject().apply { addProperty("kind", "text"); addProperty("text", r.text()) }
        return JsonObject().apply {
            addProperty("kind", r.kind)
            addProperty("text", r.text())
            when (r) {
                is TextRow -> Unit
                is ValueRow -> {
                    addProperty("label", r.label)
                    addProperty("value", r.value)
                    r.color?.let { addProperty("color", color(it)) }
                    r.hint?.let { addProperty("hint", it) }
                }
                is FieldRow -> {
                    add("field", field(r.field))
                    add("value", value(r.field, r.value))
                    r.hint?.let { addProperty("hint", it) }
                }
                is ButtonRow -> button(this, r)
                is ListRow -> add("items", JsonArray().apply { r.items.forEach { add(item(it)) } })
                is ConditionsRow -> conditions(this, r)
            }
        }
    }

    /** `{level, word, detail?}`. */
    fun status(s: Status) = JsonObject().apply {
        addProperty("level", s.level.key)
        addProperty("word", s.word)
        s.detail?.let { addProperty("detail", it) }
    }

    private fun conditions(o: JsonObject, r: ConditionsRow) = o.apply {
        addProperty("id", r.id)
        r.label?.let { addProperty("label", it) }
        add("conditions", JsonArray().apply { r.items.forEach { add(condition(it, true)) } })
        r.add?.let { b -> add("add", button(JsonObject().apply { addProperty("kind", "button"); addProperty("text", b.text()) }, b)) }
        if (r.pick) addProperty("pick", true)
        r.syntax?.let { addProperty("syntax", it) }
    }

    private fun condition(c: ConditionItem, top: Boolean): JsonObject = JsonObject().apply {
        addProperty("id", c.id)
        addProperty("text", c.text)
        c.label?.let { addProperty("label", it) }
        c.holds?.let { addProperty("holds", it) }
        c.reads?.let { addProperty("reads", it) }
        c.error?.let { addProperty("error", it) }
        c.origin?.let { addProperty("origin", it) }
        addProperty("enabled", c.enabled)
        if (c.subjects.isNotEmpty()) add("subjects", JsonArray().apply { c.subjects.forEach { add(subject(it)) } })
        if (c.any.isNotEmpty()) add("any", JsonArray().apply { c.any.forEach { add(condition(it, false)) } })
        if (top) add("actions", JsonArray().apply {
            c.buttons().forEach { b -> add(button(JsonObject().apply { addProperty("kind", "button"); addProperty("text", b.text()) }, b)) }
        })
    }

    /** `{id, label, detail?, group?, target?}`. */
    fun subject(s: ConditionSubject) = JsonObject().apply {
        addProperty("id", s.id)
        addProperty("label", s.label)
        s.detail?.let { addProperty("detail", it) }
        s.group?.let { addProperty("group", it) }
        s.target?.let { add("target", target(it)) }
    }

    /** `{subject, facts: [{name, type, label, values?, min?, max?, now?, description?}]}`. */
    fun facts(f: ConditionFacts) = JsonObject().apply {
        add("subject", subject(f.subject))
        add("facts", JsonArray().apply { f.facts.forEach { add(fact(it)) } })
    }

    private fun fact(f: ConditionFact) = JsonObject().apply {
        addProperty("name", f.name)
        addProperty("type", f.type.key)
        addProperty("label", f.label)
        if (f.values.isNotEmpty()) add("values", JsonArray().apply { f.values.forEach { add(it) } })
        f.min?.let { addProperty("min", it) }
        f.max?.let { addProperty("max", it) }
        f.now?.let { addProperty("now", it) }
        if (f.description.isNotEmpty()) addProperty("description", f.description)
    }

    /** `{ok, expression?, holds?, reads?, error?}`. */
    fun check(c: ConditionCheck) = JsonObject().apply {
        addProperty("ok", c.isOk)
        c.expression?.let { addProperty("expression", it) }
        c.holds?.let { addProperty("holds", it) }
        c.reads?.let { addProperty("reads", it) }
        c.error?.let { addProperty("error", it) }
    }

    /** The condition [e] holds (`{expression}`, `{any: [terms]}`, one term), else a [ToolError] saying what is wrong. */
    fun condition(e: JsonElement?, what: String = "condition"): Condition {
        if (e == null || !e.isJsonObject) throw ToolError("$what is {\"expression\": \"…\"} or {\"any\": [{\"subject\", \"fact\", \"op\", \"value\"}]}")
        @Suppress("UNCHECKED_CAST")
        return try {
            Condition.of(plain(e) as Map<String, *>)
        } catch (x: IllegalArgumentException) {
            throw ToolError("$what: ${x.message}")
        }
    }

    private fun button(o: JsonObject, b: ButtonRow) = o.apply {
        addProperty("id", b.id)
        addProperty("label", b.label)
        addProperty("style", b.style.key)
        b.confirm?.let { addProperty("confirm", it) }
        if (b.args.isNotEmpty()) add("args", json(b.args))
        addProperty("enabled", b.enabled)
        b.hint?.let { addProperty("hint", it) }
    }

    private fun item(i: ListItem) = JsonObject().apply {
        addProperty("id", i.id)
        addProperty("label", i.label)
        i.detail?.let { addProperty("detail", it) }
        i.color?.let { addProperty("color", color(it)) }
        i.target?.let { add("target", target(it)) }
        add("actions", JsonArray().apply {
            i.buttons().forEach { b -> add(button(JsonObject().apply { addProperty("kind", "button"); addProperty("text", b.text()) }, b)) }
        })
    }

    /** A field as layout types give it: name, type, label, description, required, default, values, min, max. */
    fun field(f: LayoutField) = JsonObject().apply {
        addProperty("name", f.name)
        addProperty("type", f.type.name.lowercase())
        addProperty("label", f.label)
        addProperty("description", f.description)
        addProperty("required", f.isRequired)
        f.defaultValue?.let { add("default", value(f, it)) }
        if (f.values.isNotEmpty()) add("values", JsonArray().apply { f.values.forEach { add(it) } })
        f.min?.let { addProperty("min", it) }
        f.max?.let { addProperty("max", it) }
    }

    /** A field's value as JSON: a target's object, a colour `#rrggbb`, null when unset. */
    fun value(f: LayoutField, v: Any?): JsonElement = when {
        v == null -> JsonNull.INSTANCE
        f.type == LayoutField.Type.COLOR && v is Number -> JsonPrimitive(color(v.toInt()))
        else -> json(v)
    }

    // ---- Results ----------------------------------------------------------------------------------------------------

    /** `{ok, message?, errors?, panel?}`. */
    fun result(r: PanelResult, panel: Panel?, kinds: Set<String>? = null) = JsonObject().apply {
        addProperty("ok", r.isOk)
        r.message?.let { addProperty("message", it) }
        if (r.errors.isNotEmpty()) add("errors", JsonObject().apply { r.errors.forEach { (k, v) -> addProperty(k, v) } })
        panel?.let { add("panel", panel(it, kinds)) }
    }

    /** `{status, message?, question?, options?, row?, condition?, target?}`. */
    fun link(r: LinkResult) = JsonObject().apply {
        addProperty("status", r.status.name.lowercase())
        r.row?.let { addProperty("row", it) }
        r.condition?.let { add("condition", json(it.toMap())) }
        r.target?.let { add("target", target(it)) }
        if (r.status == LinkResult.Status.CHOOSE) {
            addProperty("question", r.message)
            add("options", JsonArray().apply {
                r.options.forEach { o ->
                    add(JsonObject().apply {
                        addProperty("id", o.id)
                        addProperty("label", o.label)
                        o.hint?.let { addProperty("hint", it) }
                    })
                }
            })
        } else r.message?.let { addProperty("message", it) }
    }

    /** `{id, provider, from, to, from_point?, to_point?, label?, color?, state?, detail?, changes?}`. */
    fun relation(f: Panels.Found) = JsonObject().apply {
        addProperty("id", f.relation.id)
        addProperty("provider", f.entry.id)
        add("from", target(f.relation.from))
        add("to", target(f.relation.to))
        f.from?.let { add("from_point", point(it)) }
        f.to?.let { add("to_point", point(it)) }
        f.relation.label?.let { addProperty("label", it) }
        f.relation.color?.let { addProperty("color", color(it)) }
        f.relation.state?.let { addProperty("state", it.key) }
        f.relation.detail?.let { addProperty("detail", it) }
        if (f.relation.changes.isNotEmpty()) add("changes", JsonArray().apply { f.relation.changes.sortedBy { it.ordinal }.forEach { add(it.key) } })
    }

    fun point(v: Vector) = JsonArray().apply { add(round(v.x)); add(round(v.y)); add(round(v.z)) }

    private fun round(d: Double) = Math.round(d * 1000) / 1000.0

    fun color(rgb: Int) = "#%06x".format(rgb and 0xFFFFFF)

    // ---- Values -----------------------------------------------------------------------------------------------------

    /** A plain value (String, Number, Boolean, List, Map, [PanelTarget]) as JSON. */
    fun json(v: Any?): JsonElement = when (v) {
        null -> JsonNull.INSTANCE
        is PanelTarget -> target(v)
        is Map<*, *> -> JsonObject().apply { v.forEach { (k, x) -> add(k.toString(), json(x)) } }
        is Iterable<*> -> JsonArray().apply { v.forEach { add(json(it)) } }
        is JsonElement -> v
        else -> gson.toJsonTree(v)
    }

    /** [e] as plain values: String, Double, Boolean, List, Map, null. */
    fun plain(e: JsonElement?): Any? = when {
        e == null || e.isJsonNull -> null
        e.isJsonObject -> LinkedHashMap<String, Any?>().apply { e.asJsonObject.entrySet().forEach { (k, v) -> put(k, plain(v)) } }
        e.isJsonArray -> e.asJsonArray.map(::plain)
        e.asJsonPrimitive.isBoolean -> e.asBoolean
        e.asJsonPrimitive.isNumber -> e.asDouble
        else -> e.asString
    }

    /**
     * [e] as the value of [f] its provider gets (a String, Int, Double, Boolean, [PanelTarget], null to clear), or what is
     * wrong with it. Area names aren't checked here (their world's areas are the world tools').
     */
    fun parse(f: LayoutField, e: JsonElement?): Parsed {
        if (e == null || e.isJsonNull) return if (f.isRequired) Parsed(null, "${f.name} is required") else Parsed(null, null)
        val p = e as? JsonPrimitive
        fun ok(v: Any?) = Parsed(v, null)
        fun bad(why: String) = Parsed(null, why)
        fun range(d: Double, v: Any): Parsed = when {
            f.min != null && d < f.min!! -> bad("at least ${fmt(f.min!!)}")
            f.max != null && d > f.max!! -> bad("at most ${fmt(f.max!!)}")
            else -> ok(v)
        }
        val number = p?.takeIf { it.isNumber }?.asDouble?.takeIf { it.isFinite() }
        return when (f.type) {
            LayoutField.Type.STRING, LayoutField.Type.AREA -> if (p?.isString == true) ok(p.asString) else bad("text expected")
            LayoutField.Type.INT -> when {
                number == null || number % 1.0 != 0.0 || Math.abs(number) > Int.MAX_VALUE -> bad("a whole number expected")
                else -> range(number, number.toInt())
            }
            LayoutField.Type.NUMBER, LayoutField.Type.YAW -> if (number == null) bad("a number expected") else range(number, number)
            LayoutField.Type.BOOL -> if (p?.isBoolean == true) ok(p.asBoolean) else bad("true or false expected")
            LayoutField.Type.ENUM -> if (p?.isString == true && p.asString in f.values) ok(p.asString) else bad("one of ${f.values.joinToString()} expected")
            LayoutField.Type.COLOR -> if (p?.isString == true && p.asString.matches(COLOR)) ok(p.asString.lowercase()) else bad("a colour \"#rrggbb\" expected")
            LayoutField.Type.TARGET -> try {
                ok(target(e))
            } catch (x: ToolError) {
                bad(x.message.orEmpty())
            }
        }
    }

    fun fmt(d: Double): String = java.math.BigDecimal(d).round(java.math.MathContext(12)).stripTrailingZeros().toPlainString()
}
