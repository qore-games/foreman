# Security and privacy

Foreman gives an AI the hands of an admin: it runs commands, edits the world and reads logs. This page lists what it
can reach, what leaves your computer and your server, and how to keep it to the people you trust.

## What leaves your computer

Foreman collects nothing and has no telemetry. It connects to:

| From | To | What |
|---|---|---|
| The mod | The AI you chose: Claude Code and opencode (their own provider), or OpenRouter | Your prompts and what the AI's tools return: screenshots, chat lines, packets, logs, files of the chat's folders |
| The mod | The server's Foreman plugin | Tool calls, with your personal token |
| The plugin | Mojang (`piston-meta.mojang.com`), once | The client jar used for textured renders, kept in `cache/` (unless `resources.client-jar` is set) |

**Ask the AI** in the packet viewer sends the captured packets to your AI: they hold chat, player names and UUIDs,
coordinates and inventories. Record only what you mean to share.

## What reaches the AI

The AI reads what its tools return, and some of that is written by other people: chat lines, signs, books, item and
entity names, log lines, packets, skills shipped in plugins. Text in any of them can try to steer it ("ignore your
instructions and…"), so:

- **Give a chat only what it needs.** Commands run as you: as an op, a chat can do anything you can. Turn off a
  chat's **Commands** or **Vision** switch when it doesn't need them, and add only folders you trust to its workspace.
- **Agents read their workspace's own settings.** Claude Code loads `.claude/settings.json` from the folders it works
  in (hooks, permissions), opencode loads `opencode.json`. A folder from someone else can change what they may do.
- **Read before you confirm.** The world tools can be undone (`/foreman undo`); commands and file edits can't.

## The server

- **On a live server, use `preset: production`** ([Features](server/setup.md#features)): no MCP port, no console
  tools, no world edits, no dupe testing, no tools from other plugins; only the read-only server tools, for players
  with `foreman.admin`, through their game connection ([the relay](server/setup.md#the-relay)). `/foreman status`
  shows what's on.
- **Every tool call is audited** in `plugins/Foreman/audit.log`: who, which tool, its arguments, through what.
- **Every MCP request needs a token**, on localhost too: the server's (`mcp.token`, or `mcp-token.txt` that Foreman
  makes) or a player's personal one. Requests from web pages are refused, so a site open in your browser can't reach
  it.
- **The server's token and `foreman.admin` are console access:** `server_command` runs commands as the console. Keep
  them to people you'd give the console to.
- **`foreman.client`** lets a player's AI use the world tools and plugins' tools as them. The tools that act as the
  console need `foreman.admin` ([Permissions and tokens](server/permissions.md)).
- **The MCP server speaks plain HTTP.** It listens on `127.0.0.1` by default. To reach it from other machines, put it
  behind TLS (a reverse proxy) or a VPN, and don't open the port to the internet.
- `mcp-token.txt`, `tokens.yml` and `mcp.json` (the MCP address with the server's token, for scripts and agents on the
  server's machine) are readable by the server's user only.

## Your computer

- The mod's tokens and keys (`config/foreman/settings.json`, `client-mcp.json`) are readable by your user only. On
  Windows they get your profile folder's permissions, private by default.
- The client's MCP server listens on `127.0.0.1` only, refuses web pages, and needs a token: a new one each launch for
  chats, a saved one for [outside agents](ai/outside-agents.md), which are off until you allow them (in the hub, or
  with the game's `-Dforeman.mcp.external=true`).
- A chat's file tools stay inside its folders, links included.

## Reporting a vulnerability

Report it privately through the repository's
[Security tab](https://github.com/qore-games/foreman/security/advisories/new), not in a public issue.
