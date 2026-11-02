---
name: tab
description: Configure the TAB plugin's tab list, name tags, prefixes, header/footer, scoreboard and boss bars (config.yml, groups.yml) and find which values apply to a player. Read before changing the tab list or name tags.
plugin: TAB
---

# TAB

Files in `plugins/TAB/`: `config.yml` (header-footer, tablist-name-formatting, scoreboard-teams, scoreboard, bossbar,
per-world-playerlist), `groups.yml` and `users.yml` (values per group or player, `per-world:` overrides).
Placeholders are TAB's own (`%player%`, `%group%`, `%online%`, `%animation:<name>%`) and PlaceholderAPI's (see the
placeholderapi skill).

| Task | Command |
|---|---|
| Reload after editing | `/tab reload` (the reply and the log list config errors) |
| Set a value for a group | `/tab group <group> tabprefix <value>` (also `tabsuffix`, `tagprefix`, `tagsuffix`, `customtabname`; no value removes it) |
| For one player | `/tab player <player> tabprefix <value>` |
| Show what's set | `/tab group <group>`, `/tab groups` (a player's values: `users.yml`) |
| Parse a placeholder through TAB | `/tab parse <player> <placeholder>` (an online player) |
| Everything about a player | `/tab dump <player>`: uploads it with the config files to pastes.dev, a public link; ask first |

## Pitfalls

- A value that doesn't show: `/tab parse <player> %group%` says which group TAB gave them (the permission plugin's
  primary group by default, `assign-groups-by-permissions` in `config.yml` otherwise).
- Another plugin setting name tags or the scoreboard fights TAB: turn one of them off for that feature.
