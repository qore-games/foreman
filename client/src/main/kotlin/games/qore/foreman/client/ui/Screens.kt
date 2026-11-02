package games.qore.foreman.client.ui

import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen

object Screens {
    /** Opens [screen] on the next frame (commands run while the chat screen is closing). */
    fun open(screen: Screen?) {
        val minecraft = Minecraft.getInstance()
        minecraft.execute { minecraft.gui.setScreen(screen) }
    }
}
