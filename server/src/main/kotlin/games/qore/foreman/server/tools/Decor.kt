package games.qore.foreman.server.tools

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.world.Blocks
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Nature
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.BlockData
import org.bukkit.block.data.MultipleFacing
import org.bukkit.block.data.type.Door
import org.bukkit.block.data.type.Stairs
import java.util.Random

/** Edit ops for walkable stairs and for wear and abandonment in patches (the edit tool's stairs, weather, overgrow). */
object Decor {
    private val SIDES = listOf(BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST)

    private fun face(name: String?): BlockFace = runCatching { BlockFace.valueOf(name!!.uppercase()) }.getOrNull()
        ?.takeIf { it in SIDES } ?: throw ToolError("toward must be north, south, east or west")

    private fun right(f: BlockFace) = when (f) {
        BlockFace.NORTH -> BlockFace.EAST; BlockFace.EAST -> BlockFace.SOUTH; BlockFace.SOUTH -> BlockFace.WEST; else -> BlockFace.NORTH
    }

    /** The full block a stairs block is cut from: oak_stairs → oak_planks, stone_brick_stairs → stone_bricks. */
    fun fullBlock(stairs: Material): Material? {
        val family = stairs.key.key.removeSuffix("_stairs")
        return listOf(family + "s", family, family + "_planks", family.removeSuffix("_brick") + "_bricks", family + "_block")
            .firstNotNullOfOrNull { Material.matchMaterial(it)?.takeIf { m -> m.isBlock && m.isSolid } }
    }

    fun stairs(session: EditSession, op: Args): EditTools.OpResult {
        val from = op.vec("from")
        val toward = face(op.string("toward", null))
        val rise = op.int("rise").coerceIn(1, 64)
        val width = op.int("width", 2).coerceIn(1, 8)
        val headroom = op.int("headroom", 3).coerceIn(2, 6)
        val id = op.string("block").removePrefix("minecraft:").substringBefore('[')
        val material = Material.matchMaterial(id)?.takeIf { it.createBlockData() is Stairs } ?: throw ToolError("block must be a stairs block, e.g. oak_stairs")
        val support = Edits.pattern(session, op.string("support", null) ?: fullBlock(material)?.key?.key ?: throw ToolError("No full block for $id: pass support"))
        val step = Edits.pattern(session, "$id[facing=${toward.name.lowercase()},half=bottom,shape=straight]")
        val air = BukkitAdapter.adapt(Material.AIR.createBlockData())
        val side = right(toward)
        var n = 0
        for (i in 0 until rise) for (w in 0 until width) {
            val p = from.add(toward.modX * i + side.modX * w, i, toward.modZ * i + side.modZ * w)
            if (session.setBlock(p, step)) n++
            for (y in from.y() until p.y()) session.setBlock(BlockVector3.at(p.x(), y, p.z()), support)
            for (h in 1..headroom) session.setBlock(p.add(0, h, 0), air)
        }
        // The landing at the top: room to step off.
        for (w in 0 until width) {
            val p = from.add(toward.modX * rise + side.modX * w, rise, toward.modZ * rise + side.modZ * w)
            for (h in 0 until headroom) session.setBlock(p.add(0, h, 0), air)
        }
        val end = from.add(toward.modX * rise + side.modX * (width - 1), rise + headroom, toward.modZ * rise + side.modZ * (width - 1))
        return EditTools.OpResult(n, Box.of(from, end), "climbs ${toward.name.lowercase()} from ${Box.fmt(from)} to the landing at y=${from.y() + rise}")
    }

    // ---- Weathering ----------------------------------------------------------------------------------------------

