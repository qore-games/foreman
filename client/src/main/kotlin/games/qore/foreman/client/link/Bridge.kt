package games.qore.foreman.client.link

import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.agent.McpClient
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.net.Net
import games.qore.foreman.client.objects.ObjectActions
import games.qore.foreman.client.panels.Panels
import games.qore.foreman.client.util.JSON
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import org.slf4j.LoggerFactory
import kotlin.math.abs
import kotlin.math.floor

/** Layers the server pushes; the client subscribes to the ones its overlays show and its active tools need. */
enum class BridgeLayer(val id: String) {
    AREAS("areas"), POINTS("points"), ISSUES("issues"), CHANGES("changes"), INSTANCES("instances"), LAYOUT("layout"), OBJECTS("objects"), RELATIONS("relations"),
}

@Serializable
data class WireBox(val min: List<Int>, val max: List<Int>) {
    fun toBlockBox() = BlockBox.of(min[0], min[1], min[2], max[0], max[1], max[2])
}

/** Who holds an area's lock: only their Foreman edits go in ([name], [uuid]), since [since] (epoch ms). */
@Serializable
data class AreaLock(val name: String, val uuid: String = "", val since: Long = 0) {
    /** Held by this player. */
    val mine get() = uuid == Minecraft.getInstance().player?.uuid?.toString()
}

/**
 * A named area of the server's world: [kind] when it reserves space (build, road, yard…), [theme] when it is a zone,
 * [tags] its builders' labels (`asset` puts it in the [library][games.qore.foreman.client.library.Library]), [lock]
 * when a player locked it to themselves, [data] the field values of the [types][Layouts.of] its kind and tags name.
 */
@Serializable
data class Area(
    val name: String, val world: String, val dim: String, val boxes: List<List<Int>>, val kind: String? = null, val note: String = "", val theme: String = "",
    val tags: List<String> = emptyList(), @SerialName("locked_by") val lock: AreaLock? = null, val data: JsonObject = JsonObject(emptyMap()),
) {
    val blockBoxes get() = boxes.map { BlockBox.of(it[0], it[1], it[2], it[3], it[4], it[5]) }
    val isPoint get() = boxes.size == 1 && boxes[0].let { it[0] == it[3] && it[1] == it[4] && it[2] == it[5] }
}

/**
 * A spot a build recorded for gameplay ([type], [kind]: free names, or a [declared type][Layouts.point] whose fields
 * [data] holds), in [build]'s [room]: the block it is in ([pos]), where exactly ([position], else the middle of that
 * block) and where it faces ([yaw], [pitch]: degrees, Minecraft's).
 */
@Serializable
data class GamePoint(
    val world: String, val dim: String, val pos: List<Int> = emptyList(), val type: String, val kind: String = "", val build: String = "", val room: String = "",
    val position: List<Double> = emptyList(), val yaw: Float? = null, val pitch: Float? = null, val data: JsonObject = JsonObject(emptyMap()),
) {
    /** Where it is exactly. */
    val at: Vec3 get() = if (position.size == 3) Vec3(position[0], position[1], position[2]) else Vec3(pos[0] + 0.5, pos[1] + 0.5, pos[2] + 0.5)

    /** The block it is in. */
    val block: List<Int> get() = pos.ifEmpty { position.map { floor(it).toInt() } }

    /** It at [to], its block with it. */
    fun movedTo(to: Vec3) = copy(pos = listOf(floor(to.x).toInt(), floor(to.y).toInt(), floor(to.z).toInt()), position = listOf(to.x, to.y, to.z))

    /** Whether [other] is this point, however its numbers came back from the server. */
    fun same(other: GamePoint?) = other != null && world == other.world && type == other.type && kind == other.kind && build == other.build &&
        room == other.room && at.distanceToSqr(other.at) < 1e-6 && abs((yaw ?: 0f) - (other.yaw ?: 0f)) < 0.01f && abs((pitch ?: 0f) - (other.pitch ?: 0f)) < 0.01f
}

