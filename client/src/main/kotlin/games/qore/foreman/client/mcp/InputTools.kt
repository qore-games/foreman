package games.qore.foreman.client.mcp

import games.qore.foreman.client.mixin.KeyMappingAccessor
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.obj
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.world.phys.Vec3
import java.util.Locale
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Key bindings held across client ticks, like a player holding keys (`client_key hold_ms`, `client_input`). Each tick
 * starts by setting the held bindings down and the released ones up, before the game reads them: the player walks,
 * jumps, sneaks, sprints, uses and attacks as with real keys, and the client sends the server what it sends for those
 * (player input, sprinting, use, attack). Several calls' keys add up; a screen opening or the player leaving the world
 * releases everything.
 */
object InputTools {
    /** 60 s: the longest a call holds keys. */
    private const val MAX_TICKS = 1200

    /** Its [keys] held for [ticks], after [look] turned the camera (or did what else a step starts with: a replay's). */
    class Step(val keys: Set<KeyMapping>, val ticks: Int, val look: ((LocalPlayer) -> Unit)? = null)

    /** A call's steps, played a tick at a time from the next tick; [done] says how it went once released. */
    private class Plan(steps: List<Step>) {
        val ticks = steps.flatMap { step -> List(step.ticks) { step } }
        val done = CompletableFuture<String>()
        /** The tick being played. */
        var tick = -1
        var start: Vec3? = null
        var peak = 0.0
        var sprinting = 0
    }

    /**
     * Keys chosen a tick at a time (client_wander, client_path): each tick, [next] may turn the camera and returns the
     * keys to hold, null once done. [done] completes then with null, or with why it was stopped.
     */
    class Driver(val next: (LocalPlayer) -> Set<KeyMapping>?) {
        val done = CompletableFuture<String?>()
    }

    // Client thread only
    private val plans = mutableListOf<Plan>()
    private val drivers = mutableListOf<Driver>()
    private var down = emptySet<KeyMapping>()

    fun init() {
        ClientTickEvents.START_CLIENT_TICK.register { tick(it) }

        ClientMcp.register(ClientTool(
            "client_input",
            "Holds key bindings across ticks like a player holding keys, for what a press can't do: walking, sprinting, " +
                "sneaking, jumping, a double jump (jump, release, jump in the air), a dash (double-tap forward), holding use " +
                "or attack. Plays `steps` in order, each holding its `keys` (key.forward, key.back, key.left, key.right, " +
                "key.jump, key.sneak, key.sprint, key.use, key.attack…; [] for none) for `ms` rounded to ticks, after turning " +
                "to `yaw`/`pitch` or `look_at` if given; the server gets them as from real keys. Releases everything at the " +
                "end, when a screen opens or the player leaves. Returns then, with how the player moved; at once with `wait` " +
                "false (calls' keys add up; a call without steps releases everything).",
            schema(),
            enabled = { it.allowCommands },
            group = "Movement",
        ) { _, args ->
            val steps = args["steps"].arr().orEmpty().map { it.obj() ?: throw ToolFailure("Each step is {keys, ms}") }
            listOf(ClientMcp.text(if (steps.isEmpty()) ClientTools.onClient { release("released by client_input") } else input(steps, args["wait"].bool() != false)))
        })
    }

    /** The key binding [name] (`key.forward`…). On the client thread. */
    fun mapping(name: String): KeyMapping {
        val mappings = Minecraft.getInstance().options.keyMappings
        return mappings.firstOrNull { it.name == name } ?: throw ToolFailure("Unknown key binding $name: " + mappings.joinToString { it.name })
    }

    /** [ms] in client ticks, at least one. */
    fun ticks(ms: Int) = ((ms + 25) / 50).coerceAtLeast(1)

    /** Plays [steps] from the next tick, along with what other calls hold. */
    fun play(steps: List<Step>, max: Int = MAX_TICKS): CompletableFuture<String> {
        if (steps.sumOf { it.ticks } > max) throw ToolFailure("${max / 20} s at most per call")
        return ClientTools.onClient {
            val minecraft = Minecraft.getInstance()
            if (minecraft.player == null) throw ToolFailure("Not in a world")
            minecraft.gui.screen()?.let { throw ToolFailure("A screen is open (${it.javaClass.simpleName}): key bindings only act in game (escape first)") }
            Plan(steps).also { plans += it }.done
        }
    }

    /** Runs [driver] from the next tick, along with what other calls hold. */
    fun drive(driver: Driver): CompletableFuture<String?> = ClientTools.onClient {
        val minecraft = Minecraft.getInstance()
        if (minecraft.player == null) throw ToolFailure("Not in a world")
        minecraft.gui.screen()?.let { throw ToolFailure("A screen is open (${it.javaClass.simpleName}): key bindings only act in game (escape first)") }
        drivers += driver
        driver.done
    }

