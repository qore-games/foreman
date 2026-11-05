package games.qore.foreman.client.mcp

import com.mojang.brigadier.arguments.StringArgumentType
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.input.Keys
import games.qore.foreman.client.util.Chat
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * What the player does, recorded to be played back (`client_record`, `client_replay`, `/foreman-record`, a key): each tick the
 * gameplay keys held, where they look, the hotbar slot and the commands they send, and where they end up. A recording
 * is saved with a test that replays it from where it started, so a bug the player can show becomes a test kept, and
 * the replay says where its path left the recorded one. Nothing runs while nothing records nor replays.
 */
object RecordTools {
    /** 5 minutes */
    private const val MAX_TICKS = 6000
    private val NAME = Regex("[A-Za-z0-9_.-]{1,64}")
    /** The bindings recorded; a frame's keys are a bit each, in this order */
    private val KEYS = listOf("key.forward", "key.back", "key.left", "key.right", "key.jump", "key.sneak", "key.sprint",
        "key.attack", "key.use", "key.swapOffhand", "key.drop")

    /** Commands never recorded: they carry passwords (auth plugins), and a replay would send them again */
    private val SECRET = setOf("login", "l", "log", "register", "reg", "changepassword", "changepass", "unregister", "unreg", "2fa", "totp")

    private val dir: Path get() = Config.dir.resolve("recordings")

    private class Frame(val keys: Int, val yaw: Float, val pitch: Float, val slot: Int)

    /** A recording in progress. */
    private class Recording(val name: String, val max: Int) {
        val frames = mutableListOf<Frame>()
        val path = mutableListOf<Vec3>()
        val commands = mutableListOf<Pair<Int, String>>()
        val started: LocalDateTime = LocalDateTime.now()
        var screens = 0
        var secrets = 0
    }

    // Client thread only
    private var recording: Recording? = null
    /** Where the player is after each tick of a replay */
    private var replayed: MutableList<Vec3>? = null

    fun init() {
        ClientTickEvents.START_CLIENT_TICK.register { minecraft -> recording?.let { capture(it, minecraft) } }
        ClientTickEvents.END_CLIENT_TICK.register { minecraft ->
            val player = minecraft.player ?: return@register
            recording?.takeIf { it.path.size < it.frames.size }?.path?.add(player.position())
            replayed?.add(player.position())
        }
        ClientSendMessageEvents.COMMAND.register { command ->
            val rec = recording ?: return@register
            if (command.substringBefore(' ').substringAfter(':').lowercase() in SECRET) rec.secrets++
            else rec.commands += maxOf(rec.frames.size - 1, 0) to command
        }
        Keys.bind("input-record", Keys.Category.DEBUG, Keys.UNBOUND, "Input recording (replayed as a test): start / stop") {
            val name = "rec-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            Chat.info(if (recording != null) stop() else runCatching { start(name, MAX_TICKS) + " (the same key stops it)" }.getOrElse { it.message ?: "failed" })
        }
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            // not /record: a client command would hide a server's own of that name
            dispatcher.register(literal("foreman-record")
                .executes { Chat.info(status()); 1 }
                .then(literal("stop").executes { Chat.info(stop()); 1 })
                .then(argument("name", StringArgumentType.word()).executes {
                    Chat.info(runCatching { start(StringArgumentType.getString(it, "name"), MAX_TICKS) + " (/foreman-record stop)" }.getOrElse { e -> e.message ?: "failed" })
                    1
                }))
        }

