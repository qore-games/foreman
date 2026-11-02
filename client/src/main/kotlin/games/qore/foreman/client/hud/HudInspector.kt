package games.qore.foreman.client.hud

import games.qore.foreman.client.hud.TextLayout.fmt
import games.qore.foreman.client.mixin.BossHealthOverlayAccessor
import games.qore.foreman.client.mixin.HudAccessor
import games.qore.foreman.client.mixin.PlayerTabOverlayAccessor
import games.qore.foreman.client.packets.PacketInspector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import kotlin.math.abs

/**
 * The texts the HUD shows (action bar, titles, boss bars, the tab list's header and footer, the open screen's title),
 * laid out as the client draws them, and watched over time to catch what moves when it shouldn't. Client thread.
 */
object HudInspector {
    enum class Element(val id: String, val label: String, val centered: Boolean) {
        ACTION_BAR("action_bar", "Action bar", true),
        TITLE("title", "Title", true),
        SUBTITLE("subtitle", "Subtitle", true),
        BOSS_BAR("boss_bar", "Boss bars", true),
        TAB_HEADER("tab_header", "Tab header", true),
        TAB_FOOTER("tab_footer", "Tab footer", true),
        SCREEN_TITLE("screen_title", "Screen title", false);

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id }
        }
    }

    /** An element's text now, or null when it isn't shown. [index]: which boss bar, from the top. */
    fun text(element: Element, index: Int = 0): Component? {
        val minecraft = Minecraft.getInstance()
        val hud = minecraft.gui.hud
        val shown = hud as HudAccessor
        return when (element) {
            Element.ACTION_BAR -> shown.`foreman$actionBar`()?.takeIf { shown.`foreman$actionBarTime`() > 0 }
            Element.TITLE -> shown.`foreman$title`()?.takeIf { shown.`foreman$titleTime`() > 0 }
            Element.SUBTITLE -> shown.`foreman$subtitle`()?.takeIf { shown.`foreman$titleTime`() > 0 }
            Element.BOSS_BAR -> (hud.bossOverlay as BossHealthOverlayAccessor).`foreman$events`().values.toList().getOrNull(index)?.name
            Element.TAB_HEADER -> (hud.tabList as PlayerTabOverlayAccessor).`foreman$header`()
            Element.TAB_FOOTER -> (hud.tabList as PlayerTabOverlayAccessor).`foreman$footer`()
            Element.SCREEN_TITLE -> minecraft.gui.screen()?.title
        }
    }

    fun bossBars(): Int = (Minecraft.getInstance().gui.hud.bossOverlay as BossHealthOverlayAccessor).`foreman$events`().size

    /** Where the line starts, from the element's anchor (the screen's center for centered ones), as the client computes it. */
    fun start(element: Element, width: Int): Int = if (element.centered) -width / 2 else 0

    /** The baseline of the element's line (its y + 7) on the screen, as Hud and BossHealthOverlay draw it; null when unknown. */
    fun baseline(element: Element, index: Int): Float? {
        val window = Minecraft.getInstance().window
        val h = window.guiScaledHeight
        return when (element) {
            Element.ACTION_BAR -> h - 68 - 4 + 7f
            Element.TITLE -> h / 2f + scale(element) * (-10 + 7)
            Element.SUBTITLE -> h / 2f + scale(element) * (5 + 7)
            Element.BOSS_BAR -> 12 + index * 19 - 9 + 7f
            else -> null
        }
    }

    /** Title and subtitle are drawn scaled up. */
    fun scale(element: Element) = when (element) {
        Element.TITLE -> 4f
        Element.SUBTITLE -> 2f
        else -> 1f
    }

    // ---- Report -----------------------------------------------------------------------------------------------------

    /** The element's layout, part by part, in text: what the AI tools return and the hub shows. */
    fun report(element: Element, index: Int, component: Component, glyphs: Boolean = true): String {
        val layout = TextLayout.of(component)
        val start = start(element, layout.width)
        return buildString {
            append(element.label).append(if (element == Element.BOSS_BAR) " #$index" else "")
            append(": ${layout.parts.size} part(s), advance ${fmt(layout.sum)} px (width ${layout.width})")
            if (element.centered) append(", drawn from x ${start} around the screen's center")
            if (scale(element) != 1f) append(", scaled ×${fmt(scale(element))}")
            appendLine()
            appendLine("Text: ${PacketInspector.readable(component.string).take(200)}")
            for (p in layout.parts) {
                append("  part ${p.index}${p.label?.let { " \"$it\"" } ?: ""}: from ${fmt(p.start)} to ${fmt(p.end)} (net ${fmt(p.net)})")
                p.left?.let { l -> append(", draws ${fmt(start + l)}..${fmt(start + p.right!!)} on screen") }
                appendLine()
                appendLine("    ${p.text.take(300)}")
                if (glyphs) p.pieces.filter { it.kind == TextLayout.Kind.GLYPH }.distinctBy { it.name to it.cp }.take(12).forEach { g ->
                    appendLine("    glyph ${g.label} (${"U+%04X".format(g.cp)} in ${g.font}): advance ${fmt(g.advance)}, at ${fmt(start + g.x)}")
                }
            }
            val problems = TextLayout.problems(layout, element.centered)
            if (problems.isEmpty()) appendLine("No problem found: every part comes back to where it started.")
            else problems.forEach { appendLine("Problem: $it") }
        }.trimEnd()
    }

    // ---- Watch ------------------------------------------------------------------------------------------------------

    /** One state of the element and when it appeared (ms since the watch started). */
    class Sample(val ms: Long, val component: Component, val layout: TextLayout.Layout)

    class Shift(val ms: Long, val moved: List<Pair<String, Float>>, val part: Int?, val before: String, val after: String, val netBefore: Float?, val netAfter: Float?, val widthBefore: Float, val widthAfter: Float)

    class Watch(val element: Element, val index: Int, val until: Long) {
        val started = System.currentTimeMillis()
        val samples = ArrayList<Sample>()
        var done = false
        val onDone = ArrayList<() -> Unit>()
    }

    private val watches = ArrayList<Watch>()

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    /** Records each new state of [element] for [seconds]; [then] runs on the client thread when it is over. */
    fun watch(element: Element, index: Int, seconds: Int, then: (Watch) -> Unit): Watch {
        val watch = Watch(element, index, System.currentTimeMillis() + seconds * 1000L)
        watch.onDone += { then(watch) }
        watches += watch
        return watch
    }

    private fun tick() {
        if (watches.isEmpty()) return
        val now = System.currentTimeMillis()
        for (w in watches.toList()) {
            val component = text(w.element, w.index)
            if (component != null && component != w.samples.lastOrNull()?.component && w.samples.size < 500) {
                w.samples += Sample(now - w.started, component, TextLayout.of(component))
            }
            if (now >= w.until) {
                w.done = true
                watches -= w
                w.onDone.forEach { it() }
            }
        }
    }

    /** Between each state and the next: what was drawn somewhere else, and the part whose text changed. */
    fun shifts(w: Watch): List<Shift> = w.samples.zipWithNext().mapNotNull { (a, b) ->
        val pa = positions(w.element, a.layout)
        val pb = positions(w.element, b.layout)
        val moved = pa.keys.intersect(pb.keys).mapNotNull { k -> (pb[k]!! - pa[k]!!).takeIf { abs(it) >= 0.5f }?.let { k.substringBefore('#') to it } }
        if (moved.isEmpty()) return@mapNotNull null
        val changed = (0 until maxOf(a.layout.parts.size, b.layout.parts.size)).firstOrNull { i -> a.layout.parts.getOrNull(i)?.text != b.layout.parts.getOrNull(i)?.text }
        Shift(b.ms, moved, changed,
            changed?.let { a.layout.parts.getOrNull(it)?.text } ?: PacketInspector.readable(a.component.string),
            changed?.let { b.layout.parts.getOrNull(it)?.text } ?: PacketInspector.readable(b.component.string),
            changed?.let { a.layout.parts.getOrNull(it)?.net }, changed?.let { b.layout.parts.getOrNull(it)?.net }, a.layout.sum, b.layout.sum)
    }

    /** Each glyph drawn, by name and occurrence, at its x on screen (text characters are left out: they change). */
    private fun positions(element: Element, layout: TextLayout.Layout): Map<String, Float> {
        val start = start(element, layout.width)
        val seen = HashMap<String, Int>()
        return layout.pieces.filter { it.kind == TextLayout.Kind.GLYPH }.associate { p ->
            val key = p.label + "@" + p.font
            val n = seen.merge(key, 1, Int::plus)!!
            "$key#$n" to start + p.x
        }
    }

    fun watchReport(w: Watch): String {
        val shifts = shifts(w)
        return buildString {
            append("Watched ${w.element.label}${if (w.element == Element.BOSS_BAR) " #${w.index}" else ""} for ${(System.currentTimeMillis() - w.started) / 1000} s: ${w.samples.size} state(s)")
            if (w.samples.size < 2) {
                append(". It didn't change: trigger what changes it (reload, take damage…) while watching.")
                return@buildString
            }
            appendLine(", ${shifts.size} change(s) moved glyphs.")
            if (shifts.isEmpty()) append("No glyph moved: the layout is stable across these states.")
            for (s in shifts.take(20)) {
                val by = s.moved.groupBy { it.second }.entries.sortedByDescending { it.value.size }
                append("+${s.ms / 1000.0}s: ")
                append(by.take(3).joinToString("; ") { (dx, list) -> "${list.size} glyph(s) moved ${if (dx > 0) "+" else ""}${fmt(dx)} px (${list.take(3).joinToString { it.first.substringBefore('@').substringAfterLast(':') }})" })
                appendLine()
                if (s.part != null) appendLine("   part ${s.part} changed: «${s.before.take(100)}» → «${s.after.take(100)}», its net ${s.netBefore?.let { fmt(it) }} → ${s.netAfter?.let { fmt(it) }}")
                else appendLine("   text: «${s.before.take(100)}» → «${s.after.take(100)}»")
                appendLine("   line advance ${fmt(s.widthBefore)} → ${fmt(s.widthAfter)}")
            }
        }.trimEnd()
    }
}
