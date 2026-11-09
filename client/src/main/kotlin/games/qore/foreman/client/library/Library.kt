package games.qore.foreman.client.library

import com.mojang.blaze3d.platform.NativeImage
import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.Area
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.render.Overlays
import games.qore.foreman.client.util.GuiTexture
import games.qore.foreman.client.util.GuiTextures
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import java.util.Base64

/** An asset of the server's library: a box of a world its builders tagged `asset` (an area), or a saved schematic. */
@Serializable
data class LibraryAsset(
    val id: String, val source: String = "file", val world: String? = null, val size: List<Int> = emptyList(),
    val tags: List<String> = emptyList(), val description: String = "", val category: String? = null,
    val blocks: List<String> = emptyList(), val furniture: Int = 0, val displays: Int = 0,
) {
    val isArea get() = source == "area"

    /** Its name for players: an area's own name, a schematic's path in the library. */
    val name get() = if (isArea && world != null) id.removePrefix("area:$world/") else id

    /** Its tags but the one that makes an area an asset. */
    val labels get() = tags.filter { it != Library.TAG }

    val sizeX get() = size.getOrElse(0) { 1 }
    val sizeY get() = size.getOrElse(1) { 1 }
    val sizeZ get() = size.getOrElse(2) { 1 }
    val sizeLabel get() = if (size.size == 3) "$sizeX×$sizeY×$sizeZ" else "?"

    /** Whether it has [query] (lowercase) in its name, world, tags, blocks, category or description. */
    fun matches(query: String) = query.isEmpty() || id.lowercase().contains(query) || description.lowercase().contains(query) ||
        world?.lowercase()?.contains(query) == true || category?.contains(query) == true ||
        tags.any { it.lowercase().contains(query) } || blocks.any { it.contains(query) }
}

/** Images of an asset the server renders: [textures] once they came, [error] when it couldn't. */
class Renders {
    var textures: List<GuiTexture> = emptyList()
        internal set
    var error: String? = null
        internal set
    val loading get() = textures.isEmpty() && error == null
}

/**
 * The server's asset library, for the hub's Library page and the placement tool: the list (`list_assets`), a thumbnail
 * per asset and the turntable views of the ones opened (`preview_asset`), pasting (`paste_asset`), all through the
 * server's MCP tools off the client thread. Renders cost the server: thumbnails are asked for the cards in view only,
 * a few at a time, and kept until the game leaves the server or the list is refreshed. Nothing runs while the page is
 * closed.
 */
object Library {
    /** The tag that puts an area in the library. */
    const val TAG = "asset"
    const val VIEWS = 8
    private const val THUMB_SIZE = 192
    private const val VIEW_SIZE = 448
    /** Renders asked for at once. */
    private const val PARALLEL = 2
    /** The assets whose views are kept (the last opened). */
    private const val KEPT_VIEWS = 3
    /** A card not drawn for this long is no longer waiting for its thumbnail. */
    private const val WANTED_MS = 1000L

    var assets: List<LibraryAsset> = emptyList()
        private set
    var loading = false
        private set
    var error: String? = null
        private set

    /** The bridge's asset areas the list was fetched for: when they change (one tagged, moved, renamed) it is fetched again. */
    private var fetchedFor: List<Area>? = null
    /** The bridge's last list of areas looked at (it is replaced on every push). */
    private var checked: List<Area>? = null
    private val thumbs = HashMap<String, Renders>()
    private val views = LinkedHashMap<String, Renders>(8, 0.75f, true)
    /** The cards drawn lately and when, last drawn last: the next thumbnail is the first still in view. */
    private val wanted = LinkedHashMap<String, Long>()
    private var running = 0
    /** Bumped when everything is dropped: answers to older requests are thrown away. */
    private var generation = 0

