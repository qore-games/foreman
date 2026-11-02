package games.qore.foreman.client.ui

import games.qore.foreman.client.agent.Chat
import games.qore.foreman.client.agent.Chats
import games.qore.foreman.client.agent.Entry
import games.qore.foreman.client.agent.EntryKind
import games.qore.foreman.client.config.AgentBackend
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.link.WorldEditSelection
import games.qore.foreman.client.packets.PacketRecorder
import games.qore.foreman.client.packets.PacketViewer
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.util.FormattedCharSequence

/**
 * The AI chats: the list on the left (each runs on its own, a dot while it works), the open chat on the right with its
 * backend, workspaces and permissions, the transcript and the prompt box. "+" opens the quick actions: ready-made
 * prompts of the client and the server's MCP prompts. Closing the screen stops nothing.
 */
class ChatScreen(private val initial: String = "", open: Chat? = null) : KitScreen("Foreman chats") {
    /** The chat shown. */
    var chat: Chat = open ?: Chats.latest()
        private set

    private var input: EditBox? = null
    /** A chat whose delete button was clicked once: the next click deletes it. */
    private var confirmDelete: Chat? = null
    /** The quick actions pop-over is open. */
    private var menu = false

    private val rail = 150
    private val pad = 12
    private val x0 get() = rail + pad
    private val contentWidth get() = width - x0 - pad
    private val top = 54
    private val bottom get() = height - 34

    override val modal get() = menu

    override fun build() {
        val keep = input?.value ?: initial
        input = addRenderableWidget(EditBox(font, x0 + 24, height - 26, contentWidth - 24 - 64, 18, Component.literal("Prompt")).also {
            it.setMaxLength(8000)
            it.setHint(Component.literal(if (Bridge.connected) "Ask about your server, a plugin, a fix… (Enter)" else "Ask… (no Foreman server here: client tools only)"))
            it.value = keep
        })
        setInitialFocus(input!!)
        if (Chats.prompts.isEmpty()) Chats.loadPrompts()
    }

    private fun select(c: Chat) {
        chat = c
        confirmDelete = null
        menu = false
        resetScroll("transcript")
    }

    private fun delete(c: Chat) {
        if (confirmDelete !== c) {
            confirmDelete = c
            return
        }
        Chats.delete(c)
        confirmDelete = null
        if (c === chat) select(Chats.latest())
    }

    private fun insert(text: String) {
        val box = input ?: return
        box.value = (box.value.trimEnd() + " " + text).trim()
        setFocused(box)
    }

    private fun selection() = SelectionTool.describe() ?: WorldEditSelection.describe()?.let { "$it (WorldEdit)" }

    /** Fills the prompt's arguments: `place` from the selection (or "where I am"), `request` from the input box. */
    private fun runPrompt(name: String, arguments: List<String>) {
        val values = arguments.associateWith { arg ->
            when (arg) {
                "place" -> selection()?.let { "the selected area $it" } ?: "where I am (see the context)"
                "request" -> input?.value?.ifBlank { null } ?: "what fits the place"
                else -> ""
            }
        }.filterValues { it.isNotEmpty() }
        input?.value = ""
        resetScroll("transcript")
        chat.runPrompt(name, values)
    }

    /** Sends a ready-made [prompt]; what is typed in the box goes with it. */
    private fun quick(label: String, prompt: String) {
        val typed = input?.value?.trim().orEmpty()
        input?.value = ""
        resetScroll("transcript")
        chat.send(if (typed.isEmpty()) prompt else "$prompt\n\n$typed", if (typed.isEmpty()) label else "$label: $typed")
    }

    private fun sendOrCancel() {
        if (chat.running) return chat.cancel()
        val text = input?.value?.trim().orEmpty()
        if (text.isEmpty()) return
        input?.value = ""
        resetScroll("transcript")
        chat.send(text)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (menu && event.isEscape) {
            menu = false
            return true
        }
        if (event.isConfirmation && focused === input) {
            sendOrCancel()
            return true
        }
        return super.keyPressed(event)
    }

    // ---- Frame ------------------------------------------------------------------------------------------------------