    // Variants are built materials: weathered walls must not turn into what the terrain tools take for ground.
    private val VARIANTS: Map<Material, List<Material>> = mapOf(
        Material.STONE_BRICKS to listOf(Material.CRACKED_STONE_BRICKS, Material.MOSSY_STONE_BRICKS),
        Material.COBBLESTONE to listOf(Material.MOSSY_COBBLESTONE),
        Material.DEEPSLATE_BRICKS to listOf(Material.CRACKED_DEEPSLATE_BRICKS),
        Material.DEEPSLATE_TILES to listOf(Material.CRACKED_DEEPSLATE_TILES),
        Material.POLISHED_BLACKSTONE_BRICKS to listOf(Material.CRACKED_POLISHED_BLACKSTONE_BRICKS),
        Material.NETHER_BRICKS to listOf(Material.CRACKED_NETHER_BRICKS),
        // Concrete stains in its own tones: no stone brick textures on a concrete wall.
        Material.WHITE_CONCRETE to listOf(Material.CALCITE, Material.WHITE_TERRACOTTA),
        Material.LIGHT_GRAY_CONCRETE to listOf(Material.POLISHED_ANDESITE, Material.LIGHT_GRAY_TERRACOTTA),
        Material.GRAY_CONCRETE to listOf(Material.POLISHED_TUFF, Material.GRAY_TERRACOTTA),
        Material.SMOOTH_STONE to listOf(Material.POLISHED_ANDESITE, Material.CRACKED_STONE_BRICKS),
        Material.STONE to listOf(Material.COBBLESTONE, Material.MOSSY_COBBLESTONE),
        Material.POLISHED_ANDESITE to listOf(Material.SMOOTH_STONE, Material.CRACKED_STONE_BRICKS),
        Material.QUARTZ_BLOCK to listOf(Material.CALCITE),
        Material.MUD_BRICKS to listOf(Material.PACKED_MUD),
        Material.OAK_PLANKS to listOf(Material.SPRUCE_PLANKS),
        Material.SPRUCE_PLANKS to listOf(Material.DARK_OAK_PLANKS),
        Material.BIRCH_PLANKS to listOf(Material.OAK_PLANKS),
        Material.CUT_COPPER to listOf(Material.EXPOSED_CUT_COPPER, Material.WEATHERED_CUT_COPPER),
        Material.EXPOSED_CUT_COPPER to listOf(Material.WEATHERED_CUT_COPPER, Material.OXIDIZED_CUT_COPPER),
        Material.TUFF_BRICKS to listOf(Material.POLISHED_TUFF),
        Material.BRICKS to listOf(Material.TERRACOTTA),
    )

    private val SHAPES = listOf("_stairs", "_slab", "_wall")

    /** A stairs/slab/wall [data] of the family of [base], turned into the same shape of [variant] (states kept), or null. */
    private fun shaped(data: BlockData, base: Material, variant: Material): BlockData? {
        val id = data.material.key.key
        val suffix = SHAPES.firstOrNull { id.endsWith(it) } ?: return null
        val family = id.removeSuffix(suffix)
        val baseKey = base.key.key
        val variantFamily = when (baseKey) {
            family -> variant.key.key
            family + "s" -> variant.key.key.removeSuffix("s")
            else -> return null
        }
        val target = Material.matchMaterial(variantFamily + suffix) ?: return null
        return runCatching { Bukkit.createBlockData(data.asString.replace("minecraft:$id", target.key.toString())) }.getOrNull()
    }

    private fun familyOf(m: Material, table: Map<Material, List<Material>>): Material? {
        if (m in table) return m
        val id = m.key.key
        val suffix = SHAPES.firstOrNull { id.endsWith(it) } ?: return null
        val family = id.removeSuffix(suffix)
        return listOf(family, family + "s").firstNotNullOfOrNull { Material.matchMaterial(it) }?.takeIf { it in table }
    }

    /** Positions of [cells] whose noise is in the top [share]: patches covering about that share, whatever the noise spread. */
    private fun <T> patches(cells: List<T>, share: Double, noise: (T) -> Double): List<T> {
        if (cells.isEmpty() || share <= 0) return emptyList()
        val values = cells.map { noise(it) }
        val sorted = values.sorted()
        val threshold = sorted[((1 - share.coerceIn(0.0, 1.0)) * (sorted.size - 1)).toInt()]
        return cells.filterIndexed { i, _ -> values[i] >= threshold }
    }

