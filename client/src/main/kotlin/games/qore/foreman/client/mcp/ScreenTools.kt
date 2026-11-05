package games.qore.foreman.client.mcp

import com.google.gson.JsonParser
import com.mojang.blaze3d.platform.InputConstants
import com.mojang.serialization.JsonOps
import games.qore.foreman.client.lean.Lean
import games.qore.foreman.client.ui.kit.KitScreen
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import games.qore.foreman.client.util.get
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.MouseHandler
import net.minecraft.client.gui.components.AbstractScrollArea
import net.minecraft.client.gui.components.AbstractStringWidget
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Checkbox
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.MultiLineEditBox
import net.minecraft.client.gui.components.events.ContainerEventHandler
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.input.MouseButtonInfo
import net.minecraft.client.gui.screens.dialog.DialogConnectionAccess
import net.minecraft.client.gui.screens.dialog.DialogScreen
import net.minecraft.client.gui.screens.dialog.DialogScreens
import net.minecraft.core.Holder
import net.minecraft.core.RegistryAccess
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.resources.RegistryOps
import net.minecraft.server.ServerLinks
import net.minecraft.server.dialog.Dialog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.util.Optional
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Using the open screen like a player: what is on it (menus/dialogs, inventories, settings), clicks, typing and key
 * bindings, so a session can go through a UI and check it with `client_screenshot keep_screen`.
 */
object ScreenTools {
    private val MOUSE_X = MouseHandler::class.java.getDeclaredField("xpos").apply { isAccessible = true }
    private val MOUSE_Y = MouseHandler::class.java.getDeclaredField("ypos").apply { isAccessible = true }

    fun init() {
        ClientMcp.register(ClientTool(
            "client_screen",
            "The open screen: its class and title, the GUI size, then each widget (buttons, text fields, checkboxes, sliders, " +
                "texts…) with its index, kind, text, position and size in GUI units, and whether it is inactive or focused.",
            ClientTools.schema(),
            acts = { false },
            group = "Screens",
        ) { _, _ -> listOf(ClientMcp.text(describeNow())) })

        ClientMcp.register(ClientTool(
            "client_dialog_preview",
            "Opens a dialog screen on the client alone from its JSON (the format the server sends: a dialog definition " +
                "like `{\"type\": \"minecraft:notice\", \"title\": …}`, or a dialog id from the server's registry), to check " +
                "its layout without a server round trip. Then client_click, client_type and client_screenshot keep_screen " +
                "work on it. Nothing reaches the server: commands, custom actions and disconnects are only reported in chat " +
                "(client_chat_log), a dialog opening another opens it locally. Returns the screen's widgets, and a screenshot.",
            ClientTools.schema("dialog" to "object|string: the dialog's JSON (an object, or as a string), or a dialog id",
                "screenshot" to "boolean: add a screenshot of it (default true)"),
            group = "Screens",
        ) { scope, args ->
            val dialog = args["dialog"] ?: throw ToolFailure("Give dialog")
            val json = when (dialog) {
                is JsonObject -> JsonParser.parseString(dialog.toString())
                else -> dialog.str()?.trim()?.let { if (it.startsWith("{")) JsonParser.parseString(it) else com.google.gson.JsonPrimitive(it) }
                    ?: throw ToolFailure("dialog: a JSON object or a dialog id")
            }
            ClientTools.onClient {
                val minecraft = Minecraft.getInstance()
                val registries = minecraft.connection?.registryAccess() ?: RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY)
                val holder = Dialog.CODEC.parse(RegistryOps.create(JsonOps.INSTANCE, registries), json).getOrThrow { ToolFailure("Bad dialog: $it") }
                val screen = DialogScreens.createFromData(holder.value(), minecraft.gui.screen(), PreviewAccess)
                    ?: throw ToolFailure("This client has no screen for dialogs of type ${BuiltInRegistries.DIALOG_TYPE.getKey(holder.value().codec())}")
                minecraft.gui.setScreen(screen)
            }
            val opened = describeNow()
            val image = if (args["screenshot"].bool() != false && scope.allowScreenshots)
                runCatching { ClientTools.screenshot(1280, keepScreen = true) }.getOrNull() else null
            listOfNotNull(ClientMcp.text("Preview (local only):\n$opened"), image?.let { ClientMcp.image(it) })
        })

