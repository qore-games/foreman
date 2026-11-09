# Areas and points

A server with the Foreman plugin keeps, in each world, named **areas** and gameplay **points** (its
[world context](../server/world-context.md)). The **areas and points** tool of editor mode shows them where they are and
edits them in place: drag an area's box, rename it, tag it, make it a zone or a claim, place a point on a block.

## What they are for

Areas and points describe a map's layout in words a person, an AI and a plugin all read:

| | What | Used for |
|---|---|---|
| **Area** (a mark) | A named box, or several (`town_hall`, `east_gate`), with a note | Knowing what is where: the AI reads them with `get_context` and builds around them, the client names the ones you stand in |
| **Zone** | An area with a theme: its own building rules (palette, era, what goes there) | Building a district differently from the rest of the world |
| **Claim** | An area of a kind (`build`, `road`, `yard`, `terrain`…) that reserves its space | Keeping later edits off it: `edit`, `paste_asset` and `place_furniture` refuse to build into another claim |
| **Tags** | Free labels on an area (`#spawn`, `#safe`) | Grouping areas for your plugins; `asset` makes the area an [asset of the library](library.md) |
| **Point** | A precise spot with a `type` and `kind` (`container:chest`, `spawn:boss`), the way it faces, the build and room it belongs to | Gameplay spots: generated buildings record them, you add your own |
| **Typed** point or area | One of a type a plugin of the server declares (`ns:name`), with the values of that type's fields | What a plugin reads with settings: a light's colour and radius, a loot container's table ([below](#typed-points-and-areas)) |

In production they are how gameplay attaches to a map: your plugins read them with
[`ForemanWorlds`](../developers/foreman-worlds.md) and turn them into features (fill every `container` point with loot,
spawn NPCs at `spawn` points, make the areas tagged `safe` a no-PvP region, start music in a zone), and follow
`WorldDataChangeEvent` when someone edits them. The map's builders place and name them here; the code never holds
coordinates. They are kept in the world itself, so they travel with its file.

## The tool

Turn on editor mode (++b++) and pick **Areas and points** with its number. While it is on, every area of the world
shows with its name, kind and tags (areas of a declared type in its colour, claims in their kind's, zones violet, plain
areas cyan, asset areas dimmed) and every point as a small cube with its type (a declared type's label and colour, else
its `type:kind`) and an arrow the way it faces; the other overlays leave them to it. The HUD says what the clicks and
keys do. It needs the Foreman plugin on the server: without it the tool is greyed out.

