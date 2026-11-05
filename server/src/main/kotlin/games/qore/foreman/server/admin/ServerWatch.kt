package games.qore.foreman.server.admin

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Feature
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.tools.ServerTools
import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import java.util.concurrent.ConcurrentHashMap

/**
 * Follows the plugins for the server tools: records each one's jar as it enables ([PluginJars]), keeps a reload going in
 * the log while plugins enable one after the other ([ServerLog]), publishes server_reload_plugin while PlugMan runs,
 * and with the console feature checks each reload ([ReloadCheck]) into the log, however it was made.
 */
class ServerWatch(private val plugin: Foreman) : Listener {
    private val checks = plugin.features[Feature.CONSOLE]
    /** Plugins disabled once the server runs, until they enable again. */
    private val disabled = ConcurrentHashMap<String, ReloadCheck.Before>()
    /** Reloaded plugins, checked together once plugins stop enabling (a /reload is one check). */
    private val reloaded = ConcurrentHashMap<String, ReloadCheck.Before>()
    private var batch: BukkitTask? = null

    fun start() {
        Sampler.mainThread = Thread.currentThread()
        Bukkit.getPluginManager().plugins.filter { it.isEnabled }.forEach { record(it) }
        record(plugin)
        Bukkit.getPluginManager().registerEvents(this, plugin)
        syncPlugman()
        // Scheduled tasks run once the server ticks: plugins enabled or disabled from then on are reloads.
        Bukkit.getScheduler().runTask(plugin, Runnable { ServerLog.started = true })
    }

    private fun record(p: Plugin) = PluginJars.record(p) { Bukkit.getScheduler().runTaskAsynchronously(plugin, it) }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEnable(event: PluginEnableEvent) {
        ServerLog.activity()
        record(event.plugin)
        if (plugman(event.plugin)) syncPlugman()
        disabled.remove(event.plugin.name)?.let { before ->
            reloaded[before.name] = before
            batch?.cancel()
            batch = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, Runnable { checkReloads() }, 40L)
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDisable(event: PluginDisableEvent) {
        ServerLog.activity()
        val p = event.plugin
        if (checks && ServerLog.started && !Bukkit.isStopping() && p !== plugin && p.name !in ReloadCheck.busy) disabled[p.name] = ReloadCheck.Before(p)
        // Still enabled during its event: look again a tick later.
        if (plugman(event.plugin) && event.plugin !== plugin) Bukkit.getScheduler().runTask(plugin, Runnable { syncPlugman() })
    }

    /** One line per reloaded plugin: clean, or what to restart for (off the server thread: it waits for collections). */
    private fun checkReloads() {
        val reloads = reloaded.keys.toList().mapNotNull { reloaded.remove(it) }
        if (reloads.isEmpty()) return
        val now = Sync.main { reloads.map { Bukkit.getPluginManager().getPlugin(it.name) } }
        ReloadCheck.check(reloads.zip(now)).forEach { v ->
            if (v.clean) plugin.logger.info("Reload of ${v.name}: ${v.text}") else plugin.logger.warning("Reload of ${v.name}: ${v.text}")
        }
    }

    private fun plugman(p: Plugin) = p.name.equals("PlugMan", true) || p.name.equals("PlugManX", true)

    /** server_reload_plugin while PlugMan or PlugManX is enabled. */
    fun syncPlugman() {
        val plugman = Bukkit.getPluginManager().plugins.firstOrNull { plugman(it) && it.isEnabled }
        if (plugman == null) plugin.tools.remove(ForemanTool.SERVER_RELOAD_PLUGIN.id)
        else plugin.tools.add(ServerTools.reload(plugin))
    }
}
