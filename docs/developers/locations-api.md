# Location API

Show your plugin's locations (NPCs, spawns, regions, waypoints…) in the client mod, by **source** (your plugin) and
**category**, and receive what staff do with them in the location editor: moves, renames, new locations. It works on
any Paper server, with or without the Foreman plugin.

## Setup

Shade `games.qore:foreman-paper` (it brings `foreman-api`) into your plugin, see
[Add Foreman to your plugin](install.md#the-location-api), and register it once:

=== "Java"

    ```java
    ForemanPaper foreman = new ForemanPaper();
    foreman.register(this); // false when it's registered already: use ForemanPaper.getInstance()
    ```

=== "Kotlin"

    ```kotlin
    val foreman = ForemanPaper()
    foreman.register(this) // false when it's registered already: use ForemanPaper.instance
    ```

## Sending locations

=== "Java"

    ```java
    var builder = ForemanAPI.builder("myplugin", "spawns")
        .setAction(LocationAction.REPLACE)
        .setSourceColor(0x55FF55)
        .setDefaultDisplay(ForemanAPI.displayConfig().shape(RenderShape.FLAG).glow(true).build());
    for (Spawn spawn : spawns) builder.addPreciseLocation(spawn.id(), spawn.world(), spawn.x(), spawn.y(), spawn.z());
    ForemanPaper.getInstance().sendLocationData(player, builder.build());
    ```

=== "Kotlin"

    ```kotlin
    val packet = ForemanAPI.createReplacePacket("myplugin", "spawns") {
        setSourceColor(0x55FF55)
        setDefaultDisplay { shape(RenderShape.FLAG); glow(true) }
        for (spawn in spawns) addPreciseLocation(spawn.id, spawn.world, spawn.x, spawn.y, spawn.z)
    }
    ForemanPaper.instance?.sendLocationData(player, packet)
    ```

- **Actions:** `createAddPacket` adds to a category, `createReplacePacket` replaces it, `createRemovePacket` removes it
  (from Java: `builder(…).setAction(LocationAction.ADD | REPLACE)`).
  `sendClearLocations(player, source)` clears a source (or everything).
- **Locations:** `addBlockLocation` (block coordinates) or `addPreciseLocation`, each with an id unique in its
  category.
- **Display:** `color`, `name`, `glow`, `shape` (`CUBE`, `SPHERE`, `DIAMOND`, `FLAG`, `CROSS`, `BEACON`), `size`,
  `visibleDistance`, per location or as the category's default.

Send them to the players who should see them (staff), when they join and when the locations change.

## Receiving edits

Listen to the events, check the player's permission, apply the change, save, and send the updated locations back:

| Event | When |
|---|---|
| `LocationMoveRequestEvent` | A location was moved, or moved to another source or category (`newSource`, `newCategory`) |
| `LocationUpdateDisplayEvent` | A location was renamed |
| `LocationAddRequestEvent` | A player asked to add one where they stand (`/locations add <source> <category> [k=v…]`); fill `resultEntries` to answer |

All three are cancellable. Every plugin that shades the location API receives every edit: check `source` first.

## Channels

For other server platforms: `foreman:location_data` and `foreman:location_clear` (server to client),
`foreman:location_add_request`, `foreman:location_update_display` and `foreman:location_relocate` (client to server).
The codecs are in `foreman-api` (`LocationCodecs`).
