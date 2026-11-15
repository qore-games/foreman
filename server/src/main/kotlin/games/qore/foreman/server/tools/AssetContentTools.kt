package games.qore.foreman.server.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.math.BlockVector3
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Area
import games.qore.foreman.server.world.AreaAssets
import games.qore.foreman.server.world.Assets
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Clipboards
import games.qore.foreman.server.world.Snapshots
import games.qore.foreman.server.world.Sync
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer
import org.bukkit.Material
import org.bukkit.block.data.BlockData
import org.bukkit.entity.BlockDisplay
import org.bukkit.entity.Display
import org.bukkit.entity.ItemDisplay
import org.bukkit.entity.TextDisplay
import org.bukkit.util.Transformation
import org.enginehub.linbus.format.snbt.LinStringIO
import org.enginehub.linbus.tree.LinByteTag
import org.enginehub.linbus.tree.LinCompoundTag
import org.enginehub.linbus.tree.LinIntTag
import org.enginehub.linbus.tree.LinListTag
import org.enginehub.linbus.tree.LinNumberTag
import org.enginehub.linbus.tree.LinStringTag
import org.enginehub.linbus.tree.LinTag
import java.util.Locale
import kotlin.math.roundToLong

/**
 * What an asset holds, compactly, for a client to draw it where it would be pasted (the placement tool's ghost): its
 * blocks with a face in the open, as palette indices, and its display entities with their pose. Read-only.
 */
