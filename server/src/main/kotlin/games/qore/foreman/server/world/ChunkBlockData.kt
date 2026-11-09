package games.qore.foreman.server.world

import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType

/**
 * Block data of the CustomBlockData library, which many plugins use to keep data on a block: an entry of its chunk's
 * persistent data keyed `<namespace>:x<x>y<y>z<z>` (x and z within the chunk), the block's own container (with the
 * library's `customblockdata:protected` flag when set). WorldEdit copies blocks without it: Foreman's copies and moves
 * carry every namespace's entries to the destination blocks, undone with them. Main thread, chunks loaded.
 */
object ChunkBlockData {
    private val KEY = Regex("x(\\d{1,2})y(-?\\d+)z(\\d{1,2})")

    /** A block's entry of one namespace: its container serialized (a deep copy). */
    class Entry(val namespace: String, val x: Int, val y: Int, val z: Int, val data: ByteArray)

    /** The entries of the blocks of [box]. */
    fun read(world: World, box: Box): List<Entry> {
        val out = ArrayList<Entry>()
        for (cx in (box.min.x() shr 4)..(box.max.x() shr 4)) for (cz in (box.min.z() shr 4)..(box.max.z() shr 4)) {
            val pdc = world.getChunkAt(cx, cz).persistentDataContainer
            for (key in pdc.keys) {
                val m = KEY.matchEntire(key.key) ?: continue
                val (lx, y, lz) = m.destructured.let { (a, b, c) -> Triple(a.toInt(), b.toInt(), c.toInt()) }
                if (lx > 15 || lz > 15) continue
                val x = (cx shl 4) + lx
                val z = (cz shl 4) + lz
                if (!box.contains(x, y, z)) continue
                val data = runCatching { pdc.get(key, PersistentDataType.TAG_CONTAINER) }.getOrNull() ?: continue
                out += Entry(key.namespace, x, y, z, data.serializeToBytes())
            }
        }
        return out
    }

    /**
     * Copies [entries] (read from a source) to the blocks [placement] maps them to in [world]. A move ([from] its world)
     * first removes them at the source and clears [clear] (what the moved blocks replace). Recorded in [change]: undo
     * puts every entry it touched back as it was. Returns how many it wrote.
     */
    fun copy(entries: List<Entry>, placement: Placement, world: World, change: Change, from: World? = null, clear: Box? = null): Int {
        val before = LinkedHashMap<Slot, ByteArray?>()
        fun write(slot: Slot, data: ByteArray?) {
            val pdc = world(slot).getChunkAt(slot.x shr 4, slot.z shr 4).persistentDataContainer
            before.getOrPut(slot) { runCatching { pdc.get(slot.key, PersistentDataType.TAG_CONTAINER)?.serializeToBytes() }.getOrNull() }
            if (data == null) pdc.remove(slot.key)
            else pdc.set(slot.key, PersistentDataType.TAG_CONTAINER, container(pdc, data))
        }
        if (from != null) {
            entries.forEach { write(Slot(from.name, it.namespace, it.x, it.y, it.z), null) }
            clear?.let { box -> read(world, box).forEach { write(Slot(world.name, it.namespace, it.x, it.y, it.z), null) } }
        }
        for (e in entries) {
            val p = placement.block(e.x, e.y, e.z)
            write(Slot(world.name, e.namespace, p.x(), p.y(), p.z()), e.data)
        }
        if (before.isNotEmpty()) change.restores += Runnable {
            before.forEach { (slot, data) ->
                val w = Bukkit.getWorld(slot.world) ?: return@forEach
                val pdc = w.getChunkAt(slot.x shr 4, slot.z shr 4).persistentDataContainer
                if (data == null) pdc.remove(slot.key) else pdc.set(slot.key, PersistentDataType.TAG_CONTAINER, container(pdc, data))
            }
        }
        return entries.size
    }

    /** Removes [entries] (read from [world]), recorded in [change]: undo puts them back. Returns how many. */
    fun remove(world: World, entries: List<Entry>, change: Change): Int {
        val before = entries.map { Slot(world.name, it.namespace, it.x, it.y, it.z) to it.data }
        before.forEach { (slot, _) -> world.getChunkAt(slot.x shr 4, slot.z shr 4).persistentDataContainer.remove(slot.key) }
        if (before.isNotEmpty()) change.restores += Runnable {
            val w = Bukkit.getWorld(world.name) ?: return@Runnable
            before.forEach { (slot, data) ->
                val pdc = w.getChunkAt(slot.x shr 4, slot.z shr 4).persistentDataContainer
                pdc.set(slot.key, PersistentDataType.TAG_CONTAINER, container(pdc, data))
            }
        }
        return before.size
    }

    private fun world(slot: Slot): World = Bukkit.getWorld(slot.world)!!

    private fun container(pdc: PersistentDataContainer, data: ByteArray): PersistentDataContainer =
        pdc.adapterContext.newPersistentDataContainer().apply { readFromBytes(data) }

    /** A block's entry of one namespace. */
    private data class Slot(val world: String, val namespace: String, val x: Int, val y: Int, val z: Int) {
        val key: NamespacedKey get() = NamespacedKey.fromString("$namespace:x${x and 15}y${y}z${z and 15}")!!
    }
}
