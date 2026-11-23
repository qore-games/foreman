package games.qore.foreman.client.entity

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Interaction
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.decoration.painting.Painting
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.min

/**
 * The entity editor's panel, beside the world: tabs for the primary selection (the last selected entity), by its type
 * (a display's Transform, Display and Text, a mob's, an interaction's, an armor stand's, an item frame's, a dropped
 * item's or a painting's own values; every entity's Entity, Tags, persistent Data and NBT), then a strip of the entities
 * nearby, around the player or the selection, to pick the hidden ones: the nearest few, or a longer list that scrolls.
 * A change goes to every selected entity the tab is for (the NBT tab's to the primary only), one undo step each.
 */
class EntityPanel : KitScreen("Entities") {
    enum class Tab(val label: String) {
        TRANSFORM("Transform"), DISPLAY("Display"), TEXT("Text"), MOB("Mob"), INTERACTION("Interaction"), ARMOR_STAND("Armor stand"),
        FRAME("Frame"), ITEM("Item"), PAINTING("Painting"), ENTITY("Entity"), TAGS("Tags"), DATA("Data"), NBT("NBT"),
    }

    private val panelW get() = if (built?.first == Tab.NBT) min(500, width * 3 / 5) else min(340, width / 2)
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20
    private val contentY get() = py + 66
    /** Where the strip of entities nearby starts (the tab above it, the bottom line under it), and its height. */
    private val stripY get() = py + ph - 20 - stripH
    private val stripH get() = when {
        !nearbyShown -> 16
        nearbyMore -> maxOf(ph * 2 / 5, 16 + NEARBY * ROW)
        else -> 16 + NEARBY * ROW
    }

    /** The entities nearby the strip shows, nearest first ([LIMIT] at most within [RADIUS]), how many are within it, and what and when it was taken. */
    private var listed: List<Entity> = emptyList()
    private var within = 0
    private var listedFor: Any? = null
    private var listedAt = 0L

    /** The tab shown, and the entity type it was built for (its widgets differ by type). */
    private var built: Pair<Tab, Class<*>?>? = null
    private var content: PanelTab? = null
    private var message = ""
    private var error = false

    override val scrim get() = 0

    /** The primary selection: the panel's values are its. */
    val primary: Entity? get() = EntityEditor.selectedEntities().lastOrNull()

    override fun build() {
        val wanted = wanted()
        built = wanted
        say("")
        content = wanted?.let { (tab, _) -> tab(tab) }?.also { it.build(px + 10, contentY, panelW - 20, stripY - 6 - contentY) }
    }

    private fun tab(tab: Tab): PanelTab = when (tab) {
        Tab.TRANSFORM -> TransformTab(this)
        Tab.DISPLAY -> DisplayTab(this)
        Tab.TEXT -> TextTab(this)
        Tab.MOB -> MobTab(this)
        Tab.INTERACTION -> InteractionTab(this)
        Tab.ARMOR_STAND -> ArmorStandTab(this)
        Tab.FRAME -> FrameTab(this)
        Tab.ITEM -> ItemTab(this)
        Tab.PAINTING -> PaintingTab(this)
        Tab.ENTITY -> EntityTab(this)
        Tab.TAGS -> TagsTab(this)
        Tab.DATA -> DataTab(this)
        Tab.NBT -> NbtTab(this)
    }

    /** The tab to show for the primary: the last one chosen when it has it, else its first. */
    private fun wanted(): Pair<Tab, Class<*>?>? {
        val e = primary ?: return null
        val tabs = tabs(e)
        return (if (last in tabs) last else tabs.first()) to e.javaClass
    }

    override fun tick() {
        // The selection changed (another type, nothing): its tab's widgets.
        if (wanted() != built) rebuildWidgets()
    }

    /** A widget of the current tab. */
    fun <T> add(widget: T): T where T : GuiEventListener, T : Renderable, T : NarratableEntry = addRenderableWidget(widget)

    /** A text field of the current tab, [hint] shown while empty. */
    fun box(x: Int, y: Int, w: Int, hint: String, max: Int = 256): EditBox = add(EditBox(font, x, y, w, 18, Component.literal(hint)).also {
        it.setMaxLength(max)
        it.setHint(Component.literal(hint).withColor(Theme.FAINT))
    })

    /** Shows [text] at the bottom of the panel (on one line); [error] in red. */
    fun say(text: String, error: Boolean = false) {
        message = text.replace('\n', ' ')
        this.error = error
    }

    /** The server's undo of the player's last change; the panel's line forgets what it said. */
    fun undo() {
        say("")
        EntityEditor.undo()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val tab = content
        if (event.isConfirmation && tab != null && (!tab.keepsEnter || event.hasControlDown())) {
            tab.apply()
            return true
        }
        return super.keyPressed(event)
    }

