package games.qore.foreman.server.world

import com.sk89q.worldedit.bukkit.BukkitAdapter
import games.qore.foreman.server.Foreman
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.persistence.PersistentDataType

/**
 * Taking a pasted copy ([Instance]) out of its world again, and only it: what its paste wrote ([Instance.written]), the
 * entities tagged with it, its block data, points, areas and record. Turning one is [Library.move] of its box; syncing
 * one is this, then a paste at the same place.
 */
object Instances {
    /** What [delete] removed; [blocks] null when the instance doesn't know the blocks it wrote (they stayed). */
    class Deleted(val blocks: Int?, val entities: Int, val furniture: Int, val blockData: Int, val points: Int, val areas: Int)

    /** Off the main thread, before [delete]: loads the instance's chunks with their entities, so its furniture is found. */
    fun prepare(world: World, i: Instance) {
        val around = i.box.grow(Library.REACH, Library.REACH, Library.REACH)
        Furniture.ensureLoaded(world, around)
        Chunks.loadEntities(world, listOf(around))
    }

    /** Whether [e] was pasted by the instance [id] (its `foreman:instance` tag). */
    fun tagged(e: Entity, id: String) = e.persistentDataContainer.get(AreaAssets.INSTANCE_KEY, PersistentDataType.STRING) == id

    /** The entities of instance [i] still in [world]: those tagged with it, around its box or recorded. Main thread. */
    fun entities(world: World, i: Instance): List<Entity> =
        (i.entities.values.mapNotNull { Bukkit.getEntity(Edits.current(it)) } + world.getNearbyEntities(Library.search(i.box)) { tagged(it, i.id) })
            .filter { it.world == world && it !is Player && tagged(it, i.id) }.distinctBy { it.uniqueId }

    /** The points of [world] instance [id] brought: named after it (`<id>`, `<id>/…`), wherever they were moved since. */
    fun points(world: String, id: String): List<Point> = Foreman.instance.worlds.points(world).filter { it.build == id || it.build.startsWith("$id/") }

    /**
     * Removes instance [i] from the scope's world: its entities (furniture through its provider, first: its cells
     * overwritten below would make the provider drop it out of undo's reach), the blocks it wrote (to air), their block
     * data, its points, areas and record. Recorded in the scope's change. Main thread.
     */
    fun delete(scope: EditScope, i: Instance): Deleted {
        val world = scope.world
        val worlds = Foreman.instance.worlds
        val change = scope.change
        val around = i.box.grow(Library.REACH, Library.REACH, Library.REACH)
        Chunks.keepLoaded(world, around)
        change.touch(i.box)

        val roots = Furniture.inBox(world, around).filter { it.entity != null }.associateBy { it.entity }
        val entities = entities(world, i)
        for (e in entities) {
            if (e is Display) DisplayEdits.remove(DisplayEdits.Found(e, roots[e.uniqueId]), change) else remove(e, change)
        }
        val written = i.written
        if (written != null) {
            val air = BukkitAdapter.adapt(Material.AIR.createBlockData())
            written.blocks().forEach { scope.session.setBlock(it, air) }
            scope.flush()
        }
        val data = written?.let { w -> ChunkBlockData.read(world, i.box).filter { w.has(it.x, it.y, it.z) } }.orEmpty()
        val blockData = ChunkBlockData.remove(world, data, change)

        val points = points(world.name, i.id)
        if (points.isNotEmpty()) worlds.removePoints(world.name) { it in points }
        val areas = i.areas.mapNotNull { worlds.area(it, world.name) }
        areas.forEach { worlds.remove(it.name, world.name) }
        worlds.removeInstance(world.name, i.id)
        change.restores += Runnable {
            if (Bukkit.getWorld(world.name) == null) return@Runnable
            if (points.isNotEmpty()) worlds.addPoints(world.name, points)
            areas.forEach { worlds.put(it) }
            worlds.putInstance(i)
        }
        return Deleted(written?.count, entities.size, entities.count { roots[it.uniqueId] != null }, blockData, points.size, areas.size)
    }

    /** Removes [e] (not a display), recorded in [change]: undo spawns it again as it was. */
    private fun remove(e: Entity, change: Change) {
        val at = e.location
        val snapshot = e.createSnapshot()
        e.remove()
        if (snapshot != null) change.restores += Runnable { Edits.replaced(Edits.current(e.uniqueId), snapshot.createEntity(at).uniqueId) }
        change.touch(at)
    }
}
