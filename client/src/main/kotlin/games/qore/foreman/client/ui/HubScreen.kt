package games.qore.foreman.client.ui

import games.qore.foreman.client.agent.Chats
import games.qore.foreman.client.agent.ToolCatalog
import games.qore.foreman.client.config.AgentBackend
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.config.MessageOutput
import games.qore.foreman.client.config.Settings
import games.qore.foreman.client.config.Visibility
import games.qore.foreman.client.config.Workspace
import games.qore.foreman.client.debug.PluginMessageDebug
import games.qore.foreman.client.hud.HudGuides
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.SelectionTool
import games.qore.foreman.client.link.WorldEditSelection
import games.qore.foreman.client.link.WorldEditTool
import games.qore.foreman.client.locations.LocalSets
import games.qore.foreman.client.locations.LocationEditor
import games.qore.foreman.client.locations.LocationStore
import games.qore.foreman.client.mcp.ClientLog
import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.packets.PacketRecorder
import games.qore.foreman.client.packets.PacketReports
import games.qore.foreman.client.reload.PackIssues
import games.qore.foreman.client.reload.ReloadTimer
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.ui.kit.Style
import games.qore.foreman.client.ui.kit.Theme
import games.qore.foreman.client.ui.kit.Ui
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.screens.options.controls.KeyBindsScreen
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Foreman's home (H): a rail of sections grouped under parent labels on the left, the section on the right. Each
 * feature's settings are on its own page; Settings only has what belongs to none. Mod Menu opens it on Settings, with
 * its screen as [parent].
 */
class HubScreen(private var section: Section = Section.HOME, private val parent: Screen? = null) : KitScreen("Foreman") {
    /** The rail's parent labels; null: no heading (Home on top, Keys and Settings at the bottom). */
    enum class Group(val label: String?) { TOP(null), AI("AI"), WORLD("World"), DEBUG("Debug"), BOTTOM(null) }

    enum class Section(val group: Group, val icon: String, val label: String, val subtitle: String) {
        HOME(Group.TOP, "⌂", "Home", "What runs now, and where to go next"),
        CHATS(Group.AI, "✉", "Chats", "Every AI conversation, whatever the server; each runs on its own"),
        WORKSPACES(Group.AI, "▣", "Workspaces", "Named sets of folders chats work in (configs, code); default ones go to new chats"),
        TOOLS(Group.AI, "⚒", "Tools", "The skills and tools the AI can use here, by provider"),
        AGENTS(Group.AI, "⇄", "Agents", "The agent your chats run on, and agents outside the game that use the client's tools"),
        OVERLAYS(Group.WORLD, "◐", "Overlays", "What is drawn in the world: each layer off, nearby or always"),
        LOCATIONS(Group.WORLD, "⌖", "Locations", "What plugins send: show or hide sources and categories, go to them, move them"),
        SELECTION(Group.WORLD, "⬚", "Selection", "A box to work in: two corners and a gizmo, for the AI or your own commands"),
        PACKETS(Group.DEBUG, "⇅", "Packets", "What the server and your client send each other: record, then find what floods the connection"),
        HUD(Group.DEBUG, "▦", "HUD", "Your HUD's texts as the client lays them out: parts, glyphs, offsets, and what moves when they change"),
        MESSAGES(Group.DEBUG, "✎", "Plugin messages", "What plugins send on their channels, both ways"),
        RESOURCES(Group.DEBUG, "↻", "Resource packs", "Faster reloads that keep the game going, how long the last one took, and what it warned about"),
        KEYS(Group.BOTTOM, "⌨", "Keys", "Every key of the mod, and its commands"),
        SETTINGS(Group.BOTTOM, "⚙", "Settings", "Where each setting is, files and links"),
    }

    private val rail = 140
    private val pad = 14
    private val x0 get() = rail + pad
    private val contentWidth get() = width - x0 - pad
    private val y0 = 50

    private var search: EditBox? = null
    private var nameField: EditBox? = null
    private var folderField: EditBox? = null
    private var instructionsBox: MultiLineEditBox? = null
    /** The workspace [instructionsBox] shows. */
    private var instructionsOf: String? = null
    private var selectedWorkspace: String? = Config.settings.workspaces.firstOrNull()?.name
    private val expanded = mutableSetOf<String>()
    private var confirm: Any? = null
    private var message = ""
    /** The key mapping waiting for its new key (Keys: its chip was clicked). */
    private var binding: KeyMapping? = null

    override fun build() {
        search = null; nameField = null; folderField = null; instructionsBox = null; instructionsOf = null
        when (section) {
            Section.LOCATIONS, Section.TOOLS -> search = field(width - pad - 180, 16, 180, "Search…")
            Section.PACKETS -> search = field(width - pad - 180, 16, 180, "Filter: type, entity, field…")
            Section.MESSAGES -> search = field(width - pad - 180, 16, 180, "Filter: channel, text…")
            Section.WORKSPACES -> {
                nameField = field(x0, y0 + 2, 150, "New workspace name")
                folderField = field(x0 + 220, height - pad - 22, contentWidth - 220 - 64, "Folder to add: /home/you/server/plugins/MyPlugin")
                val (by, bh) = instructionsBounds()
                instructionsBox = addRenderableWidget(MultiLineEditBox.builder().setX(x0 + 230).setY(by)
                    .setPlaceholder(Component.literal("Optional: how the AI should work in this workspace (conventions, what to edit, what not to touch)"))
                    .build(font, contentWidth - 240, bh, Component.literal("Instructions")).also { box ->
                        box.setCharacterLimit(4000)
                        box.setValueListener { text -> Config.settings.workspaces.firstOrNull { it.name == instructionsOf }?.let { ws -> if (ws.instructions != text) Config.update { ws.instructions = text } } }
                    })
            }
            Section.AGENTS -> agentFields().forEachIndexed { i, (_, hint, value, set) ->
                setting(x0 + agentFieldX, agentRowY(i), contentWidth - agentFieldX - 10, hint, value, set)
            }
            else -> {}
        }
        if (section == Section.TOOLS && !ToolCatalog.loading) ToolCatalog.refresh()
    }

    private fun field(x: Int, y: Int, w: Int, hint: String) = addRenderableWidget(EditBox(font, x, y, w, 18, Component.literal(hint)).also {
        it.setMaxLength(4096)
        it.setHint(Component.literal(hint))
    })

    /** A text field saved as it is typed. */
    private fun setting(x: Int, y: Int, w: Int, hint: String, value: String, set: Settings.(String) -> Unit) = field(x, y, w, hint).also {
        it.value = value
        it.setResponder { v -> Config.update { set(v) } }
    }

    private fun go(s: Section) {
        section = s
        confirm = null
        binding = null
        message = ""
        rebuildWidgets()
    }

    override fun onClose() {
        minecraft.gui.setScreen(parent)
    }

    override fun removed() {
        if (Bridge.connected) Bridge.subscribe()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        binding?.let { mapping ->
            // Esc cancels, Backspace / Delete unbinds, any other key is the new one.
            when {
                event.isEscape -> {}
                event.key() == InputConstants.KEY_BACKSPACE || event.key() == InputConstants.KEY_DELETE -> rebind(mapping, InputConstants.UNKNOWN)
                else -> rebind(mapping, InputConstants.getKey(event))
            }
            binding = null
            return true
        }
        if (event.isConfirmation) {
            if (focused === nameField) { addWorkspace(); return true }
            if (focused === folderField) { addFolder(); return true }
        }
        return super.keyPressed(event)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val mapping = binding ?: return super.mouseClicked(event, doubleClick)
        rebind(mapping, InputConstants.Type.MOUSE.getOrCreate(event.button()))
        binding = null
        return true
    }

    private fun rebind(mapping: KeyMapping, key: InputConstants.Key) {
        mapping.setKey(key)
        KeyMapping.resetMapping()
        minecraft.options.save()
    }

    // ---- Frame ------------------------------------------------------------------------------------------------------

    override fun draw(ui: Ui) {
        rail(ui)
        ui.title(section.label, x0, 12)
        ui.text(ui.fit(section.subtitle, contentWidth - (if (search != null) 190 else 0)), x0, 32, Theme.MUTED)
        when (section) {
            Section.HOME -> home(ui)
            Section.CHATS -> chats(ui)
            Section.WORKSPACES -> workspaces(ui)
            Section.TOOLS -> tools(ui)
            Section.AGENTS -> agents(ui)
            Section.OVERLAYS -> overlays(ui)
            Section.LOCATIONS -> locations(ui)
            Section.SELECTION -> selection(ui)
            Section.PACKETS -> PacketsPanel.draw(ui, x0, y0, contentWidth, height - y0 - pad, search?.value.orEmpty())
            Section.HUD -> HudPanel.draw(ui, x0, y0, contentWidth, height - y0 - pad)
            Section.MESSAGES -> messages(ui)
            Section.RESOURCES -> resources(ui)
            Section.KEYS -> keys(ui)
            Section.SETTINGS -> settings(ui)
        }
        if (message.isNotEmpty()) ui.text(message, x0, height - 12, Theme.WARN)
    }

