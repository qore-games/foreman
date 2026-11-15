package games.qore.foreman.server.world

import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.extent.clipboard.Clipboard
import com.sk89q.worldedit.function.mask.Mask
import com.sk89q.worldedit.function.mask.MaskUnion
import com.sk89q.worldedit.function.mask.Masks
import com.sk89q.worldedit.function.mask.RegionMask
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
import org.bukkit.entity.Hanging
import org.bukkit.entity.LivingEntity
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

    /**
     * The entities a copy of [box] takes: those a move of it carries ([Library.entities]: displays, markers where plugins
     * keep data in the world, item frames, paintings, armor stands, mobs…; riders go with what they ride), persistent
     * ones only (plugins spawn their runtime parts again), mobs that despawn aside (they aren't part of a build). Main
     * thread.
     */
    fun copied(world: World, box: Box): List<Entity> =
        Library.entities(world, box).filter { it.isPersistent && !(it is LivingEntity && it.removeWhenFarAway) }

    /** The furniture whose root display is one of [displays] (its root may stand outside the box its model shows in). */
    private fun furniture(world: World, box: Box, displays: List<Entity>): List<Furniture.Placed> {
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

    /** The box [box] occupies pasted with its bottom centre at [position], mirrored when [mirror], turned [rotate]° clockwise seen from above. */
    fun footprint(box: Box, rotate: Int, position: BlockVector3, mirror: Boolean = false): Box = Placement(origin(box), rotate, position, mirror).box(box)

    /**
     * What a paste copied: entities besides furniture ([displays], markers, frames…), furniture adopted by its provider,
     * furniture placed again, furniture lost, block data entries, and the [instance] it recorded with its points and areas,
     * and the [children] nested in it.
     */
    class Pasted(val displays: Int, val furniture: Int, val replaced: Int, val lost: Int, val blockData: Int, val instance: Instance, val children: List<Instance>)

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
     * data of its blocks ([ChunkBlockData]), the points and other areas inside it, and the [instances] lying in it
     * (detached ones aside: they are plain content now), those of them changed since their paste in [edited]. [loose]: a
     * box copied to a clipboard ([Clipboards]) rather than an asset area, whose pastes are detached copies of
     * [Clipboards.ASSET], with no source to follow.
     */
    class Copy(
        val area: Area, val origin: BlockVector3, val clipboard: Clipboard, val entities: List<CopiedEntity>, val furniture: List<Furniture.Placed>,
        val blockData: List<ChunkBlockData.Entry>, val points: List<Point>, val areas: List<Area>, val instances: List<Instance>, val edited: Set<String>,
        val loose: Boolean = false,
    )

    /** An entity of an asset (a display, a marker): which it is, where, everything else about it, and the instance it was pasted by ([tag]). */
    class CopiedEntity(val id: UUID, val location: Location, val snapshot: EntitySnapshot, val tag: String?)

    /**
     * Reads [area] for [paste] ([loose]: a box for a clipboard, [Copy.loose]). Main thread, before the paste's edit session
     * opens: a second session of the same world open beside it would take its writes out of its history.
     */
    fun copy(area: Area, loose: Boolean = false): Copy {
        val source = world(area)
        val box = area.bounds
        Chunks.keepLoaded(source, box.grow(Library.REACH, Library.REACH, Library.REACH))
        val displays = copied(source, box)
        val furniture = furniture(source, box, displays)
        val entities = displays.mapNotNull { e ->
            e.createSnapshot()?.let { CopiedEntity(e.uniqueId, e.location, it, e.persistentDataContainer.get(INSTANCE_KEY, PersistentDataType.STRING)) }
        }
        val clipboard = WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(source)).build().use { session ->
            val region = CuboidRegion(session.world, box.min, box.max)
            BlockArrayClipboard(region).also {
                it.origin = origin(box)
                Operations.complete(ForwardExtentCopy(session, region, it, region.minimumPoint).apply { isCopyingEntities = false })
            }
        }
        val worlds = Foreman.instance.worlds
        val inner = worlds.instances(area.world).filter { !it.detached && box.containsBox(it.box) }
        return Copy(
            area, origin(box), clipboard, entities, furniture, ChunkBlockData.read(source, box),
            worlds.points(area.world).filter { it.inside(box) },
            worlds.areas(area.world).filter { it.name != area.name && box.containsBox(it.bounds) },
            inner, inner.filter { Fingerprints.state(source, it) == Fingerprints.State.EDITED }.map { it.id }.toSet(), loose,
        )
    }

    /**
     * How the instances lying in an asset's area ([inner]) become copies nested in instance [id] of its paste: each one's
     * id is `<id>/<its id>`, under the nearest of its ancestors that is nested too (`<id>/<parent's>/<its own>`; ancestors
     * that aren't, detached ones, join its path with `.`); the points and areas it owns (by name: its id, `<its id>/…`) and
     * the entities tagged with it go to it.
     */
    class Nesting(val id: String, val inner: List<Instance>) {
        private val byId = inner.associateBy { it.id }
        private val ids = HashMap<String, String>()

        /** The nested id of [s], one of [inner]. */
        fun childId(s: Instance): String = ids[s.id] ?: run {
            val ancestor = generateSequence(s.parent) { it.substringBeforeLast('/', "").ifEmpty { null } }.firstNotNullOfOrNull { byId[it] }
            val rest = (if (ancestor == null) s.id else s.id.removePrefix("${ancestor.id}/")).replace('/', '.')
            ((ancestor?.let(::childId) ?: id) + "/" + rest).also { ids[s.id] = it }
        }

        /** The instance of [inner] that [name] (a point's build, an area's name, an entity's tag) belongs to. */
        fun owner(name: String): Instance? = generateSequence(name.ifEmpty { null }) { it.substringBeforeLast('/', "").ifEmpty { null } }.firstNotNullOfOrNull { byId[it] }

        /** The id of the copy owning [name]: its owner's nested one, else [id]. */
        fun ownerId(name: String?): String = name?.let(::owner)?.let(::childId) ?: id

        /** [name] renamed for the paste: under its owner's nested id, else under [id] (`id` itself when empty). */
        fun rename(name: String): String = owner(name)?.let { childId(it) + name.removePrefix(it.id) } ?: if (name.isEmpty()) id else "$id/$name"
    }

    /**
     * Pastes [copy] into [scope]'s world, its bottom centre at [position], mirrored east–west when [mirror] then turned
     * [rotate]° clockwise seen from above: its blocks and their block data, then copies of its entities, their position,
     * yaw and facing placed so (a display's transformation and block mirrored too: [Placement.mirror]), tagged with the
     * instance (or the nested copy they belong to). Copies of furniture are taken in by the furniture provider, or placed again
     * through it when it doesn't. Then its points and areas, named after the instance [id], the [Instance] itself and the
     * copies nested in it ([Nesting]), with their fingerprints. Nothing is written in the boxes of [keep] (detached copies
     * a sync leaves as they are), nor nested copies made there. Everything is recorded in the scope's change. Main thread.
     */
    fun paste(scope: EditScope, copy: Copy, position: BlockVector3, rotate: Int, mirror: Boolean, ignoreAir: Boolean, id: String, keep: List<Instance> = emptyList()): Pasted {
        val world = scope.world
        val placement = Placement(copy.origin, rotate, position, mirror)
        val result = placement.box(copy.area.bounds)
        val nesting = Nesting(id, copy.instances)
        val kept = keep.map { it.box }
        fun isKept(x: Int, y: Int, z: Int) = kept.any { it.contains(x, y, z) }
        fun isKept(p: BlockVector3) = isKept(p.x(), p.y(), p.z())
        fun keptId(i: String) = keep.any { i == it.id || i.startsWith("${it.id}/") }
        Chunks.keepLoaded(world, result)
        scope.change.touch(result)

        val holder = ClipboardHolder(copy.clipboard)
        holder.transform = placement.transform
        val paste = holder.createPaste(scope.session).to(position).ignoreAirBlocks(ignoreAir).copyEntities(false).copyBiomes(false)
        if (kept.isNotEmpty()) paste.maskSource(outside(kept.map(placement.inverse()::box)))
        Operations.complete(paste.build())
        // The blocks (furniture cells included) are in the world before the entities that claim them.
        scope.flush()
        val blockData = ChunkBlockData.copy(copy.blockData.filter { !isKept(placement.block(it.x, it.y, it.z)) }, placement, world, scope.change)

        val roots = copy.furniture.mapNotNull { it.entity }.toSet()
        val tags = copy.entities.associate { it.id to nesting.ownerId(it.tag) }
        val copies = HashMap<UUID, Entity>()
        val pasted = LinkedHashMap<UUID, UUID>()
        val skipped = HashSet<UUID>()
        var displays = 0
        for (e in copy.entities) {
            val at = placement.location(e.location, world)
            if (isKept(at.blockX, at.blockY, at.blockZ) || keptId(tags.getValue(e.id))) {
                skipped += e.id
                continue
            }
            val entity = e.snapshot.createEntity(at)
            entity.setRotation(at.yaw, at.pitch)
            // Riders keep a yaw of their own: placed like the rest.
            passengers(entity).forEach { it.setRotation(placement.yaw(it.location.yaw), it.location.pitch) }
            for (part in listOf(entity) + passengers(entity)) {
                if (part is Hanging) part.setFacingDirection(placement.face(part.facing), true)
                if (part is Display) placement.mirror(part)
                tag(part, tags.getValue(e.id))
            }
            pasted[e.id] = entity.uniqueId
            if (e.id in roots) copies[e.id] = entity
            else {
                displays++
                (listOf(entity) + passengers(entity)).forEach { scope.change.spawnedEntities += it.uniqueId to it.location }
            }
        }
        // A clipboard's source world may have unloaded since: the event says only its coordinates matter then.
        val source = Bukkit.getWorld(copy.area.world) ?: world
        Bukkit.getPluginManager().callEvent(placement.event(RegionCopyEvent.Kind.PASTE, source, copy.area.bounds, world, pasted, clipboard = copy.loose))

        val adopted = Furniture.inBox(world, result).filter { it.handle != null }.associateBy { it.entity }
        var taken = 0
        var replaced = 0
        var lost = 0
        for (f in copy.furniture) {
            if (f.entity in skipped || f.entity == null && isKept(placement.block(f.x, f.y, f.z))) continue
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
            val again = Furniture.provider?.type(f.id)?.let { Furniture.spawn(world, it, placement.block(f.x, f.y, f.z), placement.yaw(f.yaw), f.state, scope.change) }
            if (again != null) {
                replaced++
                again.entity?.let { e -> world.getEntity(e)?.let { tag(it, f.entity?.let(tags::get) ?: id) }; f.entity?.let { pasted[it] = e } }
            } else lost++
        }
        val written = written(copy.clipboard, placement, result, kept)
        val records = record(scope, copy, placement, nesting, keep, result, pasted, tags, written)
        val edited = copy.edited.mapNotNull { e -> copy.instances.firstOrNull { it.id == e }?.let(nesting::childId) }.toSet()
        val fingerprinted = Fingerprints.taken(world, records, edited)
        Foreman.instance.worlds.putInstances(fingerprinted)
        return Pasted(displays, taken, replaced, lost, blockData, fingerprinted.first(), fingerprinted.drop(1))
    }

    /** A mask of a clipboard's blocks outside [boxes] (in its coordinates). */
    private fun outside(boxes: List<Box>): Mask {
        val masks = boxes.map { RegionMask(CuboidRegion(it.min, it.max)) as Mask }
        return Masks.negate(if (masks.size == 1) masks[0] else MaskUnion(masks))
    }

    /** The blocks of [clipboard] that aren't air, where [placement] puts them in [box], [kept] boxes aside: what a paste of it writes. */
    fun written(clipboard: Clipboard, placement: Placement, box: Box, kept: List<Box> = emptyList()): Written =
        Written.of(box, clipboard.region.asSequence().filter { !clipboard.getBlock(it).blockType.material.isAir }.map(placement::block)
            .filter { p -> kept.none { it.contains(p.x(), p.y(), p.z()) } })

    /**
     * The points and areas of [copy] made in the scope's world, then the instance [Nesting.id] and the copies nested in it
     * (those of [keep] aside): each with the points, areas and entities it owns. All removed on undo. Returns the instance
     * first, then its nested copies, not yet fingerprinted.
     */
    private fun record(
        scope: EditScope, copy: Copy, placement: Placement, nesting: Nesting, keep: List<Instance>, result: Box,
        entities: Map<UUID, UUID>, tags: Map<UUID, String>, written: Written,
    ): List<Instance> {
        val world = scope.world.name
        val worlds = Foreman.instance.worlds
        val id = nesting.id
        fun keptId(i: String) = keep.any { i == it.id || i.startsWith("${it.id}/") }
        val points = copy.points.map { p -> nesting.ownerId(p.build) to placement.point(p, world).copy(build = nesting.rename(p.build)) }.filter { !keptId(it.first) }
        val areas = copy.areas.map { a -> nesting.ownerId(a.name) to placement.area(a, world).copy(name = nesting.rename(a.name), tags = a.tags - TAG) }.filter { !keptId(it.first) }
        fun owned(owner: String) = entities.filter { (from, _) -> (tags[from] ?: id) == owner }
        val now = System.currentTimeMillis()
        val by = Caller.current()?.name ?: Caller.CONSOLE.name
        val instance = Instance(
            id, world, if (copy.loose) Clipboards.ASSET else id(copy.area), copy.area.world.takeIf { !copy.loose }, copy.area.name.takeIf { !copy.loose },
            placement.position, placement.rotate, result, points.filter { it.first == id }.map { it.second }, areas.filter { it.first == id }.map { it.second.name },
            owned(id), now, by, written, mirror = placement.mirror, detached = copy.loose,
        )
        val children = copy.instances.filter { !keptId(nesting.childId(it)) }.map { s ->
            val cid = nesting.childId(s)
            val (rotate, mirror) = placement.compose(s.rotate, s.mirror)
            val box = placement.box(s.box)
            val assetIds = s.entities.entries.associate { (asset, there) -> there to asset }
            Instance(
                cid, world, s.asset, s.sourceWorld, s.sourceArea, placement.block(s.origin), rotate, box,
                points.filter { it.first == cid }.map { it.second }, areas.filter { it.first == cid }.map { it.second.name },
                owned(cid).mapKeys { (from, _) -> assetIds[from] ?: from }, now, by,
                s.written?.let { w -> Written.of(box, w.blocks().map(placement::block).filter { written.has(it.x(), it.y(), it.z()) }) },
                mirror = mirror,
            )
        }
        if (points.isNotEmpty()) worlds.addPoints(world, points.map { it.second })
        areas.forEach { worlds.put(it.second) }
        val records = listOf(instance) + children
        worlds.putInstances(records)
        scope.change.restores += Runnable { records.asReversed().forEach(::forget) }
        return records
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
