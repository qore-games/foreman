---
name: worldguard
description: Protect areas and set rules with WorldGuard regions (define, flags, members, priorities, the global region), and find which regions and flags apply somewhere. Read before creating or changing a region or when something is blocked by protection.
plugin: WorldGuard
---

# WorldGuard regions

Run the commands with `server_command` (as the console; `-w <world>` names the world), except `define` and
`redefine`, which need a selection: run them through the `worldedit` tool with the region's box, e.g. command
`/rg define market`, from/to the corners. Region ids are lowercase.

## Create and change

| Task | Command |
|---|---|
| Create a cuboid region (box = the selection) | `worldedit` tool: `/rg define <id> [owners…]` |
| Change its shape | `worldedit` tool: `/rg redefine <id>` |
| Delete | `/rg remove -w <world> <id>` |
| Set a flag | `/rg flag -w <world> <id> <flag> <value>` (`-g members` limits it to a group) |
| Clear a flag | `/rg flag -w <world> <id> <flag>` (no value) |
| Priority (higher wins on overlap) | `/rg setpriority -w <world> <id> <n>` |
| Parent (inherits its flags) | `/rg setparent -w <world> <id> <parent>` |
| Owners and members | `/rg addowner -w <world> <id> <player>`, `/rg addmember …`, `/rg removemember …` |

The whole world's rules are the `__global__` region: `/rg flag -w <world> __global__ pvp deny`.

## Inspect

- `/rg info -w <world> <id>`: bounds, priority, parent, owners, members, flags.
- `/rg list -w <world>`: every region of the world.
- Which regions are at a position: list them, then compare their bounds with the position; the one with the highest
  priority decides for its flags, a child overrides its parent.

## Common flags

`build`, `block-break`, `block-place`, `interact`, `use`, `chest-access`, `pvp`, `mob-spawning`, `mob-damage`,
`creeper-explosion`, `tnt`, `fire-spread`, `entry`, `exit`, `greeting`, `farewell`, `invincible`, `fall-damage`,
`enderpearl`, `teleport` (a location), `spawn`. Values: `allow`, `deny`, or text/numbers.

## Pitfalls

- A player blocked from building: a region without them as member, with `build` or `passthrough` unset, applies.
  Check the regions at the spot and their priorities before adding flags.
- `/rg flag` on a region without `-w` from the console fails: always name the world.
- Changes apply at once and are saved by WorldGuard; `/rg load` rereads the files, `/rg save` writes them.
