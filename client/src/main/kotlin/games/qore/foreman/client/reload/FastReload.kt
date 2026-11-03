package games.qore.foreman.client.reload

import com.google.common.collect.Lists
import com.mojang.blaze3d.font.GlyphProvider
import com.mojang.blaze3d.font.SpaceProvider
import com.mojang.blaze3d.font.TrueTypeGlyphProvider
import com.mojang.blaze3d.font.UnbakedGlyph
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.util.Chat
import it.unimi.dsi.fastutil.ints.Int2ObjectMap
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap
import it.unimi.dsi.fastutil.ints.IntArrayList
import it.unimi.dsi.fastutil.ints.IntList
import it.unimi.dsi.fastutil.ints.IntOpenHashSet
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.font.AllMissingGlyphProvider
import net.minecraft.client.gui.font.FontOption
import net.minecraft.client.gui.font.glyphs.SpecialGlyphs
import net.minecraft.client.gui.font.providers.BitmapProvider
import net.minecraft.client.gui.font.providers.UnihexProvider
import net.minecraft.client.gui.screens.Overlay
import net.minecraft.client.renderer.texture.TextureAtlasSprite
import net.minecraft.util.Mth
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ForkJoinTask
import java.util.function.Consumer

/**
 * Faster resource reloads, and playing through them (the mixins call it): the work vanilla does on one thread or on
 * the main thread, spread over the background threads. Results are the same as vanilla's; only where and when they are
 * computed changes.
 */
object FastReload {
    @JvmStatic fun enabled(): Boolean = Config.settings.reload.fast

    @JvmStatic fun keepsPlaying(): Boolean = enabled() && Config.settings.reload.keepPlaying

    /** [overlay] is a reload's loading screen that lets the game go on. */
    @JvmStatic fun inBackground(overlay: Overlay?): Boolean = overlay is ReloadOverlay && overlay.`foreman$inBackground`()

    /** Since when a reload's listeners apply (all are prepared), in nanoseconds; 0 when none does. */
    @Volatile private var applyingSince = 0L

    /** Every listener of the client's reload is prepared: they apply now, on the main thread. */
    @JvmStatic fun applying() {
        if (inBackground(Minecraft.getInstance().gui.overlay())) applyingSince = System.nanoTime()
    }

    /**
     * No frame while a reload that keeps playing applies its listeners (a frame between two would show new textures
     * on the old models): the window keeps showing the last one. Vanilla draws them behind its loading screen; at most
     * [MAX_HOLD_MS], should a listener wait for one.
     */
    @JvmStatic fun holdsFrames(): Boolean {
        val since = applyingSince
        if (since == 0L) return false
        val held = (System.nanoTime() - since) / 1_000_000 < MAX_HOLD_MS && inBackground(Minecraft.getInstance().gui.overlay())
        if (!held) applyingSince = 0L
        return held
    }

    /** The reload's loading screen closed. */
    @JvmStatic fun reloadEnded() {
        applyingSince = 0L
    }

    private const val MAX_HOLD_MS = 500L

    /**
     * The main thread ran its tasks: a reload whose last listener was just applied ends now, not at the next tick, so no
     * frame draws the new textures on chunks built with the old ones.
     */
    @JvmStatic fun tasksRan() {
        val overlay = Minecraft.getInstance().gui.overlay()
        if (overlay is ReloadOverlay && overlay.`foreman$fast`()) overlay.tick()
    }

    /** A reload that keeps playing: the open screen as usual, and a thin bar of its progress along the top. */
    @JvmStatic fun extractBackgroundReload(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float, progress: Float) {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.gui.screen()
        if (screen != null) screen.extractRenderStateWithTooltipAndSubtitles(graphics, mouseX, mouseY, a)
        else minecraft.gui.hud.extractDeferredSubtitles()
        graphics.nextStratum()
        val width = graphics.guiWidth()
        graphics.fill(0, 0, width, 2, 0x66000000)
        graphics.fill(0, 0, Mth.ceil(width * progress.coerceIn(0f, 1f)), 2, 0xCCFFFFFF.toInt())
    }

