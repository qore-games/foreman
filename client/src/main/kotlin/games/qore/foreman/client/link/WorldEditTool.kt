package games.qore.foreman.client.link

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.config.Config
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
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import net.minecraft.core.BlockPos
import kotlin.math.abs

/**
 * Edits the WorldEdit selection in the world, in any of its shapes: left / right click a block for `//pos1` and
 * `//pos2` (WorldEdit's own meaning for the selector: corners, a center and its radius, polygon points), drag the
 * gizmo's arrows to move it (`//shift`) and its knobs to resize it (selected again from scratch, stretched). Shapes
 * around a center grow on both sides. What the gizmo shows stays until WorldEdit has answered.
 */
object WorldEditTool : EditorTool {
    override val title = "WorldEdit selection"
    override val id = "worldedit"

    override fun keys() = listOf(
        EditorKey(InputConstants.KEY_DELETE, "Clear it") { WorldEditSelection.clear() },
        EditorKey(InputConstants.KEY_V, if (shown) "Hide the selection" else "Show the selection") {
            Config.update { overlays.worldEdit = !overlays.worldEdit }
        },
        EditorKey(InputConstants.KEY_N, "The hub's Selection page") { Screens.open(HubScreen(HubScreen.Section.SELECTION)) },
    )

    /** Not while WorldEdit CUI (or WorldEdit) is installed: it owns the selection. */
    override fun available() = WorldEditSelection.available

    override fun snapNote() = "The selection: whole blocks"

    /** The shape as dragged, shown until WorldEdit sends the result. */
    private var preview: WeShape? = null
    /** The shape when the drag started. */
    private var from: WeShape? = null

    /** Whether the selection is shown (the hub's switch too): hidden, neither it nor its gizmo is drawn or grabbed. */
    private val shown get() = Config.settings.overlays.worldEdit

    private val gizmo = BoxGizmo({ (preview ?: WorldEditSelection.shape())?.bounds?.takeIf { shown } }, ::changed, ::committed)

    private fun changed(next: BlockBox) {
        val start = from ?: (preview ?: WorldEditSelection.shape())?.also { from = it } ?: return
        preview = start.fitted(start.constrain(start.bounds, next))
    }

    private fun committed(next: BlockBox) {
        val start = from ?: return
        from = null
        val shape = preview ?: return
        val a = start.bounds
        val b = shape.bounds
        val moved = a.sizeX == b.sizeX && a.sizeY == b.sizeY && a.sizeZ == b.sizeZ
        WorldEditSelection.run(if (moved) shift(b.minX - a.minX, b.minY - a.minY, b.minZ - a.minZ) else shape.commands())
    }

    /** `//shift` along each axis that moved. */
    private fun shift(dx: Int, dy: Int, dz: Int) = listOfNotNull(
        dx.takeIf { it != 0 }?.let { "/shift ${abs(it)} ${if (it > 0) "east" else "west"}" },
        dy.takeIf { it != 0 }?.let { "/shift ${abs(it)} ${if (it > 0) "up" else "down"}" },
        dz.takeIf { it != 0 }?.let { "/shift ${abs(it)} ${if (it > 0) "south" else "north"}" },
    )

    override fun press(button: MouseButton, ray: Ray) {
        if (button == MouseButton.LEFT && gizmo.press(ray)) return
        val p = target(ray) ?: return
        WorldEditSelection.run(listOf("/pos${if (button == MouseButton.LEFT) 1 else 2} ${p.x},${p.y},${p.z}"))
    }

    /** The block under the crosshair, however far: a selection is often bigger than the player's reach. */
    private fun target(ray: Ray): BlockPos? = ray.block(REACH)?.blockPos

    private const val REACH = 256.0

    override fun release(button: MouseButton) {
        if (button != MouseButton.LEFT) return
        gizmo.release()
        // A drag that changed nothing doesn't commit.
        if (from != null) {
            from = null
            preview = null
        }
    }

    override fun update(ray: Ray) {
        if (from == null && preview != null && !WorldEditSelection.busy) preview = null
        gizmo.update(ray)
    }

    override fun deactivated() {
        gizmo.cancel()
        from = null
        preview = null
    }

    override fun draw(draw: OverlayDraw) {
        if (!shown) return
        val shape = preview ?: WorldEditSelection.shape() ?: return
        WorldEditSelection.draw(draw, shape)
        gizmo.draw(draw)
    }

    override fun status(): ToolStatus {
        if (!WorldEditSelection.available) return ToolStatus(notes = listOf(Note("WorldEdit CUI is installed: it draws the selection")))
        val shape = WorldEditSelection.shape()
        return ToolStatus(
            selected = shape?.let { Shown("WorldEdit selection", it.label) },
            empty = "No selection from WorldEdit yet: click two blocks",
            hints = when {
                gizmo.dragging -> listOfNotNull(if (gizmo.dragged!!.face >= 0) Hint("Ctrl", "both sides at once") else null)
                gizmo.hovered != null -> if (gizmo.hovered!!.face < 0) listOf(Hint(ToolStatus.DRAG, "//shift it"))
                    else listOf(Hint(ToolStatus.DRAG, "move this face"), Hint("Ctrl", "both sides at once"))
                else -> listOfNotNull(
                    Hint(ToolStatus.LEFT, "a block: //pos1"),
                    Hint(ToolStatus.RIGHT, "a block: //pos2"),
                    if (shape != null) Hint(ToolStatus.DRAG, "a knob resizes it, an arrow moves it") else null,
                )
            },
            notes = listOfNotNull(
                if (WorldEditSelection.busy) Note("Waiting for WorldEdit…", Note.Tone.BUSY) else null,
                if (!shown) Note("Hidden: V shows it") else null,
            ),
        )
    }
}
