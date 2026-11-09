package games.qore.foreman.client.library

import games.qore.foreman.client.gizmo.BlockBox
import games.qore.foreman.client.input.Ray
import games.qore.foreman.client.link.Area
import net.minecraft.world.phys.Vec3
import kotlin.math.max
import kotlin.math.sqrt

/** The box around every box of the area. */
val Area.bounds: BlockBox
    get() = blockBoxes.let { b -> BlockBox(b.minOf { it.minX }, b.minOf { it.minY }, b.minOf { it.minZ }, b.maxOf { it.maxX }, b.maxOf { it.maxY }, b.maxOf { it.maxZ }) }

val BlockBox.volume get() = sizeX.toLong() * sizeY * sizeZ

operator fun BlockBox.contains(p: Vec3) = p.x >= minX && p.x < maxX + 1 && p.y >= minY && p.y < maxY + 1 && p.z >= minZ && p.z < maxZ + 1

/** Distance from [p] to the box (0 inside). */
fun BlockBox.distanceTo(p: Vec3): Double {
    val dx = max(max(minX - p.x, p.x - (maxX + 1)), 0.0)
    val dy = max(max(minY - p.y, p.y - (maxY + 1)), 0.0)
    val dz = max(max(minZ - p.z, p.z - (maxZ + 1)), 0.0)
    return sqrt(dx * dx + dy * dy + dz * dz)
}

/** How far along the ray it enters [box] (0 from inside), or null when it misses it. */
fun Ray.enters(box: BlockBox): Double? {
    var near = 0.0
    var far = Double.MAX_VALUE
    for (axis in 0..2) {
        val o = when (axis) { 0 -> origin.x; 1 -> origin.y; else -> origin.z }
        val d = when (axis) { 0 -> dir.x; 1 -> dir.y; else -> dir.z }
        val lo = box.min(axis).toDouble()
        val hi = box.max(axis) + 1.0
        if (kotlin.math.abs(d) < 1e-9) {
            if (o < lo || o > hi) return null
            continue
        }
        val a = (lo - o) / d
        val b = (hi - o) / d
        near = max(near, minOf(a, b))
        far = minOf(far, maxOf(a, b))
        if (near > far) return null
    }
    return near
}

/**
 * What the crosshair points at among [boxes] within [reach]: the nearest one it enters from outside, else the smallest
 * one the camera is in (looking at a copy inside a bigger asset picks the copy).
 */
fun <T> Ray.pick(boxes: List<Pair<T, BlockBox>>, reach: Double): T? {
    val hits = boxes.mapNotNull { (thing, box) -> enters(box)?.takeIf { it <= reach }?.let { Triple(thing, box, it) } }
    hits.filter { origin !in it.second }.minByOrNull { it.third }?.let { return it.first }
    return hits.minByOrNull { it.second.volume }?.first
}
