# Assets

`plugins/Foreman/assets/` holds your schematics for the AI: houses, stalls, trees, rocks, ruins… WorldEdit's
schematics (`FastAsyncWorldEdit/schematics`) are listed too, and [areas tagged `asset`](#assets-from-areas), with
their furniture and display entities.

| | |
|---|---|
| `/foreman asset save <name> [tags…]` | Saves your WorldEdit selection |
| `save_asset` | The AI saves a box of the world |
| `/foreman asset new <name> [tags…]`, `define_asset` | Makes an [asset area](#assets-from-areas) (of your WorldEdit selection) |
| `index_assets` | Reads new or changed schematics: size, main blocks, a guessed category (tree, plant, rock, ruin, structure) |
| `list_assets`, `preview_asset` | Browse them (`format: json` for programs), render one from four sides, or `views` square images around it |
| `asset_content` | What one holds, for a program to draw it (the mod's [placement ghost](../client/library.md#the-ghost)) or for the AI to read it without a render: see [below](#asset-content) |
| `paste_asset` | Paste one, its bottom center at a position, turned by quarter turns; one undo step, recorded as an [instance](#instances) that can be removed, turned or synced with its asset |
| `scatter_assets` | Scatter some over an area, each on the ground, spaced and turned |

Schematics can sit in subfolders (`houses/small_cottage`); folders starting with `_` are left out of listings but
can still be pasted by name. WorldEdit's own schematics are listed as `fawe/<name>`, read only.

A `<name>.yml` next to a schematic describes it for the AI:

```yaml title="plugins/Foreman/assets/houses/small_cottage.yml"
description: A one-room cottage with a chimney, door to the south
tags: [house, rural]
category: structure
```

## Assets from areas

Build the asset in a world (a library world of your own, say), draw an [area](world-context.md#areas) around it and tag
it `asset` (`set_mark` with `tags`, or `/foreman tag <area> asset`): it is in the library while its world is loaded, as
`area:<world>/<area>`, the area's note its description, its other tags its tags.

An asset area is its box (the bounds of the area's boxes) with what is in it: the blocks, the display entities (item,
block and text displays, whatever their transformation) and the [furniture](../developers/furniture.md). Entities
that aren't saved with the world (the parts plugins spawn again, like hitboxes) are left out.

- `list_assets` gives its size, main blocks, a guessed category, and how many furniture and other displays it holds.
- `preview_asset` renders it alone, nothing around it. `views: 1` is a thumbnail; renders are kept a minute.
- `paste_asset` copies it into any loaded world: the blocks turned, each display with its whole pose turned around the
  paste, each furniture taken in by its plugin (or placed again through it). Undo removes them all.

Nothing is saved apart from the world: change the build and the next paste has it (listings and renders within a
minute).

## Asset content

`asset_content {name, max_blocks?, format?}` reads a schematic or an asset area as a client draws it. Its text is a
summary (size, block counts, the kinds of blocks, each display); `format: json` gives everything:

| Key | |
|---|---|
| `asset`, `size` | Its id, `[x, y, z]` |
| `palette` | The block states it holds (`minecraft:oak_stairs[facing=north,…]`) |
| `blocks` | A flat list, four numbers a block: `x, y, z` from its min corner, and the index of its state in `palette`. Only blocks that aren't air with a face in the open: those whose six neighbours are full blocks are left out. Barriers, light blocks and structure voids are left out too (they draw nothing) |
| `listed`, `hidden`, `total`, `truncated` | Blocks listed, left out as enclosed, that aren't air; `truncated` when the list stopped at `max_blocks` (default and at most 30,000) |
| `displays` | Its item, block and text displays: `type`, `pos` (from its min corner), `yaw`, `pitch`, `transformation` (`translation`, `left_rotation`, `scale`, `right_rotation`), `billboard`; an item display's `item` (`id[components]`, as commands write it), `model` and `item_display`; a block display's `block`; a text display's `text` (a JSON component), `line_width`, `background`, `opacity`, `shadow`, `see_through`, `default_background`, `alignment` |

Everything is as saved: `paste_asset` turns it about its bottom center (the block at `(size - 1) / 2` on x and z,
rounded down, at its floor), blocks with their states turned as WorldEdit turns them, displays about the center of
that block with their yaw turned. A read is kept 10 seconds (a moved or resized area is read again at once).

## Defining asset areas

`define_asset` (or `/foreman asset new <name> [tags…]` on your WorldEdit selection) makes an area tagged `asset`, or
gives an existing one a new box, tags and note:

- `fit: true` shrinks the box to what it holds (blocks that aren't air, entities, points) plus one block, within the box
  you gave. `fit_asset` (`/foreman asset fit <name>`) does it again later, `margin` blocks around.
- `plot: true` keeps only the box's size and puts it on the next free plot of the world, to build a new asset in. Plots
  form rows from the north-west corner of the world's asset areas, on the floor of the lowest one: the first spot,
  row by row (north to south) then west to east, that keeps 8 blocks from every area of the world, in rows 256 blocks
  long. In a world without asset areas, the box stays where you gave it.
- `resize_asset` sets the box, nothing else: the build stays where it is.

Each is an undo step. With `format: json` they answer `{id, box: [x1, y1, z1, x2, y2, z2]}`.

## Moving an asset

`move_asset` moves an asset area and everything in it within its world, `to` a new min corner or `by` an offset,
`rotate` turning it about its bottom centre first:

- the blocks: the source becomes air, the destination box takes them (air included);
- their [block data](#block-data);
- every entity standing in the box, teleported: each keeps its UUID, its position and yaw turned with the move; riders go
  with what they ride, item frames and paintings turn their face. Players standing in it go along too (and back on
  undo) rather than over the air it leaves. Display entities keep their
  transformation: the turn of their yaw turns the whole model, tilted ones included. Furniture plugins follow through
  [`RegionCopyEvent`](../developers/asset-library.md);
- the points (their exact position turned like an entity's, their yaw too), the other areas and the
  [instances](#instances) lying inside the box (their box, points and the blocks they wrote turned), then the area
  itself.

It refuses a destination that overlaps another asset area or a claim (the areas moving with it, and those the box stands
in, aside) and boxes bigger than `limits.max-edit-blocks`. One undo step moves it all back.

## Checking a copy

`diff_regions` compares a box with where it was copied or moved: `target.origin` is where the box's min corner landed,
`target.rotate` how the copy is turned about it (`target.world` when it went to another world). It reads, without
changing anything:

| | Compared |
|---|---|
| blocks | each block's state with the source's turned as WorldEdit turns it (stairs, doors, rails…) |
| block data | the [block data](#block-data) entries, their content (a missing or extra entry is a difference) |
| entities | paired by type and turned position: yaw (turned) and pitch, custom name; a fixed display's pose (its yaw, pitch and transformation together, turned: the same model however the turn is split between them; other billboards: the transformation), billboard, brightness, view range, size, shadow and glow, and what it shows (item with its components, block state, text and its settings); an interaction's size; a hanging entity's facing (turned) and an item frame's item; their persistent data |
| furniture | as its plugin sees it: type at the turned root, yaw (turned), state, cells (turned), whether it is interactive |
| points | type and kind at the turned position, yaw (turned), pitch, room |
| areas | lying inside the box, an area spanning the whole box (the asset's own) aside: paired by turned boxes, then kind, note, theme and tags (`asset` aside) |

In data (persistent data and block data), a source entity's UUID matches its counterpart's, a number that is the
source's turned by the rotation and whole turns (a yaw) and a face name turned with it count as the same, and Foreman's
own `foreman:instance` tag is left out. An interaction's yaw and pitch aren't compared: its box is along the axes. Not
compared: block entities' contents (chests, signs), biomes, names of points and areas (copies rename them), entities'
UUIDs, an instance's record. It answers the counts per kind (compared, same, differ, missing, extra) and the first
differences (`limit`) with their coordinates; `format: json` for programs. Boxes are limited to
`limits.max-edit-blocks`.

## Block data

Many plugins keep data on blocks with the CustomBlockData library: an entry of the chunk's persistent data per plugin
and block, keyed `<namespace>:x<x>y<y>z<z>` (x and z within the chunk), with the library's `customblockdata:protected`
flag inside. WorldEdit copies blocks without it. Foreman's copies carry every namespace's entries to the blocks they go
to (turned, into another chunk or world): `paste_asset` of an asset area, `move_asset` (which removes them at the
source and clears the destination's old ones) and `transform_region`. Undo puts back every entry it touched. Data that
names other blocks or entities still names the old ones: plugins fix theirs on
[`RegionCopyEvent`](../developers/asset-library.md). Schematics (and so `scatter_assets`) hold no block data.

## Instances

Every `paste_asset` records an instance in the world it pastes into, kept in the world's data like its areas (and in
`/foreman world export`): its id (`<asset>-<n>`, unique in the world), the asset, the area it came from, where its
bottom centre went and how it turned, the box it occupies, the points and areas it brought, its entities (the asset's
UUID → the copy's), who pasted it when, and the blocks it wrote (the asset's blocks that aren't air, turned: a bit per
block of its box, deflated). The entities it pasted carry the persistent data `foreman:instance` = its id. Undoing the
paste removes the instance.

| | |
|---|---|
| `list_instances` | The instances of a world or of every loaded world; `asset` says where an asset is used |
| `instance_info` | One instance, its points, areas and entities |
| `detach_instance` | Forgets one: its record and its entities' tag go, what it pasted stays |
| `delete_instance` | Removes the copy and only it: the blocks it wrote become air (the ground it was pasted on, and other blocks it didn't write, stay), its tagged entities go (furniture through its plugin), with their block data, the points and areas it brought and its record |
| `rotate_instance` | Turns the copy in place, 90, 180 or 270° about the bottom centre of its box, with everything in the box, as [`move_asset`](#moving-an-asset) does; its record follows (rotation, box, points, blocks written). Refused when the turned box lands on another asset area or claim |
| `sync_instance` | Replaces the copy with its asset's current content, at the same place and rotation and under the same id: `delete_instance`, then a paste. What was changed inside the copy since it was pasted is lost. Fails when the asset, or the world it comes from, isn't available |

Each of the last three is one undo step. With `format: json`, an instance is `{id, world, dim, asset, source?: {world,
area}, origin, rotate, box, points: [{name, type, kind, position: [x, y, z], yaw?, pitch?}], written, areas, entities,
created, by}` (`written`: how many blocks it wrote, absent for a paste recorded before Foreman kept them: such a copy is
deleted without its blocks); `delete_instance` answers `{deleted: id}`, the others the instance as it is now. The client
draws them (bridge layer `instances`).

### Points and areas in assets

Pasting an asset area also makes, in the target world, the [points](world-context.md) and the areas lying inside the
asset's box (the asset's own area aside), turned with the paste: areas are named `<instance>/<name>` and lose the
`asset` tag, points belong to the build `<instance>/<their build>` (`<instance>` when they had none). They are the
instance's: undo removes them with it.

## Open in library

`open_asset_source` takes the player whose session calls it to the area an asset comes from (`instance` or `asset`), in
front of it on its north side, facing it: standing on the ground there when there is some near the asset's floor level,
else in the air at that level just outside the asset, flying (flight is turned on for them, so they don't fall into a
void library world). When that world isn't loaded, a plugin that loads worlds for Foreman
([`ForemanWorldLoader`](../developers/asset-library.md#loading-worlds)) loads it first; without one, it says the world
isn't loaded. The console can't call it: there is no one to move.
