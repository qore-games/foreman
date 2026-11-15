package games.qore.foreman.server.api

import org.bukkit.plugin.Plugin
import java.util.function.Function

/**
 * The point types and area types your plugin reads from Foreman's worlds ([ForemanWorlds]), declared: their fields
 * (what a point or area of that type carries in its `data`), their defaults and your own check. The AI reads them
 * (`layout_types`), the client builds its forms from them, `check_layout` finds the points and areas that don't match,
 * and the tools refuse data that doesn't. Get it as a Bukkit service, there with the world tools like [ForemanWorlds]:
 *
 * ```java
 * ForemanLayout layout = Bukkit.getServicesManager().load(ForemanLayout.class); // null without Foreman's world tools
 * layout.register(this, LayoutType.point("loot:container")
 *     .label("Loot container").description("A container filled from a loot table when the map starts")
 *     .color(0xC08040).icon("minecraft:chest")
 *     .field(LayoutField.string("table").required().description("The loot table"))
 *     .field(LayoutField.integer("rolls").range(1, 10).defaultValue(3))
 *     .validate(p -> p.block().getType().isAir() ? "nothing to fill there" : null)
 *     .build());
 * ```
 *
 * A point is of the type its `type` names; an area is of every type its claim `kind` or one of its `tags` names (an
 * area can be several things at once: its `data` holds the fields of all of them). Ids are namespaced (`ns:name`):
 * plain names stay free text, never checked. Register in your `onEnable`, or when the service registers (Foreman
 * enables after your plugin: listen to `ServiceRegisterEvent`); a plugin's types go when it disables.
 */
interface ForemanLayout {
    /** Declares [type] for [owner], in place of its own of the same target and id; another plugin's id is refused. */
    fun register(owner: Plugin, type: LayoutType)

    fun unregister(owner: Plugin, target: LayoutType.Target, id: String)

    /** Every declared type, points first. */
    fun types(): List<LayoutType>

    fun type(target: LayoutType.Target, id: String): LayoutType?
}

/**
 * A declared point or area type: [id] (`ns:name`), what the builders read ([label], [description], an overlay [color]
 * `0xRRGGBB` and an [icon] item id: hints), the [fields] its `data` holds, and the plugin's own check ([validate]).
 */
class LayoutType private constructor(
    val target: Target,
    val id: String,
    val label: String,
    val description: String,
    val color: Int?,
    val icon: String?,
    val fields: List<LayoutField>,
    private val validator: Function<Any, String?>?,
) {
    enum class Target { POINT, AREA }

    fun field(name: String): LayoutField? = fields.firstOrNull { it.name == name }

    /**
     * The plugin's check of [subject] (a [WorldPoint] for a point type, a [WorldArea] for an area type, its data with the
     * defaults filled in): null when fine, else what is wrong, for the builders. Main thread.
     */
    fun validate(subject: Any): String? = validator?.apply(subject)

    /** Makes a [LayoutType]: [field] for each field, [validate] for a check of your own, then [build]. */
    class Builder<T : Any> internal constructor(private val target: Target, private val id: String) {
        private var label = ""
        private var description = ""
        private var color: Int? = null
        private var icon: String? = null
        private val fields = ArrayList<LayoutField>()
        private var validator: Function<Any, String?>? = null

        fun label(label: String) = apply { this.label = label }

        fun description(description: String) = apply { this.description = description }

        /** The colour builders see it in, `0xRRGGBB`. */
        fun color(rgb: Int) = apply { color = rgb and 0xFFFFFF }

        /** An item id to show it with (`minecraft:torch`). */
        fun icon(item: String) = apply { icon = item }

        fun field(field: LayoutField) = apply {
            require(fields.none { it.name == field.name }) { "Field '${field.name}' twice in $id" }
            fields += field
        }

        /** Your own check: null when [check]'s subject is fine, else what is wrong. Runs on the main thread. */
        @Suppress("UNCHECKED_CAST")
        fun validate(check: Function<T, String?>) = apply { validator = Function { check.apply(it as T) } }

        fun build(): LayoutType {
            require(id.matches(ID)) { "Layout type ids are namespaced, lowercase: 'ns:name', not '$id'" }
            val name = label.ifEmpty { id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() } }
            return LayoutType(target, id, name, description, color, icon, fields.toList(), validator)
        }
    }

    companion object {
        /** `ns:name`: lowercase letters, digits, `_`, `-`, `.` (and `/` in the name). */
        @JvmField val ID = Regex("[a-z0-9_.-]+:[a-z0-9_./-]+")

        @JvmStatic fun point(id: String) = Builder<WorldPoint>(Target.POINT, id)

        @JvmStatic fun area(id: String) = Builder<WorldArea>(Target.AREA, id)
    }
}

/**
 * A field of a [LayoutType]'s data: its [name] (the key in `data`), its [type], what builders read ([label],
 * [description]), whether it must be set ([isRequired]), its [defaultValue] (what a point or area without it reads),
 * the [values] of a choice, the [min] and [max] of a number, the [group] it shows under in forms and the fields its
 * choices depend on ([dependsOn]). Immutable: each call returns a changed copy.
 */
