package games.qore.foreman.client.entity

import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.core.BlockPos
import net.minecraft.nbt.TagParser
import net.minecraft.world.phys.Vec3
import org.joml.Quaterniond
import org.joml.Vector3d
import java.util.UUID

/**
 * The entity editor's calls, to the server's display tools (`edit_displays`, `spawn_display`, `undo`) and entity tools
 * (`list_entities`, `edit_entities`, `remove_entities`, `copy_entities`) off the client thread; answers come on it.
 */
object EntityServer {
    /** An entity the server couldn't change, and why. */
    class Failed(val uuid: UUID, val error: String)

    /** What an edit did: the displays it couldn't change, and those it spawned again under a new uuid (furniture moved to another block). */
    /** [poses]: what each edited display ended as on the server, by its uuid now (a furniture keeps its root block and
     * takes a move in its transformation: the client's preview of it was only how it looks). */
    class Edited(val failed: List<Failed>, val replaced: Map<UUID, UUID>, val poses: Map<UUID, DisplayPose>)

    /** Sets [poses] (yaw and pitch only for those in [facing]), one undo step. */
    fun edit(poses: Map<UUID, DisplayPose>, facing: Set<UUID>, done: (Result<Edited>) -> Unit) {
        val args = buildJsonObject {
            world()
            put("entities", JsonArray(poses.map { (id, pose) -> pose.json(id, id in facing) }))
            put("format", "json")
        }
        ServerCalls.run({
            val answer = json(ServerCalls.callNow("edit_displays", args))
            val replaced = answer["replaced"].obj().orEmpty().mapNotNull { (old, now) -> uuid(old)?.let { o -> now.str()?.let(::uuid)?.let { o to it } } }.toMap()
            Edited(failed(answer), replaced, poses(answer))
        }, done)
    }

    private fun failed(answer: JsonElement) = answer["failed"].arr().orEmpty().mapNotNull { f ->
        f["uuid"].str()?.let(::uuid)?.let { Failed(it, f["error"].str() ?: "refused") }
    }

    private fun uuid(text: String) = runCatching { UUID.fromString(text) }.getOrNull()

    /** The `displays` of an `edit_displays` answer, by uuid. */
    private fun poses(answer: JsonElement): Map<UUID, DisplayPose> = answer["displays"].arr().orEmpty().mapNotNull { d ->
        val id = d["uuid"].str()?.let(::uuid) ?: return@mapNotNull null
        val p = numbers(d["position"], 3) ?: return@mapNotNull null
        val t = d["transformation"]
        val translation = numbers(t["translation"], 3) ?: return@mapNotNull null
        val left = numbers(t["left_rotation"], 4) ?: return@mapNotNull null
        val scale = numbers(t["scale"], 3) ?: return@mapNotNull null
        val right = numbers(t["right_rotation"], 4) ?: return@mapNotNull null
        id to DisplayPose(
            Vec3(p[0], p[1], p[2]), d["yaw"].double()?.toFloat() ?: 0f, d["pitch"].double()?.toFloat() ?: 0f,
            Vector3d(translation[0], translation[1], translation[2]), Quaterniond(left[0], left[1], left[2], left[3]),
            Vector3d(scale[0], scale[1], scale[2]), Quaterniond(right[0], right[1], right[2], right[3]),
        )
    }.toMap()

    private fun numbers(e: JsonElement?, n: Int): List<Double>? = e.arr()?.mapNotNull { it.double() }?.takeIf { it.size == n }

    /** Spawns a display per spec (`spawn_display`'s arguments), one after the other: their uuids. Off the client thread. */
    private fun spawnNow(specs: List<JsonObject>): List<UUID> = specs.map { spec ->
        val args = buildJsonObject {
            spec.forEach { (k, v) -> put(k, v) }
            if ("world" !in spec) world()
            put("format", "json")
        }
        json(ServerCalls.callNow("spawn_display", args))["uuid"].str()?.let(::uuid) ?: throw AgentException("spawn_display gave no uuid")
    }

    /** Removes [uuids], any entity (furniture through its plugin), one undo step; [done] gets those it couldn't remove. */
    fun remove(uuids: Collection<UUID>, done: (Result<List<Failed>>) -> Unit) {
        val args = buildJsonObject {
            put("uuids", JsonArray(uuids.map { JsonPrimitive(it.toString()) }))
            world()
            put("format", "json")
        }
        ServerCalls.run({ failed(json(ServerCalls.callNow("remove_entities", args))) }, done)
    }

