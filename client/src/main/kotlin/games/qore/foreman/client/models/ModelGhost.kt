package games.qore.foreman.client.models

import com.mojang.math.Transformation
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.resources.Identifier
import net.minecraft.util.Brightness
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.item.ItemDisplayContext
import net.minecraft.world.level.LightLayer
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4f
import org.joml.Vector3f

/**
 * What is about to be placed, shown where it goes: an item display of its model that only this client has (never sent
 * to the server), posed exactly as the display placed will be and lit as it will be. [ModelPlacementTool]'s, and the
 * objects tool's for kinds placed like furniture. [entityId]: negative, never one the server gives; seen as far as
 * [reach].
 */
class ModelGhost(private val entityId: Int, private val reach: Double) {
    private var entity: Display.ItemDisplay? = null
    private var model: Identifier? = null

    /**
     * Shows [model] at [at] posed by [pose] (from [at], as a display's transformation without the renderer's half turn,
     * the model's `fixed` transform still to apply), filling [box] of the world (as [box] gives it; null: unknown).
     */
    fun show(model: Identifier, at: Vec3, pose: Matrix4f, box: AABB?) {
        val level = Minecraft.getInstance().level ?: return
        val ghost = entity?.takeIf { it.level() === level && !it.isRemoved } ?: Display.ItemDisplay(EntityTypes.ITEM_DISPLAY, level).also {
            it.id = entityId
            it.setItemTransform(ItemDisplayContext.FIXED)
            it.setViewRange((reach / 64).toFloat())
            it.snapTo(at.x, at.y, at.z, 0f, 0f)
            level.addEntity(it)
            entity = it
            this.model = null
        }
        if (this.model != model) {
            ghost.setItemStack(ItemModels.stack(model))
            this.model = model
        }
        // It stands at the bottom centre of the model, its culling box the model's (as a point at a root block's corner
        // against a wall, entity culling hid it), unturned: the transformation is all of it, the pose less the half turn
        // the renderer adds. Lit as the display placed will be, where it stands (the middle of a painting is in the wall).
        val middle = box?.let { Vec3(it.center.x, it.minY, it.center.z) } ?: at
        box?.let {
            ghost.setWidth(maxOf(it.xsize, it.zsize).toFloat())
            ghost.setHeight(it.ysize.toFloat())
        }
        val offset = at.subtract(middle)
        ghost.setTransformation(Transformation(Matrix4f().translation(offset.x.toFloat(), offset.y.toFloat(), offset.z.toFloat()).mul(pose).mul(HALF_TURN)))
        // Its block as the server will have it: to the thousandth, not a hair under the floor.
        val lit = BlockPos.containing(Math.round(at.x * 1000) / 1000.0, Math.round(at.y * 1000) / 1000.0, Math.round(at.z * 1000) / 1000.0)
        ghost.brightnessOverride = Brightness(level.getBrightness(LightLayer.BLOCK, lit), level.getBrightness(LightLayer.SKY, lit))
        ghost.snapTo(middle.x, middle.y, middle.z, 0f, 0f)
        ghost.setOldPosAndRot()
    }

    fun remove() {
        val ghost = entity ?: return
        entity = null
        model = null
        val level = Minecraft.getInstance().level
        if (ghost.level() === level) level?.removeEntity(ghost.id, Entity.RemovalReason.DISCARDED)
    }

    companion object {
        /** The item display renderer's half turn, taken back from the ghost's transformation. */
        private val HALF_TURN = Matrix4f().rotationY(Math.PI.toFloat())

        /** The world box that [bounds] (a model's, its `fixed` transform applied) fills posed by [pose] from [at]. */
        fun box(bounds: AABB, pose: Matrix4f, at: Vec3): AABB {
            var min = Vector3f(Float.MAX_VALUE)
            var max = Vector3f(-Float.MAX_VALUE)
            for (x in listOf(bounds.minX, bounds.maxX)) for (y in listOf(bounds.minY, bounds.maxY)) for (z in listOf(bounds.minZ, bounds.maxZ)) {
                val p = pose.transformPosition(Vector3f(x.toFloat(), y.toFloat(), z.toFloat()))
                min = min.min(p)
                max = max.max(p)
            }
            return AABB(min.x + at.x, min.y + at.y, min.z + at.z, max.x + at.x, max.y + at.y, max.z + at.z)
        }
    }
}
