# Setup and configuration

1. Put `foreman-server-1.1.1.jar` in `plugins/` (with FastAsyncWorldEdit for the world tools) and start the server.
   The log shows the MCP address, `http://127.0.0.1:25599/mcp` by default.
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
mcp:
  # Bind to localhost unless the AI client runs on another machine. The server speaks plain HTTP: beyond this
  # machine, put it behind TLS (a reverse proxy) or a VPN.
  host: 127.0.0.1
  port: 25599
  # Clients send "Authorization: Bearer <token>". Empty: the token in mcp-token.txt (this folder), made at the first
  # start. Players with foreman.client also get their own through the mod (tokens.yml), accepted besides this one.
  token: ""
  # The URL the mod gives its AI. Default: http://<host>:<port>/mcp, with the server's IP when host is 0.0.0.0.
  # Set it when players reach the server through another address.
  public-url: ""

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

## The plugin's folder

| Path | What |
|---|---|
| `config.yml` | The settings above |
| `mcp-token.txt` | The MCP token, when `mcp.token` isn't set (readable by the server's user only) |
| `tokens.yml` | Players' MCP tokens (readable by the server's user only) |
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
| `/foreman status` | The MCP address, tools, assets, areas and undoable changes |
| `/foreman tools [filter]` | Every MCP tool by provider (Foreman's and other plugins'), those that edit in gold |
| `/foreman mark <name> [note]` | Name your WorldEdit selection (or where you stand) as an area |
| `/foreman unmark <name>`, `/foreman marks` | Remove an area, list them |
| `/foreman undo [count]` | Revert the last AI changes |
| `/foreman asset save <name> [tags…]` | Save your WorldEdit selection to the schematic library |
| `/foreman token revoke <player>` | Revoke a player's token (a new one comes on their next join) |
| `/foreman world show \| export \| import [world]` | A world's profile and areas, as YAML in `worlds/` |
