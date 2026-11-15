package games.qore.foreman.server.bridge

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.PanelTarget
import games.qore.foreman.server.panels.PanelJson
import games.qore.foreman.server.panels.Panels
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.scheduler.BukkitTask
import org.bukkit.util.BoundingBox
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The panels and relations the mod shows: the panel each builder opened (`panel_open`), described again twice a second
 * and at once after a refresh, sent when it changed; what serves the things a builder aims at in link mode
 * (`panel_resolve`); the relations near builders subscribed to `relations`, once a second when they changed. Its task
 * runs only while a panel is open or someone shows relations. Main thread.
 */
class PanelFeed(private val plugin: Foreman, private val panels: Panels, private val send: (Player, JsonObject) -> Unit) {
    companion object {
        /** The panels protocol: the server's hello says it as `panels_version`, the mod's as `panels`. */
        const val VERSION = 1
        /** Relations are sent within this many blocks of a builder, at most this many. */
        private const val RELATIONS_RADIUS = 64.0
        private const val RELATIONS_MAX = 500
        /** `panel_resolve` asks answered per builder and second; the rest are dropped (the mod asks again). */
        private const val RESOLVES_PER_SECOND = 24
    }

    /** A builder's open panel: what they aimed at (as they sent it), what serves it, what was sent last. */
    private class Open(val aimed: PanelTarget?, val aimedJson: JsonElement, var served: Panels.Served?, var sent: String = "")

    /** The row kinds each mod draws, when its hello said. */
    private val kinds = ConcurrentHashMap<UUID, Set<String>>()
    private val open = HashMap<UUID, Open>()
    /** Builders showing relations, and what they were sent last. */
    private val relations = HashMap<UUID, String>()
    @Volatile private var task: BukkitTask? = null
    private var runs = 0
    /** A refresh waits for the next tick: the targets refreshed until then. */
    private val refreshing = AtomicBoolean(false)
    private val refreshed = ConcurrentHashMap.newKeySet<PanelTarget>()
    /** Each builder's `panel_resolve` asks this second: the second, and how many. */
    private val resolves = HashMap<UUID, LongArray>()

    fun start() {
        panels.onRefresh = ::onRefresh
    }

    fun stop() {
        panels.onRefresh = null
        task?.cancel()
        task = null
    }