    override fun draw(ui: Ui) {
        rail(ui)
        header(ui)
        transcript(ui)
        ui.button(x0, height - 26, 20, 18, "+", if (menu) Style.PRIMARY else Style.NORMAL, tooltip = "Quick actions and the server's prompts") { menu = !menu }
        if (chat.running) ui.button(width - pad - 60, height - 26, 60, 18, "Cancel", Style.DANGER) { chat.cancel() }
        else ui.button(width - pad - 60, height - 26, 60, 18, "Send", Style.PRIMARY, enabled = !input?.value.isNullOrBlank()) { sendOrCancel() }
    }

    private fun rail(ui: Ui) {
        ui.rect(0, 0, rail, height, Theme.RAIL)
        ui.text("✉ Chats", 12, 12, Theme.ACCENT)
        ui.button(rail - 62, 8, 54, 16, "+ New", Style.PRIMARY) { select(Chats.create()) }
        ui.scroll("chats", 0, 32, rail, height - 32 - 30) { start ->
            var y = start
            for (c in Chats.list) {
                val on = c === chat
                val hover = ui.hovered(0, y, rail, 30)
                if (on) { ui.rect(0, y, rail, 30, Theme.PANEL); ui.rect(0, y, 3, 30, Theme.ACCENT) }
                else if (hover) ui.rect(0, y, rail, 30, Theme.PANEL_HOVER)
                val sure = confirmDelete === c
                val titleWidth = rail - 22 - if (hover || sure) 22 else 0
                if (c.running) ui.text("●", 8, y + 6, Theme.AI)
                ui.text(ui.fit(c.data.title, titleWidth - 4), 18, y + 6, if (on) Theme.TEXT else Theme.MUTED)
                val elsewhere = c.data.server.takeIf { it.isNotEmpty() && it != Chats.server }?.let { " · $it" }.orEmpty()
                ui.text(ui.fit(c.status() ?: "${c.data.backend.label} · ${c.entries.size} lines$elsewhere", rail - 22), 18, y + 17, if (c.running) Theme.AI else Theme.FAINT)
                ui.hit(0, y, rail, 30, null) { select(c) }
                if (hover || sure) ui.button(rail - 24, y + 7, 18, 16, if (sure) "?" else "✕", if (sure) Style.DANGER else Style.GHOST,
                    tooltip = if (sure) "Click again to delete" else "Delete this chat") { delete(c) }
                y += 31
            }
            y - start
        }
        ui.button(8, height - 24, 60, 16, "⌂ Hub", Style.GHOST, tooltip = "Foreman hub: workspaces, agents, overlays, debugging, keys") { minecraft.gui.setScreen(HubScreen()) }
    }

    private fun header(ui: Ui) {
        val switches = 92 + 74 + 16
        ui.title(ui.fit(chat.data.title, ((contentWidth - switches) / 1.5).toInt()), x0, 10)
        ui.switch(width - pad - 74, 10, 74, "Vision", chat.data.allowScreenshots, "Let the AI take screenshots of your view") {
            chat.update { allowScreenshots = !allowScreenshots }
        }
        ui.switch(width - pad - 74 - 12 - 92, 10, 92, "Commands", chat.data.allowCommands, "Let the AI run commands as you (/tp, plugin commands…)") {
            chat.update { allowCommands = !allowCommands }
        }
        var x = x0
        x += ui.segmented(x, 32, AgentBackend.entries.map { it.label }, chat.data.backend.ordinal) { i -> chat.update { backend = AgentBackend.entries[i] } } + 8
        val folders = chat.data.workspaces.size + chat.data.extraFolders.size
        val label = if (folders == 0) "▣ Workspaces" else "▣ Workspaces ($folders)"
        val w = font.width(label) + 14
        ui.button(x, 32, w, 16, label, tooltip = "Workspaces and folders the AI may read and edit (configs, code)" +
            chat.data.folders.joinToString("") { "\n$it" }) { minecraft.gui.setScreen(WorkspacesScreen(this, chat)) }
        x += w + 6
        chat.status()?.let { ui.text(ui.fit("● $it", width - pad - x), width - pad - font.width(ui.fit("● $it", width - pad - x)), 36, Theme.AI) }
    }

    // ---- Transcript -------------------------------------------------------------------------------------------------

