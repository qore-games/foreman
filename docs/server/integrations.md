# Plugin integrations

Foreman works with other plugins through [skills](skills.md): for each plugin below, a skill teaches the AI its
commands, files and pitfalls, and the AI runs them with `server_command`. Each skill is offered only while its plugin
is enabled, and costs one line of the AI's instructions until a request needs it. A few plugins also get code tools,
where their commands fall short.

Nothing to configure: install the plugin, and its skill is there the next time the AI connects.

| Plugin | What the AI can do | How |
|---|---|---|
| [FastAsyncWorldEdit](https://modrinth.com/plugin/fastasyncworldedit) | Any WorldEdit command on a box in one call: replace with mixes, overlay, smooth, naturalize, set biomes, regenerate. Undoable | `worldedit` tool + skill. It also powers the [world tools](world-tools.md) |
| [WorldGuard](https://enginehub.org/worldguard) | Define and redefine regions, flags, priorities, members, the global region; find what blocks a player | Skill (`/rg`, regions defined on a box through `worldedit`) |
| [MythicMobs](https://mythiccraft.io) | Write mobs, skills and items, reload, spawn and test them, read config errors | Skill (`/mm`, the YAML files) |
| [ModelEngine](https://mythiccraft.io) | List models with their bones, hitboxes and animations; put them on mobs; test them | `modelengine_models` tool + skill |
| [LuckPerms](https://luckperms.net) | Find why a player can or can't do something, give and remove permissions, groups, prefixes | Skill (`/lp`, verbose checks) |
| [PlaceholderAPI](https://placeholderapi.com) | Check what a placeholder returns for a player, install expansions, fix placeholders shown raw | Skill (`/papi`) |
| [CoreProtect](https://www.spigotmc.org/resources/coreprotect.8631/) | Find who changed, broke or took what, roll back and restore | Skill (`/co`). CoreProtect 24.1 doesn't run on 26.3 yet |
| [EssentialsX](https://essentialsx.net) | Warps, spawn, kits, economy, its configs | Skill |
| [Citizens](https://citizensnpcs.co) | Create, move, skin and script NPCs from the console, text above them | Skill (`/npc … --id`) |
| [TAB](https://modrinth.com/plugin/tab-was-taken) | Tab list, name tags, prefixes, header/footer; why a value applies | Skill (`/tab parse`, `/tab group`) |
| [Skript](https://modrinth.com/plugin/skript) | Write and fix scripts, read their errors on reload | Skill (`/sk reload`) |
| [PlugMan / PlugManX](https://modrinth.com/plugin/plugmanx) | Reload a plugin and report how it went | `server_reload_plugin` tool |

## An example: replacing blocks

> In the box from -60,60,160 to -50,95,170, replace all grass with 70% moss and 30% grass.

The AI makes one call, `worldedit` with `//replace grass_block 70%moss_block,30%grass_block` on that box, and reports
the count. `undo` puts the box back. Without WorldEdit it would have to read and rewrite the blocks itself.

## Your plugin, or one that's missing

- **Your own plugin:** ship a skill in its jar, or register tools and skills from code:
  [Skills for your plugin](../developers/skills.md), [ForemanTools](../developers/foreman-tools.md).
- **A plugin you use:** write a skill for it in `plugins/Foreman/skills/` ([Skills](skills.md)), or ask a chat to
  write it after it worked something out ("save how to do this as a skill").
- **For everyone:** skills for more plugins are welcome as pull requests: a markdown file in
  `server/src/main/resources/skills/`, tested on a server with that plugin ([Contributing](https://github.com/qore-games/foreman/blob/main/CONTRIBUTING.md)).
