package games.qore.foreman.api

import games.qore.foreman.api.builder.DisplayConfigBuilder
import games.qore.foreman.api.builder.LocationDataBuilder
import games.qore.foreman.api.data.LocationAction
import games.qore.foreman.api.packet.LocationDataPacket

/**
 * Channels of the location debugger and helpers to build what is sent on them (codecs: `LocationCodecs`). Static for
 * Java: `ForemanAPI.builder("myplugin", "spawns")`.
 */
object ForemanAPI {
    /** S2C [LocationDataPacket]. */
    const val CHANNEL_LOCATION_DATA = "foreman:location_data"
    /** S2C `ClearLocationsPacket`. */
    const val CHANNEL_LOCATION_CLEAR = "foreman:location_clear"
    /** C2S `LocationAddRequestPacket`. */
    const val CHANNEL_LOCATION_ADD_REQUEST = "foreman:location_add_request"
    /** C2S `LocationUpdateDisplayPacket`. */
    const val CHANNEL_LOCATION_UPDATE_DISPLAY = "foreman:location_update_display"
    /** C2S `LocationRelocatePacket`: a move, and/or a change of source or category. */
    const val CHANNEL_LOCATION_RELOCATE = "foreman:location_relocate"

    @JvmStatic
    fun builder(source: String, category: String) = LocationDataBuilder(source, category)

    @JvmStatic
    fun displayConfig() = DisplayConfigBuilder()

    @JvmStatic
    fun createAddPacket(source: String, category: String, builder: LocationDataBuilder.() -> Unit): LocationDataPacket =
        LocationDataBuilder(source, category).setAction(LocationAction.ADD).apply(builder).build()

    @JvmStatic
    fun createReplacePacket(source: String, category: String, builder: LocationDataBuilder.() -> Unit): LocationDataPacket =
        LocationDataBuilder(source, category).setAction(LocationAction.REPLACE).apply(builder).build()

    @JvmStatic
    fun createRemovePacket(source: String, category: String) = LocationDataPacket(source, category, LocationAction.REMOVE, emptyList())
}
