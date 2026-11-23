package games.qore.foreman.client.entity

import kotlinx.serialization.json.JsonObject
import net.minecraft.nbt.CompoundTag
import java.util.UUID

/**
 * What the server says of an entity (`list_entities` with its NBT), for the entity panel's tabs: its [type], scoreboard
 * [tags], persistent [data] (each key's `{type, value}`), whole [nbt] (what `/data get entity` shows), and the rest of
 * its entry ([json]: custom_name, text, persistent, health, attributes).
 */
class EntityInfo(val uuid: UUID, val type: String, val tags: List<String>, val data: JsonObject, val nbt: CompoundTag, val json: JsonObject)
