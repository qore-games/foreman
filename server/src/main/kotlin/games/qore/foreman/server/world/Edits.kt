package games.qore.foreman.server.world

import com.fastasyncworldedit.core.history.changeset.AbstractChangeSet
import com.fastasyncworldedit.core.history.changeset.AbstractDelegateChangeSet
import com.fastasyncworldedit.core.history.changeset.FaweStreamChangeSet
import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extension.input.InputParseException
import com.sk89q.worldedit.extension.input.ParserContext
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.function.mask.Mask
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.regions.CuboidRegion
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Caller
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import java.util.ArrayDeque
import java.util.UUID

/**
 * One AI change, undone as a whole: FAWE edit sessions (several when it was flushed midway), snapshots of boxes changed
 * by WorldEdit commands ([restores]), entities spawned and/or furniture placed and removed. Made by [author]: the player
 * the tool or command runs for, else the console (the server's token too).
 */
class Change(var label: String, val world: World, val author: Caller = Caller.current() ?: Caller.CONSOLE) {
    val sessions = mutableListOf<EditSession>()
    /** Put back on undo, newest first: what [Edits.snapshot] saved before a WorldEdit command ran. */
    val restores = mutableListOf<Runnable>()
    /** The blocks those snapshots hold, kept in memory until the change leaves the history. */
    var snapshotBlocks = 0L
    /** Entities it spawned (copied displays), by where they were spawned: removed on undo. */
    val spawnedEntities = mutableListOf<Pair<UUID, Location>>()
    val spawnedFurniture = mutableListOf<UUID>()
    val removedFurniture = mutableListOf<RemovedFurniture>()
    /** Changes made with it in other worlds, after it ([Edits.join]): undone with it, as one step. */
    val linked = mutableListOf<Change>()
    var blocks = 0
    val time = System.currentTimeMillis()
    /** Its place in the order changes were recorded, everyone's. */
    var order = 0L
        internal set
    private val touched = mutableListOf<Box>()

    /** Counts [box] as acted on, for what [box] covers: blocks changed outside its sessions, entities moved or removed. */
    fun touch(box: Box) {
        touched += box
    }

    fun touch(at: Location) = touch(BlockVector3.at(at.blockX, at.blockY, at.blockZ).let { Box(it, it) })

    private var found: Box? = null
    private var boxed = false

    /** Whether FAWE has written its sessions' histories (a few ms after the change): [box] is known from then on. */
    val written get() = sessions.all(Edits::written)

    /**
     * Where it acted: its sessions' blocks, what it [touch]ed, the entities it spawned and the furniture it removed. Read
     * from the sessions' histories once they are [written] (null before), after it was recorded.
     */
    val box: Box?
        get() = synchronized(this) {
            if (!boxed && written) {
                found = (touched + sessions.mapNotNull(Edits::blocksOf) + (spawnedEntities.map { it.second } + removedFurniture.map { it.location }).map {
                    BlockVector3.at(it.blockX, it.blockY, it.blockZ).let { p -> Box(p, p) }
                }).reduceOrNull(Box::union)
                boxed = true
            }
            found
        }

    data class RemovedFurniture(val id: String, val location: Location, val yaw: Float, val state: String, val handle: UUID? = null)
}

/**
 * The edit session of a change in progress. FAWE queues writes, so blocks set in [session] are not visible to world
 * reads (tree generation, surface lookups) until [flush] commits them and opens a new session in the same change.
 */
class EditScope(val world: World, val change: Change) {
    lateinit var session: EditSession
        private set

    internal fun open() {
        session = WorldEdit.getInstance().newEditSessionBuilder()
            .world(BukkitAdapter.adapt(world))
            .maxBlocks(Foreman.instance.limits.maxEditBlocks)
            .build()
    }

    internal fun close() {
        session.close()
        change.sessions += session
        // FAWE resets blockChangeCount on every operation: the change set holds the session's total.
        change.blocks += maxOf(session.changeSet?.longSize()?.toInt() ?: 0, session.blockChangeCount)
    }

    /** Commits the writes so far to the world, then continues in a new session. */
    fun flush() {
        close()
        open()
    }
}

object Edits {
    /** Each author's changes, newest first, by [Caller.id]: every player's own, one for the console and the server's token. */
    private val histories = HashMap<UUID, ArrayDeque<Change>>()
    private const val HISTORY_SIZE = 50
    /** At most this many snapshot blocks in all the histories: the oldest changes, anyone's, can't be undone beyond it. */
    private const val SNAPSHOT_BUDGET = 8_000_000L
    private var recorded = 0L

