package games.qore.foreman.server.world

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.session.ClipboardHolder
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.RegionCopyEvent
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.bukkit.entity.Display
import org.bukkit.entity.Entity
import org.bukkit.entity.EntitySnapshot
import org.bukkit.persistence.PersistentDataType
import org.joml.Matrix4d
import org.joml.Vector3d
import java.util.UUID

/**
 * Assets made from areas tagged [TAG]: a builder makes something in a world, draws an area around it and tags it; it is
 * then listed, previewed and pasted like a schematic of the library, with its display entities (item, block, text) and
 * furniture. Its id is `area:<world>/<area>`, its description the area's note, its tags the area's others. An asset is
 * the bounds of its area's boxes.
 */
object AreaAssets {
    const val TAG = "asset"
    const val PREFIX = "area:"
    private const val INFO_MS = 60_000L
    /** Bigger areas are listed without their main blocks. */
    private const val MAX_COUNTED = 4_000_000L
    /** Not main blocks: furniture cells, light sources, markers. */
    private val INVISIBLE = setOf(Material.BARRIER, Material.LIGHT, Material.STRUCTURE_VOID)

    fun id(area: Area) = "$PREFIX${area.world}/${area.name}"

    fun isId(name: String) = name.startsWith(PREFIX)

    /** The asset areas of the loaded worlds. */
    fun all(worlds: WorldData): List<Area> = worlds.areas.filter { TAG in it.tags }

    fun get(worlds: WorldData, id: String): Area {
        val rest = id.removePrefix(PREFIX)
        // World names may hold a '/': the longest loaded world the id starts with.
        val world = Bukkit.getWorlds().map { it.name }.filter { rest.startsWith("$it/") }.maxByOrNull { it.length }
            ?: throw ToolError("No loaded world in '$id' (area ids are area:<world>/<area>)")
        val area = worlds.area(rest.removePrefix("$world/"), world)
        if (area == null || TAG !in area.tags) throw ToolError("No asset '$id' (an area tagged $TAG; see list_assets)")
        return area
    }

    fun world(area: Area): World = Bukkit.getWorld(area.world) ?: throw ToolError("World ${area.world} is not loaded")

    /**
     * The display entities [Library.inside] [box] (where they stand or where they show), persistent ones only (plugins
     * spawn their runtime parts again). Main thread.
     */
    fun displays(world: World, box: Box): List<Display> =
        world.getNearbyEntities(Library.search(box)) { it is Display && it.isPersistent && !it.isInsideVehicle && Library.inside(it, box) }
            .map { it as Display }

    /** The furniture whose root display is one of [displays] (its root may stand outside the box its model shows in). */
    private fun furniture(world: World, box: Box, displays: List<Display>): List<Furniture.Placed> {
        val ids = displays.map { it.uniqueId }.toSet()
        return Furniture.inBox(world, box.grow(Library.REACH, Library.REACH, Library.REACH)).filter { it.entity in ids }
    }


    // ---- Listing ------------------------------------------------------------------------------------------------------

    /** What list_assets tells of an asset area: its size, main blocks and category, its furniture and other displays. */
    class Info(val area: Area, val size: List<Int>, val blocks: List<String>, val category: String?, val furniture: Int, val displays: Int) {
        val id get() = id(area)
        val tags get() = area.tags - TAG
    }

    private val infos = HashMap<String, Pair<Long, Info>>()

