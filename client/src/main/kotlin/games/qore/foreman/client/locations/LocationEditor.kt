package games.qore.foreman.client.locations

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.editor.EditorKey
import games.qore.foreman.client.editor.EditorTool
import games.qore.foreman.client.editor.Hint
import games.qore.foreman.client.editor.Shown
import games.qore.foreman.client.editor.ToolStatus
import games.qore.foreman.client.gizmo.GizmoChange
import games.qore.foreman.client.gizmo.GizmoFrame
import games.qore.foreman.client.gizmo.Snapping
import games.qore.foreman.client.gizmo.TransformGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.data.LocationType
import games.qore.foreman.api.packet.LocationRelocatePacket
import games.qore.foreman.api.packet.LocationUpdateDisplayPacket
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.util.Mth
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.Locale
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin

/**
 * Edits locations: left click selects the one under the crosshair or grabs the gizmo (its arrows move it as the editor's
 * snapping says, a block location by whole blocks; its ring turns its yaw), right click puts the selection on the face
 * of the block looked at. Every committed change is sent to the server (local sets are saved).
 */
object LocationEditor : EditorTool {
    override val title = "Location editor"
    override val id = "locations"

    var selected: LocationRef? = null
        private set
    private var lastKnown: Pair<String, Vec3>? = null
    var gizmoEnabled = true

    private val gizmo = TransformGizmo(
        frame = { selectedLocation()?.entry?.anchor()?.let { GizmoFrame(it, GizmoFrame.WORLD_AXES) } },
        changed = ::dragged, committed = ::dropped, cancelled = ::undrag,
        rings = listOf(1), ringsWithMove = true, grid = { Snapping.Grid.FLOOR },
        wholeBlocks = { selectedLocation()?.entry?.type == LocationType.BLOCK },
    )
    /** The selection as it was when the drag started. */
    private var dragStart: LocationEntry? = null

    val enabled get() = Tools.active === this

    override fun keys() = listOf(
        EditorKey(InputConstants.KEY_K, "Move it to your feet") { moveToPlayer(PlayerAnchor.FEET) },
        EditorKey(InputConstants.KEY_U, "Move it to your eyes") { moveToPlayer(PlayerAnchor.EYES) },
        EditorKey(InputConstants.KEY_J, "Move it to your block") { moveToPlayer(PlayerAnchor.BLOCK) },
        EditorKey(InputConstants.KEY_H, if (gizmoEnabled) "Hide the gizmo" else "Show the gizmo") { gizmoEnabled = !gizmoEnabled },
        EditorKey(InputConstants.KEY_N, "Sources and categories") { Screens.open(HubScreen(HubScreen.Section.LOCATIONS)) },
    )

    fun init() {
        LocationStore.onChange { revalidate() }
    }

    fun selectedLocation(): Located? = selected?.let(LocationStore::get)

    /** [change] from where the drag started, shown at once: moved, or turned around y (its yaw; right-handed, so the other way). */
    private fun dragged(change: GizmoChange) {
        val ref = selected ?: return
        val start = dragStart ?: selectedLocation()?.entry?.also { dragStart = it } ?: return
        val after = when (change) {
            is GizmoChange.Move -> start.atAnchor(start.anchor().add(change.offset))
            is GizmoChange.Rotate -> start.copy(yaw = Mth.wrapDegrees(start.yaw - Math.toDegrees(change.angle).toFloat()))
            is GizmoChange.Scale -> start
        }
        LocationStore.update(ref, notify = false) { after }
    }

    private fun dropped(change: GizmoChange) {
        dragged(change)
        val before = dragStart ?: return
        dragStart = null
        selectedLocation()?.let { commitMove(before, it.entry) }
    }

    private fun undrag() {
        val before = dragStart ?: return
        dragStart = null
        selected?.let { ref -> LocationStore.update(ref, notify = false) { before } }
    }

    fun select(ref: LocationRef?) {
        gizmo.cancel()
        selected = ref
        lastKnown = ref?.let(LocationStore::get)?.let { it.entry.world to it.pos }
    }

    /** Keeps the selection when the server replaces the category it is in (same place, maybe a new id). */
    private fun revalidate() {
        val ref = selected ?: return
        LocationStore[ref]?.let { lastKnown = it.entry.world to it.pos; return }
        val (world, pos) = lastKnown ?: return select(null)
        select(LocationStore.visible(world).firstOrNull { it.pos.distanceTo(pos) < 0.1 }?.ref)
    }

    /** The visible location nearest along [ray] within 1.5 blocks of it. */
    fun pick(ray: Ray): Located? {
        val dimension = playerDimension() ?: return null
        return LocationStore.visible(dimension)
            .mapNotNull { l -> ray.toPoint(l.entry.anchor().add(0.0, 0.5, 0.0)).takeIf { (d, t) -> d < 1.5 && t < 128 }?.let { l to it.second } }
            .minByOrNull { it.second }?.first
    }

    override fun press(button: MouseButton, ray: Ray) {
        when (button) {
            MouseButton.LEFT -> if (!(gizmoEnabled && selected != null && gizmo.press(ray))) select(pick(ray)?.ref)
            MouseButton.RIGHT -> placeOnTargetedFace()
        }
    }

    override fun release(button: MouseButton) {
        if (button == MouseButton.LEFT) gizmo.release()
    }

    override fun update(ray: Ray) {
        if (gizmoEnabled && selected != null) gizmo.update(ray)
    }

    override fun snapNote() = if (selectedLocation()?.entry?.type == LocationType.BLOCK) "Block locations: whole blocks" else null