    /** What a duplication made: the copies' uuids, and the entities it couldn't copy. */
    class Copied(val ids: List<UUID>, val failed: List<Failed>)

    /** Copies [uuids] in place (`copy_entities`, one undo step), then spawns a display per spec (furniture's models). */
    fun duplicate(uuids: Collection<UUID>, specs: List<JsonObject>, done: (Result<Copied>) -> Unit) {
        ServerCalls.run({
            val answer = if (uuids.isEmpty()) null else json(ServerCalls.callNow("copy_entities", buildJsonObject {
                put("uuids", JsonArray(uuids.map { JsonPrimitive(it.toString()) }))
                world()
                put("format", "json")
            }))
            val copies = answer?.get("copies").obj().orEmpty().values.mapNotNull { it.str()?.let(::uuid) }
            Copied(copies + spawnNow(specs), answer?.let(::failed).orEmpty())
        }, done)
    }

    /** The server's undo: its last change, whoever made it. */
    fun undo(done: (Result<String>) -> Unit) = ServerCalls.run({ ServerCalls.text(ServerCalls.callNow("undo", buildJsonObject {})) }, done)

    /** The entities the server listed, and the furniture of the displays that are one's root (uuid to furniture id). */
    class Listed(val seen: Set<UUID>, val furniture: Map<UUID, String>)

    /** The entities between [from] and [to], for their furniture and to know which are this client's own. */
    fun listed(from: BlockPos, to: BlockPos, done: (Result<Listed>) -> Unit) {
        val args = buildJsonObject {
            put("from", JsonArray(listOf(from.x, from.y, from.z).map { JsonPrimitive(it) }))
            put("to", JsonArray(listOf(to.x, to.y, to.z).map { JsonPrimitive(it) }))
            world()
            put("format", "json")
        }
        ServerCalls.run({
            val list = json(ServerCalls.callNow("list_entities", args)).arr() ?: JsonArray(emptyList())
            val seen = list.mapNotNull { d -> d["uuid"].str()?.let(::uuid)?.let { it to d } }
            Listed(seen.map { it.first }.toSet(), seen.mapNotNull { (id, d) -> d["furniture"].obj()?.let { it["id"].str() }?.let { id to it } }.toMap())
        }, done)
    }

    /** What `list_entities` says of [uuid] with its NBT; null when the server has no such loaded entity. */
    fun info(uuid: UUID, done: (Result<EntityInfo?>) -> Unit) {
        val args = buildJsonObject {
            put("uuids", JsonArray(listOf(JsonPrimitive(uuid.toString()))))
            world()
            put("nbt", true)
            put("format", "json")
        }
        ServerCalls.run({
            json(ServerCalls.callNow("list_entities", args)).arr()?.firstOrNull()?.let { e ->
                val snbt = e["nbt"].str() ?: throw AgentException("list_entities gave no nbt: update the server's Foreman")
                EntityInfo(uuid, e["type"].str().orEmpty(), e["tags"].arr().orEmpty().mapNotNull { it.str() }, e["data"].obj() ?: JsonObject(emptyMap()), TagParser.parseCompoundFully(snbt), e.obj()!!)
            }
        }, done)
    }

    /** Changes [uuids] with `edit_entities`' [changes] (add_tags, set_data, merge_nbt…), one undo step; [done] gets those it couldn't change. */
    fun editEntities(uuids: Collection<UUID>, changes: JsonObject, done: (Result<List<Failed>>) -> Unit) {
        val args = buildJsonObject {
            put("uuids", JsonArray(uuids.map { JsonPrimitive(it.toString()) }))
            changes.forEach { (k, v) -> put(k, v) }
            world()
            put("format", "json")
        }
        ServerCalls.run({ failed(json(ServerCalls.callNow("edit_entities", args))) }, done)
    }

    private fun JsonObjectBuilder.world() {
        Bridge.world.takeIf { it.isNotEmpty() }?.let { put("world", it) }
    }

    /** The JSON a tool answered with `format: json`. */
    private fun json(result: JsonObject): JsonElement {
        val text = ServerCalls.text(result)
        return runCatching { JSON.parseToJsonElement(text) }.getOrElse { throw AgentException(text.ifBlank { "No answer" }) }
    }
}
