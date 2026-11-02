package games.qore.foreman.server.world

import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.data.BlockData
import org.joml.Vector3d
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.util.stream.IntStream
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * First-person renders with the game's textures: a ray tracer over the block models of [Models] and the display
 * entities (furniture), lit by the world's sky and block light, with sun shadows and distance fog. Close to what a
 * player sees, without a client.
 */
object Camera {
    /** [yaw]/[pitch] as in game: yaw 0 looks south, 90 west; pitch 90 looks down. */
    class View(val eye: Vector3d, val yaw: Double, val pitch: Double, val fov: Double = 70.0)

    private val SKY_TOP = Color(120, 167, 255)
    private val SKY_HORIZON = Color(192, 216, 255)
    private val SUN = Vector3d(0.35, 0.85, 0.4).normalize()

    /** What the rays see: block models by position, displays by voxel, light. */
    private class Scene(
        val minX: Int, val minY: Int, val minZ: Int, val sx: Int, val sy: Int, val sz: Int,
        val ids: IntArray, val baked: List<Baked>, val liquid: BooleanArray, val tints: IntArray, val translucent: BooleanArray,
        val displays: Map<Long, List<Baked>>, val snapshots: Snapshots?, val day: Double, val floorGrid: Boolean,
    ) {
        fun id(x: Int, y: Int, z: Int): Int {
            val i = x - minX; val j = y - minY; val k = z - minZ
            if (i < 0 || j < 0 || k < 0 || i >= sx || j >= sy || k >= sz) return 0
            return ids[(i * sy + j) * sz + k]
        }

        fun inside(x: Int, y: Int, z: Int) = x - minX in 0 until sx && y - minY in 0 until sy && z - minZ in 0 until sz

        var fullbright = false

        fun light(x: Int, y: Int, z: Int): Double {
            val s = snapshots ?: return 1.0
            if (fullbright) return 0.95
            val sky = s.skyLight(x, y, z) * day
            val block = s.blockLight(x, y, z).toDouble()
            val l = max(sky, block) / 15.0
            return 0.22 + 0.78 * l.pow(1.4)
        }
    }

    private fun cell(x: Int, y: Int, z: Int) = (x.toLong() and 0x1fffff shl 42) or ((y.toLong() and 0x1fffff) shl 21) or (z.toLong() and 0x1fffff)

    /** Fixed biome colors for tinted faces (grass, leaves, water). */
    private fun tint(m: Material): Int = when {
        m == Material.SPRUCE_LEAVES -> 0x619961
        m == Material.BIRCH_LEAVES -> 0x80A755
        m == Material.MANGROVE_LEAVES -> 0x8DB127
        m.name.endsWith("_LEAVES") || m == Material.VINE -> 0x77AB2F
        m == Material.LILY_PAD -> 0x208030
        m == Material.WATER -> 0x3F76E4
        m == Material.REDSTONE_WIRE -> 0xB01010
        m.name.endsWith("_STEM") -> 0x80A040
        else -> 0x91BD59
    }

    private fun isTranslucent(m: Material) = m.name.contains("STAINED_GLASS") || m == Material.ICE || m == Material.SLIME_BLOCK ||
        m == Material.HONEY_BLOCK || m == Material.TINTED_GLASS

