package games.qore.foreman.client.ui

import games.qore.foreman.client.agent.Chats
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.editor.EditorPanel
import games.qore.foreman.client.editor.EditorTool
import games.qore.foreman.client.editor.StatusCard
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.AIOverlay
import games.qore.foreman.client.overlays.DisplayBudget
import games.qore.foreman.client.packets.PacketViewer
import games.qore.foreman.client.ui.kit.Ui
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * Top-left HUD: the active tool's [StatusCard], the areas the player is in, the display budget, a packet recording, and
 * the chats at work; hidden with the HUD (F1).
 */
object Hud {
    fun init() {
        HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath("foreman", "hud"), HudElement { g, _ ->
            val minecraft = Minecraft.getInstance()
            if (minecraft.gui.screen() != null) return@HudElement
            val font = minecraft.font
            var top = 5
            if (!minecraft.gui.hud.isHidden) {
                (Tools.active as? EditorTool)?.let { tool ->
                    val ui = Ui(g, font, -1, -1, mutableListOf(), HashMap())
                    top += StatusCard.draw(g, ui, 4, 4, g.guiWidth() - EditorPanel.drawnWidth - 12, g.guiHeight() / 2, tool) + 3
                }
                val lines = mutableListOf<String>()
                if (Config.settings.overlays.hudAreas && AIOverlay.areasHere.isNotEmpty()) lines += "§bIn: §7" + AIOverlay.areasHere.joinToString(", ")
                lines += DisplayBudget.hud()
                PacketViewer.hud()?.let { lines += it }
                Chats.status().forEach { lines += "§d$it" }
                lines.forEachIndexed { i, line -> g.text(font, line, 5, top + i * 11, -1) }
            }
            // Entering and leaving areas, under the boss bars, fading out.
            val now = System.currentTimeMillis()
            AIOverlay.notices.forEachIndexed { i, (text, at) ->
                val alpha = (1 - (now - at - 2500) / 1500f).coerceIn(0f, 1f)
                if (alpha <= 0.05f) return@forEachIndexed
                val w = font.width(text)
                val x = (g.guiWidth() - w) / 2
                val y = 32 + i * 12
                g.fill(x - 4, y - 2, x + w + 4, y + 10, ((alpha * 0x90).toInt() shl 24))
                g.text(font, text, x, y, ((alpha * 255).toInt() shl 24) or 0x7FD8FF)
            }
        })
    }
}
