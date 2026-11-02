package games.qore.foreman.server.api

import org.bukkit.Location
import org.bukkit.World
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.entity.Player
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import org.joml.Matrix4d
import java.util.UUID

/**
 * Custom furniture for Foreman: models shown by display entities, with collision cells. With a provider registered,
 * the AI gets `list_furniture`, `place_furniture`, `remove_furniture` and furniture previews; `build_structure`
 * furnishes rooms with `furniture:<id>` catalog entries; `check`, `transform_region` and undo take the furniture into
 * account. Register one as a Bukkit service (the highest priority one is used):
 *
 * ```java
 * Bukkit.getServicesManager().register(FurnitureProvider.class, new MyFurniture(), this, ServicePriority.Normal);
 * ```
 *
 * Everything is called on the main thread.
 */
interface FurnitureProvider {
    /** Every furniture type. */
    fun types(): Collection<FurnitureType>

    fun type(id: String): FurnitureType?

    /**
     * The furniture whose root block is in [box]. The chunks are loaded; their entities may have just been: take in
     * the furniture among them before answering.
     */
    fun find(world: World, box: BoundingBox): List<PlacedFurniture>

    /**
     * Floor furniture rooted in the block at [root], its front turned toward a player standing at [yaw] (0 = its front
     * faces north); [state] null for its default. Null when refused (obstructed, protected).
     */
    fun spawn(type: FurnitureType, root: Location, yaw: Float, state: String?): PlacedFurniture?

    /** Wall, ceiling or hanging furniture attached to [face] of [against], as [player] would place it. Null when refused. */
    fun place(type: FurnitureType, player: Player, against: Block, face: BlockFace): PlacedFurniture?

    /** Removes the furniture [handle] (from [PlacedFurniture.handle]) without drops; false when it is gone. */
    fun remove(handle: UUID): Boolean
}

/** A kind of furniture. */
interface FurnitureType {
    /** Its id, as players and configs know it (lowercase, `_`). */
    fun id(): String

    /** Where it can go: `floor`, `wall`, `ceiling`, `hanging`. */
    fun surfaces(): Set<String>

    /** Rotation steps: 4 or 8. */
    fun rotations(): Int

    /**
     * Its collision cells relative to the root block, unrotated (front facing north); empty when players walk through
     * it. Placed with a yaw, a cell is expected at the offset turned by the yaw snapped to 90° around the root, floored.
     */
    fun footprint(): List<Vector>

    /** It does something when used (seat, container…): players must be able to reach it. */
    fun interactive(): Boolean

    /** The item model (`namespace:path`, as in an item's `item_model`) it shows, for renders; null when unknown. */
    fun model(): String?

    /**
     * Where its display shows that item model when rooted at the block whose corner is the origin, placed with [yaw]
     * as in [FurnitureProvider.spawn]: the display's position and orientation, its transformation, and the item display
     * renderer's half turn around y. Foreman multiplies the model's `display.fixed` transform in.
     */
    fun pose(yaw: Float): Matrix4d
}

/**
 * A furniture in the world: [handle] identifies it for [FurnitureProvider.remove]; the root block; [cells] its
 * collision blocks; [entity] its display entity (so renders find its model).
 */
class PlacedFurniture(
    val handle: UUID,
    val type: String,
    val world: World,
    val x: Int,
    val y: Int,
    val z: Int,
    val yaw: Float,
    val state: String,
    val cells: List<Vector>,
    val interactive: Boolean,
    val entity: UUID?,
)