    /** An entry laid out: its wrapped lines and height; [code]: the lines of code blocks, drawn on a darker band. */
    private class Block(val entry: Entry, val lines: List<FormattedCharSequence>, val height: Int, val code: Set<Int> = emptySet())

    private var blocks: List<Block> = emptyList()
    private var laidOut = ""

    private class Wrapped(val lines: List<FormattedCharSequence>, val code: Set<Int>)

    private fun wrap(text: String, w: Int, markdown: Boolean = false): Wrapped {
        if (!markdown) return Wrapped(text.trimEnd().split('\n').flatMap { para ->
            font.split(Component.literal(para), w).ifEmpty { listOf(FormattedCharSequence.EMPTY) }
        }, emptySet())
        // The markdown chats answer in: fenced code blocks are kept as they are (indented, on a band), tables laid out
        // in columns, the rest styled.
        val lines = mutableListOf<FormattedCharSequence>()
        val code = HashSet<Int>()
        val table = mutableListOf<String>()
        fun add(component: Component, width: Int) = lines.addAll(font.split(component, width).ifEmpty { listOf(FormattedCharSequence.EMPTY) })
        fun flushTable() {
            if (table.isNotEmpty()) table(table, w).forEach { add(it, w) }
            table.clear()
        }
        var fenced = false
        for (line in text.trimEnd().split('\n')) {
            if (line.trimStart().startsWith("```")) {
                flushTable()
                fenced = !fenced
                continue
            }
            if (fenced) {
                val first = lines.size
                add(Component.literal(" " + line).withColor(CODE), w - 8)
                (first until lines.size).forEach(code::add)
            } else if (line.trimStart().startsWith("|")) table += line
            else {
                flushTable()
                add(markdown(line), w)
            }
        }
        flushTable()
        return Wrapped(lines, code)
    }

