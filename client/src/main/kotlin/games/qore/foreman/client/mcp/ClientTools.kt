package games.qore.foreman.client.mcp

import games.qore.foreman.client.agent.PromptContext
import games.qore.foreman.client.lean.Lean
import games.qore.foreman.client.move.MoveTools
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.Images
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import games.qore.foreman.client.util.get
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginConnectionEvents
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.DeathScreen
import net.minecraft.client.gui.screens.DisconnectedScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.ServerList
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.network.chat.Component
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.world.phys.Vec3
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo

/** The client MCP server's tools: the player's view, commands, movement, context, and the chat's workspace files. */
object ClientTools {
    /** Recent chat and system messages, to return what a command printed. */
    private val log = ArrayDeque<Pair<Long, String>>()
    private val waiting = ArrayDeque<() -> Unit>()
    /** The address of the last server the client logged in to (or tried to), for client_connect once it's left. */
    @Volatile
    private var lastServer: String? = null

    fun init() {
        // what the server prints, not the mod's own lines (an agent's command output stays the server's answer)
        ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay && !Chat.own(message)) record(message.string) }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> record(message.string) }
        // each login started, joined or not (a login the authentication servers refused is retried too)
        ClientLoginConnectionEvents.INIT.register { _, minecraft -> minecraft.currentServer?.ip?.let { lastServer = it } }
        ClientTickEvents.END_CLIENT_TICK.register { synchronized(waiting) { repeat(waiting.size) { waiting.removeFirst()() } } }
        ScreenTools.init()
        InputTools.init()
        MoveTools.init()
        TraceTools.init()
        PerfTools.init()
        MetricTools.init()
        EntityTools.init()
        ResourceTools.init()
        InventoryTools.init()
        ClientLog.init()
        TestTools.init()
        RecordTools.init()
        ClientLock.init()

        ClientMcp.register(ClientTool(
            "client_context",
            "Where the player is and looks, the block or entity at the crosshair, their selection, dimension, and the last chat lines.",
            schema(),
            acts = { false },
            group = "Player",
        ) { _, _ -> listOf(ClientMcp.text(onClient { PromptContext.build() } + "\n\nRecent chat:\n" + recent(Long.MIN_VALUE).takeLast(15).joinToString("\n"))) })

        ClientMcp.register(ClientTool(
            "client_screenshot",
            "What the player sees right now, as an image (the open screen is closed for the shot, unless `keep_screen`: menus, " +
                "dialogs, inventories). To look at a build: client_teleport to a good spot, client_look at it, then take the screenshot.",
            schema("max_width" to "integer: widest image in pixels (default 1280)", "keep_screen" to "boolean: keep the open screen in the shot (default false)",
                "hide_hud" to "boolean: without the HUD and the hand, as F1 (default false)"),
            enabled = { it.allowScreenshots },
            acts = { it["keep_screen"].bool() != true },
            group = "Player",
        ) { _, args ->
            listOf(ClientMcp.image(screenshot(args["max_width"].int() ?: 1280, keepScreen = args["keep_screen"].bool() == true, hideHud = args["hide_hud"].bool() == true)),
                ClientMcp.text("Screenshot of the player's view"))
        })

        ClientMcp.register(ClientTool(
            "client_run_command",
            "Runs a command as the player (without the slash), with their permissions, and returns what the server printed " +
                "in chat in the next `wait_ms` (default 800). For server commands: /tp, /give, /gamemode, plugin commands…",
            schema("command" to "string: the command, e.g. `time set day`", "wait_ms" to "integer: how long to collect the output (default 800, max 5000)"),
            enabled = { it.allowCommands },
            group = "Player",
        ) { _, args ->
            val command = args["command"].str()?.removePrefix("/")?.takeIf { it.isNotBlank() } ?: throw ToolFailure("Give command")
            listOf(ClientMcp.text(command(command, (args["wait_ms"].int() ?: 800).coerceIn(100, 5000))))
        })

        ClientMcp.register(ClientTool(
            "client_connect",
            "Joins a server (`address`, default the last one joined), leaving the one the player is on first, and waits " +
                "until they are in game, or says why not (the disconnect screen). `disconnect`: only leaves. Server packs " +
                "are taken as the saved server entry says, accepted when there is none. After a server restart or a kick.",
            schema("address" to "string: host or host:port (default: the last server it logged in to, or tried to)", "disconnect" to "boolean: only leave (default false)",
                "wait_ms" to "integer: how long to wait to be in game (default 90000)"),
            enabled = { it.allowCommands },
            group = "Player",
        ) { _, args -> listOf(ClientMcp.text(connect(args["address"].str(), args["disconnect"].bool() == true, (args["wait_ms"].int() ?: 90_000).coerceIn(1000, 300_000)))) })

        ClientMcp.register(ClientTool(
            "client_teleport",
            "Teleports the player to x, y, z, optionally facing yaw/pitch or a point to look at: vanilla's /minecraft:tp as the " +
                "player (needs the permission), else /tp where the server keeps namespaced commands from players. Says when neither moved them.",
            schema("x" to "number", "y" to "number", "z" to "number", "yaw" to "number: optional", "pitch" to "number: optional",
                "look_at" to "array: optional [x, y, z] to face"),
            enabled = { it.allowCommands },
            group = "Player",
        ) { _, args ->
            val x = args["x"].double() ?: throw ToolFailure("Give x, y, z")
            val y = args["y"].double() ?: throw ToolFailure("Give x, y, z")
            val z = args["z"].double() ?: throw ToolFailure("Give x, y, z")
            val rotation = if (args["yaw"] != null || args["pitch"] != null) " %.2f %.2f".format(Locale.ROOT, args["yaw"].double() ?: 0.0, args["pitch"].double() ?: 0.0) else ""
            val out = teleport(Vec3(x, y, z), rotation)
            point(args["look_at"])?.let { target -> waitTicks(3); onClient { Minecraft.getInstance().player?.lookAt(EntityAnchorArgument.Anchor.EYES, target) } }
            listOf(ClientMcp.text(out))
        })

        ClientMcp.register(ClientTool(
            "client_look",
            "Turns the player's camera: at a point [x, y, z], or to a yaw and pitch (yaw 0 = south, 90 = west, pitch 90 = down).",
            schema("at" to "array: [x, y, z] to face", "yaw" to "number", "pitch" to "number"),
            group = "Player",
        ) { _, args ->
            onClient {
                val player = Minecraft.getInstance().player ?: throw ToolFailure("Not in a world")
                val at = point(args["at"])
                if (at != null) player.lookAt(EntityAnchorArgument.Anchor.EYES, at)
                else {
                    args["yaw"].double()?.let { player.yRot = it.toFloat(); player.yHeadRot = it.toFloat() }
                    args["pitch"].double()?.let { player.xRot = it.toFloat().coerceIn(-90f, 90f) }
                }
            }
            listOf(ClientMcp.text("Looking ${onClient { Minecraft.getInstance().player?.let { "yaw %.0f pitch %.0f".format(it.yRot, it.xRot) } ?: "" }}"))
        })

        ClientMcp.register(ClientTool(
            "client_chat_log",
            "The last chat and system messages the player received (command outputs, plugin messages).",
            schema("lines" to "integer: how many (default 30)"),
            acts = { false },
            group = "Player",
        ) { _, args -> listOf(ClientMcp.text(recent(Long.MIN_VALUE).takeLast(args["lines"].int() ?: 30).joinToString("\n").ifEmpty { "(nothing yet)" })) })

        // ---- Workspace files: the folders the player added to the chat (CLI agents use their own file tools on them) ----
        val files = { s: ToolScope -> s.folders.isNotEmpty() }
        ClientMcp.register(ClientTool(
            "client_list_files", "Files of the chat's workspace folders (recursively), optionally matching a glob like `**/*.yml`.",
            schema("path" to "string: a folder inside the workspace (default all of them)", "glob" to "string: optional"), files,
            acts = { false },
            group = "Files",
        ) { scope, args ->
            val roots = args["path"].str()?.let { listOf(resolve(scope, it)) } ?: scope.folders.map { Path.of(it) }
            val matcher = args["glob"].str()?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
            val out = roots.flatMap { root ->
                Files.walk(root).use { s -> s.filter { it.isRegularFile() && (matcher == null || matcher.matches(it.relativeTo(root))) }.limit(2000).toList() }
            }
            listOf(ClientMcp.text(out.joinToString("\n").ifEmpty { "(no files)" }))
        })
        ClientMcp.register(ClientTool(
            "client_read_file", "Reads a text file of the workspace (lines `offset`.., at most `limit`).",
            schema("path" to "string", "offset" to "integer: first line (default 1)", "limit" to "integer: lines (default 400)"), files,
            acts = { false },
            group = "Files",
        ) { scope, args ->
            val path = resolve(scope, args["path"].str() ?: throw ToolFailure("Give path"))
            val lines = Files.readAllLines(path)
            val from = (args["offset"].int() ?: 1).coerceAtLeast(1)
            val text = lines.drop(from - 1).take(args["limit"].int() ?: 400).mapIndexed { i, l -> "${from + i}\t$l" }.joinToString("\n")
            listOf(ClientMcp.text("$path (${lines.size} lines)\n$text"))
        })
        ClientMcp.register(ClientTool(
            "client_write_file", "Writes a text file of the workspace (creates it, or replaces it whole). Read it first to keep what you don't change.",
            schema("path" to "string", "content" to "string"), files,
            acts = { false },
            group = "Files",
        ) { scope, args ->
            val path = resolve(scope, args["path"].str() ?: throw ToolFailure("Give path"))
            Files.createDirectories(path.parent)
            Files.writeString(path, args["content"].str() ?: throw ToolFailure("Give content"))
            listOf(ClientMcp.text("Wrote $path"))
        })
        ClientMcp.register(ClientTool(
            "client_search_files", "Lines matching a regular expression in the workspace's files (optionally only files matching `glob`).",
            schema("pattern" to "string: a regex", "glob" to "string: optional, e.g. `**/*.yml`"), files,
            acts = { false },
            group = "Files",
        ) { scope, args ->
            val regex = Regex(args["pattern"].str() ?: throw ToolFailure("Give pattern"))
            val matcher = args["glob"].str()?.let { FileSystems.getDefault().getPathMatcher("glob:$it") }
            val hits = mutableListOf<String>()
            for (root in scope.folders.map { Path.of(it) }) Files.walk(root).use { s ->
                s.filter { it.isRegularFile() && (matcher == null || matcher.matches(it.relativeTo(root))) && Files.size(it) < 2_000_000 }.forEach { f ->
                    runCatching { Files.readAllLines(f) }.getOrNull()?.forEachIndexed { i, l -> if (hits.size < 300 && regex.containsMatchIn(l)) hits += "$f:${i + 1}: ${l.take(200)}" }
                }
            }
            listOf(ClientMcp.text(hits.joinToString("\n").ifEmpty { "No match" }))
        })
    }

    internal fun schema(vararg props: Pair<String, String>) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (name, spec) ->
                val type = spec.substringBefore(':').trim()
                put(name, buildJsonObject {
                    // `object|string`: either
                    val types = type.split('|')
                    if (types.size > 1) put("type", JsonArray(types.map { JsonPrimitive(it) })) else put("type", type)
                    if (type == "array") put("items", buildJsonObject { put("type", "number") })
                    spec.substringAfter(':', "").trim().takeIf { it.isNotEmpty() }?.let { put("description", it) }
                })
            }
        })
    }

    /** A path inside one of the scope's folders (relative to the first one, or absolute inside any). */
    private fun resolve(scope: ToolScope, path: String): Path {
        val roots = scope.folders.map { real(Path.of(it).toAbsolutePath().normalize()) }
        if (roots.isEmpty()) throw ToolFailure("This chat has no workspace folder")
        val p = real(Path.of(path).let { if (it.isAbsolute) it else roots.first().resolve(it) }.toAbsolutePath().normalize())
        if (roots.none { p.startsWith(it) }) throw ToolFailure("$path is outside the chat's folders (${scope.folders.joinToString()})")
        if (p.isDirectory()) throw ToolFailure("$path is a folder")
        return p
    }

    /** [p] with the links of its existing part followed, so a link in a folder can't lead out of it. */
    private fun real(p: Path): Path {
        var existing = p
        while (Files.notExists(existing)) existing = existing.parent ?: return p
        return existing.toRealPath().resolve(existing.relativize(p)).normalize()
    }

    private fun point(e: kotlinx.serialization.json.JsonElement?): Vec3? = (e as? kotlinx.serialization.json.JsonArray)?.takeIf { it.size == 3 }
        ?.map { it.double() ?: return null }?.let { Vec3(it[0], it[1], it[2]) }

    private fun record(text: String) = synchronized(log) {
        log += System.currentTimeMillis() to text
        while (log.size > 200) log.removeFirst()
    }

    private fun recent(since: Long) = synchronized(log) { log.filter { it.first >= since }.map { it.second } }

    /**
     * Teleports the player with vanilla's /minecraft:tp: a plugin's /tp (an essentials plugin, a network's own) takes
     * other arguments. Some servers keep namespaced commands from players: when that didn't bring them there, the plain
     * /tp, vanilla's where no plugin took it. What each try got back, and a last line when neither moved them.
     */
    internal fun teleport(target: Vec3, rotation: String = ""): String {
        val coords = "%.4f %.4f %.4f".format(Locale.ROOT, target.x, target.y, target.z) + rotation
        val replies = mutableListOf<String>()
        for (line in listOf("minecraft:tp @s $coords", "tp @s $coords")) {
            replies += command(line, 600)
            if (onClient { Minecraft.getInstance().player?.position()?.let { arrived(it, target) } } == true) return replies.joinToString("\n")
        }
        return replies.joinToString("\n") + "\nThe player isn't there: the server refused both (its /tp may be a plugin's): teleport with its own command (client_run_command)."
    }

    /** At [target] after a teleport: on it across, and at most a fall below it (the reply takes a moment to come). */
    private fun arrived(at: Vec3, target: Vec3) = Math.hypot(at.x - target.x, at.z - target.z) < 1.0 && at.y in target.y - 12..target.y + 1

    /** Sends a command as the player and collects the chat lines that came back within [waitMs]. */
    private fun command(command: String, waitMs: Int): String {
        val sent = System.currentTimeMillis()
        onClient {
            val minecraft = Minecraft.getInstance()
            val connection = minecraft.connection ?: throw ToolFailure("Not connected")
            // a dead player's commands only get "Chat disabled in client options" back
            if (minecraft.gui.screen() is DeathScreen) throw ToolFailure("The player is dead: respawn first (client_click Respawn)")
            connection.sendCommand(command)
        }
        Thread.sleep(waitMs.toLong())
        val out = recent(sent)
        return "/$command → " + if (out.isEmpty()) "(no chat output)" else out.joinToString("\n")
    }

    /** Leaves the server, then joins [address] (or the last one) and waits up to [waitMs] to be in game. */
    private fun connect(address: String?, leaveOnly: Boolean, waitMs: Int): String {
        val minecraft = Minecraft.getInstance()
        val target = address ?: onClient { minecraft.currentServer?.ip } ?: lastServer
        onClient { if (minecraft.level != null) minecraft.disconnectFromWorld(Component.literal("client_connect")) }
        if (leaveOnly) return "Left the server"
        target ?: throw ToolFailure("Give address: no server joined yet")
        if (!ServerAddress.isValidAddress(target)) throw ToolFailure("$target isn't a server address")
        Thread.sleep(300)
        val started = System.currentTimeMillis()
        onClient {
            val saved = ServerList(minecraft).apply { load() }.get(target)
            val server = saved ?: ServerData("Foreman", target, ServerData.Type.OTHER).apply { resourcePackStatus = ServerData.ServerPackStatus.ENABLED }
            ConnectScreen.startConnecting(TitleScreen(), minecraft, ServerAddress.parseString(target), server, false, null)
        }
        while (System.currentTimeMillis() - started < waitMs) {
            Thread.sleep(250)
            val state = onClient {
                val screen = minecraft.gui.screen()
                when {
                    screen is DisconnectedScreen -> "failed"
                    minecraft.player != null && minecraft.level != null && (screen == null || screen.isPauseScreen) -> "in"
                    else -> null
                }
            }
            if (state == "in") return "Joined $target in %.1f s".format(java.util.Locale.ROOT, (System.currentTimeMillis() - started) / 1000.0)
            if (state == "failed") throw ToolFailure("Couldn't join $target: " + onClient { ScreenTools.describe() })
        }
        throw ToolFailure("Not in game on $target after ${waitMs / 1000} s: " + onClient { ScreenTools.describe() })
    }

    /** Runs [block] on the client thread and waits for it (tools are called from the MCP server's threads). */
    internal fun <T> onClient(block: () -> T): T {
        // on it already (a tool calling another's helper): a task queued to it would wait for this one
        if (Minecraft.getInstance().isSameThread) return block()
        val result = CompletableFuture<T>()
        Minecraft.getInstance().execute {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return try {
            result.get(10, TimeUnit.SECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw e.cause ?: e
        }
    }

    private fun waitTicks(n: Int) {
        val done = CompletableFuture<Unit>()
        var left = n
        lateinit var step: () -> Unit
        step = { if (--left <= 0) done.complete(Unit) else synchronized(waiting) { waiting += step } }
        synchronized(waiting) { waiting += step }
        done.get(5, TimeUnit.SECONDS)
    }

    /**
     * The main render target as PNG, the open screen closed for the shot and put back after (unless [keepScreen]).
     * [region] (x, y, width, height in GUI units): only that part, zoomed in to about [maxWidth] pixels.
     */
    internal fun screenshot(maxWidth: Int, region: IntArray? = null, keepScreen: Boolean = false, hideHud: Boolean = false): ByteArray {
        val minecraft = Minecraft.getInstance()
        val screen = if (keepScreen) null else onClient { minecraft.gui.screen().also { if (it != null) minecraft.gui.setScreen(null) } }
        val shown = hideHud && onClient { hideHud(true) }
        try {
            waitTicks(3)
            Lean.awaitPixels()
            val future = CompletableFuture<ByteArray>()
            minecraft.execute {
                val target = minecraft.gameRenderer.mainRenderTarget()
                // The client only downscales by a factor dividing both sides (an odd window height allows none but 1).
                var downscale = if (region != null) 1 else Math.ceilDiv(target.width, maxWidth.coerceAtLeast(320)).coerceAtLeast(1)
                while (downscale > 1 && (target.width % downscale != 0 || target.height % downscale != 0)) downscale--
                val guiScale = minecraft.window.guiScale
                try {
                    Screenshot.takeScreenshot(target, downscale) { shot ->
                        try {
                            val image = region?.let { r -> shot.use { crop(it, r, guiScale, maxWidth) } } ?: shot
                            future.complete(image.use { Images.png(it) })
                        } catch (e: Exception) {
                            future.completeExceptionally(e)
                        }
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            // lean: the copy goes to the GPU with the next frame
            Lean.awaitFrame()
            return future.get(10, TimeUnit.SECONDS)
        } finally {
            if (shown) minecraft.execute { hideHud(false) }
            if (screen != null) minecraft.execute { if (minecraft.gui.screen() == null) minecraft.gui.setScreen(screen) }
        }
    }

    /** Hides the HUD (as F1 does) or shows it again. @return whether it changed */
    internal fun hideHud(hide: Boolean): Boolean {
        val hud = Minecraft.getInstance().gui.hud
        if (hud.isHidden == hide) return false
        hud.toggle()
        return true
    }

    /** A part of [image] (GUI units × [guiScale]), each pixel repeated so it is about [maxWidth] wide (4× at most). */
    private fun crop(image: com.mojang.blaze3d.platform.NativeImage, r: IntArray, guiScale: Int, maxWidth: Int): com.mojang.blaze3d.platform.NativeImage {
        val x0 = (r[0] * guiScale).coerceIn(0, image.width - 1)
        val y0 = (r[1] * guiScale).coerceIn(0, image.height - 1)
        val w = (r[2] * guiScale).coerceIn(1, image.width - x0)
        val h = (r[3] * guiScale).coerceIn(1, image.height - y0)
        val zoom = (maxWidth / w).coerceIn(1, 4)
        val out = com.mojang.blaze3d.platform.NativeImage(w * zoom, h * zoom, false)
        for (y in 0 until h * zoom) for (x in 0 until w * zoom) out.setPixel(x, y, image.getPixel(x0 + x / zoom, y0 + y / zoom))
        return out
    }
}
