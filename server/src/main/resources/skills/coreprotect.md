---
name: coreprotect
description: Find who changed, broke or took what and when with CoreProtect, and roll it back or restore it (lookup, rollback, restore, purge). Read for grief investigations or undoing changes made by players.
plugin: CoreProtect
---

# CoreProtect

Run with `server_command`. Lookups print pages; `/co lookup <page>` (or `/co l <page>`) shows the next.

## Parameters

| Parameter | Means |
|---|---|
| `u:<user>` | a player (or `#tnt`, `#creeper`, `#fire`…) |
| `t:<time>` | how far back: `t:2h`, `t:3d`, `t:1w2d` |
| `r:<radius>` | blocks around the sender; from the console use `r:#global` or `r:#<world>` |
| `a:<action>` | `block`, `+block` (placed), `-block` (broken), `container`, `+container`, `-container`, `click`, `kill`, `chat`, `command`, `session`, `sign`, `item` |
| `b:<blocks>`, `e:<blocks>` | only these blocks, except these |

A radius around a place: run the command as a player standing there (`server_command` `as_player`), or use
`r:#<world>` with `u:` and `t:` to narrow it.

## Commands

| Task | Command |
|---|---|
| Who changed things | `/co lookup u:Steve t:1d r:#world a:-block` |
| What happened at a spot | as a player there: `/co lookup t:1d r:5` |
| Undo a player's damage | `/co rollback u:Steve t:2h r:#world` |
| Put it back | `/co restore u:Steve t:2h r:#world` |
| Undo the last rollback | `/co undo` |
| Container thefts | `/co lookup u:Steve t:1d a:-container r:#global` |
| Clean old data | `/co purge t:30d` |

## Pitfalls

- Rollbacks change the world for real: look up first with the same parameters, then roll back.
- Changes made by plugins (WorldEdit, Foreman's tools) are logged only when that plugin logs to CoreProtect.
