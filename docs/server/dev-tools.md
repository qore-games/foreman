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
| `server_packets` | What the server sends its players and gets from them, recorded on its side: per player and packet type, before compression and on the wire, per second, and [what the packets are about](#packets-on-the-servers-side) |
| `server_reload_plugin` | Reloads a plugin through PlugMan or PlugManX and reports how it went, with a [check of the reload](#reload-checks) (published only while one of them runs) |

## A deploy loop

1. `server_log_mark name=before-deploy`
2. Build, copy the jar, `server_reload_plugin` (or restart).
3. `server_plugins stale_only=true`: is the server running the new jar?
4. `server_command` the feature, `server_log since=before-deploy` for what went wrong.

## Packets on the server's side

`server_packets` records every player's connection at once, from the server, to find what spends bandwidth: which
players get the most, which packet types, and what they're about. It puts handlers on each player's connection for the
recording only (and on those joining meanwhile), and takes them off after: nothing runs between recordings.

```json
{"seconds": 30}
```

The answer, after `seconds` (or `wait: false`, then `action: summary`; `stop` ends early; `player` for one player's
lists):

```text
Packets recorded for 15.0 s, 2 player(s): sent 4,902 packets, 1.2 MB before compression, 219.3 KB on the wire (17 %); received 940 packets, 12.5 KB on the wire.
Sent on the wire per second: 14.6 KB on average, 69.3 KB at most (second 7).
Sent by type (before compression):
  level_chunk_with_light: 27 (1.8/s), 1.1 MB, 40877 B each
  move_entity_pos_rot: 1,599 (106.3/s), 18.7 KB, 12 B each
What they're about (before compression):
  bat ×12: 27.4 KB, 3,212 packets (rotate_head 1,536, move_entity_pos_rot 1,426, entity_position_sync 107)
  item_display [demo]: 162 B, 12 packets (move_entity_pos 10, entity_position_sync 2), e.g. tag demo_model
```

Packet types have the names the mod's [packet viewer](../client/packets.md) gives them. *What they're about* groups the
entities by what they are: their type, the plugins whose data they carry (the namespaces of their persistent data:
`[demo]`), what they ride, with a name or a tag as an example; then plugin channels, scoreboard teams and objectives.
An entity that never moves but still gets position packets is the server's periodic resync: a plugin can spare it.
The mod's packet viewer sees one player's side, with each packet's fields; this sees every player, and what the
server knows about the entities.

## Reload checks

A plugin reload can leave its old instance behind: listeners, commands or tasks still registered, a thread still
running its code, plugins that depend on it still linked to its old classes. `server_reload_plugin` ends with a check
of what the reload left: `clean`, or `restart before trusting it` with what it found:

```text
Reload check: restart before trusting it: 1 more thread runs its code than before (the old one goes on); its old
classes are still loaded (by those threads, or something else: a heap dump shows what).
```

It looks at what the old instance still has registered (listeners, commands, scheduled tasks, services, plugin
channels), counts the threads running the plugin's code before and after, names the plugins that depend on it, and
runs up to two full garbage collections to see whether its old classes were unloaded. Bukkit's `/help` makes its
topics once, at startup, and keeps the commands they describe: a plugin with commands reloaded for the first time stays
in memory through them. The check says so and counts it as clean (memory only), and skips the collections then.

With the `console` [feature](setup.md#features), reloads made any other way (`/plugman reload` typed in the console, a
`/reload`) are checked too, one line in the log each, once plugins stop enabling:

```text
[Foreman] Reload of DemoShop: clean, nothing of the old instance left that runs, and its classes unloaded.
```

A check costs the time of its garbage collections (a pause of the server, tens of milliseconds on a small heap, more on
a big one), only after a reload. Where the JVM ignores `System.gc()` (`-XX:+DisableExplicitGC`), it says it can't tell
whether the classes were unloaded. For code changes that don't touch a plugin's structure, a JVM with enhanced
hotswap (JetBrains Runtime with HotswapAgent) changes classes in place without any reload.

## Stale plugin jars

`server_plugins` compares the jar each plugin's class loader opened (its entries as they were when it loaded, even
after the file was replaced) with the file on disk now, and names the classes that differ. When the class loader can't
be read, it falls back to the size, time and hash recorded when Foreman saw the plugin enable.

!!! tip
    Replace a jar by writing the new one beside it and moving it over: copying onto a loaded jar breaks the running
    plugin.
