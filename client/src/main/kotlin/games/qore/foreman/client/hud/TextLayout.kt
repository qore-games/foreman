package games.qore.foreman.client.hud

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import games.qore.foreman.client.mixin.FontAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GlyphSource
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FontDescription
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackResources
import net.minecraft.util.FormattedCharSink
import net.minecraft.util.StringDecomposer
import java.util.Locale
import java.util.Optional
import kotlin.math.abs
import kotlin.math.ceil

/**
 * The glyphs the loaded resource packs declare, read from their font files: per font and character, the texture it
 * draws (its name for the inspector), its height and ascent, or the advance of a space character (the offsets servers
 * position HUD elements with). Read again when the packs change.
 */
object FontCatalog {
    enum class Kind { BITMAP, SPACE, OTHER }

    class Glyph(val font: Identifier, val cp: Int, val kind: Kind, val name: String?, val height: Int, val ascent: Int, val advance: Float?)

    /** The packs [fonts] were read from: opened anew at each reload, so a server pack sent again under its id counts. */
    private var packs: List<PackResources> = emptyList()
    private var fonts: Map<Identifier, Map<Int, Glyph>> = emptyMap()

    fun fonts(): Map<Identifier, Map<Int, Glyph>> {
        val current = Minecraft.getInstance().resourceManager.listPacks().toList()
        if (current.size != packs.size || current.indices.any { current[it] !== packs[it] }) {
            packs = current
            fonts = load()
        }
        return fonts
    }

    private fun load(): Map<Identifier, Map<Int, Glyph>> {
        val manager = Minecraft.getInstance().resourceManager
        val providers = HashMap<Identifier, List<JsonObject>>()
        for ((file, stack) in manager.listResourceStacks("font") { it.path.endsWith(".json") }) {
            val id = Identifier.fromNamespaceAndPath(file.namespace, file.path.removePrefix("font/").removeSuffix(".json"))
            // The pack on top comes first: the first provider with a character wins, as the client draws it.
            providers[id] = stack.asReversed().flatMap { resource ->
                runCatching { resource.openAsReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonArray("providers").map { p -> p.asJsonObject } } }.getOrDefault(emptyList())
            }
        }
        return providers.keys.associateWith { resolve(it, providers, HashSet()) }
    }

    private fun resolve(font: Identifier, providers: Map<Identifier, List<JsonObject>>, seen: MutableSet<Identifier>): Map<Int, Glyph> {
        if (!seen.add(font)) return emptyMap()
        val out = LinkedHashMap<Int, Glyph>()
        for (p in providers[font].orEmpty()) {
            when (p.get("type")?.asString) {
                "reference" -> p.get("id")?.asString?.let { Identifier.tryParse(it) }?.let { ref -> resolve(ref, providers, seen).forEach { (cp, g) -> out.putIfAbsent(cp, g) } }
                "space" -> p.getAsJsonObject("advances")?.entrySet()?.forEach { (chars, advance) ->
                    chars.codePoints().findFirst().ifPresent { cp -> out.putIfAbsent(cp, Glyph(font, cp, Kind.SPACE, null, 0, 0, advance.asFloat)) }
                }
                "bitmap" -> {
                    val file = p.get("file")?.asString ?: continue
                    val name = file.removeSuffix(".png").removePrefix("minecraft:")
                    val height = p.get("height")?.asInt ?: 8
                    val ascent = p.get("ascent")?.asInt ?: 7
                    val rows = p.getAsJsonArray("chars")?.map { it.asString.codePoints().toArray() } ?: continue
                    val sheet = rows.size > 1 || rows.any { it.size > 1 }
                    rows.forEachIndexed { r, row ->
                        row.forEachIndexed { c, cp ->
                            if (cp != 0) out.putIfAbsent(cp, Glyph(font, cp, Kind.BITMAP, if (sheet) "$name[$r,$c]" else name, height, ascent, null))
                        }
                    }
                }
                else -> {}
            }
        }
        return out
    }
}

/**
 * Text as the client lays it out, glyph by glyph: each character's real advance in its font (bold included), where it
 * starts, and whether it is text, a glyph (a texture) or a space (an offset). Split in parts the way HUD plugins compose
 * a line: the root's own text, then each child (a plugin's component, labelled by its style `insertion` when it sets
 * one). Read on the client thread.
 */
object TextLayout {
    enum class Kind { TEXT, GLYPH, SPACE }

    /** A character drawn: [glyph] is what its font file declares for it, when the packs declare it. */
    class Piece(val cp: Int, val font: Identifier, val bold: Boolean, val x: Float, val advance: Float, val kind: Kind, val glyph: FontCatalog.Glyph?) {
        val name: String? get() = glyph?.name

        /** A bitmap's height and ascent, to place it on screen (text is 8 tall, 7 up). */
        val bitmap: FontCatalog.Glyph? get() = glyph?.takeIf { it.kind == FontCatalog.Kind.BITMAP }

        /** `hud/ammo_bg`, `"a"`, `+12`. */
        val label: String get() = when (kind) {
            Kind.GLYPH -> name ?: "U+%04X".format(cp)
            Kind.SPACE -> (if (advance >= 0) "+" else "") + fmt(advance)
            Kind.TEXT -> String(Character.toChars(cp))
        }
    }

