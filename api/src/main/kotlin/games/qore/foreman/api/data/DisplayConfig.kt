package games.qore.foreman.api.data

data class DisplayConfig(
    val color: Int,
    val name: String?,
    val glow: Boolean,
    val shape: RenderShape,
    val size: Float,
    val visibleDistance: Float
) {
    companion object {
        val DEFAULT = DisplayConfig(
            color = 0x00FF00,
            name = null,
            glow = true,
            shape = RenderShape.CUBE,
            size = 1.0f,
            visibleDistance = 128.0f
        )
    }
}
