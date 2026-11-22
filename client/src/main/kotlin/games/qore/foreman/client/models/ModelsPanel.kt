package games.qore.foreman.client.models

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.ui.kit.filterChips
import games.qore.foreman.client.ui.kit.grid
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemDisplayContext
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The hub's Models page: every item model of the loaded resource packs as cards drawn in 3D (as in an inventory),
 * by folder, filtered by the search, the namespace chips (vanilla's hidden at first) and the folder chips. A card opens
 * the model as a display shows it, turned by dragging, with Place (a display entity), Place as furniture (when the
 * server says it is one) and Copy id.
 */
object ModelsPanel {
    private const val VANILLA = "minecraft"
    private const val DEFAULT_YAW = 225f
    private const val DEFAULT_PITCH = 25f

    private var opened: Identifier? = null
    /** Namespaces left out of the list (vanilla's at first). */
    private val hidden = mutableSetOf(VANILLA)
    /** A top folder the list is narrowed to, `<namespace>:<folder>`. */
    private var folder: String? = null
    private var onlyFurniture = false
    private var view: View? = null

    private var yaw = DEFAULT_YAW
    private var pitch = DEFAULT_PITCH
    /** A drag turning the model opened: where it started (NaN until the mouse moves) and the angles then. */
    private var dragFrom: Pair<Double, Double>? = null
    private var dragAngles = yaw to pitch
    /** Degrees turned per unit dragged: half a turn across the preview. */
    private var dragRate = 1.0
    /** The furniture id Place as furniture uses, among the model's. */
    private var choice = 0
    private var copiedAt = 0L

    /** Whether a model is open (the hub hides its search field then). */
    val detailOpen get() = opened != null

    fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int, query: String, close: () -> Unit) {
        ItemModels.update()
        opened?.let { detail(ui, it, x, y, w, h, close) } ?: list(ui, x, y, w, h, query.trim().lowercase(Locale.ROOT))
    }

    // ---- The list ---------------------------------------------------------------------------------------------------

    /** What the list shows, computed again when the models, the filters or the furniture change. */
    private class View(
        val key: List<Any?>, val total: Int, val namespaces: List<Pair<String, Int>>, val hidden: Set<String>,
        val folders: List<Pair<String, Int>>, val groups: List<Pair<String, List<Identifier>>>, val shown: Int,
    )

    private fun view(query: String): View {
        val all = ItemModels.all()
        val key = listOf(ItemModels.version, query, hidden.toSet(), folder, onlyFurniture, ItemModels.furniture)
        view?.takeIf { it.key == key }?.let { return it }
        val namespaces = all.groupingBy { it.namespace }.eachCount().toList().sortedWith(compareBy({ it.first == VANILLA }, { it.first }))
        // Hiding every namespace would show nothing: vanilla's alone is shown then.
        val hiddenNow = if (namespaces.all { it.first in hidden }) emptySet() else hidden.toSet()
        val inNamespaces = all.filter { it.namespace !in hiddenNow }
        val folders = inNamespaces.filter { '/' in it.path }.groupingBy { "${it.namespace}:${it.path.substringBefore('/')}" }.eachCount()
            .toList().sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
        if (folder != null && folders.none { it.first == folder }) folder = null
        val furniture = ItemModels.furniture
        val shown = inNamespaces.filter { id ->
            val text = id.toString()
            (folder == null || text.startsWith("$folder/")) && (!onlyFurniture || text in furniture) &&
                (query.isEmpty() || text.contains(query) || furniture[text]?.any { it.contains(query) } == true)
        }
        val groups = shown.groupBy { "${it.namespace}:${it.path.substringBeforeLast('/', "")}" }.toList().sortedBy { it.first }
        return View(key, all.size, namespaces, hiddenNow, folders, groups, shown.size).also { view = it }
    }

    private fun list(ui: Ui, x: Int, y: Int, w: Int, h: Int, query: String) {
        val view = view(query)
        val status = "${view.shown} of ${view.total} models"
        ui.text(status, x, y + 4, Theme.MUTED)
        var hx = x + ui.font.width(status) + 10
        if (Tools.active === ModelPlacementTool) ModelPlacementTool.model?.let { model ->
            hx += ui.chip(hx, y + 2, ui.fit("● placing ${model.path.substringAfterLast('/')}", 160), Theme.ACCENT) + 4
            ui.button(hx, y, 40, 16, "Stop", Style.GHOST) { Tools.deactivate() }
        }
        if (Bridge.connected) ui.button(x + w - 70, y, 70, 16, "Refresh", tooltip = "Asks the server again which models are furniture") { ItemModels.refresh() }
        var top = y + 22
        top += namespaceChips(ui, x, top, w, view)
        top += folderChips(ui, x, top, w, view)
        if (view.shown == 0) {
            ui.paragraph(if (view.total == 0) "No item model loaded." else "No model matches the search and chips.", x, top, w, code = Theme.TEXT)
            return
        }
        val bottom = y + h
        ui.scroll("models", x, top, w, bottom - top) { start ->
            var cy = start
            for ((group, models) in view.groups) {
                val label = if (group.endsWith(':')) group else "$group/"
                ui.text(label, x + 2, cy + 4, Theme.ACCENT)
                ui.text("${models.size}", x + 8 + ui.font.width(label), cy + 4, Theme.FAINT)
                cy += 16
                cy += ui.grid(x, cy, w - 8, models, 76, 6, top..bottom, { it + 24 }) { id, cx, ccy, cw, ch -> card(ui, id, cx, ccy, cw, ch) } + 10
            }
            cy - start
        }
    }

    /** The namespaces as chips (a click shows or hides one), and the furniture filter; returns their height. */
    private fun namespaceChips(ui: Ui, x: Int, y: Int, w: Int, view: View): Int {
        var cx = x
        for ((namespace, n) in view.namespaces) {
            val on = namespace !in view.hidden
            val label = "$namespace $n"
            if (cx + ui.font.width(label) + 8 > x + w) break
            ui.chip(cx, y, label, if (on) Theme.TEXT else Theme.FAINT, if (on) Theme.ACCENT_DARK else Theme.FIELD)
            ui.hit(cx, y, ui.font.width(label) + 8, 12, if (on) "Click: hide the models of $namespace" else "Click: show the models of $namespace") {
                if (!hidden.remove(namespace)) hidden += namespace
            }
            cx += ui.font.width(label) + 12
        }
        val furniture = ItemModels.furniture.size
        if (furniture > 0) {
            val label = "furniture $furniture"
            cx += 8
            ui.chip(cx, y, label, if (onlyFurniture) Theme.TEXT else Theme.ACCENT, if (onlyFurniture) Theme.ACCENT_DARK else Theme.FIELD)
            ui.hit(cx, y, ui.font.width(label) + 8, 12, if (onlyFurniture) "Click: every model" else "Only the models the server's furniture shows") { onlyFurniture = !onlyFurniture }
        }
        return 18
    }

    /** The top folders of the namespaces shown, the fullest first (two rows at most); a click narrows the list to one. */
    private fun folderChips(ui: Ui, x: Int, y: Int, w: Int, view: View): Int {
        if (view.folders.size < 2 && folder == null) return 0
        val several = view.namespaces.count { it.first !in view.hidden } > 1
        val options = view.folders.map { (name, n) -> name to "${if (several) name else name.substringAfter(':')}/ $n" }
        return ui.filterChips(x, y, w, options, folder, 2, { name, on -> if (on) "Click: every folder" else "Only $name/" }) { folder = it }
    }

    private fun card(ui: Ui, id: Identifier, x: Int, y: Int, w: Int, h: Int) {
        val furniture = ItemModels.furniture[id.toString()]
        modelCard(ui, x, y, w, h, id, id.path.substringAfterLast('/'), id.toString() + (furniture?.let { "\nFurniture: " + it.joinToString(", ") } ?: ""),
            badge = if (furniture != null) "furniture" else null) { open(id) }
    }

    /**
     * A card of a grid of models, [w] wide and [w] + 24 tall ([h]): [model] drawn in 3D as in an inventory (when the
     * packs have it and it draws something; else [fallback] draws in its square, or "empty"), [badge] in its corner,
     * [label] under it; a click runs [onClick]. The Models page's, and the objects tool's palette's.
     */
    fun modelCard(
        ui: Ui, x: Int, y: Int, w: Int, h: Int, model: Identifier?, label: String, tooltip: String?, badge: String? = null,
        fallback: ((x: Int, y: Int, side: Int) -> Unit)? = null, onClick: () -> Unit,
    ) {
        val hover = ui.hovered(x, y, w, h)
        ui.card(x, y, w, h, if (hover) Theme.PANEL_HOVER else Theme.PANEL, if (hover) Theme.ACCENT_DARK else Theme.BORDER)
        val side = w - 8
        ui.rect(x + 4, y + 4, side, side, Theme.FIELD)
        val drawn = model != null && ItemModels.has(model) && ModelPreview.draw(ui.g, model, ItemDisplayContext.GUI, x + 4, y + 4, side)
        if (!drawn) fallback?.invoke(x + 4, y + 4, side) ?: ui.text("empty", x + (w - ui.font.width("empty")) / 2, y + side / 2, Theme.FAINT)
        badge?.let { ui.chip(x + 6, y + 6, it, Theme.ACCENT) }
        ui.text(ui.fit(label, w - 12), x + 6, y + side + 9, Theme.TEXT)
        ui.hit(x, y, w, h, tooltip, onClick)
    }

    // ---- A model ----------------------------------------------------------------------------------------------------

    private fun open(id: Identifier) {
        opened = id
        yaw = DEFAULT_YAW
        pitch = DEFAULT_PITCH
        dragFrom = null
        choice = 0
    }

    private fun back() {
        opened = null
        dragFrom = null
    }

    private fun detail(ui: Ui, id: Identifier, x: Int, y: Int, w: Int, h: Int, close: () -> Unit) {
        ui.button(x, y - 2, 74, 16, "← Models", Style.GHOST, tooltip = "Esc") { back() }
        val top = y + 20
        val side = min(w * 11 / 20, h - 20 - 22).coerceAtLeast(60)
        ui.rect(x, top, side, side, Theme.FIELD)
        if (!ModelPreview.draw(ui.g, id, ItemDisplayContext.FIXED, x, top, side, ModelPreview.turntable(yaw, pitch), turning = true)) {
            ui.paragraph("This model draws nothing.", x + 6, top + 6, side - 12, Theme.MUTED)
        }
        dragRate = 180.0 / side
        ui.hit(x, top, side, side, "Drag to turn it (or the arrow keys)") { dragFrom = Double.NaN to Double.NaN; dragAngles = yaw to pitch }
        val cy = top + side + 4
        ui.button(x, cy, 24, 16, "◀", tooltip = "←") { turn(-45f, 0f) }
        ui.button(x + side - 24, cy, 24, 16, "▶", tooltip = "→") { turn(45f, 0f) }
        val label = "${yaw.roundToInt()}° · tilt ${pitch.roundToInt()}°"
        val lx = x + (side - ui.font.width(label)) / 2
        ui.text(label, lx, cy + 4, Theme.FAINT)
        ui.hit(lx - 4, cy, ui.font.width(label) + 8, 16, "As a display shows it. Click: back to the first angle") { yaw = DEFAULT_YAW; pitch = DEFAULT_PITCH }

        val rx = x + side + 14
        val rw = w - side - 14
        ui.title(ui.fit(id.path.substringAfterLast('/'), (rw / 1.5).toInt()), rx, top)
        var ry = top + 18
        ui.text(ui.fit(id.toString(), rw), rx, ry, Theme.FAINT)
        ry += 16
        fun row(label: String, value: String, color: Int = Theme.TEXT) {
            ui.text(label, rx, ry, Theme.MUTED)
            ry += maxOf(10, ui.paragraph(value, rx + 70, ry, rw - 70, color)) + 4
        }
        row("Namespace", id.namespace)
        id.path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let { row("Folder", "$it/") }
        ModelPreview.bounds(id, ItemDisplayContext.FIXED)?.let { b ->
            row("Size", "${fmt(b.xsize)} × ${fmt(b.ysize)} × ${fmt(b.zsize)} blocks as a display (x, y, z)")
        }
        val furniture = ItemModels.furniture[id.toString()].orEmpty()
        when {
            !Bridge.connected -> row("Furniture", Bridge.denied?.let { "Unknown: you don't have $it" } ?: "Unknown: the server doesn't run the Foreman plugin", Theme.MUTED)
            ItemModels.furnitureLoading -> row("Furniture", "Asking the server…", Theme.MUTED)
            ItemModels.furnitureError != null && furniture.isEmpty() -> row("Furniture", "Unknown: ${ItemModels.furnitureError}", Theme.WARN)
            furniture.isEmpty() -> row("Furniture", "None shows it", Theme.MUTED)
            else -> {
                choice = choice.coerceIn(0, furniture.size - 1)
                ui.text("Furniture", rx, ry, Theme.MUTED)
                var fx = rx + 70
                furniture.forEachIndexed { i, f ->
                    val cw = ui.font.width(f) + 8
                    if (fx + cw > rx + rw) return@forEachIndexed
                    val on = i == choice
                    ui.chip(fx, ry - 2, f, if (on) Theme.TEXT else Theme.MUTED, if (on) Theme.ACCENT_DARK else Theme.FIELD)
                    if (furniture.size > 1) ui.hit(fx, ry - 2, cw, 12, "Place as $f") { choice = i }
                    fx += cw + 4
                }
                ry += 16
            }
        }
        ry += 6
        val placeable = Bridge.connected
        val why = "Placing needs the Foreman plugin on the server"
        ui.button(rx, ry, 70, 18, "Place", Style.PRIMARY, placeable,
            if (placeable) "A ghost follows where you look: scroll turns it, Alt + scroll scales it, right click or Enter places it as a display" else why) {
            ModelPlacementTool.start(id)
            close()
        }
        var bx = rx + 76
        if (furniture.isNotEmpty()) {
            ui.button(bx, ry, 110, 18, "Place as furniture", enabled = placeable, tooltip = "Places ${furniture[choice]}: the server's furniture, with its collisions") {
                ModelPlacementTool.start(id, furniture[choice])
                close()
            }
            bx += 116
        }
        val copied = System.currentTimeMillis() - copiedAt < 2000
        ui.button(bx, ry, 60, 18, if (copied) "Copied" else "Copy id", Style.GHOST, tooltip = id.toString()) {
            Minecraft.getInstance().keyboardHandler.clipboard = id.toString()
            copiedAt = System.currentTimeMillis()
        }
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun turn(dYaw: Float, dPitch: Float) {
        yaw = (yaw + dYaw).mod(360f)
        pitch = (pitch + dPitch).coerceIn(-90f, 90f)
    }

    /** A drag in the hub, in its units: turns the model opened while its preview is held. */
    fun drag(mouseX: Double, mouseY: Double): Boolean {
        val from = dragFrom ?: return false
        if (from.first.isNaN()) {
            dragFrom = mouseX to mouseY
            return true
        }
        yaw = (dragAngles.first + ((mouseX - from.first) * dragRate).toFloat()).mod(360f)
        pitch = (dragAngles.second + ((mouseY - from.second) * dragRate).toFloat()).coerceIn(-90f, 90f)
        return true
    }

    fun release() {
        dragFrom = null
    }

    /** The arrow keys turn the model opened, Esc goes back to the list. */
    fun keyPressed(event: KeyEvent): Boolean {
        if (!detailOpen) return false
        when {
            event.isEscape -> back()
            event.key() == InputConstants.KEY_LEFT -> turn(-15f, 0f)
            event.key() == InputConstants.KEY_RIGHT -> turn(15f, 0f)
            event.key() == InputConstants.KEY_UP -> turn(0f, -15f)
            event.key() == InputConstants.KEY_DOWN -> turn(0f, 15f)
            else -> return false
        }
        return true
    }
}