    /** Reads the world around [views] (main thread for snapshots and entities). */
    fun world(world: World, views: List<View>, distance: Int, time: String, fullbright: Boolean = false): Stage {
        val eyes = views.map { it.eye }
        val minX = floor(eyes.minOf { it.x }).toInt() - distance
        val maxX = floor(eyes.maxOf { it.x }).toInt() + distance
        val minZ = floor(eyes.minOf { it.z }).toInt() - distance
        val maxZ = floor(eyes.maxOf { it.z }).toInt() + distance
        val minY = max(world.minHeight, floor(eyes.minOf { it.y }).toInt() - min(distance, 64))
        val maxY = min(world.maxHeight - 1, floor(eyes.maxOf { it.y }).toInt() + min(distance, 64))
        val area = Box(com.sk89q.worldedit.math.BlockVector3.at(minX, minY, minZ), com.sk89q.worldedit.math.BlockVector3.at(maxX, maxY, maxZ))
        val snapshots = Sync.main { Snapshots(world, minX, minZ, maxX, maxZ).load() }
        Furniture.ensureLoaded(world, Box(com.sk89q.worldedit.math.BlockVector3.at(minX, minY, minZ), com.sk89q.worldedit.math.BlockVector3.at(min(maxX, minX + 255), maxY, min(maxZ, minZ + 255))))
        val entries = Sync.main { Displays.read(world, area) }
        val day = when (time) {
            "night" -> 0.25
            "current" -> Sync.main { world.time }.let { t -> if (t in 0..12000 || t > 23000) 1.0 else if (t in 13000..23000) 0.25 else 0.6 }
            else -> 1.0
        }

        val sx = maxX - minX + 1; val sy = maxY - minY + 1; val sz = maxZ - minZ + 1
        val ids = IntArray(sx * sy * sz)
        val states = HashMap<BlockData, Int>()
        val stateList = ArrayList<BlockData>()
        stateList += Material.AIR.createBlockData()
        var n = 0
        for (x in minX..maxX) for (y in minY..maxY) for (z in minZ..maxZ) {
            val type = snapshots.type(x, y, z)
            ids[n++] = if (type.isAir) 0 else {
                val data = snapshots.data(x, y, z)
                states.getOrPut(data) { stateList += data; stateList.size - 1 }
            }
        }
        val baked = stateList.map { Models.block(it) }
        val liquid = BooleanArray(stateList.size) { Blocks.isLiquid(stateList[it].material) }
        val tints = IntArray(stateList.size) { tint(stateList[it].material) }
        val translucent = BooleanArray(stateList.size) { isTranslucent(stateList[it].material) }

        val displays = index(Displays.bake(entries).map { it.baked })
        val scene = Scene(minX, minY, minZ, sx, sy, sz, ids, baked, liquid, tints, translucent, displays, snapshots, day, false)
        scene.fullbright = fullbright
        return Stage(scene)
    }

    /** Blocks (positions from y 0 up) on a grid floor (schematic previews), with [models] around them. */
    fun blocks(cells: Map<Triple<Int, Int, Int>, BlockData>, models: List<Baked> = emptyList()): Stage {
        val minX = (cells.keys.minOfOrNull { it.first } ?: 0) - 1; val maxX = (cells.keys.maxOfOrNull { it.first } ?: 0) + 1
        val minZ = (cells.keys.minOfOrNull { it.third } ?: 0) - 1; val maxZ = (cells.keys.maxOfOrNull { it.third } ?: 0) + 1
        val minY = -1; val maxY = (cells.keys.maxOfOrNull { it.second } ?: 0) + 1
        val sx = maxX - minX + 1; val sy = maxY - minY + 1; val sz = maxZ - minZ + 1
        val ids = IntArray(sx * sy * sz)
        val states = HashMap<BlockData, Int>()
        val stateList = arrayListOf(Material.AIR.createBlockData())
        for ((p, data) in cells) {
            if (data.material.isAir) continue
            ids[((p.first - minX) * sy + (p.second - minY)) * sz + (p.third - minZ)] = states.getOrPut(data) { stateList += data; stateList.size - 1 }
        }
        val baked = stateList.map { Models.block(it) }
        val liquid = BooleanArray(stateList.size) { Blocks.isLiquid(stateList[it].material) }
        val tints = IntArray(stateList.size) { tint(stateList[it].material) }
        val translucent = BooleanArray(stateList.size) { isTranslucent(stateList[it].material) }
        val scene = Scene(minX, minY, minZ, sx, sy, sz, ids, baked, liquid, tints, translucent, index(models), null, 1.0, true)
        return Stage(scene)
    }