object AssetContentTools {
    /** Most blocks listed. */
    const val MAX_BLOCKS = 30_000
    /** A content read is served again this long: the placement tool asks for it each time it starts. */
    private const val KEPT_MS = 10_000L
    /** Blocks that draw nothing (furniture cells, light sources, markers): left out, and not hiding their neighbours. */
    private val INVISIBLE = setOf(Material.BARRIER, Material.LIGHT, Material.STRUCTURE_VOID)

    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.ASSET_CONTENT,
            "What an asset (schematic or asset area) holds, for a program to draw it (a ghost where it would be pasted) " +
                "or to read it without a render: size, a palette of block states, the blocks that aren't air and have a face " +
                "in the open (those enclosed by full blocks are left out and counted), and its display entities (item, block, " +
                "text) with their pose. Positions are from its min corner as saved; paste_asset turns it about its bottom " +
                "center. Text gives a summary, json everything. `clipboard` reads your clipboard (copy_region).",
            schema {
                string("name", "Asset name or area id from list_assets, or clipboard")
                int("max_blocks", "Most blocks listed (default and at most $MAX_BLOCKS): past it the list stops and says truncated", required = false)
                enum("format", listOf("text", "json"), "text (default): a summary; json: {asset, size, palette, blocks: flat [x, y, z, " +
                    "palette index, …], listed, hidden, total, truncated, displays: [{type: item|block|text, pos, yaw, pitch, " +
                    "transformation, billboard, item?, model?, item_display?, block?, text?, line_width?, background?, opacity?, " +
                    "shadow?, see_through?, default_background?, alignment?}]}", required = false)
            },
        ) { args -> content(plugin, args) },
    )

    private val kept = LinkedHashMap<String, Pair<Long, JsonObject>>()

    private fun content(plugin: Foreman, args: Args): ToolResult {
        val name = args.string("name")
        val max = args.int("max_blocks", MAX_BLOCKS).coerceIn(0, MAX_BLOCKS)
        val json = when (args.string("format", "text")) {
            "text" -> false
            "json" -> true
            else -> throw ToolError("format is text or json")
        }
        val clip = if (Clipboards.isId(name)) Clipboards.get(name) else null
        val area = if (clip == null && AreaAssets.isId(name)) AreaAssets.get(plugin.worlds, name) else null
        val file = if (clip == null && area == null) plugin.assets.get(name) else null
        // A changed area (moved, resized) or schematic is read again; changes inside an area within [KEPT_MS].
        val key = if (clip != null) "${Clipboards.ASSET}#${clip.serial}|$max" else "$name|$max|${area?.hashCode() ?: file!!.file.lastModified()}"
        val now = System.currentTimeMillis()
        val content = synchronized(kept) {
            kept.values.removeIf { now - it.first > KEPT_MS }
            kept[key]?.second
        } ?: (if (clip != null) readClip(clip, max) else if (area != null) readArea(plugin, area, max) else readFile(plugin, file!!, max)).also { read ->
            synchronized(kept) {
                kept[key] = now to read
                while (kept.size > 16) kept.remove(kept.keys.first())
            }
        }
        return ToolResult.text(if (json) content.toString() else summary(content))
    }

    private fun readArea(plugin: Foreman, area: Area, max: Int): JsonObject {
        val world = AreaAssets.world(area)
        val box = area.bounds.requireVolume(plugin.limits.maxEditBlocks.toLong(), "asset_content")
        Chunks.loadEntities(world, listOf(box))
        val (snapshots, displays) = Sync.main { Snapshots(world, box.min.x(), box.min.z(), box.max.x(), box.max.z()).load() to poses(world, box) }
        return JsonObject().apply {
            addProperty("asset", AreaAssets.id(area))
            blocks(this, box.sizeX, box.sizeY, box.sizeZ, max) { x, y, z -> snapshots.data(box.min.x() + x, box.min.y() + y, box.min.z() + z) }
            add("displays", displays)
        }
    }

    /** The displays of [box] (and their riders) with their pose from its min corner, as `displays` says them. Main thread. */
    fun poses(world: org.bukkit.World, box: Box): JsonArray = JsonArray().apply {
        AreaAssets.displays(world, box).flatMap { listOf(it) + riders(it) }.distinctBy { it.uniqueId }.mapNotNull { display(it, box.min) }.forEach(::add)
    }

    /** A clipboard: its blocks as copied, its displays as they were then ([Clipboards.Clip.displays]). */
    private fun readClip(clip: Clipboards.Clip, max: Int): JsonObject {
        val clipboard = clip.copy.clipboard
        val min = clipboard.region.minimumPoint
        val d = clipboard.dimensions
        return JsonObject().apply {
            addProperty("asset", Clipboards.ASSET)
            blocks(this, d.x(), d.y(), d.z(), max) { x, y, z ->
                val state = clipboard.getBlock(min.add(x, y, z))
                if (state.blockType.material.isAir) null else BukkitAdapter.adapt(state)
            }
            add("displays", clip.displays)
        }
    }

    private fun readFile(plugin: Foreman, asset: Assets.Asset, max: Int): JsonObject {
        // A big schematic is refused by its indexed size, before it is read.
        asset.size?.let { s -> tooBig(plugin, asset.name, s[0].toLong() * s[1] * s[2]) }
        val clipboard = plugin.assets.read(asset)
        val region = clipboard.region
        tooBig(plugin, asset.name, region.volume)
        val min = region.minimumPoint
        val d = clipboard.dimensions
        return JsonObject().apply {
            addProperty("asset", asset.name)
            blocks(this, d.x(), d.y(), d.z(), max) { x, y, z ->
                val state = clipboard.getBlock(min.add(x, y, z))
                if (state.blockType.material.isAir) null else BukkitAdapter.adapt(state)
            }
            add("displays", JsonArray().apply {
                for (e in clipboard.entities) {
                    val state = e.state ?: continue
                    val nbt = state.nbt ?: continue
                    val l = e.location
                    display(state.type.id(), nbt, doubleArrayOf(l.x() - min.x(), l.y() - min.y(), l.z() - min.z()), l.yaw, l.pitch)?.let(::add)
                }
            })
        }
    }

    private fun tooBig(plugin: Foreman, name: String, volume: Long) {
        if (volume > plugin.limits.maxEditBlocks) throw ToolError("asset_content is limited to ${plugin.limits.maxEditBlocks} blocks, $name has $volume")
    }

    /**
     * Reads the [sx]×[sy]×[sz] cells [at] gives (null or air: empty) into [out]: `size`, `palette` (block states), `blocks`
     * (x, y, z and palette index of each block with a face in the open, at most [max]), `listed`, `hidden` (enclosed by
     * full blocks), `total` and `truncated`.
     */
    private fun blocks(out: JsonObject, sx: Int, sy: Int, sz: Int, max: Int, at: (Int, Int, Int) -> BlockData?) {
        val palette = LinkedHashMap<BlockData, Int>()
        val occluding = ArrayList<Boolean>()
        val cells = IntArray(sx * sy * sz) { -1 }
        fun index(x: Int, y: Int, z: Int) = (y * sz + z) * sx + x
        for (y in 0 until sy) for (z in 0 until sz) for (x in 0 until sx) {
            val data = at(x, y, z) ?: continue
            val m = data.material
            if (m.isAir || m in INVISIBLE) continue
            cells[index(x, y, z)] = palette.getOrPut(data) { occluding += m.isOccluding; palette.size }
        }
        // Outside the asset is open: what is there depends on where it goes.
        fun solid(x: Int, y: Int, z: Int) = x in 0 until sx && y in 0 until sy && z in 0 until sz && cells[index(x, y, z)].let { it >= 0 && occluding[it] }
        val blocks = JsonArray()
        var total = 0
        var hidden = 0
        var listed = 0
        for (y in 0 until sy) for (z in 0 until sz) for (x in 0 until sx) {
            val p = cells[index(x, y, z)]
            if (p < 0) continue
            total++
            if (solid(x - 1, y, z) && solid(x + 1, y, z) && solid(x, y - 1, z) && solid(x, y + 1, z) && solid(x, y, z - 1) && solid(x, y, z + 1)) {
                hidden++
                continue
            }
            if (listed >= max) continue
            listed++
            blocks.add(x); blocks.add(y); blocks.add(z); blocks.add(p)
        }
        out.add("size", JsonArray().apply { add(sx); add(sy); add(sz) })
        out.add("palette", JsonArray().apply { palette.keys.forEach { add(it.asString) } })
        out.add("blocks", blocks)
        out.addProperty("listed", listed)
        out.addProperty("hidden", hidden)
        out.addProperty("total", total)
        out.addProperty("truncated", listed < total - hidden)
    }

    private fun riders(e: org.bukkit.entity.Entity): List<Display> = e.passengers.filterIsInstance<Display>().flatMap { listOf(it) + riders(it) }

    /** [e] as the client draws it, its position from [min]; null for an item display without an item. Main thread. */
    private fun display(e: Display, min: BlockVector3): JsonObject? {
        val out = JsonObject()
        when (e) {
            is ItemDisplay -> {
                val stack = e.itemStack
                if (stack.type.isAir) return null
                val meta = stack.itemMeta
                val id = stack.type.key.toString()
                out.addProperty("type", "item")
                out.addProperty("item", id + (meta?.asComponentString?.takeIf { it != "[]" } ?: ""))
                out.addProperty("model", if (meta != null && meta.hasItemModel()) meta.itemModel.toString() else id)
                out.addProperty("item_display", e.itemDisplayTransform.name.lowercase(Locale.ROOT))
            }
            is BlockDisplay -> {
                out.addProperty("type", "block")
                out.addProperty("block", e.block.asString)
            }
            is TextDisplay -> {
                out.addProperty("type", "text")
                out.add("text", JsonParser.parseString(GsonComponentSerializer.gson().serialize(e.text())))
                out.addProperty("line_width", e.lineWidth)
                e.backgroundColor?.let { out.addProperty("background", it.asARGB()) }
                out.addProperty("opacity", e.textOpacity.toInt())
                out.addProperty("shadow", e.isShadowed)
                out.addProperty("see_through", e.isSeeThrough)
                out.addProperty("default_background", e.isDefaultBackground)
                out.addProperty("alignment", e.alignment.name.lowercase(Locale.ROOT))
            }
            else -> return null
        }
        val l = e.location
        pose(out, doubleArrayOf(l.x - min.x(), l.y - min.y(), l.z - min.z()), l.yaw, l.pitch, transformation(e.transformation), e.billboard.name.lowercase(Locale.ROOT))
        return out
    }

    /** A display saved in a schematic, from its NBT ([type] its entity type); null for other entities. */
    private fun display(type: String, nbt: LinCompoundTag, pos: DoubleArray, yaw: Float, pitch: Float): JsonObject? {
        val v = nbt.value()
        val out = JsonObject()
        when (type) {
            "minecraft:item_display" -> {
                val item = (v["item"] as? LinCompoundTag)?.value() ?: return null
                val id = (item["id"] as? LinStringTag)?.value() ?: return null
                val components = (item["components"] as? LinCompoundTag)?.value().orEmpty()
                out.addProperty("type", "item")
                out.addProperty("item", id + if (components.isEmpty()) "" else components.entries.joinToString(",", "[", "]") { "${it.key}=${LinStringIO.writeToString(it.value)}" })
                out.addProperty("model", (components["minecraft:item_model"] as? LinStringTag)?.value() ?: id)
                out.addProperty("item_display", (v["item_display"] as? LinStringTag)?.value() ?: "none")
            }
            "minecraft:block_display" -> {
                val state = (v["block_state"] as? LinCompoundTag)?.value() ?: return null
                val name = (state["Name"] as? LinStringTag)?.value() ?: return null
                val properties = (state["Properties"] as? LinCompoundTag)?.value().orEmpty()
                out.addProperty("type", "block")
                out.addProperty("block", name + if (properties.isEmpty()) "" else properties.entries.joinToString(",", "[", "]") { "${it.key}=${(it.value as? LinStringTag)?.value()}" })
            }
            "minecraft:text_display" -> {
                out.addProperty("type", "text")
                out.add("text", v["text"]?.let(::json) ?: JsonPrimitive(""))
                (v["line_width"] as? LinIntTag)?.let { out.addProperty("line_width", it.valueAsInt()) }
                (v["background"] as? LinIntTag)?.let { out.addProperty("background", it.valueAsInt()) }
                (v["text_opacity"] as? LinByteTag)?.let { out.addProperty("opacity", it.valueAsByte().toInt()) }
                for (flag in listOf("shadow", "see_through", "default_background")) (v[flag] as? LinByteTag)?.let { out.addProperty(flag, it.valueAsByte() != 0.toByte()) }
                (v["alignment"] as? LinStringTag)?.let { out.addProperty("alignment", it.value()) }
            }
            else -> return null
        }
        val t = (v["transformation"] as? LinCompoundTag)?.value()
        fun floats(name: String, default: List<Float>): JsonArray = JsonArray().apply {
            val list = (t?.get(name) as? LinListTag<*>)?.value()?.mapNotNull { (it as? LinNumberTag<*>)?.value()?.toFloat() }?.takeIf { it.size == default.size } ?: default
            list.forEach { add(round(it.toDouble())) }
        }
        val transformation = JsonObject().apply {
            add("translation", floats("translation", listOf(0f, 0f, 0f)))
            add("left_rotation", floats("left_rotation", listOf(0f, 0f, 0f, 1f)))
            add("scale", floats("scale", listOf(1f, 1f, 1f)))
            add("right_rotation", floats("right_rotation", listOf(0f, 0f, 0f, 1f)))
        }
        pose(out, pos, yaw, pitch, transformation, (v["billboard"] as? LinStringTag)?.value() ?: "fixed")
        return out
    }

    private fun pose(out: JsonObject, pos: DoubleArray, yaw: Float, pitch: Float, transformation: JsonObject, billboard: String) {
        out.add("pos", JsonArray().apply { pos.forEach { add(round(it)) } })
        out.addProperty("yaw", round(yaw.toDouble()))
        out.addProperty("pitch", round(pitch.toDouble()))
        out.add("transformation", transformation)
        out.addProperty("billboard", billboard)
    }

    private fun transformation(t: Transformation) = JsonObject().apply {
        fun floats(vararg f: Float) = JsonArray().apply { f.forEach { add(round(it.toDouble())) } }
        add("translation", floats(t.translation.x, t.translation.y, t.translation.z))
        add("left_rotation", floats(t.leftRotation.x, t.leftRotation.y, t.leftRotation.z, t.leftRotation.w))
        add("scale", floats(t.scale.x, t.scale.y, t.scale.z))
        add("right_rotation", floats(t.rightRotation.x, t.rightRotation.y, t.rightRotation.z, t.rightRotation.w))
    }

    /** A text component saved as NBT, as the JSON the client reads components from. */
    private fun json(tag: LinTag<*>): JsonElement = when (tag) {
        is LinCompoundTag -> JsonObject().apply { tag.value().forEach { (k, v) -> add(k, json(v)) } }
        is LinListTag<*> -> JsonArray().apply { tag.value().forEach { add(json(it)) } }
        is LinStringTag -> JsonPrimitive(tag.value())
        is LinNumberTag<*> -> JsonPrimitive(tag.value())
        else -> JsonPrimitive(tag.toString())
    }

    private fun round(v: Double): Double = (v * 100_000).roundToLong() / 100_000.0

    private fun summary(c: JsonObject): String {
        val size = c.getAsJsonArray("size").map { it.asInt }
        val palette = c.getAsJsonArray("palette").map { it.asString.substringBefore('[').removePrefix("minecraft:") }.distinct()
        val displays = c.getAsJsonArray("displays").map { it.asJsonObject }
        val total = c.get("total").asInt
        val hidden = c.get("hidden").asInt
        return buildString {
            append("${c.get("asset").asString}: ${size.joinToString("x")}, $total blocks (${total - hidden} with a face in the open, $hidden enclosed)")
            if (c.get("truncated").asBoolean) append(", ${c.get("listed").asInt} listed by json (max_blocks)")
            if (palette.isNotEmpty()) append("; ${palette.size} blocks: ${palette.take(12).joinToString(", ")}" + if (palette.size > 12) ", …" else "")
            append(". ${displays.size} display${if (displays.size == 1) "" else "s"}")
            displays.take(20).forEach { d ->
                val what = d.get("model")?.asString ?: d.get("block")?.asString ?: d.get("text")?.toString()?.take(40) ?: "?"
                val pos = d.getAsJsonArray("pos").joinToString(", ") { "%.2f".format(Locale.ROOT, it.asDouble) }
                append("\n  ${d.get("type").asString} $what at [$pos] yaw ${d.get("yaw").asDouble.toInt()} pitch ${d.get("pitch").asDouble.toInt()}")
            }
            if (displays.size > 20) append("\n  … ${displays.size - 20} more")
            append("\nPositions are from the min corner as saved. format json gives every block and pose.")
        }
    }
}
