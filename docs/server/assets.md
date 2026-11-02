# Schematic library

`plugins/Foreman/assets/` holds your schematics for the AI: houses, stalls, trees, rocks, ruins… WorldEdit's
schematics (`FastAsyncWorldEdit/schematics`) are listed too.

| | |
|---|---|
| `/foreman asset save <name> [tags…]` | Saves your WorldEdit selection |
| `save_asset` | The AI saves a box of the world |
| `index_assets` | Reads new or changed schematics: size, main blocks, a guessed category (tree, plant, rock, ruin, structure) |
| `list_assets`, `preview_asset` | Browse them, render one from four sides |
| `paste_asset` | Paste one, its bottom center at a position |
| `scatter_assets` | Scatter some over an area, each on the ground, spaced and turned |

Schematics can sit in subfolders (`houses/small_cottage`); folders starting with `_` are left out of listings but
can still be pasted by name. WorldEdit's own schematics are listed as `fawe/<name>`, read only.

A `<name>.yml` next to a schematic describes it for the AI:

```yaml title="plugins/Foreman/assets/houses/small_cottage.yml"
description: A one-room cottage with a chimney, door to the south
tags: [house, rural]
category: structure
```
