package games.qore.foreman.server.world

import com.google.gson.JsonArray
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.world.block.BlockTypes
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.World
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Player

/**
 * Clipboards: a box of a world copied as it was ([AreaAssets.Copy], [loose][AreaAssets.Copy.loose]), one per caller (a
 * player's own, which their AI chats share through the bridge; one for the console and the server's token), in memory
 * until the server stops. `copy_region` fills one, `paste_asset` pastes it by the name [ASSET] through the asset paste
 * ([AreaAssets.paste]): each paste a detached copy of its own, whose id plugins scope its objects by ([RegionCopyEvent]
 * and the `foreman:instance` tag), as they do an asset's copies.
 */
object Clipboards {
    /** The name a clipboard is pasted by (`clipboard:<player>`: a player's), and the asset of the copies pasted from one. */
    const val ASSET = "clipboard"

    /** A box copied by [owner]: [copy] to paste, its displays as `asset_content` gives them (for a ghost), [serial] for caches. */
    class Clip(val serial: Long, val owner: String, val world: String, val box: Box, val copy: AreaAssets.Copy, val displays: JsonArray)

    private val clips = HashMap<String, Clip>()
    private var serial = 0L

    fun isId(name: String) = name == ASSET || name.startsWith("$ASSET:")

    /** Whether [i] was pasted from a clipboard: it has no asset to follow nor source to open. */
    fun pasted(i: Instance) = i.asset == ASSET && i.sourceWorld == null

    /** The clipboard [name] names: the caller's ([ASSET]), or a player's (`clipboard:<player>`). */
    fun get(name: String): Clip {
        val owner = if (name == ASSET) owner() else name.removePrefix("$ASSET:").lowercase()
        return synchronized(clips) { clips[owner] }
            ?: throw ToolError(if (name == ASSET) "Your clipboard is empty: copy a box first (copy_region)" else "$owner has copied nothing since the server started")
    }

    private fun owner() = (Caller.current() ?: Caller.CONSOLE).name.lowercase()

    /**
     * Reads [box] of [world] into the caller's clipboard, in place of what it held, with [displays] (its displays' poses).
     * Main thread, the chunks around it loaded with their entities.
     */
    fun copy(world: World, box: Box, displays: (World, Box) -> JsonArray): Clip {
        val copy = AreaAssets.copy(Area("", world.name, listOf(box)), loose = true)
        val shown = displays(world, box)
        return synchronized(clips) { Clip(++serial, owner(), world.name, box, copy, shown).also { clips[it.owner] = it } }
    }

    /** What [cut] took out of the world. */
    class Cut(val entities: Int, val furniture: Int, val blockData: Int, val points: Int, val areas: Int, val instances: Int)

    /**
     * Takes what a copy of [box] holds out of the scope's world: its entities as [AreaAssets.copied] finds them, before the
     * blocks they hang on (furniture through its provider: its cells overwritten below would make the provider drop it out
     * of undo's reach; the others saved for undo, which spawns them again once the blocks are back), its blocks (to air)
     * and their block data, the points in it, the areas and pasted copies lying in it. Refused when an asset area lies in
     * it: the asset tool moves those. Recorded in the scope's change. Main thread.
     */
    fun cut(scope: EditScope, box: Box): Cut {
        val world = scope.world
        val worlds = Foreman.instance.worlds
        val change = scope.change
        val areas = worlds.areas(world.name).filter { box.containsBox(it.bounds) }
        areas.firstOrNull { AreaAssets.TAG in it.tags }?.let {
            throw ToolError("Asset ${AreaAssets.id(it)} lies in the box: cutting would take its area away (move_asset moves an asset)")
        }
        val around = box.grow(Library.REACH, Library.REACH, Library.REACH)
        Chunks.keepLoaded(world, around)
        change.touch(box)

        val roots = Furniture.inBox(world, around).filter { it.entity != null }.associateBy { it.entity }
        // Riders first: removing what they ride would leave them behind. Players get off.
        val entities = AreaAssets.copied(world, box).flatMap { passengers(it).filter { p -> p !is Player }.asReversed() + it }
        for (e in entities) {
            if (e is Display) DisplayEdits.remove(DisplayEdits.Found(e, roots[e.uniqueId]), change) else Instances.remove(e, change)
        }
        val region: Region = CuboidRegion(scope.session.world, box.min, box.max)
        scope.session.setBlocks(region, BlockTypes.AIR!!.defaultState as Pattern)
        scope.flush()
        val blockData = ChunkBlockData.remove(world, ChunkBlockData.read(world, box), change)

        val points = worlds.points(world.name).filter { it.inside(box) }
        if (points.isNotEmpty()) worlds.removePoints(world.name) { it in points }
        areas.forEach { worlds.remove(it.name, world.name) }
        val instances = worlds.instances(world.name).filter { box.containsBox(it.box) }
        worlds.removeInstances(world.name, instances.map { it.id })
        change.restores += Runnable {
            if (Bukkit.getWorld(world.name) == null) return@Runnable
            if (points.isNotEmpty()) worlds.addPoints(world.name, points)
            areas.forEach { worlds.put(it) }
            worlds.putInstances(instances)
        }
        return Cut(entities.size, entities.count { roots[it.uniqueId] != null }, blockData, points.size, areas.size, instances.size)
    }

    private fun passengers(e: Entity): List<Entity> = e.passengers.flatMap { listOf(it) + passengers(it) }
}
