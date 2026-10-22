package io.schemat.connector.core.routing

/** Keys and (de)serialisation for per-context destination preference orders. */
object DestinationProfiles {
    const val DEFAULT_KEY = "default"
    const val SINGLEPLAYER_KEY = "singleplayer"

    fun serverKey(address: String): String = "server:" + address.trim().lowercase()

    fun encode(order: List<Destination>): String = order.joinToString(",") { it.name }

    /**
     * Parses a stored order. Returns null for null/blank input. Unknown names are dropped,
     * duplicates removed, and any missing destinations appended in default order.
     */
    fun decode(raw: String?): List<Destination>? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.split(',')
            .mapNotNull { name -> Destination.entries.firstOrNull { it.name == name.trim() } }
            .distinct()
        return parsed + Destination.DEFAULT_ORDER.filterNot { it in parsed }
    }
}
