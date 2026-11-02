package games.qore.foreman.client.hud

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import games.qore.foreman.client.hud.HudInspector.Element
import games.qore.foreman.client.hud.TextLayout.fmt
import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.mcp.ClientTool
import games.qore.foreman.client.mcp.ClientTools
import games.qore.foreman.client.mcp.ToolFailure
import games.qore.foreman.client.mixin.FontAccessor
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentSerialization
import net.minecraft.network.chat.FontDescription
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The AI's UI inspector tools: how the HUD's texts are laid out (parts, glyphs, offsets) and what's wrong, what moves
 * when they change, how a text would be laid out, the glyphs the packs declare, and whether declared widths (a
 * server's source of truth: glyph definitions, font width tables) match what the client draws.
 */
object UiTools {
    private val elements = Element.entries.joinToString(", ") { it.id }

    fun init() {
        HudInspector.init()

        ClientMcp.register(ClientTool(
            "ui_capture",
            "How a HUD text is laid out as the client draws it: its parts (the root's text, then each child, the way HUD " +
                "plugins compose a line), where each starts and ends and whether it comes back to where it started, each glyph " +
                "by texture name with its real advance and position on screen, and the problems found (a part that leaves the " +
                "cursor off moves everything after it; a centered line moves by half its width change). With an image of it, zoomed.",
            ClientTools.schema("element" to "string: $elements (default action_bar)", "index" to "integer: which boss bar, from the top (default 0)",
                "image" to "boolean: add a zoomed screenshot of the element (default true)"),
        ) { scope, args ->
            val element = element(args["element"].str())
            val index = args["index"].int() ?: 0
            val (report, region) = ClientTools.onClient {
                val text = HudInspector.text(element, index) ?: throw ToolFailure("${element.label} isn't shown now" + if (element == Element.BOSS_BAR) " (${HudInspector.bossBars()} boss bars)" else "")
                HudInspector.report(element, index, text) to region(element, index, TextLayout.of(text))
            }
            val image = if (args["image"].bool() != false && scope.allowScreenshots && region != null)
                runCatching { ClientTools.screenshot(1280, region, keepScreen = element == Element.SCREEN_TITLE) }.getOrNull() else null
            listOfNotNull(ClientMcp.text(report), image?.let { ClientMcp.image(it) })
        })

        ClientMcp.register(ClientTool(
            "ui_watch",
            "Watches a HUD text for `seconds` and reports every change that drew a glyph somewhere else: which glyphs moved and " +
                "by how much, the part whose text changed (before → after) and its net advance. Make the text change meanwhile: " +
                "`command` runs as the player right after the watch starts (a test command, an item use…).",
            ClientTools.schema("element" to "string: $elements (default action_bar)", "index" to "integer: which boss bar (default 0)",
                "seconds" to "integer: how long (default 10, max 60)", "command" to "string: optional command to run as the player (without the slash)"),
        ) { scope, args ->
            val element = element(args["element"].str())
            val seconds = (args["seconds"].int() ?: 10).coerceIn(1, 60)
            val command = args["command"].str()?.removePrefix("/")?.takeIf { it.isNotBlank() }
            if (command != null && !scope.allowCommands) throw ToolFailure("This session may not run commands")
            val done = CompletableFuture<String>()
            ClientTools.onClient {
                HudInspector.watch(element, args["index"].int() ?: 0, seconds) { done.complete(HudInspector.watchReport(it)) }
                command?.let { Minecraft.getInstance().connection?.sendCommand(it) }
            }
            listOf(ClientMcp.text(done.get(seconds + 10L, TimeUnit.SECONDS)))
        })

        ClientMcp.register(ClientTool(
            "ui_measure",
            "Lays out a text without showing it, as an action bar line: give `json` (a text component, like the server sends) " +
                "or `text` with an optional `font` (e.g. `myserver:hud`). Same report as ui_capture.",
            ClientTools.schema("json" to "string: a text component in JSON", "text" to "string: plain text (§ codes work)", "font" to "string: font id for `text`"),
        ) { _, args ->
            val report = ClientTools.onClient {
                val component = args["json"].str()?.let { parse(it) } ?: args["text"].str()?.let { text ->
                    val font = args["font"].str()?.let { Identifier.tryParse(it) ?: throw ToolFailure("Bad font id $it") }
                    Component.literal(text).let { c -> if (font != null) c.withStyle { it.withFont(FontDescription.Resource(font)) } else c }
                } ?: throw ToolFailure("Give json or text")
                HudInspector.report(Element.ACTION_BAR, 0, component)
            }
            listOf(ClientMcp.text(report))
        })

        ClientMcp.register(ClientTool(
            "ui_glyphs",
            "The glyphs the loaded resource packs declare, by font: character, texture name, height and ascent from the font " +
                "file, and the advance the client gives it; space characters with their advance.",
            ClientTools.schema("font" to "string: only this font (e.g. myserver:hud)", "query" to "string: part of the texture name",
                "spaces" to "boolean: list space characters too (default false)", "limit" to "integer: default 100, max 1000"),
        ) { _, args ->
            val text = ClientTools.onClient {
                val font = args["font"].str()
                val query = args["query"].str()?.lowercase()
                val spaces = args["spaces"].bool() == true
                // The default font first: most HUD glyphs are drawn from it.
                val all = FontCatalog.fonts().filterKeys { font == null || it.toString() == font }
                    .entries.sortedBy { if (it.key == Identifier.withDefaultNamespace("default")) "" else it.key.toString() }
                    .flatMap { it.value.values }
                    .filter { (it.kind == FontCatalog.Kind.BITMAP && !it.name.orEmpty().startsWith("font/")) || (spaces && it.kind == FontCatalog.Kind.SPACE) }
                    .filter { query == null || it.name?.lowercase()?.contains(query) == true }
                    .distinctBy { it.font to it.cp }
                val limit = (args["limit"].int() ?: 100).coerceIn(1, 1000)
                "${all.size} glyph(s)" + (if (all.size > limit) ", the first $limit" else "") + ":\n" + all.take(limit).joinToString("\n") { g ->
                    val advance = advance(g.font, g.cp)
                    if (g.kind == FontCatalog.Kind.SPACE) "U+%04X ${g.font} space ${fmt(advance)}".format(g.cp)
                    else "U+%04X ${g.font} ${g.name} height ${g.height} ascent ${g.ascent} advance ${fmt(advance)}".format(g.cp)
                }
            }
            listOf(ClientMcp.text(text))
        })

        ClientMcp.register(ClientTool(
            "ui_check_widths",
            "Checks declared widths (the server's source of truth: glyph definitions, a plugin's font width table) against " +
                "the advance the client draws. `widths`: {\"<character>\": width}; or `glyphs`: {\"<id>\": {\"chars\": [\"…\"] or " +
                "\"char\": \"…\", \"width\": n}} (a glyph index, as resource pack generators keep one). The client's advance should be width + `spacing` " +
                "(default 1, the pixel between characters). Each character is measured in `font`, else in the font that declares " +
                "it. Lists the mismatches: fix the declaration (or the texture), not the other way around.",
            ClientTools.schema("widths" to "object: character → declared width", "glyphs" to "object: glyph id → {chars, width}",
                "font" to "string: font to measure in", "spacing" to "integer: expected advance minus width (default 1)", "bold" to "boolean: measure bold (+1 per character)"),
        ) { _, args ->
            val declared = ArrayList<Triple<String, Int, Int>>() // label, codepoint, width
            (args["widths"] as? JsonObject)?.forEach { (chars, w) ->
                val width = (w as? JsonPrimitive)?.intOrNull ?: return@forEach
                chars.codePoints().forEach { cp -> declared += Triple("U+%04X".format(cp), cp, width) }
            }
            (args["glyphs"] as? JsonObject)?.forEach { (id, g) ->
                val width = (g.obj()?.get("width") as? JsonPrimitive)?.intOrNull ?: return@forEach
                val chars = when (val c = g.obj()?.get("chars") ?: g.obj()?.get("char")) {
                    is JsonArray -> c.mapNotNull { it.str() }.joinToString("")
                    else -> c.str().orEmpty()
                }
                chars.codePoints().forEach { cp -> declared += Triple(id, cp, width) }
            }
            if (declared.isEmpty()) throw ToolFailure("Give widths or glyphs")
            val spacing = args["spacing"].int() ?: 1
            val bold = args["bold"].bool() == true
            val text = ClientTools.onClient {
                val fixed = args["font"].str()?.let { Identifier.tryParse(it) ?: throw ToolFailure("Bad font id $it") }
                val catalog = FontCatalog.fonts()
                val wrong = declared.mapNotNull { (label, cp, width) ->
                    val font = fixed ?: catalog[Identifier.withDefaultNamespace("default")]?.get(cp)?.font
                        ?: catalog.entries.firstOrNull { cp in it.value }?.key ?: Identifier.withDefaultNamespace("default")
                    val advance = advance(font, cp, bold)
                    if (abs(advance - (width + spacing)) < 0.01f) null
                    else "$label (${"U+%04X".format(cp)} in $font${catalog[font]?.get(cp)?.name?.let { ", $it" } ?: ""}): declared $width, so ${width + spacing} expected; the client advances ${fmt(advance)}"
                }
                "${declared.size} character(s) checked, ${wrong.size} mismatch(es)" + if (wrong.isEmpty()) "." else ":\n" + wrong.take(300).joinToString("\n")
            }
            listOf(ClientMcp.text(text))
        })
    }

    private fun element(id: String?) = if (id == null) Element.ACTION_BAR else Element.of(id) ?: throw ToolFailure("element: $elements")

    /** The advance the client gives [cp] in [font]. Client thread. */
    fun advance(font: Identifier, cp: Int, bold: Boolean = false): Float =
        (Minecraft.getInstance().font as FontAccessor).`foreman$provider`().glyphs(FontDescription.Resource(font)).getGlyph(cp).info().getAdvance(bold)

    private fun parse(json: String): Component {
        val level = Minecraft.getInstance().level
        val ops = if (level != null) RegistryOps.create(JsonOps.INSTANCE, level.registryAccess()) else JsonOps.INSTANCE
        return ComponentSerialization.CODEC.parse(ops, JsonParser.parseString(json)).getOrThrow { ToolFailure("Bad component: $it") }
    }

    /**
     * Where an element is on screen (GUI units: x, y, width, height), from its layout and how the client places it: the
     * line's glyphs from the highest ascent to the lowest bottom, a few pixels around. Null when unknown.
     */
    fun region(element: Element, index: Int, layout: TextLayout.Layout): IntArray? {
        val window = Minecraft.getInstance().window
        val w = window.guiScaledWidth
        val h = window.guiScaledHeight
        val scale = HudInspector.scale(element)
        val baseline = HudInspector.baseline(element, index) ?: return null
        val start = HudInspector.start(element, layout.width)
        val drawn = layout.pieces.filter { it.kind != TextLayout.Kind.SPACE && it.advance != 0f }
        val left = (drawn.minOfOrNull { it.x } ?: 0f) + start
        val right = (drawn.maxOfOrNull { it.x + it.advance } ?: layout.sum) + start
        val glyphs = drawn.map { it.bitmap }
        val top = glyphs.maxOfOrNull { it?.ascent ?: 7 } ?: 7
        val bottom = glyphs.maxOfOrNull { g -> if (g == null) 1 else g.height - g.ascent } ?: 1
        val x0 = w / 2f + scale * left - 6
        val x1 = w / 2f + scale * right + 6
        val y0 = baseline - scale * top - 6
        val y1 = baseline + scale * bottom + 6
        return intArrayOf(x0.toInt().coerceAtLeast(0), y0.toInt().coerceAtLeast(0), (x1 - x0).toInt().coerceIn(8, w), (y1 - y0).toInt().coerceIn(8, h))
    }
}
