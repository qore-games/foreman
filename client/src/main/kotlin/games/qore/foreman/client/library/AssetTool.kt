package games.qore.foreman.client.library

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
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.input.playerDimension
import games.qore.foreman.client.link.AIOverlay
import games.qore.foreman.client.link.Area
import games.qore.foreman.client.link.AssetInstance
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.BridgeLayer
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.render.OverlayDraw
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import java.util.Locale

/**
 * Makes and edits the library's assets where they stand. While it is on, the asset areas (areas tagged `asset`) show
 * with their names, the pasted copies with theirs. A new asset is the player's selection ([SelectionTool.current]),
 * named in the [panel][AssetPanel] (`define_asset`). A click selects an asset: its face knobs resize its box
 * (`resize_asset`), its arrows move it with everything in it (`move_asset`, big ones after a yes), keys turn it a
 * quarter in place and fit its box to its content; the panel edits its name, tags and note and lists its copies. A
 * click selects a copy: keys turn it (`rotate_instance`), sync it with its asset (`sync_instance`) or delete it
 * (`delete_instance`), its [panel][CopyPanel] has the rest. Nothing runs while it is off.
 */
object AssetTool : EditorTool {
    override val title = "Asset tool"
    override val id = "assets"
    const val REACH = 256.0
    /** Moves and turns of more blocks than this ask first. */
    private const val BIG = 50_000L
    private const val AREA = 0xFFFFC857.toInt()
    private const val SELECTED = 0xFF4FB3FF.toInt()
    /** How long a change is shown before the server's areas have it. */
    private const val PREVIEW_MS = 4000L
    private const val NOTE_MS = 6000L

    val enabled get() = Tools.active === this

    /** The selected asset area. */
    var selected: AssetRef? = null
        private set
    /** The selected pasted copy's id. */
    var selectedCopy: String? = null
        private set
    /** The selected asset's box as changed here, and until when it is shown if the server's areas don't have it. */
    private var preview: Pair<BlockBox, Long>? = null
    /** A big move or turn, or a deletion, waiting for a yes: its line on the HUD and what it does. */
    private var confirm: Pair<String, () -> Unit>? = null
    /** A change is on its way to the server: the next waits for its answer. */
    var busy = false
        private set
    private var note: Pair<String, Long>? = null
    private var hoveredArea: AssetRef? = null
    var hoveredCopy: String? = null
        private set

    private val gizmo = BoxGizmo(::gizmoBox, ::changed, ::committed)

