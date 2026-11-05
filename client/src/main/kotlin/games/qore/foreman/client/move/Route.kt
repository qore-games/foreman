package games.qore.foreman.client.move

import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** A position in the world (feet). */
class Point(val x: Double, val y: Double, val z: Double) {
    fun horizontal(to: Point) = hypot(to.x - x, to.z - z)

    /** The camera's yaw facing [to]: 0 is south (+z), 90 west (-x), as Minecraft counts. */
    fun yawTo(to: Point): Float = Math.toDegrees(atan2(-(to.x - x), to.z - z)).toFloat()

    override fun toString() = "%.2f %.2f %.2f".format(java.util.Locale.ROOT, x, y, z)
}

/** Where a player can stand, read from the world by the game and from plain values in tests. */
fun interface Ground {
    /**
     * The feet's block y of a safe place to stand in column [x], [z] (solid below, room for the body, no liquid nor
     * harm), the highest from [y] + [CLIMB] down to [y] - [DROP]; null when there is none (a wall, a cliff, the void,
     * lava, an unloaded chunk).
     */
    fun stand(x: Int, z: Int, y: Int): Int?

    /**
     * Whether something that hurts (lava, fire, cactus…) is where the body would be in column [x], [z] at feet [y]:
     * what the side of the player's box must not touch, though the player walks beside it.
     */
    fun harms(x: Int, z: Int, y: Int): Boolean = false

    companion object {
        /** Half the player's width: the box reaches this far from the line it walks. */
        const val HALF_WIDTH = 0.3
        /** A block up: the player jumps it. */
        const val CLIMB = 1
        /** Three blocks down: a fall that doesn't hurt. */
        const val DROP = 3
    }
}

/**
 * Whether a straight walk from [from] to the middle of block [x], [z] stays on safe ground, a block up or three down
 * from one step to the next, sampled every quarter block: the y the player stands at there, or null. The ground under
 * the line carries the player; the columns the side of their box crosses (a corner cut diagonally, an edge walked
 * along) must only not hurt.
 */
fun walk(ground: Ground, from: Point, x: Int, z: Int): Int? {
    val tx = x + 0.5
    val tz = z + 0.5
    val steps = ceil(hypot(tx - from.x, tz - from.z) * 4).toInt().coerceAtLeast(1)
    var y = floor(from.y + 0.01).toInt()
    var bx = floor(from.x).toInt()
    var bz = floor(from.z).toInt()
    for (i in 1..steps) {
        val px = from.x + (tx - from.x) * i / steps
        val pz = from.z + (tz - from.z) * i / steps
        val sx = floor(px).toInt()
        val sz = floor(pz).toInt()
        if (sx != bx || sz != bz) {
            bx = sx
            bz = sz
            y = ground.stand(sx, sz, y) ?: return null
        }
        for (cx in floor(px - Ground.HALF_WIDTH).toInt()..floor(px + Ground.HALF_WIDTH).toInt()) {
            for (cz in floor(pz - Ground.HALF_WIDTH).toInt()..floor(pz + Ground.HALF_WIDTH).toInt()) {
                if ((cx != sx || cz != sz) && ground.harms(cx, cz, y)) return null
            }
        }
    }
    return y
}

/**
 * client_wander's waypoints: random blocks within [radius] of [start] where the player can stand, each reached from
 * where they are by a [walk] on safe ground, at least a few blocks away. The same seed in the same world gives the
 * same waypoints: each try draws two numbers.
 */
class Wander(val start: Point, val radius: Double, seed: Long, private val ground: Ground) {
    private val random = Random(seed)
    private val minHop = minOf(3.0, radius / 2)

    /** The next waypoint from [from], or null when none of [TRIES] random blocks looks reachable. */
    fun next(from: Point): Point? {
        repeat(TRIES) {
            val angle = random.nextDouble() * 2 * Math.PI
            val distance = radius * sqrt(random.nextDouble())
            val x = floor(start.x + distance * cos(angle)).toInt()
            val z = floor(start.z + distance * sin(angle)).toInt()
            if (hypot(x + 0.5 - from.x, z + 0.5 - from.z) < minHop) return@repeat
            val y = walk(ground, from, x, z) ?: return@repeat
            return Point(x + 0.5, y.toDouble(), z + 0.5)
        }
        return null
    }

    companion object {
        const val TRIES = 24
    }
}

/**
 * Stuck: no closer to the waypoint by [step] blocks for [patience] ticks in a row. [reset] for each new waypoint.
 */
class Progress(private val patience: Int = 40, private val step: Double = 0.3) {
    private var best = Double.MAX_VALUE
    private var since = 0

    fun reset() {
        best = Double.MAX_VALUE
        since = 0
    }

    /** One tick at [distance] from the waypoint: whether the player is stuck. */
    fun stuck(distance: Double): Boolean {
        if (distance <= best - step) {
            best = distance
            since = 0
        } else since++
        return since >= patience
    }
}