    /** A model alone on a grid floor (furniture previews). */
    fun studio(models: List<Baked>): Stage {
        val all = models.filter { !it.empty }
        val minX = floor(all.minOfOrNull { it.min.x } ?: 0.0).toInt() - 4
        val minZ = floor(all.minOfOrNull { it.min.z } ?: 0.0).toInt() - 4
        val maxX = floor(all.maxOfOrNull { it.max.x } ?: 1.0).toInt() + 4
        val maxZ = floor(all.maxOfOrNull { it.max.z } ?: 1.0).toInt() + 4
        val minY = -1
        val maxY = floor(all.maxOfOrNull { it.max.y } ?: 1.0).toInt() + 4
        val sx = maxX - minX + 1; val sy = maxY - minY + 1; val sz = maxZ - minZ + 1
        val scene = Scene(minX, minY, minZ, sx, sy, sz, IntArray(sx * sy * sz), listOf(Baked.EMPTY), BooleanArray(1), IntArray(1), BooleanArray(1), index(all), null, 1.0, true)
        return Stage(scene)
    }

    private fun index(models: List<Baked>): Map<Long, List<Baked>> {
        val out = HashMap<Long, MutableList<Baked>>()
        for (b in models) {
            if (b.empty) continue
            val volume = (b.max.x - b.min.x) * (b.max.y - b.min.y) * (b.max.z - b.min.z)
            if (volume > 4096) continue
            for (x in floor(b.min.x).toInt()..floor(b.max.x).toInt()) for (y in floor(b.min.y).toInt()..floor(b.max.y).toInt())
                for (z in floor(b.min.z).toInt()..floor(b.max.z).toInt()) out.getOrPut(cell(x, y, z)) { ArrayList() } += b
        }
        return out
    }

    /** A loaded scene that can be rendered from several points of view. */
    class Stage internal constructor(private val scene: Any) {
        fun render(view: View, width: Int, height: Int, maxDistance: Double, caption: String?): BufferedImage = draw(scene as Scene, view, width, height, maxDistance, caption)
    }

    private class Hit(val t: Double, val color: Int, val normal: Vector3d)

    private fun draw(scene: Scene, view: View, width: Int, height: Int, maxDistance: Double, caption: String?): BufferedImage {
        val yaw = Math.toRadians(view.yaw)
        val pitch = Math.toRadians(view.pitch)
        val forward = Vector3d(-sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
        val right = Vector3d(-cos(yaw), 0.0, -sin(yaw))
        val up = Vector3d(right).cross(forward).normalize()
        val tanY = tan(Math.toRadians(view.fov) / 2)
        val tanX = tanY * width / height
        val pixels = IntArray(width * height)
        IntStream.range(0, height).parallel().forEach { py ->
            for (px in 0 until width) {
                val sx = (2.0 * (px + 0.5) / width - 1) * tanX
                val sy = (1 - 2.0 * (py + 0.5) / height) * tanY
                val dir = Vector3d(forward).add(Vector3d(right).mul(sx)).add(Vector3d(up).mul(sy)).normalize()
                pixels[py * width + px] = trace(scene, view.eye, dir, maxDistance)
            }
        }
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.setRGB(0, 0, width, height, pixels, 0, width)
        if (caption != null) {
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.font = Font(Font.SANS_SERIF, Font.BOLD, 13)
                g.color = Color(0, 0, 0, 140)
                g.fillRect(0, height - 22, g.fontMetrics.stringWidth(caption) + 12, 22)
                g.color = Color.WHITE
                g.drawString(caption, 6, height - 7)
                // Crosshair, like in game.
                g.color = Color(255, 255, 255, 180)
                g.drawLine(width / 2 - 6, height / 2, width / 2 + 6, height / 2)
                g.drawLine(width / 2, height / 2 - 6, width / 2, height / 2 + 6)
            } catch (_: Throwable) {
            }
            g.dispose()
        }
        return image
    }

