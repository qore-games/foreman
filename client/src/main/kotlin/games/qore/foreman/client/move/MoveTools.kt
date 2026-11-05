package games.qore.foreman.client.move

import games.qore.foreman.client.mcp.ClientMcp
import games.qore.foreman.client.mcp.ClientTool
import games.qore.foreman.client.mcp.InputTools
import games.qore.foreman.client.mcp.ToolFailure
import games.qore.foreman.client.util.JSON
import games.qore.foreman.client.util.arr
import games.qore.foreman.client.util.bool
import games.qore.foreman.client.util.double
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.long
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.tags.FluidTags
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/**
 * Bot movement for tests: `client_wander` walks the player to random waypoints around where it starts, `client_path`
 * through given points. Both steer as a player does, holding the movement keys through [InputTools] and turning the
 * camera each tick, so the server sees a player walking; neither needs frames (lean clients).
 */
object MoveTools {
    private const val MAX_MS = 600_000
    /** Close enough to a waypoint, horizontally. */
    private const val ARRIVE = 0.8
    /** A path point's height may differ this much from the feet (a slab, a step). */
    private const val ARRIVE_Y = 2.0
    /** More than this in a tick is the server moving the player, not walking. */
    private const val TELEPORT = 2.0
    /** Ticks the player gets to stop once the keys are released. */
    private const val SETTLE_TICKS = 20
    /** Ticks without trying again after no waypoint looked reachable. */
    private const val RETRY_TICKS = 10

