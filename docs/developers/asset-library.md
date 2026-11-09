# Asset library hooks

Foreman's [asset library](../server/assets.md) copies and moves parts of worlds: asset areas pasted (`paste_asset`),
moved (`move_asset`), regions transformed (`transform_region`). Plugins that keep things by position or by entity
follow with `RegionCopyEvent`; a plugin that keeps worlds elsewhere loads them for Foreman with `ForemanWorldLoader`.
Both are in `games.qore:foreman-server-api` (`compileOnly`): [Add Foreman to your plugin](install.md#the-server-api).

## RegionCopyEvent

Fired on the main thread once a copy or move is in place: the blocks, their [block data](../server/assets.md#block-data)
and the entities, before Foreman asks the [furniture provider](furniture.md) about the furniture there.

| Field | What |
|---|---|
| `kind` | `PASTE` (a copy: the source stays) or `MOVE` (the source is cleared) |
| `sourceWorld`, `source` | The box copied (a `BoundingBox` of block coordinates, max corners exclusive) |
| `world`, `destination` | Where it went |
| `origin`, `offset`, `rotation` | The transform: the block at `p` went to `origin + offset + turn(p - origin)`, `turn` clockwise seen from above by `rotation` (0, 90, 180, 270; 90 maps `(dx, dz)` to `(-dz, dx)`) |
| `block(x, y, z)`, `position(x, y, z)`, `yaw(yaw)` | That transform for a block, an entity's position (turned about the centre of the `origin` block) and its yaw |
| `entities` | The source's entities → the destination's. A move teleports them: each maps to itself, except in the `MOVE` back of an undo, where an entity an undo put back since (as another entity) maps from the one recorded, which block data put back still names, to the one it is now. A paste spawns copies. Entities Foreman didn't carry over (furniture the provider placed again) aren't in it |

An undo of a move fires a `MOVE` back. Block data was copied as it is: entries that name blocks or entities still name
the old ones, so fix yours here.

=== "Java"

    ```java
    @EventHandler
    public void onCopy(RegionCopyEvent event) {
        for (var e : event.getEntities().entrySet()) {
            if (e.getKey().equals(e.getValue())) relink(event.getWorld(), e.getValue()); // moved: same entity, new place
            else adopt(event.getWorld(), e.getKey(), e.getValue());                      // copied: a new entity
        }
    }
    ```

=== "Kotlin"

    ```kotlin
    @EventHandler
    fun onCopy(event: RegionCopyEvent) = event.entities.forEach { (from, to) ->
        if (from == to) relink(event.world, to) else adopt(event.world, from, to)
    }
    ```

Register the listener only when Foreman runs (`isPluginEnabled("Foreman")`, a soft dependency), from a class that
nothing else loads without it.

A furniture provider whose root displays Foreman moves by teleport (`move_asset`, `rotate_instance`) moves its own
state with them here: its index of furniture by block or chunk, its collision cells, the parts it spawns again
(hitboxes, seats), and every rotation it keeps besides the display's (a stored yaw, a face) turned by `rotation`: Foreman
already turned the display's position and yaw, so its model is right; don't turn it again nor rebuild it from a stored
yaw. The same goes for the copies of a paste. On a paste, the copied cells' data still names the source's furniture:
hand it to the copies (`entities`), take the copies in, and drop what no furniture there owns. Mark a moved display
static (if your plugin filters redundant position packets) only once its viewers got the move: the move goes out as a
full position sync when it is long, and one filtered as a repeat leaves them seeing the model where it was.
`diff_regions` shows whether a copy came out right.

## Loading worlds

`open_asset_source` takes a player to the area an asset came from. When its world isn't loaded, Foreman asks the
`ForemanWorldLoader` service: register one if your plugin loads worlds on demand (a world store, worlds kept off the
server).

=== "Java"

    ```java
    Bukkit.getServicesManager().register(ForemanWorldLoader.class, new ForemanWorldLoader() {
        public boolean canLoad(String name) { return store.has(storeName(name)); }

        public void load(String name, BiConsumer<World, String> callback) {
            store.load(storeName(name), world -> callback.accept(world, null), error -> callback.accept(null, error));
        }
    }, this, ServicePriority.Normal);
    ```

=== "Kotlin"

    ```kotlin
    Bukkit.getServicesManager().register(ForemanWorldLoader::class.java, object : ForemanWorldLoader {
        override fun canLoad(name: String) = store.has(storeName(name))
        override fun load(name: String, callback: BiConsumer<World?, String?>) =
            store.load(storeName(name), { callback.accept(it, null) }, { callback.accept(null, it) })
    }, this, ServicePriority.Normal)
    ```

| Method | Does |
|---|---|
| `canLoad(name)` | Whether you can load the world recorded as `name` |
| `load(name, callback)` | Loads it, then calls `callback` on the main thread with the world, or null and why not; a world you already loaded is given at once |

Names are those Foreman recorded with the instance, perhaps on another server where the same world is loaded under
another name: map them to yours. Called on the main thread; Foreman waits up to 110 seconds for the callback.

## Instances

`ForemanWorlds.instances(world)` gives the [instances](../server/assets.md#instances) of a world: each paste of an
asset, with its asset and source area, origin, rotation, box, points, areas and entities. Pasted entities carry the
persistent data `foreman:instance` (a string, the instance id). `WorldDataChangeEvent` announces their changes with
the part `INSTANCES`.
