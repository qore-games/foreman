package games.qore.foreman.client.mcp

import com.mojang.blaze3d.systems.RenderSystem
import games.qore.foreman.client.lean.Lean
import games.qore.foreman.client.mc.Mc
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.client.resources.sounds.SoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.util.ARGB
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.lang.management.BufferPoolMXBean
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * The client's numbers: a snapshot of what it renders and holds (`client_stats`), what the process uses
 * (`client_usage`), the sounds it plays over a while (`client_sounds`), and exact pixel values of the world, without the
 * HUD (`client_pixels`).
 */
object MetricTools {
    private const val MAX_PIXELS = 512
    private const val MAX_SOUNDS = 400

    /** Sounds played while recording, null when not */
    @Volatile
    private var sounds: MutableList<String>? = null
    private var soundsSince = 0L

    fun init() {
        ClientMcp.register(ClientTool(
            "client_stats",
            "A snapshot of the client: FPS, memory, the graphics device and backend, render and simulation distance, the " +
                "sections and entities it renders (F3's lines), particles alive, entities in the world, ping, the post " +
                "effects applied and the resource packs. To see at a glance what weighs on the client, or check a setting.",
            ClientTools.schema(),
            acts = { false },
        ) { _, _ -> listOf(ClientMcp.text(stats())) })

        ClientMcp.register(ClientTool(
            "client_usage",
            "What the client process itself uses: heap (used, committed, max) and other JVM memory, resident memory (RSS, " +
                "where the system says), CPU time since start and CPU % over `seconds`, threads, the JVM's garbage collector, " +
                "processors and memory flags, frames drawn and loops without one, and lean mode. To see what a test client " +
                "costs, or check a launch's flags; `json` gives one object, for scripts.",
            ClientTools.schema("seconds" to "integer: measure CPU % over this long (default 0: not measured, max 60)",
                "json" to "boolean: one JSON object instead of lines (default false)"),
            acts = { false },
        ) { _, args -> listOf(ClientMcp.text(usage((args["seconds"].int() ?: 0).coerceIn(0, 60), args["json"].bool() == true))) })

        ClientMcp.register(ClientTool(
            "client_sounds",
            "Records the sounds the client plays for `seconds` (default 5, max 30): when, which, its category, where, how " +
                "far from the player, volume and pitch, and whether it started. To check what plays (gusts, footsteps, " +
                "custom sounds), and from where.",
            ClientTools.schema("seconds" to "integer: how long (default 5)"),
            acts = { false },
        ) { _, args -> listOf(ClientMcp.text(recordSounds((args["seconds"].int() ?: 5).coerceIn(1, 30)))) })

        ClientMcp.register(ClientTool(
            "client_pixels",
            "The exact colours of pixels of the world as rendered, post effects included, the HUD hidden for the shot: " +
                "a rectangle of the screen (x, y in screen pixels, from the top left; `bottom` counts y from the bottom " +
                "row instead), up to $MAX_PIXELS pixels, as #rrggbb rows. For data written into pixels and colour checks " +
                "a screenshot can't give exactly (scaled, vignette, HUD). `stats`: the numbers of a region of any size " +
                "instead (default the whole screen): mean colour, luminance mean/min/max/spread, and with `grid` n the mean " +
                "of each of n×n cells: whether an effect shows, and where, in a few lines.",
            ClientTools.schema(
                "x" to "integer: left (default: the middle of the screen minus width / 2; with stats 0)",
                "y" to "integer: top row (default 0)",
                "width" to "integer: default 16 (with stats: the screen's)",
                "height" to "integer: default 1 (with stats: the screen's)",
                "bottom" to "boolean: y counts from the bottom row (default false)",
                "stats" to "boolean: the region's numbers instead of its pixels (default false)",
                "grid" to "integer: with stats, the means of n×n cells too (default 1, max 8)",
            ),
            enabled = { it.allowScreenshots },
            acts = { false },
        ) { _, args -> listOf(ClientMcp.text(pixels(args))) })
    }