        ClientMcp.register(ClientTool(
            "client_record",
            "Records what the player does, to play it back as a test: each tick the gameplay keys held (moving, jumping, " +
                "sneaking, sprinting, attack, use, swap, drop), where they look, the hotbar slot and the commands they " +
                "send, and where they end up. `start` with a `name`, then the player plays (or you drive them: " +
                "client_input, commands), then `stop`: it is saved with a test of that name replaying it (client_replay), " +
                "unless one exists. The player records the same way with /foreman-record <name> and /foreman-record stop, or a key " +
                "(Foreman's controls): ask them to record a bug they can show. Screens and login commands aren't recorded.",
            ClientTools.schema("action" to "string: start, stop or status (default)", "name" to "string: with start, letters, digits, . _ -",
                "max_seconds" to "integer: with start, it stops itself then (default and max ${MAX_TICKS / 20})"),
            enabled = { it.allowCommands },
            acts = { (it["action"].str() ?: "status") != "status" },
            group = "Testing",
        ) { _, args ->
            val text = when (args["action"].str() ?: "status") {
                "start" -> start(args["name"].str() ?: throw ToolFailure("Give name"), ((args["max_seconds"].int() ?: (MAX_TICKS / 20)) * 20).coerceIn(20, MAX_TICKS))
                "stop" -> stop()
                "status" -> status()
                else -> throw ToolFailure("action: start, stop or status")
            }
            listOf(ClientMcp.text(text))
        })

