# Install the client

Put `foreman-1.0.0.jar` in your instance's `mods/` folder, with:

- Fabric Loader 0.19.3 or later, for Minecraft 26.3
- [Fabric API](https://modrinth.com/mod/fabric-api) 0.160.5 or later
- [Fabric Language Kotlin](https://modrinth.com/mod/fabric-language-kotlin) 1.13.12 or later

Any later version works: the mod asks for the oldest ones it was built against.

The mod keeps its files in `config/foreman/`:

| Path | What |
|---|---|
| `settings.json` | Every setting (readable by you only: it holds tokens and keys) |
| `chats/<server>/` | Your chats, in the folder of the server each started on (every chat is listed on every server) |
| `local-locations/` | Your local location sets |
| `packets/` | Packet recordings written by `/packets export` |
| `client-mcp.json` | How outside agents connect, while you allow them |

Download it from the [releases](https://github.com/qore-games/foreman/releases): each release has the mod
(`foreman-<version>.jar`) and the server plugin (`foreman-server-<version>.jar`).
