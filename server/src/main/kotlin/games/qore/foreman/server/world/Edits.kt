package games.qore.foreman.server.world

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
import com.sk89q.worldedit.regions.CuboidRegion
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.ToolError
import org.bukkit.Location
import org.bukkit.World
import java.util.ArrayDeque
import java.util.UUID

/**
 * One AI change, undone as a whole: FAWE edit sessions (several when it was flushed midway), snapshots of boxes changed
 * by WorldEdit commands ([restores]) and/or furniture placed and removed.
 */
class Change(val label: String, val world: World) {
    val sessions = mutableListOf<EditSession>()
    /** Put back on undo, newest first: what [Edits.snapshot] saved before a WorldEdit command ran. */
    val restores = mutableListOf<Runnable>()
    /** The blocks those snapshots hold, kept in memory until the change leaves the history. */
    var snapshotBlocks = 0L
    val spawnedFurniture = mutableListOf<UUID>()
    val removedFurniture = mutableListOf<RemovedFurniture>()
    var blocks = 0
    val time = System.currentTimeMillis()

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
    private val history = ArrayDeque<Change>()
    private const val HISTORY_SIZE = 50
    /** At most this many snapshot blocks in the history: older changes can't be undone beyond it. */
    private const val SNAPSHOT_BUDGET = 8_000_000L

    val recent: List<Change> get() = synchronized(history) { history.toList() }

    /** Runs [block] in a new edit session on the main thread and records it for undo. Returns its result. */
    fun <T> edit(world: World, label: String, block: (EditSession, Change) -> T): Pair<T, Change> =
        scoped(world, label) { block(it.session, it.change) }

    /**
     * Runs [block] with an [EditScope] that can flush midway. All its sessions are one undo step. When [block] throws,
     * everything it already wrote is rolled back before the error propagates: a failed edit leaves the world as it was.
     */
    fun <T> scoped(world: World, label: String, block: (EditScope) -> T): Pair<T, Change> = Sync.main {
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
        record(scope.change)
        result to scope.change
    }

    private fun rollback(change: Change) {
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
    }

    fun record(change: Change) = synchronized(history) {
        history.push(change)
        while (history.size > HISTORY_SIZE || (history.size > 1 && history.sumOf { it.snapshotBlocks } > SNAPSHOT_BUDGET)) history.removeLast()
    }

    /** Undoes the last [count] changes, newest first. Returns their labels. */
    fun undo(count: Int): List<String> = Sync.main {
        val undone = mutableListOf<String>()
        repeat(count) {
            val change = synchronized(history) { history.pollFirst() } ?: return@repeat
            rollback(change)
            Furniture.undo(change)
            undone += change.label
        }
        undone
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
