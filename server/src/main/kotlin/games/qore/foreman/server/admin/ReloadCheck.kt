package games.qore.foreman.server.admin

import games.qore.foreman.server.world.Sync
import org.bukkit.Bukkit
import org.bukkit.command.Command
import org.bukkit.command.PluginIdentifiableCommand
import org.bukkit.event.HandlerList
import org.bukkit.help.HelpTopic
import org.bukkit.plugin.Plugin
import java.lang.management.ManagementFactory
import java.lang.ref.WeakReference

/**
 * Whether a plugin reloaded cleanly (through PlugMan, or anything that disables a plugin and enables it again): what
 * still holds its old instance (listeners, commands, tasks, services, plugin channels), threads still running its code,
 * the plugins that depend on it (they keep its old classes), and whether its old class loader is gone after a few
 * garbage collections. A clean reload leaves nothing that runs; anything else means: restart before trusting the
 * server. Bukkit's /help keeps a reloaded plugin's old commands, so its old classes stay in memory: told, not a problem.
 */
object ReloadCheck {
    /** A plugin as it was before its reload, held weakly: holding it would keep its classes. */
    class Before(plugin: Plugin) {
        val name: String = plugin.name
        val instance = WeakReference(plugin)
        val loader = WeakReference(plugin.javaClass.classLoader)
        /** A Paper plugin (paper-plugin.yml): Paper doesn't support reloading those, PlugMan's way is experimental. */
        val paper = plugin.javaClass.classLoader.javaClass.name.contains("PaperPluginClassLoader")
        private val pkg = plugin.javaClass.packageName
        /** Threads running its code before the reload: more of them after it means the old ones go on. */
        val threads = threads()

        /** Live threads with the plugin's code on their stack, or its class loader as their context. */
        fun threads(): Int {
            val loader = loader.get()
            return Thread.getAllStackTraces().count { (thread, stack) ->
                thread !== Thread.currentThread() && ((loader != null && thread.contextClassLoader === loader) || stack.any { it.className.startsWith("$pkg.") })
            }
        }
    }

    class Verdict(val name: String, val clean: Boolean, val text: String)

    /** What the server still has of an old instance: what runs or answers ([running]), and the /help topics of its commands. */
    private class Left(val running: List<String>, val help: List<String>)

    /** Plugins Foreman's own reload tool is checking: the watcher leaves them to it. */
    val busy: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Checks reloads, [now] being each plugin after it (null: not loaded any more). Off the server thread: it waits for collections. */
    fun check(reloads: List<Pair<Before, Plugin?>>): List<Verdict> {
        val left = Sync.main { reloads.map { (before, _) -> leftovers(before) } }
        val reloaded = reloads.map { it.first.name }.toSet()
        val dependents = Sync.main { reloads.map { (before, now) -> dependents(before.name, now, reloaded) } }
        // Bukkit makes /help's topics once, at startup: they keep the old commands, and through them the old instance,
        // so collecting garbage for those would be in vain.
        val gc = collect(reloads.filterIndexed { i, _ -> left[i].help.isEmpty() }.map { it.first.loader })
        return reloads.mapIndexed { i, (before, now) ->
            val help = left[i].help.takeIf { it.isNotEmpty() }
            val unloaded = if (help != null) false else gc?.let { before.loader.get() == null }
            val held = unloaded == false
            val problems = buildList {
                if (left[i].running.isNotEmpty()) add("left from the old instance: ${left[i].running.joinToString(", ")}")
                val threads = (before.threads() - before.threads).takeIf { now != null && it > 0 }
                threads?.let { n -> add(if (n == 1) "1 more thread runs its code than before (the old one goes on)" else "$n more threads run its code than before (the old ones go on)") }
                if (held && help == null) add("its old classes are still loaded (" + (if (threads != null) "by those threads, or" else "by") + " something else: a heap dump shows what)")
                if (dependents[i].isNotEmpty()) add("plugins that depend on it still run on its old classes: ${dependents[i].joinToString(", ")} (reload them too)")
            }
            val notes = listOfNotNull(
                help?.let { "Bukkit's /help still holds its old commands (${it.take(5).joinToString()}), which keeps its old classes in memory until a restart (memory only; /help shows their old text)." },
                "Whether its old classes were unloaded is unknown: the JVM ignores System.gc() (-XX:+DisableExplicitGC).".takeIf { unloaded == null },
                "A Paper plugin (paper-plugin.yml): Paper doesn't support reloading those.".takeIf { before.paper },
            ).joinToString("") { " $it" }
            if (problems.isEmpty()) Verdict(before.name, true, "clean, nothing of the old instance left that runs" + (if (unloaded == true) ", and its classes unloaded." else ".") + notes)
            else Verdict(before.name, false, "restart before trusting it: " + problems.joinToString("; ") + "." + notes)
        }
    }

