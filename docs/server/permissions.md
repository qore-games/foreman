# Permissions and tokens

| Permission | Gives | Default |
|---|---|---|
| `foreman.use` | The `/foreman` command | ops |
| `foreman.client` | Being connected to the server's tools when joining with the mod (a personal token) | ops |
| `foreman.admin` | With a personal token: the [dev-server tools](dev-tools.md), `learn` and removing lessons, `save_skill`, `worldedit` commands other than block edits (WorldGuard, schematics), plugins' tools that run console commands | ops |
| `foreman.test` | The [dupe-testing](dupe-testing.md) tools (item ledger, inventory watch and restore), players kept and traced for [tests](../ai/testing.md) (`test_player`, `server_trace`), and the mod's packet options (`network`, `drop` on `inventory_actions` and test steps) | ops |
| `foreman.watch` | A chat line for each change an AI makes to the world, with who asked | ops |

## Tokens

- **Personal tokens:** each player with `foreman.client` gets one through the mod when they join, kept in
  `tokens.yml`. Calls with it act as that player: the tools above then need `foreman.admin`. A player who loses
  `foreman.client` can't use theirs any more, and it's revoked when they next join. `/foreman token revoke <player>`
  revokes one; a new one comes on their next join.
- **The server's token** (`mcp.token`, or `mcp-token.txt` that Foreman makes when it's empty): for other MCP clients
  (Claude Code in a terminal, scripts). It needs no permission: it's the console's.

Every request needs one of them, on localhost too. The MCP server refuses requests from web pages (with an `Origin`
header) so that a site open in your browser can't reach it.

!!! warning
    `server_command` runs commands as the console. Treat `foreman.admin` and the server's token like console access.
    More in [Security and privacy](../security.md).
