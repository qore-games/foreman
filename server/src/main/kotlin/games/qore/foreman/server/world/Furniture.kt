package games.qore.foreman.server.world

import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.api.FurnitureProvider
import games.qore.foreman.server.api.FurnitureType
import games.qore.foreman.server.api.PlacedFurniture
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.util.BoundingBox
import java.util.UUID

/** The furniture of the registered [FurnitureProvider] (another plugin's), when there is one. Main thread only. */
object Furniture {
    val provider: FurnitureProvider? get() = Bukkit.getServicesManager().load(FurnitureProvider::class.java)
    val available get() = provider != null

    /** Which plugin provides the furniture, for messages. */
    val providerName: String? get() = Bukkit.getServicesManager().getRegistration(FurnitureProvider::class.java)?.plugin?.name

    fun require(): FurnitureProvider = provider ?: throw ToolError("No furniture on this server (no plugin provides it to Foreman)")

    fun type(id: String): FurnitureType = require().type(id) ?: throw ToolError("Unknown furniture '$id' (see list_furniture)")

    /**
     * [cells]: its collision blocks. [interactive]: it has behaviours (seat, container…), so players must reach it.
     * [entity]: the display entity, for its model. [handle]: the provider's id for it.
     */
    data class Placed(
        val id: String, val x: Int, val y: Int, val z: Int, val yaw: Float, val state: String,
        val cells: List<BlockVector3> = emptyList(), val interactive: Boolean = false, val entity: UUID? = null, val handle: UUID? = null,
    )

    fun of(p: PlacedFurniture) = Placed(
        p.type, p.x, p.y, p.z, p.yaw, p.state, p.cells.map { BlockVector3.at(it.blockX, it.blockY, it.blockZ) }, p.interactive, p.entity, p.handle,
    )

    /**
     * Off the main thread: loads the chunks of [box] with their entities and waits until they are in (entities load
     * some ticks after their chunk), so the furniture there can be found. Up to 5 seconds.
     */
    fun ensureLoaded(world: World, box: Box) {
        if (Bukkit.isPrimaryThread() || !Sync.main { available }) return
        repeat(50) { attempt ->
            val ready = Sync.main {
                Chunks.keepLoaded(world, box)
                var all = true
                for (cx in (box.min.x() shr 4)..(box.max.x() shr 4)) for (cz in (box.min.z() shr 4)..(box.max.z() shr 4)) {
                    val chunk = world.getChunkAt(cx, cz)
                    if (!chunk.isEntitiesLoaded) { chunk.entities; all = false }
                }
                all
            }
            if (ready) {
                // Providers usually take in the furniture of freshly loaded entities a few ticks later.
                if (attempt > 0) Thread.sleep(750)
                return
            }
            Thread.sleep(100)
        }
    }

    fun inBox(world: World, box: Box): List<Placed> {
        val provider = provider ?: return emptyList()
        Chunks.keepLoaded(world, box)
        for (cx in (box.min.x() shr 4)..(box.max.x() shr 4)) for (cz in (box.min.z() shr 4)..(box.max.z() shr 4)) world.getChunkAt(cx, cz).entities
        return provider.find(world, bounds(box)).filter { it.world == world && box.contains(it.x, it.y, it.z) }.map(::of)
    }

    fun describe(placed: List<Placed>): String =
        if (placed.isEmpty()) "" else "Furniture (root block, yaw, state):\n" + placed.joinToString("\n") {
            "  ${it.id} at [${it.x}, ${it.y}, ${it.z}] yaw ${it.yaw.toInt()} state ${it.state}"
        }

    /** Places [type] rooted at [root] with [yaw], recorded in [change]. Main thread. */
    fun spawn(world: World, type: FurnitureType, root: BlockVector3, yaw: Float, state: String?, change: Change): Placed? {
        val placed = require().spawn(type, Location(world, root.x().toDouble(), root.y().toDouble(), root.z().toDouble()), yaw, state) ?: return null
        change.spawnedFurniture += placed.handle
        return of(placed)
    }

    /** Furniture put back by an undo gets a new handle: older changes still name the old one. */
    private val respawned = HashMap<UUID, UUID>()

    private fun current(handle: UUID): UUID {
        var h = handle
        while (true) h = respawned[h] ?: return h
    }

    fun undo(change: Change) {
        val provider = provider ?: return
        change.spawnedFurniture.forEach { provider.remove(current(it)) }
        change.removedFurniture.forEach { r ->
            val placed = provider.type(r.id)?.let { provider.spawn(it, r.location, r.yaw, r.state) } ?: return@forEach
            r.handle?.let { respawned[it] = placed.handle }
        }
    }

    /** Removes [placed] (no drops), recording them in [change] so undo puts them back. Main thread. */
    fun lift(world: World, placed: List<Placed>, change: Change) {
        val provider = provider ?: return
        placed.forEach { f ->
            val handle = f.handle ?: return@forEach
            if (provider.remove(handle)) change.removedFurniture += Change.RemovedFurniture(f.id, Location(world, f.x.toDouble(), f.y.toDouble(), f.z.toDouble()), f.yaw, f.state, handle)
        }
    }

    /** Places [f] again rooted at [at] with [yaw] (floor furniture). Main thread. */
    fun respawn(world: World, f: Placed, at: BlockVector3, yaw: Float, change: Change): Boolean {
        val type = provider?.type(f.id) ?: return false
        return spawn(world, type, at, yaw, f.state, change) != null
    }

    private fun bounds(box: Box) = BoundingBox(
        box.min.x().toDouble(), box.min.y().toDouble(), box.min.z().toDouble(),
        box.max.x() + 1.0, box.max.y() + 1.0, box.max.z() + 1.0,
    )
}
