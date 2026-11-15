package games.qore.foreman.client.objects

import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.link.GameObject
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import net.minecraft.world.phys.Vec3

/**
 * The server's object tools for the objects tool (`place_objects`, `edit_objects`, `remove_objects`, `undo`): each a
 * call off the client thread, [done] on it with the object as the server has it now.
 */
object ObjectCalls {
    /** Places an object of [kind] in [world] at [anchors] with [data] (the fields given; the others take their defaults). */
    fun place(kind: String, world: String, dim: String, anchors: List<Vec3>, data: JsonObject, done: (Result<GameObject>) -> Unit) =
        ServerCalls.run({
            val result = call("place_objects", world) {
                put("objects", JsonArray(listOf(buildJsonObject {
                    put("kind", kind)
                    put("anchors", anchors(anchors))
                    put("data", data)
                })))
            }
            first(result["placed"], dim)
        }, done)

    /** Moves [obj] to [anchors] (null: where it is) and changes the fields of [data] (null: none; a null value: its default). */
    fun edit(obj: GameObject, anchors: List<Vec3>?, data: JsonObject?, done: (Result<GameObject>) -> Unit) = ServerCalls.run({
        val result = call("edit_objects", obj.world) {
            put("objects", JsonArray(listOf(buildJsonObject {
                put("id", obj.id)
                put("kind", obj.kind)
                anchors?.let { put("anchors", anchors(it)) }
                data?.let { put("data", it) }
            })))
        }
        first(result["edited"], obj.dim)
    }, done)

    /**
     * The choices the fields of [kind] that depend on others offer for [data] (the form's now), of the object [id] or a
     * new one: by field name.
     */
    fun choices(kind: String, world: String, id: String?, data: JsonObject, done: (Result<Map<String, List<String>>>) -> Unit) = ServerCalls.run({
        val result = call("object_kinds", world) {
            put("kind", kind)
            put("data", data)
            id?.let { put("id", it) }
        }
        val fields = result["kinds"].arr()?.firstOrNull()?.let { it as? JsonObject }?.get("fields").arr().orEmpty()
        fields.mapNotNull { it as? JsonObject }.filter { it["depends_on"].arr()?.isNotEmpty() == true }
            .associate { f -> f["name"].str().orEmpty() to f["values"].arr().orEmpty().mapNotNull { it.str() } }
    }, done)

    fun remove(obj: GameObject, done: (Result<Unit>) -> Unit) = ServerCalls.run({
        call("remove_objects", obj.world) {
            put("ids", JsonArray(listOf(JsonPrimitive(obj.id))))
            put("kind", obj.kind)
        }
        Unit
    }, done)

    /** Undoes the player's last change on the server: what it says. */
    fun undo(done: (Result<String>) -> Unit) = ServerCalls.run({ ServerCalls.text(ServerCalls.callNow("undo", buildJsonObject {})) }, done)

    private fun call(tool: String, world: String, body: JsonObjectBuilder.() -> Unit): JsonObject {
        val args = buildJsonObject {
            if (world.isNotEmpty()) put("world", world)
            put("format", "json")
            body()
        }
        return JSON.parseToJsonElement(ServerCalls.text(ServerCalls.callNow(tool, args))) as? JsonObject ?: throw AgentException("$tool answered something else")
    }

    private fun first(list: JsonElement?, dim: String): GameObject {
        val obj = list.arr()?.firstOrNull() ?: throw AgentException("The server changed nothing")
        return JSON.decodeFromJsonElement<GameObject>(obj).copy(dim = dim)
    }

    private fun anchors(anchors: List<Vec3>) = JsonArray(anchors.map { v -> JsonArray(listOf(v.x, v.y, v.z).map { JsonPrimitive(it) }) })
}
