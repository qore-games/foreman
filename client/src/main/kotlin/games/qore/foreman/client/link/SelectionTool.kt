package games.qore.foreman.client.link

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.EditorTool
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.gizmo.BoxGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.ChatScreen
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult

/**
 * A box selection made on the client: left click a block for the first corner, right click for the second, then drag
 * the face knobs to resize or the center arrows to move it. Sent to foreman (`get_context` shows it to the AI). The
 * editor's other tools make their new things from it ([current]): assets, areas.
 */
object SelectionTool : EditorTool {
    override val title = "Selection"
    override val id = "selection"
    private const val COLOR = 0xFFFFFFFF.toInt()

    var box: BlockBox? = null
        private set
    var dimension: String? = null
        private set
    private var first: BlockPos? = null
    private var second: BlockPos? = null

    private val gizmo = BoxGizmo({ box }, { box = it }, { send() })

    override fun keys() = listOf(
        EditorKey(InputConstants.KEY_RETURN, "Ask a chat about it") { ask() },
        EditorKey(InputConstants.KEY_DELETE, "Clear it") { clear() },
        EditorKey(InputConstants.KEY_N, "The hub's Selection page") { Screens.open(HubScreen(HubScreen.Section.SELECTION)) },
    )

    /** Opens the chats with the selection as the place to work in. */
    fun ask() {
        val selection = describe() ?: return Chat.warn("No selection: click two corners with the selection tool (B, then 1)")
        Screens.open(ChatScreen("the selected area $selection"))
    }

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

    /** Makes [box] in [dimension] the selection (a pasted copy's box, from its panel). */
    fun select(dimension: String, box: BlockBox) {
        gizmo.cancel()
        this.dimension = dimension
        this.box = box
        first = BlockPos(box.minX, box.minY, box.minZ)
        second = BlockPos(box.maxX, box.maxY, box.maxZ)
        send()
    }

    fun describe(): String? = box?.let { "${it.describe()} in ${Bridge.world.ifEmpty { dimension ?: "?" }}" }

    /** The box new things are made from: this selection when it is in the player's dimension, else WorldEdit's. */
    fun current(): BlockBox? = box?.takeIf { dimension != null && dimension == playerDimension() } ?: WorldEditSelection.shape()?.bounds

    /** What [current] is, for a panel: `Selection 4×3×5 at 10 64 -3`, or null without one. */
    fun currentLabel(): String? {
        val b = current() ?: return null
        val whose = if (b == box) "Selection" else "WorldEdit selection"
        return "$whose ${b.sizeX}×${b.sizeY}×${b.sizeZ} at ${b.minX} ${b.minY} ${b.minZ}"
    }

    override fun snapNote() = "The box: whole blocks"

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

    override fun status() = ToolStatus(
        selected = box?.let { Shown("Selection box", "${it.sizeX}×${it.sizeY}×${it.sizeZ}", "${it.minX} ${it.minY} ${it.minZ} to ${it.maxX} ${it.maxY} ${it.maxZ}") },
        empty = "Click two blocks: its corners",
        hints = when {
            gizmo.dragging -> emptyList()
            gizmo.hovered != null -> listOf(Hint(ToolStatus.DRAG, if (gizmo.hovered!!.face < 0) "move the box" else "move this face"))
            else -> listOfNotNull(
                Hint(ToolStatus.LEFT, "a block: the first corner"),
                Hint(ToolStatus.RIGHT, "a block: the second corner"),
                if (box != null) Hint(ToolStatus.DRAG, "a knob resizes it, an arrow moves it") else null,
            )
        },
        notes = if (Bridge.connected) emptyList() else listOf(Note("No Foreman plugin here: chats get it from you")),
    )
}
