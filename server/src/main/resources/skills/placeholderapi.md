---
name: placeholderapi
description: Check what PlaceholderAPI placeholders return for a player (%player_name%, %vault_eco_balance%…), install expansions and debug placeholders that show raw. Read when a scoreboard, tab list, HUD or message shows a placeholder or a wrong value.
plugin: PlaceholderAPI
---

# PlaceholderAPI

Run with `server_command`. `--null` parses without a player; a player name works for online and offline players.

| Task | Command |
|---|---|
| What a text gives for a player | `/papi parse <player> %player_name% has %vault_eco_balance%` |
| Without a player | `/papi parse --null %server_online%` |
| Installed expansions | `/papi list` |
| One expansion's placeholders | `/papi info <expansion>` |
| Install from the eCloud | `/papi ecloud download <expansion>` then `/papi reload` |
| Reload | `/papi reload` |

## When a placeholder shows raw (`%vault_eco_balance%` on screen)

1. `/papi parse <player> <the placeholder>`: raw again means the expansion isn't there (`/papi list`) or the name
   is wrong (`/papi info <expansion>` lists its placeholders).
2. A right value here but raw in the plugin: that plugin doesn't parse placeholders in that field, or caches the
   text: check its docs or config for a PlaceholderAPI option.
3. Relational placeholders (`%rel_…%`) need two players: `/papi parserel <one> <two> <text>`.
