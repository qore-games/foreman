package games.qore.foreman.client.panels

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * What the server's panel providers make of the things the builder aims at in link mode, while picking a target and
 * when inspecting, and of the targets fields hold (`panel_resolve`): the target that serves each (a furniture for its
 * cell block or hitbox), its provider and its name. A thing is asked about once the aim rested on it [REST_MS] (at once when inspecting), and again
 * when its answer is older than [STALE_MS] and it is still aimed at; nothing is asked otherwise.
 */
object Resolver {
    private const val REST_MS = 120L
    private const val STALE_MS = 5000L
    /** An ask unanswered this long is asked again (the server drops asks past its rate). */
    private const val LOST_MS = 2000L
    private const val KEPT = 256

    /** A provider's answer for a thing: the [target] serving it (null: none), the [provider] and the [title] it gives. */
    class Answer(val target: JsonObject?, val provider: String?, val title: String?, val at: Long)

    private val answers = HashMap<String, Answer>()
    /** Asks in flight: their id, the thing's key and when. */
    private val asked = HashMap<String, Pair<String, Long>>()
    private var aim = emptyList<String>()
    private var aimSince = 0L
    private var next = 0

    /** The answer for [target], when there is one. */
    fun of(target: JsonObject): Answer? = key(target)?.let { answers[it] }

    /** Whether every one of [targets] that can be asked about has its answer. */
    fun answered(targets: List<JsonObject>): Boolean = targets.all { t -> key(t)?.let { it in answers } ?: true }

    /**
     * [targets] are aimed at: those without a fresh answer are asked about, once the aim rested on the same things
     * [REST_MS] ([now]: at once).
     */
    fun want(targets: List<JsonObject>, now: Boolean = false) {
        val keyed = targets.mapNotNull { t -> key(t)?.let { it to t } }
        val keys = keyed.map { it.first }
        val time = System.currentTimeMillis()
        if (keys != aim) {
            aim = keys
            aimSince = time
        }
        if (!now && time - aimSince < REST_MS) return
        for ((key, target) in keyed) {
            val answer = answers[key]
            if (answer != null && time - answer.at < STALE_MS) continue
            if (asked.values.any { (k, at) -> k == key && time - at < LOST_MS }) continue
            ask(key, target, time)
        }
    }

    /**
     * What [target]'s provider calls it (a target field shows it): asked when it has no fresh answer, null until the
     * server said or when nothing names it. Asks nothing while the aim rests elsewhere: one ask per [STALE_MS] at most.
     */
    fun title(target: JsonObject): String? {
        val key = key(target) ?: return null
        val answer = answers[key]
        val time = System.currentTimeMillis()
        if ((answer == null || time - answer.at >= STALE_MS) && asked.values.none { (k, at) -> k == key && time - at < LOST_MS }) ask(key, target, time)
        return answer?.title
    }

    private fun ask(key: String, target: JsonObject, time: Long) {
        val id = (next++).toString()
        asked.entries.removeIf { time - it.value.second >= LOST_MS }
        asked[id] = key to time
        Bridge.send(buildJsonObject {
            put("type", "panel_resolve")
            put("id", id)
            put("target", target)
        })
    }

    /** A `panel_resolved` answer. */
    fun handle(message: JsonObject) {
        val key = message["id"].str()?.let { asked.remove(it)?.first } ?: message["aimed"].obj()?.let(::key) ?: return
        if (answers.size >= KEPT) answers.clear()
        answers[key] = Answer(message["target"].obj(), message["provider"].str(), message["title"].str()?.ifEmpty { null }, System.currentTimeMillis())
    }

    /** Forgets every answer: link mode left, another server. */
    fun clear() {
        answers.clear()
        asked.clear()
        aim = emptyList()
    }

    /** What names [target] here, whatever its world is called: its type and identity. */
    private fun key(target: JsonObject): String? = when (target["type"].str()) {
        "entity" -> target["uuid"].str()?.let { "entity:$it" }
        "block" -> "block:${target["dim"].str() ?: target["world"].str()}:${target["x"]},${target["y"]},${target["z"]}"
        "object" -> target["id"].str()?.let { "object:${target["kind"].str()}:$it" }
        else -> null
    }
}
