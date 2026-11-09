package games.qore.foreman.client.ui

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorMode
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.library.AssetCalls
import games.qore.foreman.client.library.AssetCopies
import games.qore.foreman.client.library.AssetPanel
import games.qore.foreman.client.library.AssetRef
import games.qore.foreman.client.library.AssetTool
import games.qore.foreman.client.library.CopyPanel
import games.qore.foreman.client.library.Library
import games.qore.foreman.client.library.LibraryAsset
import games.qore.foreman.client.library.PlacementTool
import games.qore.foreman.client.library.Renders
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.ui.kit.grid
import games.qore.foreman.client.util.GuiTexture
import net.minecraft.client.input.KeyEvent
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The hub's Library section: the asset tool's card, then the server's assets as cards (thumbnail, name, size, tags) by
 * world, filtered by the search and tag chips; a card opens the asset, turned around by dragging its image through the
 * views, with where it was pasted; Place hands it to the [PlacementTool], Edit in world to the [AssetTool].
 */
object LibraryPanel {
    /** The asset opened, by id. */
    private var opened: String? = null
    private var view = 0
    private val tags = LinkedHashSet<String>()
    /** A drag through the views: where it started (NaN until the mouse moves) and the view then. */
    private var dragFrom: Double? = null
    private var dragView = 0
    /** How far a drag goes for one view. */
    private var dragStep = 20.0

    private fun openedAsset() = opened?.let { id -> Library.assets.firstOrNull { it.id == id } }

    /** Whether an asset is open (the hub hides its search field then). */
    val detailOpen get() = openedAsset() != null

    fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int, query: String, close: () -> Unit) {
        Library.update()
        when {
            !Bridge.connected -> ui.paragraph("The library is the Foreman plugin's: the builds its players tagged as assets and its saved schematics. " +
                "This server doesn't run it.", x, y, w)
            Bridge.hello?.features?.let { it.isNotEmpty() && "world" !in it } == true -> ui.paragraph("This server's Foreman doesn't offer its world tools " +
                "(a production preset): the library is one of them.", x, y, w)
            else -> openedAsset()?.let { detail(ui, it, x, y, w, h, close) } ?: run {
                toolCard(ui, x, y, w, close)
                list(ui, x, y + 56, w, h - 56, query.trim().lowercase())
            }
        }
    }

    /** Opens [id]'s page: shown once the list has it. */
    fun show(id: String) {
        open(id)
    }

    /** The asset tool: its key, what it does, a new asset, on and off. */
    private fun toolCard(ui: Ui, x: Int, y: Int, w: Int, close: () -> Unit) {
        val on = AssetTool.enabled
        ui.card(x, y, w, 46, if (on) Theme.PANEL_HOVER else Theme.PANEL, if (on) Theme.ACCENT else Theme.BORDER)
        ui.text("Asset tool", x + 10, y + 8, Theme.TEXT)
        EditorMode.caption(AssetTool).takeIf { it.isNotBlank() }?.let { ui.chip(x + 14 + ui.font.width("Asset tool"), y + 6, it, Theme.ACCENT) }
        ui.paragraph("Make assets from your selection, then move, turn and resize them with their content where they stand. Shows where they were pasted.",
            x + 10, y + 22, w - 190)
        ui.button(x + w - 176, y + 14, 90, 18, "+ New asset", Style.PRIMARY,
            tooltip = "From your selection (Foreman's, else WorldEdit's): its panel opens. Without one, make it with the selection tool first") {
            AssetTool.create()
            close()
        }
        ui.button(x + w - 80, y + 14, 70, 18, if (on) "Turn off" else "Use") {
            AssetTool.toggle()
            if (!on) close()
        }
    }

    // ---- The list ---------------------------------------------------------------------------------------------------

    private fun list(ui: Ui, x: Int, y: Int, w: Int, h: Int, query: String) {
        val all = Library.assets
        val status = when {
            Library.loading && all.isEmpty() -> "Loading…"
            else -> "${all.size} assets" + if (Library.loading) " · loading…" else ""
        }
        ui.text(status, x, y + 4, Theme.MUTED)
        var hx = x + ui.font.width(status) + 10
        if (Tools.active === PlacementTool) PlacementTool.asset?.let { a ->
            hx += ui.chip(hx, y + 2, ui.fit("● placing ${a.name}", 160), Theme.ACCENT) + 4
            ui.button(hx, y, 40, 16, "Stop", Style.GHOST) { Tools.deactivate() }
        }
        ui.button(x + w - 70, y, 70, 16, "Refresh", tooltip = "Lists the assets again and renders them anew") { Library.refresh() }
        var top = y + 22
        Library.error?.let { top += ui.paragraph(it, x, top, w, Theme.WARN) + 4 }
        top += tagChips(ui, x, top, w, all)
        val shown = all.filter { it.matches(query) && it.tags.containsAll(tags) }
        if (shown.isEmpty()) {
            if (!Library.loading) ui.paragraph(if (all.isEmpty()) EMPTY else "No asset matches the search and tags.", x, top, w, code = Theme.TEXT)
            return
        }
        val bottom = y + h
        ui.scroll("library", x, top, w, bottom - top) { start ->
            var cy = start
            for ((label, assets) in groups(shown)) {
                ui.text(label, x + 2, cy + 4, Theme.ACCENT)
                ui.text("${assets.size}", x + 8 + ui.font.width(label), cy + 4, Theme.FAINT)
                cy += 16
                cy += ui.grid(x, cy, w - 8, assets, 112, 6, top..bottom, { it + 30 }) { a, cx, ccy, cw, ch -> card(ui, a, cx, ccy, cw, ch) } + 10
            }
            cy - start
        }
    }

    private const val EMPTY = "No asset yet. Builders make them in the world: select a build and press New asset above, " +
        "or draw a Foreman area around it (`/foreman mark <name>`) and tag it: `/foreman tag <name> asset`. Schematics saved " +
        "with `/foreman asset save` show here too."

    /** The tags of [all] as chips, the most used first, a click filters by it; returns their height. */
    private fun tagChips(ui: Ui, x: Int, y: Int, w: Int, all: List<LibraryAsset>): Int {
        val counts = all.flatMap { it.labels }.groupingBy { it }.eachCount()
        tags.retainAll(counts.keys)
        if (counts.isEmpty()) return 0
        var cx = x
        var cy = y
        for ((tag, n) in counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })) {
            val label = "#$tag $n"
            val cw = ui.font.width(label) + 8
            if (cx + cw > x + w) {
                if (cy > y) break
                cx = x
                cy += 16
            }
            val on = tag in tags
            ui.chip(cx, cy, label, if (on) Theme.TEXT else Theme.MUTED, if (on) Theme.ACCENT_DARK else Theme.FIELD)
            ui.hit(cx, cy, cw, 12, if (on) "Click: every tag" else "Only the assets tagged $tag") { if (!tags.remove(tag)) tags += tag }
            cx += cw + 4
        }
        return cy - y + 20
    }

    /** By world, the player's first, then the saved schematics. */
    private fun groups(assets: List<LibraryAsset>): List<Pair<String, List<LibraryAsset>>> {
        val byWorld = assets.filter { it.isArea }.groupBy { it.world.orEmpty() }.toList()
            .sortedWith(compareBy<Pair<String, List<LibraryAsset>>> { it.first != Bridge.world }.thenBy { it.first })
            .map { (world, list) -> (if (world == Bridge.world) "World $world · yours" else "World $world") to list.sortedBy { it.name } }
        val files = assets.filter { !it.isArea }.sortedBy { it.name }
        return byWorld + if (files.isEmpty()) emptyList() else listOf("Saved schematics" to files)
    }

    private fun card(ui: Ui, asset: LibraryAsset, x: Int, y: Int, w: Int, h: Int) {
        val hover = ui.hovered(x, y, w, h)
        ui.card(x, y, w, h, if (hover) Theme.PANEL_HOVER else Theme.PANEL, if (hover) Theme.ACCENT_DARK else Theme.BORDER)
        val side = w - 8
        image(ui, Library.thumbnail(asset), 0, x + 4, y + 4, side, side)
        ui.text(ui.fit(asset.name, w - 12), x + 6, y + side + 9, Theme.TEXT)
        var cx = x + 6
        ui.text(asset.sizeLabel, cx, y + side + 21, Theme.MUTED)
        cx += ui.font.width(asset.sizeLabel) + 6
        for (tag in asset.labels) {
            if (cx + ui.font.width(tag) + 8 > x + w - 4) break
            cx += ui.chip(cx, y + side + 19, tag) + 3
        }
        ui.hit(x, y, w, h, asset.description.ifEmpty { null }) { open(asset) }
    }

    /** The [index]th image of [renders] fitted in the box, or what is happening instead. */
    private fun image(ui: Ui, renders: Renders?, index: Int, x: Int, y: Int, w: Int, h: Int) {
        ui.rect(x, y, w, h, Theme.FIELD)
        val texture = renders?.textures?.getOrNull(index)
        when {
            texture != null -> fit(ui, texture, x, y, w, h)
            renders?.error != null -> {
                ui.text("no preview", x + (w - ui.font.width("no preview")) / 2, y + h / 2 - 4, Theme.FAINT)
                ui.hit(x, y, w, h, renders.error)
            }
            else -> ui.text("…", x + (w - ui.font.width("…")) / 2, y + h / 2 - 4, Theme.FAINT)
        }
    }

    private fun fit(ui: Ui, t: GuiTexture, x: Int, y: Int, w: Int, h: Int) {
        val scale = min(w / t.width.toFloat(), h / t.height.toFloat())
        val dw = (t.width * scale).toInt()
        val dh = (t.height * scale).toInt()
        ui.image(t.id, x + (w - dw) / 2, y + (h - dh) / 2, dw, dh, t.width, t.height)
    }

    // ---- An asset ---------------------------------------------------------------------------------------------------

    private fun open(asset: LibraryAsset) = open(asset.id)

    private fun open(id: String) {
        opened = id
        view = 0
        dragFrom = null
    }

    private fun back() {
        opened = null
        dragFrom = null
    }

    private fun detail(ui: Ui, asset: LibraryAsset, x: Int, y: Int, w: Int, h: Int, close: () -> Unit) {
        ui.button(x, y - 2, 74, 16, "← Library", Style.GHOST, tooltip = "Esc") { back() }
        val top = y + 20
        val side = min(w * 11 / 20, h - 20 - 22).coerceAtLeast(60)
        val renders = Library.views(asset)
        val count = renders.textures.size
        if (count > 0) view = view.mod(count)
        val error = renders.error
        if (error == null) image(ui, renders, view, x, top, side, side)
        else ui.rect(x, top, side, side, Theme.FIELD).also { ui.paragraph("No preview: $error", x + 6, top + 6, side - 12, Theme.WARN) }
        if (renders.loading) ui.text("Rendering ${Library.VIEWS} views…", x + 6, top + 6, Theme.MUTED)
        if (count > 1) {
            dragStep = side / count.toDouble()
            ui.hit(x, top, side, side, "Drag to turn it (or ← →)") { dragFrom = Double.NaN; dragView = view }
            val cy = top + side + 4
            ui.button(x, cy, 24, 16, "◀", tooltip = "←") { step(-1) }
            ui.button(x + side - 24, cy, 24, 16, "▶", tooltip = "→") { step(1) }
            val label = "View ${view + 1} / $count · drag to turn"
            ui.text(label, x + (side - ui.font.width(label)) / 2, cy + 4, Theme.FAINT)
        }

        val rx = x + side + 14
        val rw = w - side - 14
        ui.title(ui.fit(asset.name, (rw / 1.5).toInt()), rx, top)
        var ry = top + 18
        ui.text(ui.fit(asset.id, rw), rx, ry, Theme.FAINT)
        ry += 16
        fun row(label: String, value: String, color: Int = Theme.TEXT) {
            ui.text(label, rx, ry, Theme.MUTED)
            ry += maxOf(10, ui.paragraph(value, rx + 70, ry, rw - 70, color)) + 4
        }
        if (asset.isArea) row("World", asset.world.orEmpty()) else row("From", "a saved schematic")
        row("Size", "${asset.sizeX} × ${asset.sizeY} × ${asset.sizeZ} blocks (x, y, z)")
        asset.category?.let { row("Category", it) }
        if (asset.blocks.isNotEmpty()) row("Blocks", asset.blocks.take(8).joinToString(", "), Theme.MUTED)
        if (asset.furniture > 0 || asset.displays > 0) row("Entities", "${asset.furniture} furniture · ${asset.displays} displays")
        if (asset.labels.isNotEmpty()) {
            ui.text("Tags", rx, ry, Theme.MUTED)
            var cx = rx + 70
            for (tag in asset.labels) {
                if (cx + ui.font.width(tag) + 8 > rx + rw) break
                cx += ui.chip(cx, ry - 2, tag) + 4
            }
            ry += 16
        }
        if (asset.description.isNotEmpty()) ry += ui.paragraph(asset.description, rx, ry + 4, rw, Theme.TEXT) + 8
        ry += 6
        ui.button(rx, ry, 90, 18, "Place", Style.PRIMARY, tooltip = "Its box follows where you look: scroll or R turns it, right click pins it, Enter pastes it") {
            PlacementTool.start(asset)
            close()
        }
        ui.button(rx + 96, ry, 70, 18, "Refresh", tooltip = "Renders it again") { Library.rerender(asset) }
        ry += 24
        if (asset.isArea && asset.world != null) {
            val ref = AssetRef(asset.world, asset.name)
            ui.button(rx, ry, 90, 18, "Edit in world", tooltip = "The asset tool with it selected: move, turn and resize it with its content") {
                AssetTool.edit(ref)
                close()
            }
            ui.button(rx + 96, ry, 70, 18, "Go there", tooltip = "Takes you to it, in its world") {
                AssetCalls.openSource(asset.id) { result -> result.onSuccess { Chat.success(it.lineSequence().first()) }.onFailure { Chat.error("Couldn't go there: ${it.message}") } }
                close()
            }
            ry += 26
        }
        usedIn(ui, asset, rx, ry, rw, top + side, close)
    }

    /** Where [asset] was pasted (`list_instances`): each copy's world and corner, to open its panel or go there. */
    private fun usedIn(ui: Ui, asset: LibraryAsset, x: Int, y: Int, w: Int, bottom: Int, close: () -> Unit) {
        val copies = AssetCopies.of(asset.id)
        val list = copies.list
        ui.text("Used in" + (list?.let { " · ${it.size}" } ?: ""), x, y, Theme.ACCENT)
        val top = y + 14
        when {
            list == null && copies.error != null -> ui.paragraph(copies.error!!, x, top, w, Theme.FAINT)
            list == null -> ui.text("Loading…", x, top, Theme.FAINT)
            list.isEmpty() -> ui.text("Not pasted anywhere yet", x, top, Theme.FAINT)
            else -> ui.scroll("used-in", x, top, w, (bottom - top).coerceAtLeast(18)) { y0 ->
                var ry = y0
                for (copy in list) {
                    AssetPanel.copyRow(ui, copy, x, ry, w - 76) { Screens.open(CopyPanel(copy)) }
                    ui.button(x + w - 72, ry, 34, 15, "Open", Style.GHOST, tooltip = "Its panel: asset, points, detach") { Screens.open(CopyPanel(copy)) }
                    ui.button(x + w - 36, ry, 30, 15, "Go", Style.GHOST, tooltip = "Teleport on top of it (/tp)") {
                        AssetCalls.teleport(copy)
                        close()
                    }
                    ry += 17
                }
                ry - y0
            }
        }
    }

    private fun step(by: Int) {
        view += by
    }

    /**
     * A drag in the hub, in its units: turns the asset opened while its image is held. The views go clockwise around it
     * seen from above: dragging right takes the next one, so its near side follows the mouse.
     */
    fun drag(mouseX: Double): Boolean {
        val from = dragFrom?.takeUnless { it.isNaN() } ?: mouseX.also { dragFrom = it }
        view = dragView + ((mouseX - from) / dragStep).roundToInt()
        return true
    }

    fun release() {
        dragFrom = null
    }

    /** ← → turn the asset opened, Esc goes back to the list. */
    fun keyPressed(event: KeyEvent): Boolean {
        if (!detailOpen) return false
        when {
            event.isEscape -> back()
            event.key() == InputConstants.KEY_LEFT -> step(-1)
            event.key() == InputConstants.KEY_RIGHT -> step(1)
            else -> return false
        }
        return true
    }
}