        ClientMcp.register(ClientTool(
            "client_replay",
            "Plays a recording back (client_record): from where it started (a /minecraft:tp there, the camera and hotbar " +
                "slot as they were; `from_start` false: from here), the same keys, look, slot and commands each tick. " +
                "Returns how far its path went from the recorded one (the largest gap and when, the first tick past " +
                "`tolerance`), rows comparing both every `every` ticks and, with `entities`, the replay's trace summary " +
                "as client_trace's (feet against those entities' tops…). Without `recording`: the saved recordings.",
            ClientTools.schema(
                "recording" to "string: its name",
                "from_start" to "boolean: teleport to where it started first (default true)",
                "tolerance" to "number: blocks off the recorded path that count (default 0.5)",
                "every" to "integer: a row every so many ticks (default 20)",
                "entities" to "string: entity types to trace meanwhile, as client_trace's",
                "radius" to "number: for entities (default 8)",
                "limit" to "integer: for entities (default 4)",
            ),
            enabled = { it.allowCommands },
            group = "Testing",
        ) { _, args -> listOf(ClientMcp.text(args["recording"].str()?.let { replay(it, args) } ?: list())) })
    }

    private fun start(name: String, max: Int): String = ClientTools.onClient {
        if (!NAME.matches(name)) throw ToolFailure("A name is letters, digits, . _ - (64 at most)")
        if (recording != null) throw ToolFailure("Already recording ${recording!!.name}")
        Minecraft.getInstance().player ?: throw ToolFailure("Not in a world")
        recording = Recording(name, max)
        "Recording $name"
    }

    private fun status(): String = ClientTools.onClient {
        recording?.let { "Recording ${it.name}: ${it.frames.size / 20.0} s, ${it.commands.size} commands" } ?: "Not recording"
    }

    /** Each tick: the keys the game reads this tick, the look and slot; no key while a screen is open. */
    private fun capture(rec: Recording, minecraft: Minecraft) {
        val player = minecraft.player
        if (player == null || rec.frames.size >= rec.max) {
            val why = if (player == null) "the player left the world" else "it reached ${rec.max / 20} s"
            Chat.info(stop() + " ($why)")
            return
        }
        val screen = minecraft.gui.screen() != null
        if (screen) rec.screens++
        val keys = if (screen) 0 else KEYS.foldIndexed(0) { i, mask, name -> if (InputTools.mapping(name).isDown) mask or (1 shl i) else mask }
        rec.frames += Frame(keys, player.yRot, player.xRot, player.inventory.selectedSlot)
    }

    /** Ends the recording and saves it, with a test replaying it when there is none of that name. */
    private fun stop(): String {
        val rec = ClientTools.onClient { recording.also { recording = null } } ?: return "Not recording"
        if (rec.frames.isEmpty()) return "Recorded nothing"
        val ticks = minOf(rec.frames.size, rec.path.size)
        val json = buildJsonObject {
            put("recorded", rec.started.toString())
            put("ticks", ticks)
            putJsonArray("keys") { KEYS.forEach { add(it) } }
            // [keys, yaw, pitch, slot] a tick
            putJsonArray("frames") { rec.frames.take(ticks).forEach { f -> addJsonArray { add(f.keys); add(round(f.yaw, 2)); add(round(f.pitch, 2)); add(f.slot) } } }
            // where the player was after each tick
            putJsonArray("path") { rec.path.take(ticks).forEach { p -> addJsonArray { add(round(p.x, 4)); add(round(p.y, 4)); add(round(p.z, 4)) } } }
            putJsonArray("commands") { rec.commands.forEach { (tick, command) -> addJsonArray { add(tick); add(command) } } }
            put("screens", rec.screens)
        }
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("${rec.name}.json"), JSON.encodeToString(JsonObject.serializer(), json))
        val test = !TestTools.exists(rec.name)
        if (test) TestTools.save(rec.name, buildJsonObject {
            put("description", "Replays the recording ${rec.name} (${rec.started.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))}, ${ticks / 20.0} s) from where it started")
            put("keep_player", true)
            putJsonArray("steps") { add(buildJsonObject { put("label", "replay ${rec.name}"); put("client", "client_replay"); putJsonObject("args") { put("recording", rec.name) } }) }
        })
        return "Recorded ${rec.name}: ${ticks / 20.0} s, ${rec.commands.size} commands" +
            (if (rec.screens > 0) ", a screen open ${rec.screens} ticks (not replayed)" else "") +
            (if (rec.secrets > 0) ", ${rec.secrets} login commands left out" else "") +
            if (test) "; saved with the test ${rec.name} replaying it" else "; saved (the test ${rec.name} exists: not replaced)"
    }

    private fun list(): String {
        val names = if (!Files.isDirectory(dir)) emptyList() else dir.listDirectoryEntries().filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted()
        return names.joinToString("\n") { n ->
            val json = runCatching { JSON.parseToJsonElement(Files.readString(dir.resolve("$n.json"))) }.getOrNull()
            "$n: ${(json["ticks"].int() ?: 0) / 20.0} s, recorded ${json["recorded"].str()?.take(16) ?: "?"}"
        }.ifEmpty { "No recording ($dir)" }
    }

    private fun replay(name: String, args: JsonObject): String {
        val file = dir.resolve("$name.json")
        if (!Files.exists(file)) throw ToolFailure("No recording $name:\n" + list())
        val json = JSON.parseToJsonElement(Files.readString(file))
        val keyNames = json["keys"].arr()?.mapNotNull { it.str() } ?: KEYS
        val frames = json["frames"].arr()?.map { it.arr() ?: throw ToolFailure("$file: a frame isn't an array") } ?: throw ToolFailure("$file has no frames")
        val recorded = json["path"].arr()?.map { p -> p.arr()!!.let { Vec3(it[0].double()!!, it[1].double()!!, it[2].double()!!) } } ?: emptyList()
        val commands = json["commands"].arr()?.groupBy({ it.arr()!![0].int()!! }, { it.arr()!![1].str()!! }) ?: emptyMap()
        if (frames.isEmpty() || recorded.isEmpty()) throw ToolFailure("$name is empty")
        val minecraft = Minecraft.getInstance()

        val fromStart = args["from_start"].bool() != false
        if (fromStart) back(recorded[0], frames[0])
        val offset = ClientTools.onClient { minecraft.player?.position() ?: throw ToolFailure("Not in a world") }.subtract(recorded[0])

        val mappings = ClientTools.onClient { keyNames.map { InputTools.mapping(it) } }
        val steps = frames.mapIndexed { i, f ->
            val mask = f[0].int() ?: 0
            val yaw = f[1].double()?.toFloat() ?: 0f
            val pitch = f[2].double()?.toFloat() ?: 0f
            val slot = f[3].int() ?: 0
            val sent = commands[i].orEmpty()
            InputTools.Step(mappings.filterIndexed { k, _ -> mask and (1 shl k) != 0 }.toSet(), 1) { p ->
                p.yRot = yaw; p.yHeadRot = yaw; p.xRot = pitch
                p.inventory.selectedSlot = slot
                sent.forEach { minecraft.connection?.sendCommand(it) }
            }
        }
        val path = Collections.synchronizedList(mutableListOf<Vec3>())
        val trace = args["entities"].str()?.let { entities ->
            TraceTools.begin(buildJsonObject {
                put("ticks", frames.size); put("entities", entities); put("rows", false)
                args["radius"]?.let { put("radius", it) }
                args["limit"]?.let { put("limit", it) }
            }, MAX_TICKS).first
        }
        ClientTools.onClient { replayed = path }
        val played = try {
            InputTools.await(InputTools.play(steps, MAX_TICKS), frames.size)
        } finally {
            ClientTools.onClient { replayed = null }
        }
        return compare(name, path.toList(), recorded, offset, fromStart, played, args) +
            (trace?.let { "\nTrace: " + runCatching { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }.getOrElse { "none (${it.message})" }.removePrefix("Summary: ") } ?: "")
    }

    /** Teleports the player to where the recording started and waits until they are there and still. */
    private fun back(start: Vec3, frame: JsonArray) {
        val minecraft = Minecraft.getInstance()
        // the path's first point is after the first tick: close enough to stand on
        ClientTools.onClient {
            val connection = minecraft.connection ?: throw ToolFailure("Not connected")
            connection.sendCommand("minecraft:tp @s %.4f %.4f %.4f %.2f %.2f".format(Locale.ROOT, start.x, start.y, start.z,
                frame[1].double() ?: 0.0, frame[2].double() ?: 0.0))
            minecraft.player?.inventory?.selectedSlot = frame[3].int() ?: 0
        }
        repeat(60) {
            Thread.sleep(50)
            if (ClientTools.onClient { minecraft.player?.position()?.distanceTo(start) ?: 99.0 } < 0.05) {
                Thread.sleep(500)
                return
            }
        }
        throw ToolFailure("Couldn't get back to where $start the recording started: /minecraft:tp needs the permission")
    }

    /** The replay's [path] against the [recorded] one ([offset]: where it was played from, against where it started). */
    private fun compare(name: String, path: List<Vec3>, recorded: List<Vec3>, offset: Vec3, fromStart: Boolean, played: String, args: JsonObject): String {
        val tolerance = args["tolerance"].double() ?: 0.5
        val every = (args["every"].int() ?: 20).coerceAtLeast(1)
        val n = minOf(path.size, recorded.size)
        if (n == 0) return "Replayed nothing of $name: $played"
        var max = 0.0
        var maxAt = 0
        var past = -1
        for (i in 0 until n) {
            val gap = path[i].subtract(offset).distanceTo(recorded[i])
            if (gap > max) { max = gap; maxAt = i }
            if (past < 0 && gap > tolerance) past = i
        }
        val end = path[n - 1].subtract(offset)
        val rows = (0 until n step every).plus(n - 1).distinct().joinToString("\n") { i ->
            val p = path[i].subtract(offset)
            val r = recorded[i]
            "%4d  %.3f %.3f %.3f  %.3f %.3f %.3f  %.3f".format(Locale.ROOT, i, p.x, p.y, p.z, r.x, r.y, r.z, p.distanceTo(r))
        }
        return ("Replayed %d of %d ticks of %s %s: max deviation %.3f blocks at tick %d, %s; ends at %.2f %.2f %.2f, recorded %.2f %.2f %.2f").format(
            Locale.ROOT, n, recorded.size, name, if (fromStart) "from its start" else "from here (compared from where it started)", max, maxAt,
            if (past < 0) "never past %.2f".format(Locale.ROOT, tolerance) else "first past %.2f at tick %d".format(Locale.ROOT, tolerance, past),
            end.x, end.y, end.z, recorded[n - 1].x, recorded[n - 1].y, recorded[n - 1].z,
        ) + "\n" + played.substringBefore(" Moved") + "\ntick  replayed x y z  recorded x y z  gap\n" + rows
    }

    private fun round(v: Float, digits: Int) = round(v.toDouble(), digits)

    private fun round(v: Double, digits: Int): Double {
        val f = Math.pow(10.0, digits.toDouble())
        return Math.round(v * f) / f
    }
}
