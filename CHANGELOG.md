# Changelog

One version for the mod, the plugin and the API artifacts, [semantic](https://semver.org): a release is the tag
`v<version>` of `mod_version` in `gradle.properties`, and its notes are its section here.

## [1.0.0] - 2026-11-02

The first public release.

### Client (Fabric mod, any server)

- The hub (H): every tool in a rail grouped by AI, World and Debug, each feature's settings on its page.
- Packet viewer: records both directions, groups the traffic by what likely sends it (Suspects), types, entities,
  chunks, every packet with its fields; export, and a chat that looks for the culprit in your plugins.
- HUD inspector: the action bar, titles, boss bars and tab list laid out glyph by glyph, guides over the HUD, a watch
  for what moves, and a check of declared glyph widths.
- Plugin locations drawn in the world, by source and category, the location editor's gizmo, local location sets.
- The WorldEdit selection drawn in any shape (on any server with WorldEdit), and a tool that sets its positions from
  afar and moves or resizes it with a gizmo.
- A log of plugin messages both ways: a colour per channel, click to expand a decoded + hex/ASCII view, pause and filter;
  printed to chat as the hub's rows (hover for the whole text, click to copy it) or to the game's log.
- A selection tool of its own, keys to copy your position.
- `client_drag` holds a mouse button and moves (gizmos, scrollbars, mining); dupe-testing inventory tools (gated).
- The hub and chats fit small windows: they draw at a smaller GUI scale when the game's is too big for their layout.
- AI chats with your own Claude Code, opencode or OpenRouter, in workspaces of folders; outside agents (Claude Code in
  a terminal) can use the client's tools through MCP.

### Server (Paper plugin)

- Dev-server tools: commands with everything they printed, the log since the last reload, stale plugin jars, reloads
  through PlugMan, status, a profile of the server thread by plugin, an event's listeners.
- World tools for the AI: renders, edits, terrain, roads, schematics, buildings from editable templates
  (`catalog/buildings.yml`, `catalog/rooms.yml`), checks that walk a build like a player, undo. They need
  FastAsyncWorldEdit; the rest of the plugin runs without it.

- Skills: markdown that teaches the AI a plugin or a workflow, read only when a request needs it (`read_skill`,
  `save_skill`, `/foreman skills`). Bundled for FastAsyncWorldEdit, WorldGuard, MythicMobs, ModelEngine, LuckPerms,
  PlaceholderAPI, CoreProtect, EssentialsX, Citizens, TAB and Skript, each offered
  while its plugin runs; plugins ship their own in `foreman/skills/`, servers in `plugins/Foreman/skills/`.
- `worldedit`: any WorldEdit/FastAsyncWorldEdit command on a box in one call, undoable with `undo`.
- `modelengine_models`: ModelEngine's models with their bones, hitboxes and animations.
- Dupe testing (needs `foreman.test`): `item_ledger` counts an item everywhere and reports the change since last time,
  `inventory_watch` logs the inventory events a test fires and who handles them, `test_restore` saves and restores
  inventories; the client's `inventory_snapshot` and `inventory_actions` (bursts in one tick) replay a player's clicks,
  with network profiles `burst` (hold the sends and send them together) and `ping_<n>` (added latency), and inbound
  `drop` of chosen server packets, for the desync classes (these two only where the server gives the player
  `foreman.test`, which the bridge's hello says). A `dupe-testing` skill and `find_dupes` prompt.
- Every MCP request needs a token, on localhost too (`mcp-token.txt`, made at the first start, when `mcp.token` is
  empty); requests from web pages are refused. Tools that act as the console (`server_*`, `learn`, WorldGuard and
  non-editing WorldEdit commands, plugins' console command tools) need `foreman.admin` with a personal token.

### For AIs

- `llms.txt` and `llms-full.txt` on the docs site, `AGENTS.md` in the repository, and prompts to paste into an agent
  ("Ask your AI to use Foreman").

### API

- `foreman-server-api`: `ForemanTools` (tools, commands and skills), `ForemanWorlds`, `FurnitureProvider`.
- `foreman-paper` and `foreman-api`: the location API, usable from Java and Kotlin.
