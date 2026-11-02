package games.qore.foreman.api.packet

data class LocationUpdateDisplayPacket(
    val locationId: String,
    val source: String,
    val category: String,
    val newName: String?
)