        ClientMcp.register(ClientTool(
            "client_click",
            "Clicks in the open screen like the mouse: on the widget whose text contains `label` (exact texts and controls " +
                "first; `index` picks among several matches), or at `x`, `y` in GUI units (client_screen gives positions; a clickable part of a text is inside its " +
                "widget). Then waits `wait_ms` (default 400) for the server's answer.",
            ClientTools.schema("label" to "string: part of the widget's text (case-insensitive)", "index" to "integer: which match (default 0)",
                "x" to "number: GUI x", "y" to "number: GUI y", "button" to "integer: 0 left (default), 1 right", "wait_ms" to "integer: default 400"),
            enabled = { it.allowCommands },
            group = "Screens",
        ) { _, args ->
            val label = args["label"].str()
            if (label != null) Lean.awaitFrame()
            val done = ClientTools.onClient {
                val screen = screen()
                val (x, y, what) = if (label != null) {
                    // Exact texts first, then controls before texts that merely mention the label
                    val matches = widgets(screen).filter { text(it).contains(label, ignoreCase = true) }
                        .sortedWith(compareBy({ !text(it).equals(label, ignoreCase = true) && !it.message.string.equals(label, ignoreCase = true) }, { it is AbstractStringWidget }))
                    val widget = matches.getOrNull(args["index"].int() ?: 0)
                        ?: throw ToolFailure("No widget with \"$label\" (${matches.size} matches)\n" + describe())
                    reveal(screen, widget)
                    val z = zoom(screen)
                    Triple((widget.x + widget.width / 2.0) * z, (widget.y + widget.height / 2.0) * z, "${kind(widget)} \"${text(widget)}\"")
                } else {
                    val x = args["x"].double() ?: throw ToolFailure("Give label, or x and y")
                    val y = args["y"].double() ?: throw ToolFailure("Give label, or x and y")
                    Triple(x, y, "${x.toInt()},${y.toInt()}")
                }
                // Minecraft's button numbers: SDL's since 26.3 (left 1, right 3), GLFW's before
                val button = if (args["button"].int() == 1) InputConstants.MOUSE_BUTTON_RIGHT else InputConstants.MOUSE_BUTTON_LEFT
                val event = MouseButtonEvent(x, y, MouseButtonInfo(button, 0))
                val handled = screen.mouseClicked(event, false)
                screen.mouseReleased(event)
                "Clicked $what" + if (handled) "" else " (nothing there took the click)"
            }
            Thread.sleep((args["wait_ms"].int() ?: 400).coerceIn(0, 5000).toLong())
            listOf(ClientMcp.text(done + "\n\n" + describeNow()))
        })

        ClientMcp.register(ClientTool(
            "client_hover",
            "Moves the mouse over `x`, `y` in GUI units without clicking, like the player pointing at something (hover looks, " +
                "tooltips), and keeps it there until the player moves the real mouse. Then client_screenshot keep_screen shows it.",
            ClientTools.schema("x" to "number: GUI x", "y" to "number: GUI y", "wait_ms" to "integer: default 200"),
            enabled = { it.allowCommands },
            group = "Screens",
        ) { _, args ->
            val x = args["x"].double() ?: throw ToolFailure("Give x and y")
            val y = args["y"].double() ?: throw ToolFailure("Give x and y")
            val done = ClientTools.onClient {
                val minecraft = Minecraft.getInstance()
                val window = minecraft.window
                // The pointer is the handler's window position, scaled to GUI units each frame
                val handler = minecraft.mouseHandler
                MOUSE_X.setDouble(handler, x * window.screenWidth / window.guiScaledWidth)
                MOUSE_Y.setDouble(handler, y * window.screenHeight / window.guiScaledHeight)
                minecraft.framerateLimitTracker.onInputReceived()
                minecraft.gui.screen()?.mouseMoved(x, y)
                "Pointing at ${x.toInt()},${y.toInt()}"
            }
            Thread.sleep((args["wait_ms"].int() ?: 200).coerceIn(0, 5000).toLong())
            // lean: hover looks and the hovered slot come with a frame
            Lean.awaitFrame()
            listOf(ClientMcp.text(done))
        })

