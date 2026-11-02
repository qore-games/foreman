package games.qore.foreman.server.tools

import com.sk89q.worldedit.bukkit.BukkitAdapter
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Blocks
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Checks
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.Tag
import kotlin.math.abs
import kotlin.random.Random

/** Cultivated land: fields of crops in parcels, laid on the ground as it is. */
object LandTools {
    /** What a parcel grows: the soil and the plant on it (a block state with {age} filled at random), null for fallow. */
    private class Crop(val soil: String, val plant: String?, val maxAge: Int = 7)

    private val CROPS = mapOf(
        "wheat" to Crop("farmland[moisture=7]", "wheat[age={age}]"),
        "potatoes" to Crop("farmland[moisture=7]", "potatoes[age={age}]"),
        "carrots" to Crop("farmland[moisture=7]", "carrots[age={age}]"),
        "beetroots" to Crop("farmland[moisture=7]", "beetroots[age={age}]", 3),
        "hay" to Crop("grass_block", "tall_grass"),
        "fallow" to Crop("coarse_dirt", null),
    )

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.MAKE_FIELDS,
            "Lays fields over open ground: parcels of crops (wheat, potatoes, carrots, beetroots), hay meadow and fallow, " +
                "in rows along x or z with furrows, a track along their edges, following the ground as it is. Only on " +
                "natural grass/dirt that is gentle (no 2-block steps across a parcel); roads, builds, water, trees and " +
                "claims are skipped. decay 0..1 lets weeds, dead bushes and bare patches in (an abandoned field). " +
                "Fills plains that look empty.",
            schema {
                vec("from", "A corner (y ignored)")
                vec("to", "The opposite corner")
                strings("crops", "Parcel kinds to mix (default wheat, potatoes, hay, fallow): ${CROPS.keys.joinToString()}", required = false)
                enum("rows", listOf("x", "z"), "Direction of the rows (default the longer side)", required = false)
                int("parcel", "Parcel width across the rows (default 12)", required = false)
                number("decay", "0..1: weeds and bare patches (default 0.3)", required = false)
                bool("track", "A dirt track between parcels (default true)", required = false)
                int("seed", "Random seed", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> fields(plugin, args) },
    )

    private fun fields(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to"))
        if (box.sizeX.toLong() * box.sizeZ > 300L * 300) throw ToolError("Fields of 300x300 at most per call")
        val kinds = args.strings("crops").ifEmpty { listOf("wheat", "potatoes", "hay", "fallow") }
        kinds.firstOrNull { it !in CROPS }?.let { throw ToolError("Unknown crop '$it' (${CROPS.keys.joinToString()})") }
        val alongX = (args.string("rows", null) ?: if (box.sizeX >= box.sizeZ) "x" else "z") == "x"
        val parcel = args.int("parcel", 12).coerceIn(5, 40)
        val decay = args.double("decay", 0.3).coerceIn(0.0, 1.0)
        val track = args.bool("track", true)
        val rng = Random(args.int("seed", box.min.x() * 7 + box.min.z()))
        val claims = plugin.worlds.reserved(world.name).flatMap { it.boxes }

        // The ground: natural grass or dirt, no tree, not claimed.
        val ground = Sync.main {
            Chunks.keepLoaded(world, box)
            HashMap<Pair<Int, Int>, Int>().also { m ->
                for (x in box.min.x()..box.max.x()) for (z in box.min.z()..box.max.z()) {
                    val s = Blocks.surface(world, x, z)
                    if (s.underLiquid || s.material !in setOf(Material.GRASS_BLOCK, Material.DIRT, Material.COARSE_DIRT, Material.PODZOL, Material.ROOTED_DIRT)) continue
                    if (Checks.DEFAULT_ROADS(s.material) && s.material != Material.COARSE_DIRT) continue
                    if ((1..3).any { Tag.LOGS.isTagged(world.getType(x, s.y + it, z)) || Tag.LEAVES.isTagged(world.getType(x, s.y + it, z)) }) continue
                    if (claims.any { it.contains(x, s.y, z) }) continue
                    m[x to z] = s.y
                }
            }
        }
        // Parcels across the rows; each takes a kind, a track between them.
        val across = if (alongX) box.min.z()..box.max.z() else box.min.x()..box.max.x()
        val bands = ArrayList<Pair<IntRange, String?>>()
        var a = across.first
        while (a <= across.last) {
            val w = (parcel + rng.nextInt(-2, 3)).coerceAtLeast(4)
            bands += (a until minOf(a + w, across.last + 1)) to kinds[rng.nextInt(kinds.size)]
            a += w
            if (track && a <= across.last) { bands += (a..a) to null; a++ }
        }
        val counts = HashMap<String, Int>()
        val (_, change) = Edits.edit(world, "fields in ${box.describe()}") { session, _ ->
            fun set(x: Int, y: Int, z: Int, state: String) = session.setBlock(x, y, z, BukkitAdapter.adapt(Bukkit.createBlockData(state)))
            for ((range, kind) in bands) {
                // A parcel on a step of 2+ is not laid: rough ground stays pasture.
                val cells = ground.filterKeys { (x, z) -> (if (alongX) z else x) in range }
                if (kind != null && cells.any { (c, y) -> listOf(1 to 0, 0 to 1).any { (dx, dz) -> cells[(c.first + dx) to (c.second + dz)]?.let { abs(it - y) >= 2 } == true } }) {
                    counts.merge("skipped (steep)", 1, Int::plus); continue
                }
                val crop = kind?.let { CROPS[it] }
                for ((c, y) in cells) {
                    val (x, z) = c
                    val row = if (alongX) z - range.first else x - range.first
                    // Clear the plants on it first.
                    if (Blocks.isPlant(world.getType(x, y + 1, z))) set(x, y + 1, z, "air")
                    if (world.getType(x, y + 2, z) == Material.TALL_GRASS || world.getType(x, y + 2, z) == Material.LARGE_FERN) set(x, y + 2, z, "air")
                    when {
                        crop == null -> { set(x, y, z, if (rng.nextDouble() < 0.7) "dirt_path" else "coarse_dirt") }
                        rng.nextDouble() < decay * 0.35 -> {
                            // Weeds and bare patches.
                            set(x, y, z, if (rng.nextBoolean()) "coarse_dirt" else "grass_block")
                            set(x, y + 1, z, listOf("short_grass", "dead_bush", "short_dry_grass", "fern")[rng.nextInt(4)].let { if (Material.matchMaterial(it) == null) "short_grass" else it })
                        }
                        crop.plant == null -> set(x, y, z, crop.soil)
                        crop.soil.startsWith("farmland") && row % 4 == 3 -> set(x, y, z, "coarse_dirt") // a furrow
                        else -> {
                            set(x, y, z, crop.soil)
                            if (crop.plant == "tall_grass") {
                                if (rng.nextDouble() < 0.6) { set(x, y + 1, z, "tall_grass[half=lower]"); set(x, y + 2, z, "tall_grass[half=upper]") }
                                else set(x, y + 1, z, "short_grass")
                            } else set(x, y + 1, z, crop.plant.replace("{age}", rng.nextInt(crop.maxAge / 2, crop.maxAge + 1).toString()))
                        }
                    }
                }
                counts.merge(kind ?: "track", 1, Int::plus)
            }
        }
        return InspectTools.result(
            plugin, world,
            "Fields in ${box.describe()}: " + counts.entries.joinToString(", ") { "${it.value} ${it.key}" } + ", ${change.blocks} blocks, ${ground.size} columns of open ground.",
            box, args, "fields",
        )
    }
}
