package games.qore.foreman.server.world

import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.session.ClipboardHolder
import com.sk89q.worldedit.world.block.BlockTypes
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.RegionCopyEvent
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.Hanging
import org.bukkit.entity.Player
import org.joml.Vector3f
import java.util.UUID

/**
 * The library's work on asset areas ([AreaAssets]): fitting an area to what it holds, finding a free plot for a new one,
 * and moving one with everything in it.
 */
object Library {
    /** Blocks kept between plots, and the length of a row of plots. */
    const val PLOT_GAP = 8
    const val PLOT_ROW = 256

    /** How far from [box] an entity may stand and still show inside it (a display's translation): where to look. */
    const val REACH = 16

    /**
     * Where [e] shows: a display's model starts at its position plus its translation, turned by its yaw and pitch when
     * it doesn't face viewers (a furniture's display stands blocks away from its model); any other entity at its
     * position.
     */
    fun shownAt(e: Entity): Location {
        val l = e.location
        val d = e as? Display ?: return l
        val t = Vector3f(d.transformation.translation)
        if (d.billboard == Display.Billboard.FIXED) DisplayEdits.rotation(l.yaw, l.pitch).transform(t)
        return l.clone().add(t.x.toDouble(), t.y.toDouble(), t.z.toDouble())
    }

    /** Whether [e] belongs to [box]: its position, or where it shows, is in it. */
    fun inside(e: Entity, box: Box): Boolean =
        box.contains(e.location.blockX, e.location.blockY, e.location.blockZ) || shownAt(e).let { box.contains(it.blockX, it.blockY, it.blockZ) }

    /** Where to look for [box]'s entities ([inside] them), [REACH] around it. */
    fun search(box: Box) = Placement.bounds(box.grow(REACH, REACH, REACH))

    /** The entities [inside] [box], players and riders aside (riders go with what they ride). Main thread. */
    fun entities(world: World, box: Box): List<Entity> =
        world.getNearbyEntities(search(box)) { it !is Player && !it.isInsideVehicle && inside(it, box) }.toList()

    /** The bounds of what [box] holds: its blocks that aren't air, where its entities show and its points; null when it is empty. Main thread. */
    fun content(world: World, box: Box): Box? {
        val snapshots = Snapshots(world, box.min.x(), box.min.z(), box.max.x(), box.max.z()).load()
        val cells = ArrayList<BlockVector3>()
        for (x in box.min.x()..box.max.x()) for (z in box.min.z()..box.max.z()) {
            var low: Int? = null
            var high = 0
            for (y in box.min.y()..box.max.y()) if (!snapshots.type(x, y, z).isAir) {
                if (low == null) low = y
                high = y
            }
            if (low != null) {
                cells += BlockVector3.at(x, low, z)
                cells += BlockVector3.at(x, high, z)
            }
        }
        entities(world, box).map(::shownAt).forEach { cells += BlockVector3.at(it.blockX, it.blockY, it.blockZ) }
        Foreman.instance.worlds.points(world.name).filter { it.inside(box) }.forEach { cells += it.block }
        if (cells.isEmpty()) return null
        return Box(cells.reduce { a, b -> a.getMinimum(b) }, cells.reduce { a, b -> a.getMaximum(b) })
    }

    /**
     * A free plot of [world] for a box the size of [size]: where [size] is when the world has no asset area yet;
     * otherwise the first spot, rows north to south and west to east in a row, that keeps [PLOT_GAP] blocks from every
     * area of the world. Rows start at the west edge of the asset areas and are [PLOT_ROW] blocks long; plots stand on
     * the floor of the lowest asset area.
     */
    fun plot(worlds: WorldData, world: String, size: Box): Box {
        val assets = worlds.areas(world).filter { AreaAssets.TAG in it.tags }
        if (assets.isEmpty()) return size
        val taken = worlds.areas(world).flatMap { it.boxes }
        val x0 = assets.minOf { it.bounds.min.x() }
        val z0 = assets.minOf { it.bounds.min.z() }
        val y0 = assets.minOf { it.bounds.min.y() }
        val rows = (listOf(z0) + taken.map { it.max.z() + 1 + PLOT_GAP }).filter { it >= z0 }.distinct().sorted()
        val columns = (listOf(x0) + taken.map { it.max.x() + 1 + PLOT_GAP }).filter { it >= x0 }.distinct().sorted()
        for (z in rows) for (x in columns) {
            if (x > x0 && x + size.sizeX > x0 + PLOT_ROW) break
            val box = Box(BlockVector3.at(x, y0, z), BlockVector3.at(x + size.sizeX - 1, y0 + size.sizeY - 1, z + size.sizeZ - 1))
            if (taken.none { near(it, box) }) return box
        }
        // Past every area there is room.
        val z = taken.maxOf { it.max.z() } + 1 + PLOT_GAP
        return Box(BlockVector3.at(x0, y0, z), BlockVector3.at(x0 + size.sizeX - 1, y0 + size.sizeY - 1, z + size.sizeZ - 1))
    }

    /** Closer than [PLOT_GAP] blocks seen from above. */
    private fun near(a: Box, b: Box) =
        a.min.x() <= b.max.x() + PLOT_GAP && a.max.x() >= b.min.x() - PLOT_GAP && a.min.z() <= b.max.z() + PLOT_GAP && a.max.z() >= b.min.z() - PLOT_GAP

    // ---- Moving -------------------------------------------------------------------------------------------------------

    /** What [move] carried, and the instances it carried as they are now. */
    class Moved(val from: Box, val box: Box, val entities: Int, val blockData: Int, val points: Int, val areas: Int, val instances: List<Instance>)