    /** `/fastreload on|off|playing|screen`, and the bare command reloads the packs like F3+T. */
    fun init() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(literal("fastreload")
                .executes { Minecraft.getInstance().reloadResourcePacks(); 1 }
                .then(literal("on").executes { set(fast = true); 1 })
                .then(literal("off").executes { set(fast = false); 1 })
                .then(literal("playing").executes { set(fast = true, keepPlaying = true); 1 })
                .then(literal("screen").executes { set(fast = true, keepPlaying = false); 1 }))
        }
    }

    private fun set(fast: Boolean, keepPlaying: Boolean = Config.settings.reload.keepPlaying) {
        Config.update { reload.fast = fast; reload.keepPlaying = keepPlaying }
        Chat.info(when {
            !fast -> "Fast resource reloads off: vanilla reloads"
            keepPlaying -> "Fast resource reloads on, the game going on meanwhile"
            else -> "Fast resource reloads on, behind the loading screen"
        })
    }

    /**
     * Runs [action] on every sprite on the current fork-join pool (the background threads), the biggest sprites first:
     * they take the longest, so they shouldn't be the last to start. Returns when all are done.
     */
    @JvmStatic fun inParallel(sprites: Collection<TextureAtlasSprite>, action: Consumer<TextureAtlasSprite>) {
        val tasks = sprites.sortedByDescending { it.contents().width().toLong() * it.contents().height() }.map { ForkJoinTask.adapt { action.accept(it) } }
        // Thieves take the oldest forks first: the biggest.
        tasks.forEach { it.fork() }
        tasks.forEach { it.join() }
    }

    /** A font's providers in use and its glyphs by advance, as `FontSet.selectProviders` makes them. */
    class FontSelection(val options: Set<FontOption>, val providers: List<GlyphProvider>, val byWidth: Int2ObjectMap<IntList>)

    /** Each font's selection, by its provider list (in the order the font set gets it), until the fonts are applied. */
    private val fonts = ConcurrentHashMap<List<GlyphProvider.Conditional>, FontSelection>()

    /**
     * Selects a font's providers off the main thread (vanilla does it for every font, codepoint by codepoint, while the
     * game is frozen). [list] is the font's providers as the font manager prepared them, which its font set gets reversed.
     */
    @JvmStatic fun prepareFont(list: List<GlyphProvider.Conditional>) {
        val providers = ArrayList(Lists.reverse(list))
        fonts[providers] = selectProviders(providers, fontOptions())
    }

    /** The selection prepared for these providers and options, or null to let vanilla select them. */
    @JvmStatic fun preparedFont(providers: List<GlyphProvider.Conditional>, options: Set<FontOption>): FontSelection? =
        fonts[providers]?.takeIf { it.options == options }

    /** Fonts start loading, or were applied: no selection is kept (a failed reload never applies its fonts). */
    @JvmStatic fun forgetFonts() = fonts.clear()

    /**
     * `FontSet.selectProviders`, without touching the font set. Vanilla tries every provider for every codepoint, which
     * is quadratic for fonts made of many small providers (one per icon); a provider of vanilla's kinds has a glyph
     * exactly for its supported codepoints, so each can claim those not taken yet, in order. Other kinds: vanilla's way.
     */
    private fun selectProviders(providers: List<GlyphProvider.Conditional>, options: Set<FontOption>): FontSelection {
        val selected = providers.filter { it.filter().apply(options) }.map { it.provider() }
        val used = HashSet<GlyphProvider>()
        val byWidth = Int2ObjectOpenHashMap<IntList>()
        fun take(provider: GlyphProvider, codepoint: Int, glyph: UnbakedGlyph) {
            used.add(provider)
            if (glyph.info() !== SpecialGlyphs.MISSING) byWidth.computeIfAbsent(Mth.ceil(glyph.info().getAdvance(false))) { IntArrayList() }.add(codepoint)
        }
        if (selected.all { it.javaClass in EXACT_PROVIDERS || it is AllMissingGlyphProvider }) {
            val supported = IntOpenHashSet()
            selected.forEach { supported.addAll(it.supportedGlyphs) }
            val taken = IntOpenHashSet()
            for (provider in selected) {
                if (provider is AllMissingGlyphProvider) {
                    if (taken.size < supported.size) used.add(provider)
                    break
                }
                provider.supportedGlyphs.forEach { codepoint ->
                    if (codepoint !in taken) provider.getGlyph(codepoint)?.let { taken.add(codepoint); take(provider, codepoint, it) }
                }
            }
        } else {
            val supported = IntOpenHashSet()
            selected.forEach { supported.addAll(it.supportedGlyphs) }
            supported.forEach { codepoint ->
                for (provider in selected) {
                    val glyph = provider.getGlyph(codepoint) ?: continue
                    take(provider, codepoint, glyph)
                    break
                }
            }
        }
        return FontSelection(options, selected.filter { it in used }, byWidth)
    }

    /** The providers whose `getGlyph` is non-null exactly for their `getSupportedGlyphs`. */
    private val EXACT_PROVIDERS = setOf(BitmapProvider::class.java, SpaceProvider::class.java, TrueTypeGlyphProvider::class.java, UnihexProvider::class.java)

    /** `FontManager.getFontOptions`. */
    private fun fontOptions(): Set<FontOption> {
        val options = Minecraft.getInstance().options
        val result = EnumSet.noneOf(FontOption::class.java)
        if (options.forceUnicodeFont().get()) result.add(FontOption.UNIFORM)
        if (options.japaneseGlyphVariants().get()) result.add(FontOption.JAPANESE_VARIANTS)
        return result
    }
}
