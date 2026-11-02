package games.qore.foreman.client.util

import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.Identifier

/** Messages the mod shows in chat: the hard hat first, one color per kind. */
object Chat {
    /** The hard hat, a glyph of the mod's `foreman:logo` font; hovering it says it's Foreman. */
    private val LOGO: Component = Component.literal("\uE000").withStyle {
        it.withFont(FontDescription.Resource(Identifier.fromNamespaceAndPath("foreman", "logo"))).withColor(ChatFormatting.WHITE)
            .withHoverEvent(HoverEvent.ShowText(Component.literal("Foreman")))
    }

    /** [text] after the hard hat, for lines a feature builds itself. */
    fun prefixed(text: Component): Component = Component.empty().append(LOGO).append(" ").append(text)

    fun send(text: Component) {
        Minecraft.getInstance().player?.sendSystemMessage(text)
    }

    fun info(text: String) = send(prefixed(Component.literal(text).withStyle(ChatFormatting.GRAY)))
    fun success(text: String) = send(prefixed(Component.literal(text).withStyle(ChatFormatting.GREEN)))
    fun warn(text: String) = send(prefixed(Component.literal(text).withStyle(ChatFormatting.YELLOW)))
    fun error(text: String) = send(prefixed(Component.literal(text).withStyle(ChatFormatting.RED)))

    /** A help or list line: [head] then a dimmer [tail]; clicking suggests [suggest]. */
    fun line(head: String, tail: String = "", color: ChatFormatting = ChatFormatting.GRAY, suggest: String? = null, hover: String? = null): MutableComponent {
        val line = Component.literal("  $head").withStyle(color)
        if (tail.isNotEmpty()) line.append(Component.literal(" $tail").withStyle(ChatFormatting.DARK_GRAY))
        if (suggest != null) line.withStyle { it.withClickEvent(ClickEvent.SuggestCommand(suggest)) }
        if (hover != null) line.withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(hover))) }
        return line
    }

    fun title(text: String) = send(prefixed(Component.literal(text).withStyle(ChatFormatting.YELLOW)))
}
