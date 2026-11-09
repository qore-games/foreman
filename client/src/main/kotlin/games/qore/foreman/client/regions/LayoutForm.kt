package games.qore.foreman.client.regions

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.LayoutField
import games.qore.foreman.client.link.LayoutType
import games.qore.foreman.client.link.Layouts
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.network.chat.Component
import java.util.Locale

/**
 * The form of a point's or an area's data for its declared [types], a section each (an area may be of several: its
 * data is one for all), made from their fields (one row per field name, the first type's when two name the same, as
 * the server reads them): a text box (string), a number box with its range
 * (int, number), a switch (bool), choices (enum: buttons, a list when they don't fit), a swatch and a hex box (color), a
 * number with the point's facing a click away ([facing]; yaw), a pick of [world]'s areas (area). A field left empty
 * reads its default (the box's hint); the server's reasons for refusing values show under the fields they name.
 * The panel adds its boxes ([build]), draws it ([draw]) and opens its lists ([open]).
 */
class LayoutForm(
    val types: List<LayoutType>, data: JsonObject, private val world: String, private val facing: () -> Float?, private val open: (PickList) -> Unit,
    from: LayoutForm? = null,
) {
    /** A field of [owner] as edited: [text] in its box, or [set] for a switch, a choice or an area (null: its default). */
    private class Row(val owner: LayoutType, val field: LayoutField) {
        var text = ""
        var set: JsonPrimitive? = null
        var box: EditBox? = null
        var error: String? = null
        val boxed get() = field.type !in PICKED
    }

    private val rows: List<Row> = types.flatMap { t -> t.fields.map { t to it } }.distinctBy { it.second.name }.map { (t, f) ->
        Row(t, f).also { row ->
            val old = from?.rows?.firstOrNull { it.field.name == f.name && it.field.type == f.type }
            if (old != null) {
                row.text = old.text
                row.set = old.set
            } else load(row, data[f.name])
        }
    }

    /** The text boxes, for the panel to take away when the form changes. */
    val boxes: List<EditBox> get() = rows.mapNotNull { it.box }

    private fun load(row: Row, value: JsonElement?) {
        val v = value as? JsonPrimitive ?: return
        if (v is JsonNull) return
        if (row.boxed) row.text = v.double()?.takeIf { !v.isString }?.let(::num) ?: v.content
        else row.set = v
    }

    /** Makes the text boxes with [add] (the screen's widgets): again whenever the screen is laid out. */
    fun build(font: Font, add: (EditBox) -> EditBox) {
        for (row in rows.filter { it.boxed }) {
            val f = row.field
            val w = when (f.type) {
                "string" -> 120
                "color" -> 64
                else -> 56
            }
            row.box = add(EditBox(font, 0, 0, w, 16, Component.literal(f.label.ifEmpty { f.name }))).also { box ->
                box.setMaxLength(if (f.type == "string") 500 else 32)
                defaultText(f)?.let { box.setHint(Component.literal(it)) }
                box.value = row.text
                box.setResponder {
                    row.text = it
                    row.error = null
                }
            }
        }
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
            if (after < x + w - 20) ui.text(ui.fit(type.id + if (type.owner.isNotEmpty()) " · ${type.owner}" else "", x + w - after), after, ry, Theme.FAINT)
            ry += 12
            if (type.description.isNotEmpty()) ry += ui.paragraph(type.description, x, ry, w, Theme.FAINT) + 4
            val own = rows.filter { it.owner === type }
            if (own.isEmpty()) ry += ui.paragraph(if (type.fields.isEmpty()) "It has no fields." else "Its fields are above.", x, ry, w, Theme.FAINT) + 4
            for (row in own) ry += drawRow(ui, row, x, ry, w, top, bottom)
            ry += 2
        }
        return ry - y
    }

    private fun drawRow(ui: Ui, row: Row, x: Int, y: Int, w: Int, top: Int, bottom: Int): Int {
        val f = row.field
        val name = ui.fit(f.label.ifEmpty { f.name }, LABEL_W - 10)
        ui.text(name, x, y + 4, if (row.error != null) Theme.ERROR else Theme.TEXT)
        if (f.required) ui.text("*", x + ui.font.width(name) + 1, y + 4, Theme.WARN)
        ui.hit(x, y, LABEL_W - 4, 16, tooltip(f))
        val cx = x + LABEL_W
        val cw = w - LABEL_W
        when (f.type) {
            "bool" -> toggle(ui, row, cx, y)
            "enum" -> choice(ui, row, cx, y, cw - RESET_W)
            "area" -> area(ui, row, cx, y, cw - RESET_W)
            else -> boxRow(ui, row, cx, y, cw, top, bottom)
        }
        if (!row.boxed && row.set != null) ui.button(x + w - RESET_W + 2, y, RESET_W - 2, 16, "×", Style.GHOST,
            tooltip = if (f.default != null) "Back to the default: ${defaultText(f)}" else "Clear it") {
            row.set = null
            row.error = null
        }
        val error = row.error ?: return 20
        return 18 + ui.paragraph(error, cx, y + 18, cw, Theme.ERROR) + 4
    }

    private fun boxRow(ui: Ui, row: Row, x: Int, y: Int, w: Int, top: Int, bottom: Int) {
        val f = row.field
        val box = row.box ?: return
        val bx = if (f.type == "color") x + 20 else x
        if (f.type == "string") box.setWidth(w)
        box.setPosition(bx, y)
        box.visible = y >= top && y + 16 <= bottom
        val after = bx + box.width + 6
        when (f.type) {
            "color" -> (Layouts.color(row.text) ?: f.default.str()?.let(Layouts::color))?.let { ui.swatch(x, y + 1, 14, it) } ?: ui.card(x, y + 1, 14, 14, Theme.FIELD)
            "yaw" -> ui.button(after, y, 50, 16, "Facing", Style.NORMAL, tooltip = "Use where it faces now") {
                facing()?.let { row.text = num(it.toDouble()); box.value = row.text }
            }
            "int", "number" -> range(f)?.let { ui.text(ui.fit(it, x + w - after), after, y + 4, Theme.FAINT) }
        }
    }

    /** A bool's switch: its value, else its default (faint). */
    private fun toggle(ui: Ui, row: Row, x: Int, y: Int) {
        val on = row.set?.bool() ?: row.field.default.bool() ?: false
        val hover = ui.hovered(x, y, 60, 16)
        ui.card(x, y + 2, 24, 12, if (on) Theme.ACCENT_DARK else Theme.FIELD, if (on || hover) Theme.ACCENT else Theme.BORDER)
        ui.rect(if (on) x + 13 else x + 2, y + 4, 9, 8, if (on) Theme.ACCENT else Theme.MUTED)
        ui.text((if (on) "on" else "off") + if (row.set == null) "  default" else "", x + 30, y + 4, if (row.set == null) Theme.FAINT else Theme.TEXT)
        ui.hit(x, y, 60, 16, null) {
            row.set = JsonPrimitive(!on)
            row.error = null
        }
    }

    /** An enum's choices: buttons when they fit in [w], else a button opening the list. */
    private fun choice(ui: Ui, row: Row, x: Int, y: Int, w: Int) {
        val f = row.field
        val current = row.set?.str() ?: f.default.str()
        val widths = f.values.sumOf { ui.font.width(it) + 15 }
        if (widths <= w) {
            val used = ui.segmented(x, y, f.values, f.values.indexOf(current)) { i ->
                row.set = JsonPrimitive(f.values[i])
                row.error = null
            }
            if (row.set == null && current != null && used + 50 <= w) ui.text("default", x + used + 6, y + 4, Theme.FAINT)
            return
        }
        val label = current?.let { it + if (row.set == null) " (default)" else "" } ?: "Choose…"
        ui.button(x, y, w, 16, "$label ▾", Style.NORMAL) {
            open(PickList(x, y + 18, w.coerceAtLeast(120), f.values.map { PickList.Option(it, it, current = it == current) }) { v ->
                row.set = JsonPrimitive(v)
                row.error = null
            })
        }
    }

    /** An area field: a button opening the list of [world]'s areas. */
    private fun area(ui: Ui, row: Row, x: Int, y: Int, w: Int) {
        val current = row.set?.str() ?: row.field.default.str()
        ui.button(x, y, w, 16, (current ?: "Pick an area") + " ▾", Style.NORMAL, tooltip = "An area of this world") {
            val names = Bridge.areas.filter { it.world == world }.map { it.name }.sorted()
            val options = if (names.isEmpty()) listOf(PickList.Option(null, "No area in $world")) else names.map { PickList.Option(it, it, current = it == current) }
            open(PickList(x, y + 18, w.coerceAtLeast(140), options) { v ->
                row.set = JsonPrimitive(v)
                row.error = null
            })
        }
    }

    /**
     * The data the form holds (the fields given; those left empty read their defaults), or null when a value isn't
     * one its field can take at all (a number box that isn't a number): those rows say why. The server checks the rest.
     */
    fun data(): JsonObject? {
        val out = LinkedHashMap<String, JsonElement>()
        var ok = true
        rows.forEach { it.error = null }
        for (row in rows) {
            val value = if (row.boxed) parse(row) else row.set
            if (row.error != null) ok = false
            value?.let { out[row.field.name] = it }
        }
        return if (ok) JsonObject(out) else null
    }

    private fun parse(row: Row): JsonPrimitive? {
        val text = row.text.trim().ifEmpty { return null }
        return when (row.field.type) {
            "int", "number", "yaw" -> {
                val d = text.replace(',', '.').toDoubleOrNull()
                if (d == null) {
                    row.error = "A number"
                    null
                } else if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
            }
            "color" -> JsonPrimitive(if (Layouts.color(text) != null) "#" + text.removePrefix("#").lowercase() else text)
            else -> JsonPrimitive(text)
        }
    }

    /**
     * Puts the server's reasons ([lines]: `radius: 200 is above 64`, `table is required`) under the fields they name;
     * returns the others.
     */
    fun errors(lines: List<String>): List<String> {
        rows.forEach { it.error = null }
        val rest = ArrayList<String>()
        for (line in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            val row = rows.firstOrNull { r -> FIELD_LINE.any { p -> line.startsWith(p.format(r.field.name)) } }
            if (row == null) {
                rest += line
                continue
            }
            val reason = line.removePrefix("${row.field.name}:").trim().replaceFirstChar { it.uppercase() }
            row.error = row.error?.let { "$it; $reason" } ?: reason
        }
        return rest
    }

    /** The server's refusal [message] shown on the fields it names; what to say besides. */
    fun refused(message: String): String {
        val rest = errors(message.lines()).filter { "doesn't fit" !in it && !it.startsWith("layout_types") }
        return when {
            rows.none { it.error != null } -> message.lines().firstOrNull { it.isNotBlank() }.orEmpty()
            rest.isEmpty() -> "Refused: see the fields in red"
            else -> "Refused: " + rest.joinToString(" · ")
        }
    }

    private fun tooltip(f: LayoutField) = listOfNotNull(
        f.label.takeIf { it.isNotEmpty() && it != f.name }?.let { "$it (${f.name})" } ?: f.name,
        f.description.ifEmpty { null },
        when (f.type) {
            "enum" -> "One of: " + f.values.joinToString()
            "color" -> "A colour, #rrggbb"
            "yaw" -> "Degrees, as Minecraft's yaw (0 south, 90 west): turned with the build"
            "area" -> "The name of an area of this world"
            else -> null
        },
        defaultText(f)?.let { "Default: $it" },
        if (f.required) "Required" else null,
    ).joinToString("\n")

    companion object {
        private const val LABEL_W = 74
        private const val RESET_W = 16
        /** The field types picked rather than typed. */
        private val PICKED = setOf("bool", "enum", "area")
        /** How the server's reasons start, by field name. */
        private val FIELD_LINE = listOf("%s:", "%s is required", "unknown field %s ", "unknown field %s(")

        private fun range(f: LayoutField): String? = when {
            f.min != null && f.max != null -> "${num(f.min)} – ${num(f.max)}"
            f.min != null -> "≥ ${num(f.min)}"
            f.max != null -> "≤ ${num(f.max)}"
            else -> null
        }

        private fun defaultText(f: LayoutField): String? = (f.default as? JsonPrimitive)?.let { d -> d.double()?.takeIf { !d.isString }?.let(::num) ?: d.content }

        fun num(v: Double) = String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

        /** Whether [a] and [b] hold the same values, numbers compared as numbers (`8` and `8.0`). */
        fun same(a: JsonObject, b: JsonObject): Boolean = a.keys == b.keys && a.all { (k, v) ->
            val o = b[k]
            val x = (v as? JsonPrimitive)?.takeIf { !it.isString }?.double()
            val y = (o as? JsonPrimitive)?.takeIf { !it.isString }?.double()
            if (x != null && y != null) x == y else v == o
        }
    }
}
