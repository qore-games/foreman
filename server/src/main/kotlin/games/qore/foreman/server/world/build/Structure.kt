package games.qore.foreman.server.world.build

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Shapes
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import java.util.Random
import kotlin.math.max
import kotlin.math.min

/** Materials of a building. */
data class Style(
    val wall: String, val trim: String, val base: String, val floor: String, val slab: String, val inner: String,
    val paint: String, val roof: String, val roofStairs: String, val parapet: String?, val window: String,
    val door: String, val entrance: String, val stairs: String, val pillar: String?, val railing: String,
) {
    companion object {
        val KEYS = listOf("wall", "trim", "base", "floor", "slab", "inner", "paint", "roof", "roof_stairs", "parapet", "window", "door", "entrance", "stairs", "pillar", "railing")

        /** The style the materials a style leaves out come from, unless it names another with `extends`. */
        const val BASE = "brick"

        /**
         * The style [name] of [presets] (catalog/buildings.yml's and the world profile's, `{extends?: <style>, wall: …}`)
         * with [overrides] (keys as in the fields: wall, trim, base, …). parapet and pillar are optional: left out, null,
         * "" or "none" is none.
         */
        fun of(name: String, overrides: JsonObject?, presets: JsonObject): Style {
            val m = materials(name, presets, emptyList()).toMutableMap()
            overrides?.entrySet()?.forEach { (k, v) ->
                if (k !in KEYS) throw ToolError("Unknown material '$k': use ${KEYS.joinToString(", ")}")
                m[k] = v.takeIf { !it.isJsonNull }?.asString
            }
            fun on(k: String) = m[k]?.takeIf { it.isNotEmpty() && it != "none" }
            fun o(k: String) = on(k) ?: throw ToolError("Style '$name' has no $k: give it one, or extend a style that has")
            return Style(
                o("wall"), o("trim"), o("base"), o("floor"), o("slab"), o("inner"), o("paint"), o("roof"), o("roof_stairs"),
                on("parapet"), o("window"), o("door"), o("entrance"), o("stairs"), on("pillar"), o("railing"),
            )
        }

        /** The materials [name] sets, over those of the style it extends ([seen]: the styles extending it, against loops). */
        private fun materials(name: String, presets: JsonObject, seen: List<String>): Map<String, String?> {
            if (name in seen) throw ToolError("Style '$name' extends itself (${(seen + name).joinToString(" > ")})")
            val p = presets.getAsJsonObject(name) ?: throw ToolError("Unknown style '$name' (${presets.keySet().joinToString(", ")})")
            p.keySet().firstOrNull { it != "extends" && it !in KEYS }?.let { throw ToolError("Style '$name': unknown material '$it' (use ${KEYS.joinToString(", ")})") }
            val from = p.get("extends")?.asString ?: BASE.takeIf { it != name && presets.has(it) }
            val out = from?.let { materials(it, presets, seen + name).toMutableMap() } ?: HashMap()
            p.entrySet().filter { it.key != "extends" }.forEach { (k, v) -> out[k] = v.takeIf { !it.isJsonNull }?.asString }
            return out
        }
    }
}

/** A room of a storey, inner cells u0..u1 x v0..v1 (local), [level] the storey (-1 = basement). */
class Room(val level: Int, val u0: Int, val v0: Int, val u1: Int, val v1: Int, var type: String, val name: String) {
    val width get() = u1 - u0 + 1
    val depth get() = v1 - v0 + 1
}

/** A 2-wide (or [width]) opening in a wall: cells along the wall from (u, v), the wall runs along [alongU]. */
class Opening(val level: Int, val u: Int, val v: Int, val alongU: Boolean, val width: Int = 2, val door: String? = null, val outward: String = "front")

/**
 * A stair core: two 2-wide lanes at u0..u0+1 and u0+4..u0+5, flights over v4..v4+H-1, the 2-wide passage between them
 * joining the front and back landings on every storey (no railing there: it would cut one landing off).
 */
class Core(val u0: Int, val v0: Int, val v1: Int)

