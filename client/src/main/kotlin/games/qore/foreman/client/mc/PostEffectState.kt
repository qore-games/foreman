package games.qore.foreman.client.mc

import net.minecraft.resources.Identifier

/**
 * The post effects of the frame, from [Mc.postEffects]: the ones [applied] in order and the ones that [failed] to load.
 * [requested] is null on versions that run one post effect at a time.
 */
data class PostEffectState(
    val requested: List<Identifier>?,
    val fromServer: List<Identifier>,
    val spectated: Identifier?,
    val applied: List<Identifier>,
    val failed: List<Identifier>,
)
