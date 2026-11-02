package games.qore.foreman.client.ui.kit

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvents

/** Foreman's look: dark panels, one accent, readable text. */
object Theme {
    const val SCRIM = 0xD80B0D12.toInt()
    const val RAIL = 0xFF12151C.toInt()
    const val PANEL = 0xFF191D26.toInt()
    const val PANEL_HOVER = 0xFF222836.toInt()
    const val FIELD = 0xFF10131A.toInt()
    const val BORDER = 0xFF2A3040.toInt()
    const val ACCENT = 0xFF4FB3FF.toInt()
    const val ACCENT_DARK = 0xFF1E5A85.toInt()
    const val TEXT = 0xFFE6E9EF.toInt()
    const val MUTED = 0xFF8A93A6.toInt()
    const val FAINT = 0xFF5A6273.toInt()
    const val OK = 0xFF4ADE80.toInt()
    const val WARN = 0xFFFBBF24.toInt()
    const val ERROR = 0xFFF87171.toInt()
    const val AI = 0xFFC084FC.toInt()
}

enum class Style { NORMAL, PRIMARY, DANGER, GHOST }

/** A clickable area registered while drawing; the last one under the mouse gets the click. */
class Hit(val x: Int, val y: Int, val w: Int, val h: Int, val tooltip: String?, val onClick: (() -> Unit)?, val blocks: Boolean = false) {
    fun contains(mx: Double, my: Double) = mx >= x && mx < x + w && my >= y && my < y + h
}

/** A scrollable area: its offset survives frames (keyed by [id]). */
class ScrollArea(val id: String, val x: Int, val y: Int, val w: Int, val h: Int, var content: Int = 0) {
    var offset = 0
    val max get() = (content - h).coerceAtLeast(0)
    /** The scrollbar's thumb: its height and top. */
    val bar get() = (h * h / content.coerceAtLeast(1).toFloat()).toInt().coerceIn(12, h)
    val barY get() = y + if (max == 0) 0 else ((h - bar) * offset / max.toFloat()).toInt()

    /** Over the scrollbar's strip, where a click grabs the thumb or jumps to it. */
    fun inGutter(mx: Double, my: Double) = max > 0 && mx >= x + w - GUTTER && mx < x + w && my >= y && my < y + h

    /** Scrolls so the thumb's top is at [top]. */
    fun dragTo(top: Double) {
        offset = ((top - y) / (h - bar).coerceAtLeast(1) * max).toInt().coerceIn(0, max)
    }

    companion object {
        const val GUTTER = 6
    }
}

/**
 * Immediate-mode drawing: each frame the screen draws its widgets through this, which also records where clicks and
 * tooltips go. No widget objects to keep in sync with the state: the state is read as it is drawn.
 */