    fun init() {
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> Minecraft.getInstance().execute { forget() } }
    }

    /** Its keys in editor mode, by name (the panels' tooltips say them). */
    private val editorKeys = linkedMapOf(
        "asset-new" to EditorKey(InputConstants.KEY_C, "New asset from the selection") { create() },
        "asset-panel" to EditorKey(InputConstants.KEY_N, "Panel: the asset or the copy") { openPanel() },
        "asset-turn" to EditorKey(InputConstants.KEY_R, "Turn a quarter, with its content") { turn() },
        "asset-fit" to EditorKey(InputConstants.KEY_F, "Fit its box to its content") { fit() },
        "copy-sync" to EditorKey(InputConstants.KEY_U, "Sync the copy with its asset") { syncCopy() },
        "asset-delete" to EditorKey(InputConstants.KEY_DELETE, "Delete the area or the copy") { delete() },
    )

    override fun keys() = editorKeys.values.toList()

    override fun snapNote() = "Asset boxes: whole blocks"

    override fun available() = Bridge.connected

    /** The key `<name>` is in editor mode (`asset-turn`…). */
    fun keyName(name: String) = editorKeys[name]?.caption ?: "unbound"

    fun toggle() {
        Tools.toggle(this)
    }

    override fun activated() {
        Bridge.need(BridgeLayer.AREAS, true)
        Bridge.need(BridgeLayer.INSTANCES, true)
    }

    /**
     * Turns the tool on and opens a new asset's panel, its box the player's selection (Foreman's, else WorldEdit's); the
     * panel says how to make one when there is none.
     */
    fun create() {
        Tools.activate(this)
        select(null)
        Screens.open(AssetPanel(null))
    }

    /** Turns the tool on with [asset] selected. */
    fun edit(asset: AssetRef) {
        Tools.activate(this)
        select(asset)
    }

    private fun select(asset: AssetRef?) {
        gizmo.cancel()
        cancelConfirm()
        preview = null
        selected = asset
        selectedCopy = null
    }

    private fun selectCopy(copy: AssetInstance) {
        select(null)
        selectedCopy = copy.id
    }

    /** The copy the copy keys work on: the selected one, else the one looked at while no asset is selected. */
    fun copyTarget(): AssetInstance? {
        val id = selectedCopy ?: hoveredCopy.takeIf { selected == null } ?: return null
        return Bridge.instances.firstOrNull { it.id == id }
    }

    /** The asset areas of [dimension]. */
    private fun assetAreas(dimension: String) = Bridge.areas.filter { Library.TAG in it.tags && it.dim == dimension && !it.isPoint }

    // ---- The tool ---------------------------------------------------------------------------------------------------

    override fun press(button: MouseButton, ray: Ray) {
        confirm?.let { (_, action) ->
            if (button == MouseButton.RIGHT) {
                confirm = null
                action()
            } else cancelConfirm()
            return
        }
        if (button != MouseButton.LEFT || gizmo.press(ray)) return
        val dimension = playerDimension() ?: return
        when (val hit = pick(ray, dimension)) {
            is Area -> select(AssetRef(hit.world, hit.name))
            is AssetInstance -> selectCopy(hit)
            else -> select(null)
        }
    }

    override fun release(button: MouseButton) {
        if (button == MouseButton.LEFT) gizmo.release()
    }

    override fun update(ray: Ray) {
        gizmo.update(ray)
        val hit = if (gizmo.dragging || gizmo.hovered != null || confirm != null) null else playerDimension()?.let { pick(ray, it) }
        hoveredArea = (hit as? Area)?.let { AssetRef(it.world, it.name) }
        hoveredCopy = (hit as? AssetInstance)?.id
    }

    /** The asset area or copy under the crosshair. */
    private fun pick(ray: Ray, dimension: String): Any? =
        ray.pick(assetAreas(dimension).map { it to it.bounds } + Bridge.instances.filter { it.inDimension(dimension) }.map { it to it.blockBox }, REACH)

    override fun escape(): Boolean {
        when {
            confirm != null -> cancelConfirm()
            gizmo.dragging -> gizmo.cancel()
            selected != null || selectedCopy != null -> select(null)
            else -> Tools.deactivate()
        }
        return true
    }

    override fun deactivated() {
        gizmo.cancel()
        cancelConfirm()
        hoveredArea = null
        hoveredCopy = null
        Bridge.need(BridgeLayer.AREAS, false)
        Bridge.need(BridgeLayer.INSTANCES, false)
    }

    /** Forgets the server's assets (the game left it). */
    private fun forget() {
        if (enabled) Tools.deactivate()
        selected = null
        selectedCopy = null
        preview = null
        busy = false
        AssetCopies.clear()
    }

    // ---- The gizmo --------------------------------------------------------------------------------------------------

    /** The selected asset when it is in this dimension and nothing is on its way. */
    private fun gizmoBox(): BlockBox? {
        if (busy || confirm != null || selected?.area()?.dim != playerDimension()) return null
        return selectedBox()
    }

    /** The selected asset's box: the change shown here until the server's areas have it, else theirs. */
    fun selectedBox(): BlockBox? {
        val actual = selected?.area()?.bounds
        val (box, until) = preview ?: return actual
        if (gizmo.dragging || busy || confirm != null) return box
        if (actual == box || System.currentTimeMillis() > until) {
            preview = null
            return actual
        }
        return box
    }

    /** A drag shows its box until it is released and sent (or the server's areas have it). */
    private fun changed(box: BlockBox) {
        preview = box to Long.MAX_VALUE
    }

    /** A drag released: the same size moves the asset with its content, another resizes its box only. */
    private fun committed(box: BlockBox) {
        val asset = selected
        val from = asset?.area()?.bounds
        if (asset == null || from == null) {
            preview = null
            return
        }
        if (box.sizeX == from.sizeX && box.sizeY == from.sizeY && box.sizeZ == from.sizeZ) {
            val by = intArrayOf(box.minX - from.minX, box.minY - from.minY, box.minZ - from.minZ)
            ask(from.volume, "Move ${asset.name} (${blocks(from.volume)}) by ${by.joinToString(" ") { if (it > 0) "+$it" else "$it" }}") {
                run("move ${asset.name}", "Moved ${asset.name}", box) { AssetCalls.move(asset, by = by, done = it) }
            }
        } else {
            run("resize ${asset.name}", "Resized ${asset.name}'s box", box) { AssetCalls.resize(asset, box, it) }
        }
    }

    // ---- Changes ----------------------------------------------------------------------------------------------------

    /** Turns the selected asset a quarter clockwise in place, with its content; else the copy selected or looked at. */
    fun turn() {
        val asset = selected ?: return copyTarget()?.let { turnCopy(it) } ?: Chat.warn("Select an asset or a copy first: click it")
        val volume = asset.area()?.bounds?.volume ?: 0
        ask(volume, "Turn ${asset.name} (${blocks(volume)}) a quarter") { run("turn ${asset.name}", "Turned ${asset.name}") { AssetCalls.move(asset, rotate = 90, done = it) } }
    }

    /** Turns [copy] a quarter clockwise in place (`rotate_instance`), a big one after a yes unless [confirmed]. */
    fun turnCopy(copy: AssetInstance, confirmed: Boolean = false, done: (Result<String>) -> Unit = {}) {
        val volume = copy.blockBox.volume
        val action = { run("turn the copy", "Turned the copy of ${copy.assetName}", after = done) { AssetCalls.rotateCopy(copy, 90, it) } }
        if (confirmed) action() else ask(volume, "Turn the copy of ${copy.assetName} (${blocks(volume)}) a quarter", action)
    }

    /** Pastes the asset's content again over [copy], after a yes (right click): what was changed in it is lost. */
    fun syncCopy(copy: AssetInstance? = copyTarget(), confirmed: Boolean = false, done: (Result<String>) -> Unit = {}) {
        copy ?: return Chat.warn("Select a copy first: click it")
        val action = { run("sync the copy", "Synced the copy of ${copy.assetName}", after = done) { AssetCalls.syncCopy(copy, it) } }
        if (confirmed) action() else confirm = "Sync the copy of ${copy.assetName} with its asset: edits made inside it are lost?" to action
    }

    /** Deletes [copy] from the world, after a yes: its blocks go (`delete_instance`). */
    fun deleteCopy(copy: AssetInstance, confirmed: Boolean = false, done: (Result<String>) -> Unit = {}) {
        val action = {
            run("delete the copy", "Deleted the copy of ${copy.assetName}", after = { result ->
                if (result.isSuccess && selectedCopy == copy.id) selectedCopy = null
                done(result)
            }) { AssetCalls.deleteCopy(copy, it) }
        }
        if (confirmed) action() else confirm = "Delete the copy of ${copy.assetName} (${blocks(copy.blockBox.volume)}) from the world?" to action
    }

    /** Fits the selected asset's box to its content, a block around. */
    fun fit() {
        val asset = selected ?: return Chat.warn("Select an asset first: click it")
        run("fit ${asset.name}", "Fitted ${asset.name}'s box") { AssetCalls.fit(asset, it) }
    }

    /** Deletes the selected asset's area (what it holds stays in the world), else the copy: after a yes unless [confirmed]. */
    fun delete(confirmed: Boolean = false, done: (Result<String>) -> Unit = {}) {
        val asset = selected ?: return copyTarget()?.let { deleteCopy(it, confirmed, done) } ?: Chat.warn("Select an asset or a copy first: click it")
        val action = {
            run("delete ${asset.name}", "Deleted the area ${asset.name}: its blocks stay", after = { result ->
                if (result.isSuccess) select(null)
                done(result)
            }) { AssetCalls.delete(asset, it) }
        }
        if (confirmed) action() else confirm = "Delete the area ${asset.name}? Its blocks stay" to action
    }

    /**
     * Saves [old] again (renamed when [name] differs), or makes the selection a new asset when null, with [tags] and
     * [note]; [fit] shrinks its box to its content, [plot] moves it to the world's next free plot. [done] on the answer.
     */
    fun save(old: AssetRef?, name: String, tags: List<String>, note: String, fit: Boolean, plot: Boolean, done: (Result<String>) -> Unit) {
        val world = Bridge.world
        val box = (if (old == null) SelectionTool.current() else selectedBox().takeIf { selected == old } ?: old.area()?.bounds)
            ?: return done(Result.failure(IllegalStateException(if (old == null) "No selection: make one with the selection tool" else "${old.name} isn't in the server's areas")))
        val asset = AssetRef(old?.world ?: world, name)
        // define_asset adds tags to the ones the area has: set_mark takes away the ones removed here.
        val removed = old?.takeIf { it.name == name }?.area()?.tags.orEmpty().filter { it != Library.TAG && it !in tags }
        run(if (old == null) "make $name" else "save $name", if (old == null) "Made the asset $name" else "Saved $name", after = { result ->
            result.onSuccess {
                select(asset)
                if (old != null && old.name != name) AssetCalls.delete(old) { gone -> gone.onFailure { failed("delete the old name ${old.name}", it) } }
                if (removed.isNotEmpty()) AssetCalls.retag(asset, tags) { r -> r.onFailure { failed("take the tags ${removed.joinToString()} off", it) } }
                if (plot) AssetCalls.openSource(asset.id) { r -> r.onFailure { failed("take you to its plot", it) } }
            }
            done(result)
        }) { AssetCalls.define(name, asset.world, box, tags, note, fit, plot, it) }
    }

    /**
     * Runs [call] ([what] it does, [done] once done) unless a change is on its way; [shown] is the box shown meanwhile,
     * [after] gets the answer.
     */
    private fun run(what: String, done: String, shown: BlockBox? = null, after: (Result<String>) -> Unit = {}, call: ((Result<String>) -> Unit) -> Unit) {
        val refused = when {
            busy -> "Wait for the last change to be saved"
            !Bridge.connected -> Bridge.unavailable("assets are its world tools")
            else -> null
        }
        if (refused != null) {
            Chat.warn(refused)
            return after(Result.failure(IllegalStateException(refused)))
        }
        busy = true
        preview = shown?.let { it to 0L }
        call { result ->
            busy = false
            result.onSuccess {
                note("§a✔ $done")
                preview = preview?.let { (box, _) -> box to System.currentTimeMillis() + PREVIEW_MS }
            }.onFailure {
                preview = null
                failed(what, it)
            }
            after(result)
        }
    }

    /** Runs [action] at once when [volume] is small, else after a yes (right click). */
    private fun ask(volume: Long, line: String, action: () -> Unit) {
        if (volume <= BIG) return action()
        confirm = "$line?" to action
    }

    private fun cancelConfirm() {
        if (confirm == null) return
        confirm = null
        preview = null
    }

    private fun failed(what: String, error: Throwable) {
        val message = error.message ?: error.javaClass.simpleName
        note("§c✖ ${message.take(60)}")
        Chat.error("Couldn't $what: $message")
    }

    private fun note(text: String) {
        note = text to System.currentTimeMillis()
    }

    private fun blocks(n: Long) = when {
        n >= 1_000_000 -> String.format(Locale.ROOT, "%.1fM blocks", n / 1_000_000.0)
        n >= 10_000 -> "${n / 1000}k blocks"
        else -> "$n blocks"
    }

    // ---- The panels -------------------------------------------------------------------------------------------------

    /** The panel of the selected asset, else of the copy selected or looked at. */
    fun openPanel() {
        selected?.let { return Screens.open(AssetPanel(it)) }
        copyTarget()?.let { return Screens.open(CopyPanel(it)) }
        Chat.warn("Select an asset or a copy (click it) first, or make a new asset from your selection (${keyName("asset-new")})")
    }

    // ---- Drawing ----------------------------------------------------------------------------------------------------

    override fun draw(draw: OverlayDraw) {
        val o = games.qore.foreman.client.config.Config.settings.overlays
        for (area in assetAreas(draw.dimension)) {
            val ref = AssetRef(area.world, area.name)
            if (ref == selected) continue
            val box = area.bounds
            val distance = box.distanceTo(draw.camera)
            if (distance > o.maxDistance) continue
            val lit = ref == hoveredArea
            val color = if (lit) Colors.HOVER else AREA
            AIOverlay.box(draw, box, color, if (lit) 3f else 2f, Colors.withAlpha(color, if (lit) 0.08f else 0.03f), onTop = true)
            if (lit || distance <= o.labelDistance + maxOf(box.sizeX, box.sizeZ) / 2.0) {
                draw.label(AIOverlay.topCenter(box), area.name + area.tags.filter { it != Library.TAG }.joinToString("") { " #$it" }, color)
            }
        }
        selected?.takeIf { it.area()?.dim == draw.dimension }?.let { asset -> selectedBox()?.let { drawSelected(draw, asset, it) } }
        Bridge.instances.firstOrNull { it.id == selectedCopy && it.inDimension(draw.dimension) }?.let { copy ->
            val color = if (busy || confirm != null) Colors.YELLOW else SELECTED
            AIOverlay.box(draw, copy.blockBox, color, 3f, Colors.withAlpha(color, 0.06f), onTop = true)
            draw.label(AIOverlay.topCenter(copy.blockBox), listOf("Copy of ${copy.assetName}" to color, "${copy.rotate}° · ${copy.blockBox.minX} ${copy.blockBox.minY} ${copy.blockBox.minZ}" to Colors.WHITE))
        }
    }

    private fun drawSelected(draw: OverlayDraw, asset: AssetRef, box: BlockBox) {
        val waiting = busy || confirm != null
        val color = if (waiting) Colors.YELLOW else SELECTED
        AIOverlay.box(draw, box, color, 3f, Colors.withAlpha(color, 0.06f), onTop = true)
        // Where it was, while a move is shown before it is done.
        asset.area()?.bounds?.takeIf { it != box }?.let { AIOverlay.box(draw, it, Colors.withAlpha(Colors.GRAY, 0.6f), 1.5f, 0, onTop = true) }
        draw.label(AIOverlay.topCenter(box), listOf(asset.name to color, "${box.sizeX}×${box.sizeY}×${box.sizeZ} · ${box.minX} ${box.minY} ${box.minZ}" to Colors.WHITE))
        if (!waiting) gizmo.draw(draw)
    }

    override fun status(): ToolStatus {
        val notes = listOfNotNull(
            if (busy) Note("Saving…", Note.Tone.BUSY) else note?.takeIf { System.currentTimeMillis() - it.second < NOTE_MS }?.let { Note.coloured(it.first) },
            if (!Bridge.connected) Note(Bridge.unavailable("assets are its world tools"), Note.Tone.ERROR) else null,
        )
        val asset = selected
        val copy = Bridge.instances.firstOrNull { it.id == selectedCopy }
        val shown = when {
            asset != null -> Shown("Asset area", asset.name, asset.area()?.let { a -> a.bounds.let { "${it.sizeX}×${it.sizeY}×${it.sizeZ}" } + a.tags.filter { it != Library.TAG }.joinToString("") { " #$it" } } ?: "not in the areas")
            copy != null -> copyShown(copy)
            else -> null
        }
        confirm?.let { (question, _) ->
            return ToolStatus(shown, prompt = question, hints = listOf(Hint(ToolStatus.RIGHT, "yes, do it"), Hint("Left click / Esc", "no")), notes = notes)
        }
        val hovered = hoveredArea?.takeIf { it != selected }?.let { Shown("Asset area", it.name) }
            ?: Bridge.instances.firstOrNull { it.id == hoveredCopy && it.id != selectedCopy }?.let(::copyShown)
        val details = mutableListOf<String>()
        val hints = mutableListOf<Hint>()
        when {
            asset != null -> {
                val area = asset.area()
                if (area != null && area.dim != playerDimension()) details += "In ${area.world}: go there from its library page"
                val face = (gizmo.dragged ?: gizmo.hovered)?.let { it.face >= 0 }
                hints += if (face != null) Hint(ToolStatus.DRAG, if (face) "resize its box (its content stays)" else "move it with its content")
                else Hint(ToolStatus.DRAG, "an arrow moves it with its content, a knob resizes its box")
                if (face == true) hints += Hint("Ctrl", "both sides at once")
                hints += Hint(keyName("asset-turn"), "turn it a quarter, with its content")
                hints += Hint(keyName("asset-panel"), "name, tags, note, its copies")
            }
            copy != null -> {
                details += "${copy.rotate}°" + (if (copy.by.isNotEmpty()) " · pasted by ${copy.by}" else "")
                hints += Hint(keyName("asset-turn"), "turn it a quarter")
                hints += Hint(keyName("copy-sync"), "paste its asset over it again")
                hints += Hint(keyName("asset-delete"), "delete it from the world")
                hints += Hint(keyName("asset-panel"), "its panel")
            }
            selectedCopy != null -> details += "The selected copy isn't in the server's list (any more)"
            else -> hints += if (SelectionTool.currentLabel() != null) Hint(keyName("asset-new"), "new asset from the selection")
            else Hint("Tool 1", "make a selection, then ${keyName("asset-new")}: a new asset")
        }
        if (asset == null || gizmo.hovered == null) hints.add(0, Hint(ToolStatus.LEFT, if (hovered != null) "select it" else "on an asset area or a copy: select it"))
        return ToolStatus(shown, "Click an asset area or a pasted copy to select it", details, hovered, hints, notes = notes)
    }

    /** A pasted copy on the status card: its id, and the asset it is a copy of. */
    private fun copyShown(copy: AssetInstance) = Shown("Pasted copy", copy.id, "of asset ${copy.assetName}")
}
