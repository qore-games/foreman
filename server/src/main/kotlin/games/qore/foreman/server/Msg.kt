package games.qore.foreman.server

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import org.bukkit.command.CommandSender

/** Foreman's chat messages: a coloured prefix, then the text. */
object Msg {
    private val PREFIX = Component.text("⚒ ", TextColor.color(0x4FB3FF))

    fun info(to: CommandSender, text: Component) = to.sendMessage(PREFIX.append(text.colorIfAbsent(NamedTextColor.GRAY)))
    fun success(to: CommandSender, text: Component) = to.sendMessage(PREFIX.append(text.colorIfAbsent(NamedTextColor.GREEN)))
    fun warning(to: CommandSender, text: Component) = to.sendMessage(PREFIX.append(text.colorIfAbsent(NamedTextColor.GOLD)))
    fun error(to: CommandSender, text: Component) = to.sendMessage(PREFIX.append(text.colorIfAbsent(NamedTextColor.RED)))
}