    /** The mod's `hello` (`{panels: 1, rows: [kinds]}`): rows of other kinds are sent to it as text. */
    fun hello(player: Player, json: JsonObject) {
        val rows = json.getAsJsonArray("rows")?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive }?.asString }?.toSet()
        if (json.has("panels") && rows != null) kinds[player.uniqueId] = rows else kinds.remove(player.uniqueId)
    }

    /** `panel_open {target}`: sends [player] the panel of what they aim at, and again whenever it changes. */
    fun open(player: Player, json: JsonObject) {
        val aimedJson = json.get("target") ?: JsonNull.INSTANCE
        val aimed = runCatching { PanelJson.target(aimedJson) }
        val o = Open(aimed.getOrNull(), aimedJson, aimed.getOrNull()?.let { panels.resolve(it) })
        open[player.uniqueId] = o
        if (aimed.isFailure) send(player, message(aimedJson, null, null, aimed.exceptionOrNull()?.message)).also { o.sent = "error" }
        else push(player, o)
        if (o.served != null) schedule()
    }

    /**
     * `panel_resolve {id, target}`, what the mod's link mode and inspector aim at: which provider serves it, the target
     * it resolves to and its name (`{type: panel_resolved, id, aimed, target, provider?, provider_label?, title?}`, target
     * null when none serves it). Asked once per thing aimed at.
     */
    fun resolve(player: Player, json: JsonObject) {
        if (!allowed(player)) return
        val aimedJson = json.get("target") ?: JsonNull.INSTANCE
        val served = runCatching { PanelJson.target(aimedJson) }.getOrNull()?.let { panels.resolve(it) }
        send(player, JsonObject().apply {
            addProperty("type", "panel_resolved")
            add("id", json.get("id") ?: JsonNull.INSTANCE)
            add("aimed", aimedJson)
            add("target", served?.let { PanelJson.target(it.target) } ?: JsonNull.INSTANCE)
            served?.let {
                addProperty("provider", it.entry.id)
                addProperty("provider_label", it.entry.label)
                panels.name(it, panels.context(it.target, player))?.let { title -> addProperty("title", title) }
            }
        })
    }

    /** Whether [player] may have one more `panel_resolve` answered this second. */
    private fun allowed(player: Player): Boolean {
        val second = System.currentTimeMillis() / 1000
        val count = resolves.getOrPut(player.uniqueId) { LongArray(2) }
        if (count[0] != second) {
            count[0] = second
            count[1] = 0
        }
        return ++count[1] <= RESOLVES_PER_SECOND
    }

    fun close(player: Player) {
        open.remove(player.uniqueId)
        unschedule()
    }

    fun subscribe(player: Player) {
        relations[player.uniqueId] = ""
        pushRelations(player)
        schedule()
    }

    fun unsubscribe(player: Player) {
        relations.remove(player.uniqueId)
        unschedule()
    }

    fun quit(player: Player) {
        kinds.remove(player.uniqueId)
        resolves.remove(player.uniqueId)
        open.remove(player.uniqueId)
        relations.remove(player.uniqueId)
        unschedule()
    }

    private fun schedule() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 10L, 10L)
    }

    private fun unschedule() {
        if (open.values.none { it.served != null } && relations.isEmpty()) {
            task?.cancel()
            task = null
        }
    }

    private fun tick() {
        open.forEach { (uuid, o) -> if (o.served != null) Bukkit.getPlayer(uuid)?.let { push(it, o) } }
        if (runs++ % 2 == 0) relations.keys.toList().forEach { uuid -> Bukkit.getPlayer(uuid)?.let(::pushRelations) }
        unschedule()
    }

    /** A provider changed [target] (any thread): its open panels and the relations are sent again at the next tick. */
    private fun onRefresh(target: PanelTarget) {
        if (task == null) return
        refreshed += target
        if (!refreshing.compareAndSet(false, true)) return
        Bukkit.getScheduler().runTask(plugin, Runnable {
            refreshing.set(false)
            val targets = refreshed.toSet().also { refreshed.removeAll(it) }
            open.forEach { (uuid, o) ->
                if (o.served?.target in targets || o.aimed in targets) Bukkit.getPlayer(uuid)?.let { push(it, o) }
            }
            relations.keys.toList().forEach { uuid -> Bukkit.getPlayer(uuid)?.let(::pushRelations) }
        })
    }

    /** Sends [o]'s panel to [player] when it changed; one that is gone (or never was) is said once, then not asked again. */
    private fun push(player: Player, o: Open) {
        // Its provider went (its plugin disabled) or was registered again: ask again who serves it.
        if (o.served?.let { panels.registered(it.entry) } == false) o.served = o.aimed?.let { panels.resolve(it) }
        val served = o.served
        val message = if (served == null) message(o.aimedJson, null, null, null) else try {
            val panel = panels.describe(served, panels.context(served.target, player))
            if (panel == null) o.served = null
            message(o.aimedJson, served.takeIf { panel != null }, panel?.let { PanelJson.panel(it, kinds[player.uniqueId]) }, null)
        } catch (e: Exception) {
            message(o.aimedJson, served, null, e.message)
        }
        val text = message.toString()
        if (text == o.sent) return
        o.sent = text
        send(player, message)
    }

    /** `{type: panel, aimed, target, provider?, provider_label?, panel, error?}`: target and panel null when there is none. */
    private fun message(aimed: JsonElement, served: Panels.Served?, panel: JsonObject?, error: String?) = JsonObject().apply {
        addProperty("type", "panel")
        add("aimed", aimed)
        add("target", served?.let { PanelJson.target(it.target) } ?: JsonNull.INSTANCE)
        served?.let {
            addProperty("provider", it.entry.id)
            addProperty("provider_label", it.entry.label)
        }
        add("panel", panel ?: JsonNull.INSTANCE)
        error?.let { addProperty("error", it) }
    }

    /** Sends [player] the relations within [RELATIONS_RADIUS] blocks (those Foreman can draw) when they changed. */
    private fun pushRelations(player: Player) {
        val at = player.location
        val r = RELATIONS_RADIUS
        val found = panels.relations(player.world, BoundingBox(at.x - r, at.y - r, at.z - r, at.x + r, at.y + r, at.z + r))
            .filter { it.from != null && it.to != null }.take(RELATIONS_MAX)
        val message = JsonObject().apply {
            addProperty("type", "relations")
            addProperty("world", player.world.name)
            addProperty("dim", player.world.key.toString())
            add("relations", JsonArray().apply { found.forEach { add(PanelJson.relation(it)) } })
        }
        val text = message.toString()
        if (relations[player.uniqueId] == text) return
        relations[player.uniqueId] = text
        send(player, message)
    }
}
