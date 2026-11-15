package games.qore.foreman.client.panels

import games.qore.foreman.client.objects.ObjectsTool
import games.qore.foreman.client.regions.PickList
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Popover
import games.qore.foreman.client.ui.kit.SidePanel
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent

/**
 * The panel a plugin of the server shows for what the builder aimed at ([Panels.shown]), beside the world: its title,
 * its status line and tabs, then its [rows][PanelRows], kept live as the server pushes it. A world object's shows in its own panel, under its form
 * ([games.qore.foreman.client.objects.ObjectPanel]). Its fields are sent as they are left, its buttons run
 * at once; the status line says how it went. Closing it tells the server (nothing is described for it any more).
 */
class PanelScreen : KitScreen("Panel"), PanelRows.Host {
    private val panelW get() = SidePanel.width(width)
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20
    private val bodyTop get() = py + 44 + rows.headerHeight(Panels.shown?.panel)
    private val bodyBottom get() = py + ph - FOOTER

    private val rows = PanelRows(this)
    private var pop: Popover? = null
    private var message = "" to Theme.FAINT
    /** Closed while the builder picks a target in the world: the panel stays open on the server. */
    private var away = false
    /** Closed for a world object's panel, which shows the plugin's panel of its own. */
    private var leaving = false

    override val scrim get() = 0

    override val modal get() = pop != null

    override val popoverOpen get() = pop != null

    override fun widgetFont(): Font = font

    override fun add(box: EditBox): EditBox = addRenderableWidget(box)

    override fun remove(box: EditBox) = removeWidget(box)

    override fun focused(box: EditBox) = box.isFocused

    override fun open(popover: Popover) {
        resetScroll("pick")
        pop = popover
    }

    override fun pickTarget(what: String, done: (JsonObject?) -> Unit) {
        away = true
        Inspector.pickTarget(what) { target ->
            away = false
            Screens.open(this)
            done(target)
        }
        minecraft.gui.setScreen(null)
    }

    /** A world object's opens with its form ([Inspector.open]); anything else here, in place. */
    override fun openTarget(target: JsonObject, name: String) {
        if (ObjectsTool.objectOf(target) == null) return Panels.open(target, name)
        leaving = true
        Inspector.open(target, name)
    }

    override fun say(text: String, color: Int) {
        message = text to color
    }

    override fun build() = rows.build()

    override fun keyPressed(event: KeyEvent): Boolean {
        if (pop != null) {
            if (event.isEscape) pop = null
            return true
        }
        if (event.isConfirmation && rows.enter()) return true
        return super.keyPressed(event)
    }

    /** A click beside the box being typed in leaves it (and sends it). */
    override fun mouseClicked(real: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val box = focused as? EditBox
        if (box != null && !box.isMouseOver(real.x() / zoom, real.y() / zoom)) focused = null
        return super.mouseClicked(real, doubleClick)
    }

    override fun removed() {
        if (away) return
        rows.flush()
        if (!leaving) Panels.close()
    }

    override fun draw(ui: Ui) {
        val state = Panels.shown
        rows.tabKey = state?.provider
        rows.sync(state)
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.scroll("body", px + 1, bodyTop, panelW - 2, bodyBottom - bodyTop) { top -> body(ui, top, state) }
        val title = state?.panel?.title?.ifEmpty { null } ?: state?.name ?: "Panel"
        val sub = listOfNotNull(state?.panel?.subtitle?.ifEmpty { null } ?: state?.name?.takeIf { it != title }, state?.providerLabel ?: state?.provider).joinToString(" · ")
        val (text, color) = message
        // The header and the status line cover what scrolled under them; their text cut short shows whole on hover.
        val cut = ui.font.width(title) > (panelW - 44) / 1.5 || ui.font.width(sub) > panelW - 20
        ui.hit(px, py, panelW, bodyTop - py, if (cut) listOf(title, sub).filter { it.isNotEmpty() }.joinToString("\n") else null) {}
        ui.hit(px, bodyBottom, panelW, py + ph - bodyBottom, text.takeIf { ui.font.width(it) > panelW - 20 }) {}
        if (scrolled("body")) ui.rect(px + 10, bodyTop - 1, panelW - 20, 1, Theme.BORDER)
        ui.title(ui.fit(title, ((panelW - 44) / 1.5).toInt()), px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        ui.text(ui.fit(sub, panelW - 20), px + 10, py + 30, Theme.MUTED)
        rows.header(ui, state?.panel, px + 10, py + 42, panelW - 20)
        ui.text(ui.fit(text.ifEmpty { "Fields are sent as you leave them · Esc closes" }, panelW - 20), px + 10, py + ph - 14, if (text.isEmpty()) Theme.FAINT else color)
    }

    private fun body(ui: Ui, top: Int, state: PanelState?): Int {
        val x = px + 10
        val w = panelW - 20
        val y = top + 2
        val panel = state?.panel
        val h = when {
            state == null -> ui.paragraph("No panel is open.", x, y, w, Theme.MUTED)
            !state.answered -> ui.paragraph("Asking the server's plugins…", x, y, w, Theme.MUTED)
            state.error != null && panel == null -> ui.paragraph(state.error, x, y, w, Theme.ERROR)
            state.target == null || panel == null -> {
                val first = ui.paragraph("No plugin of this server has a panel for ${state.name}.", x, y, w, Theme.MUTED) + 6
                first + ui.paragraph("Plugins give panels to what they manage: aim at one of those and press N again.", x, y + first, w, Theme.FAINT)
            }
            else -> {
                val error = state.error?.let { ui.paragraph(it, x, y, w, Theme.ERROR) + 6 } ?: 0
                error + rows.draw(ui, panel, x, y + error, w, bodyTop, bodyBottom)
            }
        }
        return h + 8
    }

    override fun drawOverlay(ui: Ui) {
        pop?.draw(ui, width, height) { pop = null }
    }

    companion object {
        private const val FOOTER = 24

        /** Opens the panel of [aimed]. */
        fun open(aimed: Aimed) {
            Panels.open(aimed.target, aimed.name)
            Screens.open(PanelScreen())
        }
    }
}

/** The plugin's [question] when a link needs a choice: its [options] to pick one of, [onPick] with its id. */
class ChoiceScreen(private val question: String, private val options: List<LinkOption>, private val onPick: (String) -> Unit) : KitScreen("Link") {
    private var list: PickList? = null

    override val scrim get() = 0

    override val modal get() = true

    override fun draw(ui: Ui) {}

    override fun drawOverlay(ui: Ui) {
        val list = list ?: run {
            // Wide enough for the question and each option with its hint, within the screen.
            val w = (options.map { ui.font.width(it.label) + ui.font.width(it.hint) + 32 } + (ui.font.width(question) + 14)).max().coerceIn(240, width - 40)
            val rows = listOf(PickList.Option(null, question)) + options.map { PickList.Option(it.id, it.label, it.hint) }
            PickList(width / 2 - w / 2, height / 3, w, rows, onPick).also { this.list = it }
        }
        list.draw(ui, width, height) { onClose() }
    }
}
