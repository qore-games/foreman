---
name: mythicmobs
description: Create, change, spawn and test MythicMobs mobs, skills and items (their YAML files, mechanics, targeters and triggers, /mm commands). Read before touching a MythicMobs config or when a custom mob misbehaves.
plugin: MythicMobs
---

# MythicMobs

Configs are YAML in `plugins/MythicMobs/`: `Mobs/`, `Skills/`, `Items/`, `DropTables/`, `RandomSpawns/`
(any file name, any subfolder; every top-level key is one mob, skill or item, unique across files). Edit them in the
chat's workspace, then `/mm reload` with `server_command` and read what it logged: errors name the file and the line.

## Commands (`server_command`; `[arg]` required, `<arg>` optional)

| Task | Command |
|---|---|
| Reload everything | `/mm reload` (watch the log for config errors) |
| List mobs, one mob's info | `/mm m list <filter>`, `/mm m info [type]` |
| Spawn | `/mm m spawn [type] <amount> <world,x,y,z>` (`-t` for a temporary mob) |
| Remove | `/mm m kill [type]`, `/mm m killall <radius>` |
| Active mobs | `/mm m stats` |
| Skills | `/mm s info [name]` |
| Items | `/mm i list <filter>`, `/mm i info [name]`, `/mm i give [player] [name] <amount>` |
| Debug output | `/mm debug [level]` (1-4 in the console; turn it off with 0) |

## A mob

```yaml
Raider:
  Type: ZOMBIE
  Display: '<red>Raider'
  Health: 40
  Damage: 6
  Armor: 2
  Options:
    MovementSpeed: 0.28
    PreventOtherDrops: true
  Equipment:
  - IRON_AXE HAND
  Skills:
  - skill{s=RaiderLeap} @target ~onTimer:100 ?targetwithin{d=12}
  - message{m="<caster.name> charges!"} @PlayersInRadius{r=16} ~onCombat 0.25
  Drops:
  - iron_ingot 1-3 0.5
```

A skill line: `mechanic{options} @targeter ~trigger <health modifier> <chance>` with `?conditions`.

## A skill

```yaml
RaiderLeap:
  Cooldown: 8
  Conditions:
  - onground true
  Skills:
  - leap{velocity=120;noise=0} @target
  - delay 15
  - damage{a=8} @EntitiesInRadius{r=3}
```

## Testing a change

1. `server_log_mark` a name, edit the file, `/mm reload`, then `server_log since=<mark>` for errors.
2. Spawn it near a player: `/mm m spawn Raider 1 world,<x>,<y>,<z>`, watch it (`client_screenshot` or `look`).
3. Remove it: `/mm m kill Raider`.

## Pitfalls

- Mechanic and targeter names are case-insensitive, option names aren't always: copy a working line.
- A typo in a mob's `Type` or a skill name doesn't stop the reload: read the log after every reload.
- With ModelEngine, models are mechanics on the mob: see the modelengine skill.
