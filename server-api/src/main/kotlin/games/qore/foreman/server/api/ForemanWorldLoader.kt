package games.qore.foreman.server.api

import org.bukkit.World
import java.util.function.BiConsumer

/**
 * Loads worlds that aren't loaded when Foreman needs one: the library world an asset comes from (`open_asset_source`),
 * kept elsewhere (a world store, worlds loaded on demand). Register one as a Bukkit service (the highest priority one is
 * used); without one, Foreman says the world isn't loaded.
 *
 * ```java
 * Bukkit.getServicesManager().register(ForemanWorldLoader.class, new MyWorlds(), this, ServicePriority.Normal);
 * ```
 *
 * Names are the ones Foreman recorded, perhaps on another server where the world is loaded under another name: map
 * them to yours. Called on the main thread.
 */
interface ForemanWorldLoader {
    /** Whether you can load the world recorded as [name] (or have it loaded under another name). */
    fun canLoad(name: String): Boolean

    /**
     * Loads the world recorded as [name], then calls [callback] on the main thread with it (whatever its name here), or
     * with null and why not. A world already loaded under your name for it is given at once.
     */
    fun load(name: String, callback: BiConsumer<World?, String?>)
}
