# Displays and models

Item, block and text displays are how builds get detail blocks can't give: furniture, props, signs, a crooked
lantern. With the plugin and FastAsyncWorldEdit, the AI (and the client's editors) can perfect them one by one: move
them by a fraction of a block, turn them about any axis, scale or stretch them, spawn the models of your resource
packs as decor, and remove them. Every call is one undo step.

| Tool | Does |
|---|---|
| `list_displays` | The displays whose position is in a box: uuid, kind, model, block or text, position, yaw, pitch, billboard, transformation, and the furniture whose root display it is |
| `edit_displays` | Sets absolute values on displays by uuid: position, yaw, pitch, any part of the transformation, billboard; what is left out stays |
| `spawn_display` | A new item display of a model, a block display or a text display, saved with the world; or, with `copy_of`, a copy of a display |
| `remove_displays` | Removes displays by uuid, furniture through its plugin |
| `list_models` | The item model ids of the server's resources, with the furniture showing each |

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

## Undo

`undo` reverts a whole call: displays moved or turned go back, spawned ones are removed, removed ones come back as they
were. Furniture moved to another block goes back to its first place. Changes made before still undo after: they reach
the furniture and displays that undo or a move put back as other entities.

## JSON

```json title="list_displays, one display"
{"uuid": "…", "type": "item", "world": "world", "position": [10.5, 64.0, 3.5], "yaw": 90.0, "pitch": 0.0,
 "billboard": "fixed",
 "transformation": {"translation": [0, 0, 0], "left_rotation": [0, 0, 0, 1], "scale": [1, 1, 1], "right_rotation": [0, 0, 0, 1]},
 "model": "example:lamp", "furniture": {"id": "lamp", "handle": "…"}}
```

`model` (the item's `item_model`, else its material) for item displays, `block` (the block state) for block displays,
`text` (plain) for text displays; `furniture` when it is a furniture's root display; `persistent: false` when it isn't
saved with the world (a plugin spawns it again).

| Tool | Arguments | JSON result |
|---|---|---|
| `list_displays` | `from`, `to`, `world?` | An array of displays, as above |
| `edit_displays` | `entities: [{uuid, position?, yaw?, pitch?, transformation?: {translation?, left_rotation?, scale?, right_rotation?}, billboard?}]`, `world?` | `{edited: [uuid], failed: [{uuid, error}], replaced: {old uuid: new uuid}, displays: [each as it ended]}` |
| `spawn_display` | `model` \| `block` \| `text` \| `copy_of` (a display's uuid, not a furniture's), `position` (a copy: optional, exact), `yaw?`, `pitch?`, `scale?`, `transformation?`, `billboard?`, `world?` | `{uuid, world}` |
| `remove_displays` | `uuids`, `world?` | `{removed: [uuid], failed: [{uuid, error}]}` |
| `list_models` | `filter?`, `namespace?` | `[{id, furniture: [furniture ids], vanilla}]`, the packs' models first |
