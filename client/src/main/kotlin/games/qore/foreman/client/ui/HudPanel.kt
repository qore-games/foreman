package games.qore.foreman.client.ui

import games.qore.foreman.client.hud.HudGuides
import games.qore.foreman.client.hud.HudInspector
import games.qore.foreman.client.hud.HudInspector.Element
import games.qore.foreman.client.hud.TextLayout
import games.qore.foreman.client.hud.TextLayout.fmt
import games.qore.foreman.client.ui.kit.Col
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.ui.kit.table
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.abs

/**
 * The hub's HUD section: a HUD text (action bar, titles, boss bars, the tab list's header and footer) as the client
 * lays it out, part by part, with what's wrong; guides drawn over it in game; and a watch that reports what moves when
 * it changes. Read four times a second.
 */
object HudPanel {
    private val elements = listOf(Element.ACTION_BAR, Element.TITLE, Element.SUBTITLE, Element.BOSS_BAR, Element.TAB_HEADER, Element.TAB_FOOTER)

    private var element = Element.ACTION_BAR
    private var index = 0
    private var text: Component? = null
    private var layout: TextLayout.Layout? = null
    private var readAt = 0L
    private var watch: HudInspector.Watch? = null
    private var watched: List<String> = emptyList()

    private fun read() {
        val now = System.currentTimeMillis()
        if (now - readAt < 250) return
        readAt = now
        val current = HudInspector.text(element, index)
        if (current != text) {
            text = current
            layout = current?.let { TextLayout.of(it) }
        }
    }

    private fun select(e: Element, i: Int = 0) {
        element = e
        index = i
        readAt = 0
        text = null
        layout = null
    }

    fun draw(ui: Ui, x: Int, y: Int, w: Int, h: Int) {
        read()
        var cy = y
        ui.segmented(x, cy, elements.map { it.label }, elements.indexOf(element)) { select(elements[it]) }
        cy += 20
        if (element == Element.BOSS_BAR && HudInspector.bossBars() > 1) {
            ui.segmented(x, cy, (0 until HudInspector.bossBars()).map { "#$it" }, index) { select(Element.BOSS_BAR, it) }
            cy += 20
        }

        val guides = HudGuides.element == element && HudGuides.index == index
        ui.button(x, cy, 70, 18, if (guides) "Guides: on" else "Guides", if (guides) Style.PRIMARY else Style.NORMAL,
            tooltip = "Draws over it in game: the screen's center, where each part starts (green) and leaves the cursor when it doesn't come back (red), each glyph's left edge") {
            HudGuides.toggle(element, index)
        }
        val watching = watch?.takeIf { !it.done }
        ui.button(x + 74, cy, 90, 18, watching?.let { "Watching… ${((it.until - System.currentTimeMillis()) / 1000).coerceAtLeast(0)} s" } ?: "Watch 10 s",
            enabled = watching == null, tooltip = "Records each change for 10 s and reports the glyphs that move: make it change meanwhile (close the hub)") {
            watched = emptyList()
            watch = HudInspector.watch(element, index, 10) { watched = HudInspector.watchReport(it).lines() }
        }
        ui.button(x + 168, cy, 84, 18, "Copy report", enabled = text != null, tooltip = "The layout and its problems as text, to paste in a chat") {
            text?.let { Minecraft.getInstance().keyboardHandler.setClipboard(HudInspector.report(element, index, it)) }
        }
        cy += 24

        val layout = layout
        if (text == null || layout == null) {
            ui.paragraph("${element.label} isn't shown now. Open this section while it is (the HUD keeps updating behind the hub).", x, cy, w)
            return
        }
        val start = HudInspector.start(element, layout.width)
        ui.text(ui.fit("${layout.parts.size} part(s) · advance ${fmt(layout.sum)} px · drawn from x $start" +
            if (element.centered) " around the center" else "", w), x, cy, Theme.TEXT)
        cy += 14
        for (problem in TextLayout.problems(layout, element.centered).take(3)) {
            cy += ui.paragraph("⚠ $problem", x, cy, w, Theme.WARN) + 2
        }

        val watchH = if (watched.isEmpty()) 0 else (watched.size.coerceAtMost(8) * 10 + 20)
        val cols = listOfNotNull(
            Col<TextLayout.Part>("#", 16, keep = 9) { "${it.index}" },
            // Plugins that label their parts (a style insertion) get a column for it.
            if (layout.parts.any { it.label != null }) Col("Label", 90, keep = 2, left = true) { it.label ?: "" } else null,
            Col("Start", 44, keep = 5) { fmt(it.start) },
            Col("End", 44, keep = 5) { fmt(it.end) },
            Col("Net", 40, keep = 8, color = { if (abs(it.net) >= 0.5f) Theme.WARN else Theme.MUTED }) { fmt(it.net) },
            Col("On screen", 80, keep = 4) { p -> p.left?.let { "${fmt(start + it)}..${fmt(start + p.right!!)}" } ?: "-" },
            Col("Content", 0, left = true, color = { Theme.TEXT }) { it.text },
        )
        ui.table("hud-parts", x, cy, w, y + h - cy - watchH, layout.parts, cols, tooltip = { p ->
            "Part ${p.index}${p.label?.let { " ($it)" } ?: ""}: ${fmt(p.start)} → ${fmt(p.end)}, net ${fmt(p.net)}\n${p.text}\n\n" +
                p.pieces.filter { it.kind == TextLayout.Kind.GLYPH }.distinctBy { it.label }.take(10).joinToString("\n") { "${it.label}: advance ${fmt(it.advance)}" }
        }, onClick = null)
        if (watched.isNotEmpty()) {
            val wy = y + h - watchH + 6
            ui.card(x, wy, w, watchH - 6)
            watched.take(8).forEachIndexed { i, line -> ui.text(ui.fit(line, w - 16), x + 8, wy + 6 + i * 10, if (i == 0) Theme.TEXT else Theme.MUTED) }
        }
    }
}
