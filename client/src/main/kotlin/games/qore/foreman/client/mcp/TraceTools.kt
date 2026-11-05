package games.qore.foreman.client.mcp

import games.qore.foreman.client.lean.Lean
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.imageio.stream.MemoryCacheImageOutputStream

/**
 * What the client itself sees, tick by tick (`client_trace`) and frame by frame (`client_frames`): for movement and
 * collision bugs (the server knows where the player was a tick ago, from what the client sent) and for what is too
 * quick, or too tied to the camera, for one screenshot.
 */
object TraceTools {
    private const val MAX_TICKS = 400
    private const val MAX_FRAMES = 16

    /** A trace running: the player and the [followed] entities, a row every [every] ticks for [ticks] ticks. */
    private class Trace(val ticks: Int, val every: Int, val followed: List<Entity>, val showRows: Boolean) {
        var tick = 0
        val rows = mutableListOf<String>()
        val done = CompletableFuture<String>()
        val started = System.currentTimeMillis()
        // every tick, for the summary
        var ground = 0
        var changes = 0
        var hcol = 0
        var wasGround: Boolean? = null
        var start: Vec3? = null
        var end: Vec3? = null
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        val feetMin = DoubleArray(followed.size) { Double.MAX_VALUE }
        val feetMax = DoubleArray(followed.size) { -Double.MAX_VALUE }
        val lowestAt = IntArray(followed.size)
    }

    // Client thread only
    private var trace: Trace? = null

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { minecraft -> trace?.let { record(it, minecraft) } }

        ClientMcp.register(ClientTool(
            "client_trace",
            "Records, at the end of each client tick, what the client itself has: the player's position, velocity, " +
                "on-ground and collision flags and pose, and the nearest `limit` entities of `entities` types within " +
                "`radius` (their box's bottom and top, and the player's feet against that top). For movement and " +
                "collision bugs (moving platforms, knockback, rubber-banding): the server only has the player a tick late. " +
                "`steps` (as client_input's) are played meanwhile. Returns at the end a summary line (ticks on the ground " +
                "and in the air, height range, distance, each entity's feet range and when it was lowest), then a row " +
                "every `every` ticks. The server's view of the same ticks: server_trace, alongside in a client_test `parallel`.",
            traceSchema(),
            enabled = { it.allowCommands },
            acts = { it["steps"].arr()?.isNotEmpty() == true },
            group = "Testing",
        ) { _, args -> listOf(ClientMcp.text(trace(args))) })

