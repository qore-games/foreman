# Setup and configuration

1. Put `foreman-server-1.2.0.jar` in `plugins/` (with FastAsyncWorldEdit for the world tools) and start the server.
   The log shows the MCP address, `http://127.0.0.1:25599/mcp` by default, and
   [`plugins/Foreman/mcp.json`](#the-connection-file) gives it with the token.
2. Join with the client mod and press ++y++, or connect another MCP client with the token Foreman made in
   `plugins/Foreman/mcp-token.txt` (from the server's folder):
   ```sh
   claude mcp add --transport http minecraft http://127.0.0.1:25599/mcp \
     --header "Authorization: Bearer $(cat plugins/Foreman/mcp-token.txt)"
   ```
3. For players on other machines, set `mcp.host: 0.0.0.0`. Each player with `foreman.client` gets their own token
   through the mod. The MCP server speaks plain HTTP: beyond a network you trust, put it behind TLS (a reverse proxy)
   or a VPN.

## config.yml

```yaml
# What this server offers. dev: every feature below, for dev and build servers. production: what's safe on a live
# server, the read-only server tools (diagnostics) for players with foreman.admin through their game connection
# (relay), and no MCP port. /foreman status lists what's on.
preset: dev
# Single features on or off over the preset: uncomment one and set it.
features:
  # mcp: true           # The MCP server on mcp.host:mcp.port (the server's token, players' tokens through the mod)
  # relay: true         # The mod's AI reaches the tools through the player's game connection, no port (audited)
  # diagnostics: true   # Read-only server tools: the log, plugins and their jars, status, the profiler, listeners
  # console: true       # server_command (commands as the console or a player) and plugin reloads (PlugMan)
  # world: true         # World tools that read it (with FastAsyncWorldEdit): context, scans, renders, checks
  # world-edit: true    # World tools that change it: edits, terrain, buildings, assets, furniture
  # testing: true       # Dupe and test tools, the mod's network profiles and packet drops (foreman.test)
  # test-players: true  # Registered test players let through the whitelist, every permission only when elevated
  # plugin-tools: true  # Tools other plugins publish, and Foreman's tools for other plugins
  # learning: true      # The AI saving lessons and skills for every later session

mcp:
  # Bind to localhost unless the AI client runs on another machine. The server speaks plain HTTP: beyond this
  # machine, put it behind TLS (a reverse proxy) or a VPN.
  host: 127.0.0.1
  # When it's taken (another server on this machine), the next free port up to port + 20, else any free one: the log
  # and mcp.json (this folder) say which. strict-port: true fails instead, for setups that need this very port.
  port: 25599
  strict-port: false
  # Clients send "Authorization: Bearer <token>". Empty: the token in mcp-token.txt (this folder), made at the first
  # start. Players with foreman.client also get their own through the mod (tokens.yml), accepted besides this one.
  token: ""
  # The URL the mod gives its AI. Default: http://<host>:<port>/mcp, with the server's IP when host is 0.0.0.0.
  # Set it when players reach the server through another address; {port} in it is the port the MCP server took.
  public-url: ""

relay:
  # Tool calls a player may make per minute through their game connection (their calls run one at a time).
  calls-per-minute: 120

test-players:
  # Test players count only when they connect from this machine (anyone may take a name on an offline-mode server).
  local-only: true
  # What an elevated test player gets besides every registered permission ("*" for plugins that check it as such).
  grant: ["*"]

# World used when a tool call names none. Empty: the world of the first online player.
default-world: ""

limits:
  max-edit-blocks: 1000000
  max-read-blocks: 20000
  max-render-blocks: 800000

# Assets for the textured renders (look, model previews). The client jar is downloaded from Mojang into cache/
# unless client-jar is set; packs are the server's resource packs (zips or folders), later ones winning.
resources:
  client-jar: ""
  packs: []
```

## Features

Everything Foreman can do is a feature, on or off. `preset: dev` turns them all on, for dev and build servers.
`preset: production` keeps only what's safe on a live server: the read-only [server tools](dev-tools.md) (`server_log`,
`server_plugins`, `server_status`, `server_profile`, `server_listeners`), for players with `foreman.admin`, through their
game connection ([the relay](#the-relay)). It opens no port. Under `features:`, set one on or off over the preset:

```yaml
preset: production
features:
  mcp: true   # the read-only tools for an MCP client on this machine too
```

| Feature | What | `dev` | `production` |
|---|---|---|---|
| `mcp` | The MCP server: its port, the server's token, players' tokens through the mod | on | off |
| `relay` | The mod's AI reaches the tools through the player's game connection, no port ([the relay](#the-relay)) | on | on |
| `diagnostics` | `server_log`, `server_log_mark`, `server_plugins`, `server_status`, `server_profile`, `server_listeners` | on | on |
| `console` | `server_command` (as the console or a player), `server_reload_plugin` | on | off |
| `world` | The [world tools](world-tools.md) that read it: context, scans, renders, checks (with FastAsyncWorldEdit) | on | off |
| `world-edit` | The world tools that change it: edits, terrain, buildings, assets, furniture | on | off |
| `testing` | The [dupe and test tools](dupe-testing.md), the mod's network profiles and packet drops | on | off |
| `test-players` | [Test players](../ai/testing.md#test-players): registered names through the whitelist, every permission only when elevated | on | off |
| `plugin-tools` | Tools [other plugins publish](../developers/foreman-tools.md), and Foreman's tools for other plugins | on | off |
| `learning` | `learn`, `lessons`, `save_skill`: the AI keeping lessons and skills for later sessions | on | off |

A feature that's off publishes none of its tools, whoever registers them, and costs nothing: with neither
`diagnostics` nor `console`, Foreman doesn't keep the server log either. `read_skill` is always there. Permissions
still apply on top ([Permissions and tokens](permissions.md)).

The log says what's on when the plugin starts, `(set)` after the features `features:` changed:

```text
[Foreman] Preset production: mcp (set), diagnostics on; console, world, world-edit, testing, plugin-tools, learning off (config.yml, /foreman status)
```

`/foreman status` gives the same (hover a feature for what it is), whether the MCP server listens and where, and how
many tools are published; `/foreman tools` lists them.

## The relay

With the `relay` feature, the mod's AI reaches the server's tools through the player's game connection: the mod sends
each MCP request to the server on the `foreman:mcp` plugin channel, and the answer comes back the same way. Nothing
listens on a port, and no token is needed: the connection is the login. It's how a live server is debugged, from the
developer's game, and it works behind a proxy (Velocity and BungeeCord pass plugin messages on).

- **Who:** players with `foreman.client`, calling as themselves: the server tools still need `foreman.admin`, the test
  tools `foreman.test`, and the features decide which tools there are at all.
- **The mod chooses:** a server with no MCP address for the player (no `mcp` feature), or one whose address doesn't
  answer from their computer, gets its tools through the relay; the hub says *through the game*. Outside agents use it
  too ([the server's tools through your game](../ai/outside-agents.md#the-servers-tools-through-your-game)).
- **Limits:** a player's requests run one after the other, `relay.calls-per-minute` tool calls a minute at most
  (default 120); a request is at most 1 MiB, an answer 16 MiB.
- **Audit:** every tool call (through the relay or the MCP server) is a line in `plugins/Foreman/audit.log`, readable
  by the server's user only: when, how it came (`relay`, `http`), the player, the tool, its arguments (cut at 500
  characters), whether it worked and how long it took. Relayed calls also log a line
  (`[Foreman] Relay: Steve called server_status (ok, 35 ms)`). Past 10 MB the file moves to `audit.log.1`.

```yaml
relay:
  # Tool calls a player may make per minute through their game connection (their calls run one at a time).
  calls-per-minute: 120
```

## The port

Several servers on one machine can share a config: when `mcp.port` is taken, the MCP server listens on the next free
port up to `port + 20`, else on any free one, and the log says so:

```text
[Foreman] MCP port 25599 on 127.0.0.1 is taken (Address already in use): listening on 25600 instead. plugins/Foreman/mcp.json gives the address; mcp.strict-port: true fails instead
```

The mod's AI follows: the address the server gives it is the port it took. A `public-url` follows only with `{port}` in
it (`http://build.example.net:{port}/mcp`). Where the port must not change (a firewall rule, a reverse proxy or a port
forward to it, a `public-url` without `{port}`), set `strict-port: true`: the plugin then fails to start when the port
is taken, as it always did.

## The connection file

While its MCP server runs, the plugin writes `plugins/Foreman/mcp.json`, for scripts, launchers and agents on this
machine that look for the server: where it listens, its token, and what tells it from another server or a stale file.

```json
{
  "url": "http://127.0.0.1:25600/mcp",
  "token": "fm_…",
  "port": 25600,
  "pid": 41872,
  "server": "survival-test",
  "version": "1.2.0",
  "started_at": "2026-11-04T18:12:03Z"
}
```

| Field | What |
|---|---|
| `url` | The MCP endpoint from this machine (`127.0.0.1` when `host` is `0.0.0.0`) |
| `token` | The server's token (`mcp.token`, or `mcp-token.txt`) |
| `port` | The port it listens on |
| `pid` | The server's process |
| `server` | The name of the server's folder |
| `version` | Foreman's version |
| `started_at` | When the MCP server started listening (UTC) |

It's written once the server listens and removed when the plugin stops. After a crash it stays: check that `pid` is
still running. The fields it shares with the mod's [`client-mcp.json`](../ai/outside-agents.md#turning-it-on) have the
same names.

!!! warning
    The file holds the server's token, which is console access (`server_command`). It's readable by the server's user
    only, like `mcp-token.txt`: anything that reads it can act as the console.

## The plugin's folder

| Path | What |
|---|---|
| `config.yml` | The settings above |
| `mcp-token.txt` | The MCP token, when `mcp.token` isn't set (readable by the server's user only) |
| `mcp.json` | [Where the MCP server listens](#the-connection-file), with its token, while it runs (readable by the server's user only) |
| `tokens.yml` | Players' MCP tokens (readable by the server's user only) |
| `audit.log` | Every tool call, a JSON line each ([the relay](#the-relay)), readable by the server's user only |
| `catalog/` | [Building templates](building-templates.md): `rooms.yml`, `buildings.yml`, `furniture.yml` |
| `styles/` | [Style guides](building-templates.md#style-guides) for the AI |
| `assets/` | The [schematic library](assets.md) |
| `prompts/` | Ready-made tasks ([prompts](lessons-prompts.md#prompts)) |
| `lessons.md` | What the AI learned on this server ([lessons](lessons-prompts.md#lessons)) |
| `worlds/` | World data exported with `/foreman world export` |
| `cache/` | The client jar used for textured renders |

## Commands

`/foreman` (alias `/fm`, permission `foreman.use`):

| Command | Does |
|---|---|
| `/foreman status` | The preset and its [features](#features), the MCP address, tools, assets, areas and undoable changes |
| `/foreman tools [filter]` | Every MCP tool by provider (Foreman's and other plugins'), those that edit in gold |
| `/foreman mark <name> [note]` | Name your WorldEdit selection (or where you stand) as an area |
| `/foreman unmark <name>`, `/foreman marks` | Remove an area, list them |
| `/foreman undo [count]` | Revert the last AI changes |
| `/foreman asset save <name> [tags…]` | Save your WorldEdit selection to the schematic library |
| `/foreman token revoke <player>` | Revoke a player's token (a new one comes on their next join) |
| `/foreman world show \| export \| import [world]` | A world's profile and areas, as YAML in `worlds/` |