    /** The sections under their group's label; a dot on those with something on (a recording, a tool, a log). */
    private fun rail(ui: Ui) {
        ui.rect(0, 0, rail, height, Theme.RAIL)
        ui.image(LOGO, 10, 11, 20, 16, 80, 64)
        ui.text("Foreman", 36, 12, Theme.ACCENT)
        ui.text(if (Bridge.connected) "● server linked" else "○ client only", 36, 24, if (Bridge.connected) Theme.OK else Theme.FAINT)
        val top = 44
        val headings = Group.entries.count { g -> g.label != null && Section.entries.any { it.group == g } }
        // Shorter rows when the screen is too short for them all above "Esc to close"; on the shortest (the default
        // window), smaller headings and no hint.
        fun step(reserve: Int, headH: Int) = (height - top - reserve - headings * headH - 6) / Section.entries.size
        val roomy = step(22, 14) >= 12
        val headH = if (roomy) 14 else 11
        val step = (if (roomy) step(22, 14) else step(4, 11)).coerceIn(10, 20)
        val rowH = step - 2
        var y = top
        var group: Group? = null
        for (s in Section.entries) {
            if (s.group != group) {
                if (group != null) {
                    val label = s.group.label
                    if (label != null) { ui.text(label.uppercase(), 12, y + headH - 9, Theme.FAINT); y += headH } else y += 6
                }
                group = s.group
            }
            val on = s == section
            if (on) { ui.rect(0, y, rail, rowH, Theme.PANEL); ui.rect(0, y, 3, rowH, Theme.ACCENT) }
            else if (ui.hovered(0, y, rail, rowH)) ui.rect(0, y, rail, rowH, Theme.PANEL_HOVER)
            val ix = if (s.group.label != null) 18 else 12
            val badge = when (s) {
                Section.CHATS -> Chats.running.size.takeIf { it > 0 }?.let { "$it" to Theme.AI }
                Section.AGENTS -> if (ClientMcp.externalEnabled) "●" to Theme.OK else null
                Section.LOCATIONS -> if (Tools.active === LocationEditor) "●" to Theme.ACCENT else null
                Section.SELECTION -> if (Tools.active === SelectionTool || Tools.active === WorldEditTool) "●" to Theme.ACCENT else null
                Section.PACKETS -> if (PacketRecorder.recording) "●" to Theme.ERROR else null
                Section.HUD -> if (HudGuides.element != null) "●" to Theme.ACCENT else null
                Section.MESSAGES -> if (Config.settings.pluginMessages.enabled) "●" to Theme.OK else null
                else -> null
            }
            val badgeX = badge?.let { rail - 6 - ui.font.width(it.first) - 8 } ?: rail
            ui.text(s.icon, ix, y + (rowH - 8) / 2, if (on) Theme.ACCENT else Theme.MUTED)
            ui.text(ui.fit(s.label, badgeX - ix - 18), ix + 16, y + (rowH - 8) / 2, if (on) Theme.TEXT else Theme.MUTED)
            badge?.let { (text, color) -> ui.chip(badgeX, y + (rowH - 12) / 2, text, color) }
            ui.hit(0, y, rail, rowH, null) { go(s) }
            y += step
        }
        if (roomy) ui.text("Esc to close", 12, height - 18, Theme.FAINT)
    }

    /** Two columns of cards. */
    private val colW get() = (contentWidth - pad) / 2

    private fun cardTitle(ui: Ui, x: Int, y: Int, w: Int, h: Int, title: String) {
        ui.card(x, y, w, h)
        ui.text(title, x + 10, y + 9, Theme.TEXT)
    }

    /** A tool you turn on in the world: its name, key and what it does, and a button to use it (closes the hub). */
    private fun toolCard(ui: Ui, x: Int, y: Int, w: Int, name: String, key: String, what: String, on: Boolean, toggle: () -> Unit) {
        ui.card(x, y, w, 46, if (on) Theme.PANEL_HOVER else Theme.PANEL, if (on) Theme.ACCENT else Theme.BORDER)
        ui.text(name, x + 10, y + 8, Theme.TEXT)
        if (key.isNotBlank()) ui.chip(x + 14 + ui.font.width(name), y + 6, key, Theme.ACCENT)
        ui.paragraph(what, x + 10, y + 22, w - 100)
        ui.button(x + w - 80, y + 14, 70, 18, if (on) "Turn off" else "Use", if (on) Style.NORMAL else Style.PRIMARY) { toggle(); if (!on) onClose() }
    }

    private fun visibilityControl(ui: Ui, x: Int, y: Int, value: Visibility, set: (Visibility) -> Unit) =
        ui.segmented(x, y, listOf("Off", "Nearby", "Always"), value.ordinal, listOf(Theme.FAINT, Theme.OK, Theme.WARN)) { set(Visibility.entries[it]) }

    // ---- Home -------------------------------------------------------------------------------------------------------

    private fun home(ui: Ui) = ui.scroll("home", x0, y0, contentWidth + 8, height - y0 - 4) { top ->
        val l = x0
        val r = x0 + colW + pad
        var y = top
        // Server
        cardTitle(ui, l, y, colW, 78, "Server")
        if (Bridge.connected) {
            ui.text(ui.fit("Foreman plugin linked" + if (Bridge.relayed) " · through the game" else "", colW - 20), l + 10, y + 26, Theme.OK)
            // A server without the world tools (a production preset): what it offers instead.
            val features = Bridge.hello?.features.orEmpty()
            val detail = if (features.isEmpty() || "world" in features) "World ${Bridge.world} · ${Bridge.areas.size} areas · ${Bridge.points.size} points" else "Offers: " + features.joinToString(", ")
            ui.text(ui.fit(detail, colW - 20), l + 10, y + 40, Theme.MUTED)
        } else {
            ui.text("No Foreman plugin here", l + 10, y + 26, Theme.MUTED)
            ui.paragraph("Packets, the HUD inspector, locations and chats work on any server.", l + 10, y + 40, colW - 20)
        }
        buttons(ui, l, y + 56, Action("AI tools") { go(Section.TOOLS) })
        // Chats
        cardTitle(ui, r, y, colW, 78, "Chats")
        val running = Chats.running
        ui.text("${Chats.list.size} chat(s)" + if (running.isNotEmpty()) " · ${running.size} working" else "", r + 10, y + 26, if (running.isEmpty()) Theme.MUTED else Theme.AI)
        running.take(1).forEach { ui.text(ui.fit("● ${it.data.title} · ${it.status()}", colW - 20), r + 10, y + 40, Theme.AI) }
        buttons(ui, r, y + 56,
            Action("Open chats", Style.PRIMARY) { minecraft.gui.setScreen(ChatScreen()) },
            Action("+ New chat") { minecraft.gui.setScreen(ChatScreen(open = Chats.create())) },
        )

        y += 90
        // Debug
        cardTitle(ui, l, y, colW, 78, "Debug")
        ui.text(ui.fit(when {
            PacketRecorder.recording -> "● Recording packets · ${PacketReports.clock(PacketRecorder.elapsedNanos())}"
            PacketRecorder.hasData -> "Last recording: ${PacketReports.count(PacketRecorder.total)} packets"
            else -> "No packet recording"
        }, colW - 20), l + 10, y + 26, if (PacketRecorder.recording) Theme.ERROR else Theme.MUTED)
        ui.text(ui.fit(if (Config.settings.pluginMessages.enabled) "Logging plugin messages" else "Plugin messages not logged", colW - 20), l + 10, y + 40, Theme.MUTED)
        buttons(ui, l, y + 56,
            if (PacketRecorder.recording) Action("■ Stop", Style.DANGER) { PacketRecorder.stop() }
            else Action("● Record", Style.PRIMARY, "Records the packets both ways (drops the last recording)") { PacketRecorder.start() },
            Action("Packets") { go(Section.PACKETS) },
            Action("HUD") { go(Section.HUD) },
        )
        // World
        cardTitle(ui, r, y, colW, 78, "World")
        val o = Config.settings.overlays
        ui.switch(r + 10, y + 22, colW - 20, "Show overlays", o.enabled) { Config.update { overlays.enabled = !overlays.enabled }; Bridge.subscribe() }
        val selection = SelectionTool.describe() ?: WorldEditSelection.describe()?.let { "WorldEdit $it" }
        ui.text(ui.fit("${LocationStore.totalLocations()} locations · " + (selection?.let { "selection $it" } ?: "no selection"), colW - 20), r + 10, y + 42, Theme.MUTED)
        buttons(ui, r, y + 56, Action("Locations") { go(Section.LOCATIONS) }, Action("Selection") { go(Section.SELECTION) })

        y += 90
        // Keys
        cardTitle(ui, l, y, contentWidth, 46, "Keys")
        var kx = l + 10
        Keys.described.filter { !it.mapping.isUnbound }.sortedBy { it.category.ordinal }.take(6).forEach { b ->
            val cap = b.mapping.translatedKeyMessage.string
            if (cap.isBlank() || kx > l + contentWidth - 100) return@forEach
            kx += ui.chip(kx, y + 26, cap, Theme.ACCENT) + 4
            val label = ui.fit(b.description.substringBefore(":").substringBefore("("), 90)
            ui.text(label, kx, y + 28, Theme.MUTED)
            kx += ui.font.width(label) + 12
        }
        ui.button(x0 + contentWidth - 60, y + 6, 50, 14, "All…", Style.GHOST) { go(Section.KEYS) }
        y + 46 - top
    }