class Ui(
    val g: GuiGraphicsExtractor, val font: Font, val mx: Int, val my: Int, private val hits: MutableList<Hit>,
    private val scrolls: MutableMap<String, ScrollArea>,
    /** The scroll areas drawn this frame, bottom to top (the wheel goes to the topmost under the cursor). */
    private val drawn: MutableList<ScrollArea> = mutableListOf(),
    /** The scroll area whose scrollbar is being dragged. */
    private val held: String? = null,
) {
    fun hovered(x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    fun rect(x: Int, y: Int, w: Int, h: Int, color: Int) = g.fill(x, y, x + w, y + h, color)

    /** A panel with a 1px border. */
    fun card(x: Int, y: Int, w: Int, h: Int, color: Int = Theme.PANEL, border: Int = Theme.BORDER) {
        rect(x, y, w, h, border)
        rect(x + 1, y + 1, w - 2, h - 2, color)
    }

    fun text(text: String, x: Int, y: Int, color: Int = Theme.TEXT) = g.text(font, text, x, y, color, false)

    /** [text] cut with "…" to fit [w]. */
    fun fit(text: String, w: Int): String = if (font.width(text) <= w) text else font.plainSubstrByWidth(text, (w - font.width("…")).coerceAtLeast(0)) + "…"

    fun title(text: String, x: Int, y: Int) {
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), y.toFloat())
        g.pose().scale(1.5f, 1.5f)
        g.text(font, text, 0, 0, Theme.TEXT, false)
        g.pose().popMatrix()
    }

    /** Wrapped text, its `code` spans in [code] when given; returns its height. */
    fun paragraph(text: String, x: Int, y: Int, w: Int, color: Int = Theme.MUTED, code: Int? = null): Int {
        val lines = font.split(if (code == null) Component.literal(text) else withCode(text, code), w)
        lines.forEachIndexed { i, l -> g.text(font, l, x, y + i * 10, color, false) }
        return lines.size * 10
    }

    companion object {
        /** [text] with its backquoted spans in [code], without the backquotes. */
        fun withCode(text: String, code: Int): Component = Component.empty().apply {
            text.split('`').forEachIndexed { i, part -> append(if (i % 2 == 1) Component.literal(part).withColor(code) else Component.literal(part)) }
        }
    }

    fun hit(x: Int, y: Int, w: Int, h: Int, tooltip: String? = null, onClick: (() -> Unit)? = null) {
        hits += Hit(x, y, w, h, tooltip, onClick)
    }

    fun button(x: Int, y: Int, w: Int, h: Int, label: String, style: Style = Style.NORMAL, enabled: Boolean = true, tooltip: String? = null, onClick: () -> Unit) {
        val hover = enabled && hovered(x, y, w, h)
        val (bg, fg) = when (style) {
            Style.PRIMARY -> (if (hover) Theme.ACCENT else 0xFF2E86C8.toInt()) to 0xFF0B0D12.toInt()
            Style.DANGER -> (if (hover) 0xFF7F1D1D.toInt() else 0xFF3A1A1E.toInt()) to Theme.ERROR
            Style.GHOST -> (if (hover) Theme.PANEL_HOVER else 0) to (if (hover) Theme.TEXT else Theme.MUTED)
            Style.NORMAL -> (if (hover) Theme.PANEL_HOVER else 0xFF1F2430.toInt()) to Theme.TEXT
        }
        if (bg != 0) card(x, y, w, h, bg, if (style == Style.GHOST) bg else if (hover) Theme.ACCENT_DARK else Theme.BORDER)
        val t = fit(label, w - 8)
        g.text(font, t, x + (w - font.width(t)) / 2, y + (h - 8) / 2, if (enabled) fg else Theme.FAINT, false)
        hit(x, y, w, h, tooltip, if (enabled) onClick else null)
    }

    /** An on/off switch with its label on the left; returns its height. */
    fun switch(x: Int, y: Int, w: Int, label: String, on: Boolean, tooltip: String? = null, onToggle: () -> Unit): Int {
        val hover = hovered(x, y, w, 16)
        if (hover) rect(x - 4, y - 2, w + 8, 20, Theme.PANEL_HOVER)
        text(fit(label, w - 34), x, y + 4, Theme.TEXT)
        val sx = x + w - 24
        card(sx, y + 2, 24, 12, if (on) Theme.ACCENT_DARK else Theme.FIELD, if (on) Theme.ACCENT else Theme.BORDER)
        rect(if (on) sx + 13 else sx + 2, y + 4, 9, 8, if (on) Theme.ACCENT else Theme.MUTED)
        hit(x - 4, y - 2, w + 8, 20, tooltip, onToggle)
        return 20
    }

    /** One choice among [options], as joined buttons; returns its width. */
    fun segmented(x: Int, y: Int, options: List<String>, selected: Int, colors: List<Int>? = null, onSelect: (Int) -> Unit): Int {
        var cx = x
        options.forEachIndexed { i, o ->
            val w = font.width(o) + 14
            val on = i == selected
            val hover = hovered(cx, y, w, 16)
            val accent = colors?.getOrNull(i) ?: Theme.ACCENT
            rect(cx, y, w, 16, if (on) Theme.ACCENT_DARK else if (hover) Theme.PANEL_HOVER else Theme.FIELD)
            if (on) rect(cx, y + 14, w, 2, accent)
            text(o, cx + 7, y + 4, if (on) Theme.TEXT else Theme.MUTED)
            hit(cx, y, w, 16, null) { onSelect(i) }
            cx += w + 1
        }
        return cx - x
    }

    /** A small rounded label (tags, key caps); returns its width. */
    fun chip(x: Int, y: Int, label: String, color: Int = Theme.MUTED, bg: Int = Theme.FIELD): Int {
        val w = font.width(label) + 8
        card(x, y, w, 12, bg, Theme.BORDER)
        text(label, x + 4, y + 2, color)
        return w
    }

    /**
     * The backdrop of a pop-over: covers the screen, so what is under it gets no click or tooltip; clicking it runs
     * [onClick] (close the pop-over). Draw the pop-over's own hits after it.
     */
    fun backdrop(w: Int, h: Int, onClick: () -> Unit) {
        hits += Hit(0, 0, w, h, null, onClick, blocks = true)
    }

    /** The whole of a texture ([texW] x [texH] pixels) drawn into [w] x [h]. */
    fun image(texture: Identifier, x: Int, y: Int, w: Int, h: Int, texW: Int, texH: Int) =
        g.blit(RenderPipelines.GUI_TEXTURED, texture, x, y, 0f, 0f, w, h, texW, texH, texW, texH)

    fun swatch(x: Int, y: Int, size: Int, argb: Int) {
        rect(x, y, size, size, Theme.BORDER)
        rect(x + 1, y + 1, size - 2, size - 2, argb or 0xFF000000.toInt())
    }

    /**
     * A scrolling area: [content] draws at (x, y - offset) and returns its height. Clipped to the area, with a thin
     * scrollbar when it doesn't fit; the wheel scrolls it, and the scrollbar can be dragged or clicked.
     */
    fun scroll(id: String, x: Int, y: Int, w: Int, h: Int, stickToBottom: Boolean = false, content: (top: Int) -> Int) {
        val area = scrolls.getOrPut(id) { ScrollArea(id, x, y, w, h) }
        // Kept at the bottom while it was there (a transcript growing): a scroll up stops following.
        val follow = stickToBottom && area.offset >= area.max
        val current = ScrollArea(id, x, y, w, h, area.content).also { it.offset = if (follow) it.max else area.offset.coerceIn(0, it.max) }
        scrolls[id] = current
        drawn += current
        g.enableScissor(x, y, x + w, y + h)
        current.content = content(y - current.offset)
        g.disableScissor()
        current.offset = if (follow) current.max else current.offset.coerceIn(0, current.max)
        if (current.max > 0) {
            if (id == held || current.inGutter(mx.toDouble(), my.toDouble())) rect(x + w - 4, current.barY, 3, current.bar, Theme.MUTED)
            else rect(x + w - 3, current.barY, 2, current.bar, Theme.BORDER)
        }
    }
}

