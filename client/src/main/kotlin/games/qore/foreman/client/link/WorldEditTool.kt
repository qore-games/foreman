package games.qore.foreman.client.link

import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.gizmo.BoxGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tool
import games.qore.foreman.client.render.OverlayDraw
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import kotlin.math.abs

/**
 * Edits the WorldEdit selection in the world, in any of its shapes: left / right click a block for `//pos1` and
 * `//pos2` (WorldEdit's own meaning for the selector: corners, a center and its radius, polygon points), drag the
 * gizmo's arrows to move it (`//shift`) and its knobs to resize it (selected again from scratch, stretched). Shapes
 * around a center grow on both sides. What the gizmo shows stays until WorldEdit has answered.
 */
object WorldEditTool : Tool {
    override val title = "WorldEdit selection"

    /** The shape as dragged, shown until WorldEdit sends the result. */
    private var preview: WeShape? = null
    /** The shape when the drag started. */
    private var from: WeShape? = null

    private val gizmo = BoxGizmo({ (preview ?: WorldEditSelection.shape())?.bounds }, ::changed, ::committed)

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
    private fun target(ray: Ray): BlockPos? {
        val minecraft = Minecraft.getInstance()
        val level = minecraft.level ?: return null
        val hit = level.clip(ClipContext(ray.origin, ray.at(REACH), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, minecraft.player ?: return null))
        return hit.blockPos.takeIf { hit.type == HitResult.Type.BLOCK }
    }

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
        val shape = preview ?: WorldEditSelection.shape() ?: return
        WorldEditSelection.draw(draw, shape)
        gizmo.draw(draw)
    }

    override fun hud(): List<String> = when {
        !WorldEditSelection.available -> listOf("§7WorldEdit CUI is installed: it draws the selection")
        else -> listOfNotNull(
            WorldEditSelection.shape()?.let { "§f${it.label}" } ?: "§7No selection from WorldEdit yet",
            "§7Left / right click: //pos1 / //pos2",
            if (WorldEditSelection.shape() != null) "§7Drag a knob to resize, an arrow to move" else null,
        )
    }
}
