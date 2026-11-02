package games.qore.foreman.client.link

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.net.Net
import games.qore.foreman.client.util.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import org.slf4j.LoggerFactory

/** Layers the server pushes; the client subscribes to the ones its overlays show. */
enum class BridgeLayer(val id: String) { AREAS("areas"), POINTS("points"), ISSUES("issues"), CHANGES("changes") }

@Serializable
data class WireBox(val min: List<Int>, val max: List<Int>) {
    fun toBlockBox() = BlockBox.of(min[0], min[1], min[2], max[0], max[1], max[2])
}

/** A named area of the server's world: [kind] when it reserves space (build, road, yard…), [theme] when it is a zone. */
@Serializable
data class Area(
    val name: String, val world: String, val dim: String, val boxes: List<List<Int>>, val kind: String? = null, val note: String = "", val theme: String = "",
) {
    val blockBoxes get() = boxes.map { BlockBox.of(it[0], it[1], it[2], it[3], it[4], it[5]) }
    val isPoint get() = boxes.size == 1 && boxes[0].let { it[0] == it[3] && it[1] == it[4] && it[2] == it[5] }
}

/** A spot a build recorded for gameplay ([type], [kind]: free names), in [build]'s [room]. */
@Serializable
data class GamePoint(val world: String, val dim: String, val pos: List<Int>, val type: String, val kind: String = "", val build: String = "", val room: String = "")

@Serializable
data class Issue(val check: String, val severity: String, val pos: List<Int>, val text: String) {
    val error get() = severity == "error"
}

@Serializable
data class IssueReport(val world: String, val dim: String, val box: WireBox? = null, val issues: List<Issue>)

@Serializable
data class AIChange(val label: String, val by: String = "", val world: String, val dim: String, val box: WireBox? = null) {
    @kotlinx.serialization.Transient
    val received: Long = System.currentTimeMillis()
}

/** The server's hello. [test]: the player has `foreman.test`, the server's opt-in for the packet options of dupe testing. */
@Serializable
data class Hello(
    val mcpUrl: String, val token: String, val world: String = "", val dim: String = "", val version: Int = 1,
    val test: Boolean = false,
)

/**
 * The `foreman:bridge` channel to the foreman plugin: JSON messages with a `type`. The server says hello (MCP URL and
 * this player's token), pushes areas, points, check issues and AI changes; the client sends its selection and
 * which layers it wants. Protocol in the README.
 */
object Bridge {
    private val logger = LoggerFactory.getLogger("foreman")
    const val CHANNEL = "foreman:bridge"

    private val out = Net.outgoing<JsonObject>(CHANNEL) { it.toString().encodeToByteArray() }

    var hello: Hello? = null
        private set
    /** The server's name of the player's world (areas and tools use world names). */
    var world: String = ""
        private set
    var areas: List<Area> = emptyList()
        private set
    var points: List<GamePoint> = emptyList()
        private set
    var issues: IssueReport? = null
        private set
    var change: AIChange? = null
        private set

    val connected get() = hello != null

    /** Where the agent reaches the MCP server: the config override, else what the server announced. */
    val mcpUrl: String? get() = Config.settings.agent.mcpUrlOverride.ifBlank { null } ?: hello?.mcpUrl
    val token: String? get() = hello?.token

    /** This server runs Foreman and gives the player `foreman.test`. */
    val testing: Boolean get() = hello?.test == true

    fun init() {
        Net.incoming(CHANNEL, { JSON.parseToJsonElement(it.decodeToString()).jsonObject }, ::handle)
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> reset() }
    }

    private fun reset() {
        hello = null
        world = ""
        areas = emptyList()
        points = emptyList()
        issues = null
        change = null
    }

    private fun handle(message: JsonObject) {
        try {
            when (message["type"]?.jsonPrimitive?.content) {
                "hello" -> {
                    hello = JSON.decodeFromJsonElement<Hello>(message)
                    world = hello!!.world
                    subscribe()
                }
                "world" -> world = message["world"]?.jsonPrimitive?.content ?: world
                "areas" -> areas = JSON.decodeFromJsonElement(message["areas"]!!)
                "points" -> points = JSON.decodeFromJsonElement(message["points"]!!)
                "issues" -> issues = JSON.decodeFromJsonElement<IssueReport>(message)
                "change" -> change = JSON.decodeFromJsonElement<AIChange>(message)
            }
        } catch (e: Exception) {
            logger.warn("Bad foreman message {}", message, e)
        }
    }

    /** Subscribes to the layers the overlay settings show, unsubscribes from the others. */
    fun subscribe() {
        val o = Config.settings.overlays
        val wanted = mapOf(
            BridgeLayer.AREAS to o.areas, BridgeLayer.POINTS to o.points, BridgeLayer.ISSUES to o.issues, BridgeLayer.CHANGES to o.changes,
        ).mapValues { it.value != games.qore.foreman.client.config.Visibility.OFF && o.enabled }
        for (on in listOf(true, false)) {
            val layers = wanted.filterValues { it == on }.keys
            if (layers.isEmpty()) continue
            out.send(buildJsonObject {
                put("type", if (on) "subscribe" else "unsubscribe")
                putJsonArray("layers") { layers.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.id)) } }
            })
        }
    }

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
