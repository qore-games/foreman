package games.qore.foreman.client.mcp

import games.qore.foreman.client.agent.PromptContext
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
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.world.phys.Vec3
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
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

    fun init() {
        ClientReceiveMessageEvents.GAME.register { message, overlay -> if (!overlay) record(message.string) }
        ClientReceiveMessageEvents.CHAT.register { message, _, _, _, _ -> record(message.string) }
        ClientTickEvents.END_CLIENT_TICK.register { synchronized(waiting) { repeat(waiting.size) { waiting.removeFirst()() } } }
        ScreenTools.init()
        EntityTools.init()
        ResourceTools.init()
        InventoryTools.init()
        ClientLog.init()

        ClientMcp.register(ClientTool(
            "client_context",
            "Where the player is and looks, the block or entity at the crosshair, their selection, dimension, and the last chat lines.",
            schema(),
        ) { _, _ -> listOf(ClientMcp.text(onClient { PromptContext.build() } + "\n\nRecent chat:\n" + recent(Long.MIN_VALUE).takeLast(15).joinToString("\n"))) })

        ClientMcp.register(ClientTool(
            "client_screenshot",
            "What the player sees right now, as an image (the open screen is closed for the shot, unless `keep_screen`: menus, " +
                "dialogs, inventories). To look at a build: client_teleport to a good spot, client_look at it, then take the screenshot.",
            schema("max_width" to "integer: widest image in pixels (default 1280)", "keep_screen" to "boolean: keep the open screen in the shot (default false)"),
            enabled = { it.allowScreenshots },
        ) { _, args ->
            listOf(ClientMcp.image(screenshot(args["max_width"].int() ?: 1280, keepScreen = args["keep_screen"].bool() == true)), ClientMcp.text("Screenshot of the player's view"))
        })

        ClientMcp.register(ClientTool(
            "client_run_command",
            "Runs a command as the player (without the slash), with their permissions, and returns what the server printed " +
                "in chat in the next `wait_ms` (default 800). For server commands: /tp, /give, /gamemode, plugin commands…",
            schema("command" to "string: the command, e.g. `time set day`", "wait_ms" to "integer: how long to collect the output (default 800, max 5000)"),
            enabled = { it.allowCommands },
        ) { _, args ->
            val command = args["command"].str()?.removePrefix("/")?.takeIf { it.isNotBlank() } ?: throw ToolFailure("Give command")
            listOf(ClientMcp.text(command(command, (args["wait_ms"].int() ?: 800).coerceIn(100, 5000))))
        })

        ClientMcp.register(ClientTool(
            "client_teleport",
            "Teleports the player (a /tp as the player: needs the permission) to x, y, z, optionally facing yaw/pitch or a point to look at.",
            schema("x" to "number", "y" to "number", "z" to "number", "yaw" to "number: optional", "pitch" to "number: optional",
                "look_at" to "array: optional [x, y, z] to face"),
            enabled = { it.allowCommands },
        ) { _, args ->
            val x = args["x"].double() ?: throw ToolFailure("Give x, y, z")
            val y = args["y"].double() ?: throw ToolFailure("Give x, y, z")
            val z = args["z"].double() ?: throw ToolFailure("Give x, y, z")
            val rotation = if (args["yaw"] != null || args["pitch"] != null) " ${args["yaw"].double() ?: 0.0} ${args["pitch"].double() ?: 0.0}" else ""
            val out = command("tp @s $x $y $z$rotation", 600)
            point(args["look_at"])?.let { target -> waitTicks(3); onClient { Minecraft.getInstance().player?.lookAt(EntityAnchorArgument.Anchor.EYES, target) } }
            listOf(ClientMcp.text(out))
        })

        ClientMcp.register(ClientTool(
            "client_look",
            "Turns the player's camera: at a point [x, y, z], or to a yaw and pitch (yaw 0 = south, 90 = west, pitch 90 = down).",
            schema("at" to "array: [x, y, z] to face", "yaw" to "number", "pitch" to "number"),
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
        ) { _, args -> listOf(ClientMcp.text(recent(Long.MIN_VALUE).takeLast(args["lines"].int() ?: 30).joinToString("\n").ifEmpty { "(nothing yet)" })) })

        // ---- Workspace files: the folders the player added to the chat (CLI agents use their own file tools on them) ----
        val files = { s: ToolScope -> s.folders.isNotEmpty() }
        ClientMcp.register(ClientTool(
            "client_list_files", "Files of the chat's workspace folders (recursively), optionally matching a glob like `**/*.yml`.",
            schema("path" to "string: a folder inside the workspace (default all of them)", "glob" to "string: optional"), files,
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
        ) { scope, args ->
            val path = resolve(scope, args["path"].str() ?: throw ToolFailure("Give path"))
            Files.createDirectories(path.parent)
            Files.writeString(path, args["content"].str() ?: throw ToolFailure("Give content"))
            listOf(ClientMcp.text("Wrote $path"))
        })
        ClientMcp.register(ClientTool(
            "client_search_files", "Lines matching a regular expression in the workspace's files (optionally only files matching `glob`).",
            schema("pattern" to "string: a regex", "glob" to "string: optional, e.g. `**/*.yml`"), files,
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

    /** Sends a command as the player and collects the chat lines that came back within [waitMs]. */
    private fun command(command: String, waitMs: Int): String {
        val sent = System.currentTimeMillis()
        onClient { (Minecraft.getInstance().connection ?: throw ToolFailure("Not connected")).sendCommand(command) }
        Thread.sleep(waitMs.toLong())
        val out = recent(sent)
        return "/$command → " + if (out.isEmpty()) "(no chat output)" else out.joinToString("\n")
    }

    /** Runs [block] on the client thread and waits for it (tools are called from the MCP server's threads). */
    internal fun <T> onClient(block: () -> T): T {
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
    internal fun screenshot(maxWidth: Int, region: IntArray? = null, keepScreen: Boolean = false): ByteArray {
        val minecraft = Minecraft.getInstance()
        val screen = if (keepScreen) null else onClient { minecraft.gui.screen().also { if (it != null) minecraft.gui.setScreen(null) } }
        try {
            waitTicks(3)
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
                            val file = Files.createTempFile("foreman-shot", ".png")
                            val image = region?.let { r -> shot.use { crop(it, r, guiScale, maxWidth) } } ?: shot
                            image.use { it.writeToFile(file) }
                            future.complete(Files.readAllBytes(file))
                            Files.deleteIfExists(file)
                        } catch (e: Exception) {
                            future.completeExceptionally(e)
                        }
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            return future.get(10, TimeUnit.SECONDS)
        } finally {
            if (screen != null) minecraft.execute { if (minecraft.gui.screen() == null) minecraft.gui.setScreen(screen) }
        }
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
