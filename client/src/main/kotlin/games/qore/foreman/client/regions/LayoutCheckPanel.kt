package games.qore.foreman.client.regions

import games.qore.foreman.client.editor.EditorMode
import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.link.Area
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.GamePoint
import games.qore.foreman.client.link.Layouts
import games.qore.foreman.client.link.ServerCalls
import games.qore.foreman.client.link.WireBox
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.render.Colors
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.JSON
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.CompletableFuture
import kotlin.math.abs

/** A problem `check_layout` found, of [type] (about its [field]): on a point ([position], [build]) or an area ([area], [box]). */
@Serializable
data class LayoutIssue(
    val severity: String, val type: String = "", val field: String? = null, val text: String, val target: String = "",
    val position: List<Double> = emptyList(), val build: String = "", val area: String = "", val box: WireBox? = null,
) {
    val error get() = severity == "error"
}

/** What `check_layout` says of a world: how many points and areas, how many of declared types, the [issues], the plain point types left unchecked. */
@Serializable
data class LayoutReport(
    val world: String, val points: Int = 0, @SerialName("typed_points") val typedPoints: Int = 0, val areas: Int = 0,
    @SerialName("typed_areas") val typedAreas: Int = 0, val issues: List<LayoutIssue> = emptyList(), val free: Map<String, Int> = emptyMap(),
)

/**
 * The regions tool's layout check: the server's `check_layout` on the player's world (every point and area against the
 * types its plugins declare), the last [report] kept for its [list][LayoutCheckPanel]. While the list shows, the tool
 * tints the markers of what has problems ([tint]).
 */
object LayoutCheck {
    var report: LayoutReport? = null
        private set
    var running = false
        private set
    var failure: String? = null
        private set
    /** A change was saved since the report: checked again when the list shows. */
    private var stale = false

    /** Shows the list and runs the check. */
    fun open() {
        if (!Bridge.connected) return Chat.warn(Bridge.unavailable("the layout check is its"))
        Screens.open(LayoutCheckPanel())
        run()
    }

    fun run() {
        if (running) return
        running = true
        failure = null
        val world = Bridge.world
        ServerCalls.run({
            val args = buildJsonObject {
                if (world.isNotEmpty()) put("world", world)
                put("format", "json")
            }
            JSON.decodeFromString<LayoutReport>(ServerCalls.text(ServerCalls.callNow("check_layout", args)))
        }) { result ->
            running = false
            stale = false
            result.onSuccess { report = it }.onFailure { failure = it.message ?: it.javaClass.simpleName }
        }
    }

    /** A change of the regions tool was saved: the report is checked again when the list shows next. */
    fun changed() {
        if (report != null) stale = true
    }

    fun refreshIfStale() {
        if (stale) run()
    }

    /** The list is on screen, or a panel opened from it. */
    fun showing(): Boolean = when (val s = Minecraft.getInstance().gui.screen()) {
        is LayoutCheckPanel -> true
        is PointPanel -> s.fromCheck
        is AreaPanel -> s.fromCheck
        else -> false
    }

    /** The problems found on [p]. */
    fun issues(p: GamePoint): List<LayoutIssue> = report?.takeIf { it.world == p.world }?.issues.orEmpty()
        .filter { it.target == "point" && at(it, p.at) && (it.build.isEmpty() || it.build == p.build) }

    /** The problems found on [a]. */
    fun issues(a: Area): List<LayoutIssue> = report?.takeIf { it.world == a.world }?.issues.orEmpty().filter { it.target == "area" && it.area == a.name }

    /** A marker's colour while the list shows: red with an error, amber with warnings only; null otherwise. */
    fun tint(p: GamePoint): Int? = if (report == null || !showing()) null else tint(issues(p))

    fun tint(a: Area): Int? = if (report == null || !showing()) null else tint(issues(a))

    private fun tint(list: List<LayoutIssue>) = when {
        list.any { it.error } -> Colors.RED
        list.isNotEmpty() -> Colors.YELLOW
        else -> null
    }

    private fun at(i: LayoutIssue, v: Vec3) = i.position.size == 3 && abs(i.position[0] - v.x) < 1e-3 && abs(i.position[1] - v.y) < 1e-3 && abs(i.position[2] - v.z) < 1e-3

    /** The point [i] is about, as the server's points have it. */
    fun point(i: LayoutIssue): GamePoint? = Bridge.points.firstOrNull { it.world == report?.world && at(i, it.at) && (i.build.isEmpty() || it.build == i.build) }

    /** The area [i] is about. */
    fun area(i: LayoutIssue): Area? = Bridge.areas.firstOrNull { it.world == report?.world && it.name == i.area }

    /** Teleports the player to what [i] is about: onto the point, on top of the area (the game's /tp, an operator's). */
    fun teleport(i: LayoutIssue) {
        val (dimension, at) = if (i.target == "point") {
            if (i.position.size != 3) return
            point(i)?.dim to Vec3(i.position[0], i.position[1], i.position[2])
        } else {
            val area = area(i)
            val box = i.box?.toBlockBox() ?: area?.blockBoxes?.firstOrNull() ?: return Chat.warn("${i.area} isn't in the server's areas (any more)")
            area?.dim to top(box)
        }
        val minecraft = Minecraft.getInstance()
        if (dimension == null || minecraft.level?.dimension()?.identifier()?.toString() == dimension) {
            // As client_teleport does (vanilla's /minecraft:tp, else /tp), off the client thread: it waits for the replies.
            CompletableFuture.runAsync { runCatching { ClientTools.teleport(at) } }
        } else {
            minecraft.player?.connection?.sendCommand("execute in $dimension run tp @s " + "%.2f %.2f %.2f".format(Locale.ROOT, at.x, at.y, at.z))
        }
    }