    /**
     * The asset areas and claims a move of [from] to [to] would land on: those inside [from] go along, and those [from]
     * stands in (an instance pasted in an asset area, its claim) aren't in the way.
     */
    fun obstacles(worlds: WorldData, world: String, from: Box, to: Box): List<Area> = worlds.areas(world).filter { a ->
        !from.containsBox(a.bounds) && (AreaAssets.TAG in a.tags || a.reserved) && a.boxes.none { it.containsBox(from) } && a.boxes.any { it.intersect(to) != null }
    }

    /**
     * Moves box [from] of the scope's world where [placement] puts it, with everything in it: the blocks (the source
     * cleared to air, the destination replaced), their block data ([ChunkBlockData]), the entities (teleported: each
     * keeps its UUID, its position and yaw turned) and players standing there, the points (turned), and the areas and
     * instances lying inside it (an asset area's own area, an instance's own record: their boxes, points and written
     * blocks turned). Announces it with a [RegionCopyEvent] ([RegionCopyEvent.Kind.MOVE]). Undo moves it all back. Main
     * thread.
     */
    fun move(scope: EditScope, from: Box, placement: Placement): Moved {
        val world = scope.world
        val worlds = Foreman.instance.worlds
        val to = placement.box(from)
        Chunks.keepLoaded(world, from.grow(REACH, REACH, REACH))
        Chunks.keepLoaded(world, to)
        scope.change.touch(from)
        scope.change.touch(to)

        val entities = entities(world, from).map { Carried(it.uniqueId, it.location, (it as? Hanging)?.facing) }
        val data = ChunkBlockData.read(world, from)
        val points = worlds.points(world.name).filter { it.inside(from) }
        val areas = worlds.areas(world.name).filter { from.containsBox(it.bounds) }
        val instances = worlds.instances(world.name).filter { from.containsBox(it.box) }
        val movedPoints = points.map { placement.point(it) }
        val movedInstances = instances.map { i ->
            i.copy(
                origin = placement.block(i.origin), rotate = (i.rotate + placement.rotate) % 360, box = placement.box(i.box),
                points = i.points.map { placement.point(it) }, written = i.written?.moved(placement),
            )
        }

        // Undo runs the restores newest first: the block data (recorded below) goes back before this puts the rest.
        var moved = false
        scope.change.restores += Runnable {
            val back = placement.inverse()
            for (e in entities) {
                val entity = Edits.entity(e.id, placement.location(e.at, world)) ?: continue
                entity.teleport(e.at)
                if (entity is Hanging && e.facing != null) entity.setFacingDirection(e.facing, true)
            }
            if (!moved || Bukkit.getWorld(world.name) == null) return@Runnable
            carryPlayers(world, to, back)
            if (movedPoints.isNotEmpty()) {
                worlds.removePoints(world.name) { it in movedPoints }
                worlds.addPoints(world.name, points)
            }
            areas.forEach { worlds.put(it) }
            instances.forEach { worlds.putInstance(it) }
            // An entity put back by a later undo (a deleted instance's) is another entity now: the block data put back
            // above still names the one recorded.
            Bukkit.getPluginManager().callEvent(back.event(RegionCopyEvent.Kind.MOVE, world, to, world, entities.associate { it.id to Edits.current(it.id) }))
        }

        val session = scope.session
        val region: Region = CuboidRegion(session.world, from.min, from.max)
        val clipboard = BlockArrayClipboard(region).also { it.origin = placement.origin }
        Operations.complete(ForwardExtentCopy(session, region, clipboard, region.minimumPoint).apply { isCopyingEntities = false })
        session.setBlocks(region, BlockTypes.AIR!!.defaultState as Pattern)
        val holder = ClipboardHolder(clipboard).also { it.transform = placement.transform }
        Operations.complete(holder.createPaste(session).to(placement.position).ignoreAirBlocks(false).copyEntities(false).copyBiomes(false).build())
        scope.flush()

        val blockData = ChunkBlockData.copy(data, placement, world, scope.change, from = world, clear = to)
        carryPlayers(world, from, placement)
        for (e in entities) {
            val entity = Bukkit.getEntity(e.id) ?: continue
            entity.teleport(placement.location(e.at, world))
            if (entity is Hanging && e.facing != null) entity.setFacingDirection(placement.face(e.facing), true)
        }
        if (points.isNotEmpty()) {
            worlds.removePoints(world.name) { it in points }
            worlds.addPoints(world.name, movedPoints)
        }
        areas.forEach { a -> worlds.put(placement.area(a)) }
        movedInstances.forEach { worlds.putInstance(it) }
        moved = true
        Bukkit.getPluginManager().callEvent(placement.event(RegionCopyEvent.Kind.MOVE, world, from, world, entities.associate { it.id to it.id }))
        return Moved(from, to, entities.size, blockData, points.size, areas.size, movedInstances)
    }

    /**
     * The players standing in [box] go with it (where [placement] puts them, turned), rather than over the air it leaves:
     * the builder moving an asset they stand in. Those riding something go with it.
     */
    private fun carryPlayers(world: World, box: Box, placement: Placement) {
        world.getNearbyEntities(Placement.bounds(box)) { it is Player && !it.isInsideVehicle && box.contains(it.location.blockX, it.location.blockY, it.location.blockZ) }
            .forEach { it.teleport(placement.location(it.location, world)) }
    }

    /** An entity being moved: where it was, and the face it hung on. */
    private class Carried(val id: UUID, val at: Location, val facing: BlockFace?)
}
