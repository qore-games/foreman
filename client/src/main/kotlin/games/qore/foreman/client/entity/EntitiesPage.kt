package games.qore.foreman.client.entity

import games.qore.foreman.client.editor.EditorMode
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui

/** The hub's World → Entities page: the entity editor's card, its selection, and its keys. */
object EntitiesPage {
    fun draw(ui: Ui, x: Int, y0: Int, w: Int, h: Int, close: () -> Unit) {
        val on = EntityEditor.enabled
        ui.card(x, y0, w, 46, if (on) Theme.PANEL_HOVER else Theme.PANEL, if (on) Theme.ACCENT else Theme.BORDER)
        ui.text("Entity editor", x + 10, y0 + 8, Theme.TEXT)
        EditorMode.caption(EntityEditor).takeIf { it.isNotBlank() }?.let { ui.chip(x + 14 + ui.font.width("Entity editor"), y0 + 6, it, Theme.ACCENT) }
        ui.paragraph("Pick any entity (displays and furniture by what they draw), move, turn and scale displays with a gizmo, edit tags, persistent data and NBT. The server saves each change.", x + 10, y0 + 22, w - 100)
        ui.button(x + w - 80, y0 + 14, 70, 18, if (on) "Turn off" else "Use", if (on) Style.NORMAL else Style.PRIMARY) {
            EntityEditor.toggle()
            if (!on) close()
        }
        var y = y0 + 58
        val selected = EntityEditor.selected
        ui.card(x, y, w, 40)
        ui.text("Selection", x + 10, y + 8, Theme.TEXT)
        val shown = EntityEditor.selectedEntities()
        val what = when {
            selected.isEmpty() -> "none: click entities with the editor on"
            shown.isEmpty() -> "${selected.size} selected (in view when the editor is on)"
            shown.size == 1 -> EntityEditor.label(shown[0]) + " · " + EntityEditor.kind(shown[0])
            else -> "${shown.size} entities, the last ${EntityEditor.label(shown.last())}"
        }
        ui.text(ui.fit(what, w - 190), x + 10, y + 24, if (selected.isEmpty()) Theme.FAINT else Theme.MUTED)
        ui.button(x + w - 170, y + 12, 90, 16, "Values…", tooltip = "The panel beside the world: exact values, tags, data and NBT, entities nearby (${EntityEditor.keyName("display-values")})") {
            if (!EntityEditor.enabled) Tools.toggle(EntityEditor)
            Screens.open(EntityPanel())
        }
        ui.button(x + w - 74, y + 12, 64, 16, "Clear", Style.GHOST, enabled = selected.isNotEmpty()) { EntityEditor.clearSelection() }
        y += 52
        if (!Bridge.connected) {
            ui.paragraph(Bridge.unavailable("the editor shows changes but can't save them") + ". Its display and entity tools (edit_displays, edit_entities…) save them.", x, y, w, Theme.WARN)
            y += 26
        }
        ui.text("In editor mode", x, y, Theme.ACCENT)
        y += 14
        val rows = listOf(
            "Left click" to "select the entity under the crosshair (a display by what it draws); on nothing: deselect",
            "Shift + left click" to "add it to the selection, or take it out (Shift or the sneak key)",
            "Right click" to "the next one along the crosshair: what hides behind",
            "Drag a handle" to "displays: move (arrows, planes), rotate (rings), scale (axes, center knob)",
            "Ctrl" to "while dragging: no snapping (1/16 block, 15°, scale steps of 1/16)",
            "Scroll" to "while dragging: one more step",
        ) + EntityEditor.keys().map { it.caption to it.label }
        for ((key, does) in rows) {
            if (y > y0 + h - 12) break
            ui.chip(x, y, key, Theme.ACCENT)
            ui.text(ui.fit(does, w - 120), x + 112, y + 2, Theme.MUTED)
            y += 15
        }
    }
}
