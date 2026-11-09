package games.qore.foreman.client.regions

import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.link.Area
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.GamePoint
import games.qore.foreman.client.link.ServerCalls
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.world.phys.Vec3

/**
 * An area as the regions tool writes it: what the server keeps of it. [kind] makes it a claim, [theme] a zone; [data]
 * holds the fields of the declared types its kind and tags name.
 */
data class AreaSpec(
    val world: String, val name: String, val boxes: List<BlockBox>, val kind: String?, val note: String, val theme: String, val tags: List<String>,
    val data: JsonObject = JsonObject(emptyMap()),
) {
    companion object {
        fun of(a: Area) = AreaSpec(a.world, a.name, a.blockBoxes, a.kind, a.note, a.theme, a.tags, a.data)
    }
}

/**
 * The server's tools that change areas (`set_mark`, `claim`) and points (`points`), for the regions tool: each change
 * a few calls in a row off the client thread, [done] on it.
 */
object RegionCalls {
    /** A tag: a plain word, or a declared area type's `ns:name`. */
    private val TAG = Regex("[a-z0-9_-]+|[a-z0-9_.-]+:[a-z0-9_./-]+")

    private class Call(val tool: String, val args: JsonObject)

    fun isTag(tag: String) = tag.matches(TAG)

    /** Makes the area [old] (as the server has it; null for a new one) into [target]; locked to the player, it stays so. */
    fun write(old: AreaSpec?, target: AreaSpec, done: (Result<Unit>) -> Unit) {
        val calls = plan(old, target)
        val mine = old?.let { o -> Bridge.areas.firstOrNull { it.world == o.world && it.name == o.name } }?.lock?.mine == true
        // Made again (a rename, a note cleared): the new one is locked like the old.
        val remade = calls.any { it.tool == "set_mark" && it.args["delete"] != null }
        run(if (mine && remade) calls + lock(target.world, target.name, true) else calls, done)
    }

    /** Locks the area to the player ([on]), or unlocks it: while locked, only their Foreman edits go in. */
    fun lock(area: Area, on: Boolean, done: (Result<Unit>) -> Unit) = run(listOf(lock(area.world, area.name, on)), done)

    private fun lock(world: String, name: String, on: Boolean) = Call(if (on) "lock_area" else "unlock_area", args(world) { put("name", name) })

    /** Deletes the area: what is in the world stays. */
    fun delete(area: AreaSpec, done: (Result<Unit>) -> Unit) = run(listOf(delete(area.world, area.name)), done)

    /**
     * The calls that make [old] into [target]. `set_mark` with a box replaces an area's boxes but adds to a claim's,
     * and keeps a note it isn't given: a claim stops reserving space while its boxes are written, an area whose note
     * is cleared is made again. A renamed area is made under its new name, then the old one deleted.
     */
    private fun plan(old: AreaSpec?, target: AreaSpec): List<Call> {
        if (old != null && old.name != target.name) return plan(null, target) + delete(old.world, old.name)
        val out = mutableListOf<Call>()
        var before = old
        if (before != null && before.note.isNotEmpty() && target.note.isEmpty()) {
            out += delete(before.world, before.name)
            before = null
        }
        var kind = before?.kind
        val rewrite = before == null || before.boxes != target.boxes || before.note != target.note
        if (rewrite && kind != null) {
            out += claim(target, "delete")
            kind = null
        }
        val first = target.boxes.first()
        val themed = target.theme != before?.theme.orEmpty()
        val tagged = target.tags != before?.tags.orEmpty()
        // With the tags that make it of a type, in the same call: the server checks them together.
        val data = !LayoutForm.same(target.data, before?.data ?: JsonObject(emptyMap()))
        if (rewrite || themed || tagged || data) out += Call("set_mark", args(target.world) {
            put("name", target.name)
            if (rewrite) {
                corners(first)
                if (target.note.isNotEmpty()) put("note", target.note)
            }
            if (themed) put("theme", target.theme)
            if (tagged) put("tags", JsonArray(target.tags.map { JsonPrimitive(it) }))
            if (data) {
                put("data", target.data)
                put("replace_data", true)
            }
        })
        // Only a claim has several boxes: claim adds each.
        if (rewrite) for (box in target.boxes.drop(1)) {
            kind = target.kind ?: "build"
            out += claim(target, "set", box, kind)
        }
        if (target.kind != null && kind != target.kind) out += claim(target, "set", first, target.kind)
        if (target.kind == null && kind != null) out += claim(target, "delete")
        return out
    }

