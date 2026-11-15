# Asset library

A server with the Foreman plugin keeps a library of **assets**: builds its players made in the world and marked as
assets, and the schematics of its [schematic library](../server/assets.md). The hub's **World → Library** section shows
them as cards, lets you turn one around, and places it in the world for you.

## Making an asset

An asset is a Foreman area tagged `asset`: a box of the world around a build. Make one with the **asset tool**
(below): select the build (Foreman's [selection](selection.md) or WorldEdit's), press ++c++ in the asset tool (or
**+ New asset** on the Library page), name it, done. The commands work too:

1. Select the build with WorldEdit (or stand where it goes) and name it: `/foreman mark <name> [note]`. The note is
   the asset's description.
2. Tag it: `/foreman tag <name> asset`. Its other tags (`/foreman tag <name> tree`, `ruin`…) are the asset's tags,
   used to filter the library.

The area's overlay label shows its tags (`rock_1 #asset #stone`). An asset stays in its world: what you paste is what
the area holds when you paste it (its blocks and entities), so fixing the build fixes the asset. Schematics saved with `/foreman asset save` or put in the plugin's `assets/` folder are listed too.

## The asset tool

The asset tool makes assets and changes them where they stand. Turn it on in [editor mode](editor.md) (++b++, then
++5++), from the Library page's card (**Use**, or **+ New asset**), or from an asset's page (**Edit in world**). While it is on, the
asset areas show with their names, the [pasted copies](#pasted-copies) with theirs, and the HUD says what the clicks
and keys do.

### A new asset

A new asset is your selection: there is one way of selecting things in the editor. Select the build with the
[selection tool](selection.md) (++1++: left and right click its corners, drag the knobs to fit it; WorldEdit's
selection is taken when you have no Foreman one), switch to the asset tool (++5++) and press ++c++ (**New asset from
the selection**), or press **+ New asset** on the Library page. Its panel opens with the selection's size; without a
selection it says so, with a **Selection tool** button that takes you there.

The panel names it:

| | |
|---|---|
| Name | One word: its name in the library (`area:<world>/<name>`) |
| Tags | Separated by spaces: `tree ruin`. The library filters by them |
| Note | What it is: its description in the library and for the AI |
| Fit the box to its content | Shrinks the box to what it holds, a block around |
| On the next free plot | A box of this size on the world's next free plot (a grid beside the other assets), and takes you there, to build the asset in it. What your box holds stays where it is |

++enter++ makes it, ++esc++ closes the panel (your selection stays).

### Changing an asset

Click an asset to select it (the one you look at, or the one you stand in):

| | |
|---|---|
| Drag a face knob | Resizes its box, by whole blocks. Only the box changes: what the world holds stays |
| Drag an arrow | Moves it **with everything in it**: blocks and their data, entities (furniture, displays… keeping their identity), its points, the areas and copies inside it. A move of more than 50,000 blocks asks first: right click goes ahead, left click or ++esc++ puts it back |
| ++r++ | Turns it a quarter clockwise in place, content and all (asks first when big) |
| ++f++ | Fits its box to its content, a block around |
| ++delete++ | Deletes its area, after a yes (right click): the build stays in the world |
| ++n++ | Its panel |
| ++esc++ | Deselects it; again, leaves the tool |

The server refuses a move onto another asset or a claim, and says why. Each change is one undo step of yours
(`/foreman undo`). The panel edits its name, tags and note (++enter++ saves), and has **Fit**, **Turn**, **Delete
area** (deletes the area only: the build stays in the world), its page in the library, and the list of its copies.

## Pasted copies

The server remembers each paste of an asset: its copy's box, the asset and area it came from, its turn, the points it
brought, who pasted it and when. The **Pasted copies** overlay (hub, Overlays) draws them, dimmer than the areas,
labelled `asset · turn · who`, their points as small markers with their names; the asset tool always shows them.

With the asset tool, click a copy to select it (the one you look at, or the one you stand in). Its keys work on the
selected copy, else on the one you look at:

| | |
|---|---|
| ++r++ | Turns it a quarter clockwise in place, content and all (asks first when big) |
| ++u++ | Syncs it with its asset, after a yes (right click): the copy is replaced with the asset's current content, so edits made inside the copy are lost |
| ++delete++ | Deletes it from the world, its blocks and points, after a yes (right click) |
| ++n++ | Its panel |
| ++esc++ | Deselects it |

Its panel shows its asset and source, where it is and how it is turned, who pasted it and when, its points, and:

| | |
|---|---|
| Open in library | Takes you to the asset's area, in its world (the server loads it when a plugin loads worlds for Foreman) |
| Select | Makes its box your Foreman selection |
| Teleport | On top of it (`/tp`) |
| Detach | Forgets that it is a copy: it stays in the world as plain blocks and entities (click twice) |
| Turn 90° | A quarter clockwise in place |
| Sync | Replaces the copy with the asset's current content: edits made inside the copy are lost (click twice) |
| Delete | Takes it out of the world (click twice) |

Each is one undo step of yours (`/foreman undo`). A plugin older than the mod answers Turn, Sync and Delete with an
error, shown in the panel.

An asset's page in the Library lists where it was pasted under **Used in**: each copy's world, position, turn and who
pasted it, with its panel (**Open**) and a teleport (**Go**).

## Browsing

The Library section lists the assets by world (yours first), then the saved schematics, each card with a picture
the server renders, its name, size and tags. The search field matches names, worlds, tags, blocks and descriptions;
the tag chips above the cards keep only the assets with those tags. Pictures are asked for the cards in view only, a
few at a time, and kept until you leave the server; **Refresh** lists the assets again and renders them anew.

Click a card to open the asset: drag its picture left or right (or press ++left++ / ++right++) to turn it around
through eight views, read its size, contents and tags, and where it was pasted (**Used in**). **Refresh** there
renders it again after a change; **Edit in world** turns the asset tool on with it selected, **Go there** takes you to
it; ++esc++ goes back to the list.

## Placing

**Place** closes the hub and starts the placement tool ([editor mode](editor.md)'s tool 4; ++n++ there opens the
library to pick another):

| | |
|---|---|
| Look at a block | The asset's box follows: its bottom center on the face you look at, however far. An arrow on the ground shows where its front faces |
| Scroll, ++r++ | Turn it a quarter (it starts with its front toward you) |
| Right click | Pin it where it is: the gizmo appears, drag an arrow or a square to nudge it a block at a time (scroll while dragging: one more block) |
| Right click again, ++enter++ | Paste it. ++enter++ pastes at once, pinned or not |
| ++z++ | Undo your last change: the last paste, most often |
| Left click | Unpin it; while it follows, leave the tool |
| ++esc++ | Leave the tool |

The selection tool's ++ctrl+v++ places your [clipboard](selection.md#copy-and-paste) with this tool the same way, as it
was copied (not turned toward you); leaving goes back to the selection tool.

### The ghost

While you place it, a **ghost** of the asset stands in its box: what the paste would put there, turned as it will
be. Its blocks show where they will land (stairs, logs and other turned blocks facing the way the paste turns them),
with its furniture and display entities posed as they will be. It glows, shows full bright and its blocks are a
little smaller than a block, so you tell it from the world. The ghost exists only in your game (the server and other
players never see it) and goes when you leave the tool or pick another asset.

The server sends the asset's content when the tool starts (`asset_content`): its blocks with a face in the open (those
buried inside full blocks don't show anyway), its displays. Up to 5,000 such blocks make a ghost; a bigger asset shows
its box only, and the tool says so. The ghost isn't see-through: what stands where it goes shows through its gaps only.
A plugin older than the mod has no `asset_content`: the tool says so and shows the box.

The server pastes it, blocks and entities, as one change: `/foreman undo` takes it back. It refuses a paste that
breaks its rules (claims, size limits) and says why in chat. The tool stays on after a paste for the next one; the
hub's Library page shows what you're placing and stops it.

These keys are [editor mode](editor.md)'s while their tool is in use, and the game doesn't get them then (++f++
doesn't swap your hands, ++c++ doesn't save a hotbar). An asset always goes on whole blocks, whatever the
[snapping](editor.md#gizmos-and-snapping).

!!! note "Needs the Foreman plugin"
    The library, its pictures, pasting, the asset tool and the copies are the plugin's world tools: on a server without
    them (no plugin, or a production preset) the section says so. A plugin older than the mod answers the tool's calls
    with an error, shown in the panel.
