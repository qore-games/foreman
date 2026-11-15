# Model browser

The hub's **World → Models** section lists every item model the game has loaded: vanilla's and those of the server's
resource packs (each item definition `assets/<namespace>/items/<path>.json` is the model `<namespace>:<path>`, what an
item's `item_model` component names). It draws them in 3D, and places one in the world as a display entity, or as
furniture when the server says the model is a furniture's.

The list comes from your own client, so it works on any server and follows your resource packs: it is read again
after every reload. Placing needs the Foreman plugin on the server.

## Browsing

Each card draws its model as in an inventory, with its name; a **furniture** badge marks the models the server's
furniture shows. The cards are grouped by folder.

| | |
|---|---|
| Search field | Matches model ids (`namespace:folder/name`) and furniture ids |
| Namespace chips | Show or hide a namespace's models. Vanilla's (`minecraft`) are hidden at first: click its chip to show them |
| **furniture** chip | Only the models the server's furniture shows (when the server has furniture) |
| Folder chips | Only one top folder |
| **Refresh** | Asks the server again which models are furniture |

Only the cards in view are drawn, and a card's picture is drawn again only when it changes.

Click a card to open the model as a display entity shows it: drag the picture to turn it (or ++left++ / ++right++,
++up++ / ++down++ to tilt it), click the angle under it to go back to the first one. The page gives its id, folder,
size in blocks as a display, and the furniture that shows it. ++esc++ goes back to the list.

| Button | Does |
|---|---|
| **Place** | Starts the placement tool with the model, as a display |
| **Place as furniture** | Starts it as the furniture (the chips above pick which one when several show the model) |
| **Copy id** | Copies the model's id |

## Placing

**Place** closes the hub and starts the placement tool ([editor mode](editor.md)'s tool 3). A ghost of the model (an item display only your client has) follows the block face you look
at, resting on it, however far: centered on the block with **block** [snapping](editor.md#gizmos-and-snapping), else
where the crosshair is, to the move step (++g++ changes it):

| | |
|---|---|
| Look at a block | The ghost rests on the face you look at: on top of a floor, under a ceiling, against a wall. Its box and an arrow for its front are drawn around it |
| ++ctrl++ | Free: right on the crosshair |
| Scroll | Turn it by the turn step (++g++ just after: changes it; 15° when free; ++shift++: 90°); furniture by its own step |
| ++r++ | Turn it 90° |
| ++alt++ + scroll | Scale it by the scale step (++g++ with ++alt++ held changes it; an eighth when free; ++shift++ + ++alt++: by one) |
| Right click, ++enter++ | Place it |
| ++shift++ + right click, ++shift++ + ++enter++ | Place it and edit it in the display editor, selected |
| Left click, ++esc++ | Leave |

The server spawns the display (`spawn_display`) as one change: `/foreman undo` takes it back. It refuses a placement
that breaks its rules and says why in chat. The tool stays on after a placement, for the next one; the hub's Models
page shows what you're placing and stops it.

The ghost is posed exactly as the placement will be: a model that furniture shows stands as that furniture does (the
server's furniture plugin gives the pose), and as furniture it is placed at its own size, rooted in the block before the
face you look at:

- **On a floor**, turned toward where the ghost's front faces, by the furniture's own steps (4 or 8 a turn): the scroll
  wheel turns it a step.
- **Against a wall, under a ceiling**, when the furniture goes there (its plugin says so): attached to the face you
  look at as a player placing it would, the plugin's way (a lamp out of the wall, a painting flat on it); a wall turns
  it, a ceiling leaves it free to turn. The status card says which side it is on. Furniture that doesn't go there
  stands in the block before the face, as on a floor.

The server places furniture with `place_furniture` (against a face: `against`, `face` and the yaw shown).

++r++ and ++enter++ are the placement keys the [asset library](library.md) uses too; ++n++ opens the Models section
to pick another model. They are [editor mode](editor.md)'s keys while the tool is in use.

!!! note "Needs the Foreman plugin to place"
    Browsing works anywhere. Placing, and knowing which models are furniture, are the plugin's tools: on a server
    without them the buttons say so.
