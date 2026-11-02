package games.qore.foreman.client.link

import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.gizmo.BoxGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tool
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * A box selection made on the client: left click a block for the first corner, right click for the second, then drag
 * the face knobs to resize or the center arrows to move it. Sent to foreman (`get_context` shows it to the AI).
 */
object SelectionTool : Tool {
    override val title = "Selection"
    private const val COLOR = 0xFFFFFFFF.toInt()

    var box: BlockBox? = null
        private set
    var dimension: String? = null
        private set
    private var first: BlockPos? = null
    private var second: BlockPos? = null

    private val gizmo = BoxGizmo({ box }, { box = it }, { send() })

    fun init() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> clear(send = false) }
    }

    fun clear(send: Boolean = true) {
        gizmo.cancel()
        box = null
        first = null
        second = null
        if (send) send()
    }

    fun describe(): String? = box?.let { "${it.describe()} in ${Bridge.world.ifEmpty { dimension ?: "?" }}" }

    private fun send() = Bridge.sendSelection(dimension, box)

    override fun press(button: MouseButton, ray: Ray) {
        if (button == MouseButton.LEFT && gizmo.press(ray)) return
        val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val level = Minecraft.getInstance().level ?: return
        val dim = level.dimension().identifier().toString()
        if (dim != dimension) {
            first = null
            second = null
        }
        dimension = dim
        if (button == MouseButton.LEFT) first = hit.blockPos else second = hit.blockPos
        val a = first ?: second!!
        val b = second ?: first!!
        box = BlockBox.of(a.x, a.y, a.z, b.x, b.y, b.z)
        send()
    }

    override fun release(button: MouseButton) {
        if (button == MouseButton.LEFT) gizmo.release()
    }

    override fun update(ray: Ray) = gizmo.update(ray)

    override fun deactivated() = gizmo.cancel()

    override fun draw(draw: OverlayDraw) {
        drawBox(draw)
        if (box != null && dimension == draw.dimension) gizmo.draw(draw)
    }

    /** The selection is shown even when the tool is off. */
    fun drawBox(draw: OverlayDraw) {
        val b = box ?: return
        if (dimension != draw.dimension) return
        AIOverlay.box(draw, b, COLOR, 3f, Colors.withAlpha(COLOR, 0.08f), onTop = true)
        draw.label(AIOverlay.topCenter(b), "${b.sizeX}×${b.sizeY}×${b.sizeZ}", COLOR)
    }

    override fun hud(): List<String> = listOfNotNull(
        box?.let { "§f${it.describe()}" } ?: "§7Left / right click: corners",
        if (box != null) "§7Drag a knob to resize, an arrow to move" else null,
        if (!Bridge.connected) "§7No Foreman plugin here: chats get it from you" else null,
    )
}
