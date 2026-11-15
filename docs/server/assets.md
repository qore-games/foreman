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
| `paste_asset` | Paste one, its bottom center at a position, turned by quarter turns and [mirrored](#mirrored-pastes); one undo step, recorded as an [instance](#instances) that can be removed, turned or synced with its asset |
| `scatter_assets` | Scatter some over an area, each on the ground, spaced and turned |
| `copy_region` | Copies (or cuts) a box into your [clipboard](#clipboards), which `paste_asset {name: "clipboard"}` pastes |

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

An asset area is its box (the bounds of the area's boxes) with what is in it: the blocks, the entities (item, block and
text displays whatever their transformation, the marker entities where plugins keep data in the world, item frames,
paintings, armor stands, mobs: what a move carries) and the [furniture](../developers/furniture.md). Players, mobs
that would despawn, and entities that aren't saved with the world (the parts plugins spawn again, like hitboxes) are
left out.

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
that block with their yaw turned; a [mirrored](#mirrored-pastes) paste flips it about the same block first. A read is kept 10 seconds (a moved or resized area is read again at once).

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
`target.rotate` how the copy is turned about it, `target.mirror` whether it was mirrored about it first (`target.world`
when it went to another world). The source's min corner lands where that block went: at the copy's max x for a
mirrored paste that isn't turned. It reads, without changing anything:

| | Compared |
|---|---|
| blocks | each block's state with the source's flipped and turned as WorldEdit does it (stairs, doors, rails…) |
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
bottom centre went and how it turned and [mirrored](#mirrored-pastes), the box it occupies, the points and areas it
brought, its entities (the asset's UUID → the copy's), who pasted it when, the blocks it wrote (the asset's blocks that
aren't air, placed: a bit per block of its box, deflated), and a [fingerprint](#changed-copies) of what it left. The
entities it pasted carry the persistent data `foreman:instance` = its id. Undoing the paste removes the instance.

| | |
|---|---|
| `list_instances` | The instances of a world or of every loaded world, nested copies under the copy holding them; `asset` says where an asset is used |
| `instance_info` | One instance, its points, areas and entities, the copy it is nested in and those nested in it, and which were changed since their paste |
| `sync_asset` | After changing an asset: pastes it again over every copy of it in the loaded worlds, nested ones included ([syncing](#syncing-copies)) |
| `sync_instance` | The same for one copy |
| `detach_instance` | Detaches a copy, reversibly: syncs leave it as it is ([detached copies](#detached-copies)) |
| `attach_instance` | Makes a detached copy follow its asset again; `sync: true` syncs it now |
| `forget_instance` | Forgets a copy and those nested in it: their records and their entities' tag go, what they pasted stays as plain content |
| `delete_instance` | Removes the copy and only it, with the copies nested in it: the blocks they wrote become air (the ground it was pasted on, and other blocks they didn't write, stay), their tagged entities go (furniture through its plugin), with their block data, the points and areas they brought and their records |
| `rotate_instance` | Turns the copy in place, 90, 180 or 270° about the bottom centre of its box, with everything in the box, as [`move_asset`](#moving-an-asset) does (the copies nested in it go along); the records follow (rotation, box, points, blocks written, fingerprints). Refused when the turned box lands on another asset area or claim |

Each one that changes something is one undo step, `sync_asset` too whatever the worlds it syncs in. With `format:
json`, an instance is `{id, world, dim, asset, source?: {world, area}, origin, rotate, mirror, detached, parent?, box,
points: [{name, type, kind, position: [x, y, z], yaw?, pitch?}], written, areas, entities, created, by}` (`written`:
how many blocks it wrote, absent for a paste recorded before Foreman kept them: such a copy is deleted without its
blocks); `delete_instance` answers `{deleted: id, instances: [ids]}`, the others the instance as it is now. The client
draws them (bridge layer `instances`, with `parent`, `mirror` and `detached`): detached copies grey, nested ones
thinner.

### Nested copies

An asset area may hold copies of other assets: an apartment asset pasted (twice, once mirrored) inside a building
asset. When the building is pasted, the instances lying in its area become copies nested in the new one: each its own
instance of its own asset (the apartment), its id `<building copy>/<its id in the building>` (`house-1/flat-2`), its
box, rotation and mirror the building's placement composed with its own, its entities tagged with its id, its points and
areas (those named after it) its own. Deeper nesting works the same way (`town-1/house-1/flat-2`). The building copy's
own points, areas, entities and fingerprint leave out its children's.

- A detached instance in the building is plain building content in the copies (it no longer follows its asset), and a
  copy nested under one joins its path with `.` (`house-1/flat-3.lamp-1`).
- An instance changed in the building since its paste gives a nested copy marked as such: `sync_asset` of its asset
  skips it like its source (unless `force`), a sync of the building makes it again as the building holds it.
- Undoing the paste removes the nested copies with it; `delete_instance`, `forget_instance` and `rotate_instance` of a
  copy take those nested in it along; `move_asset` carries the instances lying in the area, nested ones included.

### Syncing copies

`sync_asset {asset}` makes every copy of an asset follow it once you changed it: each of its instances in the loaded
worlds (or `world`), nested ones included, is removed with the copies nested in it and the asset pasted again in its
place, under its id, at its rotation and mirror; the copies nested in it are made again from the asset's current inner
instances. A nested copy is pasted again inside its own box: the rest of the copy holding it doesn't change. It skips,
and lists with the reason:

- detached copies;
- copies [changed since their paste](#changed-copies), or with copies nested in them changed, unless `force: true`
  (their changes are lost);
- copies lying in the asset's own area;
- nested copies whose asset changed size since: it would spill out of its place (sync the asset holding it);
- copies in worlds that aren't loaded (`list_instances` and `sync_asset` see the loaded worlds only).

A copy nested in another one being synced is made again with it. A sync takes out what pastes wrote, so blocks added
by hand where the asset has air stay. It answers what it synced (with the nested copies
made again), what it skipped and why; `format: json` gives `{asset, synced: [{id, world, children}], covered: [{id,
world, with}], skipped: [{id, world, reason}], blocks}`. The asset's world must be loaded (`open_asset_source` loads it
when a plugin loads worlds for Foreman). `sync_instance {id}` does the same for one copy, and refuses one it would skip.

### Changed copies

A paste takes a fingerprint of what it left, once plugins have handled [`RegionCopyEvent`](../developers/asset-library.md)
and the furniture is placed: the blocks it wrote with their states and their [block data](#block-data) (the entity
UUIDs it holds aside: an undo puts furniture back as other entities), and its own entities (type, position, yaw and
pitch, a fixed display's whole pose however it is split between its yaw and its transformation, what a display shows;
only the block of one that physics moves, a boat, a minecart or an armor stand with gravity, which settles after the paste),
the boxes of the copies nested in it left out (a nested copy's sync doesn't make its parent look changed). A copy whose fingerprint no longer matches
was changed: `instance_info` says so, syncs skip it unless `force`. Syncs, `attach_instance`, moves and turns take the
fingerprint again (a copy moved while changed stays changed).

Not counted as changes: block state properties that change without anyone editing the build (`open`, `powered`, `lit`,
`occupied`, `triggered`, `enabled`, `extended`, `distance`, `age`, `moisture`, `power`, `stage`, `snowy`, `has_book`,
`signal_fire`, `locked`, `in_wall`, `tilt`, `hatch`, `berries`, `bites`, `charges`, `honey_level`,
`sculk_sensor_phase`) or as the blocks around change (`shape` of stairs and rails, `waterlogged`, a note block's
`instrument`, `thickness`, `bottom`, `leaves`, `side_chain`, the sides fences, panes, bars, walls, redstone wire, fire,
chorus plants and mushroom blocks connect on, a tripwire's and a hook's `attached`, a chest's `type`), mobs, entities'
persistent data. Not seen: blocks placed where the asset has air
(the paste didn't write them), entities nobody tagged (placed by hand afterwards). A plugin that changes its blocks'
data or its furniture's display over time makes copies look changed. Copies pasted before Foreman took fingerprints
are never counted as changed.

### Detached copies

`detach_instance` keeps the copy recorded, with its areas, points and entities, flagged `detached`: syncs never touch
it, and a sync of the copy it is nested in keeps its box exactly as it is (blocks, block data, entities). Listings show
it, the client draws it grey. Change it by hand at will. `attach_instance` makes it follow its asset again: what it
holds then counts as unchanged, so its next sync replaces it (`sync: true` syncs it now). The copies nested in a
detached copy still follow their own assets: detach them too to keep them. `forget_instance` drops the records instead
(the copy becomes plain blocks for good).

### Mirrored pastes

`paste_asset {mirror: true}` mirrors the asset east–west (x negated about its bottom centre: seen from its front, its
north side, left and right swap) before turning it: with `rotate`, any of the 8 orientations. Blocks flip with their
states as WorldEdit flips them (stairs and their corners, doors' hinges, rails, chests…), their block data goes to the
mirrored blocks, entities' positions and yaws are mirrored, and so are the points (their yaw and the yaw fields of their
data) and areas. A display's model can't be mirrored by an entity's transformation without turning its faces inside
out: its position, yaw and transformation (its offset and rotations) are mirrored around it, a block display's block too
(stairs face the other way, like the blocks around them), so a symmetric model is right and an asymmetric one shows
unmirrored (a chair is a chair; a sign's text still reads). Furniture is mirrored by its root block and yaw:
furniture whose footprint isn't symmetric may take other cells. The instance records `mirror`; syncs, `rotate_instance`
and nesting compose it. Plugins following [`RegionCopyEvent`](../developers/asset-library.md) with its helpers get the
mirror for free.

### Points and areas in assets

Pasting an asset area also makes, in the target world, the [points](world-context.md) and the areas lying inside the
asset's box (the asset's own area aside), turned with the paste: areas are named `<instance>/<name>` and lose the
`asset` tag, points belong to the build `<instance>/<their build>` (`<instance>` when they had none). They are the
instance's: undo removes them with it.

## Clipboards

`copy_region {from, to}` copies a box into the caller's clipboard as it is then, everything an asset area holds (blocks
and their block data, entities, furniture, the points, areas and pasted copies lying in it), and
`paste_asset {name: "clipboard", position, rotate?, mirror?}` pastes it through the same paste as an asset area's, as
often as needed, into any loaded world. `asset_content {name: "clipboard"}` reads it. In the mod, the selection tool's
[++ctrl+c++, ++ctrl+x++ and ++ctrl+v++](../client/selection.md#copy-and-paste) do the same.

- One clipboard per caller: each player's (shared with their AI chats, which call as them), one for the console and the
  server's token. A copy replaces it; it lives in memory until the server stops. `clipboard:<player>` names a player's.
- It is a snapshot: changing or removing the build afterwards doesn't change it.
- `cut: true` also takes it out of the world: its entities (furniture through its plugin), its blocks (to air) and
  their block data, the points in it, the areas and copies lying in it. One undo step. Refused when an asset area lies
  in the box (`move_asset` moves an asset).
- Each paste is an [instance](#instances) of the asset `clipboard`, recorded [detached](#detached-copies) (there is no
  asset to follow: `attach_instance`, syncs and `open_asset_source` refuse it): `clipboard-<n>`, its entities tagged
  with it, so plugins following copies scope its objects to it, its points and areas named after it, undo, delete and
  turn as any copy. The copies of assets lying in the box are pasted as copies nested in it that still follow their own
  assets; a detached copy among them is plain content.

## Open in library

`open_asset_source` takes the player whose session calls it to the area an asset comes from (`instance` or `asset`), in
front of it on its north side, facing it: standing on the ground there when there is some near the asset's floor level,
else in the air at that level just outside the asset, flying (flight is turned on for them, so they don't fall into a
void library world). When that world isn't loaded, a plugin that loads worlds for Foreman
([`ForemanWorldLoader`](../developers/asset-library.md#loading-worlds)) loads it first; without one, it says the world
isn't loaded. The console can't call it: there is no one to move.
