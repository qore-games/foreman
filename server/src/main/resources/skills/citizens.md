---
name: citizens
description: Create, move, skin and configure Citizens NPCs (traits, commands, text, look), from the console with --id. Read before adding or changing an NPC.
plugin: Citizens
---

# Citizens

Run with `server_command`. Most `/npc` commands act on the sender's selected NPC: from the console, add
`--id <id>` instead of selecting. `/npc list -a` gives the ids. NPCs are saved in `plugins/Citizens/saves.yml`.

| Task | Command |
|---|---|
| List | `/npc list -a` (without `-a` the console sees none) |
| Create | `/npc create <name> --at <x>:<y>:<z>:<world> --type <entity>` (default a player) |
| Remove | `/npc remove <id>` |
| Move | `/npc moveto --id <id> <x> <y> <z> <world>` |
| Skin | `/npc skin --id <id> <player name>` |
| Look at nearby players | `/npc lookclose --id <id>` |
| Run a command on click | `/npc command add --id <id> <command>` (`-p` as the player) |
| Lines of text above it | `/npc hologram add --id <id> <text>` (`/npc text` is an in-game editor) |
| Rename | `/npc rename --id <id> <name>` |
| Save to disk | `/citizens save` |

## Pitfalls

- An NPC spawns only in a loaded chunk; it reappears when the chunk loads.
- `/citizens reload` (loads `saves.yml` without saving) asks to be typed again, and each `server_command` is a new
  sender, so it never goes through: set `general.reload-warning-enabled: false` in `config.yml` and restart first.
- Flags and arguments differ between Citizens builds: `/npc help` (or `/npc help <page>`) lists what this one takes.