    fun init() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { clear() } }
        AssetTool.init()
        Overlays.add(CopyLayer)
    }

    /** Fetches the list the first time the page shows on a server, and again when the server's areas change. */
    fun update() {
        if (!Bridge.connected || loading) return
        val areas = Bridge.areas
        if (areas === checked && fetchedFor != null) return
        checked = areas
        if (fetchedFor == null || assetAreas(areas) != fetchedFor) refresh(images = false)
    }

    private fun assetAreas(areas: List<Area>) = areas.filter { TAG in it.tags }

    /** Fetches the list again; [images]: renders too (what the assets look like changed). */
    fun refresh(images: Boolean = true) {
        if (images) dropImages()
        loading = true
        val generation = generation
        val areas = assetAreas(Bridge.areas)
        ServerCalls.run({ parse(ServerCalls.text(ServerCalls.callNow("list_assets", buildJsonObject { put("format", "json") }))) }) { result ->
            if (generation != this.generation) return@run
            loading = false
            fetchedFor = areas
            result.onSuccess { list ->
                error = null
                dropChanged(list)
                assets = list
            }.onFailure {
                // The bridge's asset areas still make a library (without counts nor schematics).
                error = it.message ?: it.javaClass.simpleName
                assets = areas.map(::fromArea)
            }
        }
    }

    private fun parse(text: String): List<LibraryAsset> {
        val array = runCatching { JSON.parseToJsonElement(text) as JsonArray }.getOrNull()
            ?: throw AgentException("The server's list_assets has no JSON format: its Foreman plugin is older than this mod")
        return array.map { JSON.decodeFromJsonElement<LibraryAsset>(it) }
    }

    private fun fromArea(area: Area): LibraryAsset {
        val boxes = area.blockBoxes
        val size = listOf(boxes.maxOf { it.maxX } - boxes.minOf { it.minX } + 1, boxes.maxOf { it.maxY } - boxes.minOf { it.minY } + 1, boxes.maxOf { it.maxZ } - boxes.minOf { it.minZ } + 1)
        return LibraryAsset("area:${area.world}/${area.name}", "area", area.world, size, area.tags, area.note)
    }

    /** The thumbnail of [asset] (asked for when missing: call it for the cards in view only). */
    fun thumbnail(asset: LibraryAsset): Renders? {
        wanted.remove(asset.id)
        wanted[asset.id] = System.currentTimeMillis()
        pump()
        return thumbs[asset.id]
    }

    /** The [VIEWS] views of [asset] around it, the first one its front-left. */
    fun views(asset: LibraryAsset): Renders = views[asset.id] ?: Renders().also { renders ->
        views[asset.id] = renders
        render(asset.id, renders, VIEWS, VIEW_SIZE)
        while (views.size > KEPT_VIEWS) views.remove(views.keys.first())?.let(::release)
    }

    /** Renders [asset] again: its thumbnail and views. */
    fun rerender(asset: LibraryAsset) {
        thumbs.remove(asset.id)?.let(::release)
        views.remove(asset.id)?.let(::release)
    }

    /** Starts the next thumbnail: the first card still in view without one. */
    private fun pump() {
        val now = System.currentTimeMillis()
        wanted.entries.removeIf { now - it.value > WANTED_MS }
        while (running < PARALLEL) {
            val id = wanted.keys.firstOrNull { it !in thumbs } ?: return
            render(id, Renders().also { thumbs[id] = it }, 1, THUMB_SIZE)
        }
    }

    /** Asks the server for [count] renders of [id] into [into]: kept unless it was dropped meanwhile. */
    private fun render(id: String, into: Renders, count: Int, size: Int) {
        running++
        val generation = generation
        ServerCalls.run({
            val result = ServerCalls.callNow("preview_asset", buildJsonObject { put("name", id); put("views", count); put("size", size) })
            val images = result["content"].arr().orEmpty().filter { it["type"].str() == "image" }.map { NativeImage.read(Base64.getDecoder().decode(it["data"].str())) }
            images.ifEmpty { throw AgentException(ServerCalls.text(result).ifBlank { "No image" }) }
        }) { result ->
            running--
            val images = result.getOrNull()
            val kept = generation == this.generation && (thumbs[id] === into || views[id] === into)
            if (!kept) {
                images?.forEach(NativeImage::close)
                return@run
            }
            into.error = result.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName }
            into.textures = images.orEmpty().map { GuiTextures.register(it, "library") }
        }
    }

    /** Pastes [asset] with its bottom center at [at], turned [rotate] degrees clockwise; [done] on the client thread. */
    fun paste(asset: LibraryAsset, at: BlockPos, rotate: Int, done: (Result<String>) -> Unit) {
        val args = buildJsonObject {
            put("name", asset.id)
            put("position", JsonArray(listOf(at.x, at.y, at.z).map { JsonPrimitive(it) }))
            put("rotate", rotate)
            Bridge.world.takeIf { it.isNotEmpty() }?.let { put("world", it) }
        }
        ServerCalls.run({ ServerCalls.text(ServerCalls.callNow("paste_asset", args)) }, done)
    }

    /** Drops the renders of the assets that are gone or changed size or contents in [list]. */
    private fun dropChanged(list: List<LibraryAsset>) {
        val now = list.associateBy { it.id }
        for (old in assets) if (now[old.id]?.let { it.size != old.size || it.blocks != old.blocks || it.furniture != old.furniture || it.displays != old.displays } != false) rerender(old)
    }

    private fun dropImages() {
        generation++
        thumbs.values.forEach(::release)
        views.values.forEach(::release)
        thumbs.clear()
        views.clear()
        wanted.clear()
    }

    private fun release(renders: Renders) = renders.textures.forEach(GuiTextures::release)

    /** Forgets the server's library (the game left it). */
    private fun clear() {
        dropImages()
        assets = emptyList()
        loading = false
        error = null
        fetchedFor = null
        checked = null
        ServerCalls.reset()
        if (Tools.active === PlacementTool) Tools.deactivate()
    }
}
