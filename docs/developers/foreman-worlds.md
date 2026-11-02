# ForemanWorlds

Read what Foreman keeps in each world: its [areas, points and profile](../server/world-context.md). Use them to fill
containers with loot, spawn NPCs at the spawns builds recorded, or turn areas into regions. The service is there when
the Foreman plugin runs with FastAsyncWorldEdit (the world tools); otherwise `load` returns `null`.

`compileOnly` on `games.qore:foreman-server-api`: [Add Foreman to your plugin](install.md#the-server-api).

=== "Java"

    ```java
    ForemanWorlds worlds = Bukkit.getServicesManager().load(ForemanWorlds.class); // null without Foreman
    for (WorldPoint p : worlds.points(world))
        if (p.getType().equals("container")) fillContainer(world.getBlockAt(p.getX(), p.getY(), p.getZ()), p.getKind());
    ```

=== "Kotlin"

    ```kotlin
    val worlds = Bukkit.getServicesManager().load(ForemanWorlds::class.java) // null without Foreman
    worlds?.points(world)?.filter { it.type == "container" }?.forEach { fillContainer(world.getBlockAt(it.x, it.y, it.z), it.kind) }
    ```

| Method | Returns |
|---|---|
| `areas(world)`, `area(world, name)` | `WorldArea`: name, boxes (`BoundingBox`, max corners exclusive), `kind` when it reserves space (`build`, `road`, `yard`…), note, `theme` when it's a zone |
| `points(world)` | `WorldPoint`: block position, `type` and `kind` (`container`/`chest`), the `build` and `room` it belongs to |
| `profile(world)` | The world's profile as JSON (`{}` without one) |

Reads are thread-safe and return snapshots. Listen to `WorldDataChangeEvent` (`AREAS`, `POINTS`, `PROFILE`), fired on
the main thread after a change is saved, to follow changes.
