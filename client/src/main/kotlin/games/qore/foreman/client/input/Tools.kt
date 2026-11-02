package games.qore.foreman.client.input

import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.render.Overlays
import net.minecraft.client.Minecraft

enum class MouseButton { LEFT, RIGHT }

/**
 * An in-world tool driven by the crosshair (location editor, box selection). While a tool is active it receives the
 * attack and use clicks and the scroll wheel instead of the game.
 */
interface Tool {
    val title: String

    /** A click; [ray] is the crosshair. */
    fun press(button: MouseButton, ray: Ray)

    fun release(button: MouseButton) {}

    /** Called every frame (before [draw]) while the tool is active. */
    fun update(ray: Ray) {}

    /** Scroll while active; true when used (the hotbar does not change then). */
    fun scroll(amount: Double): Boolean = false

    fun draw(draw: OverlayDraw)

    /** Lines shown under the tool's title in the HUD. */
    fun hud(): List<String> = emptyList()

    fun deactivated() {}
}

/** The active tool and the input it takes from the game (called by the Minecraft and MouseHandler mixins). */
object Tools {
    var active: Tool? = null
        private set

    private val held = mutableSetOf<MouseButton>()

    fun toggle(tool: Tool): Boolean {
        if (active === tool) {
            deactivate()
            return false
        }
        active?.deactivated()
        active = tool
        held.clear()
        return true
    }

    fun deactivate() {
        active?.deactivated()
        active = null
        held.clear()
    }

    fun init() {
        Overlays.add { draw ->
            val tool = active ?: return@add
            val options = Minecraft.getInstance().options
            releaseIfUp(MouseButton.LEFT, options.keyAttack.isDown)
            releaseIfUp(MouseButton.RIGHT, options.keyUse.isDown)
            val ray = Ray.crosshair()
            tool.update(ray)
            tool.draw(draw)
        }
    }

    private fun releaseIfUp(button: MouseButton, down: Boolean) {
        if (!down && held.remove(button)) active?.release(button)
    }

    private fun press(button: MouseButton): Boolean {
        val tool = active ?: return false
        if (Minecraft.getInstance().gui.screen() != null) return true
        if (held.add(button)) tool.press(button, Ray.crosshair())
        return true
    }

    @JvmStatic
    fun onAttack(): Boolean = press(MouseButton.LEFT)

    @JvmStatic
    fun onUse(): Boolean = press(MouseButton.RIGHT)

    @JvmStatic
    fun capturesAttack(): Boolean = active != null

    @JvmStatic
    fun onScroll(amount: Double): Boolean = active?.scroll(amount) ?: false
}
