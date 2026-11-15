package games.qore.foreman.client.library

import games.qore.foreman.client.agent.AgentException
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The player's clipboard, which the server keeps (`copy_region`: one per player, until it restarts): Ctrl+C and Ctrl+X
 * in the selection tool copy or cut the selection into it, Ctrl+V places it with the [PlacementTool] as an asset is
 * placed (a ghost at the crosshair, turned with R, pasted with Enter or a right click): `paste_asset` pastes it, the
 * same paste as an asset's, each paste a copy of its own.
 */
object Clipboard {
    /** Its name for `paste_asset` and `asset_content`. */
    const val ID = "clipboard"
    private const val NOTE_MS = 6000L

    private var busy = false
    /** The last copy's outcome and when, on the selection tool's card a few seconds. */
    private var outcome: Pair<String, Long>? = null

    /** Copies the selection into the clipboard, or cuts it ([cut]: taken out of the world, one undo step). */
    fun copy(cut: Boolean) {
        val what = if (cut) "cut" else "copy"
        if (!Bridge.connected) return Chat.warn("Copy and paste need the Foreman plugin on the server")
        val box = SelectionTool.current() ?: return Chat.warn("Nothing to $what: select a box first (click two corners)")
        if (busy) return
        busy = true
        val args = buildJsonObject {
            put("from", JsonArray(listOf(box.minX, box.minY, box.minZ).map { JsonPrimitive(it) }))
            put("to", JsonArray(listOf(box.maxX, box.maxY, box.maxZ).map { JsonPrimitive(it) }))
            if (cut) put("cut", true)
            put("format", "json")
            Bridge.world.takeIf { it.isNotEmpty() }?.let { put("world", it) }
        }
        ServerCalls.run({ ServerCalls.text(ServerCalls.callNow("copy_region", args)) }) { result ->
            busy = false
            val now = System.currentTimeMillis()
            result.onSuccess { text ->
                val json = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
                val held = listOf("blocks", "entities", "furniture", "points", "areas", "copies")
                    .mapNotNull { k -> json[k].int()?.takeIf { it > 0 }?.let { "$it $k" } }.joinToString(", ").ifEmpty { "nothing but air" }
                val line = "${if (json["cut"].bool() == true) "Cut" else "Copied"} ${box.sizeX}×${box.sizeY}×${box.sizeZ}: $held"
                outcome = "§a✔ $line" to now
                Chat.success("$line. Ctrl+V pastes it" + if (cut) " (/foreman undo puts it back)" else "")
            }.onFailure {
                outcome = "§c✖ ${it.message.orEmpty().take(60)}" to now
                Chat.error("Couldn't $what the selection: ${it.message}")
            }
        }
    }

    /** Places the clipboard with the [PlacementTool], once the server said its size (the AI may have copied since). */
    fun paste() {
        if (!Bridge.connected) return Chat.warn("Copy and paste need the Foreman plugin on the server")
        if (busy) return
        busy = true
        val args = buildJsonObject {
            put("name", ID)
            put("max_blocks", 0)
            put("format", "json")
        }
        ServerCalls.run({
            val text = ServerCalls.text(ServerCalls.callNow("asset_content", args))
            val json = runCatching { JSON.parseToJsonElement(text) }.getOrNull()
            json["size"].arr()?.mapNotNull { it.int() }?.takeIf { it.size == 3 } ?: throw AgentException(text.ifBlank { "The clipboard is empty" })
        }) { result ->
            busy = false
            result.onSuccess { size -> PlacementTool.start(LibraryAsset(ID, ID, size = size), clipboard = true) }
                .onFailure { Chat.warn(it.message ?: "The clipboard is empty") }
        }
    }

    /** What the selection tool's card says of it: copying, or the last copy's outcome a few seconds. */
    fun note(): Note? = if (busy) Note("Asking the server…", Note.Tone.BUSY)
        else outcome?.takeIf { System.currentTimeMillis() - it.second < NOTE_MS }?.let { Note.coloured(it.first) }
}
