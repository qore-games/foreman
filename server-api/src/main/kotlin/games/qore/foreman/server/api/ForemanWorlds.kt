package games.qore.foreman.server.api

import org.bukkit.World
import org.bukkit.event.Event
import org.bukkit.event.HandlerList
import org.bukkit.util.BoundingBox

/**
 * What Foreman keeps in each world (in the world's persistent data, so it travels with the world file), for other
 * plugins: its areas (named places, space reserved by builds, roads and yards, zones), the gameplay points builds
 * recorded (containers, spawns… whatever the room catalog names) and the building profile. Get it as a Bukkit service:
 *
 * ```java
 * ForemanWorlds worlds = Bukkit.getServicesManager().load(ForemanWorlds.class); // null without Foreman
 * for (WorldPoint p : worlds.points(world)) if (p.getType().equals("container")) fillWithLoot(p);
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
}

/**
 * A named area: its [boxes] (block coordinates, max corners exclusive like Bukkit's), [kind] when it reserves space
 * (`build`, `road`, `yard`…), [theme] when it is a zone with its own building rules.
 */
class WorldArea(val name: String, val world: World, val boxes: List<BoundingBox>, val kind: String?, val note: String, val theme: String) {
    val reserved get() = kind != null
}

/** A gameplay point at a block: a [type] and [kind] (`container`/`chest`), the [build] and [room] it belongs to. */
class WorldPoint(val world: World, val x: Int, val y: Int, val z: Int, val type: String, val kind: String, val build: String, val room: String)

/** Something Foreman keeps in [world] changed ([part]). Fired on the main thread, after the change is saved. */
class WorldDataChangeEvent(val world: World, val part: Part) : Event() {
    enum class Part { AREAS, POINTS, PROFILE }

    override fun getHandlers(): HandlerList = HANDLERS

    companion object {
        private val HANDLERS = HandlerList()

        @JvmStatic
        fun getHandlerList(): HandlerList = HANDLERS
    }
}