        ClientMcp.register(ClientTool(
            "client_frames",
            "Screenshots taken one after another, `every_ms` apart, as one contact sheet labelled with their time: for " +
                "what one screenshot misses (a transition, a blast, a fade) and for post effects while the camera turns " +
                "(`turn` degrees of yaw before each frame: does the effect stay in the world or stick to the screen). " +
                "`command` (as the player, without the slash) is sent just before the first frame. The open screen stays.",
            ClientTools.schema(
                "count" to "integer: frames (default 6, max $MAX_FRAMES)",
                "every_ms" to "integer: between frames (default 150, at least 50)",
                "turn" to "number: yaw degrees the camera turns before each frame (default 0)",
                "command" to "string: optional, sent first",
                "max_width" to "integer: the sheet's width in pixels (default 1600)",
            ),
            enabled = { it.allowScreenshots },
            group = "Testing",
        ) { _, args -> listOf(ClientMcp.image(frames(args)), ClientMcp.text("Frames, left to right then down")) })
    }

    private fun trace(args: kotlinx.serialization.json.JsonObject): String {
        val (done, ticks) = begin(args)
        return try {
            done.get(ticks * 50L + 10_000, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Minecraft.getInstance().execute { if (trace?.done === done) trace = null }
            throw ToolFailure("The client stopped ticking")
        }
    }

    /** Starts a trace as client_trace's [args] say, from the next tick (client_replay's too): its answer once it ends, and its ticks. */
    internal fun begin(args: kotlinx.serialization.json.JsonObject, max: Int = MAX_TICKS): Pair<CompletableFuture<String>, Int> {
        val ticks = (args["ticks"].int() ?: 60).coerceIn(1, max)
        val every = (args["every"].int() ?: 1).coerceIn(1, ticks)
        val types = args["entities"].str()?.split(',')?.map { it.trim().lowercase(Locale.ROOT) }?.filter { it.isNotEmpty() }.orEmpty()
        val radius = (args["radius"].double() ?: 8.0).coerceIn(1.0, 64.0)
        val limit = (args["limit"].int() ?: 4).coerceIn(0, 16)
        val steps = args["steps"].arr().orEmpty().map { it.obj() ?: throw ToolFailure("Each step is {keys, ms}") }
        val trace = ClientTools.onClient {
            val minecraft = Minecraft.getInstance()
            val player = minecraft.player ?: throw ToolFailure("Not in a world")
            if (trace != null) throw ToolFailure("A trace is running")
            val followed = if (types.isEmpty()) emptyList() else minecraft.level!!.getEntities(player, player.boundingBox.inflate(radius)) { e ->
                val id = BuiltInRegistries.ENTITY_TYPE.getKey(e.type).path
                types.any { id.contains(it) }
            }.sortedBy { it.distanceToSqr(player) }.take(limit)
            if (steps.isNotEmpty()) {
                InputTools.play(steps.map { step ->
                    val keys = step["keys"].arr().orEmpty().map { InputTools.mapping(it.str() ?: throw ToolFailure("keys: binding ids like key.forward")) }.toSet()
                    InputTools.Step(keys, InputTools.ticks(step["ms"].int() ?: throw ToolFailure("Each step needs ms")))
                })
            }
            Trace(ticks, every, followed, args["rows"].bool() != false).also { trace = it }
        }
        return trace.done to ticks
    }

    private fun record(trace: Trace, minecraft: Minecraft) {
        val player = minecraft.player
        if (player == null) {
            this.trace = null
            trace.done.complete(trace.rows.joinToString("\n") + "\nStopped: the player left the world")
            return
        }
        tally(trace, player)
        if (trace.tick % trace.every == 0) {
            val v = player.deltaMovement
            val row = StringBuilder("%3d  %.3f %.3f %.3f  v %+.3f %+.3f %+.3f  %s%s%s %s".format(
                Locale.ROOT, trace.tick, player.x, player.y, player.z, v.x, v.y, v.z,
                if (player.onGround()) "ground" else "air", if (player.horizontalCollision) " hcol" else "", if (player.verticalCollision) " vcol" else "",
                player.pose.name.lowercase(Locale.ROOT),
            ))
            for (e in trace.followed) {
                if (e.isRemoved) {
                    row.append("  | #${e.id} gone")
                    continue
                }
                val box = e.boundingBox
                row.append("  | #%d %.3f..%.3f feet%+.3f".format(Locale.ROOT, e.id, box.minY, box.maxY, player.boundingBox.minY - box.maxY))
            }
            trace.rows += row.toString()
        }
        if (++trace.tick >= trace.ticks) {
            this.trace = null
            val header = "tick  x y z  velocity  flags pose" + trace.followed.joinToString("") { "  | #${it.id} ${BuiltInRegistries.ENTITY_TYPE.getKey(it.type).path} box bottom..top, the player's feet against its top" }
            trace.done.complete(summary(trace) + if (trace.showRows) "\n" + header + "\n" + trace.rows.joinToString("\n") else "")
        }
    }

    /** The summary's numbers, from every tick (rows may skip some). */
    private fun tally(trace: Trace, player: net.minecraft.client.player.LocalPlayer) {
        val ground = player.onGround()
        if (ground) trace.ground++
        if (trace.wasGround != null && trace.wasGround != ground) trace.changes++
        trace.wasGround = ground
        if (player.horizontalCollision) trace.hcol++
        if (trace.start == null) trace.start = player.position()
        trace.end = player.position()
        trace.minY = minOf(trace.minY, player.y)
        trace.maxY = maxOf(trace.maxY, player.y)
        trace.followed.forEachIndexed { i, e ->
            if (e.isRemoved) return@forEachIndexed
            val feet = player.boundingBox.minY - e.boundingBox.maxY
            if (feet < trace.feetMin[i]) { trace.feetMin[i] = feet; trace.lowestAt[i] = trace.tick }
            trace.feetMax[i] = maxOf(trace.feetMax[i], feet)
        }
    }

    /**
     * The trace in a line: ticks on the ground and in the air, how often it changed, horizontal collisions, the height
     * range, how far the player went, and each followed entity's feet range (the player's feet against its top).
     */
    private fun summary(trace: Trace): String {
        val start = trace.start ?: return "Summary: no tick recorded"
        val end = trace.end ?: start
        val time = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(trace.started), java.time.ZoneId.systemDefault())
        val ticks = trace.tick
        return "Summary: %d ticks from %s: ground %d, air %d (%d changes), hcol %d; y %.3f..%.3f; moved %.2f horizontally to %.2f %.2f %.2f".format(
            Locale.ROOT, ticks, time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss.SSS")), trace.ground, ticks - trace.ground,
            trace.changes, trace.hcol, trace.minY, trace.maxY, Math.hypot(end.x - start.x, end.z - start.z), end.x, end.y, end.z,
        ) + trace.followed.indices.joinToString("") { i ->
            val e = trace.followed[i]
            if (trace.feetMin[i] == Double.MAX_VALUE) "; #${e.id} gone"
            else "; #%d %s feet %+.3f..%+.3f (lowest at tick %d)".format(Locale.ROOT, e.id, BuiltInRegistries.ENTITY_TYPE.getKey(e.type).path,
                trace.feetMin[i], trace.feetMax[i], trace.lowestAt[i])
        }
    }

    private fun frames(args: kotlinx.serialization.json.JsonObject): ByteArray {
        val count = (args["count"].int() ?: 6).coerceIn(1, MAX_FRAMES)
        val every = (args["every_ms"].int() ?: 150).coerceAtLeast(50).toLong()
        val turn = args["turn"].double()?.toFloat() ?: 0f
        val columns = minOf(count, 4)
        val width = (args["max_width"].int() ?: 1600).coerceIn(400, 3200) / columns
        args["command"].str()?.removePrefix("/")?.takeIf { it.isNotBlank() }?.let { command ->
            ClientTools.onClient { (Minecraft.getInstance().connection ?: throw ToolFailure("Not connected")).sendCommand(command) }
        }
        // Lean: the world built once, before the first frame; then a frame drawn for each (waiting for it each time would
        // space them by seconds)
        Lean.awaitPixels()
        val start = System.currentTimeMillis()
        val shots = mutableListOf<Pair<Long, BufferedImage>>()
        repeat(count) { i ->
            if (i > 0) Thread.sleep((start + i * every - System.currentTimeMillis()).coerceAtLeast(0))
            if (turn != 0f && i > 0) ClientTools.onClient { Minecraft.getInstance().player?.let { it.yRot += turn; it.yHeadRot = it.yRot } }
            Lean.awaitFrame()
            val at = System.currentTimeMillis() - start
            shots += at to capture(width)
        }
        val tileWidth = shots.maxOf { it.second.width }
        val tileHeight = shots.maxOf { it.second.height }
        val sheet = BufferedImage(tileWidth * columns, tileHeight * ((count + columns - 1) / columns), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.font = Font(Font.MONOSPACED, Font.BOLD, 16)
        shots.forEachIndexed { i, (at, image) ->
            val x = i % columns * tileWidth
            val y = i / columns * tileHeight
            g.drawImage(image, x, y, null)
            val label = "#${i + 1} +${at}ms"
            g.color = Color(0, 0, 0, 170)
            g.fillRect(x, y, g.fontMetrics.stringWidth(label) + 8, 20)
            g.color = Color.WHITE
            g.drawString(label, x + 4, y + 15)
        }
        g.dispose()
        // ImageIO caches a stream's image in a temporary file unless told to keep it in memory
        return ByteArrayOutputStream().also { out -> MemoryCacheImageOutputStream(out).use { ImageIO.write(sheet, "png", it) } }.toByteArray()
    }

    /** The main render target now, downscaled to about [width] pixels wide. */
    private fun capture(width: Int): BufferedImage {
        val minecraft = Minecraft.getInstance()
        val future = CompletableFuture<BufferedImage>()
        minecraft.execute {
            val target = minecraft.gameRenderer.mainRenderTarget()
            // The client only downscales by a factor dividing both sides
            var downscale = Math.ceilDiv(target.width, width.coerceAtLeast(100)).coerceAtLeast(1)
            while (downscale > 1 && (target.width % downscale != 0 || target.height % downscale != 0)) downscale--
            try {
                Screenshot.takeScreenshot(target, downscale) { shot ->
                    try {
                        future.complete(shot.use { BufferedImage(it.width, it.height, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, it.width, it.height, it.pixels, 0, it.width) } })
                    } catch (e: Exception) {
                        future.completeExceptionally(e)
                    }
                }
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }
        Lean.awaitFrame()
        return future.get(10, TimeUnit.SECONDS)
    }

    private fun traceSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("ticks", buildJsonObject { put("type", "integer"); put("description", "How long (default 60, max $MAX_TICKS)") })
            put("every", buildJsonObject { put("type", "integer"); put("description", "A row every so many ticks (default 1)") })
            put("entities", buildJsonObject { put("type", "string"); put("description", "Entity types to follow, comma-separated parts of their ids (shulker, item_display…)") })
            put("radius", buildJsonObject { put("type", "number"); put("description", "Blocks around the player to find them (default 8)") })
            put("limit", buildJsonObject { put("type", "integer"); put("description", "The nearest ones followed (default 4)") })
            put("rows", buildJsonObject { put("type", "boolean"); put("description", "The rows after the summary line (default true; false: the summary only)") })
            put("steps", buildJsonObject {
                put("type", "array")
                put("description", "Keys held meanwhile, as client_input's steps: {keys, ms}")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("keys", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }) })
                        put("ms", buildJsonObject { put("type", "integer") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("ms")) })
                })
            })
        })
    }
}