    /** A button of a card's row. */
    private class Action(val label: String, val style: Style = Style.NORMAL, val tooltip: String? = null, val run: () -> Unit)

    /** [actions] side by side at the bottom of a card at [x]: as wide as they need, narrower when the card is. */
    private fun buttons(ui: Ui, x: Int, y: Int, vararg actions: Action) {
        val room = colW - 20 - 4 * (actions.size - 1)
        val widths = actions.map { ui.font.width(it.label) + 16 }
        val scale = (room.toFloat() / widths.sum()).coerceAtMost(1f)
        var bx = x + 10
        actions.forEachIndexed { i, a ->
            val w = (widths[i] * scale).toInt()
            ui.button(bx, y, w, 16, ui.fit(a.label, w - 6), a.style, tooltip = a.tooltip ?: a.label.takeIf { w < widths[i] }, onClick = a.run)
            bx += w + 4
        }
    }

    // ---- AI: chats --------------------------------------------------------------------------------------------------

    private fun chats(ui: Ui) {
        ui.button(width - pad - 100, 14, 100, 18, "+ New chat", Style.PRIMARY) { minecraft.gui.setScreen(ChatScreen(open = Chats.create())) }
        if (Chats.list.isEmpty()) {
            ui.paragraph("No chat yet. Start one: ask about a plugin's config, an error in the log, a packet recording, or a change in the world.", x0, y0, contentWidth)
            return
        }
        ui.scroll("chats", x0, y0, contentWidth, height - y0 - pad) { top ->
            var y = top
            for (c in Chats.list) {
                val h = 40
                val hover = ui.hovered(x0, y, contentWidth, h)
                ui.card(x0, y, contentWidth - 6, h, if (hover) Theme.PANEL_HOVER else Theme.PANEL)
                if (c.running) ui.rect(x0, y, 3, h, Theme.AI)
                ui.text(ui.fit(c.data.title, contentWidth - 200), x0 + 10, y + 8, Theme.TEXT)
                var cx = x0 + 10
                cx += ui.chip(cx, y + 22, c.data.backend.label, Theme.ACCENT) + 4
                if (c.data.server.isNotEmpty()) cx += ui.chip(cx, y + 22, "⌂ ${c.data.server}", if (c.data.server == Chats.server) Theme.MUTED else Theme.WARN) + 4
                c.data.workspaces.forEach { cx += ui.chip(cx, y + 22, "▣ $it") + 4 }
                if (c.data.extraFolders.isNotEmpty()) cx += ui.chip(cx, y + 22, "+${c.data.extraFolders.size} folder(s)") + 4
                ui.text(c.status()?.let { "● $it" } ?: "${c.entries.size} lines", cx + 4, y + 24, if (c.running) Theme.AI else Theme.FAINT)
                ui.hit(x0, y, contentWidth - 150, h, null) { minecraft.gui.setScreen(ChatScreen(open = c)) }
                ui.button(x0 + contentWidth - 140, y + 11, 60, 18, "Open") { minecraft.gui.setScreen(ChatScreen(open = c)) }
                val sure = confirm === c
                ui.button(x0 + contentWidth - 76, y + 11, 64, 18, if (sure) "Delete?" else "Delete", Style.DANGER, tooltip = if (sure) "Click again to delete" else null) {
                    if (sure) { Chats.delete(c); confirm = null } else confirm = c
                }
                y += h + 6
            }
            y - top
        }
    }

    // ---- AI: workspaces ---------------------------------------------------------------------------------------------

    private fun addWorkspace() {
        val name = nameField?.value?.trim().orEmpty()
        if (name.isEmpty()) return
        if (Config.settings.workspaces.any { it.name.equals(name, true) }) { message = "A workspace is already named $name"; return }
        Config.update { workspaces += Workspace(name) }
        selectedWorkspace = name
        nameField?.value = ""
        message = ""
    }

    private fun addFolder() {
        val ws = Config.settings.workspaces.firstOrNull { it.name == selectedWorkspace } ?: return
        val folder = folderField?.value?.trim()?.removeSuffix("/").orEmpty()
        if (folder.isEmpty()) return
        val p = runCatching { java.nio.file.Path.of(folder) }.getOrNull()
        if (p == null || !p.isAbsolute) { message = "Give an absolute path"; return }
        message = if (java.nio.file.Files.isDirectory(p)) "" else "Not visible from the game (sandboxed launcher?): Claude Code and opencode still use it"
        Config.update { ws.folders = (ws.folders + folder).distinct() }
        folderField?.value = ""
    }

    /** Top and height of the instructions box: the bottom of the workspace card. */
    private fun instructionsBounds(): Pair<Int, Int> {
        val bottom = height - pad - 40
        val h = ((bottom - (y0 + 100)) / 2).coerceIn(30, 90)
        return (bottom - h - 6) to h
    }

    private fun workspaces(ui: Ui) {
        ui.button(x0 + 156, y0 + 2, 64, 18, "Create", Style.PRIMARY) { addWorkspace() }
        val listW = 210
        val list = Config.settings.workspaces
        ui.scroll("workspaces", x0, y0 + 28, listW, height - y0 - 28 - pad) { top ->
            var y = top
            if (list.isEmpty()) y += ui.paragraph("No workspace yet: name one above, then add its folders.", x0, y, listW - 10)
            for (ws in list) {
                val on = ws.name == selectedWorkspace
                ui.card(x0, y, listW - 6, 34, if (on) Theme.PANEL_HOVER else Theme.PANEL, if (on) Theme.ACCENT else Theme.BORDER)
                ui.text(ui.fit((if (ws.default) "★ " else "") + ws.name, listW - 20), x0 + 8, y + 7, Theme.TEXT)
                ui.text("${ws.folders.size} folder(s) · ${Chats.list.count { ws.name in it.data.workspaces }} chat(s)", x0 + 8, y + 20, Theme.MUTED)
                ui.hit(x0, y, listW - 6, 34, null) { selectedWorkspace = ws.name; confirm = null }
                y += 38
            }
            y - top
        }
        val dx = x0 + listW + 10
        val dw = contentWidth - listW - 10
        val ws = list.firstOrNull { it.name == selectedWorkspace }
        ui.card(dx, y0 + 28, dw, height - y0 - 28 - pad - 40)
        instructionsBox?.let { box ->
            box.visible = ws != null
            if (ws != null && instructionsOf != ws.name) { instructionsOf = ws.name; box.setValue(ws.instructions) }
        }
        if (ws == null) {
            ui.paragraph("Select or create a workspace. Its folders are given to every chat that uses it; mark it default to give it to new chats.", dx + 10, y0 + 40, dw - 20)
            return
        }
        ui.text(ws.name, dx + 10, y0 + 38, Theme.TEXT)
        ui.switch(dx + 10, y0 + 54, 170, "Default for new chats", ws.default) { Config.update { ws.default = !ws.default } }
        val sure = confirm === ws
        ui.button(dx + dw - 84, y0 + 34, 74, 16, if (sure) "Delete?" else "Delete", Style.DANGER, tooltip = if (sure) "Click again: chats using it keep their other folders" else null) {
            if (sure) {
                Config.update { workspaces.remove(ws) }
                Chats.list.filter { ws.name in it.data.workspaces }.forEach { c -> c.update { workspaces = workspaces - ws.name } }
                selectedWorkspace = Config.settings.workspaces.firstOrNull()?.name
                confirm = null
            } else confirm = ws
        }
        val (by, _) = instructionsBounds()
        var y = y0 + 82
        if (ws.folders.isEmpty()) ui.text("No folder yet: add one below.", dx + 10, y, Theme.FAINT)
        // The folders that fit above the instructions.
        val fits = ((by - 16 - y) / 20).coerceAtLeast(1)
        for ((i, folder) in ws.folders.withIndex()) {
            if (i == fits - 1 && ws.folders.size > fits) {
                ui.text("+${ws.folders.size - i} more: ${ws.folders.drop(i).joinToString(", ") { it.substringAfterLast('/') }}".let { ui.fit(it, dw - 20) }, dx + 10, y + 4, Theme.MUTED)
                break
            }
            ui.text(ui.fit(folder, dw - 50), dx + 10, y + 4, if (java.nio.file.Files.isDirectory(java.nio.file.Path.of(folder))) Theme.TEXT else Theme.WARN)
            ui.button(dx + dw - 30, y, 20, 16, "✕", Style.GHOST, tooltip = "Remove") { Config.update { ws.folders = ws.folders - folder } }
            y += 20
        }
        ui.text("Instructions (optional, added to every chat using ${ws.name})", dx + 10, by - 12, Theme.MUTED)
        ui.text("Add a folder to ${ws.name}:", dx, height - pad - 34, Theme.MUTED)
        ui.button(width - pad - 60, height - pad - 22, 60, 18, "Add", Style.PRIMARY) { addFolder() }
    }