/** A point a pasted copy carries from its asset, where it landed ([position], or [pos] as the world keeps points). */
@Serializable
data class InstancePoint(val name: String = "", val type: String = "", val kind: String = "", val position: List<Double> = emptyList(), val pos: List<Double> = emptyList()) {
    val at get() = position.ifEmpty { pos }
}

/** The asset area a copy was pasted from. */
@Serializable
data class InstanceSource(val world: String = "", val area: String = "")

/**
 * A pasted copy of an asset: where it is ([box], min then max corner), the [asset] and its [source] area, its quarter
 * turn [rotate] (degrees clockwise) after a [mirror] east–west, the points it carries, and who pasted it ([by]) and when
 * ([created]). [parent]: the copy it is nested in (pasted as part of that one's asset); [detached]: syncs leave it.
 */
@Serializable
data class AssetInstance(
    val id: String, val world: String = "", val dim: String = "", val asset: String = "", val source: InstanceSource? = null, val box: List<Int>,
    val rotate: Int = 0, val points: List<InstancePoint> = emptyList(), val created: kotlinx.serialization.json.JsonElement? = null, val by: String = "",
    val mirror: Boolean = false, val detached: Boolean = false, val parent: String? = null,
) {
    val blockBox get() = BlockBox.of(box[0], box[1], box[2], box[3], box[4], box[5])

    /** Whether it is in [dimension] (by its world when the server didn't say its dimension). */
    fun inDimension(dimension: String) = if (dim.isNotEmpty()) dim == dimension else world.isNotEmpty() && world == Bridge.world

    /** The asset's name for players: its source area's, else the asset id without its `area:world/` prefix. */
    val assetName get() = source?.area?.ifEmpty { null } ?: asset.substringAfterLast('/')
}

@Serializable
data class Issue(val check: String, val severity: String, val pos: List<Int>, val text: String) {
    val error get() = severity == "error"
}

@Serializable
data class IssueReport(val world: String, val dim: String, val box: WireBox? = null, val issues: List<Issue>)

/** A change a tool made in the world; [editor]: a player made it in the mod's pages, not an AI. */
@Serializable
data class AIChange(val label: String, val by: String = "", val world: String, val dim: String, val box: WireBox? = null, val editor: Boolean = false) {
    @kotlinx.serialization.Transient
    val received: Long = System.currentTimeMillis()
}

/**
 * The server's hello: its MCP address, this player's token and the mod's own [editorToken] (when it runs its MCP
 * server), [relay] (its tools through the game connection), [test] (the player has `foreman.test`, the server's opt-in
 * for the packet options of dupe testing), the [features] it offers and the version of its panels ([panelsVersion], 0
 * without them).
 */
@Serializable
data class Hello(
    val mcpUrl: String? = null, val token: String? = null, val editorToken: String? = null, val world: String = "", val dim: String = "", val version: Int = 1,
    val test: Boolean = false, val relay: Boolean = false, val features: List<String> = emptyList(),
    @SerialName("panels_version") val panelsVersion: Int = 0,
)

/**
 * The `foreman:bridge` channel to the foreman plugin: JSON messages with a `type`. The server says hello (how to reach
 * its tools: MCP URL and this player's token, or the relay) or `denied` (the permission the player lacks), pushes areas, points, the declared point and area types,
 * the object kinds and the objects near the player (while the objects tool needs them), check issues and AI changes,
 * and the [panels][Panels] (the one open, the relations near the player); the client sends its selection, which layers
 * it wants and the panel it opens. Protocol in the README.
 */
object Bridge {
    private val logger = LoggerFactory.getLogger("foreman")
    const val CHANNEL = "foreman:bridge"

    private val out = Net.outgoing<JsonObject>(CHANNEL) { it.toString().encodeToByteArray() }

