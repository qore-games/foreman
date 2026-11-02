package games.qore.foreman.client.link

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.brigadier.arguments.StringArgumentType
import games.qore.foreman.client.agent.Chats
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.config.Visibility
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.render.Overlays
import games.qore.foreman.client.ui.ChatScreen
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/** The AI side of the client: bridge, client tools, overlays, selection tool, chats, the hub, their keys and `/ai`. */
object AIClient {
    private var tipShown = false

    fun init() {
        Bridge.init()
        SelectionTool.init()
        WorldEditSelection.init()
        Chats.init()
        ClientTools.init()
        Overlays.add(AIOverlay)
        Overlays.add { if (Tools.active !== SelectionTool) SelectionTool.drawBox(it) }

        Keys.bind("hub", Keys.Category.GENERAL, InputConstants.KEY_H, "Foreman hub") { Screens.open(HubScreen()) }
        Keys.bind("ai-chat", Keys.Category.AI, InputConstants.KEY_Y, "AI chats") { Screens.open(ChatScreen()) }
        Keys.bind("ai-selection-tool", Keys.Category.WORLD, InputConstants.KEY_B, "Selection tool (left/right click the corners)") {
            Chat.info(if (Tools.toggle(SelectionTool)) "Selection tool on: left / right click the corners" else "Selection tool off")
        }
        Keys.bind("worldedit-tool", Keys.Category.WORLD, Keys.UNBOUND, "WorldEdit selection tool (click positions, drag to move and resize)") {
            Chat.info(if (Tools.toggle(WorldEditTool)) "WorldEdit tool on: left / right click for //pos1 / //pos2, drag the gizmo" else "WorldEdit tool off")
        }
        Keys.bind("ai-selection-to-prompt", Keys.Category.AI, Keys.UNBOUND, "Ask the AI about the selection") {
            val selection = SelectionTool.describe() ?: return@bind Chat.warn("No selection: B, then click two corners")
            Screens.open(ChatScreen("the selected area $selection"))
        }
        Keys.bind("ai-overlays", Keys.Category.WORLD, Keys.UNBOUND, "All overlays on / off") { toggleAll() }
        Keys.bind("ai-overlay-areas", Keys.Category.WORLD, Keys.UNBOUND, "Areas: off / nearby / always") { cycle("Areas") { overlays.areas = next(overlays.areas); overlays.areas } }
        Keys.bind("ai-overlay-points", Keys.Category.WORLD, Keys.UNBOUND, "Points: off / nearby / always") { cycle("Points") { overlays.points = next(overlays.points); overlays.points } }
        Keys.bind("ai-overlay-issues", Keys.Category.WORLD, Keys.UNBOUND, "Check issues: off / nearby / always") { cycle("Check issues") { overlays.issues = next(overlays.issues); overlays.issues } }
        Keys.bind("ai-overlay-locations", Keys.Category.WORLD, Keys.UNBOUND, "Plugin locations: off / nearby / always") { cycle("Locations") { overlays.locations = next(overlays.locations); overlays.locations } }

        // Once per game: how to find everything.
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            client.execute {
                if (tipShown) return@execute
                tipShown = true
                Chat.info("H: Foreman hub · Y: AI chats")
            }
        }

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("ai")
                .executes { Screens.open(ChatScreen()); 1 }
                .then(literal("cancel").executes { Chats.running.forEach { it.cancel() }; 1 })
                .then(literal("tools").executes { games.qore.foreman.client.agent.ToolCatalog.printToChat(); 1 })
                .then(literal("new").executes { Screens.open(ChatScreen(open = Chats.create())); 1 })
                .then(literal("selection").then(literal("clear").executes { SelectionTool.clear(); Chat.success("Selection cleared"); 1 }))
                .then(literal("ask").then(argument("prompt", StringArgumentType.greedyString()).executes { ctx ->
                    // Asked from chat: a chat of its own, so a running one isn't interrupted.
                    val chat = Chats.list.firstOrNull { !it.running && it.entries.isEmpty() } ?: Chats.create()
                    chat.send(StringArgumentType.getString(ctx, "prompt"))
                    Chat.info("Sent to the AI (Y to watch)")
                    1
                })))
        }
    }

    private fun next(v: Visibility) = Visibility.entries[(v.ordinal + 1) % Visibility.entries.size]

    private fun cycle(name: String, change: games.qore.foreman.client.config.Settings.() -> Visibility) {
        var now = Visibility.OFF
        Config.update { now = change() }
        Bridge.subscribe()
        Chat.info("$name: ${now.label}")
    }

    private fun toggleAll() {
        Config.update { overlays.enabled = !overlays.enabled }
        Bridge.subscribe()
        Chat.info("Overlays ${if (Config.settings.overlays.enabled) "shown" else "hidden"}")
    }
}