    /** What [play] says once it released its keys. If the client stops ticking first, releases everything. */
    fun <T> await(done: CompletableFuture<T>, ticks: Int): T = try {
        done.get(ticks * 50L + 10_000, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        Minecraft.getInstance().execute { release("the client stopped ticking") }
        throw ToolFailure("The client stopped ticking: released every key")
    }

    /** MinecraftMixin: a held attack mines without the mouse grabbed (the window in the background). */
    @JvmStatic
    fun holdsAttack() = Minecraft.getInstance().options.keyAttack in down

    private fun input(steps: List<JsonObject>, wait: Boolean): String {
        val parsed = ClientTools.onClient {
            steps.map { step ->
                val keys = step["keys"].arr().orEmpty().map { mapping(it.str() ?: throw ToolFailure("keys: binding ids like key.forward")) }.toSet()
                Step(keys, ticks(step["ms"].int() ?: throw ToolFailure("Each step needs ms")), look(step))
            }
        }
        val ticks = parsed.sumOf { it.ticks }
        val done = play(parsed)
        return if (wait) "Played ${steps.size} steps. " + await(done, ticks)
        else "Playing ${steps.size} steps over $ticks ticks (client_input without steps releases them)"
    }

    /** Turning the camera first, as client_look does: to `look_at` [x, y, z], or `yaw` / `pitch`. */
    private fun look(step: JsonObject): ((LocalPlayer) -> Unit)? {
        step["look_at"]?.let { e ->
            val at = e.arr()?.takeIf { it.size == 3 }?.map { it.double() ?: throw ToolFailure("look_at: [x, y, z]") } ?: throw ToolFailure("look_at: [x, y, z]")
            return { it.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3(at[0], at[1], at[2])) }
        }
        val yaw = step["yaw"].double()?.toFloat()
        val pitch = step["pitch"].double()?.toFloat()
        if (yaw == null && pitch == null) return null
        return { player ->
            yaw?.let { player.yRot = it; player.yHeadRot = it }
            pitch?.let { player.xRot = it.coerceIn(-90f, 90f) }
        }
    }

    private fun tick(minecraft: Minecraft) {
        if (plans.isEmpty() && drivers.isEmpty() && down.isEmpty()) return
        val player = minecraft.player
        plans.removeAll { plan -> (++plan.tick == plan.ticks.size).also { if (it) finish(plan, null, player) } }
        val screen = minecraft.gui.screen()
        when {
            player == null -> stop("the player left the world", null)
            screen != null -> stop("a screen opened (${screen.javaClass.simpleName})", player)
        }
        val now = plans.flatMapTo(HashSet()) { it.ticks[it.tick].keys }
        if (player != null) drivers.removeAll { driver ->
            val keys = try {
                driver.next(player)
            } catch (e: Exception) {
                driver.done.completeExceptionally(e)
                return@removeAll true
            }
            if (keys == null) driver.done.complete(null) else now += keys
            keys == null
        }
        for (key in down - now) (key as KeyMappingAccessor).`foreman$setDown`(false)
        for (key in now) press(key, key !in down)
        down = now
        if (player != null) for (plan in plans) {
            val step = plan.ticks[plan.tick]
            if (plan.tick == 0 || plan.ticks[plan.tick - 1] !== step) step.look?.invoke(player)
            track(plan, player)
        }
    }

    /** Sets [key] down, past the toggle of sprint and sneak; a new press is also a click (attack, use, drop, hotbar…). */
    private fun press(key: KeyMapping, new: Boolean) {
        val accessor = key as KeyMappingAccessor
        if (new) accessor.`foreman$setClickCount`(accessor.`foreman$clickCount`() + 1)
        accessor.`foreman$setDown`(true)
    }

    /** Ends every call's steps now and releases their keys. */
    private fun release(reason: String): String {
        val held = down.joinToString { it.name }
        stop(reason, Minecraft.getInstance().player)
        for (key in down) (key as KeyMappingAccessor).`foreman$setDown`(false)
        down = emptySet()
        return if (held.isEmpty()) "Nothing was held" else "Released $held"
    }

    private fun stop(reason: String, player: LocalPlayer?) {
        plans.forEach { finish(it, reason, player) }
        plans.clear()
        drivers.forEach { it.done.complete(reason) }
        drivers.clear()
    }

    private fun finish(plan: Plan, stopped: String?, player: LocalPlayer?) {
        if (player != null) track(plan, player)
        val played = plan.tick.coerceIn(0, plan.ticks.size)
        plan.done.complete((if (stopped == null) "Released after $played ticks." else "Released after $played of ${plan.ticks.size} ticks: $stopped.") + moved(plan, player))
    }

    /** Where the player started, how high they got and how long they sprinted (each tick sees the previous one's move). */
    private fun track(plan: Plan, player: LocalPlayer) {
        val start = plan.start ?: player.position().also { plan.start = it }
        plan.peak = maxOf(plan.peak, player.y - start.y)
        if (plan.tick > 0 && player.isSprinting) plan.sprinting++
    }

    private fun moved(plan: Plan, player: LocalPlayer?): String {
        val from = plan.start ?: return ""
        val to = player?.position() ?: return ""
        return " Moved %.2f blocks horizontally, from %.2f %.2f %.2f to %.2f %.2f %.2f; highest %+.2f; sprinting %d ticks; %s.".format(
            Locale.ROOT, Math.hypot(to.x - from.x, to.z - from.z), from.x, from.y, from.z, to.x, to.y, to.z, plan.peak, plan.sprinting,
            if (player.onGround()) "on the ground" else "in the air",
        )
    }

    private fun schema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("steps", buildJsonObject {
                put("type", "array")
                put("description", "In order, each {keys, ms, yaw?, pitch?, look_at?}. A key in two steps in a row stays down: put an empty step between to press it again")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("keys", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Key binding ids held during the step ([] = none)") })
                        put("ms", buildJsonObject { put("type", "integer"); put("description", "How long, rounded to ticks (50 ms), at least one") })
                        put("yaw", buildJsonObject { put("type", "number"); put("description", "Turn to it first (0 = south, 90 = west)") })
                        put("pitch", buildJsonObject { put("type", "number"); put("description", "Turn to it first (90 = down)") })
                        put("look_at", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "number") }); put("description", "[x, y, z] to face first") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("ms")) })
                })
            })
            put("wait", buildJsonObject { put("type", "boolean"); put("description", "Return once released (default true)") })
        })
    }
}
