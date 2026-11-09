package games.qore.foreman.client.models

import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.mixin.ModelManagerAccessor
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.phys.Vec3
import java.util.UUID

/**
 * The item models the client has: every item definition of the loaded resource packs (`assets/<namespace>/items/<path>.json`
 * is `<namespace>:<path>`, what an item's `item_model` component names), read from the model manager after each
 * reload, so it works on any server. The Foreman plugin, when the server runs it, says which are furniture
 * (`list_models`) and places them (`spawn_display`, `place_furniture`).
 */
object ItemModels {
    /** The models, sorted by namespace then path; [version] changes when they do (resource packs reloaded). */
    var version = 0
        private set
    private var source: Map<Identifier, *>? = null
    private var list: List<Identifier> = emptyList()
    private val stacks = HashMap<Identifier, ItemStack>()

    /** Furniture ids by model, from the server; empty until fetched or without the plugin. */
    var furniture: Map<String, List<String>> = emptyMap()
        private set
    var furnitureLoading = false
        private set
    var furnitureError: String? = null
        private set
    private var fetched = false

    fun init() {
        ModelPreview.init()
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { clear() } }
    }

    /** Every item model of the loaded packs. On the client thread. */
    fun all(): List<Identifier> {
        val models = (Minecraft.getInstance().modelManager as ModelManagerAccessor).`foreman$itemModels`() ?: return list
        if (models !== source) {
            source = models
            list = models.keys.sortedWith(compareBy({ it.namespace }, { it.path }))
            stacks.clear()
            version++
        }
        return list
    }

    /** An item showing [id] (its `item_model`). */
    fun stack(id: Identifier): ItemStack = stacks.getOrPut(id) { ItemStack(Items.PAPER).apply { set(DataComponents.ITEM_MODEL, id) } }

    /** Fetches which models are furniture the first time the page shows on a server with the plugin. */
    fun update() {
        if (!fetched && !furnitureLoading && Bridge.connected) refresh()
    }

    fun refresh() {
        fetched = true
        furnitureLoading = true
        ServerCalls.run({ parseFurniture(ServerCalls.text(ServerCalls.callNow("list_models", buildJsonObject { put("format", "json") }))) }) { result ->
            furnitureLoading = false
            furnitureError = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            furniture = result.getOrDefault(emptyMap())
        }
    }

    private fun parseFurniture(text: String): Map<String, List<String>> {
        val array = runCatching { JSON.parseToJsonElement(text) as JsonArray }.getOrNull()
            ?: throw AgentException("The server's list_models has no JSON format: its Foreman plugin is older than this mod")
        return array.mapNotNull { model ->
            val ids = model["furniture"].arr().orEmpty().mapNotNull { it.str() }
            model["id"].str()?.takeIf { ids.isNotEmpty() }?.let { it to ids }
        }.toMap()
    }

    /** What the server answered a placement: the display it made (when it says), and its words. */
    class Placed(val uuid: UUID?, val text: String)

    /**
     * Spawns an item display of [model] at [at] exactly (its lowest point there), its front toward a player looking at
     * [yaw], [scale] times its size, with the server's `spawn_display`: one undo step. [done] on the client thread.
     */
    fun spawnDisplay(model: Identifier, at: Vec3, yaw: Float, scale: Float, done: (Result<Placed>) -> Unit) {
        val position = listOf(at.x, at.y, at.z).map { Math.round(it * 1000) / 1000.0 }.toMutableList()
        // Whole numbers would be a block, its display moved to the centre of its floor.
        if (position.all { it % 1.0 == 0.0 }) position[1] += 0.001
        val args = buildJsonObject {
            put("model", model.toString())
            put("position", JsonArray(position.map { JsonPrimitive(it) }))
            put("yaw", yaw)
            if (scale != 1f) put("scale", scale)
            world()?.let { put("world", it) }
            put("format", "json")
        }
        ServerCalls.run({ placed(ServerCalls.text(ServerCalls.callNow("spawn_display", args))) }, done)
    }

    private fun placed(text: String): Placed {
        val json = runCatching { JSON.parseToJsonElement(text) as JsonObject }.getOrNull()
        val uuid = json?.get("uuid").str()?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return Placed(uuid, text)
    }

    /**
     * Places furniture [id] rooted in the block [at], its front toward a player looking at [yaw], with the server's
     * `place_furniture`: one undo step. [done] on the client thread.
     */
    fun placeFurniture(id: String, at: BlockPos, yaw: Float, done: (Result<String>) -> Unit) {
        val args = buildJsonObject {
            put("items", buildJsonArray {
                add(buildJsonObject {
                    put("id", id)
                    put("pos", JsonArray(listOf(at.x, at.y, at.z).map { JsonPrimitive(it) }))
                    put("yaw", yaw)
                })
            })
            world()?.let { put("world", it) }
        }
        ServerCalls.run({ ServerCalls.text(ServerCalls.callNow("place_furniture", args)) }, done)
    }

    private fun world() = Bridge.world.takeIf { it.isNotEmpty() }

    /** Forgets what the server said (the game left it). */
    private fun clear() {
        furniture = emptyMap()
        furnitureLoading = false
        furnitureError = null
        fetched = false
        if (Tools.active === ModelPlacementTool) Tools.deactivate()
    }
}
