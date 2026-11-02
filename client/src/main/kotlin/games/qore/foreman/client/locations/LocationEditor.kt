package games.qore.foreman.client.locations

import com.mojang.blaze3d.platform.InputConstants
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.gizmo.PointGizmo
import games.qore.foreman.client.input.MouseButton
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.input.Tool
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.data.LocationType
import games.qore.foreman.api.packet.LocationRelocatePacket
import games.qore.foreman.api.packet.LocationUpdateDisplayPacket
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import kotlin.math.floor
import kotlin.math.sign
import kotlin.math.sin

/**
 * Edits locations: left click selects the one under the crosshair or grabs a gizmo arrow, right click puts the
 * selection on the face of the block looked at. Every committed change is sent to the server (local sets are saved).
 */
object LocationEditor : Tool {
    override val title = "Location editor"

    var selected: LocationRef? = null
        private set
    private var lastKnown: Pair<String, Vec3>? = null
    var gizmoEnabled = true

    private val gizmo = PointGizmo(
        position = { selectedLocation()?.entry?.anchor() },
        step = { if (selectedLocation()?.entry?.type == LocationType.BLOCK) 1.0 else Config.settings.locations.snap },
        moved = { anchor -> selected?.let { ref -> LocationStore.update(ref, notify = false) { it.atAnchor(anchor) } } },
        committed = { from, _ ->
            val located = selectedLocation() ?: return@PointGizmo
            commitMove(located.entry.atAnchor(from), located.entry)
        },
    )

    val enabled get() = Tools.active === this

    fun init() {
        LocationStore.onChange { revalidate() }
    }

    fun selectedLocation(): Located? = selected?.let(LocationStore::get)

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
        val dimension = Minecraft.getInstance().level?.dimension()?.identifier()?.toString() ?: return null
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

    override fun scroll(amount: Double): Boolean {
        if (!gizmo.dragging) return false
        val fine = InputConstants.isKeyDown(InputConstants.KEY_LCONTROL)
        gizmo.nudge(sign(amount) * if (fine) 0.0625 else 1.0)
        return true
    }

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

    override fun hud(): List<String> {
        val located = selectedLocation() ?: return listOf("§7Left click a location to select it")
        val e = located.entry
        return listOfNotNull(
            "§fSource: §b${located.source}",
            "§fCategory: §e${located.category}",
            "§7Pos: %.2f, %.2f, %.2f  yaw %.0f".format(e.x, e.y, e.z, e.yaw),
            "§8ID: ${e.id}",
            if (gizmo.dragging) "§cDragging (scroll: ±1, Ctrl: free)" else if (gizmoEnabled) "§7Drag an arrow; right click: onto a block" else null,
        )
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
            PlayerAnchor.BLOCK -> moveTo(Vec3(floor(player.x) + 0.5, floor(player.y), floor(player.z) + 0.5), snapYaw(player.yRot), 0f)
        }
    }

    enum class PlayerAnchor { FEET, EYES, BLOCK }

    private fun placeOnTargetedFace() {
        val hit = Minecraft.getInstance().hitResult as? BlockHitResult ?: return
        if (hit.type != HitResult.Type.BLOCK) return
        val pos = hit.blockPos.relative(hit.direction)
        val yaw = when (hit.direction) {
            Direction.SOUTH -> 0f
            Direction.WEST -> 90f
            Direction.NORTH -> 180f
            Direction.EAST -> -90f
            else -> snapYaw(Minecraft.getInstance().player?.yRot ?: 0f)
        }
        moveTo(Vec3(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5), yaw, 0f)
    }

    private fun snapYaw(yaw: Float): Float {
        val r = Math.floorMod(Math.round(yaw / 90f) * 90, 360).toFloat()
        return if (r > 180f) r - 360f else r
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
