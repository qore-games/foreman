package games.qore.foreman.paper.event

import games.qore.foreman.api.data.LocationEntry
import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class LocationAddRequestEvent(
    val player: Player,
    val source: String,
    val category: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val metadata: Map<String, String>
) : Event(), Cancellable {

    private var cancelled = false
    val resultEntries = mutableListOf<LocationEntry>()

    override fun getHandlers(): HandlerList = handlerList

    override fun isCancelled(): Boolean = cancelled

    override fun setCancelled(cancel: Boolean) {
        cancelled = cancel
    }

    companion object {
        @JvmStatic
        val handlerList = HandlerList()
    }
}
