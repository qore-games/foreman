package games.qore.foreman.client.locations

import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import games.qore.foreman.client.input.Tools
import games.qore.foreman.client.ui.HubScreen
import games.qore.foreman.client.ui.Screens
import games.qore.foreman.client.util.Chat
import games.qore.foreman.api.data.DisplayConfig
import games.qore.foreman.api.data.LocationEntry
import games.qore.foreman.api.data.LocationType
import games.qore.foreman.api.data.RenderShape
import games.qore.foreman.api.packet.LocationAddRequestPacket
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument
import net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.world.phys.Vec3
import java.util.UUID

private typealias Ctx = CommandContext<FabricClientCommandSource>

/** `/locations …` (server and local locations, the editor) and `/local …` (local sets). */
object LocationCommands {
    fun init() {
        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            dispatcher.register(locations())
            dispatcher.register(local())
        }
    }

    private fun Ctx.string(name: String): String = StringArgumentType.getString(this, name)
    private fun Ctx.optionalString(name: String): String? = try { string(name) } catch (_: IllegalArgumentException) { null }

    private fun <T : ArgumentBuilder<FabricClientCommandSource, T>> T.does(action: (Ctx) -> Unit): T = executes { action(it); 1 }

    private fun sourceArg(name: String = "source") = argument(name, StringArgumentType.word())
        .suggests { _, b -> SharedSuggestionProvider.suggest(LocationStore.sources(), b) }

    private fun categoryArg(name: String = "category", greedy: Boolean = false) =
        argument(name, if (greedy) StringArgumentType.greedyString() else StringArgumentType.word())
            .suggests { ctx, b -> SharedSuggestionProvider.suggest(LocationStore.categories(StringArgumentType.getString(ctx, "source")), b) }

    private fun setArg(saved: Boolean = false) = argument("name", StringArgumentType.greedyString())
        .suggests { _, b -> SharedSuggestionProvider.suggest(if (saved) LocalSets.saved() else LocalSets.names(), b) }

    /** Runs [action] on the selected location, or says there is none. */
    private fun selected(action: (Located) -> Unit) {
        val located = LocationEditor.selectedLocation() ?: return Chat.error("No location selected: /locations editor, then left click one")
        action(located)
    }

    private fun locations(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("locations").does { help() }
        .then(literal("settings").does { Screens.open(HubScreen(HubScreen.Section.LOCATIONS)) })
        .then(literal("list").does { list() }.then(sourceArg().does { listSource(it.string("source")) }))
        .then(literal("clear").does {
            val count = LocationStore.totalLocations()
            LocationStore.clear()
            LocalSets.active?.let(LocalSets::use)
            Chat.success("Cleared $count locations")
        })
        .then(literal("status").does {
            Chat.info("${LocationStore.sources().size} sources, ${LocationStore.totalCategories()} categories, ${LocationStore.totalLocations()} locations")
        })
        .then(literal("add").then(sourceArg().then(categoryArg().does { add(it) }.then(argument("metadata", StringArgumentType.greedyString()).does { add(it) }))))
        .then(literal("toggle").then(sourceArg().does { toggleSource(it.string("source")) }
            .then(categoryArg(greedy = true).does { toggleCategory(it.string("source"), it.string("category")) })))
        .then(literal("editor").does {
            val on = Tools.toggle(LocationEditor)
            Chat.success("Location editor ${if (on) "on: left click to select, drag the arrows, right click to place on a block" else "off"}")
        })
        .then(literal("gizmo").does {
            LocationEditor.gizmoEnabled = !LocationEditor.gizmoEnabled
            Chat.success("Gizmo ${if (LocationEditor.gizmoEnabled) "shown" else "hidden"}")
        })
        .then(literal("relocate").does { selected { LocationEditor.moveToPlayer(LocationEditor.PlayerAnchor.BLOCK); Chat.success("Moved to your block") } }
            .then(argument("x", DoubleArgumentType.doubleArg()).then(argument("y", DoubleArgumentType.doubleArg()).then(argument("z", DoubleArgumentType.doubleArg()).does { ctx ->
                selected {
                    LocationEditor.moveTo(Vec3(DoubleArgumentType.getDouble(ctx, "x"), DoubleArgumentType.getDouble(ctx, "y"), DoubleArgumentType.getDouble(ctx, "z")))
                    Chat.success("Moved")
                }
            }))))
        .then(literal("rename").does { selected { LocationEditor.rename(null); Chat.success("Name cleared") } }
            .then(literal("source").then(argument("name", StringArgumentType.word()).does { ctx ->
                selected { l -> LocationEditor.recategorize(ctx.string("name"), l.category)?.let(Chat::error) ?: Chat.success("Moved to source ${ctx.string("name")}") }
            }))
            .then(literal("category").then(argument("name", StringArgumentType.greedyString()).does { ctx ->
                selected { l -> LocationEditor.recategorize(l.source, ctx.string("name"))?.let(Chat::error) ?: Chat.success("Moved to category ${ctx.string("name")}") }
            }))
            .then(argument("name", StringArgumentType.greedyString()).does { ctx ->
                selected { LocationEditor.rename(ctx.string("name")); Chat.success("Renamed to ${ctx.string("name")}") }
            }))
        .then(literal("delete").does {
            selected { l ->
                LocationEditor.deleteSelected()
                Chat.success("Deleted ${l.entry.display.name ?: l.entry.id}" + if (LocalSets.isLocal(l.source)) "" else " (on this client only: the server keeps it)")
            }
        })

    private fun help() {
        Chat.title("Location debugger")
        listOf(
            "/locations settings" to "sources and categories (key L)",
            "/locations add <source> <category> [k=v…]" to "ask the server to add one here",
            "/locations list [source]" to "", "/locations toggle <source> [category]" to "show / hide",
            "/locations clear | status" to "",
            "/locations editor" to "select, drag, place locations", "/locations gizmo" to "show / hide the arrows",
            "/locations relocate [x y z]" to "move the selection (default: your block)",
            "/locations rename <name> | source <s> | category <c>" to "", "/locations delete" to "",
            "/local …" to "local location sets",
        ).forEach { (cmd, what) -> Chat.send(Chat.line(cmd, what, suggest = cmd.substringBefore(" <").substringBefore(" ["))) }
    }

    private fun list() {
        if (LocationStore.sources().isEmpty()) return Chat.warn("No location received")
        Chat.title("Location sources")
        LocationStore.sources().forEach { s ->
            Chat.send(Chat.line(s, "(${LocationStore.count(s)} locations, ${LocationStore.categories(s).size} categories)",
                if (LocationStore.isSourceVisible(s)) ChatFormatting.GREEN else ChatFormatting.RED, "/locations list $s", "Click to list its categories"))
        }
    }

    private fun listSource(source: String) {
        val categories = LocationStore.categories(source)
        if (categories.isEmpty()) return Chat.warn("No category in '$source'")
        Chat.title("Categories of $source")
        categories.forEach { c ->
            val visible = LocationStore.isCategoryVisible(source, c)
            Chat.send(Chat.line(c, "(${LocationStore.entries(source, c).size}, ${if (visible) "visible" else "hidden"})",
                if (visible) ChatFormatting.AQUA else ChatFormatting.DARK_AQUA, "/locations toggle $source $c", "Click to toggle"))
        }
    }

    private fun toggleSource(source: String) {
        if (source !in LocationStore.sources()) return Chat.error("No source '$source'")
        val visible = !LocationStore.isSourceVisible(source)
        LocationStore.setSourceVisible(source, visible)
        LocationStore.categories(source).forEach { LocationStore.setCategoryVisible(source, it, true) }
        Chat.success("$source ${if (visible) "shown" else "hidden"}")
    }

    private fun toggleCategory(source: String, category: String) {
        val visible = !LocationStore.isCategoryVisible(source, category)
        LocationStore.setSourceVisible(source, true)
        LocationStore.setCategoryVisible(source, category, visible)
        Chat.success("$source > $category ${if (visible) "shown" else "hidden"}")
    }

    private fun add(ctx: Ctx) {
        val source = ctx.string("source")
        val category = ctx.string("category")
        val metadata = ctx.optionalString("metadata")?.split(" ")?.filter { it.isNotBlank() }
            ?.associate { it.substringBefore("=") to it.substringAfter("=", "") } ?: emptyMap()
        val player = ctx.source.player
        val world = player.level().dimension().identifier().toString()
        val request = LocationAddRequestPacket(source, category, world, player.x, player.y, player.z, player.yRot, player.xRot, metadata)
        if (!LocationChannels.addRequest.send(request)) return Chat.error("The server doesn't take location requests")
        // Shown at once; the server's answer replaces it.
        val display = DisplayConfig(0x00FF00, metadata["name"], true, RenderShape.CUBE, 1f, 128f)
        LocationStore.add(source, category, listOf(LocationEntry(UUID.randomUUID().toString(), LocationType.PRECISE, world, player.x, player.y, player.z, player.yRot, player.xRot, display, metadata)))
        Chat.success("Asked to add a location to $source:$category here")
    }

    private fun local(): LiteralArgumentBuilder<FabricClientCommandSource> = literal("local").does { localHelp() }
        .then(literal("list").does {
            if (LocalSets.names().isEmpty()) return@does Chat.warn("No local set: /local create <name>")
            Chat.title("Local sets")
            LocalSets.names().forEach { n ->
                val active = n == LocalSets.active
                Chat.send(Chat.line(n + if (active) " [active]" else "", "(${LocalSets.count(n)} locations, ${LocalSets.categories(n).size} categories)",
                    if (active) ChatFormatting.GREEN else ChatFormatting.WHITE, "/local use $n", "Click to use"))
            }
        })
        .then(literal("saved").does { Chat.info("Saved sets: " + LocalSets.saved().ifEmpty { listOf("none") }.joinToString(", ")) })
        .then(literal("create").then(setArg().does { ctx ->
            val n = ctx.string("name")
            if (LocalSets.create(n)) Chat.success("Created '$n': /local use $n") else Chat.error("'$n' already exists")
        }))
        .then(literal("delete").then(setArg().does { ctx ->
            val n = ctx.string("name")
            if (LocalSets.delete(n)) Chat.success("Deleted '$n'") else Chat.error("No set '$n'")
        }))
        .then(literal("use").then(setArg().does { ctx ->
            val n = ctx.string("name")
            if (LocalSets.use(n)) Chat.success("Using '$n'") else Chat.error("No set '$n'")
        }))
        .then(literal("stop").does { LocalSets.use(null); Chat.success("No active set") })
        .then(literal("add").does { addLocal(it, "default") }
            .then(argument("category", StringArgumentType.word())
                .suggests { _, b -> SharedSuggestionProvider.suggest(LocalSets.active?.let(LocalSets::categories) ?: emptySet(), b) }
                .does { addLocal(it, it.string("category")) }
                .then(argument("label", StringArgumentType.greedyString()).does { addLocal(it, it.string("category"), it.string("label")) })))
        .then(literal("save").then(setArg().does { ctx ->
            val n = ctx.string("name")
            if (LocalSets.save(n)) Chat.success("Saved '$n'") else Chat.error("Could not save '$n'")
        }))
        .then(literal("load").then(setArg(saved = true).does { ctx ->
            val n = ctx.string("name")
            if (LocalSets.load(n)) Chat.success("Loaded '$n'") else Chat.error("Could not load '$n'")
        }))
        .then(literal("active").does {
            val n = LocalSets.active ?: return@does Chat.warn("No active set")
            Chat.info("Active: $n, ${LocalSets.count(n)} locations in ${LocalSets.categories(n).sorted().joinToString(", ").ifEmpty { "no category" }}")
        })
        .then(literal("rename").then(argument("new_name", StringArgumentType.greedyString()).does { ctx ->
            val old = LocalSets.active ?: return@does Chat.error("No active set")
            val new = ctx.string("new_name")
            if (LocalSets.rename(old, new)) Chat.success("Renamed '$old' to '$new'") else Chat.error("'$new' already exists")
        }))

    private fun addLocal(ctx: Ctx, category: String, label: String? = null) {
        val p = ctx.source.player
        val entry = LocalSets.add(category, label, p.level().dimension().identifier().toString(), p.x, p.y, p.z, p.yRot, p.xRot)
            ?: return Chat.error("No active set: /local use <name>")
        Chat.success("Added ${entry.id} to ${LocalSets.active} > $category")
    }

    private fun localHelp() {
        Chat.title("Local location sets")
        listOf(
            "/local create | delete | use <name>" to "", "/local stop" to "hide the active set",
            "/local add [category] [label]" to "here, in the active set", "/local rename <new name>" to "the active set",
            "/local save | load <name>" to "config/foreman/local-locations", "/local list | saved | active" to "",
            "/locations editor" to "move and rename them",
        ).forEach { (cmd, what) -> Chat.send(Chat.line(cmd, what)) }
    }
}
