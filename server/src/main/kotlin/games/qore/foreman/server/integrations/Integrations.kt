package games.qore.foreman.server.integrations

import games.qore.foreman.server.Foreman
import games.qore.foreman.server.mcp.Tool
import org.bukkit.Bukkit
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent

/**
 * Code tools for other plugins, where their commands fall short (a skill covers the rest: see the skills): each set is
 * published while its plugin is enabled. Their classes load only then, so a plugin's API is never touched without it.
 */
class Integrations(private val plugin: Foreman) : Listener {
    private class Integration(val plugin: String, val tools: (Foreman) -> List<Tool>)

    private val all = listOf(
        Integration("ModelEngine") { ModelEngineTools.all(it) },
    )

    /** The tool names each integration published. */
    private val published = HashMap<String, List<String>>()

    fun start() {
        Bukkit.getPluginManager().registerEvents(this, plugin)
        all.forEach(::sync)
    }

    @EventHandler
    fun onEnable(event: PluginEnableEvent) {
        all.filter { it.plugin == event.plugin.name }.forEach(::sync)
    }

    @EventHandler
    fun onDisable(event: PluginDisableEvent) {
        all.filter { it.plugin == event.plugin.name }.forEach(::withdraw)
    }

    private fun sync(integration: Integration) {
        withdraw(integration)
        if (!Bukkit.getPluginManager().isPluginEnabled(integration.plugin)) return
        runCatching { integration.tools(plugin) }
            .onSuccess { tools -> tools.forEach(plugin.tools::add); published[integration.plugin] = tools.map { it.name } }
            .onFailure { plugin.logger.warning("The ${integration.plugin} tools are off: ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun withdraw(integration: Integration) {
        published.remove(integration.plugin)?.forEach(plugin.tools::remove)
    }
}