    override fun scroll(amount: Double): Boolean = gizmo.dragging && gizmo.nudge(sign(amount).toInt())

    override fun deactivated() {
        gizmo.cancel()
    }

    override fun draw(draw: OverlayDraw) {
        val located = selectedLocation() ?: return
        val anchor = located.entry.anchor()
        val pulse = (sin(System.currentTimeMillis() * 0.006) * 0.3 + 0.7).toFloat()
        val r = if (located.entry.type == LocationType.BLOCK) 0.55 else located.entry.display.size * 0.75
        draw.box(anchor.add(-r, -0.05, -r), anchor.add(r, LocationLayer.height(located.entry) + 0.1, r), Colors.withAlpha(Colors.WHITE, pulse), 3f, onTop = true)
        if (gizmoEnabled) gizmo.draw(draw)
    }

    override fun snap() = gizmo.snapKind

    override fun status(): ToolStatus {
        val located = selectedLocation()
        val e = located?.entry
        val selected = located?.let { Shown(if (it.entry.type == LocationType.BLOCK) "Block location" else "Location", it.entry.display.name ?: it.entry.id, "${it.source} · ${it.category}") }
        val details = listOfNotNull(
            e?.let { "At %.2f %.2f %.2f · yaw %.0f · id %s".format(Locale.ROOT, it.x, it.y, it.z, it.yaw, it.id) },
            gizmo.describe()?.takeIf { gizmo.dragging }?.let { "Dragged: $it" },
        )
        val hints = when {
            gizmo.dragging -> listOf(Hint("Ctrl", "drag freely"), Hint(ToolStatus.SCROLL, if (gizmo.movingFreely) "farther, nearer" else "one step more"))
            gizmo.hovering -> listOf(Hint(ToolStatus.DRAG, if (gizmo.movingFreely) "move it anywhere" else "an arrow moves it, the ring turns it"))
            else -> listOfNotNull(
                Hint(ToolStatus.LEFT, "on a location: select it"),
                if (e != null) Hint(ToolStatus.RIGHT, "put it on the block face aimed at") else null,
                if (e != null && gizmoEnabled) Hint(ToolStatus.DRAG, "an arrow moves it, the ring turns it") else null,
            )
        }
        return ToolStatus(selected, "Left click a location to select it", details, hints = hints)
    }

    /** Moves the selection to [anchor] (and [yaw]/[pitch]) and commits it. */
    fun moveTo(anchor: Vec3, yaw: Float? = null, pitch: Float? = null): Boolean {
        val located = selectedLocation() ?: return false
        val before = located.entry
        val after = before.atAnchor(anchor).copy(yaw = yaw ?: before.yaw, pitch = pitch ?: before.pitch)
        LocationStore.update(located.ref, notify = false) { after }
        commitMove(before, after)
        return true
    }

    /** Saves the move from [before] to the selection's current state: local sets are written, servers are told. */
    private fun commitMove(before: LocationEntry, after: LocationEntry) {
        val ref = selected ?: return
        LocationStore.update(ref) { it }
        lastKnown = after.world to Vec3(after.x, after.y, after.z)
        if (!LocalSets.isLocal(ref.source)) sendRelocate(ref, ref, before, after)
    }

    private fun sendRelocate(from: LocationRef, to: LocationRef, before: LocationEntry, after: LocationEntry) {
        LocationChannels.relocate.send(LocationRelocatePacket(
            from.id, from.source, from.category, to.source, to.category, before.world,
            before.x, before.y, before.z, before.yaw, before.pitch, after.x, after.y, after.z, after.yaw, after.pitch,
        ))
    }

    fun moveToPlayer(mode: PlayerAnchor): Boolean {
        val player = Minecraft.getInstance().player ?: return false
        return when (mode) {
            PlayerAnchor.FEET -> moveTo(Vec3(player.x, player.y, player.z), player.yRot, player.xRot)
            PlayerAnchor.EYES -> moveTo(Vec3(player.x, player.eyeY, player.z), player.yRot, player.xRot)
            PlayerAnchor.BLOCK -> moveTo(Vec3(floor(player.x) + 0.5, floor(player.y), floor(player.z) + 0.5), Ray.facing(Direction.UP, player.yRot), 0f)
        }
    }

    enum class PlayerAnchor { FEET, EYES, BLOCK }

    private fun placeOnTargetedFace() {
        val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val pos = hit.blockPos.relative(hit.direction)
        moveTo(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5), Ray.facing(hit.direction, Minecraft.getInstance().player?.yRot ?: 0f), 0f)
    }

    fun rename(name: String?): Boolean {
        val ref = selected ?: return false
        if (!LocationStore.update(ref) { it.copy(display = it.display.copy(name = name)) }) return false
        if (!LocalSets.isLocal(ref.source)) LocationChannels.updateDisplay.send(LocationUpdateDisplayPacket(ref.id, ref.source, ref.category, name))
        return true
    }

    /** Moves the selection to another source and/or category. Local and server sources can't be swapped. */
    fun recategorize(source: String, category: String): String? {
        val located = selectedLocation() ?: return "No location selected"
        if (LocalSets.isLocal(located.source) != LocalSets.isLocal(source)) return "Can't move a location between a local set and a server source"
        val to = LocationStore.move(located.ref, source, category) ?: return "Location not found"
        if (!LocalSets.isLocal(source)) sendRelocate(located.ref, to, located.entry, located.entry)
        selected = to
        return null
    }

    fun deleteSelected(): Located? {
        val located = selectedLocation() ?: return null
        LocationStore.remove(located.ref)
        select(null)
        return located
    }
}