/** Everything the layout decides, before any block is placed. */
class Layout(val rooms: MutableList<Room> = ArrayList(), val walls: MutableList<IntArray> = ArrayList(), val openings: MutableList<Opening> = ArrayList(), val cores: MutableList<Core> = ArrayList())

/**
 * The spec of a building (the build_structure tool's arguments) and its layout per archetype. Floors are [floorHeight]
 * apart: slab at y, 4 blocks of air for the default 5. Local frame: see [Frame]. [uses]: room types in order by building
 * use (catalog/buildings.yml's and the world profile's).
 */
class Spec(
    val name: String, val frame: Frame, val ground: Int, val floors: Int, val floorHeight: Int, val archetype: String,
    val style: Style, val roof: String, val basement: Boolean, val use: String?, val roomTypes: List<String>,
    val roomWidth: Int, val sections: Int, val flatWidth: Int, val decay: Double, val dark: Boolean, val clutter: Double,
    val furnish: Boolean, val seed: Long, val uses: Map<String, List<String>>,
) {
    val w get() = frame.width
    val d get() = frame.depth
    fun slab(level: Int) = ground + 1 + level * floorHeight
    val top get() = slab(floors)
    val lowest get() = if (basement) -1 else 0

    fun layout(): Layout = when (archetype) {
        "corridor" -> corridor()
        "sections" -> sections()
        "hall" -> hall()
        "house" -> house()
        else -> throw ToolError("Unknown archetype '$archetype' (corridor, sections, hall, house)")
    }

    private fun types(): Iterator<String> {
        val list = roomTypes.ifEmpty {
            use?.let { uses[it] ?: throw ToolError("Unknown use '$it' (${uses.keys.joinToString(", ")})") } ?: uses["office"] ?: listOf("office", "storage", "bathroom")
        }
        return generateSequence(0) { it + 1 }.map { list[it % list.size] }.iterator()
    }

    /** Splits [a]..[b] into parts of about [target] (at least 6 inside), separated by 1-wide walls. */
    private fun split(a: Int, b: Int, target: Int): List<IntRange> {
        val len = b - a + 1
        val n = max(1, (len + 1) / (target + 1))
        val inner = len - (n - 1)
        val base = inner / n
        var rest = inner % n
        val out = ArrayList<IntRange>()
        var s = a
        repeat(n) {
            val size = base + if (rest-- > 0) 1 else 0
            out += s until s + size
            s += size + 1
        }
        return out
    }

    /** Corridor down the long side, rooms on both sides, stair cores at the ends; entrance lobby in the middle. */
    private fun corridor(): Layout {
        if (d < 19) throw ToolError("corridor buildings need depth >= 19 (rooms of 6 on both sides of a 3-wide corridor)")
        if (w < 22) throw ToolError("corridor buildings need width >= 22")
        val out = Layout()
        val rf = (d - 7) / 2
        val cv0 = 1 + rf + 1
        val cv1 = cv0 + 2
        val coreRight = w >= 46
        val left = Core(1, 1, d - 2)
        out.cores += left
        if (coreRight) out.cores += Core(w - 7, 1, d - 2)
        val ua = 8
        val ub = if (coreRight) w - 9 else w - 2
        val t = types()
        for (level in lowest until floors) {
            out.walls += intArrayOf(level, 7, 1, 7, d - 2)
            if (coreRight) out.walls += intArrayOf(level, w - 8, 1, w - 8, d - 2)
            out.openings += Opening(level, 7, cv0, false, 3)
            if (coreRight) out.openings += Opening(level, w - 8, cv0, false, 3)
            out.walls += intArrayOf(level, ua, cv0 - 1, ub, cv0 - 1)
            out.walls += intArrayOf(level, ua, cv1 + 1, ub, cv1 + 1)
            out.rooms += Room(level, ua, cv0, ub, cv1, "corridor", "L$level corridor")
            for ((strip, vs) in listOf("front" to (1..cv0 - 2), "back" to (cv1 + 2..d - 2))) {
                val parts = split(ua, ub, roomWidth)
                parts.forEachIndexed { i, r ->
                    if (i > 0) out.walls += intArrayOf(level, r.first - 1, vs.first, r.first - 1, vs.last)
                    val lobby = level == 0 && strip == "front" && i == parts.size / 2
                    val type = if (lobby) "lobby" else if (level == -1) ((uses["basement"] ?: listOf("storage")).let { it[(r.first + vs.first) % it.size] }) else t.next()
                    out.rooms += Room(level, r.first, vs.first, r.last, vs.last, type, "L$level $strip ${i + 1}")
                    val du = (r.first + r.last) / 2 - 1
                    val wallV = if (strip == "front") cv0 - 1 else cv1 + 1
                    out.openings += Opening(level, du, wallV, true, if (lobby) 3 else 2, if (lobby) null else style.door, if (strip == "front") "front" else "back")
                    if (lobby) out.openings += Opening(level, du, 0, true, 2, style.entrance, "front")
                }
            }
        }
        // Stair halls double as rooms players cross: count them for the furnishing pass (nothing goes in them).
        for (c in out.cores) for (level in lowest until floors) out.rooms += Room(level, c.u0, c.v0, c.u0 + 5, c.v1, "stairwell", "L$level stairs ${c.u0}")
        return out
    }

    /** Apartment block: sections side by side, each a stair core between two flats; the entrance in each core's front. */
    private fun sections(): Layout {
        val out = Layout()
        val fw = flatWidth
        val need = 1 + sections * (2 * fw + 9)
        if (w < need) throw ToolError("$sections sections of flats $fw wide need width >= $need")
        if (d < 15) throw ToolError("sections need depth >= 15 (stair flights and landings)")
        val vm = d / 2
        var u = 1
        repeat(sections) { s ->
            val flatA = u until u + fw
            val coreU = u + fw + 1
            val flatB = coreU + 7 until coreU + 7 + fw
            val core = Core(coreU, 1, d - 2)
            out.cores += core
            for (level in lowest until floors) {
                out.walls += intArrayOf(level, coreU - 1, 1, coreU - 1, d - 2)
                out.walls += intArrayOf(level, coreU + 6, 1, coreU + 6, d - 2)
                if (s < sections - 1) out.walls += intArrayOf(level, flatB.last + 1, 1, flatB.last + 1, d - 2)
                out.rooms += Room(level, coreU, 1, coreU + 5, d - 2, "stairwell", "L$level stairs ${s + 1}")
                if (level == 0) out.openings += Opening(level, coreU + 2, 0, true, 2, style.entrance, "front")
                for ((side, flat) in listOf("left" to flatA, "right" to flatB)) {
                    val wallU = if (side == "left") coreU - 1 else coreU + 6
                    out.openings += Opening(level, wallU, 1, false, 2, style.door, side)
                    out.walls += intArrayOf(level, flat.first, vm, flat.last, vm)
                    val opU = if (side == "left") flat.last - 2 else flat.first + 1
                    out.openings += Opening(level, opU, vm, true, 2)
                    val name = "L$level flat ${s + 1}${side.first()}"
                    out.rooms += Room(level, flat.first, 1, flat.last, vm - 1, if (level == -1) "storage" else "living", "$name front")
                    out.rooms += Room(level, flat.first, vm + 1, flat.last, d - 2, if (level == -1) "storage" else if ((s + level) % 2 == 0) "kitchen" else "bedroom", "$name back")
                }
            }
            u = flatB.last + 2
        }
        return out
    }

    private fun hall(): Layout {
        val out = Layout()
        for (level in lowest until floors) out.rooms += Room(level, 1, 1, w - 2, d - 2, roomTypes.firstOrNull() ?: "hall", "L$level hall")
        out.openings += Opening(0, w / 2 - 2, 0, true, 4, null, "front")
        if (floors > 1) out.cores += Core(1, 1, min(d - 2, 13))
        return out
    }

    private fun house(): Layout {
        if (w < 13 || d < 13) throw ToolError("houses need 13 x 13 or more (rooms of 6)")
        val out = Layout()
        val um = w / 2
        val vm = d / 2
        val t = types()
        val core = if (floors > 1 || basement) Core(1, 1, d - 2) else null
        core?.let { out.cores += it }
        val u0 = if (core != null) 8 else 1
        for (level in lowest until floors) {
            if (core != null) {
                out.walls += intArrayOf(level, 7, 1, 7, d - 2)
                out.openings += Opening(level, 7, vm - 1, false, 2)
                out.rooms += Room(level, 1, 1, 6, d - 2, "stairwell", "L$level stairs")
            }
            val cols = if (w - 1 - u0 >= 14) listOf(u0..um - 1, um + 1..w - 2) else listOf(u0..w - 2)
            cols.forEachIndexed { i, c ->
                if (i > 0) { out.walls += intArrayOf(level, um, 1, um, d - 2); out.openings += Opening(level, um, 2, false, 2); out.openings += Opening(level, um, d - 4, false, 2) }
                if (d >= 15) {
                    out.walls += intArrayOf(level, c.first, vm, c.last, vm)
                    out.openings += Opening(level, (c.first + c.last) / 2 - 1, vm, true, 2)
                    out.rooms += Room(level, c.first, 1, c.last, vm - 1, if (level == 0 && i == 0) "living" else t.next(), "L$level ${i + 1} front")
                    out.rooms += Room(level, c.first, vm + 1, c.last, d - 2, if (level == 0 && i == 0) "kitchen" else t.next(), "L$level ${i + 1} back")
                } else out.rooms += Room(level, c.first, 1, c.last, d - 2, t.next(), "L$level ${i + 1}")
            }
        }
        out.openings += Opening(0, (u0 + w - 2) / 2 - 1, 0, true, 2, style.entrance, "front")
        return out
    }
}

