package games.qore.foreman.client.mcp

import games.qore.foreman.client.agent.McpClient
import games.qore.foreman.client.config.Config
import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.mcp.TestChecks.shorten
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.DeathScreen
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

/**
 * Tests as data (`client_test`): a scenario is steps that call client tools, the server's Foreman tools (through the
 * bridge's MCP) and commands, with waits, checks on what each returned and values captured for the next steps. A saved
 * scenario runs again by its name and the run answers a line a step, so a test kept costs a few lines each time
 * instead of a dozen calls and their whole outputs.
 */
object TestTools {
    private val NAME = Regex("[A-Za-z0-9_.-]{1,64}")
    private const val SHORT = 160
    private const val LONG = 1200
    private const val MAX_STEPS = 200
    private const val MAX_REPEAT = 50
    /** test_player's label for the state a test keeps */
    private const val KEPT = "client_test"

    internal val dir: Path get() = Config.dir.resolve("tests")
    /** Threads for parallel steps (none while no test runs some). */
    private val pool = Executors.newCachedThreadPool { Thread(it, "foreman-client-test").apply { isDaemon = true } }

    /** A step's outcome: its report line, its whole output when it is shown, its images. */
    private class Outcome(val passed: Boolean, val line: String, val shown: String?, val images: List<JsonObject>)

    /** What a call returned: the text of its blocks, its images, whether it failed. */
    private class Output(val text: String, val images: List<JsonObject>, val error: Boolean)

    /** A run: the vars so far, the server's MCP once a step needs it. */
    private class Run(val scope: ToolScope, val vars: MutableMap<String, String>, val verbose: Boolean) {
        var server: McpClient? = null
        var depth = 0
        /** Set once the player died or left the game during a step: the rest is skipped. */
        @Volatile
        var trouble: String? = null
    }

    fun init() {
        ClientMcp.register(ClientTool(
            "client_test",
            "Runs a test: steps calling client tools, the server's Foreman tools (through the bridge), commands as the " +
                "player or the console, waits, saved tests, steps in parallel, each checked against its output (`expect`) " +
                "and capturing values for the next ones (`capture`, \${name}). Answers a line a step and the lines around a " +
                "failure. Give `name` with `steps` to save it, then rerun it by `name` after each change. `keep_player` puts " +
                "the player back as they were, unhurt meanwhile; a death or a disconnect stops the test. `action`: guide " +
                "(how to write tests that hold: read it first), list, show or delete saved tests.",
            schema(),
            enabled = { it.allowCommands },
            group = "Testing",
        ) { scope, args -> call(scope, args) })
    }

