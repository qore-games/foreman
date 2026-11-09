# World context

Each world carries its own settings, saved in the world itself (its persistent data), so they travel with the world
file. The AI reads them with `get_context`; your plugins read them with [`ForemanWorlds`](../developers/foreman-worlds.md).

## Profile

The look and rules the AI follows in a world, and the defaults of generated buildings. Without one it builds neutrally.
Set it with the `world_profile` tool (ask the AI), which merges fields in:

| Field | What |
|---|---|
| `theme` | Text: the look, palette, era, level-design rules |
| `build` | Defaults of `build_structure` (`style`, `roof`, `decay`, `clutter`, `dark`, `furnish`, `room_width`, `floor_height`, `basement`…) |
| `styles`, `uses`, `rooms` | [Building templates](building-templates.md) for this world only |

```yaml
theme: |-
  A coastal trading town, late medieval. Timber-framed houses on stone bases, narrow streets that follow the
  relief, a market square by the harbor. Read the style guide `harbor` before building near the water.
build:
  style: timber
  roof: gable
```

## Areas

Named places ("town_hall", "east_gate"), made with `set_mark`, a tool's `mark` argument or `/foreman mark <name>`:

- **Claims** reserve space for a build, a road or a yard (`claim`, or the `claim` argument of the tools that build), so
  later edits keep off it.
- **Zones** are areas with a `theme`: their own building rules, on top of the world's profile.
- **Tags** label an area (lowercase letters, digits, `_` and `-`, or a plugin's [area type](#layout-types) `ns:name`):
  `set_mark`'s `tags` replaces them,
  `/foreman tag <area> <tag…>` adds some and `/foreman untag <area> <tag…>` removes them. The tag `asset` makes the
  area an [asset of the library](assets.md#assets-from-areas).

The client draws them as the *Areas* overlay, and can tell you when you enter or leave one. `/foreman marks` lists
them with their tags.

### Locks

When several builders share a server, each can lock the area they work in (`lock_area`, `/foreman lock <area>`, or
Lock in the client's [area panel](../client/regions.md)): until they unlock it (`unlock_area`, `/foreman unlock
<area>`), everyone else's Foreman edits that reach into it are refused, with a message naming who holds it:

- the tools that change blocks (edits, terrain, buildings, `worldedit`, pastes, moves and copies of regions), checked on
  their box before they run, and after any edit session on the blocks it actually wrote (an edit that went in anyway
  is taken back);
- assets and their copies: `move_asset`, `rotate_instance`, `sync_instance`, `delete_instance`, `detach_instance`;
- displays and furniture inside it (`edit_displays`, `spawn_display`, `remove_displays`, `place_furniture`,
  `remove_furniture`, `/foreman model spawn`);
- its points, the locked area itself (changed or deleted) and the areas reaching into it (an area around it all, a
  zone holding several builds, stays free);
- `undo` of an earlier change of theirs inside it.

The holder passes, and so do players with `foreman.lock.bypass` (ops by default), the console and the server's token.
With `locks.protect-blocks` (default true) other players can't place or break blocks in it by hand either, nor change
its displays, furniture hitboxes, item frames, paintings or armor stands: a listener Foreman registers only while an
area is locked. Locks are kept with the area, in the world (`locked_by` in the export). Only the holder unlocks;
`unlock_area` with `force` (or `/foreman unlock <area> force`) unlocks anyone's with `foreman.admin`, and tells the
holder. `get_context` and the area lists say which areas are locked, by whom.

## Points

Gameplay spots that generated buildings leave behind, from the `point` entries of the
[room templates](building-templates.md#rooms): containers, spawns… with free type and kind names. A point is an exact
position (decimals) and may face somewhere (`yaw`, `pitch`: where a spawn looks); one recorded at a block (by a build,
by `pos`, or stored before points were exact) stands on the block's bottom centre, `x.5 y z.5`. The `points` tool
lists, exports (JSON), adds (`pos` a block, or `position` exact, with `yaw`, `pitch`), moves (`at` where one is now:
its exact position, or a spot in its block; `position`, `yaw`, `pitch`) and removes them; moves, pastes and turns of
what they lie in carry them, turned. The client draws them as the *Points* overlay; your plugins read them (fill
containers, spawn NPCs) with `ForemanWorlds`. A point of a type a plugin declares carries that type's fields in its
`data` ([layout types](#layout-types)).

## Layout types

Plugins declare the point types and area types they read, with the fields each holds
([`ForemanLayout`](../developers/layout-types.md)): a loot plugin's `loot:container` with its loot table, a lights
plugin's `lights:lamp` with its colour and radius. Ids are namespaced (`ns:name`); plain names (`container`,
`spawn`) stay free text that nothing checks.

- A **point** is of the type its `type` names; its `data` holds the type's fields. `points` `add` takes `data` with
  each item; `move` changes it (`data` merged key by key, `null` removes a key; `replace_data: true` replaces it whole),
  with or without moving the point.
- An **area** is of every type its claim kind or one of its tags names (`set_mark` `tags: ["mobs:spawn_zone"]`): an area
  can be several things at once, its one `data` object holds the fields of all of them. `set_mark` `data` merges into
  it the same way (`replace_data` replaces it).
- Data that doesn't fit its types is refused when it's written: a value of the wrong kind or out of range, a choice
  that isn't one, a required field missing, a field the types don't have. A namespaced type no plugin declares is
  accepted, with a note: the plugin may not be on this server.
- `check_layout` checks a whole world: unknown namespaced types, data that doesn't fit (a plugin changed its fields
  since), area names a field gives that don't exist, and each plugin's own check ("a spot needs a yaw"), with where
  each problem is. `layout_types` lists the declared types and their fields.
- Data is opaque to pastes and moves: it is copied as it is, except fields of type `yaw` (a direction), turned with
  the asset or region like the point's own yaw.

The client gets the declared types (the bridge's `layout` layer) for its forms. Data is stored with the point or area,
in the world and in the YAML export.

## Export and import

`/foreman world export [world]` writes the profile, areas and points to `plugins/Foreman/worlds/` as YAML, and
`/foreman world import [world]` loads them back. `/foreman world show` prints the profile.
