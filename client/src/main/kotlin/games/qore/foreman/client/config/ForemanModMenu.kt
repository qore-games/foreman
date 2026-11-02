package games.qore.foreman.client.config

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import games.qore.foreman.client.ui.HubScreen

class ForemanModMenu : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> = ConfigScreenFactory { parent -> HubScreen(HubScreen.Section.SETTINGS, parent) }
}
