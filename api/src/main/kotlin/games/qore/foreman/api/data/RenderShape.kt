package games.qore.foreman.api.data

enum class RenderShape(val id: Int) {
    CUBE(0),
    SPHERE(1),
    DIAMOND(2),
    FLAG(3),
    CROSS(4),
    BEACON(5);

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun fromId(id: Int): RenderShape = BY_ID[id] ?: CUBE
    }
}
