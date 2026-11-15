package games.qore.foreman.server.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Clipboards
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Library
import games.qore.foreman.server.world.Locks
import games.qore.foreman.server.world.Sync

/** Copy and cut into a clipboard ([Clipboards]); paste_asset and asset_content take it by its name, `clipboard`. */
object ClipboardTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.COPY_REGION,
            "Copies a box into your clipboard as it is now: its blocks with their block data, display entities (furniture and " +
                "the plugins' objects drawn with them), its other entities (markers, item frames, armor stands…; not players), and the " +
                "points, areas and pasted copies lying in it. " +
                "One clipboard per player (their AI chats share it; the console and the server's token share one), replaced " +
                "by the next copy, gone when the server restarts. `cut` also takes it out of the world (one undo step). Paste " +
                "it with paste_asset {name: \"clipboard\"}, as often as needed, into any loaded world: each paste is a copy of " +
                "its own (detached: no asset to follow), its plugin objects working on their own. Returns its size and what it holds.",
            schema {
                vec("from", "A corner")
                vec("to", "The opposite corner")
                bool("cut", "Also take it out of the world: its blocks to air, its entities, points, areas and pasted copies removed (default false). Refused when an asset area lies in it", required = false)
                enum("format", listOf("text", "json"), "text (default), or json: {asset: \"clipboard\", world, from, to, size, blocks, entities, furniture, block_data, points, areas, copies, cut, message}", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> copy(plugin, args) },
    )

    private fun copy(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxEditBlocks.toLong(), "copy_region")
        val cut = args.bool("cut", false)
        val json = when (args.string("format", "text")) {
            "text" -> false
            "json" -> true
            else -> throw ToolError("format is text or json")
        }
        if (cut) Locks.guard(world.name, box)
        val around = box.grow(Library.REACH, Library.REACH, Library.REACH)
        Furniture.ensureLoaded(world, around)
        Chunks.loadEntities(world, listOf(around))
        val clip = Sync.main { Clipboards.copy(world, box, AssetContentTools::poses) }
        val removed = if (cut) Edits.scoped(world, "cut ${box.describe()}") { scope -> Clipboards.cut(scope, box) }.first else null

        val copy = clip.copy
        val blocks = copy.clipboard.region.count { !copy.clipboard.getBlock(it).blockType.material.isAir }
        val counts = linkedMapOf(
            "blocks" to blocks, "entities" to copy.entities.size, "furniture" to copy.furniture.size, "block_data" to copy.blockData.size,
            "points" to copy.points.size, "areas" to copy.areas.size, "copies" to copy.instances.size,
        )
        val held = counts.filter { it.value > 0 }.entries.joinToString(", ") { "${it.value} ${it.key.replace('_', ' ')}" }.ifEmpty { "nothing but air" }
        val message = "${if (cut) "Cut" else "Copied"} ${box.describe()} in ${world.name} into your clipboard: $held."
        if (json) return ToolResult.text(JsonObject().apply {
            addProperty("asset", Clipboards.ASSET)
            addProperty("world", world.name)
            add("from", vec(box.min.x(), box.min.y(), box.min.z()))
            add("to", vec(box.max.x(), box.max.y(), box.max.z()))
            add("size", vec(box.sizeX, box.sizeY, box.sizeZ))
            counts.forEach { (k, v) -> addProperty(k, v) }
            addProperty("cut", cut)
            addProperty("message", message)
        }.toString())
        return ToolResult.text(
            message + (if (removed != null) " Taken out of the world (undo puts it back)." else "") +
                " Paste it with paste_asset {name: \"clipboard\", position}: its bottom center goes there."
        )
    }

    private fun vec(x: Int, y: Int, z: Int) = JsonArray().apply { add(x); add(y); add(z) }
}