    /**
     * A markdown table: its cells padded into columns (the header bold, the `|---|` row left out), or one `a · b · c`
     * line per row when the columns don't fit [w].
     */
    private fun table(rows: List<String>, w: Int): List<Component> {
        val cells = rows.map { row -> row.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() } }
            .filterNot { row -> row.all { it.matches(Regex(":?-{3,}:?")) } }
        val header = rows.size > 1 && rows[1].contains(Regex("-{3,}"))
        fun styled(cell: String, row: Int) = inline(cell).also { if (header && row == 0) it.withStyle(ChatFormatting.BOLD) }
        val columns = cells.maxOf { it.size }
        val widths = (0 until columns).map { c -> cells.withIndex().maxOf { (r, row) -> row.getOrNull(c)?.let { font.width(styled(it, r)) } ?: 0 } }
        val gap = font.width("   ")
        if (widths.sum() + gap * (columns - 1) > w) return cells.mapIndexed { r, row ->
            Component.empty().also { line -> row.forEachIndexed { c, cell -> if (c > 0) line.append(" · "); line.append(styled(cell, r)) } }
        }
        val space = font.width(" ").coerceAtLeast(1)
        return cells.mapIndexed { r, row ->
            Component.empty().also { line ->
                row.forEachIndexed { c, cell ->
                    val text = styled(cell, r)
                    line.append(text)
                    if (c < row.size - 1) line.append(" ".repeat(((widths[c] - font.width(text) + gap) / space).coerceAtLeast(1)))
                }
            }
        }
    }

    /**
     * A line of markdown, styled: # headings, - and * bullets, > quotes, --- rules, **bold**, *italic*, `code` and
     * [links](…) (their text).
     */
    private fun markdown(line: String): Component {
        Regex("^#{1,6} ").find(line)?.let { return inline(line.substring(it.range.last + 1)).withStyle(ChatFormatting.BOLD) }
        if (line.trim().matches(Regex("([-*_])\\1{2,}"))) return Component.literal("─".repeat(24)).withColor(Theme.FAINT and 0xFFFFFF)
        Regex("^\\s*> ?").find(line)?.let { return inline(line.substring(it.range.last + 1)).withStyle(ChatFormatting.ITALIC).withColor(Theme.MUTED and 0xFFFFFF) }
        return inline(line.replaceFirst(Regex("^(\\s*)[-*+] "), "$1• "))
    }

    private fun inline(text: String): MutableComponent {
        val out = Component.empty()
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) out.append(text.substring(last, m.range.first))
            val (bold, code, link, italic) = m.destructured
            when {
                bold.isNotEmpty() -> out.append(inline(bold).withStyle(ChatFormatting.BOLD))
                code.isNotEmpty() -> out.append(Component.literal(code).withColor(Theme.ACCENT and 0xFFFFFF))
                link.isNotEmpty() -> out.append(Component.literal(link).withColor(Theme.ACCENT and 0xFFFFFF))
                else -> out.append(inline(italic).withStyle(ChatFormatting.ITALIC))
            }
            last = m.range.last + 1
        }
        if (last < text.length) out.append(text.substring(last))
        return out
    }

    private fun layout(w: Int) {
        val key = "${Chats.version}:${chat.id}:$w"
        if (key == laidOut) return
        laidOut = key
        blocks = chat.entries.map { e ->
            when (e.kind) {
                EntryKind.USER -> wrap(e.text, w - 40).lines.let { Block(e, it, it.size * 10 + 10) }
                EntryKind.ASSISTANT -> wrap(e.text, w - 16, markdown = true).let { Block(e, it.lines, it.lines.size * 10 + 4, it.code) }
                EntryKind.TOOL -> Block(e, emptyList(), 14)
                EntryKind.INFO -> wrap(e.text, w - 16).lines.let { Block(e, it, it.size * 10) }
                EntryKind.ERROR -> wrap(e.text, w - 24).lines.let { Block(e, it, it.size * 10 + 10) }
            }
        }
    }

    private fun transcript(ui: Ui) {
        val h = bottom - top
        ui.card(x0, top, contentWidth, h, Theme.FIELD)
        val w = contentWidth - 8
        layout(w)
        if (blocks.isEmpty()) {
            val hint = if (Bridge.connected) "Ask about your server or a plugin: a lag, an error in the log, a config change, a build. The AI sees where you are and what you look at, can take screenshots and run commands. + has ready-made prompts."
            else "No Foreman server here: the AI has your client's tools (screenshots, commands, the folders of its workspaces)."
            ui.paragraph(hint, x0 + 24, top + h / 2 - 14, contentWidth - 48, Theme.FAINT)
            return
        }
        val x = x0 + 4
        ui.scroll("transcript", x0 + 1, top + 1, contentWidth - 2, h - 2, stickToBottom = true) { start ->
            var y = start + 6
            for (b in blocks) {
                // Off screen: only counted.
                if (y + b.height >= top && y <= bottom) drawBlock(ui, b, x, y, w, top, bottom)
                y += b.height + 5
            }
            y - start
        }
    }

    private fun drawBlock(ui: Ui, b: Block, x: Int, y: Int, w: Int, top: Int, bottom: Int) {
        val e = b.entry
        when (e.kind) {
            EntryKind.USER -> {
                ui.card(x + 24, y, w - 30, b.height, Theme.PANEL_HOVER, Theme.ACCENT_DARK)
                b.lines.forEachIndexed { i, l -> ui.g.text(font, l, x + 30, y + 5 + i * 10, Theme.TEXT, false) }
            }
            EntryKind.ASSISTANT -> {
                ui.rect(x, y, 2, b.height - 4, Theme.AI)
                b.code.forEach { i -> ui.rect(x + 6, y + i * 10 - 1, w - 14, 10, Theme.RAIL) }
                b.lines.forEachIndexed { i, l -> ui.g.text(font, l, x + 8, y + i * 10, Theme.TEXT, false) }
            }
            EntryKind.TOOL -> {
                val color = if (e.ok) Theme.MUTED else Theme.ERROR
                val name = "⚙ ${e.text}"
                ui.text(name, x + 8, y + 3, color)
                ui.text(ui.fit(e.detail, w - 20 - font.width(name)), x + 14 + font.width(name), y + 3, Theme.FAINT)
                if (y >= top && y + b.height <= bottom) ui.hit(x, y, w, b.height, "${e.text}${if (e.ok) "" else " (failed)"}\n\n${e.detail.take(1200)}")
            }
            EntryKind.INFO -> b.lines.forEachIndexed { i, l -> ui.g.text(font, l, x + 8, y + i * 10, Theme.FAINT, false) }
            EntryKind.ERROR -> {
                ui.card(x, y, w - 6, b.height, 0xFF3A1A1E.toInt(), 0xFF7F1D1D.toInt())
                b.lines.forEachIndexed { i, l -> ui.g.text(font, l, x + 8, y + 5 + i * 10, Theme.ERROR, false) }
            }
        }
    }

    // ---- Quick actions ----------------------------------------------------------------------------------------------

    private class Action(val label: String, val description: String, val enabled: Boolean, val run: () -> Unit)

    private fun actions(): List<Pair<String, List<Action>>> {
        val selection = selection()
        val place = selection?.let { "the selected area $it" } ?: "the area around me (about 24 blocks)"
        val quick = listOf(
            Action("Attach selection", selection ?: "Select an area first (selection tool or WorldEdit)", selection != null) { insert("the selected area $selection") },
            Action("What am I looking at?", "Explain the block or entity in your crosshair", true) {
                quick("What am I looking at?", "Explain what I'm looking at: the block or entity in my crosshair, what it is part of, and anything notable about it.")
            },
            Action("Screenshot and describe", if (chat.data.allowScreenshots) "Describe your view and what looks wrong" else "Needs Vision on", chat.data.allowScreenshots) {
                quick("Screenshot and describe", "Take a screenshot of my view, describe what you see and point out anything that looks wrong or unfinished.")
            },
            Action("Check this area", if (Bridge.connected) "Problems of $place, by severity" else "Needs a Foreman server", Bridge.connected) {
                quick("Check this area", "Run check on $place and list the problems by severity, with coordinates. Don't fix anything yet.")
            },
            Action("Find network lag", if (PacketRecorder.hasData) "In the packet recording: what floods the connection" else "Records 20 s of packets, then looks", true) {
                quick("Find network lag", PacketViewer.ANALYSIS_PROMPT)
            },
            Action("Summarise this chat", "What was done, what is left", chat.entries.isNotEmpty()) {
                quick("Summarise this chat", "Summarise this conversation: what was done (with coordinates and files), and what is left to do.")
            },
        )
        val server = Chats.prompts.map { p ->
            Action(p.name.replace('_', ' ').replaceFirstChar { it.uppercase() }, p.description, true) { runPrompt(p.name, p.arguments.map { it.first }) }
        }
        return listOf("Quick" to quick) + if (Bridge.connected) listOf("Server prompts" to server) else emptyList()
    }

    override fun drawOverlay(ui: Ui) {
        if (!menu) return
        ui.backdrop(width, height) { menu = false }
        val sections = actions()
        val w = 280.coerceAtMost(contentWidth)
        val content = sections.sumOf { 16 + it.second.size.coerceAtLeast(1) * 26 } + 4
        val h = content.coerceAtMost(height - 40 - 40)
        val x = x0
        val y = height - 32 - h
        ui.card(x, y, w, h, Theme.PANEL, Theme.ACCENT_DARK)
        ui.scroll("menu", x + 1, y + 1, w - 2, h - 2) { start ->
            var cy = start + 4
            for ((title, list) in sections) {
                ui.text(title, x + 8, cy + 2, Theme.FAINT)
                cy += 16
                if (list.isEmpty()) { ui.text("None (loading?)", x + 10, cy + 6, Theme.FAINT); cy += 26 }
                for (a in list) {
                    val hover = a.enabled && ui.hovered(x + 2, cy, w - 4, 24)
                    if (hover) ui.rect(x + 2, cy, w - 4, 24, Theme.PANEL_HOVER)
                    ui.text(ui.fit(a.label, w - 20), x + 10, cy + 3, if (a.enabled) Theme.TEXT else Theme.FAINT)
                    ui.text(ui.fit(a.description, w - 20), x + 10, cy + 13, if (a.enabled) Theme.MUTED else Theme.FAINT)
                    if (cy >= y && cy + 24 <= y + h) ui.hit(x + 2, cy, w - 4, 24, a.description.takeIf { font.width(it) > w - 20 }, if (a.enabled) ({ menu = false; a.run() }) else null)
                    cy += 26
                }
            }
            cy - start
        }
    }

    private companion object {
        /** The text of code blocks. */
        const val CODE = 0xC9D1D9
        /** **bold**, `code`, [link](url), *italic* (not inside words, not a bullet's star). */
        val INLINE = Regex("\\*\\*(.+?)\\*\\*|`([^`]+)`|\\[([^\\]]+)]\\([^)]*\\)|(?<![*\\w])\\*(?![\\s*])([^*]+?)(?<!\\s)\\*(?![*\\w])")
    }
}