    /** SoundManagerMixin, after each sound the client plays. */
    @JvmStatic
    fun played(sound: SoundInstance, result: SoundEngine.PlayResult?) {
        val list = sounds ?: return
        if (list.size >= MAX_SOUNDS) return
        val player = Minecraft.getInstance().player
        val distance = player?.let { Math.sqrt(it.distanceToSqr(sound.x, sound.y, sound.z)) } ?: 0.0
        val volume = runCatching { sound.volume }.getOrDefault(0f)
        val pitch = runCatching { sound.pitch }.getOrDefault(0f)
        list += "+%dms %s [%s] at %.1f %.1f %.1f, %.1f blocks away, volume %.2f pitch %.2f%s".format(
            Locale.ROOT, System.currentTimeMillis() - soundsSince, sound.identifier, sound.source.name.lowercase(Locale.ROOT),
            sound.x, sound.y, sound.z, distance, volume, pitch, if (result == SoundEngine.PlayResult.NOT_STARTED) " (not started)" else "",
        )
    }

    private fun recordSounds(seconds: Int): String {
        if (sounds != null) throw ToolFailure("A recording is running")
        val list = java.util.Collections.synchronizedList(mutableListOf<String>())
        soundsSince = System.currentTimeMillis()
        sounds = list
        try {
            Thread.sleep(seconds * 1000L)
        } finally {
            sounds = null
        }
        return if (list.isEmpty()) "No sound in $seconds s" else "${list.size} sounds in $seconds s:\n" + synchronized(list) { list.joinToString("\n") }
    }

    private fun stats(): String = ClientTools.onClient {
        val minecraft = Minecraft.getInstance()
        val runtime = Runtime.getRuntime()
        val used = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576
        val device = RenderSystem.getDevice().deviceInfo
        val options = minecraft.options
        val level = minecraft.level
        val lines = mutableListOf(
            "FPS ${minecraft.fps} (limit ${options.framerateLimit().get()}), window ${minecraft.window.width}x${minecraft.window.height}, ${if (minecraft.isWindowActive) "focused" else "not focused"}",
            "Memory $used / ${runtime.maxMemory() / 1_048_576} MB",
            "Device ${device.name()} (${device.vendorName()}), backend ${device.backendName()}",
            "Render distance ${options.renderDistance().get()} chunks, simulation ${options.simulationDistance().get()}, entity distance ${options.entityDistanceScaling().get()}",
        )
        if (level != null) {
            lines.add(runCatching { minecraft.levelExtractor.sectionStatistics() }.getOrNull() ?: "Sections: ?")
            lines.add(runCatching { minecraft.levelExtractor.entityStatistics() }.getOrNull() ?: "Entities: ?")
            lines += "Entities in the world: ${level.entityCount}; particles: ${runCatching { minecraft.particleEngine.countParticles() }.getOrDefault("?")}"
        }
        minecraft.player?.let { player ->
            minecraft.connection?.getPlayerInfo(player.uuid)?.let { lines += "Ping ${it.latency} ms" }
        }
        lines += "Post effects: " + Mc.postEffects().applied.joinToString().ifEmpty { "none" }
        lines += "Resource packs: ${minecraft.resourcePackRepository.selectedPacks.size} selected"
        if (Lean.enabled) lines += "Lean mode: frames drawn on demand (FPS and the sections above are the last frame's); client_usage for the process"
        lines.joinToString("\n")
    }

