# Example zone: medieval fishing village

Copy this file per zone (`styles/<zone>.md`). The AI reads it before building there. Be concrete: palettes as block
ids, sizes in blocks, and name the assets and furniture that belong here.

## Mood
Small, lived-in, weathered. Nothing perfectly symmetrical. Houses lean on each other along the shore.

## Palette
- Foundations: `cobblestone`, `mossy_cobblestone`, `stone` (bottom row always mossy)
- Walls: `spruce_planks` with `stripped_spruce_log` frames; white `calcite` infill on bigger houses
- Roofs: `dark_oak_stairs`, `dark_oak_slab` ridge, 45° slope, 1 block overhang
- Paths: `dirt_path`, `coarse_dirt`, `gravel`, mixed
- Accents: `barrel`, `lantern`, `spruce_trapdoor` shutters

## Rules
- Houses 5–9 blocks wide, 1–2 floors, raised 1 block on a stone base
- Every door has a path to the main street
- No glass panes in walls facing the sea; use trapdoor shutters

## Assets and furniture
- Houses: `houses/fisher_hut`, `houses/net_shed`
- Furniture: `park_bench`, crates and barrels from list_furniture (filter "crate")