| | |
|---|---|
| Left click | Select the area box or the point under the crosshair (a point before the area it is in); on nothing, deselect |
| ++n++ | The panel of the selected area or point, or of the new one |
| ++m++ | A new area of your [selection](selection.md) (Foreman's, else WorldEdit's): its panel opens. Without a selection the panel says so, with a button to the selection tool (++1++) |
| ++p++ | A new point where you aim (through grass and flowers); its panel opens, with the list of declared types first |
| ++delete++ | Delete the selected area or point (press it twice) |
| ++k++ | [Check the layout](#the-layout-check): every point and area of the world against the declared types |
| ++esc++ | Cancel a drag, drop the new point, deselect |

To make an area, select its box with the [selection tool](selection.md) (++1++, two clicks, drag its knobs), come back
(++6++) and press ++m++: there is one way of selecting things in the editor.

### Areas

A selected area box shows the gizmo: the **knobs** on its faces resize it, the **arrows** move it. Areas are blocks:
their boxes always move and resize by whole blocks, whatever the [snapping](editor.md#gizmos-and-snapping) (the panel
says so). They change the area only: what is in the world stays where it is (the [asset tool](library.md#the-asset-tool) moves a
build with its content). An area made of several boxes (a claim that grew) edits the box you clicked, the others stay.
The change is saved on release.

The panel edits the rest:

| | |
|---|---|
| Name | Renaming makes the area again under the new name, then deletes the old one |
| Note | What it is, for builders and the AI |
| Tags | Separated by spaces: lowercase letters, digits, `_` and `-` (a declared type's `ns:name` shows under Types instead) |
| Zone | The zone's rules (its theme); empty makes it an ordinary area again |
| Claim | The kind of space it reserves (`build`, `road`, `yard`, `terrain`…); empty: not a claim |
| Types | The [declared types](#typed-points-and-areas) it is, with their forms; **+ Type** adds one |
| Lock | **Lock** keeps everyone else's Foreman edits out of it while you build there (and, on most servers, their own block changes): a [lock](../server/world-context.md#locks), kept until you **Unlock** it. An area someone else locked says who; only they unlock it |

Locked areas show a key and who holds them under their name, in the world and with this tool: green when the lock is
yours, red when it is someone else's. Renaming your locked area keeps it locked.

Deleting an area deletes its name and settings, never blocks. Asset areas show but belong to the asset tool: select one
and the HUD says so.

### Points

Points are precise: a position anywhere in a block, and a facing. A new one (++p++) goes where the crosshair meets a
block, to the move step, or on the middle of the floor of the block before the face with **block** snapping; it faces
away from the wall you aim at, else where you look.

A selected point shows the gizmo: its **arrows** and **squares** move it as the editor's
[snapping](editor.md#gizmos-and-snapping) says (free, by a step, or onto the middle of a block's floor), the **ring**
around it turns where it faces (its yaw, by the turn step). The marker's arrow shows the facing. The change is saved
on release.

Its panel sets its **type** (one from the list, ▾, or your own name) and **kind**, the **build** and **room** it belongs
to (both optional), where it is exactly (**At**: x, y, z) and its **facing**: a yaw in degrees (0 south, 90 west, 180
north, -90 east; empty: none) and a pitch (optional: 0 level, 90 straight down). A declared type adds its form below.
Deleting a point removes that one only: another point on the same block stays.

## Typed points and areas

Plugins of the server may declare the point and area types they read, with the fields each holds
([layout types](../server/world-context.md#layout-types)): a `lights:lamp` point with its colour, radius and strength, a
`mobs:spawn_zone` area with its mob and its cap. The client gets them from the plugin while the tool is on, and makes
a form of each.

- **Points.** The Type field's list (▾) shows the declared point types by the plugin declaring them, with their label
  and colour, then **Free text** for a name of your own (nothing checks those). A new point (++p++) opens the list
  first when the server declares any. Picking a type, or typing its id, shows its form.
- **Areas.** An area is of every declared type its tags (or its claim kind) name: one area can be a spawn zone and a
  music zone at once. **Types** in its panel shows them as chips: **+ Type** adds one (its tag), a click on a chip takes
  it away. Each type has its own section of the form; the area keeps one set of values for all of them (a field two
  types share is shown once).

The form is made from the fields:

| Field | In the form |
|---|---|
| Text | A text box |
| Whole number, number | A number box, its range beside it (`1 – 64`) |
| On/off | A switch |
| Choice | Its choices as buttons, or a list when they don't fit |
| Colour | A swatch and its `#rrggbb` |
| Direction (yaw) | Degrees, with **Facing**: the point's own facing (for an area, where you look) |
| Area | A list of the areas of the world |

A required field has a `*`, its description shows on its label. A field left empty reads its default (shown faint in
the box, or as *default* beside a switch or a choice); × beside a switch, a choice or an area takes the value back to
the default. **Save** writes the form as the whole of the point's or area's data. The plugin checks it: what doesn't fit
(out of range, not one of the choices, a required field missing) is refused, and every reason shows in red under the
field it names.

## The layout check

++k++, or **Check** at the top of a panel, runs the server's layout check on your world (`check_layout`): every point
and area against the declared types. Its list shows each problem, errors first: what type, why (a value out of range,
a required field missing, a type no plugin declares, the plugin's own check, such as a spot light without a facing),
and where. **Teleport** takes you there (the game's `/tp`), **Select** selects it in the tool and opens its panel with
the problems shown on its fields; closing the panel goes back to the list, checked again when you saved. While the
list is open, the markers of points and areas with errors are red, those with warnings amber. **Check again** runs it
again; the plain point types (free text) are counted at the bottom.

## Behind the tool

Each change is one of the plugin's world tools, as the AI would call it: `set_mark` names, moves, notes, tags and themes
an area (and deletes it), with its tags and data, `claim` sets or drops what space it reserves, `points` adds, moves and
removes points and writes their data, `check_layout` checks the world. The plugin pushes the result to every client with
its overlays on, and the declared types to the clients using this tool. Nothing runs while the tool is off.