    class Part(val index: Int, val label: String?, val pieces: List<Piece>, val start: Float, val end: Float) {
        val net get() = end - start
        private val drawn get() = pieces.filter { it.kind != Kind.SPACE && it.advance != 0f }
        /** Where something is drawn, from the start of the line: left and right edges, or null. */
        val left: Float? get() = drawn.minOfOrNull { it.x }
        val right: Float? get() = drawn.maxOfOrNull { it.x + it.advance }

        /** `[-91] hud/ammo_bg [-60] Reloading 9 [+151]`: spaces summed, glyphs by name, text as is. */
        val text: String get() = buildString {
            var space = 0f
            fun flush() {
                if (space != 0f) append(if (isNotEmpty() && last() != ' ') " " else "").append('[').append(if (space > 0) "+" else "").append(fmt(space)).append("] ")
                space = 0f
            }
            for (p in pieces) when (p.kind) {
                Kind.SPACE -> space += p.advance
                Kind.GLYPH -> { flush(); append(p.label.substringAfterLast(':')).append(' ') }
                Kind.TEXT -> { flush(); append(p.label) }
            }
            flush()
        }.trim()
    }

    class Layout(val parts: List<Part>) {
        val pieces get() = parts.flatMap { it.pieces }
        /** The sum of the advances; the client centers on its ceiling ([width]). */
        val sum get() = parts.lastOrNull()?.end ?: 0f
        val width get() = ceil(sum.toDouble()).toInt()
    }

    fun of(component: Component): Layout {
        val provider = (Minecraft.getInstance().font as FontAccessor).`foreman$provider`()
        val catalog = FontCatalog.fonts()
        val sources = HashMap<FontDescription, GlyphSource>()
        val parts = ArrayList<Part>()
        var x = 0f
        fun part(label: String?, visit: (FormattedCharSink) -> Unit) {
            val start = x
            val pieces = ArrayList<Piece>()
            visit(FormattedCharSink { _, style, cp ->
                val description = style.font
                val advance = sources.getOrPut(description) { provider.glyphs(description) }.getGlyph(cp).info().getAdvance(style.isBold)
                val font = (description as? FontDescription.Resource)?.id() ?: Identifier.withDefaultNamespace("default")
                val glyph = catalog[font]?.get(cp)
                // Vanilla's own sheets (font/ascii…) and spaces are text; any other bitmap is a glyph, any other space an offset.
                val kind = when {
                    glyph?.kind == FontCatalog.Kind.SPACE && !Character.isWhitespace(cp) -> Kind.SPACE
                    glyph?.kind == FontCatalog.Kind.BITMAP && !glyph.name.orEmpty().startsWith("font/") -> Kind.GLYPH
                    else -> Kind.TEXT
                }
                pieces += Piece(cp, font, style.isBold, x, advance, kind, glyph)
                x += advance
                true
            })
            if (pieces.isNotEmpty() || label != null) parts += Part(parts.size, label, pieces, start, x)
        }
        fun chars(style: Style, text: String, sink: FormattedCharSink): Optional<Unit> {
            StringDecomposer.iterateFormatted(text, style, sink)
            return Optional.empty()
        }
        part(component.style.insertion) { sink -> component.contents.visit(FormattedText.StyledContentConsumer { s, t -> chars(s, t, sink) }, component.style) }
        for (child in component.siblings) {
            part(child.style.insertion) { sink -> child.visit(FormattedText.StyledContentConsumer { s, t -> chars(s, t, sink) }, component.style) }
        }
        return Layout(parts)
    }

    /** What looks wrong in a line drawn centered (action bar, titles, boss bars), in plain words. */
    fun problems(layout: Layout, centered: Boolean): List<String> {
        val out = ArrayList<String>()
        val parts = layout.parts.filter { it.pieces.isNotEmpty() }
        // Plugins that compose a line of parts make each one come back to where it started: one that doesn't moves the rest.
        val balanced = parts.count { abs(it.net) < 0.5f }
        if (parts.size >= 2 && balanced * 2 >= parts.size) {
            for (p in parts) if (abs(p.net) >= 0.5f) {
                out += "part ${p.index}${p.label?.let { " ($it)" } ?: ""} doesn't come back to where it started: it leaves the cursor ${signed(p.net)} px, " +
                    "so every part after it is drawn ${signed(p.net)} px off" + (if (centered) " and the whole line moves ${signed(-p.net / 2)} px" else "") + ". It reads: ${p.text.take(120)}"
            }
        }
        if (centered && abs(layout.sum) >= 0.5f && (parts.size < 2 || balanced * 2 < parts.size)) {
            out += "the line is ${fmt(layout.sum)} px wide and drawn centered: anything placed from its start moves by half of any change in that width"
        }
        val fractional = layout.pieces.filter { abs(it.advance - Math.round(it.advance)) > 0.01f }
        if (fractional.isNotEmpty()) {
            out += "${fractional.size} character(s) advance by a fraction of a pixel (${fractional.distinctBy { it.label }.take(4).joinToString { "${it.label} ${fmt(it.advance)}" }}): " +
                "a glyph's height doesn't divide its texture, or a TrueType font; positions computed in whole pixels drift"
        }
        return out
    }

    fun fmt(f: Float): String = if (f == Math.round(f).toFloat()) Math.round(f).toString() else String.format(Locale.ROOT, "%.2f", f)

    private fun signed(f: Float) = (if (f > 0) "+" else "") + fmt(f)
}
