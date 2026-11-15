# Layout types

Declare the point types and area types your plugin reads from Foreman's worlds ([`ForemanWorlds`](foreman-worlds.md)),
with the fields their `data` holds. Builders and their AI then know them: `layout_types` lists them, the client builds
its forms from them, the tools refuse data that doesn't fit, and `check_layout` finds the points and areas that don't
any more ([layout types](../server/world-context.md#layout-types)). The service is there with Foreman's world tools,
like `ForemanWorlds`.

`compileOnly` on `games.qore:foreman-server-api`: [Add Foreman to your plugin](install.md#the-server-api).

=== "Java"

    ```java
    ForemanLayout layout = Bukkit.getServicesManager().load(ForemanLayout.class); // null without Foreman's world tools
    layout.register(this, LayoutType.point("loot:container")
        .label("Loot container")
        .description("A container filled from a loot table when the map starts")
        .color(0xC08040).icon("minecraft:chest")
        .field(LayoutField.string("table").required().description("The loot table"))
        .field(LayoutField.integer("rolls").range(1, 10).defaultValue(3))
        .field(LayoutField.choice("tier", "common", "rare").defaultValue("common"))
        .validate(p -> p.block().getType().isAir() ? "nothing to fill there" : null)
        .build());
    ```

=== "Kotlin"

    ```kotlin
    val layout = Bukkit.getServicesManager().load(ForemanLayout::class.java) // null without Foreman's world tools
    layout?.register(this, LayoutType.point("loot:container")
        .label("Loot container")
        .field(LayoutField.string("table").required())
        .field(LayoutField.integer("rolls").range(1.0, 10.0).defaultValue(3))
        .build())
    ```

Then read the values from the points and areas, the declared defaults filled in where they have none:

```java
for (WorldPoint p : worlds.points(world))
    if (p.getType().equals("loot:container")) fill(p.block(), p.getData().string("table"), p.getData().integer("rolls", 3));
```

## Types

| | |
|---|---|
| `LayoutType.point(id)`, `LayoutType.area(id)` | A builder. Ids are namespaced, lowercase: `ns:name`. A point is of the type its `type` names; an area of every type its claim `kind` or one of its `tags` names |
| `label`, `description` | What builders read (the label defaults to the id's name) |
| `color(0xRRGGBB)`, `icon(item id)` | How the client may show it: hints |
| `field(LayoutField)` | A field of its `data` (below) |
| `validate(subject -> message or null)` | Your own check: a `WorldPoint` (point types) or `WorldArea` (area types), its data with the defaults; runs on the main thread, in `check_layout` |

`register(plugin, type)` replaces your own type of the same id; another plugin's id is refused
(`IllegalArgumentException`). Your types go when your plugin disables; `unregister`, `types()` and `type(target, id)`
do the rest.

## Fields

| Factory | Holds |
|---|---|
| `LayoutField.string(name)` | Text |
| `integer(name)`, `number(name)` | A whole or decimal number; `range(min, max)`, `min`, `max` bound it |
| `bool(name)` | `true` or `false` |
| `choice(name, values…)` | One of the values |
| `color(name)` | `"#rrggbb"` |
| `yaw(name)` | A direction, degrees as Minecraft's yaw (0 south, 90 west): turned with the asset or region a paste or move turns |
| `area(name)` | The name of an area of the same world (`check_layout` reports one that doesn't exist) |
| `target(name)` | Another thing of the world, a [target's JSON](panels.md#targets) (an entity, a block, a world object); `data.target(name)` reads it as a `PanelTarget`. Picked in the world in [world objects](world-objects.md#target-fields)' forms and panels |

Each returns a field to refine: `label`, `description`, `required()` (a point or area without it is refused and
reported), `defaultValue(value)` (what one without it reads; a colour as `0xRRGGBB` or `"#rrggbb"`), `group(title)`
(the heading it shows under in the editor's forms, with the other fields of that group) and, for a
[world object](world-objects.md#choices-that-depend-on-other-fields)'s choice, `dependsOn(fields…)`. Field names are
lowercase letters, digits and `_`.

## Data

`WorldPoint.getData()` and `WorldArea.getData()` give a `LayoutData`: `string`, `number`, `integer`, `bool` (with or
without a fallback), `color` (`0xRRGGBB`), `get`, `has`, `names`, `asMap` (String, Double, Boolean, List or Map, as
JSON has them). Points and areas of no declared type keep whatever data builders gave them, as is.

Listen to `WorldDataChangeEvent` (`POINTS`, `AREAS`) to follow changes: a point moved or its data changed is a
`POINTS` change.

## When Foreman enables after you

Foreman loads `POSTWORLD`: a plugin that loads at `STARTUP` (or doesn't soft-depend on `Foreman`) registers when the
service does, and must not name Foreman's classes in a class that loads without it:

```java
@EventHandler
public void onService(ServiceRegisterEvent event) {
    if (event.getProvider().getService().getName().equals("games.qore.foreman.server.api.ForemanLayout"))
        MyLayoutTypes.register(this); // the only class naming Foreman's API
}
```