    // ---- AI: tools --------------------------------------------------------------------------------------------------

    private fun tools(ui: Ui) {
        ui.text(ui.fit(when {
            ToolCatalog.loading -> "Loading…"
            ToolCatalog.error != null -> "Server tools unavailable: ${ToolCatalog.error}"
            else -> "${ToolCatalog.entries.size} tools from ${ToolCatalog.providers().size} providers" +
                if (ToolCatalog.skills.isNotEmpty()) " · ${ToolCatalog.skills.size} skills" else ""
        }, contentWidth - 90), x0, y0 + 4, if (ToolCatalog.error != null) Theme.WARN else Theme.MUTED)
        ui.button(width - pad - 70, y0, 70, 16, "Refresh") { ToolCatalog.refresh() }
        val query = search?.value?.trim()?.lowercase().orEmpty()
        ui.scroll("tools", x0, y0 + 22, contentWidth, height - y0 - 22 - pad) { top ->
            var y = top
            // Skills first: what the AI reads when a request matches one (a plugin's commands, the server's workflows).
            val skills = ToolCatalog.skills.filter { query.isEmpty() || it.name.contains(query) || it.description.lowercase().contains(query) || it.about.lowercase().contains(query) }
            if (skills.isNotEmpty()) {
                ui.text("Skills", x0 + 2, y + 4, Theme.AI)
                ui.text("${skills.size}", x0 + 8 + ui.font.width("Skills"), y + 4, Theme.FAINT)
                y += 16
                for (s in skills) {
                    y += toolRow(ui, y, "skill/${s.name}", s.name, s.about to Theme.AI, s.description, listOf(s.description), emptyList())
                }
                y += 6
            }
            for ((provider, list) in ToolCatalog.providers()) {
                val shown = list.filter { query.isEmpty() || it.name.contains(query) || it.description.lowercase().contains(query) || provider.lowercase().contains(query) }
                if (shown.isEmpty()) continue
                ui.text(provider, x0 + 2, y + 4, Theme.ACCENT)
                ui.text("${shown.size}", x0 + 8 + ui.font.width(provider), y + 4, Theme.FAINT)
                y += 16
                for (t in shown) {
                    y += toolRow(ui, y, "${t.provider}/${t.name}", t.name, if (t.modifies) "edits" to Theme.WARN else null,
                        t.description.lineSequence().first(), sentences(t.description), t.arguments)
                }
                y += 6
            }
            y - top
        }
    }

    /** `code` in tool descriptions. */
    private val CODE = 0xFFC9D1D9.toInt()

    /** The Tools page's rows opened by a click (provider/name, skill/name). */
    private val openTools = HashSet<String>()

    /**
     * A row of the Tools page: the name, a chip and the start of [summary]; a click opens what the AI reads about it
     * under it, [paragraphs] then [arguments]. Returns its height.
     */
    private fun toolRow(ui: Ui, y: Int, key: String, name: String, chip: Pair<String, Int>?, summary: String, paragraphs: List<String>, arguments: List<ToolCatalog.Argument>): Int {
        val w = contentWidth - 6
        val open = key in openTools
        ui.rect(x0, y, w, 18, if (open || ui.hovered(x0, y, w, 18)) Theme.PANEL_HOVER else Theme.PANEL)
        ui.text(if (open) "▾" else "▸", x0 + 6, y + 5, Theme.FAINT)
        ui.text(name, x0 + 16, y + 5, Theme.TEXT)
        var bx = x0 + 22 + ui.font.width(name)
        chip?.let { (label, color) -> bx += ui.chip(bx, y + 3, label, color) + 4 }
        if (!open) ui.text(ui.fit(summary, w - (bx - x0) - 10), bx + 4, y + 5, Theme.MUTED)
        ui.hit(x0, y, w, 18, if (open) "Click to close" else "Click to open") { if (!openTools.remove(key)) openTools += key }
        if (!open) return 19

        // Measured first: the panel goes under the text.
        val tx = x0 + 16
        val tw = w - 28
        val nameWidth = (arguments.maxOfOrNull { ui.font.width(it.name) } ?: 0).coerceAtMost(110) + 10
        fun lines(text: String, width: Int) = ui.font.split(Ui.withCode(text, CODE), width).size * 10
        val height = 8 + paragraphs.sumOf { lines(it, tw) + 3 } +
            (if (arguments.isEmpty()) 0 else 6 + 12 + arguments.sumOf { maxOf(10, lines(it.description, tw - nameWidth)) + 3 }) + 4
        ui.rect(x0, y + 18, w, height, Theme.FIELD)
        var ty = y + 26
        paragraphs.forEachIndexed { i, text -> ty += ui.paragraph(text, tx, ty, tw, if (i == 0) Theme.TEXT else Theme.MUTED, code = CODE) + 3 }
        if (arguments.isNotEmpty()) {
            ty += 6
            ui.text("Arguments", tx, ty, Theme.FAINT)
            ty += 12
            for (a in arguments) {
                ui.text(ui.fit(a.name, nameWidth - 10), tx, ty, if (a.required) Theme.ACCENT else Theme.MUTED)
                ty += maxOf(10, ui.paragraph(a.description.ifEmpty { if (a.required) "required" else "optional" }, tx + nameWidth, ty, tw - nameWidth, code = CODE)) + 3
            }
        }
        return 18 + height + 1
    }

    /** A description cut into its sentences, one paragraph each: long ones read better than as one block. */
    private fun sentences(text: String): List<String> =
        text.split(Regex("\n+")).flatMap { it.split(Regex("(?<=[.!?])\\s+(?=[A-Z`(])")) }.map { it.trim() }.filter { it.isNotEmpty() }

    // ---- AI: agents -------------------------------------------------------------------------------------------------

    private data class AgentField(val label: String, val hint: String, val value: String, val set: Settings.(String) -> Unit)

    private val agentFieldX = 110
    private fun agentRowY(i: Int) = y0 + 30 + i * 22

    /** The fields of the backend new chats use, then those of every backend. */
    private fun agentFields(): List<AgentField> {
        val a = Config.settings.agent
        val backend = when (a.backend) {
            AgentBackend.CLAUDE_CODE -> listOf(
                AgentField("Command", "claude (or its full path)", a.claudeCommand) { agent.claudeCommand = it },
                AgentField("Model", "Empty: Claude Code's default", a.claudeModel) { agent.claudeModel = it },
            )
            AgentBackend.OPENCODE -> listOf(
                AgentField("Command", "opencode (or its full path)", a.opencodeCommand) { agent.opencodeCommand = it },
                AgentField("Model", "provider/model, empty: opencode's default", a.opencodeModel) { agent.opencodeModel = it },
            )
            AgentBackend.OPENROUTER -> listOf(
                AgentField("API key", "Your OpenRouter key", a.openRouterKey) { agent.openRouterKey = it },
                AgentField("Model", "e.g. anthropic/claude-sonnet-4.5", a.openRouterModel) { agent.openRouterModel = it },
            )
        }
        return backend + listOf(
            AgentField("Instructions", "Added to every prompt: house rules, language…", a.extraInstructions) { agent.extraInstructions = it },
            AgentField("Server MCP URL", "Empty: the address the Foreman plugin gives", a.mcpUrlOverride) { agent.mcpUrlOverride = it },
        )
    }

