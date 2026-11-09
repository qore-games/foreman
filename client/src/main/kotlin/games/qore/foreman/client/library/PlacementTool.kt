package games.qore.foreman.client.library

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.gizmo.GizmoChange
import games.qore.foreman.client.gizmo.GizmoFrame
import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Placing
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.AIOverlay
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.phys.Vec3
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Puts a library asset in the world. Its box follows the block looked at (its bottom center on the face looked at), with
 * a ghost of what the paste would put there ([AssetGhost]); scroll or R turns it a quarter; right click pins it there and shows arrows to nudge it; right click again (or Enter
 * at any time) pastes it with the server's `paste_asset`, one undo step. Left click unpins it, or leaves the tool while
 * it follows; Esc leaves. The tool stays on after a paste, for the next one; N picks another in the hub's library.
 */
object PlacementTool : Placing {
    override val title = "Place asset"
    override val id = "library"
    private const val REACH = 256.0
    private const val FOLLOW = 0xFF7FD8FF.toInt()
    private const val PINNED = 0xFF4FB3FF.toInt()
    /** The side the asset's front (its north as saved) faces, per quarter turn clockwise. */
    private val FRONT = listOf(Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST)

    var asset: LibraryAsset? = null
        private set
    /** Quarter turns clockwise seen from above (`paste_asset`'s rotate / 90). */
    private var turns = 0
    /** Where the bottom center goes while following: the block on the face looked at. */
    private var aimed: BlockPos? = null
    private var pinned: BlockPos? = null
    private var pasting = false
    /** The last paste's outcome and when, on the HUD a few seconds. */
    private var outcome: Pair<String, Long>? = null

    /** Arrows on the pinned spot, by whole blocks: an asset is pasted on blocks. */
    private val gizmo = TransformGizmo(
        frame = { pinned?.let { GizmoFrame(Vec3(it.x + 0.5, it.y.toDouble(), it.z + 0.5), GizmoFrame.WORLD_AXES) } },
        changed = ::nudged, committed = ::nudged, cancelled = { dragFrom?.let { pinned = it } },
        rings = emptyList(), wholeBlocks = { true },
    )
    /** Where the pin was when the drag started. */
    private var dragFrom: BlockPos? = null

    private fun nudged(change: GizmoChange) {
        val offset = (change as? GizmoChange.Move)?.offset ?: return
        val from = dragFrom ?: pinned?.also { dragFrom = it } ?: return
        pinned = from.offset(offset.x.roundToInt(), offset.y.roundToInt(), offset.z.roundToInt())
    }

    /** Starts placing [asset], its front toward the player. */
    fun start(asset: LibraryAsset) {
        this.asset = asset
        pinned = null
        outcome = null
        turns = Minecraft.getInstance().player?.direction?.get2DDataValue() ?: 0
        AssetGhost.load(asset)
        Tools.activate(this)
    }

    override fun keys() = placingKeys() + EditorKey(InputConstants.KEY_N, "Pick an asset: the library") { Screens.open(HubScreen(HubScreen.Section.LIBRARY)) }

    override fun snapNote() = "Assets go on whole blocks"

    override fun available() = Bridge.connected

    private val position get() = pinned ?: aimed

    override fun turn(quarters: Int) {
        turns = (turns + quarters).mod(4)
    }

    /** The blocks [asset] fills with its bottom center at [at], turned: what `paste_asset` returns. */
    fun footprint(asset: LibraryAsset, at: BlockPos, turns: Int): BlockBox {
        var x0 = -(asset.sizeX - 1) / 2
        var z0 = -(asset.sizeZ - 1) / 2
        var x1 = x0 + asset.sizeX - 1
        var z1 = z0 + asset.sizeZ - 1
        repeat(turns) {
            // A quarter turn clockwise seen from above: north (-z) goes east (+x).
            val (a, b) = -z0 to x0
            val (c, d) = -z1 to x1
            x0 = a; z0 = b; x1 = c; z1 = d
        }
        return BlockBox.of(at.x + x0, at.y, at.z + z0, at.x + x1, at.y + asset.sizeY - 1, at.z + z1)
    }

    override fun place() = paste()

