package code.name.monkey.retromusic.fragments.albums

object AlbumDetailsCache {
    private val colorCache = HashMap<Long, Int>()

    fun getColor(albumId: Long): Int? = colorCache[albumId]

    fun putColor(albumId: Long, color: Int) {
        colorCache[albumId] = color
    }

    fun clear() {
        colorCache.clear()
    }
}
