package games.qore.foreman.client.measure

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.EditorTool
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.abs

/**
 * Measures between two points: a click where it starts, a click where it ends, then a line with its length, how far it
 * goes along each axis and how many blocks it spans (both ends counted). A point is where the crosshair meets a block,
 * snapped as the editor's move snap says (block grid: the block's centre), else in the air as far as the start. Shift
 * on the end goes on from there. The measures stay (shown while it is the current tool) until cleared or the game leaves
 * the server.
 */
object MeasureTool : EditorTool {
    override val title = "Measure"
    override val id = "measure"
    private const val REACH = 256.0
    /** A point in the air this far, when no block is looked at and nothing is started. */
    private const val AIR = 4.0
    private const val KEEP = 32
    private const val COLOR = 0xFFFFD84A.toInt()
    private const val LAST = 0xFF7FD8FF.toInt()

    /** A point measured from or to: where exactly, and the block it counts. */
    data class Mark(val at: Vec3, val block: BlockPos)

    data class Measure(val from: Mark, val to: Mark) {
        val delta: Vec3 get() = to.at.subtract(from.at)
        val length get() = from.at.distanceTo(to.at)
        /** The blocks it spans along x, y and z, both ends counted. */
        val blocks get() = Triple(abs(to.block.x - from.block.x) + 1, abs(to.block.y - from.block.y) + 1, abs(to.block.z - from.block.z) + 1)

        fun lengthLabel() = num(length) + if (length == 1.0) " block" else " blocks"

        fun deltaLabel() = "dx ${signed(delta.x)} · dy ${signed(delta.y)} · dz ${signed(delta.z)}"

        fun blocksLabel() = blocks.let { (x, y, z) -> "$x × $y × $z blocks" }
    }

    private val measures = ArrayList<Measure>()
    /** Where the measure being made starts. */
    private var start: Mark? = null
    /** The point under the crosshair now. */
    private var aim: Mark? = null

    fun init() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { clear() } }
    }

    override fun keys() = listOf(
        EditorKey(InputConstants.KEY_BACKSPACE, "Remove the last measure") { if (start != null) start = null else measures.removeLastOrNull() },
        EditorKey(InputConstants.KEY_DELETE, "Clear every measure") { clear() },
    )

    override fun snapNote() = if (Snapping.onGrid) "Block grid: points on block centres" else null

    private fun clear() {
        measures.clear()
        start = null
    }

    override fun press(button: MouseButton, ray: Ray) {
        if (button == MouseButton.RIGHT) {
            start = null
            return
        }
        val mark = mark(ray) ?: return
        val from = start ?: run {
            start = mark
            return
        }
        if (mark.at == from.at) return
        measures += Measure(from, mark)
        if (measures.size > KEEP) measures.removeAt(0)
        start = if (shift()) mark else null
    }

    private fun shift() = Mc.isKeyDown(InputConstants.KEY_LSHIFT) || Mc.isKeyDown(InputConstants.KEY_RSHIFT)

    override fun update(ray: Ray) {
        aim = mark(ray)
    }

    /** The point [ray] gives: on the block face it meets, else in the air as far as the start. */
    private fun mark(ray: Ray): Mark? {
        val hit = ray.block(REACH)
        if (hit != null) {
            val at = if (Snapping.onGrid) Vec3.atCenterOf(hit.blockPos) else Snapping.position(hit.location)
            return Mark(at, hit.blockPos)
        }
        val at = Snapping.position(ray.at(start?.at?.distanceTo(ray.origin) ?: AIR), Snapping.Grid.CENTRES)
        return Mark(at, BlockPos.containing(at))
    }

    override fun escape(): Boolean {
        if (start == null) return false
        start = null
        return true
    }

    override fun deactivated() {
        start = null
        aim = null
    }

    override fun draw(draw: OverlayDraw) {
        measures.forEachIndexed { i, m -> drawMeasure(draw, m, if (i == measures.lastIndex) LAST else COLOR) }
        val aim = aim ?: return
        draw.point(aim.at, Colors.HOVER, 8f, onTop = true)
        start?.let { drawMeasure(draw, Measure(it, aim), Colors.HOVER) }
    }

    /** The line, its legs along x, y and z, the blocks at its ends, and its numbers at its middle. */
    private fun drawMeasure(draw: OverlayDraw, m: Measure, color: Int) {
        val a = m.from.at
        val b = m.to.at
        listOf(m.from.block, m.to.block).distinct().forEach { draw.blockBox(it.x, it.y, it.z, it.x, it.y, it.z, Colors.withAlpha(color, 0.5f), 1.5f) }
        val x = Vec3(b.x, a.y, a.z)
        val y = Vec3(b.x, b.y, a.z)
        if (listOf(m.delta.x, m.delta.y, m.delta.z).count { abs(it) > 1e-6 } > 1) {
            draw.line(a, x, Colors.withAlpha(Colors.AXIS_X, 0.6f), 1.5f, onTop = true)
            draw.line(x, y, Colors.withAlpha(Colors.AXIS_Y, 0.6f), 1.5f, onTop = true)
            draw.line(y, b, Colors.withAlpha(Colors.AXIS_Z, 0.6f), 1.5f, onTop = true)
        }
        draw.line(a, b, color, 3f, onTop = true)
        draw.point(a, color, 7f, onTop = true)
        draw.point(b, color, 7f, onTop = true)
        val middle = a.add(b).scale(0.5).add(0.0, 0.35, 0.0)
        draw.label(middle, listOf(m.lengthLabel() to color, m.deltaLabel() to Colors.WHITE, m.blocksLabel() to Colors.WHITE), size = 0.8f)
    }

    override fun status(): ToolStatus {
        val from = start
        val live = from?.let { s -> aim?.let { Measure(s, it) } }
        val shown = live ?: measures.lastOrNull()
        val selected = shown?.let { Shown(if (live != null) "Measuring" else "Measure", it.lengthLabel(), if (measures.size > 1 && live == null) "${measures.size} kept" else "") }
        val details = shown?.let { listOf(it.deltaLabel(), it.blocksLabel() + " (both ends counted)") }.orEmpty()
        val hints = if (from == null) listOf(Hint(ToolStatus.LEFT, "start a measure here")) else listOf(
            Hint(ToolStatus.LEFT, "end it here"),
            Hint("Shift+click", "end it and go on from there"),
            Hint(ToolStatus.RIGHT, "cancel it"),
        )
        return ToolStatus(selected, "Click where the measure starts, then where it ends", details, hints = hints)
    }

    private fun num(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }

    private fun signed(v: Double) = num(v).let { if (v > 1e-9) "+$it" else it }
}
