package games.qore.foreman.api.packet

data class LocationAddRequestPacket(
    val source: String,
    val category: String,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val metadata: Map<String, String> = emptyMap()
)
