# Entity editor

Builds are made of entities as much as blocks: display entities (item, block and text displays) for most decor and
furniture, interactions for their hitboxes, armor stands, item frames, the mobs a plugin places. The **entity editor**
picks any of them where it stands: displays by what they draw, the others by their box. It moves, turns, scales and
stretches displays with a gizmo, live, and edits any entity's values, scoreboard tags, persistent data and NBT, the
server saving each change.

Turn it on in [editor mode](editor.md) (++b++, then ++2++) or from the hub's **World → Entities** section (*Use*). It acts on any
server; saving needs the Foreman plugin and its display and entity tools (`edit_displays`, `spawn_display`,
`remove_displays`, `list_entities`, `edit_entities`, `undo`).

## Picking

The crosshair picks what a display draws, not a box around its anchor:

- an **item display** by its item model as the client draws it (the model's faces, in its display context, with the
  display's transformation, yaw, pitch and billboard), so you can click through the gaps of a chair to what is
  behind it. Special models (heads, chests, banners) pick by their box.
- a **block display** by its block's shape (a stair is a stair);
- a **text display** by its text and background.

Any other entity is picked by its box (an interaction's width and height, an armor stand's, a mob's).

The entity under the crosshair is outlined with its label: a display's furniture (from the plugin), else its item
model, block or text; another entity's name, else its type. Entities that draw nothing the client can find (an empty
item, a block without shape, a display scaled to nothing, a marker armor stand) show a small marker on their anchor
within 8 blocks, while the editor is on. Players and what rides them (hats, held models, anything a plugin mounts on
players) are never picked nor listed: they aren't part of a build. Neither are entities that only exist on your client,
a plugin's packet entities (the server's list of the entities around you doesn't have them): the server can't edit
them.

| | |
|---|---|
| Left click | Select the entity under the crosshair; on nothing, deselect |
| Shift + left click | Add it to the selection, or take it out (Shift or your sneak key) |
| Right click | The next entity along the crosshair: what hides behind the first |
| ++i++ | Select every entity in your [selection box](selection.md) |
| ++n++ | The panel, with the entities nearby: click one to select it (Shift adds), even one you can't see |

The last selected entity is the primary one: the panel shows its values. The gizmo takes the last selected display's
axes.

## The gizmo

The gizmo moves, turns and scales the selected displays (the other entities of the selection stay; move them from the
panel's Entity tab).

| Mode (++v++ switches) | Handles |
|---|---|
| Move | Three arrows (along an axis) and three squares (in a plane) |
| Rotate | Three rings, around each axis through the pivot |
| Scale | Three handles that stretch along an axis, and the center knob that scales on all of them |

- ++o++: the selection's own axes (the primary's, as turned) or the world's. Scaling is always along the displays' own
  axes.
- ++period++: the pivot, their **origin** (where their transformation starts) or the **center** of their bounds. Several
  displays move, turn and scale together around their common pivot.
- Snapping is the editor's ([Gizmos and snapping](editor.md#gizmos-and-snapping)): moves by 1/16 of a block, turns by
  15°, scales by steps of 1/16 until you change them (++g++ cycles the step of the mode the gizmo is in). **Block grid**
  puts the pivot on the
  grid: a block display's origin on block corners, another's on the middle of a block's floor, the center pivot on
  block centres. Hold ++ctrl++ while dragging for none; scroll while dragging adds a step.

What you drag shows at once on your client; on release the server sets the final values of every selected display in
one change (`edit_displays`). If it refuses one (a claim, a furniture that can't move there), that display goes back
and the reason shows in chat. Turns go into the transformation's left rotation (the yaw and pitch stay), scales into
its scale, moves into the position. Furniture is the plugin's: a move by less than half a block stays in its
transformation, a move by whole blocks places the furniture again there (it gets a new uuid, still selected).

## The entity's position

A display's entity often stands away from what it shows: a display posed like furniture is turned to face down with a
translation of blocks, and its entity can be under the floor. While the editor is on, the selected and hovered
displays show their **position**: an orange marker where the entity is, a line to where its model starts (its origin),
and its coordinates (`root` for a furniture's root display).

++p++ switches the gizmo to **Move position**: arrows and squares on the primary display's position move the entities of
the selection while their models stay where they are. Each display's translation takes the move back, turned into its
own axes (yaw and pitch), and the server saves both in one change. Snapping is the editor's (**block**: the middle of a
block's floor; ++ctrl++ for none); a furniture's root moves by whole blocks only, as the plugin places it again on its new root block (a new uuid,
still selected). ++p++ again brings the gizmo back.

## The panel

++n++ opens a panel beside the world, its tabs those of the primary entity's type, then the tabs every entity has; at
its bottom, the entities nearby. The panel opens on the tab you used last when the entity has it. Each change is one
undo step, and what a tab changes goes to every selected entity it is for, unless said otherwise. Numbers with a usual
range (rotation, scale and facing, view range, brightness, shadow, line width, max health, scale, count, size, yaw) are
sliders: drag one and it applies when you let go, or click it to type any value (Enter applies).

| Tab | For | Shows and changes |
|---|---|---|
| **Transform** | displays | The exact pose: position, offset (the transformation's translation), rotation (yaw, pitch and roll of its left rotation, in degrees), scale, facing (the display's own yaw and pitch); the actions below, and the gizmo's mode, axes and pivot |
| **Display** | displays | View range (1 is 64 blocks, within the server's tracking range), brightness (block and sky light, 0 to 15; empty for the world's), shadow radius and strength, glow colour (`#RRGGBB`, empty for the team's), glowing, billboard; an item display's item, its `item_model` and how it is shown (its display context); a block display's block (`oak_stairs[facing=east]`) |
| **Text** | text displays | Text (MiniMessage; `<newline>` for a new line), line width, background colour (`#AARRGGBB`), alignment, see-through, shadow, default background |
| **Mob** | living entities (not armor stands) | Health, max health and scale (attribute base values), AI (off: it stands still), and where the entity has them persistence (never despawns) and baby |
| **Interaction** | interactions | Width and height, whether it responds to clicks |
| **Armor stand** | armor stands | Yaw (the primary only), invisible, small, arms, base plate, marker |
| **Frame** | item frames, glow item frames | Item (an id; empty takes it out), rotation, whether the frame is visible, fixed |
| **Item** | dropped items | Item, count, pickup delay (32767: never), never despawns |
| **Painting** | paintings | Variant (`minecraft:kebab`; one this server knows) |
| **Entity** | every entity | Custom name (MiniMessage; empty: none), position (the primary only; a display's is in Transform), name shown, glowing, silent, gravity, invulnerable, saved with the world |
| **Tags** | every entity | The primary's scoreboard tags: add one, × removes one, on every selected entity |
| **Data** | every entity | The primary's persistent data (the keys plugins keep on entities), each with its type and value. Click one to put it in the editor, type a key (`namespace:key`), its type (string, int, long, double, float, short, byte, boolean) and its value, and **Set** sets it on every selected entity; × removes a key. Containers, lists and arrays are edited in the NBT tab (`BukkitValues`) |
| **NBT** | every entity | The primary's whole NBT as SNBT, what `/data get entity` shows, a top-level key per line. Edit it and apply (++ctrl+enter++): the keys you changed are merged in (`/data merge`), those you deleted removed (`/data remove`), on the primary only. A bad SNBT says where it is wrong and changes nothing; the server refuses changing the UUID, the type or the world, removing the UUID or the position, and changing a player, and says why. **Reload** shows the entity's NBT now, dropping your edits |

In the value tabs, type values and press ++enter++: the rows you changed are saved, the rest stays. Switches and choices
save at once. Their values are read from what the server says of the entity (its NBT, name, text and health), and
changed by `edit_entities`: an NBT change, its name, text, persistence, attributes or health.

The strip at the bottom shows the 5 entities nearest to you (**Me**) or to the selected entity (**Selection**): click one
to select it, Shift+click to add it or take it out. **More** makes it a longer list that scrolls, the 50 nearest within
32 blocks (its header says `50 of 213` when there are more), **Less** the 5 again; **Hide** folds it to its header
line, **Show** brings it back. The panel keeps your choices while the game runs.

The Transform tab's buttons, most with a key while the editor is on:

| Button | Key | Does |
|---|---|---|
| No turn | | Resets the rotation |
| Scale 1 | | Resets the scale |
| Duplicate | ++equal++ | A copy of each selected entity in place, selected: move them away |
| Drop | ++end++ | Each display down onto the surface under it |
| Undo | ++z++ | Your last change on the server |
| Delete | ++delete++ | Removes the selected entities (furniture through its plugin) |

Undo takes back your own last change, never someone else's: the server keeps a history per player. It refuses one that
someone else changed over since, and says who (`/foreman undo force` undoes it anyway); an NBT change undoes to the
whole NBT the entity had, a deletion brings the entities back as they were, with their uuid. Copies are made by
`copy_entities`: the server copies each entity with all its data (every item component, name, tags, persistent data)
under a new uuid. A furniture's copy is a display of its model (another furniture is placed from the model browser).
Players are never deleted nor copied.

These keys are editor mode's while the entity editor is its tool: the game doesn't get them then. Nothing runs while
it is off; the panel reads the primary entity's tags, data and NBT from the server only while it is open.
