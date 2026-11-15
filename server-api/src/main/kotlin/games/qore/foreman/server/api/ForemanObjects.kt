package games.qore.foreman.server.api

import org.bukkit.World
import org.bukkit.plugin.Plugin
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector

/**
 * Things in the world your plugin draws with entities (a cable hanging between two walls, a prop), offered to Foreman's
 * editor without Foreman knowing what they are: builders list, place, select, edit and remove them in game (the mod's
 * objects tool, with forms made from their fields) and their AI does with tools (`object_kinds`, `list_objects`,
 * `place_objects`, `edit_objects`, `remove_objects`), each change undoable. Your entities stay the truth: Foreman keeps
 * nothing and asks your [ObjectHandler] each time, so the objects live on without Foreman. Get it as a Bukkit service,
 * there with the world tools like [ForemanLayout]:
 *
 * ```java
 * ForemanObjects objects = Bukkit.getServicesManager().load(ForemanObjects.class); // null without Foreman's world tools
 * objects.register(this, ObjectKind.line("rides:zipline")
 *     .label("Zipline").description("A rope players slide down, from its first anchor to its second")
 *     .color(0x8B5A2B).icon("minecraft:lead").category("Rides")
 *     .field(LayoutField.choice("rope", "hemp", "steel").defaultValue("hemp"))
 *     .field(LayoutField.number("sag").range(0, 8).defaultValue(0.5))
 *     .handler(new Ziplines(this))
 *     .build());
 * ```
 *
 * Ids are namespaced (`ns:name`). Register in your `onEnable`, or when the service registers (Foreman enables after
 * your plugin: listen to `ServiceRegisterEvent`); register a kind again to change it (its choices after a reload). A
 * plugin's kinds go when it disables.
 */
interface ForemanObjects {
    /** Declares [kind] for [owner], in place of its own of the same id; another plugin's id is refused. */
    fun register(owner: Plugin, kind: ObjectKind)

    fun unregister(owner: Plugin, id: String)

    /** Every declared kind, by id. */
    fun kinds(): List<ObjectKind>

    fun kind(id: String): ObjectKind?

    /** Adds [action] for [owner] to the objects tool's keys, in place of its own of the same id; another plugin's id is refused. */
    fun addAction(owner: Plugin, action: ObjectAction)

    fun removeAction(owner: Plugin, id: String)

    /** Every action plugins added, in the order they did. */
    fun actions(): List<ObjectAction>
}

/**
 * A kind of object: [id] (`ns:name`), what builders read ([label], [description], a [color] `0xRRGGBB`, an [icon] item
 * model and a [category] to find it under in the editor's palette), its [shape] (how many anchors place one), the
 * [fields] its data holds (the same as [layout types'][LayoutType]: the editor's forms, defaults and checks come with
 * them), how a new one is placed when it stands like furniture ([placement]) and your [handler].
 */
class ObjectKind private constructor(
    val id: String,
    val shape: Shape,
    val label: String,
    val description: String,
    val color: Int?,
    val icon: String?,
    val category: String?,
    val placement: FurniturePlacement?,
    val fields: List<LayoutField>,
    val handler: ObjectHandler,
) {
    /** How an object stands in the world: by [anchors] exact positions, given in this order. */
    enum class Shape(val anchors: Int) {
        /** One anchor: a prop, a sign post (its facing is a [LayoutField.yaw] field). */
        POINT(1),

        /** Two anchors, its ends: a cable, a rope, a beam. */
        LINE(2),
    }

    fun field(name: String): LayoutField? = fields.firstOrNull { it.name == name }

    /**
     * A point kind placed like the server's furniture [furniture] ([FurnitureProvider]'s type id): the editor shows a
     * new object as that furniture would stand where the builder aims (its model on the face looked at that it goes on,
     * turned in its rotation steps) and places it there: its anchor in the middle of the floor of the block before that
     * face, its [facing] field (a [LayoutField.yaw]) where its front faces, its [surface] field (a [LayoutField.choice])
     * what it goes against: `floor`, `ceiling` or `wall` (the wall behind it).
     */
    class FurniturePlacement(val furniture: String, val facing: String?, val surface: String?)

    /** Makes an [ObjectKind]: [field] for each field, [handler] (required), then [build]. */
    class Builder internal constructor(private val id: String, private val shape: Shape) {
        private var label = ""
        private var description = ""
        private var color: Int? = null
        private var icon: String? = null
        private var category: String? = null
        private var placement: FurniturePlacement? = null
        private val fields = ArrayList<LayoutField>()
        private var handler: ObjectHandler? = null

        fun label(label: String) = apply { this.label = label }

        fun description(description: String) = apply { this.description = description }

        /** The colour builders see it in, `0xRRGGBB`. */
        fun color(rgb: Int) = apply { color = rgb and 0xFFFFFF }

        /**
         * The item model the editor draws it with in its palette (`minecraft:lead`, a resource pack's `ns:path`: what an
         * item's `item_model` names); without one, a drawing of its shape.
         */
        fun icon(model: String) = apply { icon = model }

        /** Where builders find it in the editor's palette, a few words (`Lights`, `Machines`); without one, its plugin's name. */
        fun category(category: String) = apply { this.category = category.trim().ifEmpty { null } }

        /**
         * Placed like the server's furniture [type] ([FurniturePlacement]): the editor shows a new one as that furniture
         * would stand, and fills in its [facing] yaw field and [surface] choice field (when given) from where it goes.
         */
        @JvmOverloads
        fun furniture(type: String, facing: String? = null, surface: String? = null) = apply { placement = FurniturePlacement(type, facing, surface) }

        fun field(field: LayoutField) = apply {
            require(fields.none { it.name == field.name }) { "Field '${field.name}' twice in $id" }
            fields += field
        }

        /** What finds, makes, changes and removes its objects: yours. */
        fun handler(handler: ObjectHandler) = apply { this.handler = handler }

        fun build(): ObjectKind {
            require(id.matches(LayoutType.ID)) { "Object kind ids are namespaced, lowercase: 'ns:name', not '$id'" }
            val handler = requireNotNull(handler) { "$id has no handler" }
            placement?.let { p ->
                require(shape == Shape.POINT) { "$id: only point kinds are placed like furniture" }
                p.facing?.let { f -> require(fields.any { it.name == f && it.type == LayoutField.Type.YAW }) { "$id: its facing '$f' is no yaw field of it" } }
                p.surface?.let { f -> require(fields.any { it.name == f && it.type == LayoutField.Type.ENUM }) { "$id: its surface '$f' is no choice field of it" } }
            }
            val name = label.ifEmpty { id.substringAfter(':').replace('_', ' ').replaceFirstChar { it.uppercase() } }
            return ObjectKind(id, shape, name, description, color, icon, category, placement, fields.toList(), handler)
        }
    }

    companion object {
        @JvmStatic fun point(id: String) = Builder(id, Shape.POINT)

        @JvmStatic fun line(id: String) = Builder(id, Shape.LINE)

        @JvmStatic fun of(id: String, shape: Shape) = Builder(id, shape)
    }
}