    private fun agents(ui: Ui) {
        val fields = agentFields()
        val h1 = agentRowY(fields.size) - y0 + 4
        cardTitle(ui, x0, y0, contentWidth, h1, "Chats run on")
        ui.segmented(x0 + agentFieldX, y0 + 6, AgentBackend.entries.map { it.label }, Config.settings.agent.backend.ordinal) { i ->
            Config.update { agent.backend = AgentBackend.entries[i] }
            rebuildWidgets()
        }
        fields.forEachIndexed { i, f -> ui.text(f.label, x0 + 10, agentRowY(i) + 5, Theme.MUTED) }

        val y = y0 + h1 + 10
        val e = Config.settings.external
        val launch = ClientMcp.Launch
        val half = (contentWidth - pad) / 2
        val enabled = ClientMcp.externalEnabled
        // what -Dforeman.mcp.* set holds for this launch: the switches show it and leave it as it is
        val bySystemProperty = { message = "Set for this launch by the game's -Dforeman.mcp.* system properties" }
        ui.card(x0, y, contentWidth, if (enabled) 72 else 30)
        ui.switch(x0 + 10, y + 7, contentWidth - 20, "Agents outside the game may use the client's tools", enabled,
            "For Claude Code in a terminal, or another agent on this computer: screens, the HUD inspector, packets, entities, " +
                "screenshots, commands. They connect to ${ClientMcp.externalUrl} with a token (written to config/foreman/client-mcp.json, " +
                "readable by you only)") { if (launch.external != null) bySystemProperty() else ClientMcp.setExternal(!e.enabled) }
        if (enabled) {
            ui.switch(x0 + 10, y + 29, half - 20, "They may run commands as you", launch.allow?.contains("commands") ?: e.allowCommands) {
                if (launch.allow != null) bySystemProperty() else Config.update { external.allowCommands = !external.allowCommands }
            }
            ui.switch(x0 + half + pad, y + 29, half - 10, "They may take screenshots", launch.allow?.contains("screenshots") ?: e.allowScreenshots) {
                if (launch.allow != null) bySystemProperty() else Config.update { external.allowScreenshots = !external.allowScreenshots }
            }
            ui.button(x0 + 10, y + 50, 190, 16, "Copy the claude mcp add command", tooltip = ClientMcp.externalCommand()) {
                minecraft.keyboardHandler.setClipboard(ClientMcp.externalCommand())
                message = "Copied: run it once in a terminal, in the folder Claude Code works in"
            }
            ui.text(ui.fit(ClientMcp.externalUrl, contentWidth - 220), x0 + 210, y + 54, Theme.FAINT)
        }
    }

    // ---- World: overlays --------------------------------------------------------------------------------------------

    private fun overlays(ui: Ui) {
        val o = Config.settings.overlays
        val layers: List<Triple<String, String, Pair<Visibility, (Visibility) -> Unit>>> = listOf(
            Triple("Locations", "What plugins send (NPCs, spawns, regions…): sources and categories in Locations", o.locations to { v -> Config.update { overlays.locations = v } }),
            Triple("Areas", "The Foreman plugin's named places, space reserved by builds, roads and yards, zones", o.areas to { v -> Config.update { overlays.areas = v } }),
            Triple("Points", "Spots builds recorded for gameplay plugins: containers, spawns…", o.points to { v -> Config.update { overlays.points = v } }),
            Triple("Check issues", "What the last check found, numbered", o.issues to { v -> Config.update { overlays.issues = v } }),
            Triple("Last AI change", "The box the AI just changed, fading out", o.changes to { v -> Config.update { overlays.changes = v } }),
        )
        var y = y0
        ui.switch(x0, y, 200, "Show overlays", o.enabled) { Config.update { overlays.enabled = !overlays.enabled }; Bridge.subscribe() }
        if (!Bridge.connected) ui.text(ui.fit("Areas, points, issues and changes come from the Foreman plugin", contentWidth - 220), x0 + 220, y + 4, Theme.FAINT)
        y += 26
        for ((name, what, pair) in layers) {
            val (value, set) = pair
            ui.card(x0, y, contentWidth, 30)
            ui.text(name, x0 + 10, y + 6, Theme.TEXT)
            ui.text(ui.fit(what, contentWidth - 200), x0 + 10, y + 18, Theme.MUTED)
            visibilityControl(ui, x0 + contentWidth - 172, y + 7, value) { set(it); Bridge.subscribe() }
            y += 34
        }
        y += 6
        val half = (contentWidth - pad) / 2
        ui.switch(x0, y, half, "Through blocks", o.throughBlocks) { Config.update { overlays.throughBlocks = !overlays.throughBlocks } }
        ui.switch(x0 + half + pad, y, half, "Notice when entering / leaving areas", o.zoneNotices) { Config.update { overlays.zoneNotices = !overlays.zoneNotices } }
        y += 22
        ui.switch(x0, y, half, "Areas I'm in on the HUD", o.hudAreas) { Config.update { overlays.hudAreas = !overlays.hudAreas } }
        y += 26
        fun distances(label: String, values: List<Int>, value: Int, set: (Int) -> Unit) {
            ui.text(label, x0, y + 4, Theme.MUTED)
            ui.segmented(x0 + 150, y, values.map { "$it" }, values.indexOf(value).coerceAtLeast(0)) { set(values[it]) }
            y += 22
        }
        distances("Nearby from (blocks)", listOf(16, 24, 32, 48, 64), o.nearDistance) { v -> Config.update { overlays.nearDistance = v } }
        distances("Stay inside for (blocks)", listOf(3, 6, 10, 16), o.insideBuffer) { v -> Config.update { overlays.insideBuffer = v } }
        distances("Labels within (blocks)", listOf(24, 40, 64, 96), o.labelDistance) { v -> Config.update { overlays.labelDistance = v } }
    }

    // ---- World: locations -------------------------------------------------------------------------------------------

