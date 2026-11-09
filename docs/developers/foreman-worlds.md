# ForemanWorlds

Read what Foreman keeps in each world: its [areas, points and profile](../server/world-context.md). Use them to fill
containers with loot, spawn NPCs at the spawns builds recorded, or turn areas into regions. The service is there when
the Foreman plugin runs with FastAsyncWorldEdit (the world tools); otherwise `load` returns `null`.

`compileOnly` on `games.qore:foreman-server-api`: [Add Foreman to your plugin](install.md#the-server-api).

=== "Java"

    ```java
    ForemanWorlds worlds = Bukkit.getServicesManager().load(ForemanWorlds.class); // null without Foreman
    for (WorldPoint p : worlds.points(world))
        if (p.getType().equals("container")) fillContainer(p.block(), p.getKind());
    ```

=== "Kotlin"

    ```kotlin
    val worlds = Bukkit.getServicesManager().load(ForemanWorlds::class.java) // null without Foreman
    worlds?.points(world)?.filter { it.type == "container" }?.forEach { fillContainer(it.block(), it.kind) }
    ```

| Method | Returns |
|---|---|
| `areas(world)`, `area(world, name)` | `WorldArea`: name, boxes (`BoundingBox`, max corners exclusive), `kind` when it reserves space (`build`, `road`, `yard`…), note, `theme` when it's a zone, `tags`, `data` (the fields of its [layout types](layout-types.md)) |
| `points(world)` | `WorldPoint`: exact position (`x`, `y`, `z` doubles; a point recorded at a block stands on its bottom centre, `x.5 y z.5`), `yaw` and `pitch` (null when it faces nowhere in particular), `location()`, `block()`, `type` and `kind` (`container`/`chest`), the `build` and `room` it belongs to, `data` (the fields of its [layout type](layout-types.md)) |
| `profile(world)` | The world's profile as JSON (`{}` without one) |
| `instances(world)` | `WorldInstance`: each paste of a library asset, with its asset, source area, origin, rotation, box, points, areas and entities ([asset library](asset-library.md#instances)) |

Reads are thread-safe and return snapshots. Listen to `WorldDataChangeEvent` (`AREAS`, `POINTS`, `PROFILE`, `INSTANCES`), fired on
the main thread after a change is saved, to follow changes.