    private fun usage(seconds: Int, json: Boolean): String {
        fun cpuNanos() = ProcessHandle.current().info().totalCpuDuration().map { it.toNanos() }.orElse(-1L)
        val cpu0 = cpuNanos()
        val wall0 = System.nanoTime()
        if (seconds > 0) Thread.sleep(seconds * 1000L)
        val cpu = cpuNanos()
        val cpuPercent = if (seconds > 0 && cpu >= 0) (cpu - cpu0) * 100.0 / (System.nanoTime() - wall0) else null
        val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
        val nonHeap = ManagementFactory.getMemoryMXBean().nonHeapMemoryUsage
        val direct = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean::class.java).firstOrNull { it.name == "direct" }?.memoryUsed
        val runtime = ManagementFactory.getRuntimeMXBean()
        // the kernel's view (Linux): resident memory, its peak, and every thread, the driver's and audio's too
        val status = runCatching { Files.readAllLines(Path.of("/proc/self/status")) }.getOrNull().orEmpty()
            .associate { it.substringBefore(':') to it.substringAfter(':').trim() }
        fun kb(key: String) = status[key]?.substringBefore(' ')?.toLongOrNull()
        val gcs = ManagementFactory.getGarbageCollectorMXBeans()
        val flags = runtime.inputArguments.filter { it.startsWith("-X") }
        val mb = 1_048_576L
        if (json) return buildJsonObject {
            put("heap_used_mb", heap.used / mb); put("heap_committed_mb", heap.committed / mb); put("heap_max_mb", heap.max / mb)
            put("non_heap_mb", nonHeap.used / mb); direct?.let { put("direct_mb", it / mb) }
            kb("VmRSS")?.let { put("rss_mb", it / 1024) }; kb("VmHWM")?.let { put("rss_peak_mb", it / 1024) }
            put("cpu_seconds", cpu / 1e9); cpuPercent?.let { put("cpu_percent", it); put("cpu_seconds_measured", seconds) }
            kb("Threads")?.let { put("threads", it) }; put("jvm_threads", ManagementFactory.getThreadMXBean().threadCount)
            put("uptime_seconds", runtime.uptime / 1000); put("processors", Runtime.getRuntime().availableProcessors())
            put("gc", buildJsonArray { gcs.forEach { add(buildJsonObject { put("name", it.name); put("count", it.collectionCount); put("ms", it.collectionTime) }) } })
            put("jvm_flags", buildJsonArray { flags.forEach { add(JsonPrimitive(it)) } })
            put("frames", Lean.frames); put("loops_without_frame", Lean.skipped)
            put("lean", Lean.enabled)
            if (Lean.enabled) { put("lean_fps", Lean.fps); put("lean_window", Lean.window.name.lowercase()); put("lean_render_distance", Lean.renderDistance) }
        }.toString()
        return listOfNotNull(
            "Heap ${heap.used / mb} MB used, ${heap.committed / mb} MB committed, ${heap.max / mb} MB max; non-heap ${nonHeap.used / mb} MB" +
                (direct?.let { ", direct buffers ${it / mb} MB" } ?: ""),
            kb("VmRSS")?.let { "Resident (RSS) ${it / 1024} MB, peak ${kb("VmHWM")?.div(1024) ?: "?"} MB" } ?: "Resident memory: not readable on this system",
            "CPU %.1f s since start".format(Locale.ROOT, cpu / 1e9) + (cpuPercent?.let { ", %.1f %% of one core over %d s".format(Locale.ROOT, it, seconds) } ?: ""),
            "Threads ${kb("Threads") ?: "?"} (${ManagementFactory.getThreadMXBean().threadCount} JVM), up ${runtime.uptime / 1000} s, ${Runtime.getRuntime().availableProcessors()} processors",
            "GC " + gcs.joinToString { "${it.name} ×${it.collectionCount} (${it.collectionTime} ms)" } + "; flags " + flags.joinToString(" ").ifEmpty { "none" },
            "Frames ${Lean.frames} drawn, ${Lean.skipped} loops without one",
            if (Lean.enabled) "Lean mode on: window ${Lean.window.name.lowercase()}, frames on demand" +
                (if (Lean.fps > 0) " and ${Lean.fps} per second" else " only") + ", render distance ${Lean.renderDistance}, no sound device"
            else "Lean mode off",
        ).joinToString("\n")
    }

    private fun pixels(args: kotlinx.serialization.json.JsonObject): String {
        val stats = args["stats"].bool() == true
        val width = if (stats) args["width"].int() ?: Int.MAX_VALUE else (args["width"].int() ?: 16).coerceIn(1, MAX_PIXELS)
        val height = if (stats) args["height"].int() ?: Int.MAX_VALUE else (args["height"].int() ?: 1).coerceIn(1, MAX_PIXELS / width)
        val minecraft = Minecraft.getInstance()
        val shown = ClientTools.onClient { ClientTools.hideHud(true) }
        try {
            // a few frames without the HUD
            Thread.sleep(150)
            Lean.awaitPixels()
            val future = CompletableFuture<String>()
            minecraft.execute {
                val target = minecraft.gameRenderer.mainRenderTarget()
                try {
                    Screenshot.takeScreenshot(target, 1) { image ->
                        image.use {
                            if (stats) return@use run { future.complete(stats(it, args, width, height)) }
                            val x0 = (args["x"].int() ?: (it.width / 2 - width / 2)).coerceIn(0, it.width - 1)
                            val top = args["y"].int() ?: 0
                            val y0 = (if (args["bottom"].bool() == true) it.height - 1 - top - (height - 1) else top).coerceIn(0, it.height - 1)
                            val rows = (y0 until minOf(y0 + height, it.height)).map { y ->
                                "y=$y: " + (x0 until minOf(x0 + width, it.width)).joinToString(" ") { x ->
                                    val c = it.getPixel(x, y)
                                    "#%02x%02x%02x".format(ARGB.red(c), ARGB.green(c), ARGB.blue(c))
                                }
                            }
                            future.complete("Screen ${it.width}x${it.height}, from x=$x0:\n" + rows.joinToString("\n"))
                        }
                    }
                } catch (e: Exception) {
                    future.completeExceptionally(e)
                }
            }
            Lean.awaitFrame()
            return future.get(10, TimeUnit.SECONDS)
        } finally {
            if (shown) minecraft.execute { ClientTools.hideHud(false) }
        }
    }

    /** [stats] of client_pixels: the region's mean colour and luminance spread, and its grid of cell means. */
    private fun stats(image: com.mojang.blaze3d.platform.NativeImage, args: kotlinx.serialization.json.JsonObject, width: Int, height: Int): String {
        val x0 = (args["x"].int() ?: 0).coerceIn(0, image.width - 1)
        val top = args["y"].int() ?: 0
        val w = width.coerceIn(1, image.width - x0)
        val h = height.coerceIn(1, image.height)
        val y0 = (if (args["bottom"].bool() == true) image.height - top - h else top).coerceIn(0, image.height - h)
        val grid = (args["grid"].int() ?: 1).coerceIn(1, 8)
        // every pixel up to about a million, then a regular sample
        val step = maxOf(1, Math.sqrt(w.toDouble() * h / 1_000_000).toInt())
        val sums = Array(grid * grid) { DoubleArray(4) }
        var min = 1.0
        var max = 0.0
        var sum = 0.0
        var squares = 0.0
        var n = 0
        val total = DoubleArray(3)
        for (y in y0 until y0 + h step step) for (x in x0 until x0 + w step step) {
            val c = image.getPixel(x, y)
            val r = ARGB.red(c) / 255.0
            val g = ARGB.green(c) / 255.0
            val b = ARGB.blue(c) / 255.0
            val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
            min = minOf(min, l); max = maxOf(max, l); sum += l; squares += l * l; n++
            total[0] += r; total[1] += g; total[2] += b
            val cell = sums[((y - y0) * grid / h) * grid + (x - x0) * grid / w]
            cell[0] += r; cell[1] += g; cell[2] += b; cell[3]++
        }
        fun hex(r: Double, g: Double, b: Double) = "#%02x%02x%02x".format((r * 255).toInt(), (g * 255).toInt(), (b * 255).toInt())
        fun luma(r: Double, g: Double, b: Double) = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val mean = sum / n
        val out = StringBuilder("Region ${w}x$h at x=$x0 y=$y0 of ${image.width}x${image.height}: mean ${hex(total[0] / n, total[1] / n, total[2] / n)}, " +
            "luminance %.3f (min %.3f, max %.3f, spread %.3f)".format(Locale.ROOT, mean, min, max, Math.sqrt(maxOf(0.0, squares / n - mean * mean))))
        if (grid > 1) {
            out.append("\nCells, top row first (mean colour/luminance):")
            for (row in 0 until grid) {
                out.append("\n").append((0 until grid).joinToString(" ") { col ->
                    val c = sums[row * grid + col]
                    val k = maxOf(c[3], 1.0)
                    "%s/%.2f".format(Locale.ROOT, hex(c[0] / k, c[1] / k, c[2] / k), luma(c[0] / k, c[1] / k, c[2] / k))
                })
            }
        }
        return out.toString()
    }
}