/**
 * Your side of an [ObjectKind]: finds, makes, changes and removes its objects, as your plugin keeps them (entities and
 * their persistent data). Called on the main thread. Foreman checks the data against the kind's fields first (types,
 * choices, ranges, required ones) and fills in the defaults; your own rules (a cable too long, an anchor in a wall)
 * refuse with [ObjectResult.error]. Foreman records each change for undo by calling these again.
 */
@JvmSuppressWildcards
interface ObjectHandler {
    /**
     * The objects of this kind in [world] reaching into [box] (an anchor or their outline in it). Its chunks are loaded;
     * their entities may have just been.
     */
    fun find(world: World, box: BoundingBox): List<WorldObject>

    /** The object [id] of [world], or null when there is none (any more). */
    fun get(world: World, id: String): WorldObject?

    /** Makes an object at [anchors] (as many as the kind's shape has, in order) with [data]: it, or why not. */
    fun create(world: World, anchors: List<Vector>, data: LayoutData): ObjectResult

    /**
     * Makes [current] stand at [anchors] with [data] (all of it, not a patch): it as it is now (it may have a new id), or
     * why not.
     */
    fun update(current: WorldObject, anchors: List<Vector>, data: LayoutData): ObjectResult

    /** Removes [current] from the world; false when it was gone already. */
    fun remove(current: WorldObject): Boolean

    /**
     * The choices [field] (one that [depends on][LayoutField.dependsOn] others) offers for [data], the form as the builder
     * has it now, unsaved (its target fields resolved, defaults filled in), of [current] (null: a new object): some of its
     * values, in the order to show them; null: all of them. Asked as the builder changes the fields it depends on: keep
     * it cheap.
     */
    fun choices(world: World, current: WorldObject?, field: String, data: LayoutData): List<String>? = null
}

/**
 * An object of [kind] in [world]: its [id] (yours, stable while it exists: an entity's UUID), its [anchors] (exact
 * positions, as many as its kind's shape has), its [data] (its kind's fields; Foreman fills in the defaults of those it
 * lacks), and its [outline]: the line through the world where it shows (a hanging cable's curve), which the editor draws,
 * picks with the crosshair and highlights. Without one, it is the line through its anchors (a point: a marker there).
 * Its [status], when it has one, is the badge the editor draws over it (`working`, `no power`).
 */
class WorldObject @JvmOverloads constructor(
    val id: String,
    val kind: String,
    val world: World,
    val anchors: List<Vector>,
    val data: LayoutData = LayoutData.EMPTY,
    val outline: List<Vector> = emptyList(),
    val status: Status? = null,
) {
    init {
        require(anchors.isNotEmpty()) { "World object $kind $id has no anchor" }
    }

    /** It with the badge [status] (null: none). */
    fun status(status: Status?) = WorldObject(id, kind, world, anchors, data, outline, status)
}

/** What [ObjectHandler.create] and [ObjectHandler.update] did: the object as it is now ([worldObject]), or why not ([error]). */
class ObjectResult private constructor(val worldObject: WorldObject?, val error: String?) {
    val isOk get() = worldObject != null

    companion object {
        @JvmStatic fun ok(worldObject: WorldObject) = ObjectResult(worldObject, null)

        @JvmStatic fun error(message: String) = ObjectResult(null, message)
    }
}
