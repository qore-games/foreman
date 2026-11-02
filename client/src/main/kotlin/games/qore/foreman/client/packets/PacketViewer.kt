package games.qore.foreman.client.packets

import com.mojang.brigadier.arguments.IntegerArgumentType
import games.qore.foreman.client.agent.Chats
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.ui.ChatScreen
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/** The packet viewer's entry points: `/packets`, its key, the HUD line while recording, and the AI's tools. */
object PacketViewer {
    /** What a chat is asked to find in the recording. */
    const val ANALYSIS_PROMPT = "Find what lags my connection or stutters my client in the packet recording. Start with " +
        "packets_summary, then packets_suspects: the traffic grouped by what sends it, with what to search for. For the biggest " +
        "suspects, search the chat's folders (plugin sources, server configs, language files) for their clues (item models, " +
        "custom data keys, translation keys, texts, names, plugin channels) to find the plugin and the code or config that sends " +
        "them; packets_list and packets_timeline (50 ms buckets) show the packets themselves. For each culprit: what it costs " +
        "(packets/s, KB/s, share of the traffic), the plugin and file that sends it, and the change that sends less (show the " +
        "code). If you can't find the source, say which clues you searched. If nothing is recorded, record 20 seconds first " +
        "(packets_record) and ask me to stay where it lags."

    fun init() {
        PacketTools.init()
        ClientTickEvents.END_CLIENT_TICK.register {
            if (PacketRecorder.tick()) Chat.info("Packet recording stopped: ${PacketReports.count(PacketRecorder.total)} packets (/packets to see them)")
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> PacketRecorder.stop() }
        Keys.bind("packets-record", Keys.Category.DEBUG, Keys.UNBOUND, "Packet recording: start / stop") { toggle() }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("packets")
                .executes { Screens.open(HubScreen(HubScreen.Section.PACKETS)); 1 }
                .then(literal("start").executes { start(0); 1 }
                    .then(argument("seconds", IntegerArgumentType.integer(1, 3600)).executes { start(IntegerArgumentType.getInteger(it, "seconds")); 1 }))
                .then(literal("stop").executes { PacketRecorder.stop(); Chat.info(PacketTools.status()); 1 })
                .then(literal("clear").executes { PacketRecorder.clear(); Chat.success("Packet recording dropped"); 1 })
                .then(literal("export").executes { export(); 1 })
                .then(literal("ask").executes { ask(); 1 }))
        }
    }

    fun toggle() {
        if (PacketRecorder.recording) {
            PacketRecorder.stop()
            Chat.info(PacketTools.status() + " /packets to see them")
        } else start(0)
    }

    private fun start(seconds: Int) {
        PacketRecorder.start(seconds)
        Chat.success("Recording packets" + if (seconds > 0) " for ${seconds}s" else " (/packets stop)")
    }

    fun export() {
        if (!PacketRecorder.hasData) return Chat.warn("Nothing recorded: /packets start")
        val file = runCatching { PacketReports.export(PacketReports.Names.capture()) }.getOrElse { return Chat.error("Export failed: ${it.message}") }
        Chat.success("Packets written to $file")
    }

    /** A new chat looks for what lags in the recording. */
    fun ask() {
        val chat = Chats.create()
        if (chat.data.folders.isEmpty()) Chat.warn("This chat has no folder to search: add your plugins' sources and server configs as a default workspace (H, Workspaces) so the AI can find the code")
        chat.send(ANALYSIS_PROMPT, "Find what lags in the packet recording")
        Screens.open(ChatScreen(open = chat))
    }

    /** The HUD line while recording. */
    fun hud(): String? = if (!PacketRecorder.recording) null else {
        val second = PacketRecorder.lastSecond()
        "§c● §7Packets ${PacketReports.clock(PacketRecorder.elapsedNanos())}" + (second?.let { " · ${PacketReports.bytes(it[PacketRecorder.IN_WIRE])}/s" } ?: "")
    }
}