    private fun sky(dir: Vector3d): Int {
        val t = (dir.y.coerceIn(-0.2, 1.0) + 0.2) / 1.2
        return mix(SKY_HORIZON.rgb, SKY_TOP.rgb, t.pow(0.6))
    }

    private fun mix(a: Int, b: Int, t: Double): Int {
        val r = ((a shr 16 and 255) * (1 - t) + (b shr 16 and 255) * t).toInt()
        val g = ((a shr 8 and 255) * (1 - t) + (b shr 8 and 255) * t).toInt()
        val bl = ((a and 255) * (1 - t) + (b and 255) * t).toInt()
        return (r shl 16) or (g shl 8) or bl
    }

    private fun scale(c: Int, f: Double, tint: Int = 0xffffff): Int {
        val r = ((c shr 16 and 255) * f * (tint shr 16 and 255) / 255).toInt().coerceIn(0, 255)
        val g = ((c shr 8 and 255) * f * (tint shr 8 and 255) / 255).toInt().coerceIn(0, 255)
        val b = ((c and 255) * f * (tint and 255) / 255).toInt().coerceIn(0, 255)
        return (r shl 16) or (g shl 8) or b
    }

    /** Minecraft's directional face shading. */
    private fun faceShade(n: Vector3d): Double = 0.6 * n.x * n.x + 0.8 * n.z * n.z + (if (n.y > 0) 1.0 else 0.5) * n.y * n.y

    private fun trace(scene: Scene, origin: Vector3d, dir: Vector3d, maxDistance: Double): Int {
        var transmit = 1.0
        var acc = Triple(0.0, 0.0, 0.0)
        fun add(c: Int, w: Double) {
            acc = Triple(acc.first + (c shr 16 and 255) * w, acc.second + (c shr 8 and 255) * w, acc.third + (c and 255) * w)
        }
        var inWater = false
        val result = march(scene, origin, dir, maxDistance) { x, y, z, tEnter, tExit ->
            val id = scene.id(x, y, z)
            if (id != 0 && scene.liquid[id]) {
                if (!inWater) {
                    inWater = true
                    add(scale(scene.tints[id], 0.8 * scene.light(x, y + 1, z)), transmit * 0.55)
                    transmit *= 0.45
                } else transmit *= 0.88
                return@march if (transmit < 0.04) Hit(tEnter, 0, Vector3d()) else null
            }
            inWater = false
            val hit = hitVoxel(scene, id, x, y, z, origin, dir, tEnter, tExit) ?: return@march null
            if (id != 0 && scene.translucent[id]) {
                add(hit.color, transmit * 0.5)
                transmit *= 0.5
                return@march null
            }
            hit
        }
        val color = if (result == null || result.color == 0 && result.normal.lengthSquared() == 0.0) {
            if (result != null) 0 else if (scene.floorGrid && dir.y < 0) floor(origin, dir) else sky(dir)
        } else {
            val p = Vector3d(dir).mul(result.t).add(origin)
            val n = result.normal
            val lx = floor(p.x + n.x * 0.5).toInt()
            val ly = floor(p.y + n.y * 0.5).toInt()
            val lz = floor(p.z + n.z * 0.5).toInt()
            var light = scene.light(lx, ly, lz)
            // Sun shadow: lit faces facing the sun, unless something is in the way.
            val sunFacing = n.dot(SUN)
            if (scene.fullbright) light *= 0.9 + 0.1 * max(0.0, sunFacing)
            else if (scene.snapshots != null && sunFacing > 0 && scene.snapshots.skyLight(lx, ly, lz) >= 13) {
                val start = Vector3d(p).add(Vector3d(n).mul(0.01))
                val blocked = march(scene, start, SUN, 48.0) { x, y, z, a, b ->
                    val id = scene.id(x, y, z)
                    if (id != 0 && scene.liquid[id]) null else hitVoxel(scene, id, x, y, z, start, SUN, a, b)
                } != null
                light *= if (blocked) 0.62 else 0.9 + 0.1 * sunFacing
            } else if (scene.snapshots != null) light *= 0.85
            var c = scale(result.color, faceShade(n) * light)
            val fog = ((result.t / maxDistance).coerceIn(0.0, 1.0)).pow(2.2) * 0.85
            c = mix(c, sky(dir), fog)
            c
        }
        add(color, transmit)
        return ((acc.first.toInt().coerceIn(0, 255)) shl 16) or ((acc.second.toInt().coerceIn(0, 255)) shl 8) or acc.third.toInt().coerceIn(0, 255)
    }

