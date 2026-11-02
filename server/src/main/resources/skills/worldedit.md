---
name: worldedit
description: Change many blocks at once with WorldEdit/FastAsyncWorldEdit commands through the worldedit tool (replace, set, overlay, smooth, naturalize, deform, biomes) and their patterns and masks. Read before any bulk block change or terrain reshaping.
plugin: FastAsyncWorldEdit
---

# WorldEdit through the `worldedit` tool

`worldedit` runs one WorldEdit command on a box: `from`/`to` become the selection (pos1/pos2) in `world`, the box is
saved first (`undo` puts it back), and you get what the command printed. One call replaces hundreds of edit ops: use it
for every bulk change.

- Give the box everything the command may change, and no more (it's the undo snapshot and the edit limit).
- Commands are written as a player types them: `//replace …`. One command per call.
- Read the reply: it says how many blocks changed, or why it failed (a bad pattern, an unknown block).

## Patterns (what to place)

| Pattern | Means |
|---|---|
| `stone` | one block |
| `oak_stairs[facing=north,half=top]` | a block state |
| `50%stone,30%andesite,20%cobblestone` | random mix by weight (`stone,cobblestone` = equal) |
| `#clipboard` | the clipboard's blocks, tiled |
| `##wool` | a random block of a tag |
| `^[facing=east]` | keep the block, change its state |

## Masks (where it applies)

| Mask | Means |
|---|---|
| `stone,andesite` | any of these (OR) |
| `!air` | not air |
| `>grass_block` | above grass blocks (the block over it) |
| `<stone` | under stone |
| `#existing` | any non-air block |
| `#surface` | blocks with air above them |
| `%50` | half of the blocks, at random |
| `$plains,forest` | in these biomes |
| `=y<70` | an expression |

Combine with spaces for AND: `stone >air` is stone with air above it. Masks go after `//replace` and in `//gmask`.

## Commands that do the most

| Task | Command |
|---|---|
| Replace blocks | `//replace stone grass_block`, `//replace stone,dirt 70%grass_block,30%moss_block` |
| Replace with a mix everywhere | `//replace stone 50%stone,50%cobblestone` |
| Fill the box | `//set stone_bricks`, `//set 0` (air) |
| Cover the top | `//overlay grass_block`, `//overlay 80%short_grass,20%poppy` |
| Make terrain natural | `//naturalize` (grass, dirt, stone layers) |
| Smooth terrain | `//smooth 4` (iterations), `//smooth 6 #existing` |
| Walls, faces, hollow | `//walls cobblestone`, `//faces glass`, `//hollow 1 air` |
| Lines and curves | `//line stone 2` (pos1 to pos2, thickness) |
| Biomes | `//setbiome cherry_grove` |
| Remove fluids | `//drain 20` |
| Trees and plants | `//forest oak 8` (density %), `//flora 10` |
| Regenerate | `//regen` (back to the world generator's terrain) |
| Shapes in the box | `//deform "y-=0.2*sin(x*5)"` |
| Count and inspect | `//count stone`, `//distr` (block distribution: no change) |
| Copy, move, stack | `//copy` then `//paste` (at pos1), `//move 5 up`, `//stack 3 north` |

Read-only commands (`//count`, `//distr`, `//size`) are a cheap way to measure before changing.

## Pitfalls

- `//replace` without a "from" mask replaces every non-air block of the box: always give the mask.
- Generation commands (`//sphere`, `//cyl`, `//pyramid`) place at the session's position, which the console doesn't
  have: build shapes with Foreman's `edit` ops instead.
- Brushes (`/brush …`) need a player clicking with a tool: use region commands, or tell the player the brush to bind.
- A huge box is slow and may hit the edit limit: split it, or narrow it with a mask.
- After terrain changes, `check` and `render view=slope` show what players will walk on.
