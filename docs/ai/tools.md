# Tool reference

Every tool the AI can use, by provider. In game, the hub's AI section (Tools) lists them with their full
descriptions; `/ai tools` prints them to chat, and `/foreman tools [filter]` on the server lists the server's.

## Client tools

Available on any server, to chats and to [outside agents](outside-agents.md).

### Seeing and acting

| Tool | Does |
|---|---|
| `client_context` | Where the player is and looks, the block or entity at the crosshair, their selection, dimension, the last chat lines |
| `client_screenshot` | What the player sees, as an image (needs Vision) |
| `client_run_command` | Runs a command as the player and returns what the server printed (needs Commands) |
| `client_teleport` | Teleports the player (a `/tp` as them) |
| `client_look` | Turns the camera to a point, or a yaw and pitch |
| `client_chat_log` | The last chat and system messages |
| `client_log` | The client's recent log lines, filtered by level and text |

### Screens

| Tool | Does |
|---|---|
| `client_screen` | The open screen: its class and title, the GUI size, each widget with its text and bounds |
| `client_click`, `client_hover`, `client_scroll` | Use the open screen like the mouse |
| `client_drag` | Hold a mouse button and move: across a screen (scrollbars, sliders), or turning the camera in the world (gizmos, mining) |
| `client_type`, `client_key` | Type into the focused field; press a key binding |
| `client_dialog_preview` | Opens a dialog on the client alone from its JSON, as the server would send it |
| `inventory_snapshot`, `inventory_actions` | Read the open container, and click it like a player (bursts in one tick, optionally under a network profile or inbound packet drop, which need `foreman.test`) — [dupe testing](../server/dupe-testing.md) |

### Entities, items, resources

| Tool | Does |
|---|---|
| `client_entities` | The entities around the player or along the look, with their synced data |
| `client_item` | An item the player has, with its components, as the client sees it |
| `client_resource_packs` | The loaded resource packs, bottom to top |
| `client_post_effects` | The post effects (full-screen shaders) in use |

### Packets

| Tool | Does |
|---|---|
| `packets_record` | Starts or stops a recording |
| `packets_summary` | Totals each way, the busiest second, the types, entities and chunks that get the most |
| `packets_suspects` | The traffic grouped by what likely sends it, with clues to search for |
| `packets_list` | Kept packets one by one, filtered |
| `packets_timeline` | Packets and bytes per time bucket, with the peaks |
| `packets_export` | Writes the recording to a file |

### HUD

`ui_capture`, `ui_watch`, `ui_measure`, `ui_glyphs`, `ui_check_widths`: see [HUD inspector](../client/hud-inspector.md).

### Files (chats only)

`client_list_files`, `client_read_file`, `client_write_file`, `client_search_files`: the chat's workspace folders.
Claude Code and opencode also have their own file tools on the same folders; OpenRouter chats rely on these.

## Server tools

Available where the Foreman plugin runs.

### Dev server

`server_command`, `server_log`, `server_log_mark`, `server_plugins`, `server_status`, `server_profile`,
`server_listeners`, `server_reload_plugin` (with PlugMan or PlugManX). See [Dev-server tools](../server/dev-tools.md).

### Skills

`read_skill` (one skill, or the list), `save_skill` (a skill for the server, from a chat). See
[Skills](../server/skills.md).

### Testing (dupe hunting)

`item_ledger` (count an item everywhere, with the change since last time), `inventory_watch` (the inventory events a
test fires and who handles them), `test_restore` (save/restore inventories). Need `foreman.test`. See
[Dupe testing](../server/dupe-testing.md).

### Other plugins

| Plugin | Tools |
|---|---|
| ModelEngine | `modelengine_models`: the loaded models, their bones, hitboxes and animations |

Most plugins need no tool of their own: a [skill](../server/integrations.md) teaches the AI their commands, which it
runs with `server_command` (or `worldedit`).

### World

| Group | Tools |
|---|---|
| Inspect | `get_context`, `read_style_guide`, `scan_area`, `read_blocks`, `find_blocks`, `render`, `world_profile`, `set_mark` |
| Quality | `check`, `look`, `measure_site`, `claim` |
| Edit | `edit`, `worldedit` (any WorldEdit command on a box, undoable), `transform_region`, `undo` |
| Terrain | `foundation`, `blend_terrain`, `smooth_terrain`, `make_ground`, `grade`, `build_path`, `make_fields` |
| Buildings | `build_structure`, `demolish`, `list_styles`, `points` |
| Repair | `connect_doors`, `repair_terrain` |
| Assets | `list_assets`, `index_assets`, `preview_asset`, `scatter_assets`, `paste_asset`, `save_asset` |
| Models | `preview_model` |
| Furniture | `list_furniture`, `place_furniture`, `remove_furniture` (with a [furniture provider](../developers/furniture.md)) |
| Lessons | `learn`, `lessons` |

See [World tools](../server/world-tools.md).

### Your plugins' tools

Other plugins publish their own with [`ForemanTools`](../developers/foreman-tools.md). They're listed with their
plugin as the provider.
