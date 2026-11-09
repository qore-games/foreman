package games.qore.foreman.client.library

import games.qore.foreman.client.link.AssetInstance
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.str
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A pasted copy's panel, beside the world: its asset and the area it came from, its turn, who pasted it and when, the
 * points it carries, and its actions: go to its asset's area (the server loads that world when needed), make its box
 * the selection, teleport to it, detach it from its asset, turn it a quarter, sync it with its asset (pasted again as
 * the asset is now) and delete it. Sync, detach and delete ask a second click.
 */
class CopyPanel(private val given: AssetInstance) : KitScreen("Pasted copy") {
    private val panelW = 270
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20

    /** The bridge's copy as it is now, else the one given (from a list, or detached since). */
    private val copy get() = Bridge.instances.firstOrNull { it.id == given.id } ?: given
    /** The action waiting for its second click: `detach`, `sync` or `delete`. */
    private var asking: String? = null
    private var detached = false
    private var deleted = false
    private var busy = false
    private var message = "" to Theme.FAINT

    override val scrim get() = 0

    override fun draw(ui: Ui) {
        val copy = copy
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.title(if (deleted) "Deleted copy" else if (detached) "Detached copy" else "Pasted copy", px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        var y = py + 32
        fun row(label: String, value: String, color: Int = Theme.TEXT) {
            ui.text(label, px + 10, y, Theme.MUTED)
            y += maxOf(10, ui.paragraph(value, px + 64, y, panelW - 74, color)) + 3
        }
        val box = copy.blockBox
        row("Asset", copy.assetName)
        row("From", copy.source?.let { "${it.area} in ${it.world}" } ?: copy.asset, Theme.MUTED)
        row("Where", "${copy.world} · ${box.minX} ${box.minY} ${box.minZ} → ${box.maxX} ${box.maxY} ${box.maxZ} (${box.sizeX}×${box.sizeY}×${box.sizeZ})")
        row("Turned", "${copy.rotate}° clockwise")
        row("Pasted", listOfNotNull(copy.by.ifEmpty { null }, created(copy)).joinToString(" · ").ifEmpty { "?" })
        row("Id", copy.id, Theme.FAINT)
        y += 4
        actions(ui, copy, y)
        points(ui, copy, y + 68)
        ui.text(ui.fit(message.first.ifEmpty { "Esc closes" }, panelW - 20), px + 10, py + ph - 14, if (message.first.isEmpty()) Theme.FAINT else message.second)
    }

    private fun actions(ui: Ui, copy: AssetInstance, top: Int) {
        val w = (panelW - 20 - 4) / 2
        val on = !busy && !detached && !deleted && Bridge.connected
        ui.button(px + 10, top, w, 18, "Open in library", Style.PRIMARY, on, tooltip = "Takes you to the asset's area, in its library world") {
            call { AssetCalls.openSource(copy) { result -> done(result) { onClose() } } }
        }
        ui.button(px + 14 + w, top, w, 18, "Select", tooltip = "Makes its box Foreman's selection") {
            val dimension = copy.dimension() ?: return@button say("Its dimension is unknown: go to its world first", Theme.WARN)
            SelectionTool.select(dimension, copy.blockBox)
            say("Its box is the selection", Theme.OK)
        }
        val y = top + 22
        ui.button(px + 10, y, w, 18, "Teleport", tooltip = "On top of it (/tp)") {
            AssetCalls.teleport(copy)
            onClose()
        }
        ui.button(px + 14 + w, y, w, 18, if (asking == "detach") "Sure?" else "Detach", Style.DANGER, on,
            tooltip = "It stays in the world as plain blocks, no longer a copy of its asset. Click twice") {
            if (ask("detach", "Detach: it stays as plain blocks. Click again")) call {
                AssetCalls.detach(copy) { result ->
                    done(result) {
                        detached = true
                        say("Detached: plain blocks now", Theme.OK)
                    }
                }
            }
        }
        val third = (panelW - 20 - 8) / 3
        val y2 = y + 22
        ui.button(px + 10, y2, third, 18, "Turn 90°", enabled = on, tooltip = "A quarter clockwise in place, content and all (${AssetTool.keyName("asset-turn")})") {
            asking = null
            call { AssetTool.turnCopy(copy, confirmed = true) { result -> done(result) { say("Turned", Theme.OK) } } }
        }
        ui.button(px + 14 + third, y2, third, 18, if (asking == "sync") "Sure?" else "Sync", enabled = on,
            tooltip = "Replaces the copy with the asset's current content: edits made inside the copy are lost. Click twice (${AssetTool.keyName("copy-sync")})") {
            if (ask("sync", "Sync replaces it with the asset's content: edits made inside it are lost. Click again")) call {
                AssetTool.syncCopy(copy, confirmed = true) { result -> done(result) { say("Synced with its asset", Theme.OK) } }
            }
        }
        ui.button(px + 18 + third * 2, y2, third, 18, if (asking == "delete") "Sure?" else "Delete", Style.DANGER, on,
            tooltip = "Takes it out of the world, its blocks and points. Click twice (${AssetTool.keyName("asset-delete")})") {
            if (ask("delete", "Delete removes its blocks from the world. Click again")) call {
                AssetTool.deleteCopy(copy, confirmed = true) { result ->
                    done(result) {
                        deleted = true
                        say("Deleted (/foreman undo brings it back)", Theme.OK)
                    }
                }
            }
        }
    }

    /** True on the second click of [action]; the first says [warning]. */
    private fun ask(action: String, warning: String): Boolean {
        if (asking == action) {
            asking = null
            return true
        }
        asking = action
        say(warning, Theme.WARN)
        return false
    }

    /** The points the copy carries, where they landed. */
    private fun points(ui: Ui, copy: AssetInstance, top: Int) {
        ui.text("Points (${copy.points.size})", px + 10, top, Theme.ACCENT)
        if (copy.points.isEmpty()) {
            ui.text("None", px + 10, top + 14, Theme.FAINT)
            return
        }
        ui.scroll("points", px + 6, top + 14, panelW - 12, py + ph - 20 - (top + 14)) { y0 ->
            var y = y0
            for (p in copy.points) {
                val at = p.at.joinToString(" ") { if (it % 1.0 == 0.0) it.toInt().toString() else String.format(Locale.ROOT, "%.1f", it) }
                val kind = listOf(p.type, p.kind).filter { it.isNotEmpty() }.joinToString(":")
                ui.text(ui.fit(p.name.ifEmpty { kind }, 110), px + 10, y + 2, Theme.TEXT)
                ui.text(ui.fit(if (p.name.isNotEmpty()) kind else "", panelW - 138 - ui.font.width(at)), px + 124, y + 2, Theme.MUTED)
                ui.text(at, px + panelW - 14 - ui.font.width(at), y + 2, Theme.FAINT)
                y += 12
            }
            y - y0
        }
    }

    private fun call(start: () -> Unit) {
        busy = true
        say("…", Theme.MUTED)
        start()
    }

    private fun done(result: Result<String>, then: () -> Unit) {
        busy = false
        asking = null
        result.onSuccess {
            Chat.success(it.lineSequence().firstOrNull().orEmpty().ifEmpty { "Done" })
            then()
        }.onFailure { say(it.message ?: it.javaClass.simpleName, Theme.ERROR) }
    }

    private fun say(text: String, color: Int) {
        message = text to color
    }

    private companion object {
        val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)

        /** When it was pasted: a time in milliseconds or seconds, or the server's own text. */
        fun created(copy: AssetInstance): String? {
            val value = copy.created ?: return null
            value.double()?.let { n -> return DATE.format(Date(if (n > 1e11) n.toLong() else (n * 1000).toLong())) }
            return value.str()?.replace('T', ' ')?.take(16)
        }
    }
}
