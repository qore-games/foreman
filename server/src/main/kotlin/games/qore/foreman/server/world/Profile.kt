package games.qore.foreman.server.world

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * A world's building profile (in its persistent data, see [WorldData]): what Foreman and its AI follow there instead
 * of their neutral defaults.
 *
 * - `theme`: the world's look and rules for the AI (palette, era, level design), shown by get_context.
 * - `build`: defaults of build_structure's arguments (`style`, `roof`, `decay`, `clutter`, `dark`, `furnish`,
 *   `room_width`, `floor_height`, `basement`…).
 * - `styles`: material presets by name, `{extends?: <preset>, wall: …}` (keys of list_styles), next to the server's
 *   (catalog/buildings.yml).
 * - `rooms`: room types of the furniture catalog (as in catalog/rooms.yml), replacing the server's.
 * - `uses`: room types in order by building use (`{"library": ["reading", …]}`), next to the server's.
 */
class Profile(val json: JsonObject) {
    val isEmpty get() = json.size() == 0
    val theme: String get() = json.get("theme")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
    val build: JsonObject get() = json.getAsJsonObject("build") ?: JsonObject()
    val styles: JsonObject get() = json.getAsJsonObject("styles") ?: JsonObject()
    val rooms: JsonObject get() = json.getAsJsonObject("rooms") ?: JsonObject()
    val uses: Map<String, List<String>>
        get() = (json.getAsJsonObject("uses") ?: JsonObject()).entrySet().associate { (k, v) -> k to v.asJsonArray.map { it.asString } }

    /** build_structure's default for [key]: the profile's, else [default]. */
    fun default(key: String): JsonElement? = build.get(key)?.takeIf { !it.isJsonNull }

    /** For get_context: the theme, then what the profile sets. */
    fun describe(): String {
        if (isEmpty) return "No building profile: neutral defaults (world_profile sets one)."
        val out = StringBuilder()
        if (theme.isNotEmpty()) out.appendLine(theme.trim())
        if (build.size() > 0) out.appendLine("build_structure defaults: " + build.entrySet().joinToString(", ") { "${it.key} ${it.value}" })
        if (styles.size() > 0) out.appendLine("Styles: ${styles.keySet().joinToString(", ")}")
        if (rooms.size() > 0) out.appendLine("Room types: ${rooms.keySet().joinToString(", ")}")
        if (uses.isNotEmpty()) out.appendLine("Uses: ${uses.keys.joinToString(", ")}")
        return out.toString().trimEnd()
    }
}
