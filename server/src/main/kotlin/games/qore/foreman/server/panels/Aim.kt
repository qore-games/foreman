package games.qore.foreman.server.panels

import games.qore.foreman.server.Foreman
import games.qore.foreman.server.api.PanelTarget
import org.bukkit.FluidCollisionMode
import org.bukkit.entity.Player
import org.bukkit.util.Vector

/** What a player aims at, as a [PanelTarget]: a world object's outline (with the world tools), an entity or a block. */
object Aim {
    /** How far a player aims. */
    const val REACH = 64.0
    /** How close to an object's outline the line of sight passes to pick it, in blocks. */
    private const val OBJECT_PICK = 0.35

    /** What [player] aims at within [REACH], the nearest of an object, an entity and a block; null when nothing. Main thread. */
    fun target(plugin: Foreman, player: Player): PanelTarget? {
        val eye = player.eyeLocation
        val hit = player.world.rayTrace(eye, eye.direction, REACH, FluidCollisionMode.NEVER, true, 0.0) { it != player && player.canSee(it) }
        val distance = hit?.hitPosition?.distance(eye.toVector()) ?: REACH
        if (plugin.worldTools) objectHit(plugin, player, distance)?.let { return it }
        hit?.hitEntity?.let { return PanelTarget.entity(it) }
        return hit?.hitBlock?.let { PanelTarget.block(it) }
    }

    /** The world object whose outline [player]'s line of sight passes nearest, before [within] blocks. */
    private fun objectHit(plugin: Foreman, player: Player, within: Double): PanelTarget? {
        val eye = player.eyeLocation
        val from = eye.toVector()
        val dir = eye.direction.normalize()
        var best: PanelTarget? = null
        var bestAlong = within
        for (f in plugin.worlds.objects.near(eye, within, 200)) {
            val line = f.line
            val segments = if (line.size == 1) listOf(line[0] to line[0]) else line.zipWithNext()
            for ((a, b) in segments) {
                val (along, gap) = closest(from, dir, a, b)
                if (gap <= OBJECT_PICK && along in 0.0..bestAlong) {
                    bestAlong = along
                    best = PanelTarget.worldObject(f.obj.world, f.kind.id, f.obj.id)
                }
            }
        }
        return best
    }

    /** How far along the ray ([from], [dir] of length 1) it comes closest to the segment [a]–[b], and how close. */
    internal fun closest(from: Vector, dir: Vector, a: Vector, b: Vector): Pair<Double, Double> {
        val v = b.clone().subtract(a)
        val w = from.clone().subtract(a)
        val vv = v.dot(v)
        val uv = dir.dot(v)
        val denom = vv - uv * uv
        var t = if (denom > 1e-9) ((w.dot(v) - w.dot(dir) * uv) / denom).coerceIn(0.0, 1.0) else 0.0
        val s = (a.clone().add(v.clone().multiply(t)).subtract(from).dot(dir)).coerceAtLeast(0.0)
        val p = from.clone().add(dir.clone().multiply(s))
        if (vv > 0) t = (p.clone().subtract(a).dot(v) / vv).coerceIn(0.0, 1.0)
        return s to p.distance(a.clone().add(v.multiply(t)))
    }

    /** The middle of a world object's outline (or anchors), when its plugin still finds it. Main thread, world tools. */
    fun objectCenter(plugin: Foreman, target: PanelTarget): Vector? {
        val world = target.world() ?: return null
        val found = runCatching { plugin.worlds.objects.get(world, target.id!!, target.kind) }.getOrNull() ?: return null
        val line = found.line.ifEmpty { return null }
        return line.fold(Vector()) { sum, p -> sum.add(p) }.multiply(1.0 / line.size)
    }
}