    /** Everyone's changes, newest first. */
    val recent: List<Change> get() = synchronized(histories) { histories.values.flatten() }.sortedByDescending { it.order }

    /** [author]'s changes, newest first: what their undo takes back, in this order. */
    fun of(author: Caller): List<Change> = synchronized(histories) { histories[author.id]?.toList().orEmpty() }

    /** The author named [name] among those with changes (`console`: the console's history), else null. */
    fun author(name: String): Caller? =
        if (name.equals(Caller.CONSOLE.name, true)) Caller.CONSOLE
        else synchronized(histories) { histories.values.mapNotNull { it.peekFirst()?.author } }.firstOrNull { it.name.equals(name, true) }

    /** The names of the authors with changes, for completion. */
    fun authors(): List<String> = synchronized(histories) { histories.values.mapNotNull { it.peekFirst()?.author?.name } }

    /** Runs [block] in a new edit session on the main thread and records it for undo. Returns its result. */
    fun <T> edit(world: World, label: String, block: (EditSession, Change) -> T): Pair<T, Change> =
        scoped(world, label) { block(it.session, it.change) }

    /**
     * Runs [block] with an [EditScope] that can flush midway. All its sessions are one undo step, recorded unless not a
     * [step] of its own: a part of one that [join] records. When [block] throws, everything it already wrote is rolled
     * back before the error propagates: a failed edit leaves the world as it was.
     */
    fun <T> scoped(world: World, label: String, step: Boolean = true, block: (EditScope) -> T): Pair<T, Change> = Sync.main {
        val scope = EditScope(world, Change(label, world))
        scope.open()
        val result = try {
            block(scope)
        } catch (e: Throwable) {
            scope.close()
            rollback(scope.change)
            Furniture.undo(scope.change)
            throw e
        }
        scope.close()
        if (step) record(scope.change) else ordered(scope.change)
        result to scope.change
    }.also { Locks.verify(it.second) }

    /**
     * Makes [changes], made one after the other by one author (in several worlds), one undo step under [label], the
     * newest of their author's history: the first holds the others ([Change.linked]). Those recorded leave the history;
     * the step is recorded again, whether or not the history still held its first change. Many changes are best made
     * as no [scoped] step of their own: each recorded pushes one of the author's oldest changes out. Returns the first.
     */
    fun join(changes: List<Change>, label: String): Change? {
        val head = changes.minByOrNull { it.order } ?: return null
        head.label = label
        val rest = changes.filter { it !== head }
        synchronized(histories) {
            changes.forEach { c -> histories[c.author.id]?.remove(c) }
            histories.values.removeIf { it.isEmpty() }
        }
        rest.sortedBy { it.order }.forEach { c ->
            head.linked += c
            head.linked += c.linked
            head.blocks += c.blocks
        }
        record(head)
        return head
    }

    /** Takes [change] back at once, out of its author's history: a change refused once made ([Locks.verify]). */
    fun revert(change: Change) = Sync.main {
        synchronized(histories) {
            histories[change.author.id]?.remove(change)
            histories.values.removeIf { it.isEmpty() }
        }
        rollback(change)
        Furniture.undo(change)
    }

    /** Whether [change] changed a block (its sessions' histories, once [Change.written]) or an entity where [inside] holds. */
    fun reaches(change: Change, inside: (Int, Int, Int) -> Boolean): Boolean {
        val entities = change.spawnedEntities.map { it.second } + change.removedFurniture.map { it.location }
        if (entities.any { inside(it.blockX, it.blockY, it.blockZ) }) return true
        return change.sessions.any { session ->
            val stream = history(session) as? FaweStreamChangeSet ?: return@any false
            stream.getBlockIterator(true).asSequence().any { inside(it.x, it.y, it.z) }
        }
    }

    /** Entities put back by an undo or moved by spawning them again get a new UUID: older changes still name the old one. */
    private val replacedEntities = HashMap<UUID, UUID>()

    /** [old] is now [new]. Main thread. */
    fun replaced(old: UUID, new: UUID) {
        if (old != new) replacedEntities[old] = new
    }

    /** What the entity [id] is now. Main thread. */
    fun current(id: UUID): UUID {
        var c = id
        while (true) c = replacedEntities[c] ?: return c
    }

    /** The entity [id] (or what it is now) of [at]'s world, loading the chunk at [at] when it isn't loaded. Main thread. */
    fun entity(id: UUID, at: Location): org.bukkit.entity.Entity? {
        val now = current(id)
        return at.world.getEntity(now) ?: at.chunk.entities.firstOrNull { it.uniqueId == now }
    }

