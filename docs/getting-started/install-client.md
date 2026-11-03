# Install the client

Put the jar for your Minecraft version in your instance's `mods/` folder (`foreman-1.1.1+26.3.jar` for 26.3,
`foreman-1.1.1+26.2.jar` for 26.2), with:

- Fabric Loader 0.19.3 or later
- [Fabric API](https://modrinth.com/mod/fabric-api) 0.160.5 or later on 26.3, 0.152.2 or later on 26.2
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

Download it from the [releases](https://github.com/qore-games/foreman/releases): each release has the mod for each
Minecraft version (`foreman-<version>+<minecraft>.jar`) and the server plugin (`foreman-server-<version>.jar`).