/** A screen drawn with [Ui]: hits and scroll areas are rebuilt every frame; EditBoxes are regular widgets. */
abstract class KitScreen(title: String) : Screen(Component.literal(title)) {
    private val hits = mutableListOf<Hit>()
    private val scrolls = HashMap<String, ScrollArea>()
    /** The scroll areas drawn last frame, bottom to top: a closed pop-over's area is kept in [scrolls] but not here. */
    private val drawnScrolls = mutableListOf<ScrollArea>()
    /** Index in [drawnScrolls] where the overlay's areas start (only those scroll while [modal]). */
    private var overlayScrolls = 0
    /** The scroll area whose scrollbar is held, and where the thumb was grabbed (from its top). */
    private var held: String? = null
    private var grab = 0.0

    /**
     * The screen's size against the game's GUI: below 1 on a small GUI (the default window, the automatic scale on a
     * 1080p screen), where it draws at a smaller whole GUI scale so its layout gets at least [MIN_WIDTH]×[MIN_HEIGHT]
     * and its text stays sharp. [width] and [height] are in its own units; mouse events are turned into them.
     */
    var zoom = 1f
        private set

    /** Draws the screen (called every frame, before the regular widgets). */
    protected abstract fun draw(ui: Ui)

    /** Adds the regular widgets (EditBoxes) for the current size, in the screen's own units. */
    protected open fun build() {}

    final override fun init() {
        val window = minecraft.window
        var scale = window.guiScale
        while (scale > 1 && (window.width / scale < MIN_WIDTH || window.height / scale < MIN_HEIGHT)) scale--
        zoom = scale.toFloat() / window.guiScale
        width = (window.guiScaledWidth / zoom).toInt()
        height = (window.guiScaledHeight / zoom).toInt()
        build()
    }

