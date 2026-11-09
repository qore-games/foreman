package games.qore.foreman.client.library

import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.link.AssetInstance
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.CompletableFuture

/** The copy's dimension, else the player's when it is in the player's world, else null. */
fun AssetInstance.dimension(): String? =
    dim.ifEmpty { if (world == Bridge.world) Minecraft.getInstance().level?.dimension()?.identifier()?.toString() else null }

/** An asset area of the server, by its world and name. */
data class AssetRef(val world: String, val name: String) {
    /** Its id in the library (`list_assets`, `paste_asset`). */
    val id get() = "area:$world/$name"

    fun area() = Bridge.areas.firstOrNull { it.world == world && it.name == name }
}

/**
 * The server's tools for making and changing assets and their pasted copies, for the asset tool and the panels: each
 * call off the client thread, [done] on it with the tool's text (an error result as a failure).
 */
object AssetCalls {
    fun call(tool: String, args: JsonObject, done: (Result<String>) -> Unit) =
        ServerCalls.run({ ServerCalls.text(ServerCalls.callNow(tool, args)) }, done)

    /** Makes [name] an asset of [box] in [world], or changes the one of that name (tags, note, box). */
    fun define(name: String, world: String, box: BlockBox, tags: List<String>, note: String, fit: Boolean, plot: Boolean, done: (Result<String>) -> Unit) =
        call("define_asset", args(world) {
            put("name", name)
            put("from", vec(box.minX, box.minY, box.minZ))
            put("to", vec(box.maxX, box.maxY, box.maxZ))
            put("tags", JsonArray((listOf(Library.TAG) + tags).distinct().map { JsonPrimitive(it) }))
            put("note", note)
            if (fit) put("fit", true)
            if (plot) put("plot", true)
            put("format", "json")
        }, done)

    fun fit(asset: AssetRef, done: (Result<String>) -> Unit) = call("fit_asset", args(asset.world) { put("name", asset.name) }, done)

    /** Changes the asset's box only: what is in the world stays. */
    fun resize(asset: AssetRef, box: BlockBox, done: (Result<String>) -> Unit) = call("resize_asset", args(asset.world) {
        put("name", asset.name)
        put("from", vec(box.minX, box.minY, box.minZ))
        put("to", vec(box.maxX, box.maxY, box.maxZ))
    }, done)

    /** Moves the asset with everything in it [by] blocks, or turns it [rotate] degrees clockwise in place. */
    fun move(asset: AssetRef, by: IntArray? = null, rotate: Int? = null, done: (Result<String>) -> Unit) = call("move_asset", args(asset.world) {
        put("name", asset.name)
        by?.let { put("by", vec(it[0], it[1], it[2])) }
        rotate?.let { put("rotate", it) }
        put("format", "json")
    }, done)

    /** Replaces the asset's tags (`define_asset` only adds). */
    fun retag(asset: AssetRef, tags: List<String>, done: (Result<String>) -> Unit) = call("set_mark", args(asset.world) {
        put("name", asset.name)
        put("tags", JsonArray((listOf(Library.TAG) + tags).distinct().map { JsonPrimitive(it) }))
    }, done)

    /** Deletes the asset's area: what it holds stays in the world. */
    fun delete(asset: AssetRef, done: (Result<String>) -> Unit) = call("set_mark", args(asset.world) {
        put("name", asset.name)
        put("delete", true)
    }, done)

    /** The pasted copies of [asset] (its library id), in every world. */
    fun copies(asset: String, done: (Result<List<AssetInstance>>) -> Unit) = ServerCalls.run({
        val text = ServerCalls.text(ServerCalls.callNow("list_instances", buildJsonObject { put("asset", asset); put("format", "json") }))
        val json = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
        val array = json as? JsonArray ?: json["instances"] as? JsonArray ?: throw AgentException(text.ifBlank { "No list of copies" })
        array.map { JSON.decodeFromJsonElement<AssetInstance>(it) }
    }, done)

    fun detach(copy: AssetInstance, done: (Result<String>) -> Unit) = call("detach_instance", args(copy.world) { put("id", copy.id) }, done)

    /** Deletes [copy] from the world: its blocks and the points it carries go. */
    fun deleteCopy(copy: AssetInstance, done: (Result<String>) -> Unit) = call("delete_instance", args(copy.world) { put("id", copy.id) }, done)

    /** Turns [copy] [degrees] clockwise in place, content and all. */
    fun rotateCopy(copy: AssetInstance, degrees: Int, done: (Result<String>) -> Unit) = call("rotate_instance", args(copy.world) {
        put("id", copy.id)
        put("rotate", degrees)
    }, done)

    /** Pastes [copy]'s asset again in its place, as it is now: what was changed in the copy is lost. */
    fun syncCopy(copy: AssetInstance, done: (Result<String>) -> Unit) = call("sync_instance", args(copy.world) { put("id", copy.id) }, done)

    /** Teleports the player to the asset area [copy] was pasted from (the server loads its world when needed). */
    fun openSource(copy: AssetInstance, done: (Result<String>) -> Unit) = call("open_asset_source", args(copy.world) { put("instance", copy.id) }, done)

    /** Teleports the player to [asset]'s area in its world. */
    fun openSource(asset: String, done: (Result<String>) -> Unit) = call("open_asset_source", buildJsonObject { put("asset", asset) }, done)

    /** Teleports the player on top of [copy], with the game's /tp (an operator's). */
    fun teleport(copy: AssetInstance) {
        val minecraft = Minecraft.getInstance()
        val box = copy.blockBox
        val top = Vec3(box.center.x, box.maxY + 1.0, box.center.z)
        val dimension = copy.dimension() ?: return Chat.warn("The server didn't say which dimension ${copy.id} is in")
        if (minecraft.level?.dimension()?.identifier()?.toString() == dimension) {
            // As client_teleport does (vanilla's /minecraft:tp, else /tp), off the client thread: it waits for the replies.
            CompletableFuture.runAsync { runCatching { ClientTools.teleport(top) } }
        } else {
            minecraft.player?.connection?.sendCommand("execute in $dimension run tp @s " + "%.1f %.1f %.1f".format(Locale.ROOT, top.x, top.y, top.z))
        }
    }

    private fun args(world: String, body: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        if (world.isNotEmpty()) put("world", world)
        body()
    }

    private fun vec(x: Int, y: Int, z: Int): JsonElement = JsonArray(listOf(x, y, z).map { JsonPrimitive(it) })
}

/**
 * The copies of an asset as `list_instances` gives them, for the pages that list them (the library's Used in, the asset
 * tool's panel): fetched when a page first asks, again when the server's copies changed, a few assets kept.
 */
object AssetCopies {
    private const val KEPT = 4

    class Copies {
        var list: List<AssetInstance>? = null
            internal set
        var error: String? = null
            internal set
        val loading get() = list == null && error == null
        internal var fetchedWith: List<AssetInstance>? = null
    }

    private val cache = LinkedHashMap<String, Copies>(8, 0.75f, true)

    fun of(asset: String): Copies {
        val old = cache[asset]
        if (old != null && (old.loading || old.fetchedWith === Bridge.instances)) return old
        // The last list stays shown while the new one comes.
        return Copies().also { copies ->
            copies.list = old?.list
            cache[asset] = copies
            copies.fetchedWith = Bridge.instances
            while (cache.size > KEPT) cache.remove(cache.keys.first())
            AssetCalls.copies(asset) { result ->
                result.onSuccess { copies.list = it }.onFailure { copies.error = it.message ?: it.javaClass.simpleName }
            }
        }
    }

    fun clear() = cache.clear()
}