    private fun floor(origin: Vector3d, dir: Vector3d): Int {
        val t = -origin.y / dir.y
        val p = Vector3d(dir).mul(t).add(origin)
        val checker = (floor(p.x).toInt() + floor(p.z).toInt()) and 1
        val base = if (checker == 0) 0xd8d8d8 else 0xc4c4c4
        val line = abs(p.x - Math.rint(p.x)) < 0.02 || abs(p.z - Math.rint(p.z)) < 0.02
        return mix(if (line) 0x909090 else base, sky(dir), (t / 40).coerceIn(0.0, 0.8))
    }

    /** Amanatides–Woo voxel walk; [visit] returns a hit to stop. */
    private inline fun march(scene: Scene, o: Vector3d, d: Vector3d, maxDistance: Double, visit: (Int, Int, Int, Double, Double) -> Hit?): Hit? {
        // A camera outside a scene without displays starts walking where the ray enters the scene's box.
        var t = if (scene.displays.isEmpty() && !scene.inside(floor(o.x).toInt(), floor(o.y).toInt(), floor(o.z).toInt())) entry(scene, o, d) ?: return null else 0.0
        val s = Vector3d(d).mul(t).add(o)
        var x = floor(s.x).toInt(); var y = floor(s.y).toInt(); var z = floor(s.z).toInt()
        val stepX = if (d.x > 0) 1 else -1; val stepY = if (d.y > 0) 1 else -1; val stepZ = if (d.z > 0) 1 else -1
        val dx = if (d.x != 0.0) abs(1 / d.x) else Double.MAX_VALUE
        val dy = if (d.y != 0.0) abs(1 / d.y) else Double.MAX_VALUE
        val dz = if (d.z != 0.0) abs(1 / d.z) else Double.MAX_VALUE
        var tx = if (d.x != 0.0) t + ((if (d.x > 0) x + 1 - s.x else s.x - x) * dx) else Double.MAX_VALUE
        var ty = if (d.y != 0.0) t + ((if (d.y > 0) y + 1 - s.y else s.y - y) * dy) else Double.MAX_VALUE
        var tz = if (d.z != 0.0) t + ((if (d.z > 0) z + 1 - s.z else s.z - z) * dz) else Double.MAX_VALUE
        while (t < maxDistance) {
            if (!scene.inside(x, y, z) && scene.displays.isEmpty()) return null
            val exit = min(tx, min(ty, tz))
            visit(x, y, z, t, exit)?.let { return it }
            t = exit
            when (exit) {
                tx -> { x += stepX; tx += dx }
                ty -> { y += stepY; ty += dy }
                else -> { z += stepZ; tz += dz }
            }
            if (scene.floorGrid && y < 0 && d.y < 0) return null
        }
        return null
    }

    /** Where the ray enters the scene's box (slab test, nudged inside), or null if it misses it. */
    private fun entry(scene: Scene, o: Vector3d, d: Vector3d): Double? {
        var t0 = 0.0
        var t1 = Double.MAX_VALUE
        for ((origin, dir, lo, size) in listOf(listOf(o.x, d.x, scene.minX.toDouble(), scene.sx.toDouble()), listOf(o.y, d.y, scene.minY.toDouble(), scene.sy.toDouble()), listOf(o.z, d.z, scene.minZ.toDouble(), scene.sz.toDouble()))) {
            if (abs(dir) < 1e-12) { if (origin < lo || origin > lo + size) return null; continue }
            val a = (lo - origin) / dir
            val b = (lo + size - origin) / dir
            t0 = max(t0, min(a, b))
            t1 = min(t1, max(a, b))
        }
        return if (t0 <= t1) t0 + 1e-6 else null
    }

