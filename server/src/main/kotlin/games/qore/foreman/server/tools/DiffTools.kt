package games.qore.foreman.server.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.Chunks
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Library
import games.qore.foreman.server.world.Placement
import games.qore.foreman.server.world.Point
import games.qore.foreman.server.world.RegionDiff
import games.qore.foreman.server.world.Sync

/** Comparing a region with its copy: checks that a paste, move or turn carried everything over. */
object DiffTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.DIFF_REGIONS,
            "Compares a box with where it was copied or moved (a paste, move_asset, rotate_instance, transform_region): " +
                "block states (turned), the block data entries copies carry (their content), entities (type, turned " +
                "position and yaw, a display's pose: yaw, pitch and transformation together, and its item/block/text, " +
                "interaction size, hanging facing, their persistent data), furniture as its plugin sees it (type, root, yaw, state, cells) and the points " +
                "and areas inside. In data, a source entity's UUID matches its copy's, and a yaw or face name turned by " +
                "the rotation counts as the same. `target.origin` is where the source's min corner landed (with " +
                "`rotate`, the copy turns about it). Read-only. Returns counts per kind and the first differences with " +
                "their coordinates.",
            schema {
                vec("from", "A corner of the source box")
                vec("to", "The opposite corner")
                string("world", "The source's world", required = false)
                prop("target", SchemaBuilder.type("object").apply {
                    add("properties", JsonObject().apply {
                        add("world", SchemaBuilder.type("string").apply { addProperty("description", "The copy's world (default: the source's)") })
                        add("origin", SchemaBuilder.vecSchema().apply { addProperty("description", "Where the source's min corner landed [x, y, z]") })
                        add("rotate", SchemaBuilder.type("integer").apply { addProperty("description", "Clockwise degrees seen from above the copy is turned: 0, 90, 180, 270 (default 0)") })
                    })
                    add("required", JsonArray().apply { add("origin") })
                }, "Where the copy is: {world?, origin, rotate?}")
                int("limit", "Differences listed (default 30)", required = false)
                enum("format", listOf("text", "json"), "text (default), or json: {source: {world, box}, target: {world, box, rotate}, counts: {blocks|block_data|entities|furniture|points|areas: {compared, same, differ, missing, extra}}, mapped, turned, differences: [{kind, at, text}], more}", required = false)
            },
        ) { args -> diff(plugin, args) },
    )

    private fun diff(plugin: Foreman, args: Args): ToolResult {
        val source = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxEditBlocks.toLong(), "diff_regions")
        val target = Args(args.obj("target"))
        val world = plugin.world(target.string("world", source.name))
        val rotate = Math.floorMod(target.int("rotate", 0), 360)
        if (rotate % 90 != 0) throw ToolError("target.rotate must be 0, 90, 180 or 270")
        val placement = Placement(box.min, rotate, target.vec("origin"))
        val to = placement.box(box)
        Furniture.ensureLoaded(source, box.grow(Library.REACH, Library.REACH, Library.REACH))
        Furniture.ensureLoaded(world, to.grow(Library.REACH, Library.REACH, Library.REACH))
        Chunks.loadEntities(source, listOf(box.grow(Library.REACH, Library.REACH, Library.REACH)))
        Chunks.loadEntities(world, listOf(to.grow(Library.REACH, Library.REACH, Library.REACH)))
        val diff = Sync.main { RegionDiff(source, box, world, placement, args.int("limit", 30).coerceIn(0, 1000)).run() }
        val more = diff.total - diff.differences.size
        if (args.string("format", "text") == "json") return ToolResult.text(JsonObject().apply {
            add("source", JsonObject().apply { addProperty("world", source.name); add("box", box(box)) })
            add("target", JsonObject().apply { addProperty("world", world.name); add("box", box(to)); addProperty("rotate", rotate) })
            add("counts", JsonObject().apply {
                diff.counts.forEach { (kind, c) ->
                    add(kind, JsonObject().apply {
                        addProperty("compared", c.compared); addProperty("same", c.same); addProperty("differ", c.differ)
                        addProperty("missing", c.missing); addProperty("extra", c.extra)
                    })
                }
            })
            addProperty("mapped", diff.mapped)
            addProperty("turned", diff.turned)
            add("differences", JsonArray().apply {
                diff.differences.forEach { d ->
                    add(JsonObject().apply {
                        addProperty("kind", d.kind)
                        add("at", JsonArray().apply { d.at.forEach { add(it) } })
                        addProperty("text", d.text)
                    })
                }
            })
            addProperty("more", more)
        }.toString())
        val out = StringBuilder("Compared ${box.describe()} in ${source.name} with ${to.describe()} in ${world.name}" + (if (rotate != 0) ", turned $rotate°" else "") + ":\n")
        diff.counts.forEach { (kind, c) ->
            out.append("  $kind: ${c.compared} compared, ${c.same} same")
            if (c.differ > 0) out.append(", ${c.differ} differ")
            if (c.missing > 0) out.append(", ${c.missing} missing")
            if (c.extra > 0) out.append(", ${c.extra} extra (not in the source)")
            out.append('\n')
        }
        if (diff.mapped > 0 || diff.turned > 0) out.append("  in data: ${diff.mapped} references to source entities matched their copies, ${diff.turned} values turned with the region\n")
        if (diff.total == 0) out.append("No differences.")
        else {
            out.append("${diff.total} differences" + (if (more > 0) ", the first ${diff.differences.size}" else "") + ":\n")
            diff.differences.forEach { d -> out.append("  ${d.kind} [${d.at.joinToString(", ") { Point.fmt(it) }}]: ${d.text}\n") }
        }
        return ToolResult.text(out.toString().trimEnd())
    }

    private fun box(b: Box) = JsonArray().apply { listOf(b.min.x(), b.min.y(), b.min.z(), b.max.x(), b.max.y(), b.max.z()).forEach { add(it) } }
}
