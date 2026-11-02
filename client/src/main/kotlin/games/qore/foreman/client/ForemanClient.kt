package games.qore.foreman.client

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.link.AIClient
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.debug.CopyLocationHotkeys
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.locations.LocalSets
import games.qore.foreman.client.locations.LocationChannels
import games.qore.foreman.client.locations.LocationCommands
import games.qore.foreman.client.locations.LocationEditor
import games.qore.foreman.client.locations.LocationLayer
import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.hud.HudGuides
import games.qore.foreman.client.hud.UiTools
import games.qore.foreman.client.packets.PacketViewer
import games.qore.foreman.client.render.Overlays
import games.qore.foreman.client.ui.Hud
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import net.fabricmc.api.ClientModInitializer

object ForemanClient : ClientModInitializer {
    override fun onInitializeClient() {
        Config.load()
        Keys.init()
        Overlays.init()
        Tools.init()
        Hud.init()

        LocationChannels.init()
        LocalSets.init()
        LocationEditor.init()
        LocationCommands.init()
        Overlays.add(LocationLayer)
        Keys.bind("locations", Keys.Category.WORLD, InputConstants.KEY_L, "Locations: sources and categories") { Screens.open(HubScreen(HubScreen.Section.LOCATIONS)) }
        Keys.bind("location-editor", Keys.Category.WORLD, Keys.UNBOUND, "Location editor (gizmo)") {
            Chat.info(if (Tools.toggle(LocationEditor)) "Location editor on" else "Location editor off")
        }
        Keys.bind("move-to-player-exact", Keys.Category.WORLD, InputConstants.KEY_K, "Editor: move the location to your feet") { editorMove(LocationEditor.PlayerAnchor.FEET) }
        Keys.bind("move-to-player-eye-exact", Keys.Category.WORLD, InputConstants.KEY_U, "Editor: move it to your eyes") { editorMove(LocationEditor.PlayerAnchor.EYES) }
        Keys.bind("move-to-player-block", Keys.Category.WORLD, InputConstants.KEY_J, "Editor: move it to your block") { editorMove(LocationEditor.PlayerAnchor.BLOCK) }

        CopyLocationHotkeys.init()
        games.qore.foreman.client.link.PacketFilter.init()
        games.qore.foreman.client.link.NetworkProfiles.init()
        games.qore.foreman.client.link.OutboundProfiles.init()
        AIClient.init()
        PacketViewer.init()
        UiTools.init()
        HudGuides.init()
        // Last: its threads list the tools registered above.
        ClientMcp.start()
    }

    /** The editor's move keys only act while it is on, with a selection. */
    private fun editorMove(anchor: LocationEditor.PlayerAnchor) {
        if (LocationEditor.enabled) LocationEditor.moveToPlayer(anchor)
    }
}