/** Places the blocks of a [Spec] with its [Layout]. Furnishing and decay are done by the tool after a flush. */
class Compiler(private val spec: Spec, private val layout: Layout, private val out: Writer) {
    private val s = spec.style
    private val rng = Random(spec.seed)
    private val w = spec.w
    private val d = spec.d
    private val h = spec.floorHeight

    fun build() {
        val g = spec.ground
        val lo = spec.slab(spec.lowest)
        // Clear the volume (and a ring around for the canopy and scaffolding), foundation under it.
        out.air(-1, g + 1, -1, w, spec.top + 8, d)
        if (spec.basement) out.air(0, lo, 0, w - 1, g, d - 1)
        out.fill(0, lo - 3, 0, w - 1, lo - 1, d - 1, s.base)
        for (level in spec.lowest until spec.floors) storey(level)
        out.fill(0, spec.top, 0, w - 1, spec.top, d - 1, s.slab)
        roof()
        layout.cores.forEach { stairs(it) }
        // Openings last: they cut through walls and slabs placed above.
        layout.openings.forEach { opening(it) }
        if (spec.archetype == "sections") canopies()
    }

    private fun storey(level: Int) {
        val y = spec.slab(level)
        out.fill(0, y, 0, w - 1, y, d - 1, s.slab)
        out.fill(1, y, 1, w - 2, y, d - 2, s.floor)
        out.fill(0, y + 1, 0, w - 1, y + h - 1, d - 1, s.wall, walls = true)
        out.fill(0, y, 0, w - 1, y, d - 1, s.trim, walls = true)
        if (level <= 0) out.fill(0, y + 1, 0, w - 1, y + 1, d - 1, s.base, walls = true)
        s.pillar?.let { p -> for ((u, v) in listOf(0 to 0, w - 1 to 0, 0 to d - 1, w - 1 to d - 1)) out.fill(u, y, v, u, y + h - 1, v, p) }
        // Joints every 6 along the long walls, like prefab panels.
        if (spec.archetype == "sections") for (u in 0 until w step 6) { out.fill(u, y + 1, 0, u, y + h - 1, 0, s.trim); out.fill(u, y + 1, d - 1, u, y + h - 1, d - 1, s.trim) }
        layout.walls.filter { it[0] == level }.forEach { (_, u0, v0, u1, v1) -> out.fill(u0, y + 1, v0, u1, y + h - 1, v1, s.inner) }
        // Two-tone paint on the lower wall inside rooms, windows in the outside walls.
        layout.rooms.filter { it.level == level && it.type != "stairwell" && it.type != "corridor" }.forEach { r ->
            out.fill(r.u0 - 1, y + 1, r.v0 - 1, r.u1 + 1, y + 1, r.v1 + 1, s.paint, walls = true)
            if (level >= 0) windows(r, y)
        }
        if (level >= 0) layout.rooms.filter { it.level == level && it.type == "corridor" }.forEach { r ->
            out.fill(r.u0 - 1, y + 1, r.v0 - 1, r.u1 + 1, y + 1, r.v1 + 1, s.paint, walls = true)
        }
    }