    private fun locations(ui: Ui) {
        val query = search?.value?.trim()?.lowercase().orEmpty()
        val s = Config.settings.locations
        var x = x0
        x += ui.chip(x, y0, "${LocationStore.sources().size} sources") + 4
        x += ui.chip(x, y0, "${LocationStore.totalCategories()} categories") + 4
        x += ui.chip(x, y0, "${LocationStore.totalLocations()} locations") + 4
        LocalSets.active?.let { ui.chip(x, y0, "local set: $it", Theme.ACCENT) }
        ui.button(width - pad - 90, y0 - 2, 90, 16, "Clear all", Style.DANGER, tooltip = "Forget every received location (they come back with the next update)") {
            LocationStore.clear()
            LocalSets.active?.let(LocalSets::use)
        }
        val half = (contentWidth - pad) / 2
        ui.switch(x0, y0 + 18, half, "Through blocks", s.throughBlocks) { Config.update { locations.throughBlocks = !locations.throughBlocks } }
        ui.switch(x0 + half + pad, y0 + 18, half, "Source / category labels", s.showSourceCategoryLabels) { Config.update { locations.showSourceCategoryLabels = !locations.showSourceCategoryLabels } }

        // The editor: select a location in the world and move it with the gizmo.
        val ey = y0 + 42
        val editing = Tools.active === LocationEditor
        ui.card(x0, ey, contentWidth, 44, if (editing) Theme.PANEL_HOVER else Theme.PANEL, if (editing) Theme.ACCENT else Theme.BORDER)
        ui.text("Editor", x0 + 10, ey + 8, Theme.TEXT)
        var kx = x0 + 14 + ui.font.width("Editor")
        Keys.keyOf("location-editor").takeIf { it.isNotEmpty() }?.let { kx += ui.chip(kx, ey + 6, it, Theme.ACCENT) + 4 }
        ui.text(ui.fit("Left click a location, drag the gizmo; K / U / J: to your feet, eyes, block", contentWidth - (kx - x0) - 90), kx + 4, ey + 8, Theme.MUTED)
        ui.button(x0 + contentWidth - 80, ey + 5, 70, 16, if (editing) "Turn off" else "Use", if (editing) Style.NORMAL else Style.PRIMARY) {
            Tools.toggle(LocationEditor); if (!editing) onClose()
        }
        ui.text("Gizmo snap", x0 + 10, ey + 28, Theme.MUTED)
        val snaps = listOf(0.0, 0.0625, 0.25, 0.5, 1.0)
        val sw = ui.segmented(x0 + 74, ey + 24, listOf("off", "1/16", "1/4", "1/2", "1"), snaps.indexOf(s.snap).coerceAtLeast(0)) { i -> Config.update { locations.snap = snaps[i] } }
        ui.text(ui.fit("blocks · Ctrl: free", contentWidth - 84 - sw), x0 + 80 + sw, ey + 28, Theme.FAINT)

        val top0 = ey + 52
        if (LocationStore.sources().isEmpty()) {
            ui.paragraph("Nothing received yet. Plugins send their locations (NPCs, spawns, regions, points of interest…) to players with the permission; /local keeps your own sets on this computer.", x0, top0, contentWidth)
            return
        }
        ui.scroll("locations", x0, top0, contentWidth, height - top0 - pad) { top ->
            var y = top
            for (source in LocationStore.sources().sorted()) {
                val categories = LocationStore.categories(source).sorted()
                val matches = query.isEmpty() || source.lowercase().contains(query) || categories.any { it.lowercase().contains(query) }
                if (!matches) continue
                val open = source in expanded || query.isNotEmpty()
                val visible = LocationStore.isSourceVisible(source)
                ui.card(x0, y, contentWidth - 6, 24, if (ui.hovered(x0, y, contentWidth - 6, 24)) Theme.PANEL_HOVER else Theme.PANEL)
                ui.text(if (open) "▾" else "▸", x0 + 8, y + 8, Theme.MUTED)
                ui.swatch(x0 + 20, y + 7, 10, LocationStore.sourceColor(source))
                ui.text(ui.fit(source, contentWidth - 230), x0 + 36, y + 8, if (visible) Theme.TEXT else Theme.FAINT)
                ui.text("${categories.size} cat · ${LocationStore.count(source)}", x0 + contentWidth - 190, y + 8, Theme.MUTED)
                ui.hit(x0, y, contentWidth - 100, 24, null) { if (!expanded.remove(source)) expanded += source }
                eye(ui, x0 + contentWidth - 96, y + 4, visible) { LocationStore.setSourceVisible(source, !visible) }
                ui.button(x0 + contentWidth - 60, y + 4, 48, 16, "Only", Style.GHOST, tooltip = "Show only this source") {
                    LocationStore.sources().forEach { LocationStore.setSourceVisible(it, it == source) }
                }
                y += 26
                if (open) for (category in categories) {
                    if (query.isNotEmpty() && !source.lowercase().contains(query) && !category.lowercase().contains(query)) continue
                    val cv = LocationStore.isCategoryVisible(source, category)
                    val n = LocationStore.entries(source, category).size
                    ui.rect(x0 + 14, y, contentWidth - 20, 20, if (ui.hovered(x0 + 14, y, contentWidth - 20, 20)) Theme.PANEL_HOVER else 0x40000000)
                    ui.swatch(x0 + 22, y + 6, 8, LocationStore.categoryColor(source, category))
                    ui.text(ui.fit(category, contentWidth - 230), x0 + 36, y + 6, if (cv && visible) Theme.TEXT else Theme.FAINT)
                    ui.text("$n", x0 + contentWidth - 190, y + 6, Theme.MUTED)
                    eye(ui, x0 + contentWidth - 96, y + 2, cv) { LocationStore.setCategoryVisible(source, category, !cv) }
                    ui.button(x0 + contentWidth - 60, y + 2, 48, 16, "Go", Style.GHOST, enabled = n > 0, tooltip = "Teleport to the nearest one (/tp)") { goTo(source, category) }
                    y += 22
                }
            }
            y - top
        }
    }

    private fun eye(ui: Ui, x: Int, y: Int, on: Boolean, toggle: () -> Unit) =
        ui.button(x, y, 32, 16, if (on) "◉" else "○", if (on) Style.NORMAL else Style.GHOST, tooltip = if (on) "Shown: click to hide" else "Hidden: click to show", onClick = toggle)

    private fun goTo(source: String, category: String) {
        val player = minecraft.player ?: return
        val nearest = LocationStore.entries(source, category).minByOrNull { (it.x - player.x) * (it.x - player.x) + (it.y - player.y) * (it.y - player.y) + (it.z - player.z) * (it.z - player.z) } ?: return
        player.connection.sendCommand("tp @s ${nearest.x} ${nearest.y} ${nearest.z}")
        onClose()
    }

    // ---- World: selection -------------------------------------------------------------------------------------------

    private fun selection(ui: Ui) {
        toolCard(ui, x0, y0, contentWidth, "Selection tool", Keys.keyOf("ai-selection-tool"),
            "Left / right click two corners, then drag the gizmo's arrows. Chats get it as the place to work in.", Tools.active === SelectionTool) { Tools.toggle(SelectionTool) }
        var y = y0 + 58
        val fs = SelectionTool.describe()
        val we = WorldEditSelection.describe()
        cardTitle(ui, x0, y, contentWidth, 74, "Current")
        ui.text("Foreman", x0 + 10, y + 26, Theme.MUTED)
        ui.text(ui.fit(fs ?: "none", contentWidth - 150), x0 + 80, y + 26, if (fs != null) Theme.TEXT else Theme.FAINT)
        ui.button(x0 + contentWidth - 60, y + 22, 50, 16, "Clear", Style.GHOST, enabled = fs != null) { SelectionTool.clear() }
        ui.text("WorldEdit", x0 + 10, y + 44, Theme.MUTED)
        ui.text(ui.fit(we ?: "none (shown when the server sends it)", contentWidth - 100), x0 + 80, y + 44, if (we != null) Theme.TEXT else Theme.FAINT)
        y += 84
        ui.button(x0, y, 120, 18, "Ask a chat about it", Style.PRIMARY, enabled = fs != null || we != null) {
            minecraft.gui.setScreen(ChatScreen("the selected area ${fs ?: we}"))
        }
        Keys.keyOf("ai-selection-to-prompt").takeIf { it.isNotEmpty() }?.let { ui.chip(x0 + 126, y + 3, it, Theme.ACCENT) }
        y += 30

        if (!WorldEditSelection.available) {
            ui.text(ui.fit("WorldEdit CUI (or WorldEdit) is installed: it draws the WorldEdit selection.", contentWidth), x0, y, Theme.FAINT)
            return
        }
        toolCard(ui, x0, y, contentWidth, "WorldEdit tool", Keys.keyOf("worldedit-tool"),
            "Any shape: click blocks for //pos1 and //pos2, drag the arrows to move it, the knobs to resize it.", Tools.active === WorldEditTool) { Tools.toggle(WorldEditTool) }
        y += 58
        ui.switch(x0, y, contentWidth, "Show the WorldEdit selection in the world", Config.settings.overlays.worldEdit) {
            Config.update { overlays.worldEdit = !overlays.worldEdit }
        }
    }

    // ---- Debug: plugin messages -------------------------------------------------------------------------------------

    private val clock = SimpleDateFormat("HH:mm:ss.SSS")

    /** The Debug → Plugin messages list: its rows opened by a click, and whether the live list is frozen for reading. */
    private val openMessages = HashSet<Long>()
    private var messagesPaused = false
    private var frozenMessages: List<PluginMessageDebug.Message>? = null

    private fun messages(ui: Ui) {
        val s = Config.settings.pluginMessages
        ui.switch(x0, y0, 140, "Log messages", s.enabled, "Custom payloads both ways, from now on; nothing is kept while it is off") {
            Config.update { pluginMessages.enabled = !pluginMessages.enabled }
        }
        ui.text("Print", x0 + 150, y0 + 4, Theme.MUTED)
        ui.segmented(x0 + 182, y0, MessageOutput.entries.map { it.label }, s.print.ordinal) { i -> Config.update { pluginMessages.print = MessageOutput.entries[i] } }

        val live = PluginMessageDebug.messages()
        // Pause freezes the list so a row can be opened and read without new messages shifting it.
        val all = if (messagesPaused) frozenMessages ?: live else live
        ui.button(width - pad - 60, y0, 60, 16, "Clear", Style.GHOST, enabled = all.isNotEmpty()) {
            PluginMessageDebug.clear(); frozenMessages = null; messagesPaused = false; openMessages.clear()
        }

        val query = search?.value?.trim().orEmpty()
        val shown = all.filter { query.isEmpty() || it.channel.contains(query, true) || it.body.contains(query, true) }
        val top0 = y0 + 24
        if (all.isEmpty() && query.isEmpty()) {
            ui.paragraph(if (s.enabled) "Nothing yet: messages show here as plugins send them."
                else "Turn logging on, then do what sends them. The packet viewer (Packets) also counts plugin channels by size and rate.",
                x0, top0, contentWidth)
            return
        }
        val ins = shown.count { !it.outbound }
        ui.text("${shown.size} messages · $ins in, ${shown.size - ins} out · ${shown.map { it.channel }.distinct().size} channels", x0, top0, Theme.FAINT)
        ui.button(width - pad - 60, top0 - 4, 60, 16, if (messagesPaused) "Resume" else "Pause", if (messagesPaused) Style.PRIMARY else Style.GHOST, enabled = all.isNotEmpty()) {
            messagesPaused = !messagesPaused; frozenMessages = if (messagesPaused) live else null
        }
        val listTop = top0 + 14
        if (shown.isEmpty()) {
            ui.paragraph("No message matches the filter.", x0, listTop, contentWidth)
            return
        }
        ui.scroll("messages", x0, listTop, contentWidth, height - listTop - pad) { top ->
            var y = top
            for (m in shown) y += messageRow(ui, y, m)
            y - top
        }
    }

