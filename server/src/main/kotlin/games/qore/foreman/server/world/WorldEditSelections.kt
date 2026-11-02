package games.qore.foreman.server.world

import com.sk89q.worldedit.IncompleteRegionException
import com.sk89q.worldedit.WorldEdit
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.entity.Player

/** A player's place for `/foreman` commands: their WorldEdit selection, or the block they stand on. */
object WorldEditSelections {
    fun of(player: Player): Box? = try {
        val session = WorldEdit.getInstance().sessionManager.get(BukkitAdapter.adapt(player))
        val region = session.getSelection(BukkitAdapter.adapt(player.world))
        Box(region.minimumPoint, region.maximumPoint)
    } catch (_: IncompleteRegionException) {
        null
    }

    fun here(player: Player): Box = player.location.let {
        val p = BlockVector3.at(it.blockX, it.blockY, it.blockZ)
        Box(p, p)
    }
}
