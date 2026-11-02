package games.qore.foreman.api.data

enum class LocationType(val id: Int) {
    BLOCK(0),
    PRECISE(1);

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun fromId(id: Int): LocationType = BY_ID[id] ?: PRECISE
    }
}