    private fun hitVoxel(scene: Scene, id: Int, x: Int, y: Int, z: Int, o: Vector3d, d: Vector3d, tEnter: Double, tExit: Double): Hit? {
        var best: Hit? = null
        if (id != 0) {
            val local = Vector3d(o.x - x, o.y - y, o.z - z)
            for (q in scene.baked[id].quads) {
                val h = intersect(q, local, d, tEnter - 1e-4, (best?.t ?: tExit) + 1e-4, scene.tints[id]) ?: continue
                best = h
            }
        }
        val displays = scene.displays[cell(x, y, z)]
        if (displays != null) for (b in displays) for (q in b.quads) {
            val h = intersect(q, o, d, tEnter - 1e-4, (best?.t ?: tExit) + 1e-4, 0xffffff) ?: continue
            best = h
        }
        return best
    }

    private fun intersect(q: Quad, o: Vector3d, d: Vector3d, tMin: Double, tMax: Double, tint: Int): Hit? {
        val n = q.normal
        val denom = d.x * n.x + d.y * n.y + d.z * n.z
        if (abs(denom) < 1e-9) return null
        val t = ((q.origin.x - o.x) * n.x + (q.origin.y - o.y) * n.y + (q.origin.z - o.z) * n.z) / denom
        if (t < tMin || t > tMax || t <= 0) return null
        val rx = o.x + d.x * t - q.origin.x
        val ry = o.y + d.y * t - q.origin.y
        val rz = o.z + d.z * t - q.origin.z
        val u = q.edgeU; val v = q.edgeV
        val uu = u.x * u.x + u.y * u.y + u.z * u.z
        val vv = v.x * v.x + v.y * v.y + v.z * v.z
        if (uu == 0.0 || vv == 0.0) return null
        val s = (rx * u.x + ry * u.y + rz * u.z) / uu
        val w = (rx * v.x + ry * v.y + rz * v.z) / vv
        if (s < 0 || s > 1 || w < 0 || w > 1) return null
        val color: Int
        if (q.texture != null) {
            val tu = q.uv[0] + s * (q.uv[2] - q.uv[0]) + w * (q.uv[4] - q.uv[0])
            val tv = q.uv[1] + s * (q.uv[3] - q.uv[1]) + w * (q.uv[5] - q.uv[1])
            val texel = q.texture.sample(tu - floor(tu).let { if (tu == 1.0) 1e-9 else it }, tv - floor(tv).let { if (tv == 1.0) 1e-9 else it })
            if ((texel ushr 24) < 100) return null
            color = if (q.tint >= 0) scale(texel and 0xffffff, 1.0, tint) else texel and 0xffffff
        } else if (q.color == 0) return null
        else color = q.color and 0xffffff
        // Faces seen from behind (plants, panes) are lit like their front.
        val normal = if (denom > 0) Vector3d(n).negate() else n
        return Hit(t, color, normal)
    }

    /** Direction of a yaw/pitch, for captions. */
    fun facing(yaw: Double): String {
        val names = listOf("south", "south-west", "west", "north-west", "north", "north-east", "east", "south-east")
        return names[Math.floorMod(Math.round(yaw / 45.0).toInt(), 8)]
    }

    /** Yaw and pitch looking from [from] to [to]. */
    fun lookAt(from: Vector3d, to: Vector3d): Pair<Double, Double> {
        val d = Vector3d(to).sub(from)
        val yaw = Math.toDegrees(kotlin.math.atan2(-d.x, d.z))
        val pitch = Math.toDegrees(-kotlin.math.atan2(d.y, sqrt(d.x * d.x + d.z * d.z)))
        return yaw to pitch
    }
}
