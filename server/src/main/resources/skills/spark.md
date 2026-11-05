---
name: spark
description: Measure lag and memory with spark (bundled with Paper): TPS and tick durations, CPU, memory pools, GC pauses, slow ticks logged as they happen, and profiles or heap summaries saved for spark's viewer. Read for lag, memory or GC questions that server_status and server_profile don't answer.
command: spark
---

# spark

Paper bundles spark (`spark.enabled` in `config/paper-global.yml`: when `/spark` says it's disabled, say so). Run its
commands with `server_command`. Foreman's `server_profile` answers in text you can read (the server thread's time by
plugin); spark's profiles and heap summaries are files for spark's viewer, for the player to open.

## Text you can read

- `spark tps`: TPS over 5 s to 15 min, tick durations (min, median, 95th percentile, max) over 10 s and 1 min, CPU
  (system and the server's process).
- `spark health show --memory`: the same, plus heap, non-heap and each memory pool (and its use after the last GC).
  `--network` adds the network interfaces' rates.
- `spark gc`: each collector's average pause, collections and how often since the start. `spark gcmonitor` logs a line
  per collection; the same command turns it off.
- Slow ticks as they happen: `spark tickmonitor --threshold-tick 50` (ms), or `--threshold 20` (% over the average),
  `--without-gc` to skip ticks with a collection. It measures the average for about 6 s, then logs each slow tick
  (`Tick #186 lasted 62 ms`): reproduce the lag, read them with `server_log contains="lasted"`, then turn it off with
  `spark tickmonitor` (no flags). Never leave it on.
- `spark ping` (`--player <name>`): ping averages, once players have been on for a while.

## Files for the viewer

Data leaves the server only when spark uploads it to spark.lucko.me: save files instead, unless the player asks for a
link.

- **Profile:** `spark profiler start` with `--thread *` (every thread; default the server thread) or `--thread <name>`,
  `--only-ticks-over 50` (only ticks slower than 50 ms: lag spikes), `--alloc` (allocations instead of CPU),
  `--lock`, `--interval <ms>`. Let it run while the problem happens (30 s or more), `spark profiler info` meanwhile,
  then `spark profiler stop --save-to-file`: it writes `plugins/spark/profile-<date>.sparkprofile` and says where.
  `spark profiler stop` alone uploads it, and so does a run started with `--timeout` when it ends: stop it yourself.
  `spark profiler cancel` drops it. Starting a profile stops spark's background profiler; stopping restarts it.
- **Heap:** `spark heapsummary --save-to-file` writes the heap's classes by size to
  `plugins/spark/heapsummary-<date>.sparkheap` (a short pause); without the flag it uploads. `spark heapdump` writes a
  full `.hprof`: a long pause and a file as big as the heap, only when asked.
- `spark health` with no argument, and `spark health upload`, upload a health report: use `spark health show`.
- `spark activity`: the profiles and summaries made so far, with their files or links.

The files are binary, not for you to read: tell the player where they are, and that spark's viewer
(https://spark.lucko.me/) opens them.
