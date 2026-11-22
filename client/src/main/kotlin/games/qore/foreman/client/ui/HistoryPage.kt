package games.qore.foreman.client.ui

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.JSON
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.CompletableFuture

/**
 * The hub's World → History page: the server's undo history (its `history` tool), newest first: when, who, what, the
 * world and the size of the box it changed, with Teleport to the box and, on the player's own changes, Undo (the
 * server's `undo`, back to that one: it refuses one that others changed over since, and says so). The player's own
 * changes, or everyone's when the server lets them read those. Read when the page opens and on Refresh: nothing is
 * asked while it is closed.
 */
object HistoryPage {
    /** A change as `history` gives it in JSON: [undo] is the undo count that takes it back (the player's own only). */
    @Serializable
    data class Change(
        val order: Long, val time: Long, val by: String, val mine: Boolean = false, val label: String, val world: String, val dim: String = "",
        val blocks: Int = 0, val box: List<Int>? = null, val undo: Int? = null,
    )

    /** What `history` gives: the changes, and whether this player may read [everyone]'s. */
    @Serializable
    data class Page(val everyone: Boolean = false, val total: Int = 0, val changes: List<Change> = emptyList())

    private const val ROW = 30
    private var page: Page? = null
    private var everyone = false
    private var loading = false
    private var message: Pair<String, Int>? = null
    /** When the page was last drawn: a gap means it was opened again, and is read again. */
    private var drawn = 0L
    /** The change whose Undo was clicked once: a second click undoes. */
    private var confirm: Long? = null

    fun draw(ui: Ui, x: Int, y0: Int, w: Int, h: Int, close: () -> Unit) {
        val now = System.currentTimeMillis()
        if (now - drawn > 1000) {
            message = null
            refresh()
        }
        drawn = now
        if (!Bridge.connected) {
            ui.paragraph(Bridge.unavailable("the history is its, made by its tools") + ".", x, y0, w, Theme.WARN)
            return
        }
        val current = page
        var bx = x
        if (current?.everyone == true) bx += ui.segmented(x, y0, listOf("Mine", "Everyone's"), if (everyone) 1 else 0) { i ->
            everyone = i == 1
            refresh()
        } + 8
        val count = when {
            current == null -> if (loading) "Reading…" else ""
            current.total == 0 -> "No changes"
            else -> "${current.total} change${if (current.total == 1) "" else "s"}" + if (current.total > current.changes.size) ", the last ${current.changes.size}" else ""
        }
        ui.text(ui.fit(count, w - (bx - x) - 80), bx, y0 + 4, Theme.MUTED)
        ui.button(x + w - 70, y0, 70, 16, if (loading) "Reading…" else "Refresh", Style.NORMAL, !loading) { refresh() }
        var y = y0 + 22
        message?.let { (text, color) -> y += ui.paragraph(text, x, y, w, color) + 4 }
        val changes = current?.changes.orEmpty()
        if (current != null && changes.isEmpty()) {
            ui.paragraph(if (everyone) "Nobody has changed the world with Foreman since the server started." else "You haven't changed the world with Foreman since the server started: your AI's edits and yours show here.", x, y, w, Theme.FAINT)
            return
        }
        val top = y
        val height = y0 + h - top
        ui.scroll("history", x, top, w, height) { start ->
            changes.forEachIndexed { i, c ->
                val ry = start + i * ROW
                if (ry + ROW >= top && ry <= top + height) row(ui, c, x, ry, w - 6, close)
            }
            changes.size * ROW
        }
    }

    private fun row(ui: Ui, c: Change, x: Int, y: Int, w: Int, close: () -> Unit) {
        ui.card(x, y, w, ROW - 3, if (ui.hovered(x, y, w, ROW - 3)) Theme.PANEL_HOVER else Theme.PANEL)
        val buttons = (if (c.box != null) 64 else 0) + (if (c.undo != null) 84 else 0)
        ui.text(ui.fit(c.label, w - 16 - buttons), x + 8, y + 4, Theme.TEXT)
        val size = c.box?.let { b -> " · ${b[3] - b[0] + 1}×${b[4] - b[1] + 1}×${b[5] - b[2] + 1}" }.orEmpty()
        val details = "${ago(c.time)} · ${if (c.mine) "you" else c.by} · ${c.world}$size · ${c.blocks} block${if (c.blocks == 1) "" else "s"}"
        ui.text(ui.fit(details, w - 16 - buttons), x + 8, y + 15, if (c.mine) Theme.MUTED else Theme.FAINT)
        var bx = x + w - 6
        c.undo?.let { count ->
            bx -= 80
            val asking = confirm == c.order
            val label = if (asking) "Sure?" else if (count == 1) "Undo" else "Undo to here"
            val tip = if (count == 1) "Undo your last change" else "Undo your last $count changes, back to this one (undo takes them back newest first)"
            ui.button(bx, y + 5, 78, 16, label, if (asking) Style.DANGER else Style.NORMAL, !loading, tip) {
                if (!asking) confirm = c.order else undo(count)
            }
        }
        if (c.box != null) {
            bx -= 62
            ui.button(bx, y + 5, 60, 16, "Teleport", Style.GHOST, tooltip = "Go above the box it changed (the game's /tp)") {
                teleport(c)
                close()
            }
        }
    }

    /** Reads the history again (off the client thread). */
    fun refresh() {
        if (loading || !Bridge.connected) return
        loading = true
        confirm = null
        val name = Minecraft.getInstance().player?.name?.string
        val args = buildJsonObject {
            put("format", "json")
            if (!everyone && name != null) put("player", name)
        }
        ServerCalls.run({ JSON.decodeFromString(Page.serializer(), ServerCalls.text(ServerCalls.callNow("history", args))) }) { result ->
            loading = false
            result.onSuccess {
                page = it
                if (!it.everyone) everyone = false
            }.onFailure { message = (it.message ?: "Couldn't read the history") to Theme.ERROR }
        }
    }

    /** The server's `undo` of the player's last [count] changes; its answer (a refusal too) shows on the page. */
    private fun undo(count: Int) {
        confirm = null
        loading = true
        ServerCalls.run({ ServerCalls.text(ServerCalls.callNow("undo", buildJsonObject { put("count", count) })) }) { result ->
            loading = false
            message = result.fold({ it to if ("not undone" in it) Theme.WARN else Theme.OK }, { (it.message ?: "Couldn't undo") to Theme.ERROR })
            refresh()
        }
    }

    /** Above the middle of the box [c] changed, in its dimension, with the game's /tp. */
    private fun teleport(c: Change) {
        val b = c.box ?: return
        val minecraft = Minecraft.getInstance()
        val top = Vec3((b[0] + b[3] + 1) / 2.0, b[4] + 1.0, (b[2] + b[5] + 1) / 2.0)
        if (c.dim.isEmpty() || minecraft.level?.dimension()?.identifier()?.toString() == c.dim) {
            // As client_teleport does (vanilla's /minecraft:tp, else /tp), off the client thread: it waits for the replies.
            CompletableFuture.runAsync { runCatching { ClientTools.teleport(top) } }
        } else {
            minecraft.player?.connection?.sendCommand("execute in ${c.dim} run tp @s " + "%.1f %.1f %.1f".format(Locale.ROOT, top.x, top.y, top.z))
        }
    }

    private fun ago(time: Long): String {
        val s = (System.currentTimeMillis() - time) / 1000
        return when {
            s < 60 -> "${s}s ago"
            s < 3600 -> "${s / 60} min ago"
            s < 86_400 -> "${s / 3600} h ${s % 3600 / 60} min ago"
            else -> "${s / 86_400} days ago"
        }
    }
}
