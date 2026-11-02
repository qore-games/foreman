package games.qore.foreman.server.world

import games.qore.foreman.server.mcp.ToolError
import com.sk89q.worldedit.math.BlockVector3
import org.bukkit.Bukkit
import org.bukkit.HeightMap
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.Tag
import org.bukkit.World
import org.bukkit.block.data.BlockData
import kotlin.math.max

object Blocks {
    // Listed explicitly: block tags come from datapacks and a server's may not contain the vanilla entries.
    private val NATURAL_EXTRA = setOf(
        Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.MYCELIUM,
        Material.ROOTED_DIRT, Material.MOSS_BLOCK, Material.MUD, Material.SAND, Material.RED_SAND, Material.STONE,
        Material.GRANITE, Material.DIORITE, Material.ANDESITE, Material.DEEPSLATE, Material.TUFF, Material.GRAVEL, Material.CLAY, Material.SNOW_BLOCK, Material.POWDER_SNOW, Material.SANDSTONE,
        Material.RED_SANDSTONE, Material.DIRT_PATH, Material.FARMLAND, Material.PACKED_ICE, Material.ICE,
        Material.BLUE_ICE, Material.SUSPICIOUS_SAND, Material.SUSPICIOUS_GRAVEL, Material.BEDROCK, Material.OBSIDIAN,
    )

    /**
     * Terrain that terrain tools may raise, lower or smooth. Everything else solid is treated as a build. Terracotta and
     * calcite are left out: builds use them far more than terrain does (plaster, tiles, white walls).
     */
    fun isNaturalGround(m: Material) = Tag.DIRT.isTagged(m) || Tag.BASE_STONE_OVERWORLD.isTagged(m) ||
        Tag.SAND.isTagged(m) || m in NATURAL_EXTRA || m.name.endsWith("_ORE")

    fun isLiquid(m: Material) = m == Material.WATER || m == Material.LAVA || m == Material.BUBBLE_COLUMN

    /** Air, plants, leaves, snow layers: what sits on top of the ground and is skipped when looking for it. */
    fun isCover(m: Material) = m.isAir || Tag.LEAVES.isTagged(m) || m == Material.SNOW || (!m.isSolid && !isLiquid(m))

    /** Removable plants (not leaves): cleared when a path or terrain change goes through them. */
    fun isPlant(m: Material) = !m.isAir && !m.isSolid && !isLiquid(m)

    /** What a raised column is filled with under its surface block. */
    fun subsurface(top: Material): Material = when (top) {
        Material.GRASS_BLOCK, Material.PODZOL, Material.MYCELIUM, Material.DIRT_PATH, Material.FARMLAND,
        Material.COARSE_DIRT, Material.ROOTED_DIRT, Material.MOSS_BLOCK -> Material.DIRT
        Material.SNOW_BLOCK -> Material.DIRT
        else -> top
    }

    /** A block state as `oak_stairs[facing=north,half=bottom]`, without the namespace and the waterlogged noise. */
    fun id(data: BlockData): String = data.getAsString(true)
        .removePrefix("minecraft:")
        .replace(",waterlogged=false", "")
        .replace("waterlogged=false,", "")
        .replace("[waterlogged=false]", "")

    fun id(m: Material): String = m.key.key

    /** The top solid block of a column, skipping [isCover] blocks and liquids. */
    data class Surface(val y: Int, val material: Material, val underLiquid: Boolean) {
        val natural get() = isNaturalGround(material)
    }

    fun surface(world: World, x: Int, z: Int): Surface {
        var y = world.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE)
        var liquid = false
        while (y > world.minHeight) {
            val m = world.getType(x, y, z)
            if (isLiquid(m)) liquid = true
            else if (!isCover(m)) break
            y--
        }
        return Surface(y, world.getType(x, y, z), liquid)
    }

    /**
     * The walkable ground of a column: [surface], unless that is a build overhead (eaves, a roof overhang, a bridge)
     * with natural ground and open space under it, then that ground. Inside a build there is none: its top is returned.
     */
    fun ground(world: World, x: Int, z: Int): Surface {
        val top = surface(world, x, z)
        if (top.natural || top.material.isAir) return top
        for (y in top.y - 1 downTo max(world.minHeight, top.y - 32)) {
            val m = world.getType(x, y, z)
            if (isLiquid(m)) return Surface(y, m, true)
            if (isNaturalGround(m) && isCover(world.getType(x, y + 1, z))) return Surface(y, m, false)
        }
        return top
    }

    /** Matches materials against `oak_door`, `*_door` (glob) or `#doors` (block tag), comma separated. */
    fun matcher(spec: String): (Material) -> Boolean {
        val matchers = spec.split(",").map { it.trim().lowercase().removePrefix("minecraft:") }.filter { it.isNotEmpty() }.map { token ->
            when {
                token.startsWith("#") -> {
                    val tag = Bukkit.getTag(Tag.REGISTRY_BLOCKS, NamespacedKey.minecraft(token.substring(1)), Material::class.java)
                        ?: throw ToolError("Unknown block tag $token")
                    val f: (Material) -> Boolean = { tag.isTagged(it) }
                    f
                }

                token.contains("*") -> {
                    val regex = Regex(token.split("*").joinToString(".*") { Regex.escape(it) })
                    val f: (Material) -> Boolean = { regex.matches(it.key.key) }
                    f
                }

                else -> {
                    val material = Material.matchMaterial(token) ?: throw ToolError("Unknown block $token")
                    val f: (Material) -> Boolean = { it == material }
                    f
                }
            }
        }
        return { m -> matchers.any { it(m) } }
    }
}

/** An axis-aligned block box, inclusive. */
data class Box(val min: BlockVector3, val max: BlockVector3) {
    val sizeX get() = max.x() - min.x() + 1
    val sizeY get() = max.y() - min.y() + 1
    val sizeZ get() = max.z() - min.z() + 1
    val volume get() = sizeX.toLong() * sizeY * sizeZ

    fun contains(x: Int, y: Int, z: Int) =
        x in min.x()..max.x() && y in min.y()..max.y() && z in min.z()..max.z()

    fun describe() = "${fmt(min)} to ${fmt(max)} (${sizeX}x${sizeY}x${sizeZ})"

    fun union(other: Box) = Box(min.getMinimum(other.min), max.getMaximum(other.max))

    fun grow(x: Int, y: Int, z: Int) = Box(min.subtract(x, y, z), max.add(x, y, z))

    fun containsBox(other: Box) = contains(other.min.x(), other.min.y(), other.min.z()) && contains(other.max.x(), other.max.y(), other.max.z())

    /** The common part, or null when they do not overlap. */
    fun intersect(other: Box): Box? {
        val lo = min.getMaximum(other.min)
        val hi = max.getMinimum(other.max)
        return if (lo.x() <= hi.x() && lo.y() <= hi.y() && lo.z() <= hi.z()) Box(lo, hi) else null
    }

    fun requireVolume(limit: Long, what: String): Box {
        if (volume > limit) throw ToolError("$what is limited to $limit blocks, this box has $volume: split it")
        return this
    }

    companion object {
        fun of(a: BlockVector3, b: BlockVector3) = Box(a.getMinimum(b), a.getMaximum(b))

        fun fmt(v: BlockVector3) = "[${v.x()}, ${v.y()}, ${v.z()}]"
    }
}
