package games.qore.foreman.client.models

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.math.Transformation
import games.qore.foreman.client.display.DisplayEditor
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Note
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Placing
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.resources.Identifier
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Quaternionf
import org.joml.Vector3f
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Puts an item model in the world as a display entity, or as furniture when the server says the model is one. A ghost
 * of it (an item display only this client has, never sent to the server, posed as `spawn_display` poses the display:
 * `fixed` item transform, lifted so its lowest point is at the display's position) follows the block face looked at,
 * resting on it: centered on the block with block snapping, else where the crosshair is, to the move step (Ctrl: free).
 * Scroll turns it by the turn step (15° when free; Shift: 90°), R a quarter, Alt + scroll scales it by the scale step
 * (an eighth when free). Right click or Enter places it with the server's `spawn_display` (or `place_furniture`), one undo step;
 * the tool stays on for the next one. With Shift, the display placed goes to the display editor, selected. Left click
 * or Esc leaves; N picks another in the hub's model browser.
 */
object ModelPlacementTool : Placing {
    override val title = "Place model"
    override val id = "models"
    private const val REACH = 256.0
    private const val COLOR = 0xFF7FD8FF.toInt()
    /** The ghost's entity id: negative, never one the server gives. */
    private const val GHOST_ID = -0x464D4D31
    /** Scroll steps while the editor's snapping is free. */
    private const val STEP = 15.0
    private const val SCALE_STEP = 0.125
    private val HALF_TURN = Quaternionf().rotationY(Math.PI.toFloat())
    private const val ADJUSTED_MS = 4000L

    var model: Identifier? = null
        private set
    /** The furniture it is placed as, or null for a display. */
    var furniture: String? = null
        private set
    /** Degrees, as `spawn_display` and `place_furniture` take it: the model's front (its north side) faces a player looking this way. */
    private var yaw = 0f
    private var scale = 1f
    /** The box the model fills (its `fixed` transform applied) around the point it is drawn at; null when it draws nothing. */
    private var bounds: AABB? = null
    private var ghost: Display.ItemDisplay? = null
    /** Where the display goes now, and the block furniture is rooted in. */
    private var at: Vec3? = null
    private var root: BlockPos? = null
    private var placing = false
    /** The last placement's outcome and when, on the HUD a few seconds. */
    private var outcome: Pair<String, Long>? = null
    /** What the scroll wheel changed last and when: the snap key cycles its step a while. */
    private var adjusted = Snapping.Kind.MOVE to 0L

    /** Starts placing [model] (as [furniture] when given), its front toward the player. */
    fun start(model: Identifier, furniture: String? = null) {
        removeGhost()
        this.model = model
        this.furniture = furniture
        bounds = ModelPreview.bounds(model, ItemDisplayContext.FIXED)
        scale = 1f
        outcome = null
        val player = Minecraft.getInstance().player
        yaw = player?.let { (it.yRot / 90f).roundToInt() * 90f }?.mod(360f) ?: 0f
        Tools.activate(this)
    }

    override fun keys() = placingKeys() + listOf(
        EditorKey(InputConstants.KEY_RETURN, "Place it, then edit it", shift = true) { place() },
        EditorKey(InputConstants.KEY_N, "Pick a model: the browser") { Screens.open(HubScreen(HubScreen.Section.MODELS)) },
    )

    override fun available() = Bridge.connected

    override fun snapNote() = if (furniture != null) "Furniture: whole blocks" else null

    val name get() = model?.path?.substringAfterLast('/') ?: ""

    override fun turn(quarters: Int) = turnBy(90f * quarters)

    private fun turnBy(degrees: Float) {
        yaw = (yaw + degrees).mod(360f)
    }

    override fun place() {
        val edit = held(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT)
        val model = model ?: return
        // Where the crosshair is now, not at the last frame.
        update(Ray.crosshair())
        val at = at ?: return Chat.warn("Look at a block to place $name")
        if (placing) return
        placing = true
        val furniture = furniture
        if (furniture != null) ItemModels.placeFurniture(furniture, root ?: BlockPos.containing(at), yaw) { result ->
            done(result.map { it.lineSequence().firstOrNull().orEmpty() }, furniture)
        } else ItemModels.spawnDisplay(model, at, yaw, scale) { result ->
            done(result.map { it.text.lineSequence().firstOrNull().orEmpty() }, name)
            result.getOrNull()?.uuid?.takeIf { edit }?.let { DisplayEditor.select(listOf(it)) }
        }
    }

    private fun done(result: Result<String>, what: String) {
        placing = false
        val now = System.currentTimeMillis()
        result.onSuccess { text ->
            outcome = "§a✔ Placed $what" to now
            Chat.success("Placed $what${if (text.isNotBlank() && !text.startsWith("{")) ": $text" else ""} (/foreman undo to take it back)")
        }.onFailure {
            outcome = "§c✖ ${it.message.orEmpty().take(60)}" to now
            Chat.error("Couldn't place $what: ${it.message}")
        }
    }

    override fun press(button: MouseButton, ray: Ray) {
        when (button) {
            MouseButton.LEFT -> Tools.deactivate()
            MouseButton.RIGHT -> place()
        }
    }

    override fun scroll(amount: Double): Boolean {
        val up = amount > 0
        when {
            furniture == null && held(InputConstants.KEY_LALT, InputConstants.KEY_RALT) -> {
                adjusted = Snapping.Kind.SCALE to System.currentTimeMillis()
                val unit = Config.settings.editor.scaleStep.takeIf { it > 0 } ?: SCALE_STEP
                val step = if (held(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT)) 1.0 else unit
                scale = (((scale + if (up) step else -step) / unit).roundToInt().coerceIn(1, (16 / unit).roundToInt()) * unit).toFloat()
                updateGhost()
            }
            held(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT) -> turnBy(if (up) -90f else 90f)
            else -> {
                adjusted = Snapping.Kind.TURN to System.currentTimeMillis()
                (Config.settings.editor.turnStep.takeIf { it > 0 } ?: STEP).toFloat().let { turnBy(if (up) -it else it) }
            }
        }
        return true
    }

    private fun held(vararg keys: Int) = keys.any(Mc::isKeyDown)

    override fun escape(): Boolean {
        Tools.deactivate()
        return true
    }

    override fun update(ray: Ray) {
        val hit = target(ray)
        at = hit?.let(::anchor)
        root = hit?.let { it.blockPos.relative(it.direction) }
        updateGhost()
    }

    /** The block face looked at, however far: models are often placed from a distance. */
    private fun target(ray: Ray): BlockHitResult? = ray.block(REACH)

    /**
     * The display's position for [hit]: the model's box against the face looked at, on its outer side, centered on the
     * face's block with block snapping, else on the crosshair (to the move step). Furniture stands in the block before
     * the face, on its floor.
     */
    private fun anchor(hit: BlockHitResult): Vec3 {
        val box = box(Vec3.ZERO) ?: AABB(-0.5, -0.5, -0.5, 0.5, 0.5, 0.5)
        if (furniture != null) {
            val root = hit.blockPos.relative(hit.direction)
            return Vec3(root.x + 0.5 - box.center.x, root.y - box.minY, root.z + 0.5 - box.center.z)
        }
        val target = if (Snapping.onGrid) Vec3.atCenterOf(hit.blockPos) else Snapping.position(hit.location)
        val face = hit.direction
        fun along(axis: Direction.Axis, center: Double, min: Double, max: Double) = when {
            face.axis != axis -> target.get(axis) - center
            face.axisDirection == Direction.AxisDirection.POSITIVE -> hit.location.get(axis) - min
            else -> hit.location.get(axis) - max
        }
        return Vec3(
            along(Direction.Axis.X, box.center.x, box.minX, box.maxX),
            along(Direction.Axis.Y, box.center.y, box.minY, box.maxY),
            along(Direction.Axis.Z, box.center.z, box.minZ, box.maxZ),
        )
    }

    /** How far the display lifts the model so its lowest point is at the display's position, as `spawn_display` does. */
    private val lift get() = -(bounds?.minY ?: -0.5)

    /**
     * The world box the model fills with its display at [at]: lifted, turned by the yaw (the display's own half turn
     * and its renderer's cancel out), scaled.
     */
    private fun box(at: Vec3): AABB? {
        val b = bounds ?: return null
        val angle = Math.toRadians(-yaw.toDouble())
        val c = cos(angle)
        val s = sin(angle)
        var min = Vec3(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE)
        var max = Vec3(-Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (x in listOf(b.minX, b.maxX)) for (y in listOf(b.minY, b.maxY)) for (z in listOf(b.minZ, b.maxZ)) {
            val p = Vec3((x * c + z * s) * scale, (y + lift) * scale, (-x * s + z * c) * scale)
            min = Vec3(minOf(min.x, p.x), minOf(min.y, p.y), minOf(min.z, p.z))
            max = Vec3(maxOf(max.x, p.x), maxOf(max.y, p.y), maxOf(max.z, p.z))
        }
        return AABB(min.add(at), max.add(at))
    }

    /** The ghost where the model goes, made when missing (or in another world), hidden while no block is looked at. */
    private fun updateGhost() {
        val model = model ?: return
        val level = Minecraft.getInstance().level ?: return
        val at = at ?: return removeGhost()
        val ghost = ghost?.takeIf { it.level() === level && !it.isRemoved } ?: Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level).also {
            it.id = GHOST_ID
            it.setItemStack(ItemModels.stack(model))
            it.setItemTransform(ItemDisplayContext.FIXED)
            // Seen as far as it can be placed.
            it.setViewRange((REACH / 64).toFloat())
            it.snapTo(at.x, at.y, at.z, yaw, 0f)
            level.addEntity(it)
            ghost = it
        }
        ghost.setTransformation(Transformation(Vector3f(0f, (lift * scale).toFloat(), 0f), HALF_TURN, Vector3f(scale), null))
        ghost.snapTo(at.x, at.y, at.z, yaw, 0f)
        ghost.setOldPosAndRot()
    }

    private fun removeGhost() {
        val ghost = ghost ?: return
        this.ghost = null
        val level = Minecraft.getInstance().level
        if (ghost.level() === level) level?.removeEntity(ghost.id, Entity.RemovalReason.DISCARDED)
    }

    override fun deactivated() {
        removeGhost()
        at = null
        root = null
    }

    override fun draw(draw: OverlayDraw) {
        val at = at ?: return
        val box = box(at) ?: return
        val color = if (placing) Colors.YELLOW else COLOR
        draw.box(Vec3(box.minX, box.minY, box.minZ), Vec3(box.maxX, box.maxY, box.maxZ), color, 2f, Colors.withAlpha(color, 0.05f), onTop = true)
        // Its front: an arrow on the ground, out of the side it faces (toward a player looking at the yaw).
        val front = Vec3(sin(Math.toRadians(yaw.toDouble())), 0.0, -cos(Math.toRadians(yaw.toDouble())))
        val reach = maxOf(box.xsize, box.zsize) / 2
        val base = Vec3(box.center.x, box.minY + 0.05, box.center.z).add(front.scale(reach))
        draw.arrow(base, base.add(front.scale(1.0)), color, onTop = true)
        draw.label(Vec3(box.center.x, box.maxY + 0.6, box.center.z), listOf((furniture ?: name) to color, "${yaw.roundToInt()}° · ×${fmt(scale)}" to Colors.WHITE))
    }

    /** The scale step while Alt is held or just after a scale, the turn step just after a turn, else the move step. */
    override fun snap() = when {
        furniture == null && held(InputConstants.KEY_LALT, InputConstants.KEY_RALT) -> Snapping.Kind.SCALE
        System.currentTimeMillis() - adjusted.second < ADJUSTED_MS -> adjusted.first
        else -> Snapping.Kind.MOVE
    }

    override fun status(): ToolStatus {
        val model = model ?: return ToolStatus(empty = "Pick a model in the hub's Models page [N]")
        val at = at
        val selected = furniture?.let { Shown("Furniture", it, "model $model") } ?: Shown("Model", model.toString(), "as a display")
        val details = listOf("${yaw.roundToInt()}° · ×${fmt(scale)}" + (at?.let { " · at ${fmt(it.x)} ${fmt(it.y)} ${fmt(it.z)}" } ?: " · look at a block"))
        val hints = listOfNotNull(
            Hint(ToolStatus.RIGHT, if (furniture == null) "place it here (Shift: and edit it)" else "place it here"),
            Hint(ToolStatus.SCROLL, "turn it (Shift: 90°)"),
            if (furniture == null) Hint("Alt+scroll", "scale it") else null,
            Hint(ToolStatus.LEFT, "leave the tool"),
        )
        val notes = listOfNotNull(if (placing) Note("Placing…", Note.Tone.BUSY) else outcome?.takeIf { System.currentTimeMillis() - it.second < 6000 }?.let { Note.coloured(it.first) })
        return ToolStatus(selected, details = details, hints = hints, notes = notes)
    }

    private fun fmt(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')

    private fun fmt(v: Float) = fmt(v.toDouble())
}
