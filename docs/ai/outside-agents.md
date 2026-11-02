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

## Example

```text
> Open /shop as me, click the second item, and tell me whether the price in the lore matches the config.
```

The agent calls `client_run_command`, `client_screen`, `client_click`, `client_item`, then reads your plugin's config
with its own tools.

