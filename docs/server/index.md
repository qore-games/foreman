# Server plugin

The Paper plugin runs an MCP server inside your Minecraft server. The client mod's chats connect to it, and so can any
MCP client (Claude Code, opencode…). It brings:

- [Dev-server tools](dev-tools.md): commands with everything they printed, the log, stale jars, a profiler, event
  listeners, plugin reloads.
- [World tools](world-tools.md): read and edit the world, terrain and roads, buildings from
  [templates](building-templates.md), checks, undo.
- [Dupe testing](dupe-testing.md): count an item everywhere, run inventory attacks against a plugin, and catch the ones
  that duplicate items (needs `foreman.test`).
- [World context](world-context.md): each world's profile, named areas and points, kept in the world.
- Tools other plugins publish through [`ForemanTools`](../developers/foreman-tools.md).

Start with [Setup and configuration](setup.md) and [Permissions and tokens](permissions.md).
