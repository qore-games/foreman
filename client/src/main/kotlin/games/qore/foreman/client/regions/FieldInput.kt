package games.qore.foreman.client.regions

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.LayoutField
import games.qore.foreman.client.link.Layouts
import games.qore.foreman.client.ui.kit.ColorPicker
import games.qore.foreman.client.ui.kit.Popover
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

/**
 * One [field] being edited, a row of a [form][LayoutForm] or of a plugin's panel: its label, then a text box (string),
 * a number box with its range (int, number), a switch (bool), choices (enum: buttons, a list when they don't fit), a
 * swatch opening a colour picker and a hex box (color), a number with the facing a click away ([facing]; yaw), a pick
 * of [world]'s areas (area), or what it points at and a button picking it in the world ([pickTarget]; target). Empty,
 * it reads its default (the box's hint). [open] opens its lists; [onPicked] runs when a picked value changes.
 */
class FieldInput(
    val field: LayoutField, private val world: String, private val facing: () -> Float?, private val open: (Popover) -> Unit,
    /** Picks a target in the world, then gives it (null: none picked); null where targets can't be picked. */
    private val pickTarget: (((JsonObject?) -> Unit) -> Unit)? = null,
    /** What a target is, for people. */
    private val describe: (JsonObject) -> String = { it.toString() },
) {
    /** What its box holds. */
    var text = ""
    /** A switch's, choice's, area's or target's value; null: its default. */
    var set: JsonElement? = null
    var box: EditBox? = null
    var error: String? = null
    var onPicked: (() -> Unit)? = null
    /** An enum's choices as the server gives them for the form's values now (a field that depends on others); null: its values. */
    var choices: List<String>? = null

    /** Typed in a box, rather than picked. */
    val boxed get() = field.type !in PICKED

    /** Shows [value] (null: empty, its default). */
    fun load(value: JsonElement?) {
        text = ""
        set = null
        if (value is JsonObject && field.type == "target") set = value
        val v = value as? JsonPrimitive ?: return box()
        if (v is JsonNull) return box()
        if (boxed) text = v.double()?.takeIf { !v.isString }?.let(::num) ?: v.content
        else set = v
        box()
    }

    private fun box() {
        box?.let { if (it.value != text) it.value = text }
    }

    /** What [other] holds, typed or picked. */
    fun copy(other: FieldInput) {
        text = other.text
        set = other.set
    }

    /** Makes its text box, when it has one, with [add] (the screen's widgets): again whenever the screen is laid out. */
    fun build(font: Font, add: (EditBox) -> EditBox): EditBox? {
        if (!boxed) return null
        val f = field
        val w = when (f.type) {
            "string" -> 120
            "color" -> 64
            else -> 56
        }
        return add(EditBox(font, 0, 0, w, 16, Component.literal(f.label.ifEmpty { f.name }))).also { box ->
            box.setMaxLength(if (f.type == "string") 500 else 32)
            defaultText(f)?.let { box.setHint(Component.literal(it)) }
            box.value = text
            box.setResponder {
                text = it
                error = null
            }
            this.box = box
        }
    }

    /** Draws it at [x], [y] in [w], its box shown only between [top] and [bottom] (a scrolling body); returns its height. */
    fun draw(ui: Ui, x: Int, y: Int, w: Int, top: Int, bottom: Int, busy: Boolean = false): Int {
        val f = field
        val labelW = labelWidth(w)
        val name = ui.fit(f.label.ifEmpty { f.name }, labelW - 10)
        ui.text(name, x, y + 4, if (error != null) Theme.ERROR else Theme.TEXT)
        if (f.required) ui.text("*", x + ui.font.width(name) + 1, y + 4, Theme.WARN)
        if (busy) ui.text("…", x + ui.font.width(name) + if (f.required) 7 else 1, y + 4, Theme.MUTED)
        ui.hit(x, y, labelW - 4, 16, tooltip())
        val cx = x + labelW
        val cw = w - labelW
        when (f.type) {
            "bool" -> toggle(ui, cx, y)
            "enum" -> choice(ui, cx, y, cw - RESET_W)
            "area" -> area(ui, cx, y, cw - RESET_W)
            "target" -> target(ui, cx, y, cw - RESET_W)
            else -> boxRow(ui, cx, y, cw, top, bottom)
        }
        // A required field without a default has nothing to go back to.
        if (!boxed && set != null && (f.default != null || !f.required)) ui.button(x + w - RESET_W + 2, y, RESET_W - 2, 16, "×", Style.GHOST,
            tooltip = if (f.default != null) "Back to the default: ${defaultText(f)}" else "Clear it") {
            set = null
            error = null
            onPicked?.invoke()
        }
        val error = error ?: return 20
        return 18 + ui.paragraph(error, cx, y + 18, cw, Theme.ERROR) + 4
    }

    private fun picked(value: JsonElement?) {
        set = value
        error = null
        onPicked?.invoke()
    }

    private fun boxRow(ui: Ui, x: Int, y: Int, w: Int, top: Int, bottom: Int) {
        val f = field
        val box = box ?: return
        val bx = if (f.type == "color") x + 20 else x
        if (f.type == "string") box.setWidth(w)
        box.setPosition(bx, y)
        box.visible = y >= top && y + 16 <= bottom
        val after = bx + box.width + 6
        when (f.type) {
            "color" -> colorSwatch(ui, x, y)
            "yaw" -> ui.button(after, y, 50, 16, "Facing", Style.NORMAL, tooltip = "Use where it faces now") {
                facing()?.let { text = num(it.toDouble()); box.value = text }
            }
            "int", "number" -> range(f)?.let { ui.text(ui.fit(it, x + w - after), after, y + 4, Theme.FAINT) }
        }
    }

    /** A colour's swatch (its value, else its default): a click opens the picker, which writes the hex box as it goes. */
    private fun colorSwatch(ui: Ui, x: Int, y: Int) {
        val default = field.default.str()?.let(Layouts::color)
        val shown = Layouts.color(text) ?: default
        if (ui.hovered(x, y, 16, 16)) ui.rect(x - 1, y, 16, 16, Theme.ACCENT)
        if (shown != null) ui.swatch(x, y + 1, 14, shown) else ui.card(x, y + 1, 14, 14, Theme.FIELD)
        ui.hit(x - 1, y, 16, 16, "Pick a colour") {
            open(ColorPicker(x, y + 18, Layouts.color(text), default) { rgb ->
                val hex = ColorPicker.hex(rgb)
                text = hex
                error = null
                box?.value = hex
            })
        }
    }

    /** A bool's switch: its value, else its default (faint). */
    private fun toggle(ui: Ui, x: Int, y: Int) {
        val on = set?.bool() ?: field.default.bool() ?: false
        val hover = ui.hovered(x, y, 60, 16)
        ui.card(x, y + 2, 24, 12, if (on) Theme.ACCENT_DARK else Theme.FIELD, if (on || hover) Theme.ACCENT else Theme.BORDER)
        ui.rect(if (on) x + 13 else x + 2, y + 4, 9, 8, if (on) Theme.ACCENT else Theme.MUTED)
        ui.text((if (on) "on" else "off") + if (set == null) "  default" else "", x + 30, y + 4, if (set == null) Theme.FAINT else Theme.TEXT)
        ui.hit(x, y, 60, 16, null) { picked(JsonPrimitive(!on)) }
    }

    /** An enum's choices ([choices], else its values): buttons when they fit in [w], else a button opening the list. */
    private fun choice(ui: Ui, x: Int, y: Int, w: Int) {
        val f = field
        val values = choices ?: f.values
        val current = set?.str() ?: f.default.str()
        val widths = values.sumOf { ui.font.width(it) + 15 }
        if (widths <= w && (current == null || current in values)) {
            val used = ui.segmented(x, y, values, values.indexOf(current)) { i -> picked(JsonPrimitive(values[i])) }
            if (set == null && current != null && used + 50 <= w) ui.text("default", x + used + 6, y + 4, Theme.FAINT)
            return
        }
        val label = current?.let { it + if (set == null) " (default)" else if (it !in values) " (not offered)" else "" } ?: "Choose…"
        ui.button(x, y, w, 16, "$label ▾", Style.NORMAL) {
            open(PickList(x, y + 18, w.coerceAtLeast(120), values.map { PickList.Option(it, it, current = it == current) }) { v -> picked(JsonPrimitive(v)) })
        }
    }

    /** An area field: a button opening the list of [world]'s areas. */
    private fun area(ui: Ui, x: Int, y: Int, w: Int) {
        val current = set?.str() ?: field.default.str()
        ui.button(x, y, w, 16, (current ?: "Pick an area") + " ▾", Style.NORMAL, tooltip = "An area of this world") {
            val names = Bridge.areas.filter { it.world == world }.map { it.name }.sorted()
            val options = if (names.isEmpty()) listOf(PickList.Option(null, "No area in $world")) else names.map { PickList.Option(it, it, current = it == current) }
            open(PickList(x, y + 18, w.coerceAtLeast(140), options) { v -> picked(JsonPrimitive(v)) })
        }
    }

    /** A target field: what it points at, and a button picking another in the world. */
    private fun target(ui: Ui, x: Int, y: Int, w: Int) {
        val current = set as? JsonObject ?: field.default as? JsonObject
        val pickW = 40
        ui.card(x, y, w - pickW - 4, 16, Theme.FIELD)
        val shown = current?.let(describe) ?: "None"
        ui.text(ui.fit(shown + if (set == null && current != null) " (default)" else "", w - pickW - 12), x + 4, y + 4, if (set == null) Theme.FAINT else Theme.TEXT)
        ui.hit(x, y, w - pickW - 4, 16, current?.let { shown })
        val pick = pickTarget
        ui.button(x + w - pickW, y, pickW, 16, "Pick", Style.NORMAL, pick != null,
            tooltip = "Aim at it in the world and click it (right click or Esc: back without one)") {
            pick?.invoke { picked -> if (picked != null) picked(picked) }
        }
    }

    /**
     * Its value as typed or picked (null: empty, its default), or null with [error] said when the box holds what its
     * field can't take at all (a number box that isn't a number).
     */
    fun value(): JsonElement? {
        error = null
        return read { error = it }
    }

    /** Its value as [value] reads it, without saying anything of it: what tells whether it changed. */
    fun peek(): JsonElement? = read {}

    private fun read(wrong: (String) -> Unit): JsonElement? {
        if (!boxed) return set
        val text = text.trim().ifEmpty { return null }
        return when (field.type) {
            "int", "number", "yaw" -> {
                val d = text.replace(',', '.').toDoubleOrNull()
                if (d == null) {
                    wrong("A number")
                    null
                } else if (d % 1.0 == 0.0 && kotlin.math.abs(d) < 1e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
            }
            "color" -> JsonPrimitive(if (Layouts.color(text) != null) "#" + text.removePrefix("#").lowercase() else text)
            else -> JsonPrimitive(text)
        }
    }

    private fun tooltip() = listOfNotNull(
        field.label.takeIf { it.isNotEmpty() && it != field.name }?.let { "$it (${field.name})" } ?: field.name,
        field.description.ifEmpty { null },
        when (field.type) {
            "enum" -> "One of: " + (choices ?: field.values).joinToString()
            "color" -> "A colour, #rrggbb"
            "yaw" -> "Degrees, as Minecraft's yaw (0 south, 90 west): turned with the build"
            "area" -> "The name of an area of this world"
            "target" -> "Something in the world: an entity, a block or an object"
            else -> null
        },
        defaultText(field)?.let { "Default: $it" },
        if (field.required) "Required" else null,
    ).joinToString("\n")

    companion object {
        const val LABEL_W = 74
        private const val LABEL_MAX = 120

        /** The width of a row's label in a row [w] wide: [LABEL_W], more in a wide panel. */
        fun labelWidth(w: Int) = (w * 0.3).toInt().coerceIn(LABEL_W, LABEL_MAX)
        private const val RESET_W = 16
        /** The field types picked rather than typed. */
        private val PICKED = setOf("bool", "enum", "area", "target")

        private fun range(f: LayoutField): String? = when {
            f.min != null && f.max != null -> "${num(f.min)} – ${num(f.max)}"
            f.min != null -> "≥ ${num(f.min)}"
            f.max != null -> "≤ ${num(f.max)}"
            else -> null
        }

        private fun defaultText(f: LayoutField): String? = (f.default as? JsonPrimitive)?.let { d -> d.double()?.takeIf { !d.isString }?.let(::num) ?: d.content }

        private fun num(v: Double) = LayoutForm.num(v)
    }
}
