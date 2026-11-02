package games.qore.foreman.server.integrations

import com.ticxo.modelengine.api.ModelEngineAPI
import com.ticxo.modelengine.api.generator.blueprint.BlueprintBone
import com.ticxo.modelengine.api.generator.blueprint.ModelBlueprint
import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Tool
import games.qore.foreman.server.mcp.ToolError
import games.qore.foreman.server.mcp.ToolResult
import games.qore.foreman.server.mcp.schema
import games.qore.foreman.server.world.Sync
import java.util.Locale

/** ModelEngine's loaded models: it has no command that lists them, their bones or their animations. */
object ModelEngineTools {
    fun all(plugin: Foreman) = listOf(
        Tool(
            ForemanTool.MODELENGINE_MODELS,
            "ModelEngine's loaded models (blueprints): their ids with their animations, or one `model` in full: hitbox, " +
                "its bones as a tree with their behaviors (head, hitboxes, seats, nametags…), its animations with their " +
                "length and loop mode. Use the ids and animation names it gives in MythicMobs mechanics (model{mid=…}, " +
                "state{s=…}); see the modelengine skill.",
            schema {
                string("model", "A model id: everything about it", required = false)
                string("query", "Only models whose id contains this", required = false)
            },
        ) { args ->
            Sync.main {
                val registry = ModelEngineAPI.getAPI().modelRegistry
                val id = args.string("model", null)
                if (id != null) {
                    val model = registry.get(id) ?: throw ToolError("No model '$id'. Models: ${registry.orderedId.take(40).joinToString()}")
                    ToolResult.text(describe(id, model))
                } else {
                    val query = args.string("query", null)?.lowercase()
                    val ids = registry.orderedId.filter { query == null || it.lowercase().contains(query) }
                    ToolResult.text(if (ids.isEmpty()) "No model loaded" + (query?.let { " matching '$it'" }.orEmpty()) + " (blueprints in plugins/ModelEngine/blueprints, then /meg reload)."
                    else "${ids.size} model(s):\n" + ids.joinToString("\n") { m ->
                        val model = registry.get(m)
                        "- $m: ${model.flatMap.size} bones, animations: ${model.animations.keys.joinToString().ifEmpty { "none" }}"
                    })
                }
            }
        },
    )

    private fun describe(id: String, model: ModelBlueprint): String = buildString {
        val box = model.mainHitbox
        appendLine("$id: hitbox ${f(box.width)} x ${f(box.height)} x ${f(box.depth)} (eyes at ${f(box.eyeHeight)}), shadow radius ${f(model.shadowRadius.toDouble())}")
        appendLine("Animations (${model.animations.size}):")
        model.animations.values.forEach { a -> appendLine("  ${a.name}: ${f(a.length)} s, ${a.loopMode.name.lowercase()}") }
        appendLine("Bones (${model.flatMap.size}):")
        fun bone(b: BlueprintBone, depth: Int) {
            val behaviors = b.behaviors.keys.takeIf { it.isNotEmpty() }?.joinToString(prefix = " [", postfix = "]").orEmpty()
            appendLine("  ".repeat(depth + 1) + b.name + behaviors)
            b.children.values.forEach { bone(it, depth + 1) }
        }
        model.bones.values.forEach { bone(it, 0) }
    }

    private fun f(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
}