    /** What the server still has of the old instance (on the server thread). */
    private fun leftovers(before: Before): Left {
        val old = before.instance.get() ?: return Left(emptyList(), emptyList())
        val loader = before.loader.get()
        val listeners = HandlerList.getHandlerLists().flatMap { it.registeredListeners.toList() }
            .filter { it.plugin === old || it.listener.javaClass.classLoader === loader }.map { it.listener }.distinct()
        val commands = Bukkit.getCommandMap().knownCommands.filter { (_, c) -> (c as? PluginIdentifiableCommand)?.plugin === old || c.javaClass.classLoader === loader }.keys
        val tasks = Bukkit.getScheduler().pendingTasks.count { it.owner === old } + Bukkit.getScheduler().activeWorkers.count { it.owner === old }
        val services = Bukkit.getServicesManager().getRegistrations(old).map { it.service.simpleName }
        val channels = Bukkit.getMessenger().getOutgoingChannels(old) + Bukkit.getMessenger().getIncomingChannelRegistrations(old).map { it.channel }
        val help = Bukkit.getHelpMap().helpTopics.filter { (command(it) as? PluginIdentifiableCommand)?.plugin === old }.map { it.name }
        return Left(listOfNotNull(
            listeners.takeIf { it.isNotEmpty() }?.let { l -> "${l.size} listeners (${l.map { it.javaClass.simpleName }.distinct().take(5).joinToString()})" },
            commands.takeIf { it.isNotEmpty() }?.let { "commands ${it.filter { c -> ':' !in c }.ifEmpty { it }.take(8).joinToString { c -> "/$c" }}" },
            tasks.takeIf { it > 0 }?.let { "$it scheduled tasks" },
            services.takeIf { it.isNotEmpty() }?.let { "services ${it.distinct().joinToString()}" },
            channels.takeIf { it.isNotEmpty() }?.let { "plugin channels ${it.distinct().joinToString()}" },
        ), help)
    }

    /** The command a /help topic is about (Bukkit's command topics keep it in a field), or null. */
    private fun command(topic: HelpTopic): Command? = generateSequence<Class<*>>(topic.javaClass) { it.superclass }
        .flatMap { it.declaredFields.asSequence() }.firstOrNull { Command::class.java.isAssignableFrom(it.type) }
        ?.let { field -> runCatching { field.isAccessible = true; field.get(topic) as Command? }.getOrNull() }

    /** Enabled plugins that depend on [name] and weren't reloaded with it: the classes of it they linked are the old ones. */
    private fun dependents(name: String, now: Plugin?, reloaded: Set<String>): List<String> = Bukkit.getPluginManager().plugins.filter { p ->
        p.isEnabled && p !== now && p.name !in reloaded && (name in p.pluginMeta.pluginDependencies || name in p.pluginMeta.pluginSoftDependencies)
    }.map { it.name }

    /**
     * Collects garbage (full collections, which unload classes) until every loader is gone, twice at most. When
     * System.gc() does nothing, null: whether they are gone can't be told.
     */
    private fun collect(loaders: List<WeakReference<ClassLoader>>): Boolean? {
        if (ManagementFactory.getRuntimeMXBean().inputArguments.any { it == "-XX:+DisableExplicitGC" }) return null
        repeat(2) {
            if (loaders.all { it.get() == null }) return true
            System.gc()
            Thread.sleep(50)
        }
        return true
    }
}
