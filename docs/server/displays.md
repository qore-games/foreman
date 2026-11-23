# Displays and models

Item, block and text displays are how builds get detail blocks can't give: furniture, props, signs, a crooked
lantern. With the plugin and FastAsyncWorldEdit, the AI (and the client's editors) can perfect them one by one: move
them by a fraction of a block, turn them about any axis, scale or stretch them, spawn the models of your resource
packs as decor, and remove them. Every call is one undo step.

| Tool | Does |
|---|---|
| `list_displays` | The displays whose position is in a box: uuid, kind, model, block or text, position, yaw, pitch, billboard, view range, transformation, and the furniture whose root display it is |
| `edit_displays` | Sets absolute values on displays by uuid: position, yaw, pitch, any part of the transformation, billboard, view range; what is left out stays |
| `spawn_display` | A new item display of a model, a block display or a text display, saved with the world; or, with `copy_of`, a copy of a display |
| `remove_displays` | Removes displays by uuid, furniture through its plugin |
| `list_models` | The item model ids of the server's resources, with the furniture showing each |
| `list_entities` | Any entity in a box, filtered by type and by what it shows: its scoreboard tags, its persistent data, a display's view range ([below](#entities)) |
| `edit_entities` | Many entities at once, by uuid or by box and filters: a display's view range, scoreboard tags, persistent data keys |

`format: json` gives programs the same as JSON ([below](#json)).

## Transformations

A display is drawn at its position, turned by its yaw and pitch, then by its transformation, in the display's own
axes: a `translation` in blocks, a `left_rotation`, a `scale` along each axis (stretch it with different values) and a
`right_rotation`, the rotations as quaternions `[x, y, z, w]`. `list_displays` gives them as the entity holds them;
`edit_displays` sets the ones you give and keeps the others, so an editor can send only what changed.

## Spawning models

The models are the item definitions of the server's resources: the vanilla client jar and the packs of
`resources.packs` in `config.yml` ([setup](setup.md#configyml)), `assets/<namespace>/items/<path>.json` being the model
`<namespace>:<path>`, what an item's `item_model` names. Without the packs there, only vanilla models are listed (a
model can still be spawned by its id: players with the pack see it).

`spawn_display` with `model` puts an item display (a paper item with that `item_model`, display context `fixed`):

- A whole-number `position` is a block: the display stands at the centre of its floor. Decimals put it exactly there.
- A model shown by a furniture type ([`FurnitureType.model`](../developers/furniture.md)) gets that furniture's pose,
  so it looks exactly as the furniture would, without its collision and behaviours.
- Another model stands on the floor: lifted so its lowest point is at the display's height, turned half a turn so its
  front (the north side of its model file) faces a player looking at `yaw`. Its transformation is `translation [0, lift,
  0]`, `left_rotation [0, 1, 0, 0]`, `scale [1, 1, 1]`.
- A block display fills the block and turns about its bottom centre: `translation [-0.5, 0, -0.5]`. A text faces
  every viewer (`billboard: center`).

`scale` multiplies the default size, `transformation` then sets the parts it gives.

## View range

A display is drawn up to `view_range` × 64 blocks away (1 by default), scaled by the player's entity distance setting:
decor on a big map needs more. The server must send it first: nothing is sent past its entity tracking range
(`entity-tracking-range` in `spigot.yml`, the same for every entity of a category). `edit_displays` sets it on some
displays, `edit_entities` on every display of a box; 0 hides a display.

In game, `/foreman model spawn <model> [scale]` spawns one on the block face you look at (else at your feet), its
front toward you, and `/foreman model list [filter] [page]` lists them (click one to spawn it). A model id without a
namespace is a pack's model of that path first, else vanilla's; the command completes the ids.

## Furniture

A furniture's root display stays its plugin's: it stays on its root block with the yaw and pitch the plugin gives it,
and the plugin keeps knowing it.

- **A move** by less than half a block, and **a turn** (a new yaw or pitch), go into the display's transformation: it
  looks moved and turned, its collision cells and hitboxes stay.
- **A move by whole blocks** (the move rounded) places the furniture again there through its plugin: its display is
  copied (its data, its look) and the plugin takes the copy in, so its collision cells follow; what is left of the move
  goes into the transformation. It is refused when a cell would land on a block that isn't free. The furniture is then
  a new entity: `edit_displays` says which in `replaced`.
- Its position stays its root block's, so an editor that showed the move by moving the entity takes the values
  `edit_displays` gives back in `displays` (the plugin doesn't resend a furniture's position).
- **Rotations and scales** only change how it looks.
- **Removing** it goes through its plugin (no drops); undo puts it back as it was, look included.

A plugin that doesn't take copied displays in gets the furniture placed again with `spawn`, then the look set on its
new display. The look lasts as long as the plugin leaves its displays' transformation alone when it loads them
([FurnitureProvider](../developers/furniture.md#the-look-of-each-furniture)).

## Entities

`list_entities` reads any entity whose position is in a box, not only displays: armor stands, item frames,
interactions, markers, mobs. Players are left out unless `types` names them. Or give `uuids` instead of a box: those
entities, wherever they are loaded. Filters, for a box, all optional:

| Filter | Takes |
|---|---|
| `types` | Those entity types (`item_display`, `armor_stand`…; `minecraft:` may be left out) |
| `model` | Those showing this item model: item displays, item frames, dropped items (`namespace:path`, or a path alone) |
| `item` | Those showing this item type |
| `block` | Block displays of this block, any state |
| `furniture` | Furniture root displays only (`true`), or none of them (`false`) |

Each comes with its custom name (MiniMessage), its scoreboard tags and its persistent data (PDC): every key, with its
type (`byte`, `short`, `int`, `long`, `float`, `double`, `string`, `byte_array`, `int_array`, `long_array`,
`container`, `list`) and its value. A boolean is a byte. Displays add their view range and the furniture whose root
display they are, text displays their text (MiniMessage), living entities whether their AI runs, their health and max
health; `persistent:
false` marks those not saved with the world. With `nbt: true`, each also has its whole NBT as SNBT, what `/data get
entity` shows (1000 entities at most): attribute base values are there.

`edit_entities` makes one change to many entities, one undo step: those listed by `uuids`, or every entity in a box
that the same filters take (several hundred at once is fine). What it can set, each optional:

- `view_range`: displays only. Other entities have no view range of their own, they fail.
- `add_tags`, `remove_tags`: scoreboard tags.
- `set_data`: persistent data keys, `[{key: "namespace:key", type, value}]`, `type` one of `byte`, `boolean` (a byte 0
  or 1), `short`, `int`, `long`, `float`, `double`, `string`.
- `remove_data`: keys to remove.
- `custom_name`: its custom name, MiniMessage (`<red>Guard`); an empty one takes it away.
- `text`: text displays only, their text, MiniMessage.
- `persistent`: whether it is saved with the world (`false`: gone when its chunk unloads).
- `ai`: living entities only, whether their AI runs (`false`: they stand still, as `NoAI`).
- `set_attributes`: living entities only, attribute base values, `{"minecraft:scale": 2, "max_health": 40}`.
- `health`: living entities only, at most their max health (set after `set_attributes`, so both can rise together).
- `remove_nbt`: NBT paths removed, as `/data remove entity` takes them (`brightness`,
  `item.components."minecraft:item_model"`, `Tags[0]`).
- `merge_nbt`: an SNBT compound merged into each entity's NBT, after the removals, as `/data merge entity` does:
  compounds key by key, any other value replaced. It sets what the other arguments don't: a display's brightness, glow
  colour, shadow, item or block, an armor stand's flags, an interaction's size, a mob's `NoAI`.

NBT is changed as `/data` changes it, without running the command (nothing goes to the admins' chat), and refused
where it would break the entity: it can't change its UUID, its type (`id`), the world it is in (`WorldUUIDMost`,
`WorldUUIDLeast`; `Pos` moves it within its world, into an unlocked area only), nor remove its `UUID` or `Pos`, nor
change a player. A bad SNBT or path fails the call before anything changes, saying where it is wrong. 1000 entities at
most per NBT change.

The NBT is written first, everything else over it. It returns what changed on each entity (the top-level NBT keys
whose value changed under `nbt`, the other properties under `changed`), those that already were so, and those it
couldn't change, with why (a locked area, not a display, a refused NBT change). Plugins that read a key when an entity
loads see a new value at its next load.

```json title="edit_entities: the decor of a box seen from further, and tagged for a plugin"
{"from": [0, 60, 0], "to": [200, 120, 200], "types": ["item_display", "block_display"], "furniture": false,
 "view_range": 4, "set_data": [{"key": "example:static", "type": "byte", "value": 1}]}
```

```json title="edit_entities: lit like daylight and glowing red, without its brightness override first"
{"uuids": ["…"], "remove_nbt": ["brightness"], "merge_nbt": "{Glowing:1b,glow_color_override:16728128}"}
```

`remove_entities` removes entities, one undo step: those listed by `uuids`, or every one in a box that the same filters
take. Any entity but a player: a furniture's root display goes through its plugin (as `remove_displays`), the others
are saved with all their data first, and undo brings them back as they were, with their uuid while it is still free
(else under a new one, which older changes follow). What rides one stays where it is. 1000 entities at most per call.

`copy_entities` copies entities by `uuids` with all their data under new uuids, in place or moved by `offset` (`[dx,
dy, dz]`), one undo step that removes the copies. Players and furniture are refused (`place_furniture` places another
one), with why.

## Undo

`undo` reverts a whole call: displays moved or turned go back, spawned ones are removed, removed ones come back as they
were. Furniture moved to another block goes back to its first place. Entities edited by `edit_entities` get their view
range, tags, name, text, persistence, AI, attributes and health back, and their persistent data as it was before the
call; after an NBT change, their whole NBT as it was. Entities removed by `remove_entities` come back, copies made by
`copy_entities` go. Changes made before still undo after: they reach
the furniture and displays that undo or a move put back as other entities.

## JSON

```json title="list_displays, one display"
{"uuid": "…", "type": "item", "world": "world", "position": [10.5, 64.0, 3.5], "yaw": 90.0, "pitch": 0.0,
 "billboard": "fixed", "view_range": 1.0,
 "transformation": {"translation": [0, 0, 0], "left_rotation": [0, 0, 0, 1], "scale": [1, 1, 1], "right_rotation": [0, 0, 0, 1]},
 "model": "example:lamp", "furniture": {"id": "lamp", "handle": "…"}}
```

`model` (the item's `item_model`, else its material) for item displays, `block` (the block state) for block displays,
`text` (plain) for text displays; `furniture` when it is a furniture's root display; `persistent: false` when it isn't
saved with the world (a plugin spawns it again).

| Tool | Arguments | JSON result |
|---|---|---|
| `list_displays` | `from`, `to`, `world?` | An array of displays, as above |
| `edit_displays` | `entities: [{uuid, position?, yaw?, pitch?, transformation?: {translation?, left_rotation?, scale?, right_rotation?}, billboard?, view_range?}]`, `world?` | `{edited: [uuid], failed: [{uuid, error}], replaced: {old uuid: new uuid}, displays: [each as it ended]}` |
| `spawn_display` | `model` \| `block` \| `text` \| `copy_of` (a display's uuid, not a furniture's), `position` (a copy: optional, exact), `yaw?`, `pitch?`, `scale?`, `transformation?`, `billboard?`, `view_range?`, `world?` | `{uuid, world}` |
| `remove_displays` | `uuids`, `world?` | `{removed: [uuid], failed: [{uuid, error}]}` |
| `list_models` | `filter?`, `namespace?` | `[{id, furniture: [furniture ids], vanilla}]`, the packs' models first |
| `list_entities` | `from`, `to` (with `types?`, `model?`, `item?`, `block?`, `furniture?`) \| `uuids`, `world?`, `nbt?` | An array of entities, as below |
| `edit_entities` | `uuids` \| `from`, `to` (with the filters of `list_entities`), `world?`, `view_range?`, `add_tags?`, `remove_tags?`, `set_data?: [{key, type, value}]`, `remove_data?`, `remove_nbt?: [path]`, `merge_nbt?` (SNBT), `custom_name?`, `text?`, `persistent?`, `ai?`, `set_attributes?: {key: base}`, `health?` | `{edited: [{uuid, view_range?: [before, after], tags_added?, tags_removed?, data_set?, data_removed?, nbt?: [key], changed?: [name]}], unchanged: [uuid], failed: [{uuid, error}]}` |
| `remove_entities` | `uuids` \| `from`, `to` (with the filters of `list_entities`), `world?` | `{removed: [uuid], failed: [{uuid, error}]}` |
| `copy_entities` | `uuids`, `offset?: [dx, dy, dz]`, `world?` | `{copies: {uuid: copy's uuid}, failed: [{uuid, error}]}` |

```json title="list_entities, one entity"
{"uuid": "…", "type": "item_display", "world": "world", "position": [10.5, 64.0, 3.5], "model": "example:lamp",
 "view_range": 1.0, "tags": ["decor"],
 "data": {"example:static": {"type": "byte", "value": 1}, "example:names": {"type": "list", "of": "string", "value": ["a", "b"]}},
 "furniture": {"id": "lamp", "handle": "…"}}
```

`model` for entities showing an item (item displays, item frames, dropped items), `block` for block displays,
`view_range` for displays, `custom_name` (MiniMessage) when it has one, `text` (MiniMessage) for text displays,
`ai`, `health` and `max_health` for living entities, `persistent: false` when it isn't saved, `nbt` (SNBT) with `nbt: true`;
a container's `value` holds its own keys, the same way.
