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
{"url": "http://127.0.0.1:25601/mcp/external", "token": "…", "claude": "claude mcp add --transport http foreman-client …"}
```

The port stays the same from one launch to the next (`25601`, or another free one when it's taken).

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