    private fun windowBlock(): String = when {
        spec.dark && rng.nextDouble() < 0.75 -> listOf("spruce_planks", "dark_oak_planks", s.railing, "spruce_planks")[rng.nextInt(4)]
        rng.nextDouble() < spec.decay * 0.5 -> "air"
        else -> s.window
    }

    /** 2-wide windows every 4 blocks along each outside wall of the room. */
    private fun windows(r: Room, y: Int) {
        fun along(a: Int, b: Int, put: (Int) -> Unit) {
            var u = a + 1
            while (u + 1 <= b - 1) { put(u); u += 4 }
        }
        val block = { windowBlock() }
        if (r.v0 == 1) along(r.u0, r.u1) { u -> out.fill(u, y + 2, 0, u + 1, y + 3, 0, block()) }
        if (r.v1 == d - 2) along(r.u0, r.u1) { u -> out.fill(u, y + 2, d - 1, u + 1, y + 3, d - 1, block()) }
        if (r.u0 == 1) along(r.v0, r.v1) { v -> out.fill(0, y + 2, v, 0, y + 3, v + 1, block()) }
        if (r.u1 == w - 2) along(r.v0, r.v1) { v -> out.fill(w - 1, y + 2, v, w - 1, y + 3, v + 1, block()) }
    }

