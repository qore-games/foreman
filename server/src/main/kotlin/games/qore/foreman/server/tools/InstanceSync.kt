package games.qore.foreman.server.tools

import com.sk89q.worldedit.extent.clipboard.Clipboard
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.world.Area
import games.qore.foreman.server.world.AreaAssets
import games.qore.foreman.server.world.Assets
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Change
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Clipboards
import games.qore.foreman.server.world.EditScope
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Fingerprints
import games.qore.foreman.server.world.Instance
import games.qore.foreman.server.world.Instances
import games.qore.foreman.server.world.Locks
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit

/**
 * Syncing pasted copies with their asset (sync_instance, sync_asset). Which: not the detached ones, those changed since
 * their paste (unless forced), those nested in another copy synced with them (it makes them again), those lying in
 * their own asset's area, nested ones whose asset no longer fits their box, nor those in an area someone else locked. Each is then removed with the copies
 * nested in it, its detached ones kept as they are, and its asset pasted again in its place, under its id: its nested
 * copies made again from the asset's current inner instances. One undo step, whatever the worlds.
 */
object InstanceSync {
    /** An asset's content, read once for every copy synced: an asset [area] or a [schematic]. */
    class Source private constructor(val id: String, val area: Area?, val schematic: Assets.Asset?, val clipboard: Clipboard?) {
        /** The area read, once the copies are prepared. */
        var copy: AreaAssets.Copy? = null

        /** The box a paste of it at [i]'s place takes. */
        fun footprint(i: Instance): Box =
            if (area != null) AreaAssets.footprint(area.bounds, i.rotate, i.origin, i.mirror) else AssetTools.footprint(clipboard!!, i.rotate, i.origin, i.mirror)

        companion object {
            /** The asset [i] was pasted from. */
            fun of(plugin: Foreman, i: Instance): Source {
                if (i.sourceWorld == null || i.sourceArea == null) return schematic(plugin, i.asset, "${i.id} comes from schematic ${i.asset}, which isn't in the library any more (list_assets)")
                val world = Bukkit.getWorld(i.sourceWorld)
                    ?: throw ToolError("${i.id} comes from ${i.asset}, whose world ${i.sourceWorld} isn't loaded: load it (open_asset_source), then sync again")
                val area = plugin.worlds.area(i.sourceArea, world.name)?.takeIf { AreaAssets.TAG in it.tags }
                    ?: throw ToolError("${i.id} comes from ${i.asset}, which isn't an asset area any more (list_assets)")
                return area(plugin, area)
            }

            /** The asset [name]: an area id, the name of an asset area of a loaded world, or a schematic's. */
            fun of(plugin: Foreman, name: String): Source {
                if (AreaAssets.isId(name)) return area(plugin, AreaAssets.get(plugin.worlds, name))
                val areas = AreaAssets.all(plugin.worlds).filter { it.name == name }
                if (areas.size > 1) throw ToolError("Several asset areas are named $name: give its id (${areas.joinToString(", ") { AreaAssets.id(it) }})")
                return areas.singleOrNull()?.let { area(plugin, it) } ?: schematic(plugin, name, null)
            }

            private fun area(plugin: Foreman, area: Area): Source {
                area.bounds.requireVolume(plugin.limits.maxEditBlocks.toLong(), "sync")
                return Source(AreaAssets.id(area), area, null, null)
            }

            private fun schematic(plugin: Foreman, name: String, gone: String?): Source {
                val asset = try {
                    plugin.assets.get(name)
                } catch (e: ToolError) {
                    throw if (gone != null) ToolError(gone) else e
                }
                return Source(asset.name, null, asset, plugin.assets.read(asset))
            }
        }
    }

    /** A copy left as it is, and why. */
    class Skip(val instance: Instance, val reason: String)

    /** What a sync does: the copies it pastes again, those it leaves, and those made again with the copy they are nested in. */
    class Plan(val sync: List<Instance>, val skipped: List<Skip>, val covered: Map<Instance, Instance>)