    /** [areas] described, read again after a minute or when the area changed. Off the main thread. */
    fun info(plugin: Foreman, areas: List<Area>): List<Info> {
        val now = System.currentTimeMillis()
        val missing = synchronized(infos) {
            infos.values.removeIf { now - it.first >= INFO_MS }
            areas.filter { a -> infos[id(a)]?.second?.area != a }
        }
        for ((name, list) in missing.groupBy { it.world }) {
            val world = Bukkit.getWorld(name) ?: continue
            Chunks.loadEntities(world, list.map { it.bounds.grow(Library.REACH, Library.REACH, Library.REACH) })
            val read = Sync.main {
                list.map { a ->
                    val box = a.bounds
                    val snapshots = if (box.volume <= MAX_COUNTED) Snapshots(world, box.min.x(), box.min.z(), box.max.x(), box.max.z()).load() else null
                    val displays = displays(world, box)
                    val furniture = furniture(world, box, displays)
                    val roots = furniture.mapNotNull { it.entity }.toSet()
                    Triple(a, snapshots, furniture.size to displays.count { it.uniqueId !in roots })
                }
            }
            for ((a, snapshots, counts) in read) {
                val box = a.bounds
                val materials = HashMap<Material, Int>()
                if (snapshots != null) for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
                    val m = snapshots.type(x, y, z)
                    if (!m.isAir && m !in INVISIBLE) materials.merge(m, 1, Int::plus)
                }
                val info = Info(
                    a, listOf(box.sizeX, box.sizeY, box.sizeZ), materials.entries.sortedByDescending { it.value }.take(6).map { it.key.key.key },
                    if (materials.isEmpty()) null else plugin.assets.category(materials, box.sizeX, box.sizeY, box.sizeZ), counts.first, counts.second,
                )
                synchronized(infos) { infos[id(a)] = now to info }
            }
        }
        return synchronized(infos) { areas.mapNotNull { infos[id(it)]?.second } }
    }

    // ---- Preview ------------------------------------------------------------------------------------------------------

    /** A scene to render and the box it spans. */
    class Scene(val stage: Camera.Stage, val lo: Vector3d, val hi: Vector3d, val blocks: Int, val models: Int)

    /**
     * [area] alone, its blocks from y 0 up (everything around it air, so neighbours don't hide it) with its displays and
     * furniture, on a grid floor. Off the main thread.
     */
    fun scene(area: Area): Scene {
        val world = world(area)
        val box = area.bounds
        if (box.volume > 400_000) throw ToolError("${id(area)} is too big to preview (${box.volume} blocks)")
        Chunks.loadEntities(world, listOf(box))
        val (snapshots, entries) = Sync.main {
            Snapshots(world, box.min.x(), box.min.z(), box.max.x(), box.max.z()).load() to displays(world, box).mapNotNull(Displays::entry)
        }
        val cells = HashMap<Triple<Int, Int, Int>, BlockData>()
        for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
            if (!snapshots.type(x, y, z).isAir) cells[Triple(x - box.min.x(), y - box.min.y(), z - box.min.z())] = snapshots.data(x, y, z)
        }
        val shift = Matrix4d().translation(-box.min.x().toDouble(), -box.min.y().toDouble(), -box.min.z().toDouble())
        val models = Displays.bake(entries).map { it.baked.transformed(shift) }
        if (cells.isEmpty() && models.isEmpty()) throw ToolError("${id(area)} is empty")
        val lo = Vector3d(0.0)
        val hi = Vector3d(box.sizeX.toDouble(), box.sizeY.toDouble(), box.sizeZ.toDouble())
        models.forEach { lo.min(it.min); hi.max(it.max) }
        return Scene(Camera.blocks(cells, models), lo, hi, cells.size, models.size)
    }

    // ---- Paste --------------------------------------------------------------------------------------------------------

    /** Bottom centre of [box]: what pastes put at their position, and turn around. */
    fun origin(box: Box): BlockVector3 = BlockVector3.at(Math.floorDiv(box.min.x() + box.max.x(), 2), box.min.y(), Math.floorDiv(box.min.z() + box.max.z(), 2))

    /** The box [box] occupies pasted with its bottom centre at [position], turned [rotate]° clockwise seen from above. */
    fun footprint(box: Box, rotate: Int, position: BlockVector3): Box = Placement(origin(box), rotate, position).box(box)

    /**
     * What a paste copied: display entities, furniture adopted by its provider, furniture placed again, furniture lost,
     * block data entries, and the [instance] it recorded with its points and areas.
     */
    class Pasted(val displays: Int, val furniture: Int, val replaced: Int, val lost: Int, val blockData: Int, val instance: Instance)

    /**
     * Off the main thread, before [copy]: loads the entities of the asset and of where it goes, so that its furniture
     * and displays are found and the copies land in loaded chunks.
     */
    fun prepare(area: Area, world: World, result: Box) {
        val around = area.bounds.grow(Library.REACH, Library.REACH, Library.REACH)
        Furniture.ensureLoaded(world(area), around)
        Chunks.loadEntities(world(area), listOf(around))
        Chunks.loadEntities(world, listOf(result))
    }

    /**
     * An asset area read for [paste]: its blocks around its bottom centre, its display entities, its furniture, the block
     * data of its blocks ([ChunkBlockData]), and the points and other areas inside it.
     */
    class Copy(
        val area: Area, val origin: BlockVector3, val clipboard: Clipboard, val entities: List<CopiedEntity>, val furniture: List<Furniture.Placed>,
        val blockData: List<ChunkBlockData.Entry>, val points: List<Point>, val areas: List<Area>,
    )

    /** A display entity of an asset: which it is, where, and everything else about it. */
    class CopiedEntity(val id: UUID, val location: Location, val snapshot: EntitySnapshot)

    /**
     * Reads [area] for [paste]. Main thread, before the paste's edit session opens: a second session of the same world
     * open beside it would take its writes out of its history.
     */
    fun copy(area: Area): Copy {
        val source = world(area)
        val box = area.bounds
        Chunks.keepLoaded(source, box.grow(Library.REACH, Library.REACH, Library.REACH))
        val displays = displays(source, box)
        val furniture = furniture(source, box, displays)
        val entities = displays.mapNotNull { e -> e.createSnapshot()?.let { CopiedEntity(e.uniqueId, e.location, it) } }
        val clipboard = WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(source)).build().use { session ->
            val region = CuboidRegion(session.world, box.min, box.max)
            BlockArrayClipboard(region).also {
                it.origin = origin(box)
                Operations.complete(ForwardExtentCopy(session, region, it, region.minimumPoint).apply { isCopyingEntities = false })
            }
        }
        val worlds = Foreman.instance.worlds
        return Copy(
            area, origin(box), clipboard, entities, furniture, ChunkBlockData.read(source, box),
            worlds.points(area.world).filter { it.inside(box) },
            worlds.areas(area.world).filter { it.name != area.name && box.containsBox(it.bounds) },
        )
    }

    /**
     * Pastes [copy] into [scope]'s world, its bottom centre at [position], turned [rotate]° clockwise seen from above:
     * its blocks and their block data, then copies of its display entities with their whole pose turned (their
     * transformation stays, their position and yaw turn), tagged with the instance. Copies of furniture are taken in by
     * the furniture provider, or placed again through it when it doesn't. Then its points and areas, named after the
     * instance [id], and the [Instance] itself. Everything is recorded in the scope's change. Main thread.
     */
    fun paste(scope: EditScope, copy: Copy, position: BlockVector3, rotate: Int, ignoreAir: Boolean, id: String): Pasted {
        val world = scope.world
        val placement = Placement(copy.origin, rotate, position)
        val transform = placement.transform
        val result = placement.box(copy.area.bounds)
        Chunks.keepLoaded(world, result)
        scope.change.touch(result)

        val holder = ClipboardHolder(copy.clipboard)
        holder.transform = transform
        Operations.complete(holder.createPaste(scope.session).to(position).ignoreAirBlocks(ignoreAir).copyEntities(false).copyBiomes(false).build())
        // The blocks (furniture cells included) are in the world before the entities that claim them.
        scope.flush()
        val blockData = ChunkBlockData.copy(copy.blockData, placement, world, scope.change)

        val roots = copy.furniture.mapNotNull { it.entity }.toSet()
        val copies = HashMap<UUID, Entity>()
        val pasted = LinkedHashMap<UUID, UUID>()
        var displays = 0
        for (e in copy.entities) {
            val at = placement.location(e.location, world)
            val entity = e.snapshot.createEntity(at)
            entity.setRotation(at.yaw, at.pitch)
            (listOf(entity) + passengers(entity)).forEach { tag(it, id) }
            pasted[e.id] = entity.uniqueId
            if (e.id in roots) copies[e.id] = entity
            else {
                displays++
                (listOf(entity) + passengers(entity)).forEach { scope.change.spawnedEntities += it.uniqueId to it.location }
            }
        }
        Bukkit.getPluginManager().callEvent(placement.event(RegionCopyEvent.Kind.PASTE, world(copy.area), copy.area.bounds, world, pasted))

        val adopted = Furniture.inBox(world, result).filter { it.handle != null }.associateBy { it.entity }
        var taken = 0
        var replaced = 0
        var lost = 0
        for (f in copy.furniture) {
            val entity = f.entity?.let { copies[it] }
            val placed = entity?.let { adopted[it.uniqueId] }
            if (placed != null) {
                scope.change.spawnedFurniture += placed.handle!!
                taken++
                continue
            }
            // A provider that doesn't take in copied furniture places it again, as transform_region does.
            entity?.remove()
            f.entity?.let { pasted.remove(it) }
            val again = Furniture.provider?.type(f.id)?.let { Furniture.spawn(world, it, placement.block(f.x, f.y, f.z), f.yaw + rotate, f.state, scope.change) }
            if (again != null) {
                replaced++
                again.entity?.let { e -> Bukkit.getEntity(e)?.let { tag(it, id) }; f.entity?.let { pasted[it] = e } }
            } else lost++
        }
        val instance = record(scope, copy, placement, id, result, pasted, written(copy.clipboard, placement, result))
        return Pasted(displays, taken, replaced, lost, blockData, instance)
    }

    /** The blocks of [clipboard] that aren't air, where [placement] puts them in [box]: what a paste of it writes. */
    fun written(clipboard: Clipboard, placement: Placement, box: Box): Written =
        Written.of(box, clipboard.region.asSequence().filter { !clipboard.getBlock(it).blockType.material.isAir }.map(placement::block))

    /** The points and areas of [copy] made in the scope's world for instance [id], then the instance: all removed on undo. */
    private fun record(scope: EditScope, copy: Copy, placement: Placement, id: String, result: Box, entities: Map<UUID, UUID>, written: Written): Instance {
        val world = scope.world.name
        val worlds = Foreman.instance.worlds
        val points = copy.points.map { p -> placement.point(p, world).copy(build = if (p.build.isEmpty()) id else "$id/${p.build}") }
        val areas = copy.areas.map { a -> placement.area(a, world).copy(name = "$id/${a.name}", tags = a.tags - TAG) }
        val instance = Instance(
            id, world, id(copy.area), copy.area.world, copy.area.name, placement.position, placement.rotate, result, points, areas.map { it.name },
            entities, System.currentTimeMillis(), Caller.current()?.name ?: Caller.CONSOLE.name, written,
        )
        if (points.isNotEmpty()) worlds.addPoints(world, points)
        areas.forEach { worlds.put(it) }
        worlds.putInstance(instance)
        scope.change.restores += Runnable { forget(instance) }
        return instance
    }

    /** Removes [instance] with the points and areas it made (an undone paste). */
    fun forget(instance: Instance) {
        val worlds = Foreman.instance.worlds
        if (Bukkit.getWorld(instance.world) == null) return
        if (instance.points.isNotEmpty()) worlds.removePoints(instance.world) { it in instance.points }
        instance.areas.forEach { worlds.remove(it, instance.world) }
        worlds.removeInstance(instance.world, instance.id)
    }

    /** The persistent data key pasted entities carry: their instance's id. */
    val INSTANCE_KEY by lazy { NamespacedKey(Foreman.instance, "instance") }

    fun tag(entity: Entity, id: String) = entity.persistentDataContainer.set(INSTANCE_KEY, PersistentDataType.STRING, id)

    private fun passengers(e: Entity): List<Entity> = e.passengers.flatMap { listOf(it) + passengers(it) }
}
