package games.qore.foreman.api.packet

import games.qore.foreman.api.codec.Codec
import games.qore.foreman.api.codec.PacketReader
import games.qore.foreman.api.codec.PacketWriter
import games.qore.foreman.api.data.DisplayConfig
import games.qore.foreman.api.data.LocationAction
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.data.LocationType
import games.qore.foreman.api.data.RenderShape

/** The wire format of every location debugger channel, one codec per message, shared by the mod and the server. */
object LocationCodecs {
    val DISPLAY = object : Codec<DisplayConfig> {
        override fun write(writer: PacketWriter, value: DisplayConfig) = with(writer) {
            writeInt(value.color)
            writeOptional(value.name) { writeUtf(it) }
            writeBoolean(value.glow)
            writeVarInt(value.shape.id)
            writeFloat(value.size)
            writeFloat(value.visibleDistance)
        }

        override fun read(reader: PacketReader) = with(reader) {
            DisplayConfig(readInt(), readOptional { readUtf() }, readBoolean(), RenderShape.fromId(readVarInt()), readFloat(), readFloat())
        }
    }

    val ENTRY = object : Codec<LocationEntry> {
        override fun write(writer: PacketWriter, value: LocationEntry) = with(writer) {
            writeUtf(value.id)
            writeVarInt(value.type.id)
            writeUtf(value.world)
            writeDouble(value.x)
            writeDouble(value.y)
            writeDouble(value.z)
            writeFloat(value.yaw)
            writeFloat(value.pitch)
            DISPLAY.write(this, value.display)
            writeStringMap(value.metadata)
        }

        override fun read(reader: PacketReader) = with(reader) {
            LocationEntry(
                readUtf(), LocationType.fromId(readVarInt()), readUtf(), readDouble(), readDouble(), readDouble(),
                readFloat(), readFloat(), DISPLAY.read(this), readStringMap(),
            )
        }
    }

    val DATA = object : Codec<LocationDataPacket> {
        override fun write(writer: PacketWriter, value: LocationDataPacket) = with(writer) {
            writeUtf(value.source)
            writeUtf(value.category)
            writeVarInt(value.action.id)
            writeVarInt(value.locations.size)
            value.locations.forEach { ENTRY.write(this, it) }
            writeOptional(value.sourceColor) { writeInt(it) }
            writeOptional(value.categoryColor) { writeInt(it) }
            writeOptional(value.categoryDisplayText) { writeUtf(it) }
        }

        override fun read(reader: PacketReader) = with(reader) {
            val source = readUtf()
            val category = readUtf()
            val action = LocationAction.fromId(readVarInt())
            val locations = List(readVarInt()) { ENTRY.read(this) }
            LocationDataPacket(source, category, action, locations, readOptional { readInt() }, readOptional { readInt() }, readOptional { readUtf() })
        }
    }

    val CLEAR = object : Codec<ClearLocationsPacket> {
        override fun write(writer: PacketWriter, value: ClearLocationsPacket) = writer.writeOptional(value.source) { writeUtf(it) }
        override fun read(reader: PacketReader) = ClearLocationsPacket(reader.readOptional { readUtf() })
    }

    val ADD_REQUEST = object : Codec<LocationAddRequestPacket> {
        override fun write(writer: PacketWriter, value: LocationAddRequestPacket) = with(writer) {
            writeUtf(value.source)
            writeUtf(value.category)
            writeUtf(value.world)
            writeDouble(value.x)
            writeDouble(value.y)
            writeDouble(value.z)
            writeFloat(value.yaw)
            writeFloat(value.pitch)
            writeStringMap(value.metadata)
        }

        override fun read(reader: PacketReader) = with(reader) {
            LocationAddRequestPacket(readUtf(), readUtf(), readUtf(), readDouble(), readDouble(), readDouble(), readFloat(), readFloat(), readStringMap())
        }
    }

    val UPDATE_DISPLAY = object : Codec<LocationUpdateDisplayPacket> {
        override fun write(writer: PacketWriter, value: LocationUpdateDisplayPacket) = with(writer) {
            writeUtf(value.locationId)
            writeUtf(value.source)
            writeUtf(value.category)
            writeOptional(value.newName) { writeUtf(it) }
        }

        override fun read(reader: PacketReader) = with(reader) {
            LocationUpdateDisplayPacket(readUtf(), readUtf(), readUtf(), readOptional { readUtf() })
        }
    }

    val RELOCATE = object : Codec<LocationRelocatePacket> {
        override fun write(writer: PacketWriter, value: LocationRelocatePacket) = with(writer) {
            writeUtf(value.locationId)
            writeUtf(value.source)
            writeUtf(value.category)
            writeUtf(value.newSource)
            writeUtf(value.newCategory)
            writeUtf(value.world)
            writeDouble(value.currentX)
            writeDouble(value.currentY)
            writeDouble(value.currentZ)
            writeFloat(value.currentYaw)
            writeFloat(value.currentPitch)
            writeDouble(value.x)
            writeDouble(value.y)
            writeDouble(value.z)
            writeFloat(value.yaw)
            writeFloat(value.pitch)
        }

        override fun read(reader: PacketReader) = with(reader) {
            LocationRelocatePacket(
                readUtf(), readUtf(), readUtf(), readUtf(), readUtf(), readUtf(),
                readDouble(), readDouble(), readDouble(), readFloat(), readFloat(),
                readDouble(), readDouble(), readDouble(), readFloat(), readFloat(),
            )
        }
    }
}
