package games.qore.foreman.server.world

import games.qore.foreman.server.Foreman
import org.bukkit.Bukkit
import org.bukkit.World

/**
 * Keeps the chunks the AI works in loaded. Without a player nearby they unload right after each call, and furniture
 * plugins forget the furniture in them (they usually track loaded furniture only). Tickets are released after
 * [IDLE_MS] without use.
 */
object Chunks {
    private const val IDLE_MS = 10 * 60 * 1000L
    private const val MAX_CHUNKS = 4096
    private var lastUse = 0L
    private val ticketed = HashSet<World>()

    /** Main thread. */
    fun keepLoaded(world: World, box: Box) {
        lastUse = System.currentTimeMillis()
        val minX = box.min.x() shr 4
        val maxX = box.max.x() shr 4
        val minZ = box.min.z() shr 4
        val maxZ = box.max.z() shr 4
        if ((maxX - minX + 1L) * (maxZ - minZ + 1L) > MAX_CHUNKS) return
        for (x in minX..maxX) for (z in minZ..maxZ) world.addPluginChunkTicket(x, z, Foreman.instance)
        ticketed += world
    }

    /**
     * Off the main thread: loads the chunks of [boxes] with their entities and waits until they are in (entities load
     * some ticks after their chunk), up to 5 seconds. True when some had to be loaded.
     */
    fun loadEntities(world: World, boxes: List<Box>): Boolean {
        if (Bukkit.isPrimaryThread()) return false
        val chunks = boxes.flatMap { b -> ((b.min.x() shr 4)..(b.max.x() shr 4)).flatMap { x -> ((b.min.z() shr 4)..(b.max.z() shr 4)).map { z -> x to z } } }.toSet()
        repeat(50) { attempt ->
            val ready = Sync.main {
                boxes.forEach { keepLoaded(world, it) }
                chunks.count { (x, z) ->
                    val chunk = world.getChunkAt(x, z)
                    chunk.isEntitiesLoaded || run { chunk.entities; false }
                } == chunks.size
            }
            if (ready) return attempt > 0
            Thread.sleep(100)
        }
        return false
    }

    fun start(plugin: Foreman) {
        Bukkit.getScheduler().runTaskTimer(plugin, Runnable {
            if (ticketed.isNotEmpty() && System.currentTimeMillis() - lastUse > IDLE_MS) release()
        }, 1200L, 1200L)
    }

    fun release() {
        ticketed.forEach { it.removePluginChunkTickets(Foreman.instance) }
        ticketed.clear()
    }
}