class LayoutField private constructor(
    val name: String,
    val type: Type,
    val label: String,
    val description: String,
    val isRequired: Boolean,
    val defaultValue: Any?,
    val values: List<String>,
    val min: Double?,
    val max: Double?,
    val group: String?,
    val dependsOn: List<String>,
) {
    /**
     * What a field holds: text ([STRING]), whole ([INT]) or decimal ([NUMBER]) numbers, [BOOL], one of [values]
     * ([ENUM]), a colour `"#rrggbb"` ([COLOR]), a direction (a yaw in degrees, Minecraft's: 0 south, 90 west), which
     * turns with the asset or region it is pasted or moved with ([YAW]), the name of an area of the same world ([AREA]),
     * another thing builders pick in the world ([TARGET]: a [PanelTarget], as its JSON map in data).
     */
    enum class Type { STRING, INT, NUMBER, BOOL, ENUM, COLOR, YAW, AREA, TARGET }

    private fun copy(
        label: String = this.label, description: String = this.description, required: Boolean = isRequired, default: Any? = defaultValue,
        min: Double? = this.min, max: Double? = this.max, group: String? = this.group, dependsOn: List<String> = this.dependsOn,
    ) = LayoutField(name, type, label, description, required, default, values, min, max, group, dependsOn)

    fun label(label: String) = copy(label = label)

    fun description(description: String) = copy(description = description)

    /** It must be set: a point or area without it is reported. */
    fun required() = copy(required = true)

    /** What a point or area without it reads: a String, Number or Boolean as its type holds, a colour as `0xRRGGBB` or `"#rrggbb"`. */
    fun defaultValue(value: Any) = copy(default = when {
        type == Type.COLOR && value is Number -> "#%06x".format(value.toInt() and 0xFFFFFF)
        type == Type.INT && value is Number -> value.toLong()
        (type == Type.NUMBER || type == Type.YAW) && value is Number -> value.toDouble()
        else -> value
    })

    /** A number's bounds, both included. */
    fun range(min: Double, max: Double) = copy(min = min, max = max)

    fun min(min: Double) = copy(min = min)

    fun max(max: Double) = copy(max = max)

    /**
     * The heading it shows under in the editor's forms (`Inputs`), with the other fields of that group; without one, under
     * its type's name. In a [world object][ObjectKind]'s panel, a [Section] of the same title shows under that heading too.
     * A [YAW] field without a group shows with the object's position.
     */
    fun group(title: String) = copy(group = title.trim().ifEmpty { null })

    /**
     * Its choices depend on the values of the fields [names] (the outputs of the thing another field links): a
     * [world object][ObjectKind]'s form asks its [ObjectHandler.choices] for them as the builder changes those fields.
     * [values] stays every choice it may take, which the checks hold it to.
     */
    fun dependsOn(vararg names: String) = copy(dependsOn = names.toList())

    companion object {
        private val NAME = Regex("[a-z][a-z0-9_]*")

        private fun of(name: String, type: Type, values: List<String> = emptyList()): LayoutField {
            require(name.matches(NAME)) { "Field names are lowercase letters, digits and _: '$name'" }
            val label = name.replace('_', ' ').replaceFirstChar { it.uppercase() }
            return LayoutField(name, type, label, "", false, null, values, null, null, null, emptyList())
        }

        @JvmStatic fun string(name: String) = of(name, Type.STRING)
        @JvmStatic fun integer(name: String) = of(name, Type.INT)
        @JvmStatic fun number(name: String) = of(name, Type.NUMBER)
        @JvmStatic fun bool(name: String) = of(name, Type.BOOL)
        @JvmStatic fun choice(name: String, vararg values: String): LayoutField {
            require(values.isNotEmpty()) { "A choice needs values: $name" }
            return of(name, Type.ENUM, values.toList())
        }
        @JvmStatic fun color(name: String) = of(name, Type.COLOR)
        @JvmStatic fun yaw(name: String) = of(name, Type.YAW)
        @JvmStatic fun area(name: String) = of(name, Type.AREA)

        /** Another thing, picked in the world: an entity, a block, a world object ([PanelTarget]). */
        @JvmStatic fun target(name: String) = of(name, Type.TARGET)
    }
}

/**
 * The `data` of a point, an area or a world object: its field values (String, Double, Boolean, List or Map, as JSON has
 * them; a target field's is its JSON map, [target] reads it), with the defaults of its declared types filled in where it
 * has none.
 */
class LayoutData(values: Map<String, Any?>) {
    private val values: Map<String, Any?> = java.util.Collections.unmodifiableMap(LinkedHashMap(values))

    val isEmpty get() = values.isEmpty()

    fun has(name: String) = values[name] != null

    operator fun get(name: String): Any? = values[name]

    fun string(name: String): String? = values[name]?.toString()

    fun number(name: String): Double? = (values[name] as? Number)?.toDouble()

    fun number(name: String, fallback: Double): Double = number(name) ?: fallback

    fun integer(name: String): Int? = (values[name] as? Number)?.toInt()

    fun integer(name: String, fallback: Int): Int = integer(name) ?: fallback

    fun bool(name: String): Boolean? = values[name] as? Boolean

    fun bool(name: String, fallback: Boolean): Boolean = bool(name) ?: fallback

    /** A target field ([LayoutField.Type.TARGET]): the thing it names; null when unset or not a target. */
    fun target(name: String): PanelTarget? = when (val v = values[name]) {
        is PanelTarget -> v
        is Map<*, *> -> runCatching { @Suppress("UNCHECKED_CAST") PanelTarget.of(v as Map<String, *>) }.getOrNull()
        else -> null
    }

    /** A colour field, `0xRRGGBB`; null when unset or not `#rrggbb`. */
    fun color(name: String): Int? = string(name)?.removePrefix("#")?.takeIf { it.length == 6 }?.toIntOrNull(16)

    fun names(): Set<String> = values.keys

    fun asMap(): Map<String, Any?> = values

    override fun equals(other: Any?) = other is LayoutData && other.values == values

    override fun hashCode() = values.hashCode()

    override fun toString() = values.toString()

    companion object {
        @JvmField val EMPTY = LayoutData(emptyMap())
    }
}
