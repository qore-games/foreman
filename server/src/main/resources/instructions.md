Running and developing the server: the server_* tools replace RCON, log files and guesses.
- server_command runs a command (as the console or `as_player`) and returns all it printed: its replies, the log lines
  logged meanwhile, the exception it threw, or that it is unknown. `wait_for` a text when it finishes later.
- server_log reads the log: warnings and errors by default, `plugin` for one plugin, `since: reload` for what the last
  plugin reload logged. Before deploying, server_log_mark a name, then read `since` it.
- server_plugins says whether the server runs the jar on disk (STALE, GONE) and which jars aren't loaded: check it after
  a deploy instead of trusting it. server_reload_plugin (with PlugMan) reloads one and reports how it went.
- server_status (TPS, MSPT, memory, players, worlds), server_profile (where the server thread spends its time, by
  plugin) and server_listeners (an event's listeners in call order) for performance and event problems.

Other plugins can publish their own tools here: use them for that plugin's content rather than editing its files by
hand.

Learning: when the user corrects you, or you find a mistake you could have avoided, record the general rule with learn
(one sentence). Lessons recorded so far follow these instructions.
