---
name: skript
description: Write, reload and debug Skript scripts (.sk files in plugins/Skript/scripts) for commands, events and small features without Java. Read before writing or fixing a script.
plugin: Skript
---

# Skript

Scripts are `plugins/Skript/scripts/<name>.sk` (a file starting with `-` is disabled). Write them in the chat's
workspace, then `/sk reload <name>` with `server_command`: the reply lists every error with its line.

```
command /heal [<player>]:
    permission: myserver.heal
    trigger:
        set {_p} to arg-1 ? player
        heal {_p}
        send "<green>Healed %{_p}%" to player

on join:
    if {joined::%player's uuid%} is not set:
        set {joined::%player's uuid%} to now
        broadcast "<yellow>Welcome %player% to the server!"
```

| Task | Command |
|---|---|
| Reload one script | `/sk reload <name>` |
| Reload all | `/sk reload scripts` |
| Enable, disable | `/sk enable <name>`, `/sk disable <name>` (renames the file with or without `-`) |
| List enabled and disabled | `/sk list` |
| Version, addons | `/sk info` |

## Pitfalls

- Indentation is the structure: tabs or spaces, one kind per file.
- `{variables}` are saved; `{_local}` ones live only in the trigger.
- Addons add syntax: what works on another server may not parse here (`/sk info` lists the addons).
