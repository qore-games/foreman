package games.qore.foreman.client

import games.qore.foreman.client.link.AIClient
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.debug.CopyLocationHotkeys
import games.qore.foreman.client.display.DisplayEditor
import games.qore.foreman.client.editor.EditorMode
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.library.Library
import games.qore.foreman.client.models.ItemModels
import games.qore.foreman.client.locations.LocalSets
import games.qore.foreman.client.locations.LocationChannels
import games.qore.foreman.client.locations.LocationCommands
import games.qore.foreman.client.locations.LocationEditor
import games.qore.foreman.client.locations.LocationLayer
import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.hud.HudGuides
import games.qore.foreman.client.hud.UiTools
import games.qore.foreman.client.packets.PacketViewer
import games.qore.foreman.client.reload.FastReload
import games.qore.foreman.client.render.Overlays
import games.qore.foreman.client.ui.Hud
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

        CopyLocationHotkeys.init()
        games.qore.foreman.client.link.PacketFilter.init()
        games.qore.foreman.client.link.NetworkProfiles.init()
        games.qore.foreman.client.link.OutboundProfiles.init()
        AIClient.init()
        Library.init()
        DisplayEditor.init()
        games.qore.foreman.client.regions.RegionsTool.init()
        ItemModels.init()
        EditorMode.init()
        games.qore.foreman.client.overlays.HiddenBlocks.init()
        games.qore.foreman.client.overlays.DisplayBudget.init()
        PacketViewer.init()
        UiTools.init()
        HudGuides.init()
        FastReload.init()
        // Last: its threads list the tools registered above.
        ClientMcp.start()
    }
}
