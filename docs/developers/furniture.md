# FurnitureProvider

Foreman has no furniture of its own, and its furniture tools are world tools: they need FastAsyncWorldEdit on the
server. A furniture plugin offers its furniture (models shown by display entities, with
collision cells) by registering a `FurnitureProvider`. The AI then gets `list_furniture`, `place_furniture`,
`remove_furniture`, furniture previews and [display editing](../server/displays.md); `build_structure` furnishes rooms with `furniture:<id>` entries of the
[room templates](../server/building-templates.md#rooms); `check`, `transform_region` and undo take the furniture into
account.

`compileOnly` on `games.qore:foreman-server-api`: [Add Foreman to your plugin](install.md#the-server-api).

=== "Java"

    ```java
    Bukkit.getServicesManager().register(FurnitureProvider.class, new MyFurniture(), this, ServicePriority.Normal);
    ```

=== "Kotlin"

    ```kotlin
    Bukkit.getServicesManager().register(FurnitureProvider::class.java, MyFurniture(), this, ServicePriority.Normal)
    ```

The highest priority provider is used. Everything is called on the main thread.

`find` takes in the furniture whose entities just appeared: pasting an [asset area](../server/assets.md#assets-from-areas)
copies each furniture's display entity (its data included, turned with the paste) and its blocks, then asks `find` for
the copies. A copy it doesn't return is removed and the furniture placed again with `spawn`. `move_asset` teleports
your root displays instead (same entities, new places): follow both with
[`RegionCopyEvent`](asset-library.md#regioncopyevent), fired before `find` is asked.

| Method | Does |
|---|---|
| `types()`, `type(id)` | Your furniture types |
| `find(world, box)` | The furniture whose root block is in a box |
| `spawn(type, root, yaw, state)` | Places floor furniture; null when refused |
| `place(type, player, against, face)` | Places wall, ceiling or hanging furniture as a player would |
| `place(type, player, against, face, yaw)` | The same, turned to `yaw` where the face leaves it free to turn (under a ceiling): the mod's placement tool places what its preview shows. By default the player's yaw |
| `remove(handle)` | Removes one without drops |

A `FurnitureType` gives its `id`, the `surfaces` it goes on (`floor`, `wall`, `ceiling`, `hanging`), its `rotations`
(4 or 8), its `footprint` (collision cells relative to the root, front facing north), whether it's `interactive`
(seats, containers: players must reach it), and its item `model` and `pose` so renders can draw it. `pose(face, yaw)`
is where its display shows the model attached to a face of a block (`DOWN` under a ceiling, a side on a wall), as
`place` puts it, null where it doesn't go: the mod's placement tool previews furniture with these poses
(`list_furniture`'s `poses`), so return the ones your placement really gives. By default it is `pose` on a floor and
nothing elsewhere.

## The look of each furniture

[`edit_displays`](../server/displays.md#furniture) perfects a furniture's look in place: it changes the
transformation of its root display (the `entity` of `PlacedFurniture`), never its position, yaw or pitch. Keep that
transformation when you load the display again (chunk loads, restarts, reloads of your plugin): rebuild its item if you
must, not its transformation. A move by whole blocks copies the root display (its data included) to the new root block
and asks `find` for the copy, as asset pastes do; a copy `find` doesn't return is removed and the furniture placed again
with `spawn`, its look then set on the new display. `spawn_display` shows a type's `model` with its `pose`: return the
pose your displays really have.
