package games.qore.foreman.client.move

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** client_wander's and client_path's logic on a made-up world: waypoints, walks, being stuck, steering. */
class RouteTest {
    /** A world of columns: the feet's y where one can stand in each, null where one can't (lava, void). */
    private fun world(feet: (Int, Int) -> Int?) = Ground { x, z, y -> feet(x, z)?.takeIf { it in y - Ground.DROP..y + Ground.CLIMB } }

    private val flat = world { _, _ -> 64 }

    /** Flat at 64 with a lava pool (x 4..8, z -3..3) east of the start. */
    private val pool = world { x, z -> if (x in 4..8 && z in -3..3) null else 64 }

    @Test
    fun yawFollowsMinecraft() {
        val o = Point(0.0, 64.0, 0.0)
        assertEquals(0f, o.yawTo(Point(0.0, 64.0, 5.0)), 0.01f)
        assertEquals(90f, o.yawTo(Point(-5.0, 64.0, 0.0)), 0.01f)
        assertEquals(-90f, o.yawTo(Point(5.0, 64.0, 0.0)), 0.01f)
        assertEquals(180f, kotlin.math.abs(o.yawTo(Point(0.0, 64.0, -5.0))), 0.01f)
    }

    @Test
    fun walksOverFlatGroundAndSteps() {
        val from = Point(0.5, 64.0, 0.5)
        assertEquals(64, walk(flat, from, 10, 0))
        // a block up at x 5, two at x 6: the step is climbed, the wall isn't
        val steps = world { x, _ -> when { x >= 6 -> 66; x == 5 -> 65; else -> 64 } }
        assertEquals(65, walk(steps, from, 5, 0))
        val wall = world { x, _ -> if (x >= 5) 66 else 64 }
        assertNull(walk(wall, from, 8, 0))
        // a drop of three is fine, four is a cliff
        assertEquals(61, walk(world { x, _ -> if (x >= 5) 61 else 64 }, from, 8, 0))
        assertNull(walk(world { x, _ -> if (x >= 5) 60 else 64 }, from, 8, 0))
    }

    @Test
    fun neverWalksThroughLava() {
        val from = Point(0.5, 64.0, 0.5)
        assertNull(walk(pool, from, 12, 0))
        assertEquals(64, walk(pool, from, 0, 12))
    }

    /** Lava at a corner a diagonal cuts, under the side of the player's box though not under the line. */
    @Test
    fun keepsTheBoxOffHarmBesideTheLine() {
        val lava = setOf(1 to 0)
        val ground = object : Ground {
            override fun stand(x: Int, z: Int, y: Int) = if ((x to z) in lava) null else 64.takeIf { it in y - Ground.DROP..y + Ground.CLIMB }
            override fun harms(x: Int, z: Int, y: Int) = (x to z) in lava
        }
        assertNull(walk(ground, Point(0.5, 64.0, 0.5), 1, 1))
        // A block away from it, the box doesn't reach it.
        assertEquals(64, walk(ground, Point(0.5, 64.0, 2.5), 5, 2))
    }

    @Test
    fun waypointsStayInRadiusOnSafeGround() {
        val start = Point(0.5, 64.0, 0.5)
        val wander = Wander(start, 12.0, 42, pool)
        var at = start
        repeat(200) {
            val next = assertNotNull(wander.next(at))
            assertTrue(start.horizontal(next) <= 12.0 + 0.71, "within the radius: $next")
            assertTrue(at.horizontal(next) >= 3.0, "a few blocks away: $next")
            val bx = kotlin.math.floor(next.x).toInt()
            val bz = kotlin.math.floor(next.z).toInt()
            assertFalse(bx in 4..8 && bz in -3..3, "not in the lava: $next")
            assertEquals(64.0, next.y)
            at = next
        }
    }

    @Test
    fun sameSeedSameWaypoints() {
        val start = Point(0.5, 64.0, 0.5)
        fun route(seed: Long): List<String> {
            val wander = Wander(start, 20.0, seed, pool)
            var at = start
            return List(30) { wander.next(at)!!.also { at = it }.toString() }
        }
        assertEquals(route(7), route(7))
        assertNotEquals(route(7), route(8))
    }

    @Test
    fun noWaypointWithoutGround() {
        val void = world { _, _ -> null }
        assertNull(Wander(Point(0.5, 64.0, 0.5), 16.0, 1, void).next(Point(0.5, 64.0, 0.5)))
    }

    @Test
    fun stuckWithoutProgress() {
        val progress = Progress(patience = 40, step = 0.3)
        // walking closer every tick: never stuck
        for (i in 0 until 100) assertFalse(progress.stuck(30.0 - i * 0.2))
        progress.reset()
        // standing still: stuck on the 40th tick after the first
        assertFalse(progress.stuck(10.0))
        repeat(39) { assertFalse(progress.stuck(10.0)) }
        assertTrue(progress.stuck(10.0))
        progress.reset()
        // pushing into a wall, swaying a little: no closer by 0.3, stuck
        assertFalse(progress.stuck(5.0))
        var stuck = false
        for (i in 0 until 40) stuck = progress.stuck(5.0 - (i % 2) * 0.2)
        assertTrue(stuck)
    }
}
