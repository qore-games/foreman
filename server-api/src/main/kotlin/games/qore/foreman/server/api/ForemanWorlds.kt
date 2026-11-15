package games.qore.foreman.server.api

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.util.BoundingBox
import java.util.UUID

/**
 * What Foreman keeps in each world (in the world's persistent data, so it travels with the world file), for other
 * plugins: its areas (named places, space reserved by builds, roads and yards, zones), the gameplay points builds
 * recorded (containers, spawns… whatever the room catalog names), the building profile and the instances of library
 * assets pasted into it. Get it as a Bukkit service:
 *
 * ```java
 * ForemanWorlds worlds = Bukkit.getServicesManager().load(ForemanWorlds.class); // null without Foreman
 * for (WorldPoint p : worlds.points(world)) if (p.getType().equals("container")) fillWithLoot(p.block());
 * ```
 *
 * Listen to [WorldDataChangeEvent] to follow changes (regions from claimed areas, loot from new points…). Thread-safe
 * reads; the lists are snapshots.
 */
interface ForemanWorlds {
    fun areas(world: World): List<WorldArea>

    fun area(world: World, name: String): WorldArea?

    fun points(world: World): List<WorldPoint>

    /** The world's building profile as JSON (`{}` when it has none); see Foreman's README for its fields. */
    fun profile(world: World): String

    /** The assets of the library pasted into [world], oldest first: where each went and what it brought. */
    fun instances(world: World): List<WorldInstance>
}

/**
 * A named area: its [boxes] (block coordinates, max corners exclusive like Bukkit's), [kind] when it reserves space
 * (`build`, `road`, `yard`…), [theme] when it is a zone with its own building rules, its [tags] (lowercase; `asset`
 * makes it an asset of Foreman's library), its [data]: the fields of the area types it is ([ForemanLayout]: its kind and
 * tags that name one), their defaults filled in.
 */
class WorldArea(
    val name: String,
    val world: World,
    val boxes: List<BoundingBox>,
    val kind: String?,
    val note: String,
    val theme: String,
    val tags: List<String> = emptyList(),
    val data: LayoutData = LayoutData.EMPTY,
) {
    val reserved get() = kind != null
}

/**
 * A gameplay point: its exact position ([x], [y], [z]; a point recorded at a block, by a build or before points were
 * exact, stands on the block's bottom centre, `x.5 y z.5`), the way it faces ([yaw], [pitch]: null when it faces nowhere
 * in particular), a [type] and [kind] (`container`/`chest`), the [build] and [room] it belongs to, its [data]: the fields of
 * its type when a plugin declares it ([ForemanLayout]), their defaults filled in.
 */
class WorldPoint(
    val world: World,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float?,
    val pitch: Float?,
    val type: String,
    val kind: String,
    val build: String,
    val room: String,
    val data: LayoutData = LayoutData.EMPTY,
) {
    /** Where it is, facing its way (yaw and pitch 0 when it has none). */
    fun location(): Location = Location(world, x, y, z, yaw ?: 0f, pitch ?: 0f)

    /** The block it is in. */
    fun block(): Block = world.getBlockAt(location())
}

/**
 * An asset of the library pasted into [world]: its [id] (unique in the world, `<asset>-<n>`), the [asset] (`area:<world>/<area>`
 * or a schematic's name), the area it was copied from ([sourceWorld], [sourceArea]; null for a schematic), where its
 * bottom centre went ([x], [y], [z]) turned [rotation]° clockwise seen from above, the [box] it occupies (max corners
 * exclusive), the points and areas it brought (named `<id>/…`), its entities (the asset's UUID → the pasted one's, which
 * carries the persistent data `foreman:instance` = [id]), when ([created], epoch ms) and by whom ([by]: a player's name
 * or `console`). A copy pasted inside another as part of its asset is nested: its id is `<parent id>/<its own>`, its
 * [parent] the copy holding it. [mirror]: mirrored east–west before the turn. [detached]: it no longer follows its
 * asset (syncs leave it as it is).
 */
class WorldInstance @JvmOverloads constructor(
    val id: String,
    val world: World,
    val asset: String,
    val sourceWorld: String?,
    val sourceArea: String?,
    val x: Int,
    val y: Int,
    val z: Int,
    val rotation: Int,
    val box: BoundingBox,
    val points: List<WorldPoint>,
    val areas: List<String>,
    val entities: Map<UUID, UUID>,
    val created: Long,
    val by: String,
    val mirror: Boolean = false,
    val parent: String? = null,
    val detached: Boolean = false,
)

/** Something Foreman keeps in [world] changed ([part]). Fired on the main thread, after the change is saved. */
class WorldDataChangeEvent(val world: World, val part: Part) : Event() {
    enum class Part { AREAS, POINTS, PROFILE, INSTANCES }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
