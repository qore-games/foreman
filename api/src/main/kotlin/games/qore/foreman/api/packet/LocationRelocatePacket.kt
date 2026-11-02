package games.qore.foreman.api.packet

data class LocationRelocatePacket(
    val locationId: String,
    val source: String,
    val category: String,
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
)
