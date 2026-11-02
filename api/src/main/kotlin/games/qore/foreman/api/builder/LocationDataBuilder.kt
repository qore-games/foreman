package games.qore.foreman.api.builder

import games.qore.foreman.api.data.DisplayConfig
import games.qore.foreman.api.data.LocationAction
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.data.LocationType
import games.qore.foreman.api.packet.LocationDataPacket

class LocationDataBuilder(
    private val source: String,
    private val category: String
) {
    private val locations = mutableListOf<LocationEntry>()
    private var defaultDisplay: DisplayConfig = DisplayConfig.DEFAULT
    private var action: LocationAction = LocationAction.ADD
    private var sourceColor: Int? = null
    private var categoryColor: Int? = null
    private var categoryDisplayText: String? = null

    fun setDefaultDisplay(display: DisplayConfig): LocationDataBuilder {
        this.defaultDisplay = display
        return this
    }

    fun setDefaultDisplay(builder: DisplayConfigBuilder.() -> Unit): LocationDataBuilder {
        this.defaultDisplay = DisplayConfigBuilder().apply(builder).build()
        return this
    }

    fun setAction(action: LocationAction): LocationDataBuilder {
        this.action = action
        return this
    }

    fun setSourceColor(color: Int?): LocationDataBuilder {
        this.sourceColor = color
        return this
    }

    fun setCategoryColor(color: Int?): LocationDataBuilder {
        this.categoryColor = color
        return this
    }

    fun setCategoryDisplayText(text: String?): LocationDataBuilder {
        this.categoryDisplayText = text
        return this
    }

    fun addLocation(id: String, type: LocationType, world: String, x: Double, y: Double, z: Double): LocationDataBuilder {
        locations.add(LocationEntry(id, type, world, x, y, z, display = defaultDisplay))
        return this
    }

    fun addLocation(id: String, type: LocationType, world: String, x: Double, y: Double, z: Double, display: DisplayConfig): LocationDataBuilder {
        locations.add(LocationEntry(id, type, world, x, y, z, display = display))
        return this
    }

    fun addLocation(id: String, type: LocationType, world: String, x: Double, y: Double, z: Double, displayBuilder: DisplayConfigBuilder.() -> Unit): LocationDataBuilder {
        val display = DisplayConfigBuilder().apply(displayBuilder).build()
        locations.add(LocationEntry(id, type, world, x, y, z, display = display))
        return this
    }

    fun addBlockLocation(id: String, world: String, x: Int, y: Int, z: Int): LocationDataBuilder {
        locations.add(LocationEntry(id, LocationType.BLOCK, world, x.toDouble(), y.toDouble(), z.toDouble(), display = defaultDisplay))
        return this
    }

    fun addBlockLocation(id: String, world: String, x: Int, y: Int, z: Int, display: DisplayConfig): LocationDataBuilder {
        locations.add(LocationEntry(id, LocationType.BLOCK, world, x.toDouble(), y.toDouble(), z.toDouble(), display = display))
        return this
    }

    fun addPreciseLocation(id: String, world: String, x: Double, y: Double, z: Double): LocationDataBuilder {
        locations.add(LocationEntry(id, LocationType.PRECISE, world, x, y, z, display = defaultDisplay))
        return this
    }

    fun addPreciseLocation(id: String, world: String, x: Double, y: Double, z: Double, display: DisplayConfig): LocationDataBuilder {
        locations.add(LocationEntry(id, LocationType.PRECISE, world, x, y, z, display = display))
        return this
    }

    fun addEntry(entry: LocationEntry): LocationDataBuilder {
        locations.add(entry)
        return this
    }

    fun clearLocations(): LocationDataBuilder {
        locations.clear()
        return this
    }

    fun build(): LocationDataPacket {
        return LocationDataPacket(source, category, action, locations.toList(), sourceColor, categoryColor, categoryDisplayText)
    }
}