    /**
     * The loaded entity [id] of [world]; with none, of the one world that has it (worlds cloned from one map share
     * entity UUIDs: a [ToolError] asks which when several do). Main thread.
     */
    fun loaded(world: World?, id: UUID): org.bukkit.entity.Entity? {
        if (world != null) return world.getEntity(id)
        val found = Bukkit.getWorlds().mapNotNull { it.getEntity(id) }
        if (found.size > 1) throw ToolError("$id is in several worlds (${found.joinToString { it.world.name }}): give world")
        return found.firstOrNull()
    }

    private fun rollback(change: Change) {
        Furniture.removeSpawned(change)
        change.spawnedEntities.forEach { (id, at) -> entity(id, at)?.remove() }
        change.sessions.asReversed().forEach { session ->
            WorldEdit.getInstance().newEditSessionBuilder().world(BukkitAdapter.adapt(change.world)).build().use {
                session.undo(it)
            }
        }
        change.restores.asReversed().forEach { it.run() }
    }

    /**
     * Saves the blocks and biomes of [box] now into [change], which puts them back on undo: the undo of a change made
     * outside Foreman's own edit sessions (a WorldEdit command). Main thread.
     */
    fun snapshot(change: Change, box: Box) {
        val weWorld = BukkitAdapter.adapt(change.world)
        val region = CuboidRegion(weWorld, box.min, box.max)
        val clipboard = BlockArrayClipboard(region)
        WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).build().use { source ->
            Operations.complete(ForwardExtentCopy(source, region, clipboard, region.minimumPoint).apply { isCopyingEntities = false; isCopyingBiomes = true })
        }
        change.restores += Runnable {
            WorldEdit.getInstance().newEditSessionBuilder().world(weWorld).build().use { target ->
                Operations.complete(ForwardExtentCopy(clipboard, region, target, region.minimumPoint).apply { isCopyingEntities = false; isCopyingBiomes = true })
            }
        }
        change.snapshotBlocks += box.volume
        change.touch(box)
    }

    /**
     * Adds [change] to its author's history, the newest, in its place in the order (given now unless [ordered] before).
     * Over the snapshot budget, the oldest changes of anyone's leave.
     */
    fun record(change: Change) {
        synchronized(histories) {
            if (change.order == 0L) change.order = ++recorded
            val history = histories.getOrPut(change.author.id) { ArrayDeque() }
            history.push(change)
            if (history.size > HISTORY_SIZE) history.removeLast()
            var snapshots = histories.values.sumOf { h -> h.sumOf { it.snapshotBlocks } }
            while (snapshots > SNAPSHOT_BUDGET) {
                val oldest = histories.values.mapNotNull { it.peekLast() }.filter { it !== change }.minByOrNull { it.order } ?: break
                histories[oldest.author.id]?.removeLast()
                snapshots -= oldest.snapshotBlocks
            }
            histories.values.removeIf { it.isEmpty() }
        }
    }

    /** Gives [change] its place in the order changes are made, unrecorded: a part of a step [join] records. */
    private fun ordered(change: Change) = synchronized(histories) { change.order = ++recorded }

    /** What an undo did: the labels undone, newest first, and the change it [stopped] at, which later changes [by] others overlap. */
    class Undone(val labels: List<String>, val stopped: Change? = null, val by: List<Change> = emptyList(), val locked: Area? = null) {
        /** Why it stopped, naming who changed what since or who locked it; [force]: how to undo it anyway. */
        fun refusal(force: String): String? = stopped?.let { c ->
            if (locked != null) return "\"${c.label}\" was not undone: ${Locks.refusal(locked)}"
            "\"${c.label}\" (${c.box?.describe()}) was not undone: " +
                by.take(3).joinToString("; ") { "${it.author.name} changed it since with \"${it.label}\" ${ago(it.time)} (${it.box?.describe()})" } +
                (if (by.size > 3) "; and ${by.size - 3} more changes" else "") + ". Undoing it would wipe that work: $force to undo it anyway."
        }
    }

    /**
     * Undoes [author]'s last [count] changes, newest first. Stops at one that a later change by someone else overlaps
     * (blocks or entities in its box), which undoing would wipe, unless [force].
     */
    fun undo(author: Caller, count: Int, force: Boolean): Undone {
        val mine = of(author).take(count)
        mine.minOfOrNull { it.order }?.let { from -> await(recent.filter { it.order >= from }) }
        return Sync.main { undoNow(author, count, force) }
    }

    /** Waits, off the main thread and 2 s at most, until FAWE has [Change.written] the histories of [changes], so their boxes are known. */
    fun await(changes: List<Change>) {
        if (Bukkit.isPrimaryThread()) return
        val all = changes.flatMap { listOf(it) + it.linked }
        val until = System.currentTimeMillis() + 2000
        while (all.any { !it.written } && System.currentTimeMillis() < until) Thread.sleep(10)
    }

    private fun undoNow(author: Caller, count: Int, force: Boolean): Undone {
        val undone = mutableListOf<String>()
        repeat(count) {
            val change = synchronized(histories) { histories[author.id]?.peekFirst() } ?: return Undone(undone)
            val steps = change.linked.asReversed() + change
            if (!force) steps.flatMap { overlapping(it, change) }.distinct().takeIf { it.isNotEmpty() }?.let { return Undone(undone, change, it) }
            steps.firstNotNullOfOrNull { c -> c.box?.let { Locks.locks(c.world.name, it) } }?.let { return Undone(undone, change, locked = it) }
            synchronized(histories) {
                histories[author.id]?.remove(change)
                histories.values.removeIf { it.isEmpty() }
            }
            steps.forEach {
                rollback(it)
                Furniture.undo(it)
            }
            undone += change.label
        }
        return Undone(undone)
    }

    /** The changes others recorded after [head] (whose step [change] is) that overlap [change]'s box, theirs linked included, newest first. */
    private fun overlapping(change: Change, head: Change = change): List<Change> {
        val box = change.box ?: return emptyList()
        return recent.filter { c ->
            c.order > head.order && c.author.id != head.author.id && (listOf(c) + c.linked).any { it.world == change.world && it.box?.intersect(box) != null }
        }
    }

    /** A line of a history: when, who, what, where. */
    fun describe(change: Change): String =
        "${ago(change.time)} ${change.author.name}: ${change.label} (${change.blocks} block${if (change.blocks == 1) "" else "s"}) in ${change.world.name}" + (change.box?.let { " ${it.describe()}" } ?: "")

    fun ago(time: Long): String {
        val s = (System.currentTimeMillis() - time) / 1000
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ${s % 3600 / 60} min ago"
            else -> "${s / 86_400} days ago"
        }
    }

    /**
     * FAWE writes a session's history after the session closed, on its threads, then closes it: closing it earlier (as
     * reading it through the change set's own iterators does) would lose what comes after, and undo with it.
     */
    private val closed = runCatching { AbstractChangeSet::class.java.getDeclaredField("closed").apply { isAccessible = true } }.getOrNull()

    private fun history(session: EditSession): AbstractChangeSet? {
        var set = session.changeSet
        while (set is AbstractDelegateChangeSet) set = set.parent
        return set as? AbstractChangeSet
    }

    /** Whether FAWE has finished writing [session]'s history. */
    internal fun written(session: EditSession) = history(session)?.let { closed?.getBoolean(it) } ?: true

    /** The box of the blocks [session] changed, read from its FAWE history once [written]. */
    internal fun blocksOf(session: EditSession): Box? {
        val stream = history(session) as? FaweStreamChangeSet ?: return null
        val lo = IntArray(3) { Int.MAX_VALUE }
        val hi = IntArray(3) { Int.MIN_VALUE }
        stream.getBlockIterator(true).forEach { c ->
            lo[0] = minOf(lo[0], c.x); lo[1] = minOf(lo[1], c.y); lo[2] = minOf(lo[2], c.z)
            hi[0] = maxOf(hi[0], c.x); hi[1] = maxOf(hi[1], c.y); hi[2] = maxOf(hi[2], c.z)
        }
        return if (lo[0] > hi[0]) null else Box(BlockVector3.at(lo[0], lo[1], lo[2]), BlockVector3.at(hi[0], hi[1], hi[2]))
    }

    fun pattern(session: EditSession, input: String): Pattern = try {
        WorldEdit.getInstance().patternFactory.parseFromInput(input, context(session))
    } catch (e: InputParseException) {
        throw ToolError("Invalid block pattern '$input': ${e.message}")
    }

    fun mask(session: EditSession, input: String): Mask = try {
        WorldEdit.getInstance().maskFactory.parseFromInput(input, context(session))
    } catch (e: InputParseException) {
        throw ToolError("Invalid mask '$input': ${e.message}")
    }

    private fun context(session: EditSession) = ParserContext().apply {
        extent = session
        world = session.world
        isRestricted = false
        isPreferringWildcard = false
    }
}