    /** A message row: time, direction, channel chip and a preview; click opens the decoded text and a hex/ASCII dump. */
    private fun messageRow(ui: Ui, y: Int, m: PluginMessageDebug.Message): Int {
        val w = contentWidth - 6
        val open = m.id in openMessages
        ui.rect(x0, y, w, 18, if (open || ui.hovered(x0, y, w, 18)) Theme.PANEL_HOVER else Theme.PANEL)
        ui.text(if (open) "▾" else "▸", x0 + 5, y + 5, Theme.FAINT)
        ui.text(clock.format(Date(m.at)), x0 + 16, y + 5, Theme.FAINT)
        ui.text(if (m.outbound) "C→S" else "S→C", x0 + 88, y + 5, if (m.outbound) Theme.WARN else Theme.ACCENT)
        var cx = x0 + 114
        cx += ui.chip(cx, y + 3, ui.fit(m.channel, contentWidth / 3), PluginMessageDebug.channelColor(m.channel)) + 6
        if (!open) ui.text(ui.fit((if (m.size >= 0) "${m.size}B " else "") + m.body, (w - (cx - x0) - 6).coerceAtLeast(0)), cx, y + 5, Theme.MUTED)
        ui.hit(x0, y, w, 18, if (open) "Click to close" else "Click to open") { if (!openMessages.remove(m.id)) openMessages += m.id }
        if (!open) return 19

        val tx = x0 + 16
        val tw = w - 28
        val head = "${if (m.outbound) "client → server" else "server → client"}  ·  ${if (m.size >= 0) "${m.size} bytes" else "not decoded"}"
        val hex = m.data?.let { hexDump(it, m.size) } ?: emptyList()
        val textH = if (m.body.isNotBlank()) ui.font.split(Component.literal(m.body), tw).size * 10 + 4 else 0
        val height = 8 + 13 + textH + (if (hex.isNotEmpty()) hex.size * 10 + 2 else 0) + 4
        ui.rect(x0, y + 18, w, height, Theme.FIELD)
        var dy = y + 26
        ui.text(head, tx, dy, Theme.MUTED); dy += 13
        if (m.body.isNotBlank()) dy += ui.paragraph(m.body, tx, dy, tw, Theme.TEXT) + 4
        hex.forEach { ui.text(it, tx, dy, Theme.MUTED); dy += 10 }
        return 18 + height + 1
    }

    /** Lines of `offset  hex  ascii`, 8 bytes each, for the detail panel; a last line notes bytes beyond what's kept. */
    private fun hexDump(data: ByteArray, total: Int): List<String> {
        val out = mutableListOf<String>()
        var i = 0
        while (i < data.size) {
            val end = (i + 8).coerceAtMost(data.size)
            val hex = (i until end).joinToString(" ") { "%02x".format(data[it]) }.padEnd(8 * 3 - 1)
            val ascii = (i until end).joinToString("") { (data[it].toInt() and 0xFF).let { c -> if (c in 32..126) c.toChar().toString() else "." } }
            out += "%04x  %s  %s".format(i, hex, ascii)
            i = end
        }
        if (total > data.size) out += "… ${data.size} of $total bytes shown"
        return out
    }

    // ---- Keys -------------------------------------------------------------------------------------------------------

    private fun keys(ui: Ui) {
        ui.button(width - pad - 120, 14, 120, 18, "All Minecraft keys…", Style.GHOST) { minecraft.gui.setScreen(KeyBindsScreen(this, minecraft.options)) }
        ui.text("Click a key, then press the new key or mouse button: Esc cancels, Backspace unbinds.", x0, y0 - 2, Theme.FAINT)
        ui.scroll("keys", x0, y0 + 12, contentWidth, height - y0 - 12 - pad) { top ->
            var y = top
            for (category in Keys.Category.entries) {
                val bindings = Keys.described.filter { it.category == category }
                if (bindings.isEmpty()) continue
                ui.text(if (category == Keys.Category.GENERAL) "General" else category.label, x0, y + 4, Theme.ACCENT)
                y += 16
                for (b in bindings) {
                    val mapping = b.mapping
                    val waiting = binding === mapping
                    val conflicts = if (mapping.isUnbound) emptyList() else minecraft.options.keyMappings.filter { it !== mapping && it.same(mapping) }
                    val cap = mapping.translatedKeyMessage.string
                    ui.rect(x0, y, contentWidth - 6, 20, if (waiting) Theme.PANEL_HOVER else Theme.PANEL)
                    val label = when {
                        waiting -> "> press a key <"
                        cap.isBlank() || mapping.isUnbound -> "unbound"
                        conflicts.isNotEmpty() -> "⚠ $cap"
                        else -> cap
                    }
                    ui.button(x0 + 4, y + 2, 86, 16, label, if (waiting) Style.PRIMARY else Style.NORMAL,
                        tooltip = if (waiting) "Esc cancels, Backspace unbinds" else "Click to change") { binding = mapping }
                    var right = x0 + contentWidth - 10
                    if (!mapping.isDefault) {
                        right -= 20
                        ui.button(right, y + 2, 18, 16, "↺", Style.GHOST, tooltip = "Back to ${mapping.defaultKey.displayName.string}") { rebind(mapping, mapping.defaultKey) }
                    }
                    if (conflicts.isNotEmpty()) {
                        val names = conflicts.map { Component.translatable(it.name).string }
                        val chip = ui.fit("also ${names.first()}" + if (names.size > 1) " +${names.size - 1}" else "", 140)
                        right -= ui.font.width(chip) + 12
                        ui.chip(right, y + 4, chip, Theme.WARN)
                        ui.hit(right, y + 4, ui.font.width(chip) + 8, 12, "Also bound to:\n" + names.joinToString("\n"))
                    }
                    ui.text(ui.fit(b.description, right - x0 - 104), x0 + 98, y + 6, if (conflicts.isNotEmpty()) Theme.WARN else Theme.TEXT)
                    y += 22
                }
                y += 6
            }
            ui.text("Commands", x0, y + 4, Theme.ACCENT); y += 18
            listOf("/ai" to "the chats", "/ai ask <prompt>" to "a prompt in a new chat", "/ai cancel" to "stop every running chat",
                "/ai tools" to "every AI tool by provider", "/packets" to "the packet viewer (start, stop, export, ask)",
                "/hud" to "the HUD inspector (guides)",
                "/locations" to "location sources and editing", "/local" to "local location sets")
                .forEach { (c, w) -> ui.text(c, x0 + 8, y, Theme.TEXT); ui.text(w, x0 + 130, y, Theme.MUTED); y += 12 }
            y - top
        }
    }

    // ---- Debug: resource packs --------------------------------------------------------------------------------------


    private var resourcesTab = 0
    private val openIssues = mutableSetOf<String>()

    private fun resources(ui: Ui) {
        val r = Config.settings.reload
        val half = (contentWidth - pad) / 2
        var y = y0
        ui.switch(x0, y, half, "Fast reloads", r.fast, "Slow steps in parallel and off the main thread, no fade-out: the same resources, much sooner") {
            Config.update { reload.fast = !reload.fast }
        }
        ui.switch(x0 + half + pad, y, half, "Keep playing meanwhile", r.keepPlaying, "With fast reloads: the game stays on screen and takes input, under a thin progress bar; only the last step freezes it") {
            Config.update { reload.keepPlaying = !reload.keepPlaying }
        }
        y += 22
        ui.switch(x0, y, half, "Report each reload in chat", r.report) { Config.update { reload.report = !reload.report } }
        ui.button(x0 + half + pad, y, 100, 16, "Reload now", tooltip = "Reloads the resource packs, like F3+T") { minecraft.reloadResourcePacks() }
        y += 28

        val issues = PackIssues.groups()
        val count = issues.sumOf { it.lines.size }
        ui.segmented(x0, y, listOf("Performance", if (count == 0) "Issues" else "Issues ($count)"), resourcesTab,
            listOf(Theme.TEXT, if (issues.any { it.error }) Theme.ERROR else if (count > 0) Theme.WARN else Theme.TEXT)) { resourcesTab = it }
        y += 24
        if (resourcesTab == 0) reloadTimes(ui, y) else reloadIssues(ui, y, issues)
    }

