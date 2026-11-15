# Editor mode

Foreman's building tools are behind one key: ++b++ turns editor mode on, a small panel on the right of the screen
lists the tools and the keys of the one in use, and ++b++ again turns it off.

Its tools work on what a build is made of rather than on its blocks: saving builds as assets and placing them again,
editing display entities (furniture included) where they stand, and marking the areas, points and locations that
gameplay uses, so you see the layout in the world and the server can attach features to it.

## Turning it on

| | |
|---|---|
| ++b++ | Editor mode on, with the tool you used last (kept after a restart), or off |
| ++1++ to ++9++ | Another tool, by its number in the panel |
| ++esc++ | The tool's own step back first (cancel a drag, unpin, deselect, close a question), else editor mode off. The pause menu doesn't open |

The hub opens it too: the **Use** buttons of the tool cards (Selection, Locations, Library, Displays), **Place** on an
asset or a model, **Edit in world** on an asset, and `/locations editor`, each on its tool. ++b++ is *Editor mode* in
the hub's Keys section and in Minecraft's controls: change it there.

## The panel

At the right edge of the screen, from the top:

- **The tools**, numbered: the one in use is lit, those that can't be used here are greyed (the ones that need the
  Foreman plugin on a server without it; the WorldEdit tool while WorldEdit CUI is installed).
