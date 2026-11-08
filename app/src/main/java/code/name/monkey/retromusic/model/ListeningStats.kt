package code.name.monkey.retromusic.model

/** Numbers shown on the Listening stats screen, derived from the play-count table. */
data class ListeningStats(
    val totalPlays: Int,
    val totalListeningMillis: Long,
    val songsPlayed: Int,
    val librarySongs: Int,
    val topSongTitle: String?,
    val topSongArtist: String?,
    val topSongPlays: Int,
    val topArtistName: String?,
    val topArtistPlays: Int,
    val topAlbumName: String?,
    val topAlbumPlays: Int
)
