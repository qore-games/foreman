package games.qore.foreman.client.models

import com.mojang.blaze3d.platform.InputConstants
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
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Puts an item model in the world as a display entity, or as furniture when the server says the model is one. A ghost
 * of it (an item display only this client has, never sent to the server) shows it exactly as the placement will: posed
 * as `spawn_display` poses the display (`fixed` item transform, lifted so its lowest point is at the display's position;
 * a model some furniture shows, as that furniture), or as the furniture's plugin places it (the [poses][ItemModels.Poses]
 * the server gives: on a floor at the rotation step the yaw falls on, attached to the ceiling or wall looked at when it
 * goes there). It follows the block face looked at, resting on it: centered on the block with block snapping, else where
 * the crosshair is, to the move step (Ctrl: free); furniture in the block before the face. Scroll turns it by the turn
 * step (15° when free; Shift: 90°; furniture: by its rotation step), R a quarter, Alt + scroll scales it by the scale
 * step (an eighth when free). Right click or Enter places it with the server's `spawn_display` (or `place_furniture`),
 * one undo step; the tool stays on for the next one. With Shift, the display placed goes to the display editor,
 * selected. Left click or Esc leaves; N picks another in the hub's model browser.
 */
object ModelPlacementTool : Placing {
    override val title = "Place model"
    override val id = "models"
    private const val REACH = 256.0
    private const val COLOR = 0xFF7FD8FF.toInt()
    /** Scroll steps while the editor's snapping is free. */
    private const val STEP = 15.0
    private const val SCALE_STEP = 0.125
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
    /** Its ghost's entity id: negative, never one the server gives. */
    private val ghost = ModelGhost(-0x464D4D31, REACH)
    /** Where the display goes now (furniture: its root block's corner), and the block furniture is rooted in. */
    private var at: Vec3? = null
    private var root: BlockPos? = null
    /** How the furniture (or the furniture showing the model) shows, once the server said: null for a plain display. */
    private var poses: ItemModels.Poses? = null
    /** Counts the starts: the poses asked for an earlier model are dropped. */
    private var asked = 0
    /** The face the furniture is attached to (a ceiling, a wall) and the block it is against; null on a floor. */
    private var face: Direction? = null
    private var against: BlockPos? = null
    private var placing = false
    /** The last placement's outcome and when, on the HUD a few seconds. */
    private var outcome: Pair<String, Long>? = null
    /** What the scroll wheel changed last and when: the snap key cycles its step a while. */
    private var adjusted = Snapping.Kind.MOVE to 0L

