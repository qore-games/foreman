# World objects

Your plugin draws things in the world with entities: a rope hanging between two posts, a zipline, a sign post, a
prop. Declare their kinds with `ForemanObjects` and builders edit them like the rest of the map, without Foreman
knowing what they are:

- in game, the [objects tool](../client/objects.md) of editor mode outlines them, selects them with a click, moves
  their anchors with the gizmo, edits their fields in a form made from your declaration, and places new ones;
- their AI lists, places, edits and removes them with `object_kinds`, `list_objects`, `place_objects`, `edit_objects`
  and `remove_objects`;
- every change is one step of Foreman's [undo](../server/world-tools.md#undo) and history.

Your entities stay the truth. Foreman keeps nothing: it asks your handler each time, so your objects work the same
without Foreman (Foreman runs on dev and build servers, your plugin everywhere). The service is there with Foreman's
world tools, like [`ForemanLayout`](layout-types.md).

`compileOnly` on `games.qore:foreman-server-api`: [Add Foreman to your plugin](install.md#the-server-api).

## Declaring a kind

```java
ForemanObjects objects = Bukkit.getServicesManager().load(ForemanObjects.class); // null without Foreman's world tools
if (objects != null) objects.register(this, ObjectKind.line("rides:zipline")
    .label("Zipline")
    .description("A rope players slide down, from its first anchor to its second")
    .color(0x8B5A2B).icon("minecraft:lead").category("Rides")
    .field(LayoutField.choice("rope", "hemp", "steel").defaultValue("hemp"))
    .field(LayoutField.number("sag").range(0, 8).defaultValue(0.5).description("How far its middle hangs, in blocks"))
    .field(LayoutField.number("speed").range(0.1, 2).defaultValue(0.6))
    .handler(new Ziplines(this))
    .build());
```

| | |
|---|---|
| `ObjectKind.point(id)`, `ObjectKind.line(id)` | A builder. Ids are namespaced, lowercase: `ns:name` |
| Shape | `POINT`: one anchor, where it stands (a prop; its facing is a `LayoutField.yaw` field). `LINE`: two anchors, its ends, in order |
| `label`, `description` | What builders and their AI read (the label defaults to the id's name) |
| `color(0xRRGGBB)` | The colour the editor draws it in |
| `icon(item model)` | The item model its card in the editor's palette shows in 3D (`minecraft:lead`, a resource pack's `ns:path`: what an item's `item_model` names); without one (or one the player's packs lack), a drawing of its shape |
| `category(name)` | The palette's group for it, a few words (`Lights`); without one, your plugin's name |
| `furniture(type, facing?, surface?)` | A point kind placed like your [furniture](furniture.md) `type` (your `FurnitureProvider`'s id, its `pose`s): [below](#placed-like-furniture) |
| `field(LayoutField)` | A field of its data: the same fields as [layout types](layout-types.md#fields) (text, numbers with ranges, switches, choices, colours, yaws, area names), with their defaults and required ones, and `LayoutField.target(name)`: another thing of the world ([below](#target-fields)) |
| `handler(ObjectHandler)` | Your side (below). Required |

`register` replaces your own kind of the same id (register it again when its choices change, after a reload);
another plugin's id is refused (`IllegalArgumentException`). Your kinds go when your plugin disables; `unregister`,
`kinds()` and `kind(id)` do the rest.

## The handler

Foreman calls it on the main thread. Before `create` and `update` it checks the data against the kind's fields (types,
choices, ranges, unknown and missing required fields) and fills in the defaults, so your handler only gets data that
fits; your own rules refuse with `ObjectResult.error("…")`, which builders and the AI read.

| Method | Does |
|---|---|
| `find(world, box)` | Your objects of this kind reaching into the box (an anchor or their outline in it). The chunks are loaded |
| `get(world, id)` | One by id, or null when there is none (any more) |
| `create(world, anchors, data)` | Makes one: `ObjectResult.ok(object)`, or `ObjectResult.error(why)` |
| `update(current, anchors, data)` | Moves it to `anchors` and gives it `data` (all of it, not a patch): it as it is now, or why not. It may come back with a new id |
| `remove(current)` | Removes it; false when it was gone |
| `choices(world, current, field, data)` | Optional: the choices of a field that [depends on others](#choices-that-depend-on-other-fields) for `data` as the builder has it now (`current` null for a new one): some of its values, in order; null for all of them |

A `WorldObject` is what you give back: its `id` (yours, stable while it exists: an entity's UUID), `kind`, `world`,
`anchors` (exact positions, as many as its shape has), `data` (a `LayoutData` of its field values; Foreman fills in the
defaults it lacks) and its `outline`: the line where it shows, which the editor draws, picks with the crosshair and
highlights. A sagging rope's outline is its curve, a polyline you compute the way your renderer bends it; without one
it is the line through its anchors (a point: a marker at it).

```java
final class Ziplines implements ObjectHandler {
    private final NamespacedKey key;

    Ziplines(Plugin plugin) { key = new NamespacedKey(plugin, "zipline"); }

    public List<WorldObject> find(World world, BoundingBox box) {
        // A zipline is one display entity at its middle: reach for the middles of those whose ends are in the box.
        List<WorldObject> found = new ArrayList<>();
        for (Entity e : world.getNearbyEntities(box.clone().expand(MAX_LENGTH / 2), e -> e.getPersistentDataContainer().has(key)))
            found.add(read(e));
        return found;
    }

    public WorldObject get(World world, String id) {
        Entity e = Bukkit.getEntity(UUID.fromString(id));
        return e != null && e.getWorld() == world && e.getPersistentDataContainer().has(key) ? read(e) : null;
    }

    public ObjectResult create(World world, List<Vector> anchors, LayoutData data) {
        if (anchors.get(0).distance(anchors.get(1)) > MAX_LENGTH) return ObjectResult.error("Too long: " + MAX_LENGTH + " blocks at most");
        ItemDisplay display = world.spawn(anchors.get(0).toLocation(world), ItemDisplay.class);
        write(display, anchors, data); // stores anchors and data in its persistent data, places and bends it
        return ObjectResult.ok(read(display));
    }

    public ObjectResult update(WorldObject current, List<Vector> anchors, LayoutData data) {
        if (anchors.get(0).distance(anchors.get(1)) > MAX_LENGTH) return ObjectResult.error("Too long: " + MAX_LENGTH + " blocks at most");
        Entity display = Bukkit.getEntity(UUID.fromString(current.getId()));
        if (display == null) return ObjectResult.error("It is gone");
        write(display, anchors, data);
        return ObjectResult.ok(read(display));
    }

    public boolean remove(WorldObject current) {
        Entity display = Bukkit.getEntity(UUID.fromString(current.getId()));
        if (display == null) return false;
        display.remove();
        return true;
    }

    /** The zipline [e] draws: its anchors and data from its persistent data, its outline the curve it hangs in. */
    private WorldObject read(Entity e) {
        List<Vector> anchors = anchors(e);
        LayoutData data = new LayoutData(Map.of("rope", rope(e), "sag", sag(e), "speed", speed(e)));
        return new WorldObject(e.getUniqueId().toString(), "rides:zipline", e.getWorld(), anchors, data, curve(anchors, sag(e), 16));
    }
}
```

A point kind is the same with one anchor: a sign post whose handler spawns its display at the anchor, turned by a
`yaw` field.

## Status badges

An object may say how it is doing: `new WorldObject(…).status(Status.blocked("no power"))` (or the constructor's last
argument). The objects tool draws it as a badge under the object's name, its word in its level's colour (`OK` green,
`OFF` grey, `BLOCKED` red, `PROBLEM` orange), the status card of the selected one with its `detail`, and `list_objects`
gives it to the AI. Your `find` is asked once a second for the objects near each builder with the tool on: keep the
status cheap to work out. Its [panel](panels.md#tabs-and-status)'s status line can say more.

## Placed like furniture

A prop your [`FurnitureProvider`](furniture.md) places (on floors, walls, ceilings) can be a point kind that builders
place as they place furniture: declare it with `.furniture(type, facing, surface)`. Before a new one is placed, the
objects tool shows that furniture's model as it would stand where the builder aims (`model()` and the `pose`s of
`list_furniture`): in the block before the face looked at, attached to it when the furniture goes there, else refused,
turned in its rotation steps by the scroll wheel. Your `create` then gets:

- the anchor: the middle of the floor of that block (the furniture's root block);
- `facing`, a `LayoutField.yaw` field of the kind: where its front faces (Minecraft's yaw, 0 south): away from the
  wall on a wall, else as turned;
- `surface`, a `LayoutField.choice` field: `floor`, `ceiling` or `wall` (the wall behind its front). Leave it out when
  the furniture goes on one surface only.

```java
ObjectKind.point("shop:counter_bell").icon("shop:bell").category("Shop")
    .field(LayoutField.yaw("facing").defaultValue(0))
    .furniture("counter_bell", "facing", null)
    .handler(new Bells(this))
    .build();
```

Both fields are the builder's to change in the form afterwards; the AI's `place_objects` gives them too.

## Target fields

A `LayoutField.target(name)` field links the object to another thing of the world: an entity, a block, another object
(an input this machine takes, the lamp a switch drives). Builders pick it in the world from the object's form, the AI
gives it as a [target's JSON](panels.md#targets) (`{"type":"entity","world":"world","uuid":"…"}`…). Foreman checks it
is a target, and when plugins give [panels](panels.md) has their providers resolve it first (a furniture for its cell
block or hitbox, as a panel's target field). Your handler gets it as its JSON map, `data.target("input")` reads it as a
`PanelTarget` (null when unset); you may give it back in your `WorldObject`'s data as that map or as the `PanelTarget`.

## Choices that depend on other fields

A choice may offer what fits another field's value: which output of the thing a target field links feeds an input.
Declare every value it may ever take, mark what it depends on, and give the choices for a value in your handler:

```java
.field(LayoutField.target("source").group("Inputs"))
.field(LayoutField.choice("source_output", "auto", "power", "signal", "level").defaultValue("auto")
    .group("Inputs").dependsOn("source"))
```

```java
@Override
public List<String> choices(World world, WorldObject current, String field, LayoutData data) {
    if (!field.equals("source_output")) return null;
    PanelTarget source = data.target("source");
    return source == null ? List.of("auto") : Stream.concat(Stream.of("auto"), outputsOf(source).stream()).toList();
}
```

The object's form asks for them when it opens and each time the builder changes a field they depend on (a value it
doesn't offer any more goes back to its default); `object_kinds {kind, data, id?}` gives them to the AI. The checks
still hold the field to its declared values: refuse in `create` and `update` a value that doesn't fit the data.

## Panels for your objects

The objects tool shows an object's form and the [panel](panels.md) a provider gives for the object as one panel: a
kind's objects may have live states, values, buttons and lists besides their fields. Foreman asks the providers for
`PanelTarget.worldObject(world, kind, id)` (`{"type":"object","world":…,"kind":…,"id":…}`); a provider of yours that
`resolve`s it (to itself, or to the entity it is) serves it. First comes **Location**: the anchors, with the kind's
`yaw` fields that have no group and the fields of the group `Location`. Then a field's `group` puts it at the top of
the panel's section of the same title (case aside), above that section's rows; groups no section has follow under
their own headings, and fields without one under the kind's name. So group your fields as your panel's sections:

```java
.field(LayoutField.choice("state", "off", "on").label("Start state").group("Machine"))   // the form: what it starts in
// describe(): Section.of("Machine").row(Row.field(LayoutField.choice("now", "off", "on").label("Now"), live))
```

Keep in the panel what isn't the object's data (its state now, what it does, its links' readings): the form edits its
fields and **Save** sends those the builder changed, as an `edit_objects` patch (others the object changes meanwhile
stay as they are, and the form's untouched fields follow them), while the panel's fields are sent one by one as they
are left.

## Keys in the objects tool

What builders switch while editing your objects (a preview of your machines running, a debug view) can be a key of the
objects tool rather than a button in each object's panel: `addAction(plugin, action)` adds one, `removeAction`
takes it back (your actions go when your plugin disables).

```java
objects.addAction(this, ObjectAction.of("rides:test_run").label("Run the rides").key('R')
    .description("Runs every ride of this world, for everyone in it")
    .handler(new ObjectActionHandler() {
        public Boolean state(Player player) { return running(player.getWorld()); }  // a switch; null: an action
        public boolean available(Player player) { return player.hasPermission("rides.admin"); }
        public PanelResult run(Player player) { return toggle(player.getWorld()); }
    })
    .build());
```

The mod lists it with the tool's keys (`Run the rides: on`), on the letter it asks for unless the tool already uses
it (then the first free letter of its label), says on the status card while a switch is on, and shows the answer of
`run`. The server asks `available` and `state` twice a second for each builder with the tool on (and right after a
run): keep them cheap. Nothing is asked while nobody has the tool on.

## Undo, locks and ids

Each `place_objects`, `edit_objects` or `remove_objects` call (and each change in the editor) is one undo step of
whoever made it. Undo calls your handler back: `remove` what was placed, `update` to the old anchors and data, `create`
what was removed. An object made again, or one your `update` gave a new id, is followed: later undo steps naming the
old id reach it. Changes inside an area [locked](../server/world-context.md#locks) by someone else are refused before
your handler is called.

Your objects are entities: pasting an [asset](../server/assets.md) or moving a region copies or moves them with their
persistent data, like any display. Follow those with [`RegionCopyEvent`](asset-library.md#regioncopyevent) when your
plugin keeps track of them elsewhere.

## When Foreman enables after you

As with [layout types](layout-types.md#when-foreman-enables-after-you): register in `onEnable` when you soft-depend on
`Foreman`, else when its service registers (`ServiceRegisterEvent` for
`games.qore.foreman.server.api.ForemanObjects`), from a class only loaded then.