    private fun roof() {
        val top = spec.top
        when (spec.roof) {
            "flat" -> s.parapet?.let { out.fill(0, top + 1, 0, w - 1, top + 1, d - 1, it, walls = true) }
            else -> {
                val f = out.frame
                val stairs = Shapes.Palette(s.roofStairs, rng, { it.createBlockData() is Stairs }, "a stairs block")
                val slabId = s.roofStairs.substringBefore('[').removeSuffix("_stairs") + "_slab"
                val slab = Shapes.Palette(if (Material.matchMaterial(slabId) != null) slabId else "smooth_stone_slab", rng, { it.createBlockData() is Slab }, "a slab")
                val axisX = (f.facing == BlockFace.NORTH || f.facing == BlockFace.SOUTH) == (w >= d)
                Shapes.roof(
                    out.session,
                    Shapes.Roof(f.x0, f.z0, f.x1, f.z1, top + 1, spec.roof, axisX, BlockFace.NORTH, stairs, slab, null, out.pattern(s.wall), 1, true),
                )
            }
        }
    }

    /** Two lanes: even storeys climb toward the back on the left lane, odd ones toward the front on the right lane. */
    private fun stairs(c: Core) {
        val flight = 4 until 4 + h
        for (level in spec.lowest until spec.floors - 1) {
            val y = spec.slab(level)
            val up = (level - spec.lowest) % 2 == 0
            val lane = if (up) c.u0 to c.u0 + 1 else c.u0 + 4 to c.u0 + 5
            out.air(lane.first, y + h, c.v0 + flight.first, lane.second, y + h, c.v0 + flight.last)
            for (i in 0 until h) {
                val v = c.v0 + if (up) flight.first + i else flight.last - i
                for (u in lane.first..lane.second) {
                    out.set(u, y + 1 + i, v, out.facing(s.stairs, if (up) "back" else "front") .replace("]", ",half=bottom]"))
                    for (yy in y + 1 until y + 1 + i) out.set(u, yy, v, s.slab)
                    out.air(u, y + 2 + i, v, u, y + 4 + i, v)
                }
            }
        }
        // The roof hatch: a ladder up the back of the core on the top storey.
        if (spec.roof == "flat") {
            val y = spec.slab(spec.floors - 1)
            val v = c.v1
            for (yy in y + 1..spec.top) out.set(c.u0 + 2, yy, v, out.facing("ladder", "front"))
            out.set(c.u0 + 2, spec.top, v, "air")
            out.set(c.u0 + 2, spec.top + 1, v, "air")
        }
    }

