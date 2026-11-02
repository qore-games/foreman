# FurnitureProvider

Foreman has no furniture of its own, and its furniture tools are world tools: they need FastAsyncWorldEdit on the
server. A furniture plugin offers its furniture (models shown by display entities, with
collision cells) by registering a `FurnitureProvider`. The AI then gets `list_furniture`, `place_furniture`,
`remove_furniture` and furniture previews; `build_structure` furnishes rooms with `furniture:<id>` entries of the
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

| Method | Does |
|---|---|
| `types()`, `type(id)` | Your furniture types |
| `find(world, box)` | The furniture whose root block is in a box |
| `spawn(type, root, yaw, state)` | Places floor furniture; null when refused |
| `place(type, player, against, face)` | Places wall, ceiling or hanging furniture as a player would |
| `remove(handle)` | Removes one without drops |

A `FurnitureType` gives its `id`, the `surfaces` it goes on (`floor`, `wall`, `ceiling`, `hanging`), its `rotations`
(4 or 8), its `footprint` (collision cells relative to the root, front facing north), whether it's `interactive`
(seats, containers: players must reach it), and its item `model` and `pose` so renders can draw it.
