package games.qore.foreman.client.link

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * A point or area type a plugin of the server declares ([target] `point` or `area`, [id] `ns:name`, [owner] the
 * plugin), with the fields its `data` holds: what the regions tool's forms are made of. [color] (`#rrggbb`) and [icon]
 * (an item id) are hints for showing it.
 */
@Serializable
data class LayoutType(
    val target: String, val id: String, val label: String = "", val description: String = "", val owner: String = "",
    val color: String? = null, val icon: String? = null, val fields: List<LayoutField> = emptyList(),
) {
    /** Its name for people: its label, else its id. */
    val name get() = label.ifEmpty { id }

    /** Its colour as opaque ARGB, null when it has none. */
    val argb: Int? get() = color?.let(Layouts::color)
}

/**
 * A field of a [LayoutType]'s data: [type] `string`, `int`, `number`, `bool`, `enum` (one of [values]), `color`
 * (`#rrggbb`), `yaw` (degrees) or `area` (an area name of the same world); [min] and [max] bound numbers, [default] is
 * what one without it reads. [group]: the heading it shows under in forms; [dependsOn]: the fields whose values its
 * choices depend on (an object's form asks the server for them as those change).
 */
@Serializable
data class LayoutField(
    val name: String, val type: String, val label: String = "", val description: String = "", val required: Boolean = false,
    val default: JsonElement? = null, val values: List<String> = emptyList(), val min: Double? = null, val max: Double? = null,
    val group: String? = null, @SerialName("depends_on") val dependsOn: List<String> = emptyList(),
)

/** The server's declared types ([Bridge.layout]) by what they type. */
object Layouts {
    private val COLOR = Regex("#?[0-9a-fA-F]{6}")

    /** The declared point type [type] names, if any. */
    fun point(type: String): LayoutType? = Bridge.layout.firstOrNull { it.target == "point" && it.id == type }

    /** The declared area type [name] (a tag or claim kind) names, if any. */
    fun area(name: String): LayoutType? = Bridge.layout.firstOrNull { it.target == "area" && it.id == name }

    /** The declared types [a] is: those its claim kind and tags name. */
    fun of(a: Area): List<LayoutType> = (listOfNotNull(a.kind) + a.tags).distinct().mapNotNull(::area)

    fun points(): List<LayoutType> = Bridge.layout.filter { it.target == "point" }

    fun areas(): List<LayoutType> = Bridge.layout.filter { it.target == "area" }

    /** `#rrggbb` (the `#` optional) as opaque ARGB, null when it isn't one. */
    fun color(text: String): Int? = text.trim().takeIf { it.matches(COLOR) }?.removePrefix("#")?.toInt(16)?.or(0xFF000000.toInt())
}
