# Skills

A skill teaches the AI how to do one kind of task on your server: a plugin's commands and conventions, a workflow of
yours ("open the market for the day", "set up a new arena"). It's a markdown file.

The AI sees every skill's name and one-line description in its instructions, and reads the whole skill
(`read_skill`) only when a request matches it. A skill costs one line until it's needed, where a tool's definition is
sent with every request. That's how Foreman supports many plugins without slowing the AI down or using more tokens:
the skill explains the commands, and the AI runs them with `server_command` (or `worldedit` for WorldEdit).

`/foreman skills` lists the skills of your server; the hub's AI → Tools page shows them too.

## Where skills come from

Later ones win on a name, so you can replace any skill with your own version.

| Source | What | Who |
|---|---|---|
| Foreman | Skills for [popular plugins](integrations.md), offered while their plugin runs | Foreman |
| A plugin's jar | `foreman/skills/<name>.md` files in the plugin, offered while it runs: no code | [Plugin authors](../developers/skills.md) |
| A plugin's code | `ForemanTools.registerSkill(…)` | [Plugin authors](../developers/skills.md) |
| Your server | `plugins/Foreman/skills/<name>.md` | You, or the AI with `save_skill` |

## Writing one

Drop a file in `plugins/Foreman/skills/`, for example `arena-setup.md`:

```markdown
---
name: arena-setup
description: Set up a new PvP arena (region, spawns, kit, signs). Read when asked to create or reset an arena.
plugin: WorldGuard
---

# Arena setup

1. Define the region with the worldedit tool on the arena's box: `/rg define arena-<name>`.
2. Flags: `/rg flag -w <world> arena-<name> pvp allow`, `... build deny`, `... item-drop deny`.
3. Spawns: two points 30 blocks apart on the arena floor, saved with `/arena setspawn <name> 1|2` as a player.
4. Kit: `/kit arena <player>` gives the default kit; never give armor above iron.
5. Check: walk it with `check`, then `look` from both spawns.
```

- **`name`:** lowercase letters, digits and `-`; the file name when it's missing.
- **`description`:** when to read it. It's what the AI chooses by, so say what it's for and when it applies.
- **`plugin`** (optional): the skill is offered only while that plugin is enabled.
- **The body:** the how. Commands with their exact syntax, the order of steps, what to check, the pitfalls. Short and
  concrete beats long and general.

The next AI session sees it; there's nothing to reload.

## Letting the AI write them

Tell a chat what you want it to remember ("save how we set up arenas as a skill"): it writes the file with
`save_skill` (players need `foreman.admin`). Use `learn` instead for one-sentence rules.

## Skills or tools?

A skill is enough when the plugin has commands for what the AI needs, and most plugins do. A tool is worth writing
when commands fall short: reading structured data no command prints (ModelEngine's models), an action that needs code,
or an answer the AI would otherwise have to piece together from many commands. See
[ForemanTools](../developers/foreman-tools.md).
