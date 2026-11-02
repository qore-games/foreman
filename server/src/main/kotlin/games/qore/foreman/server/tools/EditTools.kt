package games.qore.foreman.server.tools

import com.sk89q.worldedit.EditSession
import com.sk89q.worldedit.bukkit.BukkitAdapter
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard
import com.sk89q.worldedit.function.operation.ForwardExtentCopy
import com.sk89q.worldedit.function.operation.Operations
import com.sk89q.worldedit.math.BlockVector3
import com.sk89q.worldedit.math.transform.AffineTransform
import com.sk89q.worldedit.regions.CuboidRegion
import com.sk89q.worldedit.regions.Region
import com.sk89q.worldedit.function.pattern.Pattern
import com.sk89q.worldedit.session.ClipboardHolder
import com.sk89q.worldedit.world.block.BlockTypes
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.SchemaBuilder
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Blocks
import games.qore.foreman.server.world.Box
import games.qore.foreman.server.world.EditScope
import games.qore.foreman.server.world.Edits
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Nature
import games.qore.foreman.server.world.Shapes
import games.qore.foreman.server.world.Sync
import games.qore.foreman.server.world.TallTrees
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.World
import org.bukkit.block.BlockFace
import org.bukkit.block.data.type.Slab
import org.bukkit.block.data.type.Stairs
import java.util.Random
import kotlin.math.max
import kotlin.math.min

object EditTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.EDIT,
            "Applies block operations in order, all undone together by one undo; if one fails, none is applied. Blocks " +
                "use WorldEdit patterns: `stone_bricks`, `oak_stairs[facing=east,half=top]`, " +
                "`60%stone_bricks,30%cracked_stone_bricks,10%mossy_stone_bricks`, `air`. Ops (objects with \"op\"):\n" +
                "- fill {from, to, block, mode?: solid|walls|faces} (walls = 4 sides, faces = walls + floor + ceiling)\n" +
                "- replace {from, to, mask, block} (mask: `grass_block,dirt`, `#leaves`, `!air`…)\n" +
                "- set {blocks: [[x, y, z, block], …]} for detailing\n" +
                "- line {from, to, block, radius?}\n" +
                "- sphere {center, radius | radii [rx, ry, rz], block, hollow?}\n" +
                "- cylinder {center (bottom center), radius, height, block, hollow?}\n" +
                "- door {at (lower half), facing, block? (oak_door), width? (1, or 2 for a double door: the second leaf on the " +
                "right of someone facing `facing`), hinge? (default: right for a door next to one on its left, else left)}: " +
                "both halves; a double door gets the hinges that make it open from the middle\n" +
                "- stairs {from (the first step, at the lower floor's feet level), toward (north/south/east/west: the way it " +
                "climbs), rise (steps = upper feet level - lower feet level: the top step is level with the upper floor), width? (2), " +
                "block (`oak_stairs`), support? (pattern under the steps, default the stairs' full block), headroom? (3: air " +
                "above each step and the landing)}: a staircase players can walk, carved through what is in the way\n" +
                "- weather {from, to, amount? (0.25), patch? (3: patch size), variants? {block: [variants…]}, seed?}: wear in " +
                "clustered patches, never per-block noise: cracked and mossy bricks, mossy cobblestone, dirty concrete…; " +
                "stairs, slabs and walls follow their block\n" +
                "- overgrow {from, to, amount? (0.3), kinds? [vines, moss, grass, litter, cobwebs, rubble] (default all but " +
                "cobwebs), seed?}: abandonment, in patches: vines down walls, moss on floors and roofs, grass at wall feet, " +
                "leaf litter, cobwebs in interior corners, rubble along walls\n" +
                "- roof {from, to: the box of the walls (the roof starts on top of it), stairs (`dark_oak_stairs`, or " +
                "weighted `70%dark_oak_stairs,30%spruce_stairs`), style?: gable (default) | hip | shed, axis?: x|z (gable " +
                "ridge direction, default the long side), high?: north|south|east|west (shed), overhang? (1), eaves? (true: " +
                "upside-down stairs under the overhang), gable? (pattern filling the end walls under the slopes), slab?, " +
                "ridge_block?}\n" +
                "- scatter {from, to, on, block, density?}: plants or props on the `on` blocks (`grass_block`, `#dirt`) in the " +
                "box, only into air; double plants get their upper half. density 0..1 (default 0.2)\n" +
                "- tree {at | points: [[x, z] | [x, y, z], …], type?}: trees with their trunk on the ground; [x, z] finds " +
                "the ground, a plant in the way is removed. type oak (default), birch, spruce, dark_oak, cherry, acacia, pine… " +
                "or, where players walk, tall_spruce, tall_birch, tall_oak, snag (tall trunks, no leaves below 5 blocks), seed?\n" +
                "Masks: `a,b` matches a OR b; a space ANDs (`air >grass_block` = air above grass). Prefer scatter over " +
                "replace with masks for vegetation. Afterwards, panes, fences, bars and walls are connected to their " +
                "neighbours and stairs turn corners, as in game (connect=false to keep the states as set).",
            schema {
                array("ops", SchemaBuilder.type("object"), "Operations, applied in order")
                string("label", "Short description for the undo history, e.g. \"town hall roof trim\"")
                bool("connect", "Connect panes, fences, walls and stair corners in the changed box (default true)", required = false)
                string("claim", "Claim the changed box under this name (a build, a yard…); fails if it overlaps another claim", required = false)
                bool("allow_overlap", "Allow changing blocks inside other claims (default false)", required = false)
                resultOptions()
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> edit(plugin, args) },

        Tool(
            ForemanTool.TRANSFORM_REGION,
            "Moves or copies a box, optionally rotated around its center (blocks, block states and entities such as " +
                "furniture turn with it). Use it to rotate a house, shift a build, duplicate a stall. The source is " +
                "cleared to air when moving; terrain at the destination is overwritten unless ignore_air. Afterwards, " +
                "fix the ground with foundation / blend_terrain and reconnect paths.",
            schema {
                vec("from", "A corner of the box")
                vec("to", "The opposite corner")
                int("rotate", "Clockwise degrees seen from above: 0, 90, 180 or 270 (default 0)", required = false)
                vec("offset", "Translation [dx, dy, dz] applied after rotation (default [0, 0, 0])", required = false)
                enum("mode", listOf("move", "copy"), "move (default) clears the source, copy keeps it", required = false)
                bool("ignore_air", "Do not paste the air of the box over the destination (default false)", required = false)
                string("world", "World name", required = false)
            },
            modifies = true,
        ) { args -> transform(plugin, args) },

        Tool(
            ForemanTool.UNDO,
            "Undoes the last AI changes (blocks and furniture), newest first. get_context lists them.",
            schema { int("count", "How many changes to undo (default 1)", required = false) },
            modifies = true,
        ) { args ->
            val undone = Edits.undo(args.int("count", 1))
            ToolResult.text(if (undone.isEmpty()) "Nothing to undo" else "Undid: " + undone.joinToString(", "))
        },
    )

    /** What an op did: blocks changed, the box it touched and anything the model should know. */
    class OpResult(val blocks: Int, val box: Box?, val note: String? = null)

    /** Ops that read the world: the ops before them in the same edit are committed first, or they would not see them. */
    private val READS_WORLD = setOf("scatter", "tree", "door", "weather", "overgrow")
    private const val CONNECT_MAX = 1_000_000L

    private fun edit(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val ops = args.array("ops").map { Args(it.asJsonObject) }
        if (ops.isEmpty()) throw ToolError("No ops")
        val label = args.string("label")
        val max = plugin.limits.maxEditBlocks.toLong()

        var bounds: Box? = null
        var connected = 0
        val (notes, change) = Edits.scoped(world, label) { scope ->
            val notes = mutableListOf<String>()
            ops.forEachIndexed { i, op ->
                val kind = op.string("op")
                val result = try {
                    if (i > 0 && kind in READS_WORLD) scope.flush()
                    apply(scope, op, max)
                } catch (e: ToolError) {
                    throw ToolError("Op #${i + 1} ($kind): ${e.message}. Nothing was applied.")
                }
                result.box?.let { b -> bounds = bounds?.union(b) ?: b }
                when {
                    result.note != null -> notes += "#${i + 1} $kind: ${result.note}"
                    result.blocks == 0 -> notes += "#${i + 1} $kind changed nothing"
                }
            }
            val claim = args.string("claim", null)
            bounds?.let { QualityTools.guard(plugin, world, it, claim, args.bool("allow_overlap", false)) }
            val box = bounds?.grow(1, 1, 1)
            if (args.bool("connect", true) && box != null && box.volume <= CONNECT_MAX) {
                scope.flush()
                connected = Shapes.connect(world, scope.session, box)
            }
            notes
        }
        val claimed = args.string("claim", null)?.let { name -> bounds?.let { plugin.worlds.reserve(name, world.name, it) } }
        val text = "Applied ${ops.size} op${if (ops.size > 1) "s" else ""}: ${change.blocks} blocks changed" +
            (if (connected > 0) ", $connected panes/fences/walls/stairs connected" else "") +
            (bounds?.let { ", in ${it.describe()}" } ?: "") + "." +
            notes.joinToString("") { "\n$it" } + (claimed?.let { "\nClaimed: ${it.describe()}" } ?: "")
        return InspectTools.result(plugin, world, text, bounds, args, label)
    }

    private fun apply(scope: EditScope, op: Args, max: Long): OpResult {
        val session = scope.session
        fun box() = Box.of(op.vec("from"), op.vec("to")).requireVolume(max, "An op")
        fun region(b: Box): Region = CuboidRegion(session.world, b.min, b.max)
        fun block() = Edits.pattern(session, op.string("block"))

        return when (op.string("op")) {
            "fill" -> {
                val b = box()
                val r = region(b)
                OpResult(when (op.string("mode", "solid")) {
                    "solid" -> session.setBlocks(r, block())
                    "walls" -> session.makeWalls(r, block())
                    "faces" -> session.makeFaces(r, block())
                    else -> throw ToolError("mode must be solid, walls or faces")
                }, b)
            }

            "replace" -> box().let { OpResult(session.replaceBlocks(region(it), Edits.mask(session, op.string("mask")), block()), it) }

            "set" -> {
                var b: Box? = null
                val n = op.array("blocks").sumOf { e ->
                    val a = e.asJsonArray
                    if (a.size() != 4) throw ToolError("Each block is [x, y, z, block], got $e")
                    val pos = BlockVector3.at(a[0].asInt, a[1].asInt, a[2].asInt)
                    b = b?.union(Box(pos, pos)) ?: Box(pos, pos)
                    val pattern = Edits.pattern(session, a[3].asString)
                    if (session.setBlock(pos, pattern)) 1 else 0
                }
                OpResult(n, b)
            }

            "line" -> {
                val from = op.vec("from")
                val to = op.vec("to")
                val r = op.double("radius", 0.0)
                OpResult(session.drawLine(block(), from, to, r, true), Box.of(from, to).grow(r.toInt(), r.toInt(), r.toInt()))
            }

            "sphere" -> {
                val radii = if (op.has("radii")) op.vec("radii") else op.int("radius").let { BlockVector3.at(it, it, it) }
                if (radii.x() > 64 || radii.y() > 64 || radii.z() > 64) throw ToolError("Radius above 64")
                val center = op.vec("center")
                OpResult(
                    session.makeSphere(center, block(), radii.x().toDouble(), radii.y().toDouble(), radii.z().toDouble(), !op.bool("hollow", false)),
                    Box(center.subtract(radii), center.add(radii)),
                )
            }

            "cylinder" -> {
                val radius = op.double("radius", 0.0)
                if (radius <= 0 || radius > 64) throw ToolError("radius must be in 1..64")
                val center = op.vec("center")
                val height = op.int("height")
                val r = radius.toInt()
                OpResult(
                    session.makeCylinder(center, block(), radius, height, !op.bool("hollow", false)),
                    Box(center.subtract(r, 0, r), center.add(r, height - 1, r)),
                )
            }

            "door" -> door(scope, op)

            "stairs" -> Decor.stairs(session, op)

            "weather" -> Decor.weather(scope.world, session, box(), op)

            "overgrow" -> Decor.overgrow(scope.world, session, box(), op)

            "roof" -> roof(session, op)

            "scatter" -> {
                val b = box()
                val on = Blocks.matcher(op.string("on"))
                val density = op.double("density", 0.2).coerceIn(0.0, 1.0)
                val pattern = block()
                val random = Random()
                var placed = 0
                for (x in b.min.x()..b.max.x()) for (z in b.min.z()..b.max.z()) {
                    val y = (b.max.y() - 1 downTo b.min.y()).firstOrNull { !session.getBlock(BlockVector3.at(x, it, z)).blockType.material.isAir } ?: continue
                    if (!on(BukkitAdapter.adapt(session.getBlock(BlockVector3.at(x, y, z)).blockType)) || random.nextDouble() >= density) continue
                    if (Nature.plant(session, BlockVector3.at(x, y + 1, z), pattern)) placed++
                }
                OpResult(placed, b)
            }

            "tree" -> trees(scope, op)

            else -> throw ToolError("Unknown op '${op.string("op")}'")
        }
    }

    /**
     * Doors as vanilla places them: a door next to a door of the same facing on its left gets the right hinge, so a
     * pair opens from the middle. Left/right are as seen by someone facing `facing`.
     */
    private fun door(scope: EditScope, op: Args): OpResult {
        val session = scope.session
        val at = op.vec("at")
        val facing = runCatching { BlockFace.valueOf(op.string("facing", "")!!.uppercase()) }.getOrNull()
            ?.takeIf { it in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST) }
            ?: throw ToolError("facing must be north, south, east or west")
        val id = op.string("block", "oak_door")!!.removePrefix("minecraft:")
        if (!id.endsWith("_door")) throw ToolError("block must be a door, e.g. spruce_door")
        val width = op.int("width", 1)
        if (width !in 1..2) throw ToolError("width must be 1 or 2")
        val right = when (facing) {
            BlockFace.NORTH -> BlockFace.EAST; BlockFace.EAST -> BlockFace.SOUTH; BlockFace.SOUTH -> BlockFace.WEST; else -> BlockFace.NORTH
        }
        fun isDoor(x: Int, y: Int, z: Int) = (scope.world.getBlockData(x, y, z) as? org.bukkit.block.data.type.Door)?.let { it.facing == facing && it.half == org.bukkit.block.data.Bisected.Half.BOTTOM } == true
        val leaves = if (width == 2) listOf(at to "left", at.add(right.modX, 0, right.modZ) to "right") else {
            val hinge = op.string("hinge", null) ?: when {
                isDoor(at.x() - right.modX, at.y(), at.z() - right.modZ) -> "right"
                else -> "left"
            }
            listOf(at to hinge)
        }
        leaves.forEach { (pos, hinge) ->
            session.setBlock(pos, Edits.pattern(session, "$id[facing=${facing.name.lowercase()},half=lower,hinge=$hinge]"))
            session.setBlock(pos.add(0, 1, 0), Edits.pattern(session, "$id[facing=${facing.name.lowercase()},half=upper,hinge=$hinge]"))
        }
        // A single door placed on the left of an existing one: that one now needs the left hinge.
        var note: String? = null
        if (width == 1) {
            val other = at.add(right.modX, 0, right.modZ)
            if (isDoor(other.x(), other.y(), other.z())) {
                session.setBlock(at, Edits.pattern(session, "$id[facing=${facing.name.lowercase()},half=lower,hinge=left]"))
                session.setBlock(at.add(0, 1, 0), Edits.pattern(session, "$id[facing=${facing.name.lowercase()},half=upper,hinge=left]"))
                val data = scope.world.getBlockData(other.x(), other.y(), other.z()) as org.bukkit.block.data.type.Door
                for (dy in 0..1) {
                    val d = (scope.world.getBlockData(other.x(), other.y() + dy, other.z()) as? org.bukkit.block.data.type.Door ?: data).clone() as org.bukkit.block.data.type.Door
                    d.hinge = org.bukkit.block.data.type.Door.Hinge.RIGHT
                    session.setBlock(other.add(0, dy, 0), BukkitAdapter.adapt(d))
                }
                note = "paired with the door at ${Box.fmt(other)} as a double door (hinges left/right)"
            }
        }
        val last = leaves.last().first
        return OpResult(leaves.size * 2, Box(at, last.add(0, 1, 0)).union(Box(at, at)), note)
    }

    private fun trees(scope: EditScope, op: Args): OpResult {
        val world = scope.world
        val kind = op.string("type", "oak")!!
        val type = if (kind in TallTrees.KINDS) null else Nature.treeType(kind)
        val points = if (op.has("points")) op.array("points").map { it.asJsonArray } else listOf(op.array("at"))
        if (points.isEmpty()) throw ToolError("No points")
        val trunks = points.map { a ->
            when (a.size()) {
                2 -> BlockVector3.at(a[0].asInt, Blocks.surface(world, a[0].asInt, a[1].asInt).y + 1, a[1].asInt)
                3 -> BlockVector3.at(a[0].asInt, a[1].asInt, a[2].asInt)
                else -> throw ToolError("A tree point is [x, z] or [x, y, z], got $a")
            }
        }

        // A plant on the trunk's spot stops the tree. The generator reads the world, so the clearing is committed first.
        val air = BukkitAdapter.adapt(Material.AIR.createBlockData())
        trunks.forEach { t ->
            var y = t.y()
            while (y <= t.y() + 1 && Blocks.isPlant(world.getType(t.x(), y, t.z()))) scope.session.setBlock(t.x(), y++, t.z(), air)
        }
        scope.flush()

        val failed = if (type != null) trunks.filterNot { Nature.tree(scope.session, it, type) } else {
            // Tall trees grow on natural ground and only into air, plants and leaves.
            val rng = kotlin.random.Random(op.int("seed", trunks.sumOf { it.x() * 31 + it.z() }))
            trunks.filterNot { t ->
                if (!Blocks.isNaturalGround(world.getType(t.x(), t.y() - 1, t.z()))) return@filterNot false
                TallTrees.grow(kind, t.x(), t.y(), t.z(), rng) { x, y, z, block ->
                    if (Blocks.isCover(world.getType(x, y, z))) scope.session.setBlock(x, y, z, BukkitAdapter.adapt(Bukkit.createBlockData(block)))
                }
                true
            }
        }
        if (failed.size == trunks.size) throw ToolError("No tree could grow: " + failed.joinToString("; ") { why(world, it) })
        val box = trunks.map { if (type == null) Box(it.subtract(4, 1, 4), it.add(4, 18, 4)) else Box(it.subtract(3, 1, 3), it.add(3, 12, 3)) }.reduce { a, b -> a.union(b) }
        val grown = trunks.size - failed.size
        return OpResult(
            grown, box,
            if (failed.isEmpty()) (if (trunks.size > 1) "$grown trees" else null)
            else "$grown/${trunks.size} trees grew; not at " + failed.joinToString("; ") { why(world, it) },
        )
    }

    /** Why a tree could not grow at [at]: what is there and below. */
    private fun why(world: World, at: BlockVector3): String {
        val here = world.getType(at.x(), at.y(), at.z())
        val below = world.getType(at.x(), at.y() - 1, at.z())
        return "${Box.fmt(at)} (${Blocks.id(here)} there, ${Blocks.id(below)} below; it needs air on dirt or grass and room above)"
    }

    private fun roof(session: EditSession, op: Args): OpResult {
        val box = Box.of(op.vec("from"), op.vec("to"))
        val random = Random()
        val stairs = Shapes.Palette(op.string("stairs"), random, { it.createBlockData() is Stairs }, "a stairs block")
        val slabSpec = op.string("slab", null) ?: stairs.first.key.key.replace("_stairs", "_slab").also {
            if (Material.matchMaterial(it) == null) throw ToolError("No slab '$it' to match the stairs: pass slab")
        }
        val slab = Shapes.Palette(slabSpec, random, { it.createBlockData() is Slab }, "a slab block")
        val style = op.string("style", "gable")!!
        val axis = op.string("axis", null) ?: if (box.sizeX >= box.sizeZ) "x" else "z"
        if (axis != "x" && axis != "z") throw ToolError("axis must be x or z")
        val high = runCatching { BlockFace.valueOf(op.string("high", "north")!!.uppercase()) }.getOrNull()
            ?.takeIf { it in listOf(BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST) }
            ?: throw ToolError("high must be north, south, east or west")
        val overhang = op.int("overhang", 1).coerceIn(0, 4)
        val base = box.max.y() + 1
        val placed = Shapes.roof(
            session,
            Shapes.Roof(
                box.min.x(), box.min.z(), box.max.x(), box.max.z(), base, style, axis == "x", high, stairs, slab,
                op.string("ridge_block", null)?.let { Edits.pattern(session, it) },
                op.string("gable", null)?.let { Edits.pattern(session, it) },
                overhang, op.bool("eaves", true),
            ),
        )
        val height = (if (style == "shed") max(box.sizeX, box.sizeZ) else min(box.sizeX, box.sizeZ)) / 2 + overhang + 2
        return OpResult(
            placed,
            Box(BlockVector3.at(box.min.x() - overhang, base - 1, box.min.z() - overhang), BlockVector3.at(box.max.x() + overhang, base + height, box.max.z() + overhang)),
        )
    }

    private fun transform(plugin: Foreman, args: Args): ToolResult {
        val world = plugin.world(args.string("world", null))
        val box = Box.of(args.vec("from"), args.vec("to")).requireVolume(plugin.limits.maxEditBlocks.toLong(), "transform_region")
        val rotate = Math.floorMod(args.int("rotate", 0), 360)
        if (rotate % 90 != 0) throw ToolError("rotate must be 0, 90, 180 or 270")
        val offset = args.vec("offset", BlockVector3.ZERO)!!
        val move = args.string("mode", "move") == "move"
        val ignoreAir = args.bool("ignore_air", false)

        val center = BlockVector3.at(
            Math.floorDiv(box.min.x() + box.max.x(), 2), box.min.y(), Math.floorDiv(box.min.z() + box.max.z(), 2)
        )
        val destination = center.add(offset)
        // WorldEdit's rotateY turns counter-clockwise seen from above (east → north).
        val transform = AffineTransform().rotateY(-rotate.toDouble())

        val corners = listOf(box.min, box.max, BlockVector3.at(box.min.x(), box.min.y(), box.max.z()), BlockVector3.at(box.max.x(), box.max.y(), box.min.z()))
            .map { transform.apply(it.subtract(center).toVector3()).toBlockPoint().add(destination) }
        val result = Box(corners.reduce { a, b -> a.getMinimum(b) }, corners.reduce { a, b -> a.getMaximum(b) })

        val label = "${if (move) "move" else "copy"} ${box.describe()}" + (if (rotate != 0) " rotated $rotate°" else "") +
            (if (offset != BlockVector3.ZERO) " by ${Box.fmt(offset)}" else "")
        // Furniture is taken out and placed again at its new spot: copying its entities through WorldEdit breaks on
        // the hitboxes the provider does not save, and its collision blocks are rebuilt by the new placement anyway.
        Furniture.ensureLoaded(world, box)
        val furniture = Sync.main { Furniture.inBox(world, box) }
        var moved = 0
        var skipped = 0
        val (_, change) = Edits.edit(world, label) { session, change ->
            if (move) Furniture.lift(world, furniture, change)
            val region: Region = CuboidRegion(session.world, box.min, box.max)
            val clipboard = BlockArrayClipboard(region)
            clipboard.origin = center
            val copy = ForwardExtentCopy(session, region, clipboard, region.minimumPoint)
            copy.isCopyingEntities = false
            Operations.complete(copy)

            if (move) session.setBlocks(region, BlockTypes.AIR!!.defaultState as Pattern)

            val holder = ClipboardHolder(clipboard)
            holder.transform = transform
            Operations.complete(
                holder.createPaste(session).to(destination).ignoreAirBlocks(ignoreAir).copyEntities(false).copyBiomes(false).build()
            )
            session.flushQueue()
            furniture.forEach { f ->
                val p = transform.apply(BlockVector3.at(f.x, f.y, f.z).subtract(center).toVector3()).toBlockPoint().add(destination)
                if (Furniture.respawn(world, f, p, f.yaw + rotate, change)) moved++ else skipped++
            }
        }

        return ToolResult.text(
            "Done: $label. The build now occupies ${result.describe()}. ${change.blocks} blocks changed" +
                (if (furniture.isNotEmpty()) ", $moved furniture ${if (move) "moved" else "copied"}" + (if (skipped > 0) " ($skipped could not be placed again: wall or obstructed)" else "") else "") +
                ". Other entities are not copied. Check the result with render, then fix the ground around it."
        )
    }
}
