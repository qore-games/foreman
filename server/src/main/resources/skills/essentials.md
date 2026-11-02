---
name: essentials
description: Manage EssentialsX features: warps, homes, spawn, kits, economy, nicknames and its configs (config.yml, kits.yml, worth.yml). Read before changing Essentials settings, kits or warps, or when one of its commands misbehaves.
plugin: Essentials
---

# EssentialsX

Files in `plugins/Essentials/`: `config.yml` (most settings), `kits.yml`, `worth.yml`, `warps/<name>.yml`,
`userdata/<uuid>.yml`. After editing them: `/essentials reload` with `server_command`.

## Commands

Many use the sender's position (`/setwarp`, `/setspawn`, `/createkit` from the inventory): run those as a player
standing in the right place (`server_command` `as_player`, or `client_run_command` for the user).

| Task | Command |
|---|---|
| Reload | `/essentials reload` |
| Warps | `/setwarp <name>` (as a player), `/warp <name> <player>`, `/delwarp <name>`, `/warp` (list) |
| Spawn (EssentialsSpawn) | `/setspawn` (as a player), `/spawn <player>` |
| Kits | `/kit <kit> <player>`, `/createkit <name> <delay>` (as a player, from their inventory), `/delkit <name>` |
| Economy | `/eco give\|take\|set <player> <amount>`, `/balance <player>`, `/baltop` |
| Players | `/seen <player>`, `/whois <player>`, `/nick <player> <nickname>` |
| Run a command as someone | `/sudo <player> <command>` |

## Pitfalls

- A kit's items are YAML in `kits.yml`: edit them there for exact items, or rebuild with `/createkit`.
- Commands with the same name as another plugin's: Essentials gives them up by default; `overridden-commands` in
  `config.yml` keeps one, `disabled-commands` drops one; `/essentials:<command>` always runs Essentials' own.
