package games.qore.foreman.client.objects

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.str
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

/**
 * The keys the server's plugins add to the objects tool (`ForemanObjects` actions): a switch (a preview of their
 * machines) or an action each, sent with the objects while the tool is on, their state for this player kept fresh. Each
 * takes the key it asks for when the tool leaves it free, else the first free letter of its label; pressing it asks the
 * server to run it, and its answer shows on the tool's status card.
 */
object ObjectActions {
    /** An action: [id], what the keys list reads ([label]), the [key] it asks for, its [description], its plugin ([owner]), its [state] (a switch's). */
    @Serializable
    data class Action(val id: String, val label: String = "", val key: String? = null, val description: String = "", val owner: String = "", val state: Boolean? = null)

    private const val NOTE_MS = 6000L

    var actions: List<Action> = emptyList()
        private set
    private val running = HashSet<String>()
    private var note: Pair<String, Long>? = null
    /** The key each action took last, by id. */
    private var bound = emptyMap<String, EditorKey>()

    /** An `object_actions` or `object_action_done` message. */
    fun handle(type: String, message: JsonObject) {
        when (type) {
            "object_actions" -> actions = JSON.decodeFromJsonElement(message["actions"] ?: return)
            "object_action_done" -> {
                val id = message["id"].str() ?: return
                running -= id
                val label = actions.firstOrNull { it.id == id }?.label ?: id
                val text = message["message"].str() ?: if (message["ok"].bool() == false) "$label: refused" else "$label: done"
                note = (if (message["ok"].bool() == false) "§c✖ " else "§a✔ ") + text to System.currentTimeMillis()
            }
        }
    }

    fun reset() {
        actions = emptyList()
        running.clear()
        note = null
    }

    /** The actions' keys, none of them one of [taken] (the tool's and the editor's own). */
    fun keys(taken: Set<Int>): List<EditorKey> {
        val used = taken.toMutableSet()
        val keys = LinkedHashMap<String, EditorKey>()
        for (a in actions) {
            val key = keyFor(a, used) ?: continue
            used += key
            val state = when (a.state) {
                true -> ": on"
                false -> ": off"
                null -> ""
            }
            keys[a.id] = EditorKey(key, a.label + state + if (a.id in running) "…" else "") { run(a) }
        }
        bound = keys
        return keys.values.toList()
    }

    /** A line for each switch that is on, and how the last action went. */
    fun notes(): List<Note> {
        val on = actions.filter { it.state == true }.map { a ->
            Note(a.label + ": on" + (bound[a.id]?.let { " (${it.caption} turns it off)" } ?: ""), Note.Tone.OK)
        }
        val last = note?.takeIf { System.currentTimeMillis() - it.second < NOTE_MS }?.let { Note.coloured(it.first) }
        return on + listOfNotNull(last)
    }

    private fun run(a: Action) {
        if (a.id in running) return
        running += a.id
        Bridge.send(buildJsonObject {
            put("type", "object_action")
            put("id", a.id)
        })
    }

    /** The key [a] asks for when free, else the first free letter of its label; null when none is. */
    private fun keyFor(a: Action, used: Set<Int>): Int? {
        val wanted = listOfNotNull(a.key?.firstOrNull()) + a.label.filter { it.isLetter() }.toList()
        return wanted.map { it.uppercaseChar() }.filter { it in 'A'..'Z' }.map(::letter).firstOrNull { it !in used }
    }

    /** The key code of the letter [c]: the letters' codes follow each other (GLFW and SDL alike). */
    private fun letter(c: Char) = InputConstants.KEY_A + (c - 'A')
}
