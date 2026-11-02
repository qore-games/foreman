package games.qore.foreman.client.hud

import games.qore.foreman.client.hud.HudInspector.Element
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.util.Chat
import com.mojang.brigadier.arguments.StringArgumentType
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.Minecraft
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Guides drawn over one HUD text: the screen's center, where each part starts and where it leaves the cursor (in red
 * when it doesn't come back), and the left edge of each glyph. `/hud` opens the hub's HUD section.
 */
object HudGuides {
    /** The element with guides, or null. */
    var element: Element? = null
    var index = 0

    private var layoutOf: Component? = null
    private var layout: TextLayout.Layout? = null

    fun init() {
        HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("foreman", "hud_guides"), HudElement { g, _ -> draw(g) })
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("hud")
                .executes { Screens.open(HubScreen(HubScreen.Section.HUD)); 1 }
                .then(literal("guides").executes { toggle(Element.ACTION_BAR); 1 }
                    .then(argument("element", StringArgumentType.word())
                        .suggests { _, b -> SharedSuggestionProvider.suggest(Element.entries.map { it.id }, b) }
                        .executes { ctx -> Element.of(StringArgumentType.getString(ctx, "element"))?.let { toggle(it) } ?: Chat.error("No such element"); 1 })))
        }
    }

    fun toggle(e: Element, i: Int = 0) {
        if (element == e && index == i) element = null else { element = e; index = i }
        Chat.info(element?.let { "Guides on the ${it.label.lowercase()}" } ?: "Guides off")
    }

    private fun draw(g: net.minecraft.client.gui.GuiGraphicsExtractor) {
        val e = element ?: return
        val minecraft = Minecraft.getInstance()
        if (minecraft.gui.screen() != null) return
        val text = HudInspector.text(e, index) ?: return
        val baseline = HudInspector.baseline(e, index) ?: return
        val layout = if (text === layoutOf) layout!! else TextLayout.of(text).also { layoutOf = text; layout = it }
        val scale = HudInspector.scale(e)
        val center = g.guiWidth() / 2f
        val start = HudInspector.start(e, layout.width)
        fun sx(x: Float) = (center + scale * (start + x)).roundToInt()
        // A bitmap glyph's top is its ascent above the baseline (negative ascents draw it lower); text is 7 up, 8 tall.
        val drawn = layout.pieces.filter { it.kind != TextLayout.Kind.SPACE && it.advance != 0f }.map { p ->
            val glyph = p.bitmap
            val top = baseline - scale * (glyph?.ascent ?: 7)
            Triple(p, top.roundToInt(), (top + scale * (glyph?.height ?: 8)).roundToInt())
        }
        val top = drawn.minOfOrNull { it.second } ?: (baseline - 7).roundToInt()
        val bottom = drawn.maxOfOrNull { it.third } ?: (baseline + 1).roundToInt()
        g.fill(center.roundToInt(), top - 4, center.roundToInt() + 1, bottom + 4, 0x80FFFFFF.toInt())
        for ((p, t, _) in drawn) if (p.kind == TextLayout.Kind.GLYPH) {
            g.fill(sx(p.x), t, sx(p.x) + 1, t + 3, Theme.ACCENT)
            g.fill(sx(p.x), t, sx(p.x) + 3, t + 1, Theme.ACCENT)
        }
        layout.parts.forEachIndexed { i, part ->
            g.fill(sx(part.start), bottom + 2, sx(part.start) + 1, bottom + 8, Theme.OK)
            if (abs(part.net) >= 0.5f) {
                g.fill(sx(part.end), bottom + 2, sx(part.end) + 1, bottom + 10, Theme.ERROR)
                g.text(minecraft.font, (if (part.net > 0) "+" else "") + TextLayout.fmt(part.net), sx(part.end) + 2, bottom + 4 + (i % 2) * 8, Theme.ERROR, true)
            }
        }
    }
}
