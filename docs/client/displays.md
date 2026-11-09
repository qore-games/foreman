# Display editor

Display entities (item, block and text displays) make most decor and furniture. The **display editor** picks them by
what they draw and moves, turns, scales and stretches them with a gizmo, live, the server saving each change: the
way to perfect where furniture and props sit in a build.

Turn it on in [editor mode](editor.md) (++b++, then ++2++) or from the hub's **World → Displays** section (*Use*). It acts on any
server; saving needs the Foreman plugin and its display tools (`edit_displays`, `spawn_display`, `remove_displays`,
`list_displays`, `undo`).

## Picking

The crosshair picks what a display draws, not a box around its anchor:

- an **item display** by its item model as the client draws it (the model's faces, in its display context, with the
  display's transformation, yaw, pitch and billboard), so you can click through the gaps of a chair to what is
  behind it. Special models (heads, chests, banners) pick by their box.
- a **block display** by its block's shape (a stair is a stair);
- a **text display** by its text and background.

The display under the crosshair is outlined with its label: the furniture it belongs to (from the plugin), else its
item model, block or text. Displays that draw nothing the client can find (an empty item, a block without shape, a
display scaled to nothing) show a small marker on their anchor within 8 blocks, while the editor is on.

| | |
|---|---|
| Left click | Select the display under the crosshair; on nothing, deselect |
| Shift + left click | Add it to the selection, or take it out (Shift or your sneak key) |
| Right click | The next display along the crosshair: what hides behind the first |
| ++i++ | Select every display in your [selection box](selection.md) |
| ++n++ | The panel, with the displays within 64 blocks: click one to select it (Shift adds), even one you can't see |

The last selected display is the primary one: the gizmo takes its axes, the panel shows its values.

## The gizmo

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

++n++ opens a panel beside the world with the primary display's exact values: position, offset (the transformation's
translation), rotation (yaw, pitch and roll of its left rotation, in degrees), scale, and facing (the display's own yaw
and pitch). Type a value and press ++enter++: what you changed goes to every selected display, the rest stays.

Its buttons, most with a key while the editor is on:

| Button | Key | Does |
|---|---|---|
| No turn | | Resets the rotation |
| Scale 1 | | Resets the scale |
| Duplicate | ++equal++ | A copy of each in place, selected: drag them away |
| Drop | ++end++ | Each down onto the surface under it |
| Undo | ++z++ | Your last change on the server |
| Delete | ++delete++ | Removes them (furniture through its plugin) |

Undo takes back your own last change, never someone else's: the server keeps a history per player. It refuses one that
someone else changed over since, and says who (`/foreman undo force` undoes it anyway). Copies are made by `spawn_display`'s `copy_of`: the server copies the entity itself, every item component
included. A furniture's copy is a display of its model (another furniture is placed from the model browser).

These keys are editor mode's while the display editor is its tool: the game doesn't get them then. Nothing runs while
it is off.
