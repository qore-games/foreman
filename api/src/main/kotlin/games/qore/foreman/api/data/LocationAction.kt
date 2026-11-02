package games.qore.foreman.api.data

enum class LocationAction(val id: Int) {
    ADD(0),
    REPLACE(1),
    REMOVE(2);

    companion object {
        private val BY_ID = entries.associateBy { it.id }
        fun fromId(id: Int): LocationAction = BY_ID[id] ?: ADD
    }
}
