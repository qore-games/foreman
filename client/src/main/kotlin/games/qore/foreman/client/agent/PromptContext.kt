package games.qore.foreman.client.agent

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.link.WorldEditSelection
import net.minecraft.client.Minecraft
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult

/** What the builder's game knows that the AI should: where they are and look, their selection. Read on the client thread. */
object PromptContext {
    fun build(): String {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return ""
        val level = minecraft.level ?: return ""
        val lines = mutableListOf("Context from ${player.name.string}'s game client (they are the one asking):")
        val world = Bridge.world.ifEmpty { level.dimension().identifier().toString() }
        lines += "- Position: [${player.blockX}, ${player.blockY}, ${player.blockZ}] in world $world, facing ${facing(player.yRot)}, pitch ${player.xRot.toInt()}"
        when (val hit = minecraft.hitResult) {
            is BlockHitResult -> if (hit.type == HitResult.Type.BLOCK) {
                val block = BuiltInRegistries.BLOCK.getKey(level.getBlockState(hit.blockPos).block)
                lines += "- Looking at $block at [${hit.blockPos.x}, ${hit.blockPos.y}, ${hit.blockPos.z}] (${hit.direction.name.lowercase()} face)"
            }
            is EntityHitResult -> lines += "- Looking at entity ${hit.entity.type.description.string} at [${hit.entity.blockX}, ${hit.entity.blockY}, ${hit.entity.blockZ}]"
            else -> {}
        }
        SelectionTool.describe()?.let { lines += "- Foreman selection (the client selection tool): $it" }
        WorldEditSelection.describe()?.let { lines += "- WorldEdit selection: $it" }
        if (SelectionTool.describe() == null && WorldEditSelection.describe() == null) lines += "- No selection"
        lines += "- \"Here\", \"this\", \"the selection\" and \"where I look\" refer to the above (work in a selection, and around it only when asked)."
        return lines.joinToString("\n")
    }

    private fun facing(yaw: Float) = listOf("south (+z)", "west (-x)", "north (-z)", "east (+x)")[Math.floorMod(Math.round(yaw / 90f), 4)]
}
