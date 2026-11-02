package games.qore.foreman.client.mcp

import games.qore.foreman.client.link.Bridge
import games.qore.foreman.client.link.NetworkProfiles
import games.qore.foreman.client.link.PacketFilter
import games.qore.foreman.client.util.get
import games.qore.foreman.client.util.int
import games.qore.foreman.client.util.str
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.minecraft.client.Minecraft
import net.minecraft.world.inventory.ContainerInput

/**
 * Dupe-testing from the client: read the open container exactly ([inventory_snapshot]) and perform inventory clicks the
 * way a player (or a fast one) does ([inventory_actions]), including several in the same client tick. These replay the
 * clicks a player makes, through the vanilla click path, on any server. The lag and desync layer (a [NetworkProfile]:
 * stock Foreman has `burst`, hold and send together, and `ping_<n>`, added latency; and inbound drops) needs the
 * server's opt-in: the Foreman plugin giving the player `foreman.test` ([Bridge.testing]), like its dupe tools.
 */
object InventoryTools {
    private val INPUTS = ContainerInput.values().associateBy { it.name.lowercase() }

    fun init() {
        ClientMcp.register(ClientTool(
            "inventory_snapshot",
            "The open container exactly as the client holds it: the container id, the sync state id, the cursor item, and " +
                "every non-empty slot (index → item and count). Take it before and after an inventory_actions burst to see " +
                "what changed, and read the state id to spot desync. Needs a container open (inventory, chest, a plugin menu).",
            ClientTools.schema(),
        ) { _, _ -> listOf(ClientMcp.text(ClientTools.onClient { snapshot() })) })

        ClientMcp.register(ClientTool(
            "inventory_actions",
            "Performs a sequence of inventory clicks in the open container, as the player would, for dupe testing: each " +
                "action is a slot, an input (pickup = normal click, quick_move = shift-click, swap = number/offhand key, " +
                "clone = middle-click, throw = drop, pickup_all = double-click collect) and a button. With `per_tick` > 1 it " +
                "fires that many in the same client tick (pulling an item out and back in one tick, the classic dupe race). " +
                "`network` and `drop` (desync) need a server running Foreman that gives the player foreman.test. " +
                "Returns the snapshot after. Needs a container open.",
            actionsSchema(),
            enabled = { it.allowCommands },
        ) { _, args -> inventoryActions(args) })
    }

    private fun snapshot(): String {
        val player = Minecraft.getInstance().player ?: return "Not in a world"
        val menu = player.containerMenu
        val carried = menu.carried
        return buildString {
            appendLine("container #${menu.containerId}, state ${menu.stateId}, ${menu.slots.size} slots")
            appendLine("cursor: ${item(carried)}")
            val filled = menu.slots.withIndex().filter { it.value.hasItem() }
            if (filled.isEmpty()) appendLine("(all slots empty)")
            else filled.forEach { (i, slot) -> appendLine("  slot $i: ${item(slot.item)}") }
        }
    }

    private fun inventoryActions(args: JsonObject): List<JsonObject> {
        val actions = (args["actions"] as? JsonArray)?.map { it as JsonObject }
            ?: throw ToolFailure("Give actions: a list of {slot, input, button}")
        val network = args["network"].str()
        if (network != null && NetworkProfiles.get(network) == null) {
            throw ToolFailure("No network profile '$network' registered: ${NetworkProfiles.ids.joinToString().ifEmpty { "none" }}")
        }
        val drop = (args["drop"] as? JsonArray)?.mapNotNull { it.str() } ?: emptyList()
        if ((network != null || drop.isNotEmpty()) && !Bridge.testing) {
            throw ToolFailure("`network` and `drop` need a server running Foreman that gives you foreman.test")
        }
        // The server's reply to an action (the slot correction a drop targets) arrives a round-trip later, so keep the
        // drop armed past the last click. Default covers a real server's latency; override for a slow link.
        val dropMs = (args["drop_ms"].int() ?: 400).coerceIn(0, 10_000)
        val done = burst(actions, (args["per_tick"].int() ?: 1).coerceIn(1, 64), network, drop, dropMs)
        return listOf(ClientMcp.text("Did ${actions.size} action(s): $done\n\n${ClientTools.onClient { snapshot() }}"))
    }

    /** Runs [actions] in groups of [perTick] (same client tick), holding the network profile and the inbound [drop] around them. */
    private fun burst(actions: List<JsonObject>, perTick: Int, network: String?, drop: List<String>, dropMs: Int): String {
        val done = StringBuilder()
        if (drop.isNotEmpty()) PacketFilter.drop(drop)
        try {
        NetworkProfiles.around(network) {
            for (chunk in actions.chunked(perTick)) {
                ClientTools.onClient {
                    val minecraft = Minecraft.getInstance()
                    val player = minecraft.player ?: throw ToolFailure("Not in a world")
                    val menu = player.containerMenu
                    for (action in chunk) {
                        val slot = action["slot"].int() ?: throw ToolFailure("Each action needs a slot")
                        val inputName = action["input"].str()?.lowercase() ?: "pickup"
                        val input = INPUTS[inputName] ?: throw ToolFailure("Unknown input '$inputName': ${INPUTS.keys.joinToString()}")
                        minecraft.gameMode?.handleContainerInput(menu.containerId, slot, action["button"].int() ?: 0, input, player)
                        done.append("$inputName@$slot ")
                    }
                }
                if (chunk !== actions) Thread.sleep(55)  // next chunk on a later tick
            }
        }
        // After the profile released (a burst's held sends have gone out): keep dropping so the server's reply, which
        // arrives a round-trip later, is still caught.
        if (drop.isNotEmpty()) Thread.sleep(dropMs.toLong())
        } finally {
            if (drop.isNotEmpty()) PacketFilter.clear()
        }
        return done.toString().trim() + (network?.let { " [network: $it]" } ?: "") + (drop.takeIf { it.isNotEmpty() }?.let { " [drop: ${it.joinToString()}, ${dropMs}ms]" } ?: "")
    }

    /** `actions`: a list of {slot, input, button}; `per_tick`: how many in one tick. */
    private fun actionsSchema() = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("actions", buildJsonObject {
                put("type", "array")
                put("description", "The clicks in order: each {slot:int, input:string, button:int}")
                put("items", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("slot", buildJsonObject { put("type", "integer"); put("description", "The slot index (inventory_snapshot lists them)") })
                        put("input", buildJsonObject { put("type", "string"); put("description", "pickup, quick_move, swap, clone, throw, pickup_all (default pickup)") })
                        put("button", buildJsonObject { put("type", "integer"); put("description", "0 left (default), 1 right; for swap, the hotbar slot 0-8 or 40 offhand") })
                    })
                })
            })
            put("per_tick", buildJsonObject { put("type", "integer"); put("description", "How many actions to send in the same client tick (default 1)") })
            put("network", buildJsonObject { put("type", "string"); put("description", "A network profile to hold around the burst: burst (hold the sends, then send them together) or ping_100/300/500/1000 (added latency). Needs foreman.test on a Foreman server") })
            put("drop", buildJsonObject { put("type", "array"); put("items", buildJsonObject { put("type", "string") }); put("description", "Inbound packet types to ignore during the burst (name fragments like setslot, containerset). Needs foreman.test on a Foreman server") })
            put("drop_ms", buildJsonObject { put("type", "integer"); put("description", "How long to keep dropping after the last click, to catch the server's reply (default 400)") })
        })
        put("required", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("actions")) })
    }

    private fun item(stack: net.minecraft.world.item.ItemStack) =
        if (stack.isEmpty) "empty" else "${net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item)} x${stack.count}"
}
