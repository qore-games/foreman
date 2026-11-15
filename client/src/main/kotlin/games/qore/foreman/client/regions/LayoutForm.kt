package games.qore.foreman.client.regions

import games.qore.foreman.client.link.LayoutField
import games.qore.foreman.client.link.LayoutType
import games.qore.foreman.client.ui.kit.Popover
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import java.util.Locale

/**
 * The form of a point's, an area's or an object's data for its declared [types], a section each (an area may be of
 * several: its data is one for all), made from their fields (one row per field name, the first type's when two name the
 * same, as the server reads them), each a [FieldInput], those of a group under its heading. A field left empty reads its
 * default (the box's hint); the server's reasons for refusing values show under the fields they name. It knows which
 * fields the builder changed ([changes]), follows new values in the others ([follow]) and takes the choices the server
 * gives fields that depend on others ([choices]). The panel adds its boxes ([build]), draws it ([draw], or field by
 * field: [drawField]) and opens its lists ([open]).
 */
class LayoutForm(
    val types: List<LayoutType>, data: JsonObject, world: String, facing: () -> Float?, open: (Popover) -> Unit,
    from: LayoutForm? = null,
    /** Picks a target field's value in the world ([what] it is), then gives it (null: none); null where targets can't be picked. */
    pickTarget: ((what: String, done: (JsonObject?) -> Unit) -> Unit)? = null,
    /** What a target is, for people. */
    describe: (JsonObject) -> String = { it.toString() },
) {
    /** A field of [owner] as edited. */
    private class Row(val owner: LayoutType, val input: FieldInput) {
        val field get() = input.field
    }

    private val rows: List<Row> = types.flatMap { t -> t.fields.map { t to it } }.distinctBy { it.second.name }.map { (t, f) ->
        val pick = pickTarget?.let { p -> { done: (JsonObject?) -> Unit -> p(f.label.ifEmpty { f.name }, done) } }
        Row(t, FieldInput(f, world, facing, open, pick, describe)).also { row ->
            val old = from?.rows?.firstOrNull { it.field.name == f.name && it.field.type == f.type }
            if (old != null) row.input.copy(old.input) else row.input.load(data[f.name])
        }
    }

    /** Each field's value as the form was made or last [followed][follow]: what [changes] compares with. */
    private val initial = HashMap<String, JsonElement?>().apply { rows.forEach { put(it.field.name, it.input.value()) } }

    /** Its fields, in the order they show. */
    val fields: List<LayoutField> get() = rows.map { it.field }

    /** The text boxes, for the panel to take away when the form changes. */
    val boxes: List<EditBox> get() = rows.mapNotNull { it.input.box }

    /** Makes the text boxes with [add] (the screen's widgets): again whenever the screen is laid out. */
    fun build(font: Font, add: (EditBox) -> EditBox) {
        rows.forEach { it.input.build(font, add) }
    }

    /**
     * Draws a section per type from [y] in [w] at [x] (its name, what it is, its fields), the boxes shown only between
     * [top] and [bottom] (a scrolling body); returns the height taken.
     */
    fun draw(ui: Ui, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int {
        var ry = y
        for (type in types) {
            ui.rect(x, ry, w, 1, Theme.BORDER)
            ry += 6
            val nx = x + if (type.argb != null) 12 else 0
            type.argb?.let { ui.swatch(x, ry, 8, it) }
            ui.text(ui.fit(type.name, w - (nx - x)), nx, ry, Theme.TEXT)
            val after = nx + ui.font.width(type.name) + 8
            val id = type.id + if (type.owner.isNotEmpty()) " · ${type.owner}" else ""
            if (after < x + w - 20) ui.text(ui.fit(id, x + w - after), after, ry, Theme.FAINT)
            // Cut short, the name and id show whole on hover.
            if (after + ui.font.width(id) > x + w) ui.hit(x, ry - 1, w, 10, "${type.name}\n$id")
            ry += 12
            if (type.description.isNotEmpty()) ry += ui.paragraph(type.description, x, ry, w, Theme.FAINT) + 4
            val own = rows.filter { it.owner === type }
            if (own.isEmpty()) ry += ui.paragraph(if (type.fields.isEmpty()) "It has no fields." else "Its fields are above.", x, ry, w, Theme.FAINT) + 4
            for (row in own.filter { it.field.group == null }) ry += row.input.draw(ui, x, ry, w, top, bottom)
            for ((group, grouped) in own.filter { it.field.group != null }.groupBy { it.field.group!! }) {
                ry += heading(ui, group, x, ry + 2, w) + 2
                for (row in grouped) ry += row.input.draw(ui, x, ry, w, top, bottom)
            }
            ry += 2
        }
        return ry - y
    }

    /** Draws the field [name] at [x], [y] in [w] (its box only between [top] and [bottom]); returns the height taken. */
    fun drawField(ui: Ui, name: String, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int =
        rows.firstOrNull { it.field.name == name }?.input?.draw(ui, x, y, w, top, bottom) ?: 0

    /** Hides the box of the field [name] (a field the panel doesn't draw this frame). */
    fun hide(name: String) {
        rows.firstOrNull { it.field.name == name }?.input?.box?.visible = false
    }

    /**
     * The fields the builder changed since the form was made or last [followed][follow], with their values (null: cleared,
     * its default), or null when a value isn't one its field can take at all: those rows say why.
     */
    fun changes(): JsonObject? {
        val out = LinkedHashMap<String, JsonElement>()
        var ok = true
        for (row in rows) {
            val value = row.input.value()
            if (row.input.error != null) ok = false
            if (!sameValue(value, initial[row.field.name])) out[row.field.name] = value ?: JsonNull
        }
        return if (ok) JsonObject(out) else null
    }

    /** The fields the builder hasn't changed take their values in [data] (the object as the server has it now). */
    fun follow(data: JsonObject) {
        for (row in rows) {
            val name = row.field.name
            val box = row.input.box
            if (box?.isFocused == true || !sameValue(row.input.peek(), initial[name])) continue
            row.input.load(data[name])
            initial[name] = row.input.peek()
        }
    }

    /** What the field [name] holds now, as typed or picked; nothing is checked. */
    fun peek(name: String): JsonElement? = rows.firstOrNull { it.field.name == name }?.input?.peek()

    /** What every field holds now (those left empty left out), unchecked: a draft of the data. */
    fun peeked(): JsonObject = JsonObject(rows.mapNotNull { r -> r.input.peek()?.let { r.field.name to it } }.toMap())

    /**
     * The choices the server gives the fields that depend on others, by field name; with [reset], a field whose value
     * isn't among them any more goes back to its default (the builder changed what it depends on).
     */
    fun choices(given: Map<String, List<String>>, reset: Boolean) {
        for ((name, values) in given) {
            val input = rows.firstOrNull { it.field.name == name }?.input ?: continue
            input.choices = values
            val current = input.set?.str()
            if (reset && current != null && current !in values) input.set = null
        }
    }

    /**
     * The data the form holds (the fields given; those left empty read their defaults), or null when a value isn't
     * one its field can take at all (a number box that isn't a number): those rows say why. The server checks the rest.
     */
    fun data(): JsonObject? {
        val out = LinkedHashMap<String, JsonElement>()
        var ok = true
        for (row in rows) {
            val value = row.input.value()
            if (row.input.error != null) ok = false
            value?.let { out[row.field.name] = it }
        }
        return if (ok) JsonObject(out) else null
    }

    /**
     * Puts the server's reasons ([lines]: `radius: 200 is above 64`, `table is required`) under the fields they name;
     * returns the others.
     */
    fun errors(lines: List<String>): List<String> {
        rows.forEach { it.input.error = null }
        val rest = ArrayList<String>()
        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            val row = rows.firstOrNull { r -> FIELD_LINE.any { p -> line.startsWith(p.format(r.field.name)) } }
            if (row == null) {
                rest += line
                continue
            }
            val reason = line.removePrefix("${row.field.name}:").trim().replaceFirstChar { it.uppercase() }
            row.input.error = row.input.error?.let { "$it; $reason" } ?: reason
        }
        return rest
    }

    /** The server's refusal [message] shown on the fields it names; what to say besides. */
    fun refused(message: String): String {
        val rest = errors(message.lines()).filter { "doesn't fit" !in it && !it.startsWith("layout_types") }
        return when {
            rows.none { it.input.error != null } -> message.lines().firstOrNull { it.isNotBlank() }.orEmpty()
            rest.isEmpty() -> "Refused: see the fields in red"
            else -> "Refused: " + rest.joinToString(" · ")
        }
    }

    companion object {
        /** A group's heading in a form, its title; returns the height taken. */
        fun heading(ui: Ui, title: String, x: Int, y: Int, w: Int): Int {
            ui.text(ui.fit(title, w), x, y, Theme.ACCENT)
            return 13
        }

        /** Whether [a] and [b] are the same value, numbers compared as numbers. */
        fun sameValue(a: JsonElement?, b: JsonElement?): Boolean {
            if (a == null || a is JsonNull) return b == null || b is JsonNull
            val x = (a as? JsonPrimitive)?.takeIf { !it.isString }?.double()
            val y = (b as? JsonPrimitive)?.takeIf { !it.isString }?.double()
            return if (x != null && y != null) x == y else a == b
        }

        /** How the server's reasons start, by field name. */
        private val FIELD_LINE = listOf("%s:", "%s is required", "unknown field %s ", "unknown field %s(")

        fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

        /** Whether [a] and [b] hold the same values, numbers compared as numbers (`8` and `8.0`). */
        fun same(a: JsonObject, b: JsonObject): Boolean = a.keys == b.keys && a.all { (k, v) -> sameValue(v, b[k]) }
    }
}
