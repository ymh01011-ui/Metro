package code.name.monkey.retromusic.fragments.home

import android.content.Context

/**
 * Tiny store for the "Pinned" row on the home screen.
 * Kept in its own SharedPreferences file so no Room migration is needed.
 */
object PinnedStore {

    enum class Type { ALBUM, ARTIST }

    data class Pin(val type: Type, val id: Long)

    private const val PREFS = "home_pinned"
    private const val KEY = "items"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(context: Context): List<Pin> =
        prefs(context).getString(KEY, "").orEmpty().split(",").mapNotNull { token ->
            val parts = token.split(":")
            if (parts.size != 2) return@mapNotNull null
            val type = Type.values().firstOrNull { it.name == parts[0] } ?: return@mapNotNull null
            val id = parts[1].toLongOrNull() ?: return@mapNotNull null
            Pin(type, id)
        }

    private fun save(context: Context, pins: List<Pin>) {
        prefs(context).edit()
            .putString(KEY, pins.joinToString(",") { it.type.name + ":" + it.id })
            .apply()
    }

    fun contains(context: Context, type: Type, id: Long): Boolean =
        get(context).any { it.type == type && it.id == id }

    fun add(context: Context, type: Type, id: Long) {
        val pins = get(context)
        if (pins.none { it.type == type && it.id == id }) save(context, pins + Pin(type, id))
    }

    fun remove(context: Context, type: Type, id: Long) {
        save(context, get(context).filterNot { it.type == type && it.id == id })
    }
}
