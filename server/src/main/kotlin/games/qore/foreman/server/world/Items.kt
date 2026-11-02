package games.qore.foreman.server.world

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Container
import org.bukkit.entity.ArmorStand
import org.bukkit.entity.Item
import org.bukkit.entity.ItemFrame
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack

/**
 * Counts an item everywhere it can hide, for dupe testing: an item that duplicates ends up somewhere, so a test counts
 * the whole server before and after and compares. Must not use WorldEdit types (loaded without FastAsyncWorldEdit).
 */
object Items {
    /** Where copies of the item were found, and how many. */
    data class Tally(val players: Long, val containers: Long, val entities: Long, val ground: Long, val spots: List<Spot>) {
        val total get() = players + containers + entities + ground
    }

    /** A place holding the item, for the report: how many, what and where. */
    data class Spot(val amount: Long, val what: String, val where: String)

    /** A box in a world, or a whole world's loaded chunks. */
    class Scope(val world: World, val box: IntArray? = null) {
        fun has(l: Location) = box == null ||
            (l.blockX in box[0]..box[3] && l.blockY in box[1]..box[4] && l.blockZ in box[2]..box[5])
    }

    /** Counts the item matching [match] across online players and the loaded blocks and entities of [scope]. */
    fun count(scope: Scope, match: (ItemStack) -> Boolean): Tally {
        val spots = mutableListOf<Spot>()
        fun tally(stacks: Iterable<ItemStack?>, what: () -> String, where: () -> String): Long {
            val n = stacks.filterNotNull().filter(match).sumOf { it.amount.toLong() }
            if (n > 0) spots += Spot(n, what(), where())
            return n
        }

        var players = 0L
        for (player in scope.world.players) {
            if (!scope.has(player.location)) continue  // a box scopes players by where they stand, like everything else
            players += tally(player.inventory.contents.asIterable(), { "${player.name} inventory" }, { pos(player.location) })
            players += tally(player.enderChest.contents.asIterable(), { "${player.name} ender chest" }, { pos(player.location) })
            player.itemOnCursor.takeIf(match::invoke)?.let { players += it.amount; spots += Spot(it.amount.toLong(), "${player.name} cursor", pos(player.location)) }
        }

        var containers = 0L
        var entities = 0L
        var ground = 0L
        for (chunk in scope.world.loadedChunks) {
            for (state in chunk.tileEntities) {
                val loc = state.location
                if (state is Container && scope.has(loc)) containers += tally(state.inventory.contents.asIterable(), { state.type.name.lowercase() }, { pos(loc) })
            }
        }
        for (entity in scope.world.entities) {
            if (entity is org.bukkit.entity.HumanEntity || !scope.has(entity.location)) continue  // players counted above
            val loc = entity.location
            when {
                entity is Item -> ground += tally(listOf(entity.itemStack), { "dropped item" }, { pos(loc) })
                entity is ItemFrame -> entities += tally(listOf(entity.item), { "item frame" }, { pos(loc) })
                entity is ArmorStand -> entities += tally(entity.equipment.armorContents.asList() + entity.equipment.itemInMainHand + entity.equipment.itemInOffHand, { "armor stand" }, { pos(loc) })
                entity is InventoryHolder -> entities += tally((entity as InventoryHolder).inventory.contents.asIterable(), { entity.type.name.lowercase() }, { pos(loc) })
            }
        }
        return Tally(players, containers, entities, ground, spots.sortedByDescending { it.amount })
    }

    private fun pos(l: Location) = "${l.blockX}, ${l.blockY}, ${l.blockZ}"
}
