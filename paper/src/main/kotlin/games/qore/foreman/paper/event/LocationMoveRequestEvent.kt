package games.qore.foreman.paper.event

import org.bukkit.entity.Player
import org.bukkit.event.Cancellable
import org.bukkit.event.Event
import org.bukkit.event.HandlerList

class LocationMoveRequestEvent(
    val player: Player,
    val source: String,
    val category: String,
    val locationId: String,
    val newSource: String,
    val newCategory: String,
    val world: String,
    val currentX: Double,
    val currentY: Double,
    val currentZ: Double,
    val currentYaw: Float,
    val currentPitch: Float,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float
) : Event(), Cancellable {

    private var cancelled = false

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
