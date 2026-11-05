# ForemanTools

Publish tools the AI can call, next to Foreman's own, with your plugin as their provider. They show up in
`/foreman tools`, in the client's hub and in every chat on the server. Chats that load tools by group (OpenRouter)
see them as one group named after your plugin: keep its tools few and their descriptions short.

## Setup

`compileOnly` on `games.qore:foreman-server-api` and a soft dependency on Foreman:
[Add Foreman to your plugin](install.md#the-server-api). Register in `onEnable` (Foreman enables first). Your tools are removed when your plugin disables, so a reload
registers them again.

## A command as a tool

The simplest tool runs one of your commands. `{argument}` placeholders are filled from the call, and the AI gets back
everything the command printed (like `server_command`).

=== "Java"

    ```java
    ForemanTools tools = Bukkit.getServicesManager().load(ForemanTools.class); // null without Foreman
    if (tools != null)
        tools.registerCommand(this, "npc_reload", "Reloads the NPCs of a zone", "npcs reload {zone}",
            Map.of("zone", "Zone id"), false);
    ```

=== "Kotlin"

    ```kotlin
    val tools = Bukkit.getServicesManager().load(ForemanTools::class.java) // null without Foreman
    tools?.registerCommand(this, "npc_reload", "Reloads the NPCs of a zone", "npcs reload {zone}",
        mapOf("zone" to "Zone id"), false)
    ```

The last argument runs it as the calling player (who must be online), with their permissions. Otherwise it runs from
the console, and players calling it need `foreman.admin`, like `server_command`. Each value is one word, so a call can't
add arguments to the command, except the one whose placeholder ends it (`broadcast {message}`).

## A tool of your own

=== "Java"

    ```java
    tools.register(this, new ExternalTool(
        "npc_list",
        "Lists the NPCs of a world with their positions. Use it before moving or editing one.",
        """
        {"type": "object", "properties": {"world": {"type": "string", "description": "World name"}}}
        """,
        false, // modifies: changes are announced to ops with foreman.watch
        call -> {
            World world = Bukkit.getWorld(call.string("world"));
            if (world == null) return ToolReply.error("No such world");
            String text = tools.onMainThread(() -> describeNpcs(world));
            return ToolReply.ok(text);
        }));
    ```

=== "Kotlin"

    ```kotlin
    tools.register(this, ExternalTool(
        "npc_list",
        "Lists the NPCs of a world with their positions. Use it before moving or editing one.",
        """{"type": "object", "properties": {"world": {"type": "string", "description": "World name"}}}""",
        false, // modifies: changes are announced to ops with foreman.watch
    ) { call ->
        val world = call.string("world")?.let { Bukkit.getWorld(it) }
        if (world == null) ToolReply.error("No such world")
        else ToolReply.ok(tools.onMainThread { describeNpcs(world) })
    })
    ```

- **Names:** lowercase letters, digits and `_`. Foreman's own names and other plugins' are refused.
- **Descriptions:** the AI reads them to choose a tool. Say what it does and when to use it.
- **Handlers** run off the main thread: use `onMainThread` for the world.
- **Replies:** `ToolReply.ok(text)`, `ToolReply.error(text)`, or `new ToolReply(text, false, images)` with PNG bytes
  the AI can look at.
- **Calls:** `call.argument(name)` (String, Double, Boolean, List, Map or null), `call.string`, `call.number`,
  `call.getPlayer()` (who asked, when online), `call.getArguments()` (the JSON).

`tools.tools()` lists every tool with its provider; `unregister(owner, name)` removes one.

## Your values in server traces

`server_trace` records the server's view of a player tick by tick (where it heard they are, their on-ground flag, the
keys they hold, entities' boxes) for movement and gameplay bugs. Add what your plugin knows of them, and the AI sees it
in the same rows, with each number's range in the summary as `<your plugin>.<key>`:

=== "Java"

    ```java
    tools.registerTraceValues(this, player -> {
        Ride ride = rides.get(player.getUniqueId());
        if (ride == null) return null; // nothing to add for this player
        return Map.of("carried", ride.carried(), "above", ride.above());
    });
    ```

=== "Kotlin"

    ```kotlin
    tools.registerTraceValues(this) { player ->
        rides[player.uniqueId]?.let { mapOf("carried" to it.carried, "above" to it.above) }
    }
    ```

Values are numbers, texts, booleans or vectors. Foreman calls you on the server thread, each tick a player is traced and
only then. One provider per plugin, removed when it disables (`unregisterTraceValues(owner)` before).