    var hello: Hello? = null
        private set
    /** The permission the server's Foreman wants before it links this player (its `denied`): null when it linked them or isn't there. */
    var denied: String? = null
        private set
    /** The server's name of the player's world (areas and tools use world names). */
    var world: String = ""
        private set
    var areas: List<Area> = emptyList()
        private set
    var points: List<GamePoint> = emptyList()
        private set
    /** The point and area types the server's plugins declare (the `layout` layer). */
    var layout: List<LayoutType> = emptyList()
        private set
    var issues: IssueReport? = null
        private set
    var change: AIChange? = null
        private set
    /** The pasted copies of assets, in every world. */
    var instances: List<AssetInstance> = emptyList()
        private set
    /** The kinds of objects the server's plugins declare (the `objects` layer). */
    var objectKinds: List<ObjectKind> = emptyList()
        private set
    /** The objects near the player, in their dimension (the `objects` layer), and when they last came. */
    var objects: List<GameObject> = emptyList()
        private set
    var objectsAt = 0L
        private set

    /** Layers an active tool needs whatever the overlay settings (the asset tool: areas and copies). */
    private val needed = HashSet<BridgeLayer>()

    val connected get() = hello != null

    /**
     * What the mod says where it needs the server's tools and has none, with what that leaves out ([why]): no Foreman
     * plugin on the server, or one that doesn't link this player ([denied]).
     */
    fun unavailable(why: String? = null): String =
        denied?.let { "You don't have $it on this server's Foreman" } ?: listOfNotNull("No Foreman plugin on this server", why).joinToString(": ")

    /**
     * The server's tools go through the game connection ([Relay]): it relays, and announced no MCP address or one this
     * computer doesn't reach (checked when it says hello).
     */
    @Volatile
    var relayed = false
        private set

    /** Where the agent reaches the server's MCP: the config override, else what the server announced, else the relay. */
    val mcpUrl: String? get() = override ?: if (relayed) ClientMcp.relayUrl else hello?.mcpUrl
    val token: String? get() = if (override == null && relayed) ClientMcp.token else hello?.token
    /** The token of the mod's own pages (the builder's own edits, not an AI's), where [token] is the server's. */
    val editorToken: String? get() = if (override == null && relayed) ClientMcp.token else hello?.editorToken
    private val override get() = Config.settings.agent.mcpUrlOverride.ifBlank { null }

    /** This server runs Foreman and gives the player `foreman.test`. */
    val testing: Boolean get() = hello?.test == true

    fun init() {
        Relay.init()
        Net.incoming(CHANNEL, { JSON.parseToJsonElement(it.decodeToString()).jsonObject }, ::handle)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
        // A proxy moving the player to another server fires no disconnect: what the last one said goes, and a server
        // without Foreman sends nothing in its place. Its hello comes after this (the channels are registered after).
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
    }

    private fun reset() {
        hello = null
        denied = null
        relayed = false
        world = ""
        areas = emptyList()
        points = emptyList()
        layout = emptyList()
        issues = null
        change = null
        instances = emptyList()
        objectKinds = emptyList()
        objects = emptyList()
        ObjectActions.reset()
        Panels.reset()
    }

