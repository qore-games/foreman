# Outside agents (client MCP)

The client runs a small MCP server on your computer (loopback only). Chats use it, and when you allow it, so can
**AI sessions outside the game**: Claude Code in a terminal, or another agent on this computer. That's how you
implement fast and test end to end: the agent edits your plugin in your editor, deploys it to your dev server, then
plays the change in the running client (screens, commands, entities, items, packets, HUD) and reads what happened.

## Turning it on

1. In the hub's **AI → Agents**, allow agents outside the game.
2. Optionally forbid them to run commands as you, or to take screenshots.
3. Copy the `claude mcp add` command and run it once in the folder Claude Code works in.

The address and a token are also written to `config/foreman/client-mcp.json` (readable by you only), for other agents:

```json
{"url": "http://127.0.0.1:25601/mcp/external", "token": "…", "relay": "http://127.0.0.1:25601/relay/external",
 "claude": "claude mcp add --transport http foreman-client …", "state": "in_game", "pid": 41872, "lean": false,
 "version": "1.2.0+26.3", "started_at": "2026-11-04T18:12:03Z"}
```

The port stays the same from one launch to the next (`25601`, or another free one when it's taken). `state` says where
the game is, for a script waiting until it can test: `starting` (loading), `menu` (no world: the title screen, or a
server that refused it), `joining`, `in_game`, then `stopped` when the game closes (quit, or `SIGTERM`). `pid` is the
game's process, `version` the mod's, `started_at` when its MCP server started (UTC).

The [server plugin](../server/setup.md#the-connection-file) writes the same kind of file for the server's MCP,
`plugins/Foreman/mcp.json` in the server's folder (`url`, `token`, `port`, `pid`, `server`, `version`, `started_at`):
an agent that tests on a dev server reads both, and finds the right server's MCP when several run on one machine.

## The server's tools through your game

On a server with the [relay](../server/setup.md#the-relay) (a live server opens no MCP port), your agent reaches the
server's tools through the game you're connected with: `relay` in `client-mcp.json`, with the same token, as you (your
permissions, every call in the server's audit log). Add it beside the client's:

```sh
claude mcp add --transport http foreman-server http://127.0.0.1:25601/relay/external \
  --header "Authorization: Bearer <the token of client-mcp.json>"
```

It answers once the game is on such a server; elsewhere each call says the server doesn't relay. The mod's chats do
the same by themselves on those servers.

## Without the hub

Scripts and launchers that start the game set outside access with system properties among its JVM arguments. They hold
for that launch only, over the hub's settings, and nothing is saved:

| Property | Does |
|---|---|
| `-Dforeman.mcp.external=true` | Outside agents may connect, without the hub's switch (`false` keeps them out). The token is made once and kept |
| `-Dforeman.mcp.port=<n>` | The port; `0` for any free one, which the file gives. Without it, `25601` or another free one |
| `-Dforeman.mcp.allow=commands,screenshots` | What outside agents may do: only what's listed (empty: neither) |

Delete `client-mcp.json` before a launch, or check its `pid`: after a crash it still says the last state. For test
clients that cost as little as possible, see [lean clients](lean-clients.md).

## Watching the game live

The same server streams what the game's window shows, screens and HUD included, for a launcher or a dashboard to
watch a client (a [lean](lean-clients.md) one without a window too): `GET /stream` with the token of
`client-mcp.json` in its `Authorization` header (a browser's `<img>` can't send one: show the stream through a page or
a program that does).

```sh
curl -N -H 'Authorization: Bearer <token>' 'http://127.0.0.1:25601/stream?fps=10' | ffplay -f mjpeg -
```

| Parameter | Default | Range | Does |
|---|---|---|---|
| `fps` | `10` | 1 to 30 | Frames per second at most |
| `width` | `640` | 160 to 1920 | Frames scaled down to this width at most, keeping their shape (never up) |
| `quality` | `70` | 30 to 95 | JPEG quality |

The answer is Motion JPEG, as cameras send it: `Content-Type: multipart/x-mixed-replace; boundary=foreman-frame`, then
for each frame `--foreman-frame`, `Content-Type: image/jpeg`, `Content-Length` and `X-Frame-Time` (when it was drawn,
in milliseconds since 1970) headers, a blank line and the JPEG. It goes on until you close the connection or the game
stops. Only the newest frame is ever sent: a reader that falls behind skips frames instead of seeing them late, and one
that reads nothing for a minute is dropped.

- `401` without the right token, `403` for a request with an `Origin` (web pages) or when outside agents may not take
  screenshots, `405` for anything but `GET`, `503` when 4 streams are already open.
- Frames stay in memory: nothing is written to disk, and nothing runs while no stream is open. While one is, the game
  reads its frames back and each stream scales and encodes them: a few percent of a core at the defaults, besides
  drawing them.

## While an agent works

While a session uses the tools (a call in the last 5 minutes), the game doesn't pause when its window loses focus,
so screenshots and clicks keep working while you're in the terminal. Idle again, it pauses as usual.

Outside sessions get no workspace folders: they have their own file tools.

## One agent at a time

Two agents testing in the same client at once undo each other's work (one teleports you while the other measures),
and both spend their time on errors that aren't theirs. So the client is driven by one MCP session at a time: the
first call of a tool that acts (commands, input, the camera, screens, tests, recordings) takes it, and while it has it,
other sessions' acting calls are refused with who has it and until when. Tools that only look (context, entities,
logs, stats) stay open to all.

It's a lease, not a lock: it lasts 2 minutes past the holder's last call, or the idle time it asked for with
`client_lock acquire` (with a label saying what it's doing), and goes as soon as it releases or its session ends.
Nothing is saved, so a crashed agent or a restarted game never leaves the client taken. A waiting agent calls
`client_lock wait` instead of retrying.

You take the client back with `/foreman-lock take` (or the key in Foreman's controls): agents are refused until
`/foreman-lock free`. `/foreman-lock` says who has it. A chat line tells you when an agent takes the client for a
session and when it's done.

## After the mod is updated

Each agent's MCP session has an id the game gives it, kept in memory. When the game restarts with a new version of the
mod, it no longer knows those ids and tells the agents so, and they start a new session, which lists the tools again:
new tools are there without restarting the agent (Claude Code does this on its own).

## Example

```text
> Open /shop as me, click the second item, and tell me whether the price in the lore matches the config.
```

The agent calls `client_run_command`, `client_screen`, `client_click`, `client_item`, then reads your plugin's config
with its own tools.