    fun weather(world: World, session: EditSession, box: Box, op: Args): EditTools.OpResult {
        val amount = op.double("amount", 0.25).coerceIn(0.0, 1.0)
        val patch = op.double("patch", 3.0).coerceIn(1.0, 16.0)
        val seed = op.int("seed", Random().nextInt()).toLong()
        val table = HashMap(VARIANTS)
        if (op.has("variants")) op.obj("variants").entrySet().forEach { (k, v) ->
            val base = Material.matchMaterial(k) ?: throw ToolError("Unknown block $k in variants")
            table[base] = v.asJsonArray.map { Material.matchMaterial(it.asString) ?: throw ToolError("Unknown block ${it.asString} in variants") }
        }
        // Only blocks that show: next to air or something see-through.
        val candidates = ArrayList<BlockVector3>()
        for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
            val m = world.getType(x, y, z)
            if (familyOf(m, table) == null) continue
            if (BlockFace.entries.take(6).none { f -> !world.getType(x + f.modX, y + f.modY, z + f.modZ).isOccluding }) continue
            candidates += BlockVector3.at(x, y, z)
        }
        val chosen = patches(candidates, amount) { p -> Nature.noise3(seed, p.x(), p.y(), p.z(), patch) }
        var n = 0
        for (p in chosen) {
            val data = world.getBlockData(p.x(), p.y(), p.z())
            val base = familyOf(data.material, table) ?: continue
            val variants = table.getValue(base)
            // A second, broader noise picks the variant, so each patch is mostly one kind of wear.
            val variant = variants[(Nature.noise3(seed + 99, p.x(), p.y(), p.z(), patch * 2.5) * variants.size).toInt().coerceIn(0, variants.size - 1)]
            val out = if (data.material == base) variant.createBlockData() else shaped(data, base, variant) ?: continue
            if (session.setBlock(p, BukkitAdapter.adapt(out))) n++
        }
        return EditTools.OpResult(n, box, "${chosen.size} of ${candidates.size} visible weatherable blocks changed in patches")
    }

    // ---- Overgrowth ----------------------------------------------------------------------------------------------

    /** Open to the sky: nothing solid above (light is not recomputed right after an edit, so it can't tell). */
    private fun sky(world: World, x: Int, y: Int, z: Int): Boolean {
        val top = world.getHighestBlockYAt(x, z, org.bukkit.HeightMap.MOTION_BLOCKING_NO_LEAVES)
        return y > top || (y + 1..top).none { world.getType(x, it, z).isOccluding }
    }

    fun overgrow(world: World, session: EditSession, box: Box, op: Args): EditTools.OpResult {
        val amount = op.double("amount", 0.3).coerceIn(0.0, 1.0)
        val seed = op.int("seed", Random().nextInt()).toLong()
        val kinds = op.strings("kinds").ifEmpty { listOf("vines", "moss", "grass", "litter", "rubble") }.toSet()
        kinds.firstOrNull { it !in setOf("vines", "moss", "grass", "litter", "cobwebs", "rubble") }?.let { throw ToolError("Unknown kind '$it'") }
        val placed = HashMap<String, Int>()
        val taken = HashSet<BlockVector3>()
        fun put(kind: String, p: BlockVector3, data: BlockData) {
            if (!taken.add(p)) return
            if (session.setBlock(p, BukkitAdapter.adapt(data))) placed.merge(kind, 1, Int::plus)
        }
        fun solid(x: Int, y: Int, z: Int) = world.getBlockAt(x, y, z).let { it.type.isOccluding && !Tag.LEAVES.isTagged(it.type) }
        fun nearDoor(x: Int, y: Int, z: Int) = (-1..1).any { dx -> (-1..1).any { dz -> (-1..0).any { dy -> world.getBlockData(x + dx, y + dy, z + dz) is Door } } }

        val air = ArrayList<BlockVector3>()
        for (x in box.min.x()..box.max.x()) for (y in box.min.y()..box.max.y()) for (z in box.min.z()..box.max.z()) {
            if (world.getType(x, y, z).isAir) air += BlockVector3.at(x, y, z)
        }

        if ("vines" in kinds) {
            val spots = air.filter { p -> sky(world, p.x(), p.y(), p.z()) && SIDES.any { f -> solid(p.x() + f.modX, p.y(), p.z() + f.modZ) } }
            for (p in patches(spots, amount * 0.35) { Nature.noise3(seed, it.x(), it.y() / 3, it.z(), 4.0) }) {
                val vine = Material.VINE.createBlockData() as MultipleFacing
                SIDES.forEach { f -> if (solid(p.x() + f.modX, p.y(), p.z() + f.modZ)) vine.setFace(f, true) }
                put("vines", p, vine)
            }
        }
        if ("moss" in kinds) {
            val spots = air.filter { p -> solid(p.x(), p.y() - 1, p.z()) && sky(world, p.x(), p.y(), p.z()) && !Blocks.isNaturalGround(world.getType(p.x(), p.y() - 1, p.z())) }
            for (p in patches(spots, amount * 0.4) { Nature.noise3(seed + 1, it.x(), it.y(), it.z(), 3.0) }) put("moss", p, Material.MOSS_CARPET.createBlockData())
        }
        if ("grass" in kinds) {
            val spots = air.filter { p ->
                val below = world.getType(p.x(), p.y() - 1, p.z())
                (below == Material.GRASS_BLOCK || below == Material.DIRT || below == Material.COARSE_DIRT || below == Material.PODZOL) &&
                    (-2..2).any { dx -> (-2..2).any { dz -> world.getType(p.x() + dx, p.y(), p.z() + dz).let { it.isSolid && !Blocks.isNaturalGround(it) } } }
            }
            val plants = listOf(Material.SHORT_GRASS, Material.SHORT_GRASS, Material.FERN, Material.SHORT_GRASS)
            for (p in patches(spots, amount * 0.8) { Nature.noise3(seed + 2, it.x(), it.y(), it.z(), 2.0) }) {
                if (world.getType(p.x(), p.y() - 1, p.z()) == Material.DIRT) continue
                put("grass", p, plants[(Nature.random(seed, p.x(), p.z()) * plants.size).toInt()].createBlockData())
            }
        }
        if ("litter" in kinds && Material.matchMaterial("leaf_litter") != null) {
            val spots = air.filter { p -> solid(p.x(), p.y() - 1, p.z()) && world.getType(p.x(), p.y() - 1, p.z()).let { it != Material.WATER } }
            for (p in patches(spots, amount * 0.25) { Nature.noise3(seed + 3, it.x(), it.y(), it.z(), 3.5) }) {
                put("litter", p, Bukkit.createBlockData("leaf_litter[segment_amount=${1 + (Nature.random(seed + 3, p.x(), p.z()) * 4).toInt().coerceAtMost(3)}]"))
            }
        }
        if ("cobwebs" in kinds) {
            val spots = air.filter { p ->
                !sky(world, p.x(), p.y(), p.z()) && solid(p.x(), p.y() + 1, p.z()) && SIDES.count { f -> solid(p.x() + f.modX, p.y(), p.z() + f.modZ) } >= 2
            }
            for (p in patches(spots, amount * 0.3) { Nature.noise3(seed + 4, it.x(), it.y(), it.z(), 2.0) }) put("cobwebs", p, Material.COBWEB.createBlockData())
        }
        if ("rubble" in kinds) {
            val spots = air.filter { p ->
                val below = world.getBlockAt(p.x(), p.y() - 1, p.z())
                below.type.isOccluding && !nearDoor(p.x(), p.y(), p.z()) && SIDES.any { f -> solid(p.x() + f.modX, p.y(), p.z() + f.modZ) }
            }
            for (p in patches(spots, amount * 0.12) { Nature.noise3(seed + 5, it.x(), it.y(), it.z(), 2.5) }) {
                val wall = SIDES.map { f -> world.getType(p.x() + f.modX, p.y(), p.z() + f.modZ) }.firstOrNull { it.isOccluding }
                val slab = wall?.let { w -> listOf(w.key.key.removeSuffix("s") + "_slab", w.key.key + "_slab").firstNotNullOfOrNull { Material.matchMaterial(it) } } ?: Material.COBBLESTONE_SLAB
                put("rubble", p, slab.createBlockData())
            }
        }
        return EditTools.OpResult(placed.values.sum(), box, if (placed.isEmpty()) "nothing to grow on" else placed.entries.joinToString(", ") { "${it.value} ${it.key}" })
    }
}