    override fun draw(ui: Ui) {
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.title("Entities", px + 10, py + 10)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        val entities = EntityEditor.selectedEntities()
        val head = when {
            !EntityEditor.enabled -> "The editor is off: turn it on to pick entities"
            entities.isEmpty() -> "Nothing selected: click one in the world or below"
            entities.size == 1 -> EntityEditor.label(entities[0]) + " · " + EntityEditor.kind(entities[0])
            else -> "${entities.size} selected · values of ${EntityEditor.label(entities.last())}"
        }
        ui.text(ui.fit(head, panelW - 20), px + 10, py + 30, if (entities.isEmpty()) Theme.FAINT else Theme.MUTED)
        val e = primary
        val tab = content
        if (e != null && tab != null) {
            tabBar(ui, tabs(e), py + 44)
            tab.draw(ui, px + 10, contentY, panelW - 20, stripY - 6 - contentY)
        }
        nearby(ui, stripY)
        val hint = tab?.hint ?: "Esc closes · each change is one undo step"
        ui.text(ui.fit(message.ifEmpty { hint }, panelW - 20), px + 10, py + ph - 14, if (message.isEmpty()) Theme.FAINT else if (error) Theme.ERROR else Theme.OK)
    }

    /** The tabs of the primary's type, the one shown underlined; as tight as it takes to fit the panel. */
    private fun tabBar(ui: Ui, tabs: List<Tab>, y: Int) {
        val labels = tabs.sumOf { ui.font.width(it.label) }
        val pad = ((panelW - 20 - labels - tabs.size) / tabs.size).coerceIn(6, 16)
        var x = px + 10
        for (t in tabs) {
            val w = ui.font.width(t.label) + pad
            val on = t == built?.first
            ui.rect(x, y, w, 18, if (on) Theme.ACCENT_DARK else if (ui.hovered(x, y, w, 18)) Theme.PANEL_HOVER else Theme.FIELD)
            if (on) ui.rect(x, y + 16, w, 2, Theme.ACCENT)
            ui.text(t.label, x + pad / 2, y + 5, if (on) Theme.TEXT else Theme.MUTED)
            ui.hit(x, y, w, 18, null) {
                last = t
                rebuildWidgets()
            }
            x += w + 1
        }
    }

    /**
     * The strip of entities nearby: its header (how many, around the player or the selection, more of them, shown or
     * hidden), then the [NEARBY] nearest, or up to [LIMIT] in a list that scrolls. A click selects one, Shift+click adds
     * or removes it.
     */
    private fun nearby(ui: Ui, top: Int) {
        val player = minecraft.player ?: return
        val center = if (aroundSelection) primary else null
        val from = center?.position() ?: player.position()
        val list = nearest(center, from)
        val shown = if (nearbyMore) list.size else minOf(NEARBY, list.size)
        ui.rect(px + 6, top - 3, panelW - 12, 1, Theme.BORDER)
        ui.text("Nearby (" + (if (within > shown) "$shown of $within" else "$within") + ")", px + 10, top + 4, Theme.ACCENT)
        var bx = px + panelW - 10
        bx = headerButton(ui, bx, top, if (nearbyShown) "Hide" else "Show", if (nearbyShown) "Hide the entities nearby" else "Show the entities nearby") {
            nearbyShown = !nearbyShown
        }
        if (nearbyShown) bx = headerButton(ui, bx, top, if (nearbyMore) "Less" else "More", if (nearbyMore) "The $NEARBY nearest only" else "Up to $LIMIT within ${RADIUS.toInt()} blocks, in a list that scrolls") {
            nearbyMore = !nearbyMore
        }
        val modes = listOf("Me", "Selection")
        var mx = bx - 4 - modes.sumOf { ui.font.width(it) + 11 }
        modes.forEachIndexed { i, label ->
            val w = ui.font.width(label) + 10
            val on = aroundSelection == (i == 1)
            ui.rect(mx, top, w, 14, if (on) Theme.ACCENT_DARK else if (ui.hovered(mx, top, w, 14)) Theme.PANEL_HOVER else Theme.FIELD)
            ui.text(label, mx + 5, top + 3, if (on) Theme.TEXT else Theme.MUTED)
            ui.hit(mx, top, w, 14, if (i == 0) "Nearest to you" else "Nearest to the selected entity") { aroundSelection = i == 1 }
            mx += w + 1
        }
        if (!nearbyShown) return
        val selected = EntityEditor.selected.toSet()
        if (list.isEmpty()) return ui.text("No entity within ${RADIUS.toInt()} blocks", px + 10, top + 19, Theme.FAINT)
        if (!nearbyMore) return list.take(NEARBY).forEachIndexed { i, e -> row(ui, e, top + 16 + i * ROW, e.uuid in selected, e.position().distanceTo(from)) }
        ui.scroll("nearby", px + 6, top + 16, panelW - 12, stripH - 16) { y0 ->
            list.forEachIndexed { i, e -> row(ui, e, y0 + i * ROW, e.uuid in selected, e.position().distanceTo(from)) }
            list.size * ROW
        }
    }

