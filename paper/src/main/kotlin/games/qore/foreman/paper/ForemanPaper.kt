package games.qore.foreman.paper

import games.qore.foreman.api.ForemanAPI
import games.qore.foreman.api.builder.DisplayConfigBuilder
import games.qore.foreman.api.builder.LocationDataBuilder
import games.qore.foreman.api.codec.Codec
import games.qore.foreman.api.data.LocationAction
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.packet.ClearLocationsPacket
import games.qore.foreman.api.packet.LocationCodecs
import games.qore.foreman.api.packet.LocationDataPacket
import games.qore.foreman.paper.event.LocationAddRequestEvent
import games.qore.foreman.paper.event.LocationMoveRequestEvent
import games.qore.foreman.paper.event.LocationUpdateDisplayEvent
import org.bukkit.entity.Player
import org.bukkit.event.Event
import org.bukkit.plugin.Plugin
import java.util.logging.Level

/**
 * Server side of the location debugger: sends locations to the foreman mod and turns its requests into events
 * ([LocationAddRequestEvent], [LocationMoveRequestEvent], [LocationUpdateDisplayEvent]).
 *
 * Runs inside the standalone Foreman plugin, or shaded into another plugin with [register].
 */
class ForemanPaper {
    companion object {
        /** The registered one (yours or another plugin's), `ForemanPaper.getInstance()` from Java. */
        @Volatile
        @JvmStatic
        var instance: ForemanPaper? = null
            private set

        @JvmStatic
        fun isInitialized(): Boolean = instance != null
    }

    private lateinit var plugin: Plugin

    /** Registers the channels with [plugin]. Returns false when Foreman is already registered. */
    fun register(plugin: Plugin): Boolean {
        if (instance != null) {
            plugin.logger.warning("Foreman already registered!")
            return false
        }
        this.plugin = plugin
        instance = this

        val messenger = plugin.server.messenger
        messenger.registerOutgoingPluginChannel(plugin, ForemanAPI.CHANNEL_LOCATION_DATA)
        messenger.registerOutgoingPluginChannel(plugin, ForemanAPI.CHANNEL_LOCATION_CLEAR)
        receive(ForemanAPI.CHANNEL_LOCATION_ADD_REQUEST, LocationCodecs.ADD_REQUEST) { player, p ->
            val event = LocationAddRequestEvent(player, p.source, p.category, p.world, p.x, p.y, p.z, p.yaw, p.pitch, p.metadata)
            if (call(event) && event.resultEntries.isNotEmpty()) {
                sendLocationData(player, LocationDataPacket(p.source, p.category, LocationAction.ADD, event.resultEntries))
            }
        }
        receive(ForemanAPI.CHANNEL_LOCATION_UPDATE_DISPLAY, LocationCodecs.UPDATE_DISPLAY) { player, p ->
            call(LocationUpdateDisplayEvent(player, p.source, p.category, p.locationId, p.newName))
        }
        receive(ForemanAPI.CHANNEL_LOCATION_RELOCATE, LocationCodecs.RELOCATE) { player, p ->
            call(LocationMoveRequestEvent(
                player, p.source, p.category, p.locationId, p.newSource, p.newCategory, p.world,
                p.currentX, p.currentY, p.currentZ, p.currentYaw, p.currentPitch, p.x, p.y, p.z, p.yaw, p.pitch,
            ))
        }
        plugin.logger.info("Foreman registered")
        return true
    }

    fun unregister() {
        if (instance !== this) return
        plugin.server.messenger.unregisterOutgoingPluginChannel(plugin)
        plugin.server.messenger.unregisterIncomingPluginChannel(plugin)
        instance = null
    }

    private fun <T> receive(channel: String, codec: Codec<T>, handle: (Player, T) -> Unit) {
        plugin.server.messenger.registerIncomingPluginChannel(plugin, channel) { _, player, data ->
            try {
                handle(player, codec.decode(data))
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Bad $channel message from ${player.name}", e)
            }
        }
    }

    /** Calls [event]; true when no listener cancelled it. */
    private fun <E> call(event: E): Boolean where E : Event, E : org.bukkit.event.Cancellable {
        plugin.server.pluginManager.callEvent(event)
        return !event.isCancelled
    }

    fun sendLocationData(player: Player, packet: LocationDataPacket) =
        player.sendPluginMessage(plugin, ForemanAPI.CHANNEL_LOCATION_DATA, LocationCodecs.DATA.encode(packet))

    fun sendLocationData(
        player: Player,
        source: String,
        category: String,
        action: LocationAction,
        locations: List<LocationEntry>,
        sourceColor: Int? = null,
        categoryColor: Int? = null,
        categoryDisplayText: String? = null,
    ) = sendLocationData(player, LocationDataPacket(source, category, action, locations, sourceColor, categoryColor, categoryDisplayText))

    fun sendClearLocations(player: Player, source: String? = null) =
        player.sendPluginMessage(plugin, ForemanAPI.CHANNEL_LOCATION_CLEAR, LocationCodecs.CLEAR.encode(ClearLocationsPacket(source)))

    fun sendLocationDataToAll(packet: LocationDataPacket) = plugin.server.onlinePlayers.forEach { sendLocationData(it, packet) }

    fun sendClearLocationsToAll(source: String? = null) = plugin.server.onlinePlayers.forEach { sendClearLocations(it, source) }

    fun builder(source: String, category: String): LocationDataBuilder = ForemanAPI.builder(source, category)

    fun displayConfig(): DisplayConfigBuilder = ForemanAPI.displayConfig()

    fun createAddPacket(source: String, category: String, builder: LocationDataBuilder.() -> Unit) = ForemanAPI.createAddPacket(source, category, builder)

    fun createReplacePacket(source: String, category: String, builder: LocationDataBuilder.() -> Unit) = ForemanAPI.createReplacePacket(source, category, builder)

    fun createRemovePacket(source: String, category: String) = ForemanAPI.createRemovePacket(source, category)
}
