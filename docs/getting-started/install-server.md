# Install the server plugin

The plugin is optional: the client mod works on any server. Install it where you want the
[dev-server tools](../server/dev-tools.md), the [world tools](../server/world-tools.md) or tools other plugins publish.

1. Put `foreman-server-1.2.0.jar` in `plugins/`. For the world tools, add
   [FastAsyncWorldEdit](https://modrinth.com/plugin/fastasyncworldedit) too: without it the plugin has the dev-server
   tools and other plugins' tools, and none that edit the world (the log says so at start).
2. Start the server. The log shows the MCP address, `http://127.0.0.1:25599/mcp` by default (the next free port when
   it's taken); other MCP clients send the token Foreman made in `plugins/Foreman/mcp-token.txt`.
   `plugins/Foreman/mcp.json` gives both while the server runs.
3. Join with the client mod. Players with `foreman.client` (ops by default) are connected to the server's tools.

Then read [Setup and configuration](../server/setup.md) to let players on other machines connect, and
[Permissions and tokens](../server/permissions.md) for who can use what.

!!! warning
    The world tools edit the world, and the dev-server tools run commands as the console. Give `foreman.client` and
    `foreman.admin` only to people you trust with that, and keep the MCP server on localhost unless you need more.
    On a live server, set `preset: production` in `plugins/Foreman/config.yml`: only the read-only server tools stay,
    and no port opens ([Features](../server/setup.md#features)).
