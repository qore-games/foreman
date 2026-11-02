package games.qore.foreman.client.debug

import games.qore.foreman.client.config.Config
import games.qore.foreman.client.config.MessageOutput
import games.qore.foreman.client.net.RawPayload
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.util.Chat
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.ChatComponent
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.network.protocol.common.custom.DiscardedPayload
import org.slf4j.LoggerFactory

/**
 * Logs plugin messages (custom payloads) in both directions while it is on (hooked by `ConnectionMixin`): the last
 * [LIMIT] in the hub's list, and printed to chat (in the hub's colours) or the console when asked.
 */
object PluginMessageDebug {
    private val logger = LoggerFactory.getLogger("foreman")
    private const val LIMIT = 500
    /** The most raw bytes kept per message for the hex view; the preview and the count still see the whole payload. */
    private const val KEPT_BYTES = 1024

    /** One logged payload. [data] is the raw bytes (capped at [KEPT_BYTES]) for the hex view, or null when not raw. */
    class Message(val id: Long, val at: Long, val outbound: Boolean, val channel: String, val size: Int, val body: String, val data: ByteArray?)

    private val counter = java.util.concurrent.atomic.AtomicLong()
    private val messages = ArrayDeque<Message>()

    /** The messages kept, newest first. */
    fun messages(): List<Message> = synchronized(messages) { messages.reversed() }

    fun clear() = synchronized(messages) { messages.clear() }

    @JvmStatic
    fun onPayload(payload: CustomPacketPayload, outbound: Boolean) {
        val settings = Config.settings.pluginMessages
        if (!settings.enabled) return
        try {
            val (size, body, data) = when (payload) {
                is RawPayload -> Triple(payload.data.size, describe(payload.data), payload.data.copyOf(payload.data.size.coerceAtMost(KEPT_BYTES)))
                is DiscardedPayload -> Triple(-1, "(not decoded by the client)", null)
                else -> Triple(-1, fields(payload), null)
            }
            val message = Message(counter.getAndIncrement(), System.currentTimeMillis(), outbound, payload.type().id().toString(), size, body, data)
            synchronized(messages) {
                messages.addLast(message)
                if (messages.size > LIMIT) messages.removeFirst()
            }
            when (settings.print) {
                MessageOutput.CHAT -> Minecraft.getInstance().execute { Chat.send(Chat.prefixed(chatLine(message))) }
                MessageOutput.CONSOLE -> logger.info("Plugin message {} {}{} {}", direction(message), message.channel, if (size >= 0) " ${size}B" else "", body)
                MessageOutput.NONE -> {}
            }
        } catch (e: Exception) {
            logger.error("Could not log a payload", e)
        }
    }

    /** A stable colour per channel (hue from its hash), so the same channel always reads the same, in the hub and in chat. */
    fun channelColor(channel: String): Int {
        val hue = (channel.hashCode() and 0x7fffffff) % 360
        val c = 0.45f
        val x = c * (1 - kotlin.math.abs((hue / 60f) % 2 - 1))
        val (r, g, b) = when (hue / 60) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = 1f - c  // value 1.0
        fun ch(f: Float) = ((f + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun direction(m: Message) = if (m.outbound) "C→S" else "S→C"

    /**
     * [m] as the hub's row reads, on one chat line: direction, channel in its colour, size, and a preview cut to the chat's
     * width; hover shows it whole, click copies it. On the client thread (it measures with the font).
     */
    private fun chatLine(m: Message): Component {
        val minecraft = Minecraft.getInstance()
        val line = Component.empty()
            .append(Component.literal(direction(m)).withColor((if (m.outbound) Theme.WARN else Theme.ACCENT) and 0xFFFFFF))
            .append(" ")
            .append(Component.literal(m.channel).withColor(channelColor(m.channel) and 0xFFFFFF))
        if (m.size >= 0) line.append(Component.literal(" ${m.size}B").withStyle(ChatFormatting.DARK_GRAY))
        val font = minecraft.font
        val chatWidth = (ChatComponent.getWidth(minecraft.options.chatWidth().get()) / minecraft.options.chatScale().get()).toInt()
        val room = chatWidth - font.width(Chat.prefixed(line)) - font.width(" ")
        val preview = if (font.width(m.body) <= room) m.body else font.plainSubstrByWidth(m.body, (room - font.width("…")).coerceAtLeast(0)) + "…"
        line.append(Component.literal(" $preview").withStyle(ChatFormatting.GRAY))
        val head = "${if (m.outbound) "client → server" else "server → client"}  ·  ${if (m.size >= 0) "${m.size} bytes" else "not decoded"}"
        val hover = Component.literal(head).withColor(Theme.MUTED and 0xFFFFFF)
            .append(Component.literal("\n" + m.body).withColor(Theme.TEXT and 0xFFFFFF))
            .append(Component.literal("\nClick to copy").withStyle(ChatFormatting.DARK_GRAY))
        return line.withStyle { it.withHoverEvent(HoverEvent.ShowText(hover)).withClickEvent(ClickEvent.CopyToClipboard(m.body)) }
    }

    /** A payload the client decoded, by its fields: its `toString` without the record's name and `type` (the channel says it). */
    private fun fields(payload: CustomPacketPayload): String {
        val text = payload.toString().replace(Regex("""type=Type\[id=[^\]]*],?\s*"""), "")
        return Regex("""^\w+\[(.*)]$""").matchEntire(text)?.groupValues?.get(1) ?: text
    }

    private fun describe(data: ByteArray): String {
        val text = String(data, Charsets.UTF_8)
        val escaped = buildString {
            for (c in text.take(400)) when {
                c == '\n' -> append("\\n")
                Character.isISOControl(c) -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
        return "\"$escaped\"" + if (text.length > 400) "…" else ""
    }
}
