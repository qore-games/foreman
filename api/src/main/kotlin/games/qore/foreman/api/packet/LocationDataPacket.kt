package games.qore.foreman.api.packet

import games.qore.foreman.api.data.LocationAction
import games.qore.foreman.api.data.LocationEntry

data class LocationDataPacket(
    val source: String,
    val category: String,
    val action: LocationAction,
    val locations: List<LocationEntry>,
    val sourceColor: Int? = null,
    val categoryColor: Int? = null,
    val categoryDisplayText: String? = null
)
