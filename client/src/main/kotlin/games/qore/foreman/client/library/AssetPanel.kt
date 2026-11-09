package games.qore.foreman.client.library

import games.qore.foreman.client.editor.EditorMode
import games.qore.foreman.client.link.AssetInstance
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.LibraryPanel
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component

/**
 * The asset tool's panel, beside the world: a new asset's name, tags and note, its box the player's selection, with
 * fitting its box to its content and putting it in the world's next free plot (`define_asset`); or the selected
 * [asset]'s, editable, its actions (fit, turn, delete the area, its library page) and its pasted copies. Enter saves.
 */
class AssetPanel(private val asset: AssetRef?) : KitScreen("Asset") {
    private val panelW = 270
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20

    private val fields = mutableListOf<EditBox>()
    /** What the fields hold (name, tags, note), kept when the screen is laid out again. */
    private val values = initial()
    private var fit = false
    private var plot = false
    private var saving = false
    private var deleting = false
    private var message = "" to Theme.FAINT

    override val scrim get() = 0

    private fun initial(): MutableList<String> {
        val area = asset?.area() ?: return mutableListOf("", "", "")
        return mutableListOf(area.name, area.tags.filter { it != Library.TAG }.joinToString(" "), area.note)
    }

    override fun build() {
        fields.clear()
        listOf("Its name in the library", "Tags, by spaces: tree ruin", "What it is (its description)").forEachIndexed { i, hint ->
            fields += addRenderableWidget(EditBox(font, px + 54, py + 48 + i * 22, panelW - 64, 16, Component.literal(hint)).also { box ->
                box.setMaxLength(if (i == 2) 500 else 64)
                box.setHint(Component.literal(hint))
                box.value = values[i]
                box.setResponder { values[i] = it }
            })
        }
        if (asset == null) setInitialFocus(fields[0])
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isConfirmation) {
            save()
            return true
        }
        return super.keyPressed(event)
    }

    private fun save() {
        val name = values[0].trim()
        if (name.isEmpty()) return say("Give it a name", Theme.WARN)
        if (saving) return
        val tags = values[1].split(' ', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        saving = true
        say("Saving…", Theme.MUTED)
        AssetTool.save(asset, name, tags, values[2].trim(), fit, plot) { result ->
            saving = false
            result.onSuccess {
                when {
                    asset == null -> onClose()
                    asset.name != name -> minecraft.gui.setScreen(AssetPanel(AssetTool.selected))
                    else -> say("Saved", Theme.OK)
                }
            }.onFailure { say(it.message ?: "Couldn't save", Theme.ERROR) }
        }
    }

    private fun say(text: String, color: Int) {
        message = text to color
    }

    override fun draw(ui: Ui) {
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.title(if (asset == null) "New asset" else "Asset", px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        val area = asset?.area()
        val box = if (asset == null) SelectionTool.current() else AssetTool.selectedBox().takeIf { AssetTool.selected == asset } ?: area?.bounds
        val where = box?.let { "${it.sizeX}×${it.sizeY}×${it.sizeZ} at ${it.minX} ${it.minY} ${it.minZ}" }
        val head = when {
            asset == null && box == null -> "No selection: make one with the selection tool"
            asset == null -> "${SelectionTool.currentLabel()} · ${Bridge.world}"
            area == null -> "${asset.name} isn't in the server's areas (any more)"
            else -> "${area.world} · $where"
        }
        ui.text(ui.fit(head, panelW - 20), px + 10, py + 30, if (asset == null && box == null) Theme.WARN else Theme.MUTED)
        if (asset == null && box == null) ui.button(px + panelW - 112, py + 8, 84, 16, "Selection tool", Style.PRIMARY,
            tooltip = "Make the box with the selection tool (${EditorMode.caption(SelectionTool)}), then come back (${EditorMode.caption(AssetTool)}) and press ${AssetTool.keyName("asset-new")}") {
            onClose()
            EditorMode.open(SelectionTool)
        }
        listOf("Name", "Tags", "Note").forEachIndexed { i, label -> ui.text(label, px + 10, py + 52 + i * 22, Theme.TEXT) }
        var y = py + 48 + 3 * 22 + 4
        if (asset == null) {
            y += ui.switch(px + 10, y, panelW - 20, "Fit the box to its content", fit, "Shrinks the box to what it holds, a block around") {
                fit = !fit
                if (fit) plot = false
            }
            y += ui.switch(px + 10, y, panelW - 20, "On the next free plot, to build it there", plot,
                "A box of this size on the world's next free plot (a grid beside the other assets), and takes you there. What this box holds stays") {
                plot = !plot
                if (plot) fit = false
            }
            y += 4
        }
        val ready = !saving && !AssetTool.busy && (asset == null && box != null || area != null)
        val w = (panelW - 20 - 4) / 2
        ui.button(px + 10, y, w, 18, if (asset == null) "Make it (Enter)" else "Save (Enter)", Style.PRIMARY, ready) { save() }
        ui.button(px + 14 + w, y, w, 18, if (asset == null) "Cancel" else "Close", Style.GHOST) { onClose() }
        y += 24
        if (asset != null) actions(ui, asset, area != null && !AssetTool.busy, y)
        ui.text(ui.fit(message.first.ifEmpty { if (asset == null) "Enter makes it · Esc closes" else "Enter saves the name, tags and note" }, panelW - 20),
            px + 10, py + ph - 14, if (message.first.isEmpty()) Theme.FAINT else message.second)
    }

    /** Fit, turn, delete the area and its library page, then its copies. */
    private fun actions(ui: Ui, asset: AssetRef, on: Boolean, top: Int) {
        val w = (panelW - 20 - 8) / 3
        ui.button(px + 10, top, w, 16, "Fit", enabled = on, tooltip = "Its box around its content, a block around (${AssetTool.keyName("asset-fit")})") { AssetTool.fit() }
        ui.button(px + 14 + w, top, w, 16, "Turn", enabled = on, tooltip = "A quarter clockwise in place, content and all (${AssetTool.keyName("asset-turn")})") { AssetTool.turn() }
        ui.button(px + 18 + w * 2, top, w, 16, if (deleting) "Sure?" else "Delete area", Style.DANGER, on,
            tooltip = "Deletes the area only: its blocks stay in the world. Click twice") {
            if (!deleting) deleting = true else AssetTool.delete(confirmed = true) { result -> result.onSuccess { onClose() }.onFailure { say(it.message.orEmpty(), Theme.ERROR) } }
        }
        val y = top + 20
        ui.button(px + 10, y, panelW - 20, 16, "Its page in the library", tooltip = "The hub's Library page: its views, size, copies") {
            LibraryPanel.show(asset.id)
            minecraft.gui.setScreen(HubScreen(HubScreen.Section.LIBRARY))
        }
        copies(ui, asset, y + 26)
    }

    /** Where it was pasted (`list_instances`): a click opens the copy's panel. */
    private fun copies(ui: Ui, asset: AssetRef, top: Int) {
        val copies = AssetCopies.of(asset.id)
        val list = copies.list
        ui.text("Copies" + (list?.let { " (${it.size})" } ?: ""), px + 10, top, Theme.ACCENT)
        val y = top + 14
        val bottom = py + ph - 20
        when {
            list == null && copies.error != null -> ui.paragraph(copies.error!!, px + 10, y, panelW - 20, Theme.WARN)
            list == null -> ui.text("Loading…", px + 10, y, Theme.FAINT)
            list.isEmpty() -> ui.text("Not pasted anywhere yet", px + 10, y, Theme.FAINT)
            else -> ui.scroll("copies", px + 6, y, panelW - 12, bottom - y) { y0 ->
                var ry = y0
                for (copy in list) {
                    copyRow(ui, copy, px + 6, ry, panelW - 18) { minecraft.gui.setScreen(CopyPanel(copy)) }
                    ry += 16
                }
                ry - y0
            }
        }
    }

    companion object {
        /** A copy in a list: its world and corner, its turn and who pasted it; [open] on a click. */
        fun copyRow(ui: Ui, copy: AssetInstance, x: Int, y: Int, w: Int, open: () -> Unit) {
            if (ui.hovered(x, y, w, 15)) ui.rect(x, y, w, 15, Theme.PANEL_HOVER)
            val b = copy.blockBox
            val turn = "${copy.rotate}°"
            ui.text(ui.fit("${copy.world} · ${b.minX} ${b.minY} ${b.minZ}" + if (copy.by.isNotEmpty()) " · ${copy.by}" else "", w - 14 - ui.font.width(turn)), x + 4, y + 4, Theme.TEXT)
            ui.text(turn, x + w - 4 - ui.font.width(turn), y + 4, Theme.MUTED)
            ui.hit(x, y, w, 15, "Its panel", open)
        }
    }
}
