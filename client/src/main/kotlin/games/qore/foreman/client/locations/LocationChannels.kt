package games.qore.foreman.client.locations

import games.qore.foreman.client.net.Net
import games.qore.foreman.client.util.Chat
import games.qore.foreman.api.ForemanAPI
import games.qore.foreman.api.packet.LocationCodecs

/** The location debugger channels (wire format in `LocationCodecs`, shared with the server). */
object LocationChannels {
    val addRequest = Net.outgoing(ForemanAPI.CHANNEL_LOCATION_ADD_REQUEST, LocationCodecs.ADD_REQUEST::encode)
    val updateDisplay = Net.outgoing(ForemanAPI.CHANNEL_LOCATION_UPDATE_DISPLAY, LocationCodecs.UPDATE_DISPLAY::encode)
    val relocate = Net.outgoing(ForemanAPI.CHANNEL_LOCATION_RELOCATE, LocationCodecs.RELOCATE::encode)

    fun init() {
        Net.incoming(ForemanAPI.CHANNEL_LOCATION_DATA, LocationCodecs.DATA::decode) { packet ->
            LocationStore.apply(packet)
            for (source in LocationStore.drainNewSources()) {
                Chat.warn("New location source '$source' (hidden: show it with L or /locations toggle $source)")
            }
        }
        Net.incoming(ForemanAPI.CHANNEL_LOCATION_CLEAR, LocationCodecs.CLEAR::decode) { packet ->
            val source = packet.source
            if (source == null) LocationStore.clear() else LocationStore.removeSource(source)
        }
    }
}