    private fun MouseButtonEvent.scaled() = if (zoom == 1f) this else MouseButtonEvent(x() / zoom, y() / zoom, buttonInfo())

    /** Drawn over everything, widgets included (pop-overs): start it with [Ui.backdrop]. */
    protected open fun drawOverlay(ui: Ui) {}

    /** While true (a pop-over is open) the screen under the overlay is drawn without hover and widgets get no click. */
    protected open val modal: Boolean get() = false

    /** Forgets a scroll area's position (another list shown in it). */
    protected fun resetScroll(id: String) {
        scrolls.remove(id)
    }

    override fun extractRenderState(graphics: GuiGraphicsExtractor, realX: Int, realY: Int, a: Float) {
        val mouseX = (realX / zoom).toInt()
        val mouseY = (realY / zoom).toInt()
        hits.clear()
        drawnScrolls.clear()
        graphics.pose().pushMatrix()
        graphics.pose().scale(zoom, zoom)
        graphics.fill(0, 0, width, height, Theme.SCRIM)
        val modal = modal
        draw(Ui(graphics, font, if (modal) -1 else mouseX, if (modal) -1 else mouseY, hits, scrolls, drawnScrolls, held))
        super.extractRenderState(graphics, if (modal) -1 else mouseX, if (modal) -1 else mouseY, a)
        overlayScrolls = drawnScrolls.size
        drawOverlay(Ui(graphics, font, mouseX, mouseY, hits, scrolls, drawnScrolls, held))
        graphics.pose().popMatrix()
        val above = hits.drop(hits.indexOfLast { it.blocks }.coerceAtLeast(0))
        above.lastOrNull { it.tooltip != null && it.contains(mouseX.toDouble(), mouseY.toDouble()) }?.let { hit ->
            graphics.setTooltipForNextFrame(font.split(Component.literal(hit.tooltip!!), 260), realX, realY)
        }
    }

    override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {}

    override fun mouseClicked(real: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val event = real.scaled()
        if (!modal && super.mouseClicked(event, doubleClick)) return true
        liveScrolls().lastOrNull { it.inGutter(event.x(), event.y()) }?.let { area ->
            // On the thumb: drag it from where it was grabbed. Above or below: it jumps there, centered.
            grab = if (event.y() >= area.barY && event.y() < area.barY + area.bar) event.y() - area.barY else area.bar / 2.0
            area.dragTo(event.y() - grab)
            held = area.id
            return true
        }
        val hit = hits.lastOrNull { it.onClick != null && it.contains(event.x(), event.y()) } ?: return false
        Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
        hit.onClick!!()
        return true
    }

    override fun mouseDragged(real: MouseButtonEvent, dx: Double, dy: Double): Boolean {
        val event = real.scaled()
        val area = held?.let(scrolls::get) ?: return super.mouseDragged(event, dx / zoom, dy / zoom)
        area.dragTo(event.y() - grab)
        return true
    }

    override fun mouseReleased(real: MouseButtonEvent): Boolean {
        if (held == null) return super.mouseReleased(real.scaled())
        held = null
        return true
    }

    override fun mouseMoved(x: Double, y: Double) = super.mouseMoved(x / zoom, y / zoom)

    /** The scroll areas that take the mouse: the overlay's only while [modal]. */
    private fun liveScrolls() = if (modal) drawnScrolls.drop(overlayScrolls) else drawnScrolls

    override fun mouseScrolled(realX: Double, realY: Double, scrollX: Double, scrollY: Double): Boolean {
        val x = realX / zoom
        val y = realY / zoom
        val area = liveScrolls().lastOrNull { x >= it.x && x < it.x + it.w && y >= it.y && y < it.y + it.h } ?: return super.mouseScrolled(x, y, scrollX, scrollY)
        area.offset = (area.offset - (scrollY * 24).toInt()).coerceIn(0, area.max)
        return true
    }

    override fun isPauseScreen() = false

    companion object {
        /** The smallest layout the screens are made for, in their units. */
        const val MIN_WIDTH = 560
        const val MIN_HEIGHT = 300
    }
}
