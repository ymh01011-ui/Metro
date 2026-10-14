package code.name.monkey.retromusic.glide.playlistPreview

import code.name.monkey.retromusic.db.PlaylistEntity
import code.name.monkey.retromusic.db.PlaylistWithSongs
import code.name.monkey.retromusic.db.toSongs
import code.name.monkey.retromusic.model.Song

class PlaylistPreview(val playlistWithSongs: PlaylistWithSongs) {

    val playlistEntity: PlaylistEntity get() = playlistWithSongs.playlistEntity

    // بتتحسب مرة واحدة بس (قبل كده كانت بتتحول من جديد مع كل مقارنة)
    val songs: List<Song> by lazy { playlistWithSongs.songs.toSongs() }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as PlaylistPreview
        if (other.playlistEntity.playListId != playlistEntity.playListId) return false
        if (other.playlistWithSongs.songs.size != playlistWithSongs.songs.size) return false
        return true
    }

    override fun hashCode(): Int {
        var result = playlistEntity.playListId.hashCode()
        result = 31 * result + playlistWithSongs.songs.size
        return result
    }
}
