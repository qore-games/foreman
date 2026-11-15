package games.qore.foreman.client.link

import games.qore.foreman.client.panels.Badge
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import net.minecraft.world.phys.Vec3

/**
 * A kind of object a plugin of the server draws with entities and lets builders edit ([id] `ns:name`, [owner] the
 * plugin): its [shape] (`point` or `line`, placed by [anchors] positions) and the fields its data holds, the same as a
 * [LayoutType]'s (the objects tool's forms are made of them). [color] (`#rrggbb`), [icon] (an item model) and
 * [category] show it in the palette; [furniture]: it is placed like that furniture of the server.
 */
@Serializable
data class ObjectKind(
    val id: String, val label: String = "", val description: String = "", val owner: String = "", val color: String? = null, val icon: String? = null,
    val shape: String = "point", val anchors: Int = 1, val fields: List<LayoutField> = emptyList(), val category: String? = null,
    val furniture: FurniturePlacement? = null,
) {
    /**
     * Placed like the server's furniture [id]: a new one shows as it would stand, and its [facing] (a yaw field) and
     * [surface] (`floor`, `ceiling` or `wall`) fields take where it goes.
     */
    @Serializable
    data class FurniturePlacement(val id: String, val facing: String? = null, val surface: String? = null)

    /** Its name for people: its label, else its id. */
    val name get() = label.ifEmpty { id }

    /** Where the palette lists it: its category, else its plugin. */
    val group get() = category ?: owner.ifEmpty { "Other" }

    /** Its colour as opaque ARGB, null when it has none. */
    val argb: Int? get() = color?.let(Layouts::color)

    val isLine get() = shape == "line"

    /** It as a type of the forms ([games.qore.foreman.client.regions.LayoutForm]). */
    val layoutType get() = LayoutType("object", id, label, description, owner, color, icon, fields)

    /** What its anchor [index] is, for people: `Start`, `End`, `At`, `Anchor 3`. */
    fun anchorName(index: Int) = when {
        anchors == 1 -> "At"
        anchors == 2 && isLine -> if (index == 0) "Start" else "End"
        else -> "Anchor ${index + 1}"
    }
}

/**
 * An object of [kind] near the player (in [dim]): its [id] (its plugin's), its [anchors] (exact positions), its [data]
 * (its kind's fields, defaults filled in), its [outline], the line where it shows (a hanging cable's curve), when its
 * plugin gives one, and its [status] badge.
 */
@Serializable
data class GameObject(
    val id: String, val kind: String, val world: String = "", val dim: String = "", val anchors: List<List<Double>> = emptyList(),
    val data: JsonObject = JsonObject(emptyMap()), val outline: List<List<Double>> = emptyList(), val status: Badge? = null,
) {
    val anchorsAt: List<Vec3> get() = anchors.filter { it.size == 3 }.map { Vec3(it[0], it[1], it[2]) }

    /** Where it shows: its outline, else the line through its anchors (a point: its anchor). */
    val line: List<Vec3> get() = outline.filter { it.size == 3 }.map { Vec3(it[0], it[1], it[2]) }.ifEmpty { anchorsAt }

    /** Its kind, when the server declares it. */
    val type: ObjectKind? get() = Bridge.objectKinds.firstOrNull { it.id == kind }
}
