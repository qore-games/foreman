# Dev-server tools

For an AI (or you) working on plugins against a dev server: they replace RCON, log files and guesses. With a personal
token, the player needs `foreman.admin` (ops have it).

<video src="../assets/videos/ai-debug.mp4" controls muted loop playsinline preload="metadata" width="100%"></video>

*A chat fixing a plugin's bug with them: `server_log` for the stack trace, `server_log_mark` and
`server_reload_plugin` after the fix, then the command again as the player ([the run](../ai/examples.md#fix-a-plugins-bug)).*

| Tool | Does |
|---|---|
| `server_command` | Runs a command on the server thread and returns everything it printed: the replies, the log lines logged meanwhile (exceptions with their frames), that it's unknown (with close names) or its syntax is wrong. As the console, or `as_player`. `wait_for` a text when it finishes later |
| `server_log` | The log kept in memory (the last 10,000 lines): by `level`, `plugin`, `contains`, `since` start, the last reload or a mark. Repeated lines are folded, exceptions show their causes and the frame they came from |
| `server_log_mark` | Marks the log now, to read only what follows (before a deploy or a test) |
| `server_plugins` | Each plugin's version, jar and dependencies, and whether the server runs the jar on disk: **STALE** (with the classes that differ) or **GONE**. Then the jars nothing loaded |
| `server_status` | Version, uptime, TPS, MSPT, memory, CPU, GC, players, worlds (chunks, entities, block entities), tasks by plugin |
| `server_profile` | A sampling profiler of the server thread (or other threads): each plugin's share, how the server entered it, its hottest lines |
| `server_listeners` | An event's listeners in call order (priority, plugin, method, ignores cancelled), or the events a plugin handles |
| `server_reload_plugin` | Reloads a plugin through PlugMan or PlugManX and reports how it went (published only while one of them runs) |

## A deploy loop

1. `server_log_mark name=before-deploy`
2. Build, copy the jar, `server_reload_plugin` (or restart).
3. `server_plugins stale_only=true`: is the server running the new jar?
4. `server_command` the feature, `server_log since=before-deploy` for what went wrong.

## Stale plugin jars

`server_plugins` compares the jar each plugin's class loader opened (its entries as they were when it loaded, even
after the file was replaced) with the file on disk now, and names the classes that differ. When the class loader can't
be read, it falls back to the size, time and hash recorded when Foreman saw the plugin enable.

!!! tip
    Replace a jar by writing the new one beside it and moving it over: copying onto a loaded jar breaks the running
    plugin.