    private fun delete(world: String, name: String) = Call("set_mark", args(world) {
        put("name", name)
        put("delete", true)
    })

    private fun claim(area: AreaSpec, action: String, box: BlockBox? = null, kind: String? = null) = Call("claim", args(area.world) {
        put("action", action)
        put("name", area.name)
        box?.let { corners(it) }
        kind?.let { put("kind", it) }
    })

    /** Adds [point]. */
    fun add(point: GamePoint, done: (Result<Unit>) -> Unit) = run(listOf(add(point.world, point.build, listOf(point))), done)

    /** Removes [point]. */
    fun remove(point: GamePoint, done: (Result<Unit>) -> Unit) = run(remove(point), done)

    /**
     * Puts [new] in place of [old]: moved, turned or its data changed, one `points` move (a server with precise points:
     * it sent [old]'s position); else, or with its type, kind, build or room changed or its facing taken away, removed
     * and added again.
     */
    fun replace(old: GamePoint, new: GamePoint, done: (Result<Unit>) -> Unit) {
        val same = old.type == new.type && old.kind == new.kind && old.build == new.build && old.room == new.room
        val facing = (new.yaw != null || old.yaw == null) && (new.pitch != null || old.pitch == null)
        run(if (same && facing && old.position.size == 3) listOf(move(old, new)) else remove(old) + add(new.world, new.build, listOf(new)), done)
    }

    /**
     * `points` move: [old] by where it is exactly (its type and build to tell it apart) to [new]'s position and facing,
     * with [new]'s data when it changed (replacing the old: what the form shows is all of it).
     */
    private fun move(old: GamePoint, new: GamePoint) = Call("points", args(old.world) {
        put("action", "move")
        put("type", old.type)
        if (old.build.isNotEmpty()) put("build", old.build)
        put("at", exact(old.at))
        put("position", exact(new.at))
        new.yaw?.let { put("yaw", it) }
        new.pitch?.let { put("pitch", it) }
        if (!LayoutForm.same(old.data, new.data)) {
            put("data", new.data)
            put("replace_data", true)
        }
    })

    /**
     * `points` removes by block, type and build: the others it takes with [point] (the same block and type, another
     * kind or room, or another build when it has none) are added back.
     */
    private fun remove(point: GamePoint): List<Call> {
        val removed = Bridge.points.filter {
            it.world == point.world && it.block == point.block && it.type == point.type && (point.build.isEmpty() || it.build == point.build)
        }
        val others = removed.filter { !it.same(point) }
        return listOf(Call("points", args(point.world) {
            put("action", "remove")
            put("type", point.type)
            if (point.build.isNotEmpty()) put("build", point.build)
            put("from", vec(point.block))
            put("to", vec(point.block))
        })) + others.groupBy { it.build }.map { (build, list) -> add(point.world, build, list) }
    }

    private fun add(world: String, build: String, points: List<GamePoint>) = Call("points", args(world) {
        put("action", "add")
        if (build.isNotEmpty()) put("build", build)
        put("items", JsonArray(points.map { p ->
            buildJsonObject {
                put("pos", vec(p.block))
                if (p.position.size == 3) put("position", exact(p.at))
                p.yaw?.let { put("yaw", it) }
                p.pitch?.let { put("pitch", it) }
                put("type", p.type)
                if (p.kind.isNotEmpty()) put("kind", p.kind)
                if (p.room.isNotEmpty()) put("room", p.room)
                if (p.data.isNotEmpty()) put("data", p.data)
            }
        }))
    })

    /** Runs [calls] one after the other, stopping at the first that fails. */
    private fun run(calls: List<Call>, done: (Result<Unit>) -> Unit) = ServerCalls.run({ calls.forEach { ServerCalls.callNow(it.tool, it.args) } }, done)

    private fun args(world: String, body: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        if (world.isNotEmpty()) put("world", world)
        body()
    }

    private fun JsonObjectBuilder.corners(box: BlockBox) {
        put("from", vec(listOf(box.minX, box.minY, box.minZ)))
        put("to", vec(listOf(box.maxX, box.maxY, box.maxZ)))
    }

    private fun vec(v: List<Int>): JsonElement = JsonArray(v.map { JsonPrimitive(it) })

    private fun exact(v: Vec3): JsonElement = JsonArray(listOf(v.x, v.y, v.z).map { JsonPrimitive(it) })
}