    fun paste() {
        val asset = asset ?: return
        val at = position ?: return Chat.warn("Look at a block to place ${asset.name}")
        if (pasting) return
        pasting = true
        Library.paste(asset, at, turns * 90) { result ->
            pasting = false
            val now = System.currentTimeMillis()
            result.onSuccess { text ->
                outcome = "§a✔ Pasted ${asset.name}" to now
                Chat.success("${text.lineSequence().firstOrNull().orEmpty()} (/foreman undo to take it back)")
                gizmo.cancel()
                dragFrom = null
                pinned = null
            }.onFailure {
                outcome = "§c✖ ${it.message.orEmpty().take(60)}" to now
                Chat.error("Couldn't paste ${asset.name}: ${it.message}")
            }
        }
    }

    override fun press(button: MouseButton, ray: Ray) {
        when (button) {
            MouseButton.LEFT -> when {
                pinned != null && gizmo.press(ray) -> dragFrom = pinned
                pinned != null -> pinned = null
                else -> Tools.deactivate()
            }
            MouseButton.RIGHT -> if (pinned == null) pinned = aimed else paste()
        }
    }

    override fun release(button: MouseButton) {
        if (button != MouseButton.LEFT) return
        gizmo.release()
        dragFrom = null
    }

    override fun update(ray: Ray) {
        // The block on the face looked at, however far: assets are often placed from a distance.
        if (pinned != null) gizmo.update(ray) else aimed = ray.block(REACH)?.let { it.blockPos.relative(it.direction) }
        AssetGhost.show(position, turns)
    }

    override fun scroll(amount: Double): Boolean {
        if (!(gizmo.dragging && gizmo.nudge(sign(amount).toInt()))) turn(if (amount < 0) 1 else -1)
        return true
    }

    override fun escape(): Boolean {
        Tools.deactivate()
        return true
    }

    override fun deactivated() {
        AssetGhost.clear()
        gizmo.cancel()
        dragFrom = null
        pinned = null
        aimed = null
    }

    override fun draw(draw: OverlayDraw) {
        val asset = asset ?: return
        val at = position ?: return
        val box = footprint(asset, at, turns)
        val color = if (pasting) Colors.YELLOW else if (pinned != null) PINNED else FOLLOW
        AIOverlay.box(draw, box, color, 3f, Colors.withAlpha(color, 0.08f), onTop = true)
        // Its front: an arrow on the ground, out of the side it faces.
        val front = FRONT[turns]
        val center = box.center
        val edge = Vec3(center.x + front.stepX * box.sizeX / 2.0, box.minY + 0.05, center.z + front.stepZ * box.sizeZ / 2.0)
        draw.arrow(edge, edge.add(front.stepX * 1.5, 0.0, front.stepZ * 1.5), color, onTop = true)
        draw.label(AIOverlay.topCenter(box), listOf(asset.name to color, "${turns * 90}° · ${at.x} ${at.y} ${at.z}" to Colors.WHITE))
        if (pinned != null) gizmo.draw(draw)
    }

    override fun status(): ToolStatus {
        val asset = asset ?: return ToolStatus(empty = "Pick an asset in the hub's Library [N]")
        val at = position
        val details = listOf("${asset.sizeLabel} · turned ${turns * 90}° · " + (at?.let { "at ${it.x} ${it.y} ${it.z}" + if (pinned != null) " (pinned)" else "" } ?: "look at a block"))
        val hints = if (pinned == null) listOf(
            Hint(ToolStatus.RIGHT, "pin it here"),
            Hint(ToolStatus.SCROLL, "turn it"),
            Hint(ToolStatus.LEFT, "leave the tool"),
        ) else listOf(
            Hint(ToolStatus.RIGHT, "paste it here"),
            Hint(ToolStatus.DRAG, "an arrow nudges it"),
            Hint(ToolStatus.SCROLL, "turn it"),
            Hint(ToolStatus.LEFT, "unpin it"),
        )
        val notes = listOfNotNull(
            if (pasting) Note("Pasting…", Note.Tone.BUSY) else outcome?.takeIf { System.currentTimeMillis() - it.second < 6000 }?.let { Note.coloured(it.first) },
            AssetGhost.note(),
        )
        return ToolStatus(
            Shown("Asset", asset.name, "to paste"), details = details, hints = hints, notes = notes,
            prompt = if (pinned != null && !pasting) "Pinned: right click to paste it" else null,
        )
    }
}
