package games.qore.foreman.client.link

import games.qore.foreman.client.gizmo.BlockBox
import kotlin.test.Test
import kotlin.test.assertEquals

/** The gizmo's side of the WorldEdit tool: what a drag makes of each shape, and the commands that select it again. */
class WeShapeTest {
    @Test
    fun cuboidResizesFreely() {
        val shape = WeShape.Cuboid(BlockBox(0, 64, 0, 4, 66, 4))
        val dragged = shape.constrain(shape.bounds, BlockBox(0, 64, 0, 7, 66, 4))
        assertEquals(listOf("/pos1 0,64,0", "/pos2 7,66,4"), shape.fitted(dragged).commands())
    }

    @Test
    fun ellipsoidGrowsOnBothSides() {
        val shape = WeShape.Ellipsoid(doubleArrayOf(10.0, 70.0, 10.0), doubleArrayOf(5.0, 3.0, 7.0), sphere = false)
        assertEquals(BlockBox(5, 67, 3, 15, 73, 17), shape.bounds)
        // The east face dragged 2 blocks out: the west face goes 2 the other way, the center stays.
        val dragged = shape.constrain(shape.bounds, BlockBox(5, 67, 3, 17, 73, 17))
        assertEquals(BlockBox(3, 67, 3, 17, 73, 17), dragged)
        assertEquals(
            listOf("/pos1 10,70,10", "/pos2 17,70,10", "/pos2 10,73,10", "/pos2 10,70,17"),
            shape.fitted(dragged).commands(),
        )
    }

    @Test
    fun ellipsoidMovesWithoutResizing() {
        val shape = WeShape.Ellipsoid(doubleArrayOf(10.0, 70.0, 10.0), doubleArrayOf(5.0, 3.0, 7.0), sphere = false)
        val moved = shape.bounds.moved(4, 0, -2)
        assertEquals(moved, shape.constrain(shape.bounds, moved))
    }

    @Test
    fun sphereGrowsOnEveryAxis() {
        val shape = WeShape.Ellipsoid(doubleArrayOf(0.0, 64.0, 0.0), doubleArrayOf(4.0, 4.0, 4.0), sphere = true)
        val dragged = shape.constrain(shape.bounds, BlockBox(-4, 60, -4, 4, 70, 4))
        assertEquals(BlockBox(-6, 58, -6, 6, 70, 6), dragged)
        assertEquals("/pos2 6,64,0", shape.fitted(dragged).commands()[1])
    }

    @Test
    fun ellipsoidShrinksToOneBlockAtMost() {
        val shape = WeShape.Ellipsoid(doubleArrayOf(0.0, 64.0, 0.0), doubleArrayOf(3.0, 3.0, 2.0), sphere = false)
        // The east face dragged past the west one: the gizmo stops it there.
        val dragged = shape.constrain(shape.bounds, BlockBox(-3, 61, -2, -3, 67, 2))
        assertEquals(BlockBox(0, 61, -2, 0, 67, 2), dragged)
    }

    @Test
    fun cylinderGrowsAroundItsAxisAndFreelyInHeight() {
        val shape = WeShape.Cylinder(0.0, 0.0, 5.0, 3.0, 64, 69)
        assertEquals(BlockBox(-5, 64, -3, 5, 69, 3), shape.bounds)
        val wider = shape.constrain(shape.bounds, BlockBox(-5, 64, -3, 5, 69, 5))
        assertEquals(BlockBox(-5, 64, -5, 5, 69, 5), wider)
        val taller = shape.constrain(shape.bounds, BlockBox(-5, 64, -3, 5, 75, 3))
        assertEquals(BlockBox(-5, 64, -3, 5, 75, 3), taller)
        assertEquals(listOf("/pos1 0,64,0", "/pos2 5,75,3"), shape.fitted(taller).commands())
    }

    @Test
    fun polygonStretchesItsPoints() {
        val shape = WeShape.Polygon(listOf(intArrayOf(0, 0), intArrayOf(10, 0), intArrayOf(5, 10)), 64, 66)
        val fitted = shape.fitted(BlockBox(0, 64, 0, 20, 70, 10))
        assertEquals(listOf("/pos1 0,64,0", "/pos2 20,70,0", "/pos2 10,64,10"), fitted.commands())
    }

    @Test
    fun polyhedronStretchesItsVertices() {
        val shape = WeShape.Polyhedron(listOf(intArrayOf(0, 64, 0), intArrayOf(4, 64, 0), intArrayOf(0, 68, 4)), emptyList())
        val fitted = shape.fitted(BlockBox(0, 64, 0, 8, 68, 4))
        assertEquals(listOf("/pos1 0,64,0", "/pos2 8,64,0", "/pos2 0,68,4"), fitted.commands())
    }
}
