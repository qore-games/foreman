package games.qore.foreman.server.tools

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Args
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Baked
import games.qore.foreman.server.world.Camera
import games.qore.foreman.server.world.Displays
import games.qore.foreman.server.world.Furniture
import games.qore.foreman.server.world.Models
import games.qore.foreman.server.world.Renderer
import games.qore.foreman.server.world.Resources
import games.qore.foreman.server.world.Sync
import games.qore.foreman.server.world.Walk
import games.qore.foreman.server.api.FurnitureType
import org.joml.Vector3d
import java.awt.Color
import java.awt.image.BufferedImage
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Furniture models: preview a furniture or any model file with the game's textures.
 */
object ModelTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.PREVIEW_MODEL,
            "Shows a furniture model from four sides (front-left, front-right, back, above; its front is the side it " +
                "faces) with the game's textures, its size in blocks and its collision cells (orange on the floor). Use " +
                "it before decorating with furniture you have not seen.",
            schema {
                string("furniture", "Furniture id (list_furniture)", required = false)
                string("model", "Or a model file id, e.g. minecraft:block/anvil", required = false)
            },
        ) { args -> preview(plugin, args) },
    )

    // ---- Preview -------------------------------------------------------------------------------------------------

    private class Subject(val model: Models.ItemModel, val pose: (Float) -> org.joml.Matrix4d, val footprint: List<Triple<Int, Int, Int>>)

    private fun preview(plugin: Foreman, args: Args): ToolResult {
        val furniture = args.string("furniture", null)
        val subject = when {
            furniture != null -> {
                val type = Sync.main { Furniture.type(furniture) }
                val model = type.model()?.let { Models.item(it) } ?: throw ToolError("No item model for '$furniture' in the resource pack (${Resources.status()})")
                Subject(model, { yaw -> type.pose(yaw) }, type.footprint().map { Triple(it.blockX, it.blockY, it.blockZ) })
            }

            args.has("model") -> Subject(Models.modelFile(args.string("model")) ?: throw ToolError("No model ${args.string("model")}"), { Displays.itemPose() }, emptyList())
            else -> throw ToolError("Give furniture or model")
        }
        val baked = subject.model.baked.transformed(subject.pose(0f).mul(subject.model.fixed))
        val image = turntable(baked, subject.footprint)
        return ToolResult.image(
            Renderer.png(image),
            "Model ${Displays.describe(baked.min, baked.max)} in blocks around its root block (0..1), placed facing north." +
                (if (subject.footprint.isNotEmpty()) " Collision cells: " + subject.footprint.joinToString(" ") { "[${it.first}, ${it.second}, ${it.third}]" } + "."
                else " No collision cells (players walk through it).") + " Views: front-left, front-right, back, above.",
        )
    }

    /** A furniture's model bounds relative to its root block's corner, for each side it can face; empty when unknown. */
    fun boundsByFacing(type: FurnitureType): Map<org.bukkit.block.BlockFace, Pair<Vector3d, Vector3d>> {
        val model = type.model()?.let { Models.item(it) } ?: return emptyMap()
        return Walk.FACES.associateWith { f ->
            val baked = model.baked.transformed(type.pose(games.qore.foreman.server.world.Arrange.yaw(f)).mul(model.fixed))
            baked.min to baked.max
        }.filterValues { !it.first.x.isInfinite() }
    }

    /** Four studio views of [model] (its front is north), the [footprint] cells drawn on the floor. */
    private fun turntable(model: Baked, footprint: List<Triple<Int, Int, Int>>): BufferedImage = turntable(listOf(model), footprint)

    /** Four studio views of [models] together (front-left, front-right, back, above), [footprint] cells on the floor. */
    fun turntable(models: List<Baked>, footprint: List<Triple<Int, Int, Int>>): BufferedImage {
        val solid = models.filter { !it.empty }
        if (solid.isEmpty()) throw ToolError("The model has no visible faces")
        val lo = solid.fold(Vector3d(Double.MAX_VALUE)) { a, b -> a.min(b.min) }
        val hi = solid.fold(Vector3d(-Double.MAX_VALUE)) { a, b -> a.max(b.max) }
        val cells = footprint.map { (x, y, z) -> Baked(Models.cuboid(Vector3d(x + 0.03, y.toDouble(), z + 0.03), Vector3d(x + 0.97, y + 0.015, z + 0.97), 0xffe0782a.toInt())) }
        return turntable(Camera.studio(solid + cells), lo, hi)
    }

    /** Four views of [stage] around the box [lo]..[hi]. */
    fun turntable(stage: Camera.Stage, lo: Vector3d, hi: Vector3d): BufferedImage {
        val center = Vector3d(lo).add(hi).mul(0.5)
        val size = max(hi.x - lo.x, max(hi.y - lo.y, hi.z - lo.z))
        val distance = size * 1.9 + 1.5
        val views = listOf("front-left" to Vector3d(-0.7, 0.55, -1.0), "front-right" to Vector3d(0.7, 0.55, -1.0), "back" to Vector3d(0.3, 0.45, 1.0), "above" to Vector3d(0.15, 2.2, -0.6))
        val w = 480
        val h = 360
        val out = BufferedImage(w * 2, h * 2, BufferedImage.TYPE_INT_RGB)
        val g = out.createGraphics()
        views.forEachIndexed { i, (label, dir) ->
            val eye = Vector3d(dir).normalize().mul(distance).add(center)
            val (yaw, pitch) = Camera.lookAt(eye, center)
            g.drawImage(stage.render(Camera.View(eye, yaw, pitch, 45.0), w, h, max(64.0, distance * 3), label), (i % 2) * w, (i / 2) * h, null)
        }
        g.color = Color(255, 255, 255, 120)
        g.drawLine(w, 0, w, h * 2)
        g.drawLine(0, h, w * 2, h)
        g.dispose()
        return out
    }
}