        ClientMcp.register(ClientTool(
            "client_drag",
            "Holds a mouse button and moves, like the player dragging. In the open screen: from `from` to `to`, each [x, y] " +
                "in GUI units (scrollbars, sliders, items between slots). In the world (no screen): holds attack (`button` 0) or " +
                "use (1) and turns the camera from `look_from` to `look_to`, each a point [x, y, z] to face or [yaw, pitch] " +
                "(gizmos of the selection, WorldEdit and location tools; mining; drawing a bow). Over `ms` (default 500), then releases.",
            ClientTools.schema(
                "from" to "array: [x, y] in a screen", "to" to "array: [x, y] in a screen",
                "look_from" to "array: in the world, [x, y, z] or [yaw, pitch]", "look_to" to "array: in the world, [x, y, z] or [yaw, pitch]",
                "button" to "integer: 0 left (default), 1 right", "ms" to "integer: default 500",
            ),
            enabled = { it.allowCommands },
            group = "Screens",
        ) { _, args ->
            val ms = (args["ms"].int() ?: 500).coerceIn(50, 10_000)
            val steps = (ms / 50).coerceAtLeast(2)
            val right = args["button"].int() == 1
            val inScreen = ClientTools.onClient { Minecraft.getInstance().gui.screen() != null }
            val done = if (inScreen) dragInScreen(pair(args["from"]), pair(args["to"]), right, steps, ms)
            else dragInWorld(args["look_from"], args["look_to"], right, steps, ms)
            Thread.sleep(300)
            listOf(ClientMcp.text(done + if (inScreen) "\n\n" + describeNow() else ""))
        })

        ClientMcp.register(ClientTool(
            "client_scroll",
            "Scrolls the open screen like the mouse wheel at `x`, `y` (GUI units; default the screen's center): `amount` > 0 " +
                "scrolls up, < 0 down (one notch = 1). client_click by label scrolls its widget into view by itself.",
            ClientTools.schema("amount" to "number: notches, negative = down", "x" to "number", "y" to "number"),
            enabled = { it.allowCommands },
            group = "Screens",
        ) { _, args ->
            val amount = args["amount"].double() ?: throw ToolFailure("Give amount")
            Lean.awaitFrame()
            ClientTools.onClient {
                val screen = screen()
                val window = Minecraft.getInstance().window
                screen.mouseScrolled(args["x"].double() ?: (window.guiScaledWidth / 2.0), args["y"].double() ?: (window.guiScaledHeight / 2.0), 0.0, amount)
            }
            listOf(ClientMcp.text("Scrolled $amount\n\n" + describeNow()))
        })

        ClientMcp.register(ClientTool(
            "client_type",
            "Types `text` into the focused widget of the open screen (client_click a text field first), then presses Enter if `enter`.",
            ClientTools.schema("text" to "string", "enter" to "boolean: default false", "clear" to "boolean: empty the field first (default false)"),
            enabled = { it.allowCommands },
            group = "Screens",
        ) { _, args ->
            val text = args["text"].str() ?: ""
            listOf(ClientMcp.text(ClientTools.onClient {
                val screen = screen()
                val focused = focused(screen) ?: throw ToolFailure("No focused widget: click a text field first")
                if (args["clear"].bool() == true) {
                    when (focused) {
                        is EditBox -> focused.value = ""
                        is MultiLineEditBox -> focused.value = ""
                    }
                }
                text.codePoints().forEach { screen.charTyped(CharacterEvent(it)) }
                if (args["enter"].bool() == true) screen.keyPressed(KeyEvent(InputConstants.KEY_RETURN, 0, 0))
                "Typed into ${kind(focused)}: \"${text(focused)}\""
            }))
        })

