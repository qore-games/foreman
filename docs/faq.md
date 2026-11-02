# FAQ and known limits

## Does Foreman cost anything when I don't use it?

No. Packet recordings run only while you record, overlays are drawn only when they're on, and the client's MCP server
only answers outside agents when you allow them. On the server, the dev-server tools only do work when called.

## Do I need the server plugin?

No. The packet viewer, the HUD inspector, local location sets and AI chats with the client's tools work on any server.
Plugin locations need a plugin that sends them ([location API](developers/locations-api.md)). The dev-server tools,
the world tools and other plugins' tools need the plugin.

## Which AI does it use?

Yours: Claude Code or opencode installed on your computer with your own login, or OpenRouter with your own key. See
[Backends](ai/backends.md).

## Can I use it on a production server?

Yes. The client mod does nothing on a server until you use it. The server plugin runs without FastAsyncWorldEdit:
then it has the dev-server tools and your plugins' tools, and none that edit the world. With FastAsyncWorldEdit, the
world tools are on too. Either way, keep `foreman.client` and `foreman.admin` to staff you trust, and the MCP server on
localhost unless you need more ([Permissions and tokens](server/permissions.md)).

## Chats can't start Claude Code

If your launcher is a Flatpak, see [Flatpak launchers](getting-started/flatpak.md). Otherwise check the command in the
hub's AI settings (default `claude`) and that it runs in a terminal.

## Can I use it on my commercial server, or in a paid plugin?

Yes. Foreman is under the [MIT License](https://github.com/qore-games/foreman/blob/main/LICENSE): use it, change it,
share it and build on it for any purpose, as long as the license's notice stays with the copies.

## Known limits

- The undo history is kept in memory and is lost on restart.
- `check` tells terrain from builds by block type, so a build made of plain stone or dirt looks like terrain.
- Paths don't bridge water.
- `server_reload_plugin` needs PlugMan or PlugManX. Plugins that depend on the reloaded one keep its old classes:
  reload them too, or restart.