    private fun handle(message: JsonObject) {
        try {
            when (message["type"]?.jsonPrimitive?.content) {
                "hello" -> {
                    val hello = JSON.decodeFromJsonElement<Hello>(message)
                    this.hello = hello
                    denied = null
                    world = hello.world
                    relayed = hello.relay && hello.mcpUrl == null
                    if (hello.relay && hello.mcpUrl != null && hello.token != null) probe(hello.mcpUrl, hello.token)
                    Panels.hello()
                    subscribe()
                }
                // Its Foreman doesn't link this player (any more): what it said before goes.
                "denied" -> {
                    reset()
                    denied = message["permission"]?.jsonPrimitive?.content ?: "permission"
                }
                "world" -> world = message["world"]?.jsonPrimitive?.content ?: world
                "areas" -> areas = JSON.decodeFromJsonElement(message["areas"]!!)
                "points" -> points = JSON.decodeFromJsonElement(message["points"]!!)
                "layout" -> layout = JSON.decodeFromJsonElement(message["types"]!!)
                "issues" -> issues = JSON.decodeFromJsonElement<IssueReport>(message)
                "change" -> change = JSON.decodeFromJsonElement<AIChange>(message)
                "instances" -> instances = JSON.decodeFromJsonElement(message["instances"]!!)
                "object_kinds" -> objectKinds = JSON.decodeFromJsonElement(message["kinds"]!!)
                "objects" -> {
                    val dim = message["dim"]?.jsonPrimitive?.content.orEmpty()
                    objects = JSON.decodeFromJsonElement<List<GameObject>>(message["objects"]!!).map { it.copy(dim = dim) }
                    objectsAt = System.currentTimeMillis()
                }
                "panel", "panel_resolved", "relations" -> Panels.handle(message["type"]!!.jsonPrimitive.content, message)
                "object_actions", "object_action_done" -> ObjectActions.handle(message["type"]!!.jsonPrimitive.content, message)
            }
        } catch (e: Exception) {
            logger.warn("Bad foreman message {}", message, e)
        }
    }

    /** Relays when the server's MCP address doesn't answer from here (a port only its own machine reaches). */
    private fun probe(url: String, token: String) = Thread({
        if (runCatching { McpClient(url, token).initialize() }.isFailure && hello?.mcpUrl == url) {
            relayed = true
            logger.info("The server's MCP at {} doesn't answer: its tools go through the game connection", url)
        }
    }, "foreman-mcp-probe").apply { isDaemon = true; start() }

    /** An active tool needs [layer] ([on]), or no longer does: subscribes or unsubscribes as needed. */
    fun need(layer: BridgeLayer, on: Boolean) {
        if (if (on) needed.add(layer) else needed.remove(layer)) subscribe()
    }

    /** Subscribes to the layers the overlay settings show and the active tools need, unsubscribes from the others. */
    fun subscribe() {
        if (!connected) return
        val o = Config.settings.overlays
        val wanted = mapOf(
            BridgeLayer.AREAS to o.areas, BridgeLayer.POINTS to o.points, BridgeLayer.ISSUES to o.issues, BridgeLayer.CHANGES to o.changes,
            BridgeLayer.INSTANCES to o.instances,
        ).mapValues { (layer, visibility) -> layer in needed || visibility != games.qore.foreman.client.config.Visibility.OFF && o.enabled } +
            (BridgeLayer.LAYOUT to (BridgeLayer.LAYOUT in needed)) + (BridgeLayer.OBJECTS to (BridgeLayer.OBJECTS in needed)) +
            (BridgeLayer.RELATIONS to (BridgeLayer.RELATIONS in needed && Panels.supported))
        for (on in listOf(true, false)) {
            val layers = wanted.filterValues { it == on }.keys
            if (layers.isEmpty()) continue
            out.send(buildJsonObject {
                put("type", if (on) "subscribe" else "unsubscribe")
                putJsonArray("layers") { layers.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.id)) } }
            })
        }
    }

    /** Sends [message] to the plugin. */
    fun send(message: JsonObject) = out.send(message)

    fun sendSelection(dimension: String?, box: BlockBox?) {
        out.send(buildJsonObject {
            put("type", "selection")
            if (box == null || dimension == null) put("clear", true) else {
                put("dim", dimension)
                putJsonArray("min") { add(kotlinx.serialization.json.JsonPrimitive(box.minX)); add(kotlinx.serialization.json.JsonPrimitive(box.minY)); add(kotlinx.serialization.json.JsonPrimitive(box.minZ)) }
                putJsonArray("max") { add(kotlinx.serialization.json.JsonPrimitive(box.maxX)); add(kotlinx.serialization.json.JsonPrimitive(box.maxY)); add(kotlinx.serialization.json.JsonPrimitive(box.maxZ)) }
            }
        })
    }
}