/** A chat's workspaces (switches) and its own extra folders; the workspaces themselves are managed in the hub. */
class WorkspacesScreen(private val parent: Screen, private val chat: Chat) : KitScreen("Chat workspaces") {
    private lateinit var path: EditBox
    private var message = ""
    private val w get() = 380.coerceAtMost(width - 20)
    private val x get() = (width - w) / 2

    override fun build() {
        path = addRenderableWidget(EditBox(font, x, height - 58, w - 60, 18, Component.literal("Folder")).also {
            it.setMaxLength(1024)
            it.setHint(Component.literal("A folder for this chat only: /home/you/server/plugins/MyPlugin"))
        })
    }

    private fun add() {
        val folder = path.value.trim().removeSuffix("/").ifEmpty { return }
        val p = runCatching { java.nio.file.Path.of(folder) }.getOrNull()
        if (p == null || !p.isAbsolute) {
            message = "Give an absolute path"
            return
        }
        // A sandboxed launcher may not see a host folder: the CLI agents, run on the host, still do.
        message = if (java.nio.file.Files.isDirectory(p)) "" else "Not visible from the game (sandboxed launcher?): Claude Code and opencode still use it"
        chat.update { extraFolders = (extraFolders + folder).distinct() }
        path.value = ""
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.isConfirmation && focused === path) {
            add()
            return true
        }
        return super.keyPressed(event)
    }

    override fun draw(ui: Ui) {
        ui.title("Workspaces", x, 12)
        ui.text(ui.fit("of \"${chat.data.title}\": the AI reads and edits files in their folders", w), x, 30, Theme.MUTED)
        ui.button(x + w - 130, 12, 130, 16, "Manage workspaces…") { minecraft.gui.setScreen(HubScreen(HubScreen.Section.WORKSPACES)) }
        val all = Config.settings.workspaces
        ui.scroll("workspaces", x, 46, w, height - 46 - 70) { top ->
            var y = top
            if (all.isEmpty()) y += ui.paragraph("No workspace yet: create named sets of folders in the hub, then switch them on per chat.", x, y, w) + 8
            for (ws in all) {
                val on = ws.name in chat.data.workspaces
                ui.card(x, y, w - 6, 30, if (on) Theme.PANEL_HOVER else Theme.PANEL, if (on) Theme.ACCENT else Theme.BORDER)
                val label = (if (ws.default) "★ " else "") + ws.name
                ui.switch(x + 8, y + 3, w - 22, label, on) {
                    chat.update { workspaces = if (on) workspaces - ws.name else workspaces + ws.name }
                }
                if (ws.instructions.isNotBlank()) {
                    val cx = x + 14 + font.width(label)
                    ui.chip(cx, y + 5, "instructions", Theme.AI)
                    ui.hit(cx, y + 5, font.width("instructions") + 8, 12, ws.instructions.take(400))
                }
                ui.text(ui.fit(ws.folders.joinToString(" · ").ifEmpty { "no folder" }, w - 22), x + 8, y + 19, Theme.MUTED)
                y += 34
            }
            y += 6
            ui.text("Folders of this chat only", x, y, Theme.TEXT)
            y += 14
            if (chat.data.extraFolders.isEmpty()) { ui.text("None", x, y, Theme.FAINT); y += 12 }
            for (folder in chat.data.extraFolders) {
                ui.text(ui.fit(folder, w - 36), x + 4, y + 4, Theme.TEXT)
                ui.button(x + w - 28, y, 20, 16, "✕", Style.GHOST, tooltip = "Remove") { chat.update { extraFolders = extraFolders - folder } }
                y += 18
            }
            y - top
        }
        ui.button(x + w - 56, height - 58, 56, 18, "Add", Style.PRIMARY) { add() }
        if (message.isNotEmpty()) ui.text(ui.fit(message, w), x, height - 36, Theme.WARN)
        ui.button(width / 2 - 40, height - 24, 80, 18, "Done", Style.PRIMARY) { onClose() }
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }
}