        ClientMcp.register(ClientTool(
            "client_key",
            "Presses a key binding like the player, by its id (`key.quickActions` opens the server's quick actions dialog, " +
                "`key.inventory`, `key.chat`, `key.jump`…), or `escape` (what Esc does on the open screen). `hold_ms` holds it " +
                "down that long (walking, sneaking, using an item…), then releases and says how the player moved (at once with " +
                "`wait` false); client_input holds several in sequence. Unknown ids list the known ones.",
            ClientTools.schema("key" to "string: a key binding id or escape", "hold_ms" to "integer: hold it down this long (default a tap: one tick)",
                "wait" to "boolean: with hold_ms, return after the release (default true)", "wait_ms" to "integer: then wait this long (default 400)"),
            enabled = { it.allowCommands },
            group = "Screens",
        ) { _, args ->
            val key = args["key"].str() ?: throw ToolFailure("Give key")
            val holdMs = args["hold_ms"].int()
            val done = if (key.equals("escape", ignoreCase = true)) {
                if (holdMs != null) throw ToolFailure("escape can't be held")
                ClientTools.onClient { screen().keyPressed(KeyEvent(InputConstants.KEY_ESCAPE, 0, 0)) }
                "Pressed Esc"
            } else {
                val (mapping, label) = ClientTools.onClient { InputTools.mapping(key).let { it to "$key (${it.translatedKeyMessage.string})" } }
                val ticks = holdMs?.let { InputTools.ticks(it) } ?: 1
                val released = InputTools.play(listOf(InputTools.Step(setOf(mapping), ticks)))
                when {
                    holdMs == null -> "Pressed $label"
                    args["wait"].bool() == false -> "Holding $label for $ticks ticks"
                    else -> "Held $label. " + InputTools.await(released, ticks)
                }
            }
            if (holdMs == null || args["wait"].bool() != false) Thread.sleep((args["wait_ms"].int() ?: 400).coerceIn(0, 5000).toLong())
            listOf(ClientMcp.text(done + "\n\n" + describeNow()))
        })
    }

    /** What a previewed dialog would ask of the server: reported in chat instead, its screens opened locally. */
    private object PreviewAccess : DialogConnectionAccess {
        override fun disconnect(message: Component) {
            Chat.info("Dialog preview: would disconnect (\"${message.string}\")")
            Minecraft.getInstance().gui.setScreen(null)
        }

        override fun runCommand(command: String, activeScreen: Screen?) {
            Chat.info("Dialog preview: would run /$command")
            Minecraft.getInstance().gui.setScreen(activeScreen)
        }

        override fun openDialog(dialog: Holder<Dialog>, activeScreen: Screen?) {
            val previous = (activeScreen as? DialogScreen<*>)?.previousScreen() ?: activeScreen
            Minecraft.getInstance().gui.setScreen(DialogScreens.createFromData(dialog.value(), previous, this) ?: activeScreen)
        }

        override fun sendCustomAction(id: Identifier, payload: Optional<Tag>) {
            Chat.info("Dialog preview: would send the custom action $id" + payload.map { " $it" }.orElse(""))
        }

        override fun serverLinks(): ServerLinks = Minecraft.getInstance().connection?.serverLinks() ?: ServerLinks.EMPTY
    }

    private fun screen(): Screen = Minecraft.getInstance().gui.screen() ?: throw ToolFailure("No screen open")

    private fun pair(e: JsonElement?): Pair<Double, Double> =
        (e as? JsonArray)?.takeIf { it.size == 2 }?.let { it[0].double()!! to it[1].double()!! }
            ?: throw ToolFailure("In a screen, give from and to as [x, y]")

    /** Presses at [from], moves to [to] in [steps] over [ms] like the mouse, releases there. GUI units. */
    private fun dragInScreen(from: Pair<Double, Double>, to: Pair<Double, Double>, right: Boolean, steps: Int, ms: Int): String {
        val button = if (right) InputConstants.MOUSE_BUTTON_RIGHT else InputConstants.MOUSE_BUTTON_LEFT
        fun event(x: Double, y: Double) = MouseButtonEvent(x, y, MouseButtonInfo(button, 0))
        val pressed = ClientTools.onClient {
            point(from.first, from.second)
            screen().mouseClicked(event(from.first, from.second), false)
        }
        var (x, y) = from
        for (i in 1..steps) {
            Thread.sleep((ms / steps).toLong())
            val nx = from.first + (to.first - from.first) * i / steps
            val ny = from.second + (to.second - from.second) * i / steps
            val (dx, dy) = nx - x to ny - y
            ClientTools.onClient {
                point(nx, ny)
                screen().mouseDragged(event(nx, ny), dx, dy)
            }
            x = nx
            y = ny
        }
        ClientTools.onClient { screen().mouseReleased(event(to.first, to.second)) }
        return "Dragged from ${from.first.toInt()},${from.second.toInt()} to ${to.first.toInt()},${to.second.toInt()}" +
            if (pressed) "" else " (nothing there took the press)"
    }

    /** Moves the pointer to [x], [y] (GUI units), for hover looks and the screen's own idea of where the mouse is. */
    private fun point(x: Double, y: Double) {
        val minecraft = Minecraft.getInstance()
        val window = minecraft.window
        MOUSE_X.setDouble(minecraft.mouseHandler, x * window.screenWidth / window.guiScaledWidth)
        MOUSE_Y.setDouble(minecraft.mouseHandler, y * window.screenHeight / window.guiScaledHeight)
        minecraft.gui.screen()?.mouseMoved(x, y)
    }

    /** Holds attack or use and turns the camera from [from] to [to] (a point or [yaw, pitch]) in [steps] over [ms]. */
    private fun dragInWorld(from: JsonElement?, to: JsonElement?, right: Boolean, steps: Int, ms: Int): String {
        val (start, end) = ClientTools.onClient { rotation(from) to rotation(to) }
        val mapping = ClientTools.onClient { Minecraft.getInstance().options.let { if (right) it.keyUse else it.keyAttack } }
        val key = InputConstants.getKey(mapping.saveString())
        ClientTools.onClient {
            turn(start.first, start.second)
            KeyMapping.set(key, true)
            KeyMapping.click(key)
        }
        // Yaw the short way round
        val yaw = ((end.first - start.first + 540) % 360) - 180
        try {
            for (i in 1..steps) {
                Thread.sleep((ms / steps).toLong())
                ClientTools.onClient { turn(start.first + yaw * i / steps, start.second + (end.second - start.second) * i / steps) }
            }
            Thread.sleep(100)
        } finally {
            ClientTools.onClient { KeyMapping.set(key, false) }
        }
        return "Held ${if (right) "use" else "attack"} from yaw %.0f pitch %.0f to yaw %.0f pitch %.0f".format(start.first, start.second, end.first, end.second)
    }

    /** [yaw, pitch] for a [x, y, z] point to face (from the eyes) or a [yaw, pitch] pair. */
    private fun rotation(e: JsonElement?): Pair<Double, Double> {
        val values = (e as? JsonArray)?.map { it.double() ?: throw ToolFailure("Numbers only in look_from / look_to") }
        val player = Minecraft.getInstance().player ?: throw ToolFailure("Not in a world")
        return when (values?.size) {
            2 -> values[0] to values[1]
            3 -> {
                val eye = player.eyePosition
                val (dx, dy, dz) = Triple(values[0] - eye.x, values[1] - eye.y, values[2] - eye.z)
                Math.toDegrees(atan2(-dx, dz)) to -Math.toDegrees(atan2(dy, sqrt(dx * dx + dz * dz)))
            }
            else -> throw ToolFailure("In the world, give look_from and look_to as [x, y, z] or [yaw, pitch]")
        }
    }

    private fun turn(yaw: Double, pitch: Double) {
        val player = Minecraft.getInstance().player ?: return
        player.yRot = yaw.toFloat()
        player.yHeadRot = yaw.toFloat()
        player.xRot = pitch.toFloat().coerceIn(-90f, 90f)
    }

    /** A Foreman screen's own units against GUI units: its widgets' positions are multiplied by it. */
    private fun zoom(screen: Screen) = (screen as? KitScreen)?.zoom?.toDouble() ?: 1.0

    /**
     * [describe] from a tool's thread, after a fresh frame in lean mode: some screens place their widgets while drawing
     * (option lists, for one), so a screen not drawn yet has them all at 0,0.
     */
    private fun describeNow(): String {
        Lean.awaitFrame()
        return ClientTools.onClient { describe() }
    }

    internal fun describe(): String {
        val minecraft = Minecraft.getInstance()
        val screen = minecraft.gui.screen() ?: return "No screen open"
        val window = minecraft.window
        return buildString {
            append("${screen.javaClass.simpleName}: \"${screen.title.string}\" (GUI ${window.guiScaledWidth}x${window.guiScaledHeight}, scale ${window.guiScale})\n")
            val z = zoom(screen)
            widgets(screen).forEachIndexed { i, w ->
                append("$i. ${kind(w)} \"${text(w)}\" at ${(w.x * z).toInt()},${(w.y * z).toInt()} ${(w.width * z).toInt()}x${(w.height * z).toInt()}")
                if (!w.active) append(" inactive")
                if (w.isFocused) append(" focused")
                append('\n')
            }
        }
    }

    /** Scrolls the scroll areas holding [widget] until it is inside them (a dialog's body scrolls). */
    private fun reveal(screen: Screen, widget: AbstractWidget) {
        fun path(listener: GuiEventListener, trail: List<GuiEventListener>): List<GuiEventListener>? {
            if (listener === widget) return trail
            if (listener is ContainerEventHandler) listener.children().forEach { child -> path(child, trail + listener)?.let { return it } }
            return null
        }
        val areas = screen.children().firstNotNullOfOrNull { path(it, emptyList()) }.orEmpty().filterIsInstance<AbstractScrollArea>()
        for (area in areas) {
            val delta = when {
                widget.y < area.y -> widget.y - area.y
                widget.y + widget.height > area.y + area.height -> widget.y + widget.height - (area.y + area.height)
                else -> 0
            }
            if (delta != 0) area.setScrollAmount(area.scrollAmount() + delta)
        }
    }

    /** Every widget of the screen, in containers too (a dialog's body is a scrollable container). */
    private fun widgets(screen: Screen): List<AbstractWidget> {
        val out = mutableListOf<AbstractWidget>()
        fun visit(listener: GuiEventListener) {
            if (listener is AbstractWidget) out += listener
            if (listener is ContainerEventHandler) listener.children().forEach(::visit)
        }
        screen.children().forEach(::visit)
        return out
    }

    private fun focused(screen: Screen): AbstractWidget? = widgets(screen).firstOrNull { it.isFocused && it !is ContainerEventHandler }

    private fun kind(widget: AbstractWidget) = widget.javaClass.simpleName.ifEmpty { widget.javaClass.superclass.simpleName }

    private fun text(widget: AbstractWidget): String = when (widget) {
        is EditBox -> widget.value
        is MultiLineEditBox -> widget.value
        is Checkbox -> (if (widget.selected()) "[x] " else "[ ] ") + widget.message.string
        else -> widget.message.string
    }.replace('\n', ' ')
}