- **The tool's keys**, each with what it does.
- **The snap key** (++g++) with the step of what the gizmo does now ([below](#gizmos-and-snapping)), and what the tool
  keeps to whole blocks whatever it says.
- **The overlay keys**: ++x++ [hidden blocks](#hidden-blocks), ++l++ [displays per chunk](#displays-per-chunk), each
  with whether it is on.
- **The editor's own**: ++b++ leaves, ++esc++ steps back.

++f1++ hides the panel with the rest of the HUD; a screen (the hub, a panel, chat) covers it.

## The status card

At the top left of the screen, in the same look as the panel, the tool says what it has and what to do now:

- **What is selected**: its kind in a chip, then its name and what tells it apart. An asset area `cottage`, a pasted
  copy `cottage-2` *of asset cottage*, an item display `example:lamp`, a furniture `chair`, an area `market` (*build claim ·
  zone*), a point `loot:container`, a location, a selection box `12×5×9`, a measure `12.5 blocks`. With nothing
  selected, what to do first ("Click an asset area or a pasted copy to select it").
- Its details (size, where it is, tags, the gizmo's mode), and **Looking at** what the crosshair is on besides it.
- **What the mouse and keys do now**, each input in a chip: *Left click* select it, *Right click* pin it here, paste it,
  place it, *Drag* an arrow moves it, *Scroll* turn it, a key and what it does. They change with what you aim at: over
  a gizmo handle, what dragging it does; while dragging, ++ctrl++ (free) and the scroll wheel (one step more).
- **A question waiting for you** on a highlighted row: "Pinned: right click to paste it", "Delete the copy of cottage
  from the world?" (right click yes, left click or ++esc++ no), "Press Delete again to delete market".
- The last change's outcome (saved, refused and why) and what is on its way.

++f1++ hides it with the HUD.

## The tools

| # | Tool | For | Keys |
|---|---|---|---|
| 1 | [Selection](selection.md) | A box for the AI or your commands | ++enter++ ask a chat about it, ++delete++ clear it, ++n++ the hub's Selection page |
| 2 | [Display editor](displays.md) | Select item, block and text displays, move, turn and scale them | ++v++ mode, ++o++ axes, ++period++ pivot, ++n++ values, ++equal++ duplicate, ++delete++ delete, ++z++ undo, ++end++ drop, ++i++ select in the box, ++p++ move position |
| 3 | [Place model](models.md#placing) | An item model placed as a display or furniture | ++r++ turn, ++enter++ place, ++shift+enter++ place and edit, ++n++ the model browser |
| 4 | [Place asset](library.md#placing) | An asset of the library pasted in the world | ++r++ turn, ++enter++ paste, ++n++ the library |
| 5 | [Asset tool](library.md#the-asset-tool) | Make assets, move, turn and resize them with their content; turn, sync and delete their copies | ++c++ new asset from the selection, ++n++ panel, ++r++ turn, ++f++ fit, ++u++ sync a copy, ++delete++ delete |
| 6 | [Areas and points](regions.md#the-tool) | The server's areas, marks and points, for gameplay | ++n++ panel, ++m++ new area from the selection, ++p++ new point, ++delete++ delete |
| 7 | [Measure](#measuring) | Lengths, offsets and block counts between two points | ++backspace++ remove the last, ++delete++ clear them all |
| 8 | [Location editor](locations.md#editing) | Plugin locations and your own sets | ++k++ / ++u++ / ++j++ to your feet / eyes / block, ++h++ gizmo on or off, ++n++ sources and categories |
| 9 | [WorldEdit selection](selection.md#the-worldedit-selection) | The WorldEdit selection in any shape | ++n++ the hub's Selection page |

The clicks, the gizmo and the scroll wheel are each tool's own: see its page. The panel always shows the keys of the
version you run.

New things are made from the one [selection](selection.md): select a box with tool 1 (Foreman's selection, else
WorldEdit's is taken), then press the tool's key: ++c++ in the asset tool makes an asset of it, ++m++ in the areas tool
an area. Their panels say so when there is no selection, with a button to the selection tool.

## Gizmos and snapping

Every tool that moves, turns or scales something does it with the same gizmo, grabbed with the crosshair: arrows move
along an axis, squares in a plane, the knob at its center anywhere, rings turn, knobs resize a box or scale. Points and
locations show their arrows and a ring around them at once (the ring turns where they face); displays switch between
moving, turning and scaling. Scrolling while you drag adds a step; dragging the center knob, it moves across your view
(snapped on every axis) and the scroll wheel pushes it farther (up) or pulls it nearer (down). Holding ++ctrl++ while you drag a box's knob moves the opposite face too, the
other way: the box grows or shrinks around its center (selections, WorldEdit's, areas, assets). The status card says
what dragging the handle under the crosshair does.

How drags snap is one setting for the whole editor, kept after a restart. One key, ++g++, cycles the step of what the
gizmo does now, and the panel shows only that one:

| The gizmo | ++g++ cycles | Steps | The panel says |
|---|---|---|---|
| Moves (arrows, squares, the center knob) | the move step | free, 1/16, 1/8, 1/4, 1/2 of a block, **block grid** | `Snap: 1/4 block` |
| Turns (rings) | the turn step | free, 5°, 15°, 22.5°, 45°, 90° | `Snap: 15°` |
| Scales | the scale step | free, 1/16, 1/4, 1/2 | `Snap: 1/16` |

The display editor's gizmo is in one mode at a time (++v++): ++g++ follows it. Points and locations show their arrows
and their ring together: the ring under the crosshair (or being dragged) is turning, anything else moving, so aim at the
ring and press ++g++ to change the turn step. The model placement tool turns with the scroll wheel and scales with
++alt++ + scroll: ++g++ changes the step of what you just did (the scale step while ++alt++ is held), else the move step.

A step rounds what the drag adds: something at 3.3 moved with 1/4 goes to 3.55, 3.8… **Block grid** puts the position
itself on the block grid: a block display's origin on block corners, the other displays, points, locations and models
on the middle of a block's floor (`x.5 y x.5`), a display's centre pivot on block centres. Hold ++ctrl++ while you drag
to move, turn or scale freely; let go and the snapping is back.

Some things are made of blocks and move by whole blocks whatever the setting (the panel says so under the snap
key): boxes (the selection, areas, assets), assets being placed, furniture and block locations. The model placement
tool follows the move snap too: centered on the block with **block grid**, else where the crosshair is, to the step;
its scroll turns by the turn step and scales by the scale step. So does the measure tool's points.

## Measuring

The measure tool (7) measures between two points: left click where it starts, left click where it ends. A line joins
them with its length, how far it goes along each axis (`dx +3 · dy 0 · dz +12`, drawn as red, green and blue legs) and
how many blocks it spans, both ends counted (`4 × 1 × 13 blocks`): the blocks at its ends are outlined. Until the
second click the line follows the crosshair with its numbers.

A point is where the crosshair meets a block, as the move snap says: on the **block grid**, the centre of the block
looked at; with a step, the point of the face rounded to it; ++ctrl++ held, exactly there. Looking at the sky, it is in
the air as far away as the start. ++shift++ on the end click goes on from there (a chain of measures); right click or
++esc++ cancels the one being made. The tool keeps the last 32 measures, the newest in blue, until ++backspace++ removes
the last, ++delete++ clears them or you leave the server; its status card shows the last one.

## Hidden blocks

++x++ in editor mode (or **Hidden blocks** on the hub's [Overlays](hub.md#overlays) page, without editor mode) outlines,
around you, the blocks the world draws as nothing, each kind in its colour:

| Colour | What |
|---|---|
| Red | Barriers |
| Yellow | Light blocks, their level written on those near you |
| Violet | Structure voids |
| Teal | Every block state the current resource packs draw as nothing: its model has no faces (a pack that turns some slab, stair or trapdoor states into invisible collision blocks) |

The one under the crosshair is named, with its state and position. The radius (16 to 64 blocks, 32 by default) is on
the Overlays page; the nearest 1500 are drawn. The world is scanned when you turn it on and as chunks come in range, a
few sections a tick, and again where a block changes or the packs reload: nothing runs while it is off.

## Displays per chunk

++l++ in editor mode (or **Displays per chunk** on the Overlays page) outlines each chunk around you that has display
entities (item, block, text) or interaction entities, from its lowest to its highest one, with their numbers on top:
green, yellow from 50, red from 150 (both thresholds on the Overlays page). The HUD gives the totals of what your client
has loaded and the busiest chunk. Counted twice a second while it is on.

## What the game still gets

While editor mode is on (and no screen is open), the editor's keys are taken before Minecraft's key bindings: the tool
keys, the snap key (++g++), the overlay keys (++x++, ++l++), ++1++ to ++9++, ++b++ and ++esc++. So ++f++ fits an asset instead of swapping your hands, ++1++ to ++9++ pick a
tool instead of a hotbar slot, ++c++ doesn't save a hotbar. A key the tool asks for without ++shift++ also works with
it held (flying down while you turn something).

Every other key plays as usual: moving, jumping, sneaking, sprinting, looking around, chat, ++h++ for the hub, ++f3++
and its combinations (++f3+b++ still shows hitboxes). The mouse buttons go to the tool, not the game: nothing is broken
or placed. The scroll wheel goes to the tool when it uses it (turning, nudging a drag), else it changes the hotbar slot.

## Spectator mode

Minecraft hides the crosshair in spectator mode (but over a container). While editor mode is on it stays, since the
tools aim with it: build in spectator, through walls, without breaking anything.
