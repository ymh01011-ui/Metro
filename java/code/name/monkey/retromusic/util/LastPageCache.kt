package code.name.monkey.retromusic.util

/**
 * Remembers the data of the LAST detail page that was opened from the home page (one slot).
 * Opening the same page again shows that data at once instead of loading it again; opening a
 * different page replaces it.
 */
object LastPageCache {
    private var key: String? = null
    private var value: Any? = null

    @Synchronized
    fun put(newKey: String, newValue: Any?) {
        key = newKey
        value = newValue
    }

    @Synchronized
    @Suppress("UNCHECKED_CAST")
    fun <T> get(wanted: String): T? = if (key == wanted) value as? T else null
}
