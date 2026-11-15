package games.qore.foreman.client.objects

import net.minecraft.world.phys.Vec3
import kotlin.math.ceil
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How the world objects tool draws what isn't selected nor aimed at (the objects, the panels' relations): lighter,
 * fading out with the distance from the camera, so a street of lights and cables doesn't hide the build. An object's
 * outline also fades away from the line through its anchors: a spot's wide cone shows its axis and the start of its cone,
 * a cable's curve stays (its sag keeps near that line). What fades out can't be picked either. The selected object and
 * the aimed one are drawn whole. Every distance of it is here.
 */
object ObjectFade {
    /** Full within this many blocks of the camera, gone by [FAR]. */
    private const val NEAR = 12.0
    private const val FAR = 40.0
    /** An outline is full within this many blocks of the line through its anchors, gone by [AWAY_FAR]. */
    private const val AWAY_NEAR = 2.5
    private const val AWAY_FAR = 6.0
    /** How opaque an object that isn't selected nor aimed at is, at most, and how wide its lines. */
    const val ALPHA = 0.6f
    const val WIDTH = 1.5f
    /** An outline is cut in pieces about this long, each faded on its own. */
    private const val PIECE = 0.5
    private const val MAX_PIECES = 64
    /** Pieces fainter than this aren't picked. */
    private const val PICKABLE = 0.25f
    /** Faded in this many steps: pieces next to each other on the same step are drawn as one line. */
    private const val STEPS = 16

    /** A straight piece of an outline and how visible it is, 0 to 1. */
    class Piece(val from: Vec3, val to: Vec3, val alpha: Float) {
        val pickable get() = alpha >= PICKABLE
    }

    /** How visible something [distance] blocks from the camera is: 1 near, 0 far. */
    fun byDistance(distance: Double) = smooth(distance, NEAR, FAR)

    /** Whether anything [distance] blocks from the camera shows at all. */
    fun inRange(distance: Double) = distance < FAR

    /**
     * The visible pieces of [line] (an outline) seen from [camera]: faded by their distance from it and from the line
     * through [anchors]. Pieces next to each other that fade alike are merged.
     */
    fun pieces(line: List<Vec3>, anchors: List<Vec3>, camera: Vec3): List<Piece> {
        val pieces = ArrayList<Piece>()
        for (i in 0 until line.size - 1) {
            val a = line[i]
            val b = line[i + 1]
            // No point of it is nearer the anchors' line than its nearer end less half its length.
            if (min(away(a, anchors), away(b, anchors)) >= AWAY_FAR + a.distanceTo(b) / 2 || toSegment(camera, a, b) >= FAR) continue
            val n = ceil(a.distanceTo(b) / PIECE).toInt().coerceIn(1, MAX_PIECES)
            var start = a
            var step = -1
            for (j in 0 until n) {
                val from = if (j == 0) a else a.lerp(b, j / n.toDouble())
                val to = if (j == n - 1) b else a.lerp(b, (j + 1) / n.toDouble())
                val mid = from.lerp(to, 0.5)
                val s = (byDistance(mid.distanceTo(camera)) * smooth(away(mid, anchors), AWAY_NEAR, AWAY_FAR) * STEPS).roundToInt()
                if (s != step) {
                    if (step > 0) pieces += Piece(start, from, step / STEPS.toFloat())
                    start = from
                    step = s
                }
            }
            if (step > 0) pieces += Piece(start, b, step / STEPS.toFloat())
        }
        return pieces
    }

    /** 1 up to [full], 0 from [gone], smooth between. */
    private fun smooth(value: Double, full: Double, gone: Double): Float {
        val t = ((gone - value) / (gone - full)).coerceIn(0.0, 1.0)
        return (t * t * (3 - 2 * t)).toFloat()
    }

    /** How far [p] is from the line through [anchors] (one anchor: from it). */
    private fun away(p: Vec3, anchors: List<Vec3>): Double = when (anchors.size) {
        0 -> 0.0
        1 -> p.distanceTo(anchors[0])
        else -> (0 until anchors.size - 1).minOf { toSegment(p, anchors[it], anchors[it + 1]) }
    }

    /** How far [p] is from the segment [a] [b]. */
    private fun toSegment(p: Vec3, a: Vec3, b: Vec3): Double {
        val ab = b.subtract(a)
        val length = ab.lengthSqr()
        val t = if (length < 1e-9) 0.0 else (p.subtract(a).dot(ab) / length).coerceIn(0.0, 1.0)
        return p.distanceTo(a.add(ab.scale(t)))
    }
}
