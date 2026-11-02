package games.qore.foreman.server.admin

import games.qore.foreman.api.ForemanTool
import games.qore.foreman.server.Foreman
import games.qore.foreman.server.tools.ServerTools
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.plugin.Plugin

/**
 * Follows the plugins for the server tools: records each one's jar as it enables ([PluginJars]), keeps a reload going in
 * the log while plugins enable one after the other ([ServerLog]), and publishes server_reload_plugin while PlugMan runs.
 */
class ServerWatch(private val plugin: Foreman) : Listener {
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
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onDisable(event: PluginDisableEvent) {
        ServerLog.activity()
        // Still enabled during its event: look again a tick later.
        if (plugman(event.plugin) && event.plugin !== plugin) Bukkit.getScheduler().runTask(plugin, Runnable { syncPlugman() })
    }

    private fun plugman(p: Plugin) = p.name.equals("PlugMan", true) || p.name.equals("PlugManX", true)

    /** server_reload_plugin while PlugMan or PlugManX is enabled. */
    fun syncPlugman() {
        val plugman = Bukkit.getPluginManager().plugins.firstOrNull { plugman(it) && it.isEnabled }
        if (plugman == null) plugin.tools.remove(ForemanTool.SERVER_RELOAD_PLUGIN.id)
        else plugin.tools.add(ServerTools.reload(plugin))
    }
}