    /** The last reload's time and its steps. */
    private fun reloadTimes(ui: Ui, top: Int) {
        var y = top
        val last = ReloadTimer.last
        if (last == null) {
            ui.paragraph("No reload yet: F3+T, the options' packs or a server's pack starts one. Its steps and their times show here.", x0, y, contentWidth)
            return
        }
        ui.text(ui.fit(last.summary(), contentWidth), x0, y, Theme.TEXT)
        ui.text(ui.fit("Main thread: ${"%.2f".format(Locale.ROOT, last.blockingMs / 1000.0)} s applying. A step prepares off the main thread, waits for the others, then applies on it.", contentWidth), x0, y + 12, Theme.MUTED)
        y += 30
        val cols = listOf(contentWidth - 210, contentWidth - 140, contentWidth - 70)
        ui.text("Step, last prepared first", x0, y, Theme.FAINT)
        listOf("prepared at", "preparing", "applying").forEachIndexed { i, h -> ui.text(h, x0 + cols[i], y, Theme.FAINT) }
        y += 12
        ui.scroll("reload-steps", x0, y, contentWidth, height - y - pad) { top ->
            var row = top
            for (step in last.steps.sortedByDescending { it.preparedMs }) {
                ui.text(ui.fit(step.name, cols[0] - 10), x0, row, Theme.TEXT)
                listOf(step.preparedMs, step.prepareMs, step.applyMs).forEachIndexed { i, ms -> ui.text(if (ms < 0) "—" else "$ms ms", x0 + cols[i], row, Theme.MUTED) }
                row += 12
            }
            row - top
        }
    }

    /** The last reload's warnings and errors, a row per kind (click to see its lines), and chats about them. */
    private fun reloadIssues(ui: Ui, top: Int, issues: List<PackIssues.Group>) {
        if (issues.isEmpty()) {
            ui.paragraph(if (ClientLog.lastReload() == 0L) "No reload seen yet: the warnings and errors of the next one show here."
                else "No warning or error in the last reload.", x0, top, contentWidth)
            return
        }
        val count = issues.sumOf { it.lines.size }
        ui.text("$count lines in ${issues.size} kinds, errors first", x0, top + 4, Theme.FAINT)
        ui.button(width - pad - 90, top, 90, 16, "Ask the AI", Style.PRIMARY, tooltip = "A new chat finds the files that cause them in your pack's sources, and fixes them") {
            PackIssues.ask(issues)
        }
        val listTop = top + 22
        ui.scroll("reload-issues", x0, listTop, contentWidth, height - listTop - pad) { scrollTop ->
            var y = scrollTop
            for (group in issues) y += issueRow(ui, y, group)
            y - scrollTop
        }
    }

    private fun issueRow(ui: Ui, y: Int, group: PackIssues.Group): Int {
        val w = contentWidth - 6
        val open = group.key in openIssues
        ui.rect(x0, y, w, 18, if (open || ui.hovered(x0, y, w, 18)) Theme.PANEL_HOVER else Theme.PANEL)
        ui.text(if (open) "▾" else "▸", x0 + 5, y + 5, Theme.FAINT)
        var cx = x0 + 16
        cx += ui.chip(cx, y + 3, "${group.lines.size}", if (group.error) Theme.ERROR else Theme.WARN) + 6
        cx += ui.chip(cx, y + 3, group.logger) + 6
        ui.text(ui.fit(group.kind, (w - (cx - x0) - 6).coerceAtLeast(0)), cx, y + 5, Theme.TEXT)
        ui.hit(x0, y, w, 18, if (open) "Click to close" else "Click to see its lines") { if (!openIssues.remove(group.key)) openIssues += group.key }
        if (!open) return 19

        val tx = x0 + 16
        val tw = w - 28
        // Each line: its first line, then the rest (a model's missing textures…) indented and dimmer.
        val shown = group.lines.take(30).map { line -> line.text.lines().map { it.trim() }.filter { it.isNotEmpty() } }
        fun h(text: String, width: Int) = ui.font.split(Component.literal(text), width).size * 10
        val textH = shown.sumOf { parts -> parts.withIndex().sumOf { (i, part) -> h(part, if (i == 0) tw else tw - 10) } + 2 }
        val more = group.lines.size - shown.size
        val height = 8 + textH + (if (more > 0) 12 else 0) + 24
        ui.rect(x0, y + 18, w, height, Theme.FIELD)
        var dy = y + 26
        for (parts in shown) {
            parts.forEachIndexed { i, part -> dy += if (i == 0) ui.paragraph(part, tx, dy, tw, Theme.TEXT) else ui.paragraph(part, tx + 10, dy, tw - 10, Theme.MUTED) }
            dy += 2
        }
        if (more > 0) { ui.text("… and $more more", tx, dy + 2, Theme.FAINT); dy += 12 }
        ui.button(tx, dy + 4, 110, 16, "Ask about these", Style.GHOST, tooltip = "A new chat about this kind of issue") { PackIssues.ask(listOf(group)) }
        return 18 + height + 1
    }

    // ---- Settings ---------------------------------------------------------------------------------------------------

    private fun settings(ui: Ui) {
        val half = (contentWidth - pad) / 2
        // Every setting is on the page of its feature: this is the map.
        cardTitle(ui, x0, y0, contentWidth, 26 + 4 * 20 + 6, "Where the settings are")
        val pages = listOf(
            Section.AGENTS to "AI backends (Claude Code, opencode, OpenRouter), extra instructions, agents outside the game",
            Section.WORKSPACES to "The folders chats work in, and their instructions",
            Section.OVERLAYS to "Layers drawn in the world, distances, area notices",
            Section.LOCATIONS to "Location labels, through blocks, the editor's gizmo snap",
            Section.PACKETS to "Packets kept per recording, keeping their fields",
            Section.MESSAGES to "Logging plugin messages, printing them",
            Section.RESOURCES to "Fast reloads, playing through them, the chat report",
            Section.KEYS to "Every key of the mod",
        )
        val colX = listOf(x0 + 10, x0 + 10 + half)
        pages.forEachIndexed { i, (s, what) ->
            val bx = colX[i % 2]
            val by = y0 + 24 + (i / 2) * 20
            ui.button(bx, by, 110, 16, "${s.icon} ${s.label}", Style.NORMAL, tooltip = what) { go(s) }
            ui.text(ui.fit(what, half - 130), bx + 116, by + 4, Theme.MUTED)
        }

        val y = y0 + 26 + 4 * 20 + 18
        cardTitle(ui, x0, y, half, 66, "Files")
        ui.paragraph("Settings, chats, local location sets, packet exports and the outside agents' token are in config/foreman.", x0 + 10, y + 22, half - 20)
        ui.button(x0 + 10, y + 46, 100, 16, "Copy its path", Style.GHOST) {
            minecraft.keyboardHandler.setClipboard(Config.dir.toAbsolutePath().toString())
            message = "Copied ${Config.dir.toAbsolutePath()}"
        }

        val version = FabricLoader.getInstance().getModContainer("foreman").map { it.metadata.version.friendlyString }.orElse("?")
        cardTitle(ui, x0 + half + pad, y, half, 66, "Foreman $version")
        val lx = x0 + half + pad + 10
        ui.button(lx, y + 24, 100, 16, "Documentation") { ConfirmLinkScreen.confirmLinkNow(this, URI.create(DOCS)) }
        ui.button(lx + 104, y + 24, 70, 16, "Source", Style.GHOST) { ConfirmLinkScreen.confirmLinkNow(this, URI.create(SOURCE)) }
        ui.button(lx, y + 44, 100, 16, "Report an issue", Style.GHOST) { ConfirmLinkScreen.confirmLinkNow(this, URI.create("$SOURCE/issues")) }
    }

    private companion object {
        const val DOCS = "https://qore-games.github.io/foreman/"
        const val SOURCE = "https://github.com/qore-games/foreman"
        val LOGO: Identifier = Identifier.fromNamespaceAndPath("foreman", "textures/gui/logo.png")
    }
}
