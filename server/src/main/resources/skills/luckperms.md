---
name: luckperms
description: Find why a player can or can't do something and change permissions, groups, prefixes and contexts with LuckPerms (/lp commands, verbose checks, tracks). Read before changing a permission or when a player lacks access.
plugin: LuckPerms
---

# LuckPerms

Run with `server_command` (the console has every right). Node names are lowercase; contexts are `key=value`
(`server=survival`, `world=nether`).

## Why can (or can't) a player do something

1. Find the permission the command or action needs: the plugin's docs, `plugin.yml`, or watch it live:
   `/lp verbose on <player>`, have the player try (or `client_run_command` it): the console shows each check with
   its result; then `/lp verbose off`. (`record` then `upload` posts the checks to LuckPerms' web viewer instead.)
2. `/lp user <player> permission check <node>`: the result and which node, group or context gives it.
3. `/lp user <player> info`: primary group, parents, prefix/suffix, meta.

## Change

| Task | Command |
|---|---|
| Give or deny a node | `/lp user <player> permission set <node> [true\|false] [context…]` |
| Remove it | `/lp user <player> permission unset <node> [context…]` |
| For a group | `/lp group <group> permission set <node> [true\|false] [context…]` |
| Group membership | `/lp user <player> parent add <group>`, `parent remove`, `parent set` |
| A group's weight, prefix | `/lp group <group> setweight <n>`, `/lp group <group> meta setprefix <weight> "<prefix>"` |
| Temporary | `/lp user <player> permission settemp <node> true 1h` |
| Groups | `/lp listgroups`, `/lp creategroup <name>`, `/lp group <group> info` |
| Everything a group has | `/lp group <group> permission info` |

## Pitfalls

- A `false` node wins over a `true` one inherited from a group: `permission check` shows which.
- `/lp user <name>` finds only players who have joined; use the UUID for one who hasn't.
- Wildcards (`plugin.*`) only work when the plugin registers its nodes or LuckPerms' `apply-wildcards` is on.
- Changes apply at once; on a network, other servers see them on their next sync (`/lp networksync`).