    /** Starts placing [model] (as [furniture] when given), its front toward the player. */
    fun start(model: Identifier, furniture: String? = null) {
        ghost.remove()
        this.model = model
        this.furniture = furniture
        bounds = ModelPreview.bounds(model, ItemDisplayContext.FIXED)
        scale = 1f
        outcome = null
        poses = null
        face = null
        val player = Minecraft.getInstance().player
        yaw = player?.let { (it.yRot / 90f).roundToInt() * 90f }?.mod(360f) ?: 0f
        val ask = ++asked
        if (Bridge.connected && (furniture != null || ItemModels.furniture.containsKey(model.toString()))) ItemModels.poses(model) { result ->
            if (ask == asked) poses = result.getOrNull()?.let { all -> if (furniture != null) all.firstOrNull { it.id == furniture } else all.firstOrNull() }
        }
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

    /** The yaw it is placed with: on the furniture's rotation step when it shows as one. */
    private val placedYaw get() = poses?.let { it.step(yaw) * 360f / it.rotations } ?: yaw

    override fun place() {
        val edit = held(InputConstants.KEY_LSHIFT, InputConstants.KEY_RSHIFT)
        val model = model ?: return
        // Where the crosshair is now, not at the last frame.
        update(Ray.crosshair())
        val at = at ?: return Chat.warn("Look at a block to place $name")
        if (placing) return
        placing = true
        val furniture = furniture
        val face = face
        val against = against
        val placed = { result: Result<String> -> done(result.map { it.lineSequence().firstOrNull().orEmpty() }, furniture ?: "") }
        if (furniture != null && face != null && against != null) ItemModels.placeFurniture(furniture, against, face, placedYaw, placed)
        else if (furniture != null) ItemModels.placeFurniture(furniture, root ?: BlockPos.containing(at), placedYaw, placed)
        else ItemModels.spawnDisplay(model, at, placedYaw, scale) { result ->
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
            // Furniture turns by its own steps: any less would show nothing.
            poses != null -> poses?.let { p ->
                yaw = placedYaw
                turnBy(if (up) -360f / p.rotations else 360f / p.rotations)
            }
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
        val p = poses
        val placed = furniture != null && p != null
        face = hit?.direction?.takeIf { placed && it != Direction.UP && p!!.faces.containsKey(it) }
        against = hit?.blockPos
        root = hit?.let { it.blockPos.relative(it.direction) }
        at = if (placed) root?.let(Vec3::atLowerCornerOf) else hit?.let(::anchor)
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
     * Where the model shows from [at], its `fixed` transform applied: the furniture's pose (on the face it is attached
     * to, else on a floor) at its rotation step; a display of a model some furniture shows, as that furniture rooted in
     * a block whose floor's centre is [at], scaled; any other display lifted, turned by the yaw, scaled.
     */
    private fun pose(): Matrix4f {
        val p = poses
        if (p != null) {
            if (furniture != null) return p.pose(face, yaw)
            return Matrix4f().scaling(scale).translate(-0.5f, 0f, -0.5f).mul(p.pose(null, yaw))
        }
        return Matrix4f().rotationY(Math.toRadians(-yaw.toDouble()).toFloat()).translate(0f, (lift * scale).toFloat(), 0f).scale(scale)
    }

    /** The world box the model fills with its display at [at], as [pose] shows it. */
    private fun box(at: Vec3): AABB? = bounds?.let { ModelGhost.box(it, pose(), at) }

    /** The ghost where the model goes, hidden while no block is looked at. */
    private fun updateGhost() {
        val model = model ?: return
        val at = at ?: return ghost.remove()
        ghost.show(model, at, pose(), box(at))
    }

    override fun deactivated() {
        ghost.remove()
        at = null
        root = null
        face = null
        against = null
    }

    override fun draw(draw: OverlayDraw) {
        val at = at ?: return
        val box = box(at) ?: return
        val color = if (placing) Colors.YELLOW else COLOR
        draw.box(Vec3(box.minX, box.minY, box.minZ), Vec3(box.maxX, box.maxY, box.maxZ), color, 2f, Colors.withAlpha(color, 0.05f), onTop = true)
        // Its front: an arrow on the ground, out of the side it faces (toward a player looking at the yaw). On a wall,
        // the wall turns it.
        if (face?.axis?.isHorizontal != true) {
            val radians = Math.toRadians(placedYaw.toDouble())
            val front = Vec3(sin(radians), 0.0, -cos(radians))
            val reach = maxOf(box.xsize, box.zsize) / 2
            val base = Vec3(box.center.x, box.minY + 0.05, box.center.z).add(front.scale(reach))
            draw.arrow(base, base.add(front.scale(1.0)), color, onTop = true)
        }
        draw.label(Vec3(box.center.x, box.maxY + 0.6, box.center.z), listOf((furniture ?: name) to color, how() to Colors.WHITE))
    }

    /** How it is placed now: its yaw and scale, or the face it is attached to. */
    private fun how() = when (face?.axis?.isHorizontal) {
        true -> "on the ${face?.serializedName} side"
        false -> "under the ceiling · ${placedYaw.roundToInt()}°"
        null -> "${placedYaw.roundToInt()}°" + if (furniture == null) " · ×${fmt(scale)}" else ""
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
        val details = listOf(how() + (at?.let { " · at ${fmt(it.x)} ${fmt(it.y)} ${fmt(it.z)}" } ?: " · look at a block"))
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
