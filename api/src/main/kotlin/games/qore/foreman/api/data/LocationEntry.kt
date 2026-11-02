package games.qore.foreman.api.data

data class LocationEntry(
    val id: String,
    val type: LocationType,
    val world: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float = 0f,
    val pitch: Float = 0f,
    val display: DisplayConfig,
    val metadata: Map<String, String> = emptyMap()
) {
    val blockX: Int get() = x.toInt().let { if (x < 0) it - 1 else it }
    val blockY: Int get() = y.toInt().let { if (y < 0) it - 1 else it }
    val blockZ: Int get() = z.toInt().let { if (z < 0) it - 1 else it }

    fun distanceTo(px: Double, py: Double, pz: Double): Double {
        val dx = x - px
        val dy = y - py
        val dz = z - pz
        return kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
    }

    companion object {
        private val BUKKIT_TO_MINECRAFT = mapOf(
            "world" to "minecraft:overworld",
            "world_nether" to "minecraft:the_nether",
            "world_the_end" to "minecraft:the_end"
        )

        fun normalizeWorld(world: String): String {
            BUKKIT_TO_MINECRAFT[world]?.let { return it }
            return if (':' in world) world else "minecraft:$world"
        }

        private val MINECRAFT_TO_BUKKIT = BUKKIT_TO_MINECRAFT.entries.associate { (k, v) -> v to k }

        fun toBukkitWorld(world: String): String {
            MINECRAFT_TO_BUKKIT[world]?.let { return it }
            return world.removePrefix("minecraft:")
        }
    }
}