    /** Which of [instances] (copies of [source]) a sync takes, unless [force] those changed aside. Off the main thread. */
    fun plan(plugin: Foreman, source: Source, instances: List<Instance>, force: Boolean): Plan {
        val sync = ArrayList<Instance>()
        val skipped = ArrayList<Skip>()
        val covered = LinkedHashMap<Instance, Instance>()
        for (i in instances.sortedWith(compareBy({ it.id.count { c -> c == '/' } }, { it.world }, { it.id }))) {
            val world = Bukkit.getWorld(i.world)
            if (world == null) {
                skipped += Skip(i, "its world isn't loaded")
                continue
            }
            if (i.detached) {
                skipped += Skip(i, if (Clipboards.pasted(i)) "pasted from a clipboard: no asset to follow" else "detached (attach_instance to make it follow its asset again)")
                continue
            }
            val with = sync.firstOrNull { it.world == i.world && i.isUnder(it.id) }
            if (with != null) {
                covered[i] = with
                continue
            }
            val area = source.area
            if (area != null && i.world == area.world && area.bounds.containsBox(i.box)) {
                skipped += Skip(i, "lies in its own asset's area")
                continue
            }
            val parent = i.parent?.let { plugin.worlds.instance(i.world, it) }
            if (parent != null) {
                val box = source.footprint(i)
                if (box != i.box) {
                    skipped += Skip(i, "its asset's box changed size (${size(i.box)} → ${size(box)}) and would spill out of its place in ${parent.id}: sync ${parent.asset} instead")
                    continue
                }
            }
            val locked = runCatching { Locks.guard(i.world, i.box) }.exceptionOrNull()
            if (locked != null) {
                skipped += Skip(i, locked.message ?: "locked")
                continue
            }
            Instances.prepare(world, i)
            val (changed, edited) = Sync.main { Fingerprints.changed(world, i).let { it to Fingerprints.edited(world, i, it) } }
            if (edited.isNotEmpty() && !force) {
                skipped += Skip(i, edited.joinToString("; ") { e ->
                    when {
                        e !== i -> "${e.id}, nested in it, was changed"
                        i.edited && !changed -> "it was pasted with the changes its source copy had (in the asset holding it)"
                        else -> "it was changed since its paste"
                    }
                } + " (force: true replaces the changes)")
                continue
            }
            sync += i
        }
        return Plan(sync, skipped, covered)
    }

    private fun size(b: Box) = "${b.sizeX}x${b.sizeY}x${b.sizeZ}"

    /** A copy synced: its record now and the copies nested in it. */
    class Synced(val instance: Instance, val children: List<Instance>)

    /** What [run] did: the copies synced, the blocks changed, its undo step (null when it synced nothing). */
    class Result(val synced: List<Synced>, val blocks: Int, val change: Change?)

    /** Syncs the copies of [plan] with [source], labelled [label] in the history: one undo step. Off the main thread. */
    fun run(plugin: Foreman, source: Source, plan: Plan, label: String): Result {
        if (plan.sync.isEmpty()) return Result(emptyList(), 0, null)
        plan.sync.forEach { Locks.guard(it.world, it.box) }
        for (i in plan.sync) {
            val world = Bukkit.getWorld(i.world) ?: continue
            Instances.prepare(world, i)
            val area = source.area
            if (area != null) AreaAssets.prepare(area, world, source.footprint(i)) else Chunks.loadEntities(world, listOf(source.footprint(i)))
        }
        source.area?.let { area -> source.copy = Sync.main { AreaAssets.copy(area) } }
        val changes = ArrayList<Change>()
        val synced = ArrayList<Synced>()
        try {
            for ((name, list) in plan.sync.groupBy { it.world }) {
                val world = Bukkit.getWorld(name) ?: continue
                val (done, change) = Edits.scoped(world, label, step = false) { scope -> list.map { one(plugin, scope, source, it) } }
                changes += change
                synced += done
            }
        } catch (e: Throwable) {
            // The worlds already synced stay one undo step.
            Edits.join(changes, label)
            throw e
        }
        val blocks = changes.sumOf { it.blocks }
        return Result(synced, blocks, Edits.join(changes, label))
    }

    /** Removes [i] (its detached nested copies aside) and pastes its asset again in its place. Main thread. */
    private fun one(plugin: Foreman, scope: EditScope, source: Source, i: Instance): Synced {
        val keep = Instances.detachedIn(i)
        Instances.delete(scope, i, keep)
        scope.flush()
        val copy = source.copy
        if (copy != null) return AreaAssets.paste(scope, copy, i.origin, i.rotate, i.mirror, true, i.id, keep).let { Synced(it.instance, it.children) }
        return Synced(AssetTools.pasteSchematic(plugin, scope, source.schematic!!.name, source.clipboard!!, i.origin, i.rotate, i.mirror, true, i.id), emptyList())
    }
}
