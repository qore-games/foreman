# Foreman: notes for AI agents

Foreman is a multi-tool for Minecraft server owners, admins and plugin developers: a Fabric client mod (works on any
server) and an optional Paper plugin, for Minecraft 26.3. Users read the docs in `docs/` (published at
<https://qore-games.github.io/foreman/>, with every page in one file at `/llms-full.txt`). This file is for agents
working on the code.

## Modules

| Module | What | Language |
|---|---|---|
| `client` | The Fabric mod: the hub (`ui/HubScreen.kt`), packet viewer (`packets/`), HUD inspector (`hud/`), locations (`locations/`), AI chats (`agent/`), the client MCP server and its tools (`mcp/`) | Kotlin, Java mixins |
| `server` | The Paper plugin: the MCP server (`mcp/`), server tools (`tools/ServerTools.kt`), world tools (`tools/`, `world/`), skills (`skills/`), integrations with other plugins (`integrations/`), the bridge to the mod (`bridge/`) | Kotlin |
| `server-api` | What plugins compile against: `ForemanTools` (tools and skills), `ForemanWorlds`, `FurnitureProvider` | Kotlin, Java-friendly |
| `api` | The location protocol, and `ForemanTool`: the names of Foreman's own tools, shared by the client and the server | Kotlin |
| `paper` | The location API's Paper side, shaded into other plugins | Kotlin |

## Build and try

- JDK 25. `./gradlew build` (add `-x test`), `./gradlew :client:runClient` for a dev client.
- Jars: `client/build/libs/foreman-<version>.jar`, `server/build/libs/foreman-server-<version>.jar`.
- A throwaway Paper server is the way to test the plugin: Paper, Foreman, and the plugins involved (FastAsyncWorldEdit
  for the world tools), Foreman's MCP on `mcp.port`. Call tools with JSON-RPC over HTTP (`initialize`, `tools/list`,
  `tools/call`). Test with and without the optional plugins: the plugin must enable cleanly either way.
- Docs: `pip install -r docs/requirements.txt`, `zensical serve`; `zensical build --clean` then
  `python3 -I scripts/llms.py site` writes `llms.txt` and `llms-full.txt`.

## Adding things

- **A server tool:** a `ForemanTool` entry in `api/…/ForemanTool.kt` (its `group`, and `requires` when it's published
  only with another plugin), then a `Tool(ForemanTool.X, description, schema { … }) { args -> … }` in a tool object
  under `server/…/tools/`, added where the others of its kind are (`WorldTools.start` for world tools,
  `Foreman.onEnable` otherwise). The startup check warns when the enum and the tools differ.
- **A skill for a plugin** (preferred over a tool when the plugin has commands for it): a markdown file in
  `server/src/main/resources/skills/<name>.md` with front matter `name`, `description` (when to read it), `plugin`
  (its `plugin.yml` name: the skill is offered while it's enabled). Only commands checked on a server with that
  plugin. List it in `docs/server/integrations.md`.
- **Code tools for another plugin** (when its commands fall short): a class in `server/…/integrations/` using its API,
  an `Integration("<PluginName>") { XTools.all(it) }` entry in `Integrations.kt`, the API as `compileOnly` in
  `server/build.gradle.kts`, `ForemanTool` entries with `requires = "<Plugin> on the server"`.
- **A client tool:** `ClientMcp.register(ClientTool(name, description, schema) { scope, args -> … })` in a tools
  object under `client/…/mcp/` (or the feature's package), registered from `ForemanClient.onInitializeClient`. A tool
  that only looks says so (`acts = { false }`, or a test of its arguments): the others take the `ClientLock` lease.
  Sessions that started before you rebuilt the mod start again after the game restarts (their session ids are
  unknown) and see the new tools; one without a session id calls them with
  `scripts/client-call.py --connection <game dir>/config/foreman/client-mcp.json <tool> '<json args>'` (`--list`).
- **Docs:** the page of the feature, the tool reference (`docs/ai/tools.md`), the navigation in `zensical.toml`.

## Rules

- Nothing may cost anything while unused: no listener, task or recording doing work while its feature is off.
- Optional plugins stay optional. A class loaded without FastAsyncWorldEdit (or another plugin) must not have that
  plugin's types in its method signatures or catch clauses (Bukkit reflects every method of a listener; the JVM loads
  caught exception types): keep them in classes loaded only when the plugin runs.
- Generic: no server's own names or content in Foreman's code, defaults or examples.
- Tool descriptions are read by the AI to choose: say what it does, what it returns, when to use it, concisely.
- Code style: the code around it. Short functions, a doc comment that says what a thing is or does, few comments
  inside, for the why.