    private fun schema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", prop("string", "run (default), guide, list, show, delete"))
            put("name", prop("string", "The test's name (saved in the Foreman config's tests/): run it, or save the given steps under it"))
            put("description", prop("string", "What the test checks (saved with it)"))
            put("steps", buildJsonObject {
                put("type", "array")
                put("description", "The steps, in order. A step is an object with one of: `client` (a client tool's name) or " +
                    "`server` (a server tool's name) with `args`; `command` (as the player, `wait_ms` to collect its replies, " +
                    "default 800); `console` (a server command as the console, `wait_for` a text); `wait` (ms); `run` (a " +
                    "saved test's name); `parallel` (a list of steps run at the same time, e.g. a trace while a command " +
                    "fires later). And optionally: `label`; `delay_ms` (waits first); `expect`: a text it contains, a list " +
                    "of them, or {contains, not_contains, regex, not_regex, number (a regex: its first match's group 1; " +
                    "default the first number) or all (a regex: every match's group 1), min, max (numbers, or arithmetic " +
                    "with vars: \"\${before} + 0.5\"), error (true: the call must fail)}; `capture`: {var: regex} (its " +
                    "group 1, else the match); `until_ms` (retried every `every_ms`, default 250, until `expect` holds); " +
                    "`show` (its whole output and images in the answer); `optional` (a failure is reported, the test goes " +
                    "on and passes). Around a step, or a `parallel` or `run` of them: `network` (a network profile held: " +
                    "burst, ping_100/300/500/1000), `drop` (inbound packet types ignored: name fragments like set_slot), " +
                    "`packets` (true, or {sent, received, not_sent, not_received}: packet types, or {type, min, max}; the " +
                    "counts on its line), `after_ms` (drops and packet counting go on that long after, for the server's " +
                    "answer: default 400). `network` and `drop` need a server running Foreman that gives you foreman.test.")
                put("items", buildJsonObject { put("type", "object") })
            })
            put("cleanup", buildJsonObject {
                put("type", "array")
                put("description", "Steps run at the end even when a step failed (removing what the test made)")
                put("items", buildJsonObject { put("type", "object") })
            })
            put("vars", buildJsonObject {
                put("type", "object")
                put("description", "Values for \${name} in the steps (saved ones are defaults). \${player} is the player's name")
            })
            put("keep_player", prop("boolean", "Save the player's state first and put it back at the end, unhurt meanwhile (saved with the test; needs Foreman on the server)"))
            put("repeat", prop("integer", "Runs it this many times (max $MAX_REPEAT) and reports how many passed, with the first failure: for flaky behaviour"))
            put("verbose", prop("boolean", "Every step's output (truncated), not only the failures'"))
        })
    }

    private fun prop(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }

    private fun call(scope: ToolScope, args: JsonObject): List<JsonObject> {
        val name = args["name"].str()?.also { if (!NAME.matches(it)) throw ToolFailure("A name is letters, digits, . _ - (64 at most)") }
        return when (args["action"].str() ?: "run") {
            "guide" -> listOf(ClientMcp.text(guide()))
            "list" -> listOf(ClientMcp.text(list()))
            "show" -> listOf(ClientMcp.text(format(load(name ?: throw ToolFailure("Give name")))))
            "delete" -> {
                if (!Files.deleteIfExists(file(name ?: throw ToolFailure("Give name")))) throw ToolFailure("No test $name")
                listOf(ClientMcp.text("Deleted $name"))
            }
            "run" -> {
                val given = args["steps"].arr()
                val test = if (given != null) buildJsonObject {
                    args["description"]?.let { put("description", it) }
                    args["vars"]?.let { put("vars", it) }
                    args["keep_player"]?.let { put("keep_player", it) }
                    put("steps", given)
                    args["cleanup"]?.let { put("cleanup", it) }
                }.also { if (name != null) save(name, it) } else load(name ?: throw ToolFailure("Give steps, or the name of a saved test"))
                run(scope, name ?: "(unsaved)", test, args)
            }
            else -> throw ToolFailure("action: run, guide, list, show or delete")
        }
    }

    /** The testing skill (the api's `skills/testing.md`, a skill on servers running Foreman too), without its front matter. */
    private fun guide(): String {
        val text = TestTools::class.java.getResourceAsStream("/skills/testing.md")?.use { it.readAllBytes().decodeToString() }
            ?: throw ToolFailure("The guide is missing from this build")
        return text.substringAfter("\n---\n", text).trim()
    }

    private fun file(name: String) = dir.resolve("$name.json")

    internal fun exists(name: String) = Files.exists(file(name))

    private fun load(name: String): JsonObject {
        val f = file(name)
        if (!Files.exists(f)) throw ToolFailure("No test $name (saved: ${names().joinToString().ifEmpty { "none" }})")
        return JSON.parseToJsonElement(Files.readString(f)) as? JsonObject ?: throw ToolFailure("$f isn't a JSON object")
    }

    internal fun save(name: String, test: JsonObject) {
        Files.createDirectories(dir)
        Files.writeString(file(name), format(test) + "\n")
    }

    /** [test] as saved and shown: compact JSON, a line per key and per step (smaller than indented, still diffable). */
    private fun format(test: JsonObject): String = test.entries.joinToString(",\n ", "{", "}") { (key, value) ->
        JsonPrimitive(key).toString() + ":" + if (value is JsonArray && (key == "steps" || key == "cleanup")) {
            value.joinToString(",\n  ", "[\n  ", "\n ]") { compact(it) }
        } else compact(value)
    }

    private fun compact(e: JsonElement) = JSON.encodeToString(JsonElement.serializer(), e)

    private fun names(): List<String> =
        if (!Files.isDirectory(dir)) emptyList() else dir.listDirectoryEntries().filter { it.extension == "json" }.map { it.nameWithoutExtension }.sorted()

    private fun list(): String = names().joinToString("\n") { n ->
        val test = runCatching { load(n) }.getOrNull()
        "$n (${test?.get("steps").arr()?.size ?: "?"} steps)" + (test?.get("description").str()?.let { ": $it" } ?: "")
    }.ifEmpty { "No saved test (${dir})" }

    private fun run(scope: ToolScope, name: String, test: JsonObject, args: JsonObject): List<JsonObject> {
        val repeat = (args["repeat"].int() ?: 1).coerceIn(1, MAX_REPEAT)
        val verbose = args["verbose"].bool() == true
        val keep = args["keep_player"].bool() ?: test["keep_player"].bool() ?: false
        val vars = ConcurrentHashMap<String, String>()
        test["vars"].obj()?.forEach { (k, v) -> vars[k] = v.str() ?: v.toString() }
        args["vars"].obj()?.forEach { (k, v) -> vars[k] = v.str() ?: v.toString() }
        vars.putIfAbsent("player", ClientTools.onClient { Minecraft.getInstance().player?.gameProfile?.name ?: "" })
        if (repeat == 1) return once(Run(scope, vars, verbose), name, test, keep).second
        var passed = 0
        var failure: List<JsonObject>? = null
        val started = System.currentTimeMillis()
        repeat(repeat) {
            val (ok, blocks) = once(Run(scope, ConcurrentHashMap(vars), verbose), name, test, keep)
            if (ok) passed++ else if (failure == null) failure = blocks
        }
        val head = "test $name: $passed/$repeat runs passed in ${seconds(System.currentTimeMillis() - started)}" +
            (if (failure != null) "; the first failed run:" else "")
        return listOf(ClientMcp.text(head)) + (failure ?: emptyList())
    }

    /** One run of [test]: whether it passed, and its report. */
    private fun once(run: Run, name: String, test: JsonObject, keep: Boolean): Pair<Boolean, List<JsonObject>> {
        val started = System.currentTimeMillis()
        val lines = mutableListOf<String>()
        val shown = mutableListOf<JsonObject>()
        val steps = test["steps"].arr() ?: throw ToolFailure("The test has no steps")
        val kept = keep && keepPlayer(run, lines)
        val (passed, total) = steps(run, steps, "", lines, shown)
        run.trouble?.let { lines += "stopped: $it" }
        if (ClientTools.onClient { dead() }) lines += respawn()
        run.trouble = null
        val cleanup = test["cleanup"].arr()
        if (cleanup != null) {
            lines += "cleanup:"
            steps(run, cleanup, "  ", lines, shown, stopOnFailure = false)
        }
        val restored = if (kept) restorePlayer(run) else null
        val ok = passed == total
        val captured = run.vars.filterKeys { it != "player" }.entries.joinToString(" ") { "${it.key}=${it.value}" }
        val head = "test $name: ${if (ok) "passed" else "FAILED"} $passed/$total in ${seconds(System.currentTimeMillis() - started)}" +
            (restored?.let { "; $it" } ?: "")
        val text = (listOf(head) + lines + listOfNotNull(captured.takeIf { it.isNotEmpty() }?.let { "vars: $it" })).joinToString("\n")
        return ok to listOf(ClientMcp.text(text)) + shown
    }

    /** Saves the player's state on the server (test_player), unhurt until it is put back. Whether it was. */
    private fun keepPlayer(run: Run, lines: MutableList<String>): Boolean {
        val output = try {
            server(run, "test_player", buildJsonObject { put("action", "save"); put("label", KEPT); put("protect", true) })
        } catch (e: ToolFailure) {
            Output(e.message ?: "failed", emptyList(), true)
        }
        if (output.error) lines += "player not kept: ${shorten(output.text, SHORT)}"
        return !output.error
    }

    private fun restorePlayer(run: Run): String = try {
        val output = server(run, "test_player", buildJsonObject { put("action", "restore"); put("label", KEPT) })
        if (output.error) "player NOT restored: ${shorten(output.text, SHORT)}" else "player restored"
    } catch (e: ToolFailure) {
        "player NOT restored: ${e.message}"
    }

    /** Runs [steps] in order, adding their lines; how many passed of how many ran or should have. */
    private fun steps(run: Run, steps: JsonArray, indent: String, lines: MutableList<String>, shown: MutableList<JsonObject>, stopOnFailure: Boolean = true): Pair<Int, Int> {
        if (steps.size > MAX_STEPS) throw ToolFailure("$MAX_STEPS steps at most")
        var passed = 0
        var total = 0
        fun skip(i: Int): Pair<Int, Int> {
            val skipped = steps.size - i - 1
            if (skipped > 0) lines += "$indent· $skipped skipped"
            return passed to total + skipped
        }
        /** A group's conditions around [block]; whether its packets held, their line counted and added after its steps'. */
        fun <T> under(conditions: TestConditions?, number: String, block: () -> T): Pair<T, () -> Boolean> {
            val (value, packets) = conditions?.around(block) ?: (block() to null)
            return value to {
                if (packets != null) {
                    total++
                    if (packets.failure == null) passed++ else shown += ClientMcp.text("step $number packets:\n${packets.traffic}")
                    lines += "$indent  ${if (packets.failure == null) "✓" else "✗"} ${number.trim()} packets: " + (packets.failure?.let { "$it (${packets.note})" } ?: packets.note)
                }
                packets?.failure == null
            }
        }
        for ((i, element) in steps.withIndex()) {
            val step = element.obj() ?: throw ToolFailure("Step ${i + 1} isn't an object")
            val number = "$indent${i + 1}"
            val group = step["parallel"] != null || step["run"] != null
            val conditions = if (!group) null else try {
                TestConditions.of(step)
            } catch (e: ToolFailure) {
                total++
                lines += "$indent✗ ${i + 1}: ${e.message}"
                if (stopOnFailure && step["optional"].bool() != true) return skip(i) else continue
            }
            val together = step["parallel"].arr()
            if (together != null) {
                val subs = together.mapIndexed { j, e -> e.obj()?.takeIf { it["run"] == null && it["parallel"] == null } ?: throw ToolFailure("Step $number.${j + 1}: parallel steps are calls (not run nor parallel)") }
                val healthy = ClientTools.onClient { trouble() } == null
                lines += "$number together:"
                val (outcomes, held) = under(conditions, number) {
                    subs.mapIndexed { j, sub -> CompletableFuture.supplyAsync({ step(run, sub, "$number.${j + 1}", false) }, pool) }.map { it.get() }
                }
                if (healthy) ClientTools.onClient { trouble() }?.let { run.trouble = "$it (during step $number)" }
                var failed = run.trouble != null
                outcomes.forEachIndexed { j, o ->
                    lines += "  " + o.line
                    o.shown?.let { shown += ClientMcp.text("step $number.${j + 1}:\n$it") }
                    shown += o.images
                    total++
                    if (o.passed || subs[j]["optional"].bool() == true) passed++ else failed = true
                }
                if (!held()) failed = true
                if (run.trouble != null || (failed && stopOnFailure && step["optional"].bool() != true)) return skip(i)
                continue
            }
            val nested = step["run"].str()
            if (nested != null) {
                if (run.depth >= 4) throw ToolFailure("Tests run inside each other 4 deep at most")
                lines += "$number run $nested:"
                run.depth++
                val test = load(nested)
                test["vars"].obj()?.forEach { (k, v) -> run.vars.putIfAbsent(k, v.str() ?: v.toString()) }
                val (counts, held) = under(conditions, number) {
                    try { steps(run, test["steps"].arr() ?: JsonArray(emptyList()), "$number.", lines, shown, stopOnFailure) } finally { run.depth-- }
                }
                val (p, t) = counts
                passed += p
                total += t
                val packetsHeld = held()
                if (run.trouble != null || ((p < t || !packetsHeld) && stopOnFailure && step["optional"].bool() != true)) return skip(i)
                continue
            }
            total++
            val outcome = step(run, step, number, true)
            lines += outcome.line
            outcome.shown?.let { shown += ClientMcp.text("step $number:\n$it") }
            shown += outcome.images
            if (outcome.passed || (run.trouble == null && step["optional"].bool() == true)) passed++
            if (run.trouble != null || (!outcome.passed && step["optional"].bool() != true && stopOnFailure)) return skip(i)
        }
        return passed to total
    }

    /**
     * Runs one step under its conditions (TestConditions), retried while `until_ms` lasts and its check fails.
     * [watch]: a death or a disconnect meanwhile fails it.
     */
    private fun step(run: Run, step: JsonObject, number: String, watch: Boolean): Outcome {
        step["delay_ms"].int()?.let { Thread.sleep(it.toLong()) }
        val started = System.currentTimeMillis()
        val until = step["until_ms"].int() ?: 0
        val every = (step["every_ms"].int() ?: 250).coerceAtLeast(50).toLong()
        val label = step["label"].str()?.let { TestChecks.substitute(JsonPrimitive(it), run.vars).str() } ?: describe(step, run.vars)
        // a test may start out of the game (waiting to join): only a change for the worse counts
        val healthy = watch && ClientTools.onClient { trouble() } == null
        var tries = 0

        /** Its report line, and the output shown: around what failed, all of it with `show`. */
        fun outcome(output: Output, verdict: TestChecks.Verdict, failure: String?, packets: TestConditions.Packets? = null): Outcome {
            val passed = failure == null
            val first = shorten(summary(step, output.text), SHORT)
            val ms = "(${System.currentTimeMillis() - started} ms${if (tries > 1) ", $tries tries" else ""})"
            val indent = number.takeWhile { it == ' ' }
            val notes = listOfNotNull(verdict.note, packets?.note).joinToString("; ").let { if (it.isEmpty()) "" else " [$it]" }
            val line = if (passed) {
                val images = output.images.size.let { if (it == 0) "" else " ($it image${if (it > 1) "s" else ""})" }
                "$indent✓ ${number.trim()} $label → $first$notes$images $ms"
            } else "$indent✗ ${number.trim()} $label: $failure${if (step["optional"].bool() == true) " (optional)" else ""} $ms"
            val show = step["show"].bool() == true
            val full = when {
                show -> output.text
                packets?.failure != null && failure == packets.failure -> "${packets.note}\n${packets.traffic}"
                !passed -> TestChecks.excerpt(output.text, verdict.at)
                run.verbose -> shorten(output.text, LONG / 2)
                else -> null
            }
            return Outcome(passed, line, full?.takeIf { it.isNotBlank() && (show || it.trim() != first) }, if (show) output.images else emptyList())
        }

        /** The tries: the last output, its verdict and why it fails (null when it holds). */
        fun attempt(): Triple<Output, TestChecks.Verdict, String?> {
            while (true) {
                tries++
                val output = try {
                    execute(run, step)
                } catch (e: ToolFailure) {
                    Output(e.message ?: "failed", emptyList(), true)
                } catch (e: Exception) {
                    Output("${e.javaClass.simpleName}: ${e.message}", emptyList(), true)
                }
                if (healthy) ClientTools.onClient { trouble() }?.let { why ->
                    run.trouble = "$why (during step ${number.trim()})"
                    return Triple(output, TestChecks.Verdict(why), why)
                }
                val verdict = TestChecks.check(step["expect"]?.let { TestChecks.substitute(it, run.vars) }, output.text, output.error)
                if (verdict.failure == null) {
                    val missing = TestChecks.capture(step["capture"].obj(), output.text, run.vars)
                    return Triple(output, verdict, missing?.let { "nothing matched capture $it" })
                }
                if (System.currentTimeMillis() - started + every > until) return Triple(output, verdict, verdict.failure)
                Thread.sleep(every)
            }
        }

        val conditions = try {
            TestConditions.of(step)
        } catch (e: ToolFailure) {
            return outcome(Output("", emptyList(), true), TestChecks.Verdict(e.message), e.message)
        }
        val (result, packets) = conditions?.around { attempt() } ?: (attempt() to null)
        val (output, verdict, failure) = result
        return outcome(output, verdict, failure ?: packets?.failure, packets)
    }

    /** The line a step shows: the first of its output, past the command it repeats (and icon glyphs). */
    private fun summary(step: JsonObject, text: String): String {
        var lines = text.lineSequence().map { clean(it).trim() }.filter { it.isNotEmpty() }.toList()
        // server_command: "/<command> (console, n ms)", "Replied:", then the replies
        if (step["console"] != null) lines = lines.drop(1).filter { it != "Replied:" }.ifEmpty { lines }
        val first = lines.firstOrNull() ?: return "(no output)"
        // client_run_command: "/<command> → <its replies>"
        return if (step["command"] != null && first.startsWith("/")) first.substringAfter(" → ", first).trim() else first
    }

    /** [s] without private-use characters: the icon glyphs of server messages, unreadable here. */
    private fun clean(s: String) = buildString { s.codePoints().forEach { if (it !in 0xE000..0xF8FF && it < 0xF0000) appendCodePoint(it) } }

    /** What went wrong with the player, null when nothing: dead, or out of the game (and why). Client thread. */
    private fun trouble(): String? {
        val minecraft = Minecraft.getInstance()
        return when {
            minecraft.level == null || minecraft.player == null ->
                "the player is out of the game: " + ScreenTools.describe().lineSequence().take(3).joinToString(" ")
            dead() -> "the player died"
            else -> null
        }
    }

    private fun dead(): Boolean {
        val minecraft = Minecraft.getInstance()
        return minecraft.player?.isDeadOrDying == true || minecraft.gui.screen() is DeathScreen
    }

    /** Respawns a dead player, for the cleanup and the state put back. */
    private fun respawn(): String {
        ClientTools.onClient { Minecraft.getInstance().player?.respawn() }
        repeat(40) {
            Thread.sleep(100)
            if (!ClientTools.onClient { dead() }) return "respawned the player"
        }
        return "couldn't respawn the player"
    }

    private fun execute(run: Run, raw: JsonObject): Output {
        val step = TestChecks.substitute(raw, run.vars) as JsonObject
        val args = step["args"].obj() ?: JsonObject(emptyMap())
        step["wait"].int()?.let { Thread.sleep(it.toLong()); return Output("waited $it ms", emptyList(), false) }
        step["command"].str()?.let { command ->
            return client(run, "client_run_command", buildJsonObject {
                put("command", command)
                step["wait_ms"]?.let { put("wait_ms", it) }
            })
        }
        step["console"].str()?.let { command ->
            return server(run, "server_command", buildJsonObject {
                put("command", command.removePrefix("/"))
                step["wait_ms"]?.let { put("wait_ms", it) }
                step["wait_for"]?.let { put("wait_for", it) }
            })
        }
        step["client"].str()?.let { return client(run, it, args) }
        step["server"].str()?.let { return server(run, it, args) }
        throw ToolFailure("A step needs client, server, command, console, wait or run")
    }

    private fun client(run: Run, name: String, args: JsonObject): Output {
        if (name == "client_test") throw ToolFailure("Use `run` to run a test inside another")
        val tool = ClientMcp.tools().firstOrNull { it.name == name && it.enabled(run.scope) }
            ?: throw ToolFailure("No client tool $name (or not allowed in this chat)")
        return blocks(tool.run(run.scope, args), false)
    }

    private fun server(run: Run, name: String, args: JsonObject): Output {
        val mcp = synchronized(run) {
            run.server ?: run {
                val url = Bridge.mcpUrl ?: throw ToolFailure("No Foreman server bridge here (the server has no Foreman, or this player has no token)")
                McpClient(url, Bridge.token ?: "").also { it.initialize(); run.server = it }
            }
        }
        val result = mcp.callTool(name, args)
        return blocks(result["content"].arr()?.mapNotNull { it.obj() } ?: emptyList(), result["isError"].bool() == true)
    }

    private fun blocks(content: List<JsonObject>, error: Boolean) = Output(
        content.filter { it["type"].str() == "text" }.joinToString("\n") { it["text"].str() ?: "" },
        content.filter { it["type"].str() == "image" },
        error,
    )

    private fun describe(step: JsonObject, vars: Map<String, String>): String {
        val s = TestChecks.substitute(step, vars) as JsonObject
        return shorten(when {
            s["command"] != null -> "/" + s["command"].str()?.removePrefix("/")
            s["console"] != null -> "console /" + s["console"].str()?.removePrefix("/")
            s["wait"] != null -> "wait ${s["wait"].int()} ms"
            s["client"] != null -> s["client"].str() + args(s["args"].obj())
            s["server"] != null -> s["server"].str() + args(s["args"].obj())
            else -> "?"
        }, 90)
    }

    private fun args(args: JsonObject?) = if (args.isNullOrEmpty()) "" else " " + args.entries.joinToString(" ") { (k, v) -> "$k=${v.str() ?: v}" }

    private fun seconds(ms: Long) = String.format(Locale.ROOT, "%.1f s", ms / 1000.0)
}
