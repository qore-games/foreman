# Building templates

`build_structure` builds a whole building in one call: site levelling, foundation, walls, storeys, rooms and doors,
walkable stairs, roof, furniture by room type, then a `check` of the result. Its layouts are built in (the
archetypes); what goes into them comes from templates you can edit:

| Template | What | Where |
|---|---|---|
| **Styles** | Material presets: wall, trim, roof, doors… | `catalog/buildings.yml` `styles:` |
| **Uses** | The room types of a building use, in order | `catalog/buildings.yml` `uses:` |
| **Rooms** | What each room type gets: furniture, blocks, points | `catalog/rooms.yml` |
| **Style guides** | Free text the AI follows in a zone | `styles/<name>.md` |

`list_styles` shows the result in a world: every style, use and room type with what it gets.

## Where they're defined

Later ones win, by name:

1. Foreman's bundled defaults, neutral on purpose (styles `brick`, `wood`, `stone`, `modern`, `industrial`; uses
   `office`, `school`, `hotel`, `apartment`, `dormitory`, `shop`, `workshop`, `basement`).
2. The server's `plugins/Foreman/catalog/buildings.yml` and `catalog/rooms.yml`. Foreman writes them with commented
   examples on first start.
3. A world's profile (`world_profile`: `styles`, `uses`, `rooms`), kept in the world. See
   [World context](world-context.md).
4. One building's arguments: `style` + `materials`, `use` or `rooms`, `catalog`.

You can also ask the AI to write them: "add a timber style and a library use to the catalog".

## Archetypes

| Archetype | Layout |
|---|---|
| `corridor` | Rooms on both sides of a 3-wide corridor, stair halls at the ends (depth 19 or more) |
| `sections` | An apartment block: `sections` stair cores, each between two flats of `flat_width` |
| `hall` | One large room |
| `house` | Rooms of 6 or more around the stairs (13 × 13 or more) |

Other arguments: `floors`, `floor_height`, `roof` (flat, gable, hip), `basement`, `room_width`, `furnish`, `connect`
(paths to the nearest roads), and the optional `decay` (wear, broken windows, vines), `dark` (boarded windows) and
`clutter` (debris that breaks sightlines, a path kept free).

## Styles

```yaml title="plugins/Foreman/catalog/buildings.yml"
styles:
  timber:
    extends: wood          # start from wood, give only what changes
    wall: white_terracotta
    trim: dark_oak_log
    pillar: dark_oak_log
    roof_stairs: dark_oak_stairs
  sandstone:
    extends: stone
    wall: smooth_sandstone
    trim: cut_sandstone
    base: sandstone
    roof_stairs: sandstone_stairs
    parapet: smooth_sandstone_slab[type=bottom]
```

Keys: `wall`, `trim` (floor edges, corners), `base` (foundation, bottom row), `floor`, `slab` (between storeys, flat
roofs), `inner` (inner walls), `paint` (bottom row of the rooms' walls), `roof`, `roof_stairs` (gable and hip roofs),
`parapet` (flat roofs, optional), `window`, `door`, `entrance` (front doors), `stairs`, `pillar` (corners, optional),
`railing`. Values are block states; `none` removes an optional one. A style without `extends` starts from `brick`.

## Uses

```yaml title="plugins/Foreman/catalog/buildings.yml"
uses:
  library: [reading, reading, office, storage]
  tavern: [dining, kitchen, storage, bedroom, bedroom]
```

Rooms get the types in order, repeating. `basement` is the use of basements.

## Rooms

```yaml title="plugins/Foreman/catalog/rooms.yml"
reading:
  - {item: "block:bookshelf", count: 3-5}
  - {item: "block:lectern[facing={facing}]"}
  - {item: "block:oak_stairs[facing={back}]", count: 2-3, place: center}
  - {item: "block:lantern", chance: 0.5}
guardroom:
  - {item: "block:barrel[facing={facing}]", count: 1-2, point: "container:supplies"}
  - {point: "spawn:guard", count: 1-2}
clutter:
  - {item: "block:barrel[facing=up]"}
  - {item: "block:hay_block"}
```

| Key | |
|---|---|
| `item` | `furniture:<id>` or `block:<state>`. `{facing}` is the side its front looks to, `{back}` the wall side. `a\|b\|c` after the prefix picks one per piece |
| `count` | `n` or `a-b` (default 1), for a room of about 50 cells: bigger rooms get more |
| `chance` | 0..1 for each one (default 1) |
| `place` | `wall` (default: back against a wall, clear of doors and windows) or `center` |
| `point` | `<type>:<kind>`: records a [point](world-context.md#points) there for gameplay plugins. Without `item`, on a free floor cell |

Room types are free names. The archetypes use `living`, `kitchen`, `lobby`, `corridor`, `stairwell` and `hall`; uses
name the others. `clutter`, when defined, lists the blocks `clutter` scatters.

## Style guides

`styles/<name>.md` is free text the AI reads (`read_style_guide`) before building where it applies: the mood, the
palette as block ids, sizes in blocks, rules, and the assets and furniture that belong there. Foreman writes
`styles/example.md` as a template on first start. Name the guide in a world's profile or a zone's theme so the AI
knows when to read it.

```markdown title="plugins/Foreman/styles/harbor.md"
# Harbor district

## Palette
- Foundations: `cobblestone`, `mossy_cobblestone`
- Walls: `spruce_planks` with `stripped_spruce_log` frames
- Roofs: `dark_oak_stairs`, 1 block overhang

## Rules
- Houses 5–9 blocks wide, 1–2 floors, raised 1 block on a stone base
- Every door has a path to the quay
```