    fun init() {
        ClientMcp.register(ClientTool(
            "client_wander",
            "Walks the player around at random like a bot: waypoints within `radius` blocks of where it starts, on safe ground " +
                "a straight walk reaches (no lava, water, cliff or void, a block up at most), steering with the movement keys " +
                "and the camera as client_input does, jumping onto single blocks, picking another waypoint once there or after " +
                "`stuck_ms` without getting closer. After `duration_ms` it lets go and waits for the player to stop. The same " +
                "`seed` from the same place picks the same waypoints. Returns JSON: distance walked, waypoints reached, times " +
                "stuck, jumps, start and final positions. For load and network tests (with packets_record) or to move a test " +
                "client; needs no frames. client_input without steps stops it.",
            schema(
                "radius" to "number: blocks around the start (default 16, max 128)",
                "duration_ms" to "integer: how long (default 30000, max $MAX_MS)",
                "seed" to "integer: for the same waypoints again (default random, returned)",
                "speed" to "string: walk (default) or sprint",
                "jump" to "boolean: jump onto blocks in the way (default true)",
                "stuck_ms" to "integer: no closer for this long is stuck (default 2000)",
            ),
            enabled = { it.allowCommands },
        ) { _, args -> listOf(ClientMcp.text(wander(args))) })

        ClientMcp.register(ClientTool(
            "client_path",
            "Walks the player through `points` in order, steering as client_wander does (movement keys and camera, jumping " +
                "onto single blocks); a point not got closer to for `stuck_ms` is skipped. `loop` goes round again until " +
                "`duration_ms`. Returns JSON: for each point how often it was reached or skipped and how long each took (ms), " +
                "the distance walked, the final position. client_input without steps stops it.",
            buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject {
                    put("points", buildJsonObject {
                        put("type", "array")
                        put("description", "[[x, y, z], …] feet positions, exact (x.5 is a block's middle)")
                        put("items", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "number") }) })
                    })
                    put("loop", prop("boolean", "Back to the first point after the last, until duration_ms (default false)"))
                    put("duration_ms", prop("integer", "How long at most (default 60000 with loop, else $MAX_MS)"))
                    put("speed", prop("string", "walk (default) or sprint"))
                    put("jump", prop("boolean", "Jump onto blocks in the way (default true)"))
                    put("stuck_ms", prop("integer", "No closer for this long skips the point (default 2000)"))
                })
                put("required", buildJsonArray { add(JsonPrimitive("points")) })
            },
            enabled = { it.allowCommands },
        ) { _, args -> listOf(ClientMcp.text(path(args))) })
    }

    private fun wander(args: JsonObject): String {
        val radius = (args["radius"].double() ?: 16.0).coerceIn(1.0, 128.0)
        val seed = args["seed"].long() ?: Random.nextLong()
        val walker = Wanderer(radius, seed, options(args, 30_000))
        val stopped = run(walker)
        return json(walker.report(stopped, {
            put("waypoints_reached", walker.reached)
            put("no_waypoint", walker.none)
        }) {
            put("seed", seed)
            put("radius", radius)
            walker.start?.let { start -> put("max_from_start", round(walker.farthest)); walker.last?.let { put("from_start", round(start.horizontal(it))) } }
        })
    }

    private fun path(args: JsonObject): String {
        val points = args["points"].arr()?.map { p ->
            p.arr()?.takeIf { it.size == 3 }?.map { it.double() ?: throw ToolFailure("points: [[x, y, z], …]") }?.let { Point(it[0], it[1], it[2]) }
                ?: throw ToolFailure("points: [[x, y, z], …]")
        } ?: throw ToolFailure("Give points: [[x, y, z], …]")
        if (points.isEmpty() || points.size > 500) throw ToolFailure("1 to 500 points")
        val loop = args["loop"].bool() == true
        val walker = Follower(points, loop, options(args, if (loop) 60_000 else MAX_MS))
        val stopped = run(walker)
        return json(walker.report(stopped, {
            put("reached", walker.visits.sumOf { v -> v.count { it.reached } })
            put("skipped", walker.visits.sumOf { v -> v.count { !it.reached } })
            put("laps", walker.lap)
            if (!walker.done) put("unfinished_at", walker.index)
        }) {
            put("points", JsonArray(points.mapIndexed { i, p ->
                val visits = walker.visits[i]
                buildJsonObject {
                    put("point", i)
                    put("at", position(p))
                    put("reached", visits.count { it.reached })
                    put("skipped", visits.count { !it.reached })
                    put("ms", JsonArray(visits.takeLast(20).map { JsonPrimitive(it.ms) }))
                }
            }))
        })
    }

    private class Options(val sprint: Boolean, val jump: Boolean, val durationMs: Long, val stuckTicks: Int)

    private fun options(args: JsonObject, defaultMs: Int) = Options(
        sprint = when (args["speed"].str() ?: "walk") {
            "walk" -> false
            "sprint" -> true
            else -> throw ToolFailure("speed: walk or sprint")
        },
        jump = args["jump"].bool() != false,
        durationMs = (args["duration_ms"].int() ?: defaultMs).coerceIn(50, MAX_MS).toLong(),
        stuckTicks = InputTools.ticks((args["stuck_ms"].int() ?: 2000).coerceIn(250, 60_000)),
    )

    /** Drives [walker] until it ends; why it was stopped early, or null. */
    private fun run(walker: Walker): String? {
        val done = InputTools.drive(InputTools.Driver(walker::tick))
        return InputTools.await(done, InputTools.ticks(walker.options.durationMs.toInt()) + SETTLE_TICKS)
    }

    /**
     * Walks the player toward one target after another, a tick at a time, as a player holding forward (and sprint) and
     * turning: jumps when a block is in the way (and swims up in water), takes the [next] target once there or stuck,
     * and once the time is up or there is no next one, lets go and waits for the player to stop. Client thread.
     */
    private abstract class Walker(val options: Options) {
        private val started = System.nanoTime()
        private val end = started + options.durationMs * 1_000_000
        private val progress = Progress(options.stuckTicks)
        var start: Point? = null
        var last: Point? = null
        private var target: Point? = null
        var distance = 0.0
        var farthest = 0.0
        var ticks = 0
        var jumps = 0
        var stuck = 0
        var teleports = 0
        /** Ticks without keys, then the target is checked again. */
        var pause = 0
        private var ended = false
        private var settling = 0
        private var elapsedMs = 0L
        private var onGround = true

        /** The target after the current one: it was [reached], the player got stuck (false), or the walk starts (null). Null ends. */
        abstract fun next(at: Point, reached: Boolean?): Point?

        open fun arrived(at: Point, target: Point) = at.horizontal(target) < ARRIVE

        fun tick(player: LocalPlayer): Set<KeyMapping>? {
            val at = Point(player.x, player.y, player.z)
            last?.let { val moved = it.horizontal(at); if (moved > TELEPORT) teleports++ else distance += moved }
            last = at
            val from = start ?: at.also { start = it }
            farthest = maxOf(farthest, from.horizontal(at))
            onGround = player.onGround()
            if (ended) return if (++settling >= SETTLE_TICKS || (onGround && player.deltaMovement.horizontalDistance() < 0.01)) null else emptySet()
            if (System.nanoTime() >= end) return finish()
            ticks++
            if (pause > 0) { pause--; return emptySet() }
            var goal = target
            when {
                goal == null -> goal = choose(at, null)
                arrived(at, goal) -> goal = choose(at, true)
                progress.stuck(at.horizontal(goal)) -> { stuck++; goal = choose(at, false) }
            }
            if (goal == null) return finish()
            if (pause > 0) return emptySet()
            val yaw = at.yawTo(goal)
            player.yRot = yaw
            player.yHeadRot = yaw
            val options = Minecraft.getInstance().options
            val keys = HashSet<KeyMapping>()
            keys += options.keyUp
            if (this.options.sprint) keys += options.keySprint
            if (this.options.jump && (player.isInWater || (onGround && player.horizontalCollision))) {
                keys += options.keyJump
                if (onGround) jumps++
            }
            return keys
        }

        private fun choose(at: Point, reached: Boolean?): Point? {
            progress.reset()
            return next(at, reached).also { target = it }
        }

        private fun finish(): Set<KeyMapping> {
            ended = true
            elapsedMs = (System.nanoTime() - started) / 1_000_000
            return emptySet()
        }

        /** The JSON every walk returns: the outcome first (a test's report line shows the start), with its [results] and [details]. */
        fun report(stopped: String?, results: JsonObjectBuilder.() -> Unit, details: JsonObjectBuilder.() -> Unit) = buildJsonObject {
            stopped?.let { put("stopped", it) }
            put("distance", round(distance))
            results()
            put("stuck", stuck)
            put("jumps", jumps)
            if (teleports > 0) put("teleports", teleports)
            put("ms", if (ended) elapsedMs else (System.nanoTime() - started) / 1_000_000)
            put("ticks", ticks)
            put("blocks_per_s", round(distance / (maxOf(ticks, 1) / 20.0)))
            start?.let { put("start", position(it)) }
            last?.let { put("final", position(it)) }
            put("on_ground", onGround)
            details()
        }
    }

    /** client_wander: random waypoints around the start. */
    private class Wanderer(val radius: Double, val seed: Long, options: Options) : Walker(options) {
        private var waypoints: Wander? = null
        /** The target is where the player stood when no waypoint looked reachable: none was reached there. */
        private var waiting = false
        var reached = 0
        var none = 0

        override fun next(at: Point, reached: Boolean?): Point? {
            if (reached == true && !waiting) this.reached++
            val level = Minecraft.getInstance().level ?: return null
            val planner = waypoints ?: Wander(start ?: at, radius, seed, WorldGround(level)).also { waypoints = it }
            val next = planner.next(at)
            waiting = next == null
            if (next != null) return next
            none++
            pause = RETRY_TICKS
            return at
        }
    }

    /** client_path: the given points in order, round again with [loop]. */
    private class Follower(val points: List<Point>, val loop: Boolean, options: Options) : Walker(options) {
        class Visit(val reached: Boolean, val ms: Long)

        val visits = List(points.size) { mutableListOf<Visit>() }
        var index = 0
        var lap = 0
        var done = false
        private var since = System.nanoTime()

        override fun next(at: Point, reached: Boolean?): Point? {
            if (reached != null) {
                visits[index] += Visit(reached, (System.nanoTime() - since) / 1_000_000)
                since = System.nanoTime()
                if (++index == points.size) {
                    lap++
                    index = 0
                    if (!loop) { done = true; return null }
                }
            } else since = System.nanoTime()
            return points[index]
        }

        override fun arrived(at: Point, target: Point) = super.arrived(at, target) && abs(at.y - target.y) <= ARRIVE_Y
    }

    /** [Ground] in the client's world: solid below, the body's two blocks free of collisions, liquids and harm. */
    private class WorldGround(private val level: Level) : Ground {
        private val pos = BlockPos.MutableBlockPos()

        override fun stand(x: Int, z: Int, y: Int): Int? {
            if (!level.hasChunk(x shr 4, z shr 4)) return null
            for (feet in y + Ground.CLIMB downTo maxOf(y - Ground.DROP, level.minY + 1)) if (standable(x, feet, z)) return feet
            return null
        }

        private fun standable(x: Int, feet: Int, z: Int): Boolean {
            val below = state(x, feet - 1, z)
            if (below.getCollisionShape(level, pos).isEmpty || !below.fluidState.isEmpty || below.block in HARMFUL) return false
            return free(x, feet, z) && free(x, feet + 1, z)
        }

        override fun harms(x: Int, z: Int, y: Int): Boolean {
            if (!level.hasChunk(x shr 4, z shr 4)) return false
            return (y..y + 1).any { h -> state(x, h, z).let { it.block in HARMFUL || it.fluidState.`is`(FluidTags.LAVA) } }
        }

        private fun free(x: Int, y: Int, z: Int): Boolean {
            val state = state(x, y, z)
            return state.getCollisionShape(level, pos).isEmpty && state.fluidState.isEmpty && state.block !in HARMFUL
        }

        private fun state(x: Int, y: Int, z: Int): BlockState = level.getBlockState(pos.set(x, y, z))

        companion object {
            /** Blocks that hurt or trap whoever stands in or on them (liquids are left out apart). */
            private val HARMFUL = setOf(
                Blocks.FIRE, Blocks.SOUL_FIRE, Blocks.MAGMA_BLOCK, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE, Blocks.CACTUS,
                Blocks.SWEET_BERRY_BUSH, Blocks.WITHER_ROSE, Blocks.POWDER_SNOW, Blocks.COBWEB, Blocks.POINTED_DRIPSTONE,
            )
        }
    }

    private fun position(p: Point) = JsonArray(listOf(p.x, p.y, p.z).map { JsonPrimitive(round(it)) })

    private fun round(v: Double) = Math.round(v * 100) / 100.0

    private fun json(o: JsonObject) = JSON.encodeToString(JsonElement.serializer(), o)

    private fun prop(type: String, description: String) = buildJsonObject { put("type", type); put("description", description) }

    private fun schema(vararg props: Pair<String, String>) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            props.forEach { (name, spec) -> put(name, prop(spec.substringBefore(':'), spec.substringAfter(':').trim())) }
        })
    }
}