    private fun opening(o: Opening) {
        val y = spec.slab(o.level)
        val cells = (0 until o.width).map { k -> if (o.alongU) (o.u + k) to o.v else o.u to (o.v + k) }
        cells.forEach { (u, v) -> out.air(u, y + 1, v, u, y + 3, v) }
        // Doors out of the building on the ground floor: a slab half-step outside, the floor being a block up.
        if (o.level == 0) {
            val out1 = cells.map { (u, v) -> when { v == 0 -> u to -1; v == d - 1 -> u to d; u == 0 -> -1 to v; u == w - 1 -> w to v; else -> null } }
            out1.filterNotNull().forEach { (u, v) -> out.set(u, spec.ground + 1, v, "${s.base.substringBefore('[').let { b -> listOf("${b}_slab", "${b.removeSuffix("s")}_slab").firstOrNull { Material.matchMaterial(it) != null } ?: "smooth_stone_slab" }}[type=bottom]") }
        }
        val door = o.door ?: return
        if (o.width != 2) return
        // A double door: the left leaf (lower u or v toward the frame's right) hinge left, the other right.
        val facing = out.frame.dir(o.outward)
        val world = cells.map { (u, v) -> out.frame.at(u, y + 1, v) }
        val right = when (facing) { BlockFace.NORTH -> BlockFace.EAST; BlockFace.EAST -> BlockFace.SOUTH; BlockFace.SOUTH -> BlockFace.WEST; else -> BlockFace.NORTH }
        val (a, b) = if (world[1].subtract(world[0]) == BlockVector3.at(right.modX, 0, right.modZ)) world[0] to world[1] else world[1] to world[0]
        for ((p, hinge) in listOf(a to "left", b to "right")) {
            out.session.setBlock(p, out.pattern("$door[facing=${facing.name.lowercase()},half=lower,hinge=$hinge]"))
            out.session.setBlock(p.add(0, 1, 0), out.pattern("$door[facing=${facing.name.lowercase()},half=upper,hinge=$hinge]"))
        }
    }

    /** Apartment blocks: a concrete canopy on two posts and a step over each stairwell entrance. */
    private fun canopies() {
        val y = spec.slab(0)
        layout.cores.forEach { c ->
            out.fill(c.u0, y + 4, -3, c.u0 + 5, y + 4, -1, "smooth_stone_slab[type=top]")
            out.fill(c.u0, spec.ground + 1, -3, c.u0, y + 3, -3, "andesite_wall")
            out.fill(c.u0 + 5, spec.ground + 1, -3, c.u0 + 5, y + 3, -3, "andesite_wall")
            out.fill(c.u0 + 1, y, -2, c.u0 + 4, y, -1, "smooth_stone_slab[type=bottom]")
        }
    }

    companion object {
        fun array(vararg v: Int) = JsonArray().apply { v.forEach { add(it) } }
    }
}

/** Runs a Decor op (weather, overgrow) from code. */
internal fun decorOp(op: String, box: Box, extra: Map<String, Any>): JsonObject = JsonObject().apply {
    addProperty("op", op)
    add("from", Compiler.array(box.min.x(), box.min.y(), box.min.z()))
    add("to", Compiler.array(box.max.x(), box.max.y(), box.max.z()))
    extra.forEach { (k, v) ->
        when (v) {
            is Number -> addProperty(k, v)
            is String -> addProperty(k, v)
            is List<*> -> add(k, JsonArray().apply { v.forEach { add(it.toString()) } })
        }
    }
}
