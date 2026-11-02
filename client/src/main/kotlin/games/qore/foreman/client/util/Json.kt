package games.qore.foreman.client.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

val JSON = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = false
    explicitNulls = false
}

val PRETTY_JSON = Json(JSON) { prettyPrint = true }

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonElement?.int(): Int? = (this as? JsonPrimitive)?.intOrNull
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull
fun JsonElement?.double(): Double? = (this as? JsonPrimitive)?.doubleOrNull
fun JsonElement?.bool(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** [x, y, z] as ints. */
fun JsonElement?.blockPos(): IntArray? = arr()?.takeIf { it.size == 3 }?.map { it.int() ?: return null }?.toIntArray()
