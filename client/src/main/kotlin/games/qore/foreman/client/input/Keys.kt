package games.qore.foreman.client.input

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/** Every key binding of the mod: declared with what it does, run on the client tick while in game. */
object Keys {
    val UNBOUND = InputConstants.UNKNOWN.value

    /** The categories of Minecraft's controls screen, in the hub's order (`key.category.foreman.<id>`). */
    enum class Category(val id: String, val label: String) {
        GENERAL("general", "Foreman"), AI("ai", "AI"), WORLD("world", "World"), DEBUG("debug", "Debug"),
    }

    /** A binding with what it does, for the hub's key list. */
    class Binding(val mapping: KeyMapping, val description: String, val category: Category)

    private val categories = HashMap<Category, KeyMapping.Category>()
    private val bindings = mutableListOf<Pair<KeyMapping, () -> Unit>>()

    val described = mutableListOf<Binding>()

    /** `key.foreman.<name>` in [category]. */
    fun bind(name: String, category: Category, key: Int, description: String = "", onPress: () -> Unit): KeyMapping {
        val cat = categories.getOrPut(category) { KeyMapping.Category.register(Identifier.fromNamespaceAndPath("foreman", category.id)) }
        val mapping = KeyMappingHelper.registerKeyMapping(KeyMapping("key.foreman.$name", InputConstants.Type.KEYBOARD, key, cat))
        bindings += mapping to onPress
        if (description.isNotEmpty()) described += Binding(mapping, description, category)
        return mapping
    }

    /** The key `key.foreman.<name>` is bound to, or "" when unbound. */
    fun keyOf(name: String): String =
        described.firstOrNull { it.mapping.name == "key.foreman.$name" }?.mapping?.takeIf { !it.isUnbound }?.translatedKeyMessage?.string.orEmpty()

    fun init() {
        ClientTickEvents.END_CLIENT_TICK.register { client: Minecraft ->
            if (client.player == null) return@register
            for ((mapping, action) in bindings) {
                while (mapping.consumeClick()) action()
            }
        }
    }
}