    /** A small button of the strip's header ending at [right]; returns where the next one ends. */
    private fun headerButton(ui: Ui, right: Int, top: Int, label: String, tooltip: String, onClick: () -> Unit): Int {
        val w = ui.font.width(label) + 12
        ui.button(right - w, top, w, 14, label, Style.GHOST, tooltip = tooltip) {
            onClick()
            rebuildWidgets()
        }
        return right - w - 2
    }

    /**
     * The entities nearest to [from] (not [center] itself), [LIMIT] at most within [RADIUS]: taken again each second, or
     * when the center changes, so the strip costs next to nothing and its rows don't shuffle under the cursor.
     */
    private fun nearest(center: Entity?, from: Vec3): List<Entity> {
        val key = aroundSelection to center?.uuid
        val now = System.currentTimeMillis()
        if (key == listedFor && now - listedAt < LIST_MS) return listed
        val inside = EntityEditor.nearby.values.filter { it !== center && it.distanceToSqr(from) <= RADIUS * RADIUS }
        within = inside.size
        listed = inside.sortedBy { it.distanceToSqr(from) }.take(LIMIT)
        listedFor = key
        listedAt = now
        return listed
    }

    private fun row(ui: Ui, e: Entity, y: Int, selected: Boolean, distance: Double) {
        val x = px + 6
        val w = panelW - 12
        if (selected || ui.hovered(x, y, w, ROW - 1)) ui.rect(x, y, w, ROW - 1, if (selected) Theme.ACCENT_DARK else Theme.PANEL_HOVER)
        val chip = ui.chip(x + 4, y + 1, EntityEditor.kind(e))
        val dist = "%.1f".format(Locale.ROOT, distance)
        ui.text(ui.fit(EntityEditor.label(e), w - chip - 14 - ui.font.width(dist)), x + chip + 8, y + 3, Theme.TEXT)
        ui.text(dist, x + w - 4 - ui.font.width(dist), y + 3, Theme.MUTED)
        ui.hit(x, y, w, ROW - 1, null) {
            if (Mc.isKeyDown(InputConstants.KEY_LSHIFT)) EntityEditor.toggleSelected(e.uuid) else EntityEditor.selectOnly(e.uuid)
        }
    }

    companion object {
        /** How many entities the strip shows, how many its longer list at most, within how far, and a row's height. */
        private const val NEARBY = 5
        private const val LIMIT = 50
        private const val RADIUS = 32.0
        private const val ROW = 14
        private const val LIST_MS = 1000L

        /** The tab last chosen: the panel opens on it when the selection has it. */
        private var last = Tab.TRANSFORM
        /** The strip of entities nearby: shown, as the longer list, and around the selection instead of the player. */
        private var nearbyShown = true
        private var nearbyMore = false
        private var aroundSelection = false

        /** The tabs of [e]'s type: its own first, then those of every entity. */
        fun tabs(e: Entity): List<Tab> = when (e) {
            is Display.TextDisplay -> listOf(Tab.TRANSFORM, Tab.DISPLAY, Tab.TEXT)
            is Display -> listOf(Tab.TRANSFORM, Tab.DISPLAY)
            is Interaction -> listOf(Tab.INTERACTION)
            is ArmorStand -> listOf(Tab.ARMOR_STAND)
            is LivingEntity -> listOf(Tab.MOB)
            is ItemFrame -> listOf(Tab.FRAME)
            is ItemEntity -> listOf(Tab.ITEM)
            is Painting -> listOf(Tab.PAINTING)
            else -> emptyList()
        } + listOf(Tab.ENTITY, Tab.TAGS, Tab.DATA, Tab.NBT)
    }
}

/** A tab of the entity panel: its widgets, what it draws each frame, and what Enter applies. */
abstract class PanelTab(protected val panel: EntityPanel) {
    /** The panel's bottom line while it says nothing else. */
    open val hint: String = "Enter applies · each change is one undo step"

    /** Whether plain Enter goes to its widget (a multi-line box): Ctrl+Enter applies then. */
    open val keepsEnter = false

    /** Adds its widgets at [x], [y], in [w] by at most [h]. */
    open fun build(x: Int, y: Int, w: Int, h: Int) {}

    /** Draws it at [x], [y], [w] wide, at most [h] high; returns the height it took. */
    abstract fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int): Int

    /** Enter: applies what was typed. */
    open fun apply() {}
}
