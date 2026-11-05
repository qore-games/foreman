package games.qore.foreman.client.lean

import games.qore.foreman.client.mixin.OptionInstanceAccessor
import net.minecraft.client.CloudStatus
import net.minecraft.client.GraphicsPreset
import net.minecraft.client.OptionInstance
import net.minecraft.client.Options
import net.minecraft.client.PrioritizeChunkUpdates
import net.minecraft.server.level.ParticleStatus

/**
 * Lean mode's options, in memory only: set when options.txt is read (before the window, the atlases and the server see
 * them), and swapped back for the player's own while options.txt is written, so the same game directory runs lean or
 * normal. Values are set as stored, without the callbacks a change in the options screen runs.
 */
object LeanOptions {
    private class Override<T : Any>(val option: (Options) -> OptionInstance<T>, val lean: () -> T) {
        /** The player's value, from options.txt. */
        private var own: T? = null
        /** The value in use while options.txt is written. */
        private var held: T? = null

        fun load(options: Options) {
            own = option(options).get()
            set(options, lean())
        }

        fun hold(options: Options) {
            held = option(options).get()
            own?.let { set(options, it) }
        }

        fun release(options: Options) {
            held?.let { set(options, it) }
        }

        private fun set(options: Options, value: T) = (option(options) as OptionInstanceAccessor).`foreman$setValue`(value)
    }

    private val overrides = listOf(
        Override({ it.renderDistance() }) { Lean.renderDistance },
        Override({ it.simulationDistance() }) { 5 },
        // a preset would set the others back as the game starts
        Override({ it.graphicsPreset() }) { GraphicsPreset.CUSTOM },
        Override({ it.particles() }) { ParticleStatus.MINIMAL },
        Override({ it.cloudStatus() }) { CloudStatus.OFF },
        Override({ it.mipmapLevels() }) { 0 },
        Override({ it.framerateLimit() }) { 30 },
        // presenting to a window nobody sees must never wait for the screen
        Override({ it.enableVsync() }) { false },
        Override({ it.fullscreen() }) { false },
        Override({ it.entityShadows() }) { false },
        Override({ it.biomeBlendRadius() }) { 0 },
        // a frame drawn on demand shows chunks whole: built at once near the player, no fade-in
        Override({ it.chunkSectionFadeInTime() }) { 0.0 },
        Override({ it.prioritizeChunkUpdates() }) { PrioritizeChunkUpdates.NEARBY },
    )
    private var ownPauseOnLostFocus = true
    private var heldPauseOnLostFocus = false

    /** LeanOptionsMixin, options.txt read. */
    @JvmStatic fun loaded(options: Options) {
        if (!Lean.enabled) return
        overrides.forEach { it.load(options) }
        ownPauseOnLostFocus = options.pauseOnLostFocus
        options.pauseOnLostFocus = false
    }

    /** LeanOptionsMixin, before options.txt is written: the player's values. */
    @JvmStatic fun beforeSave(options: Options) {
        if (!Lean.enabled) return
        overrides.forEach { it.hold(options) }
        heldPauseOnLostFocus = options.pauseOnLostFocus
        options.pauseOnLostFocus = ownPauseOnLostFocus
    }

    /** LeanOptionsMixin, after: the values in use again. */
    @JvmStatic fun afterSave(options: Options) {
        if (!Lean.enabled) return
        overrides.forEach { it.release(options) }
        options.pauseOnLostFocus = heldPauseOnLostFocus
    }
}