    private fun top(b: BlockBox) = Vec3(b.center.x, b.maxY + 1.0, b.center.z)

    /** Selects what [i] is about in the regions tool and opens its panel, with the problems found on it, back to [list] when it closes. */
    fun select(i: LayoutIssue, list: LayoutCheckPanel) {
        if (!RegionsTool.enabled) EditorMode.open(RegionsTool)
        if (i.target == "point") {
            val p = point(i) ?: return Chat.warn("That point isn't in the server's points (any more)")
            RegionsTool.selectPoint(p)
            Screens.open(PointPanel(p, list, issues(p).map { it.text }))
        } else {
            val a = area(i) ?: return Chat.warn("${i.area} isn't in the server's areas (any more)")
            val ref = AreaRef(a.world, a.name)
            RegionsTool.selectArea(ref)
            Screens.open(AreaPanel(ref, list, issues(a).map { it.text }))
        }
    }
}

/**
 * The layout check's list, beside the world: what the check of the world found (errors first, then warnings), each with
 * the type it is about, why, where, and Teleport and Select (its panel, the problems shown on its fields). While it is
 * open, the regions tool tints the markers of the points and areas with problems.
 */
class LayoutCheckPanel : KitScreen("Layout check") {
    private val panelW = 300
    private val px get() = width - panelW - 10
    private val py = 10
    private val ph get() = height - 20

    override val scrim get() = 0

    override fun build() = LayoutCheck.refreshIfStale()

    override fun draw(ui: Ui) {
        ui.card(px, py, panelW, ph, Theme.RAIL)
        ui.title("Layout check", px + 10, py + 10)
        list(ui)
        ui.button(px + panelW - 24, py + 8, 16, 16, "×", Style.GHOST, tooltip = "Close (Esc)") { onClose() }
        ui.button(px + panelW - 104, py + 8, 76, 16, if (LayoutCheck.running) "Checking…" else "Check again", Style.NORMAL, !LayoutCheck.running && Bridge.connected) { LayoutCheck.run() }
    }

    /** The report: counts, then the issues (their buttons under the header and the footer take no clicks). */
    private fun list(ui: Ui) {
        val report = LayoutCheck.report
        var y = py + 30
        val failure = LayoutCheck.failure
        when {
            failure != null -> y += ui.paragraph("Couldn't check: $failure", px + 10, y, panelW - 20, Theme.ERROR) + 4
            report == null -> {
                ui.text(if (LayoutCheck.running) "Checking the world…" else "Not checked yet", px + 10, y, Theme.MUTED)
                return
            }
        }
        if (report == null) return
        ui.text(ui.fit("${report.world}: ${report.points} points (${report.typedPoints} typed) · ${report.areas} areas (${report.typedAreas} typed)", panelW - 20), px + 10, y, Theme.MUTED)
        y += 12
        val errors = report.issues.count { it.error }
        val warnings = report.issues.size - errors
        ui.text(if (report.issues.isEmpty()) "✔ No problems" else "$errors errors · $warnings warnings", px + 10, y, when {
            errors > 0 -> Theme.ERROR
            warnings > 0 -> Theme.WARN
            else -> Theme.OK
        })
        y += 14
        val free = report.free.entries.joinToString { "${it.key} ×${it.value}" }
        val freeH = if (free.isEmpty()) 0 else ui.font.split(Component.literal("Plain point types (not checked): $free"), panelW - 20).size * 10 + 6
        val bottom = py + ph - 8 - freeH
        ui.scroll("issues", px + 1, y, panelW - 2, bottom - y) { top ->
            var iy = top
            for (issue in report.issues) iy += issue(ui, issue, px + 6, iy, panelW - 14) + 4
            iy - top
        }
        ui.hit(px, py, panelW, y - py) {}
        ui.hit(px, bottom, panelW, py + ph - bottom) {}
        if (free.isNotEmpty()) ui.paragraph("Plain point types (not checked): $free", px + 10, py + ph - 4 - freeH, panelW - 20, Theme.FAINT)
    }

    /** An issue's card: severity, type, why, where, Teleport and Select; returns its height. */
    private fun issue(ui: Ui, i: LayoutIssue, x: Int, y: Int, w: Int): Int {
        val lines = ui.font.split(Component.literal(i.text), w - 12)
        val h = 32 + lines.size * 10
        ui.card(x, y, w, h, Theme.PANEL)
        val cw = ui.chip(x + 6, y + 5, if (i.error) "error" else "warning", if (i.error) Theme.ERROR else Theme.WARN)
        val type = (if (i.target == "point") Layouts.point(i.type) else Layouts.area(i.type))?.name ?: i.type
        ui.text(ui.fit(type + (i.field?.let { " · $it" } ?: ""), w - cw - 20), x + cw + 12, y + 7, Theme.TEXT)
        lines.forEachIndexed { n, l -> ui.g.text(ui.font, l, x + 6, y + 20 + n * 10, Theme.MUTED, false) }
        val by = y + 20 + lines.size * 10
        val where = if (i.target == "point") "point at " + i.position.joinToString(" ") { LayoutForm.num(it) } + (if (i.build.isNotEmpty()) " · ${i.build}" else "") else "area ${i.area}"
        ui.text(ui.fit(where, w - 12 - 112), x + 6, by + 1, Theme.FAINT)
        ui.button(x + w - 110, by - 2, 56, 14, "Teleport", Style.GHOST, tooltip = "Go there (/tp)") { LayoutCheck.teleport(i) }
        ui.button(x + w - 52, by - 2, 48, 14, "Select", Style.NORMAL, tooltip = "Select it in the regions tool and open its panel") { LayoutCheck.select(i, this) }
        return h
    }
}
