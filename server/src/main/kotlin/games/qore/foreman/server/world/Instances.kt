package games.qore.foreman.server.world

import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
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
 * entities tagged with it, its block data, points, areas and record, with the copies nested in it. Turning one is
 * [Library.move] of its box; syncing one is this, then a paste at the same place.
 */
object Instances {
    /** What [delete] removed; [blocks] null when the instances don't know the blocks they wrote (they stayed). */
    class Deleted(val blocks: Int?, val entities: Int, val furniture: Int, val blockData: Int, val points: Int, val areas: Int, val instances: List<Instance>)

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
        (i.entities.values.mapNotNull { world.getEntity(Edits.current(it)) } + world.getNearbyEntities(Library.search(i.box)) { tagged(it, i.id) })
            .filter { it.world == world && it !is Player && tagged(it, i.id) }.distinctBy { it.uniqueId }

    /** The points of [world] instance [id] brought: named after it (`<id>`, `<id>/…`), wherever they were moved since. */
    fun points(world: String, id: String): List<Point> = Foreman.instance.worlds.points(world).filter { it.build == id || it.build.startsWith("$id/") }

    /** [root] and the copies nested in it. */
    fun tree(root: Instance): List<Instance> = listOf(root) + Foreman.instance.worlds.descendants(root.world, root.id)

    /** The detached copies nested in [root], the top ones (those nested in them go with them): what a sync of [root] keeps. */
    fun detachedIn(root: Instance): List<Instance> {
        val detached = Foreman.instance.worlds.descendants(root.world, root.id).filter { it.detached }
        return detached.filter { d -> detached.none { d.isUnder(it.id) } }
    }

    /**
     * Removes instance [i] and the copies nested in it from the scope's world: their entities (furniture through its
     * provider, first: its cells overwritten below would make the provider drop it out of undo's reach), the blocks they
     * wrote (to air), their block data, their points, areas and records. The copies of [keep] (detached ones a sync
     * leaves) stay as they are, with everything in their boxes. Recorded in the scope's change. Main thread.
     */
    fun delete(scope: EditScope, i: Instance, keep: List<Instance> = emptyList()): Deleted {
        val world = scope.world
        val worlds = Foreman.instance.worlds
        val change = scope.change
        val around = i.box.grow(Library.REACH, Library.REACH, Library.REACH)
        Chunks.keepLoaded(world, around)
        change.touch(i.box)
        val kept = keep.map { it.box }
        fun isKept(x: Int, y: Int, z: Int) = kept.any { it.contains(x, y, z) }
        fun keptName(name: String) = keep.any { name == it.id || name.startsWith("${it.id}/") }
        val tree = tree(i).filter { !keptName(it.id) }

        val roots = Furniture.inBox(world, around).filter { it.entity != null }.associateBy { it.entity }
        val entities = tree.flatMap { entities(world, it) }.distinctBy { it.uniqueId }.filter { !isKept(it.location.blockX, it.location.blockY, it.location.blockZ) }
        for (e in entities) {
            if (e is Display) DisplayEdits.remove(DisplayEdits.Found(e, roots[e.uniqueId]), change) else remove(e, change)
        }
        val written = tree.mapNotNull { it.written }
        val cells = HashSet<BlockVector3>()
        if (written.isNotEmpty()) {
            val air = BukkitAdapter.adapt(Material.AIR.createBlockData())
            written.forEach { w -> w.blocks().filter { !isKept(it.x(), it.y(), it.z()) && cells.add(it) }.forEach { scope.session.setBlock(it, air) } }
            scope.flush()
        }
        val data = ChunkBlockData.read(world, i.box).filter { e -> written.any { it.has(e.x, e.y, e.z) } && !isKept(e.x, e.y, e.z) }
        val blockData = ChunkBlockData.remove(world, data, change)

        val points = worlds.points(world.name).filter { p -> tree.any { p.build == it.id || p.build.startsWith("${it.id}/") } && !keptName(p.build) }
        if (points.isNotEmpty()) worlds.removePoints(world.name) { it in points }
        val areas = tree.flatMap { it.areas }.mapNotNull { worlds.area(it, world.name) }
        areas.forEach { worlds.remove(it.name, world.name) }
        worlds.removeInstances(world.name, tree.map { it.id })
        change.restores += Runnable {
            if (Bukkit.getWorld(world.name) == null) return@Runnable
            if (points.isNotEmpty()) worlds.addPoints(world.name, points)
            areas.forEach { worlds.put(it) }
            worlds.putInstances(tree)
        }
        return Deleted(
            if (written.isEmpty()) null else cells.size, entities.size, entities.count { roots[it.uniqueId] != null }, blockData, points.size, areas.size, tree,
        )
    }

    /**
     * Forgets instance [i] and the copies nested in it: their records and their entities' tags go, what they pasted stays.
     * Recorded in [change]: undo puts them back. Main thread. Returns the instances forgotten and the entities untagged.
     */
    fun forget(world: World, i: Instance, change: Change): Pair<List<Instance>, Int> {
        val tree = tree(i)
        val tagged = tree.flatMap { t -> entities(world, t).map { it to t.id } }
        tagged.forEach { (e, _) -> e.persistentDataContainer.remove(AreaAssets.INSTANCE_KEY) }
        Foreman.instance.worlds.removeInstances(world.name, tree.map { it.id })
        change.touch(i.box)
        change.restores += Runnable {
            val now = Bukkit.getWorld(world.name) ?: return@Runnable
            tagged.forEach { (e, id) -> now.getEntity(Edits.current(e.uniqueId))?.let { AreaAssets.tag(it, id) } }
            Foreman.instance.worlds.putInstances(tree)
        }
        return tree to tagged.size
    }

    /** Removes [e] (not a display), recorded in [change]: undo spawns it again as it was. */
    fun remove(e: Entity, change: Change) {
        val at = e.location
        val snapshot = e.createSnapshot()
        e.remove()
        if (snapshot != null) change.restores += Runnable { Edits.replaced(Edits.current(e.uniqueId), snapshot.createEntity(at).uniqueId) }
        change.touch(at)
    }
}
