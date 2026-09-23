/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.albums

import androidx.lifecycle.*
import code.name.monkey.retromusic.interfaces.IMusicServiceEventListener
import code.name.monkey.retromusic.model.Album
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.repository.RealRepository
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// نفس الفكرة اللي طبقناها في صفحة الفنان: لو الألبوم راجع من الكاش، بنأجل
// إعادة التحميل من MediaStore شوية عشان الاستعلام ما ينافسش أنيميشن الفتح.
// لو حاجة اتغيرت فعلًا الصفحة بتتحدث بعد نص ثانية بس.
private const val REFRESH_DELAY_AFTER_CACHE_HIT_MS = 500L

/**
 * بصمة خفيفة لمحتوى الألبوم، بنقارن بيها بدل الاعتماد على equals() عشان
 * نعرف هل البيانات اتغيرت فعلًا قبل ما نعيد رسم الصفحة من الأول.
 */
internal fun Album.contentSignature(): Int {
    var hash = 17
    hash = 31 * hash + title.hashCode()
    hash = 31 * hash + artistName.hashCode()
    hash = 31 * hash + (albumArtist?.hashCode() ?: 0)
    hash = 31 * hash + year
    for (song in songs) {
        hash = 31 * hash + song.id.hashCode()
        hash = 31 * hash + song.title.hashCode()
        hash = 31 * hash + song.trackNumber.hashCode()
        hash = 31 * hash + song.duration.hashCode()
        hash = 31 * hash + song.dateModified.hashCode()
    }
    return hash
}

/**
 * كاش في الذاكرة لآخر [MAX_ENTRIES] ألبومات اتفتحت صفحاتهم: بياناتهم ولون
 * الخلفية المستخرج من الغلاف. بيخلي الصفحة تظهر فورًا (بيانات + لون) لما
 * ترجع لنفس الألبوم، من غير ما تستنى استعلام MediaStore ولا استخراج اللون
 * من الصورة تاني.
 */
object AlbumDetailsCache {
    private const val MAX_ENTRIES = 6

    private class Entry(val album: Album, val signature: Int, var color: Int? = null)

    private val entries = object : LinkedHashMap<Long, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    @Synchronized
    fun get(albumId: Long): Album? = entries[albumId]?.album

    @Synchronized
    fun getColor(albumId: Long): Int? = entries[albumId]?.color

    /** بترجع true لو الألبوم جديد أو محتواه اتغير عن اللي متخزن. */
    @Synchronized
    fun put(albumId: Long, album: Album): Boolean {
        val signature = album.contentSignature()
        val existing = entries[albumId]
        if (existing != null && existing.signature == signature) return false
        entries[albumId] = Entry(album, signature, existing?.color)
        return true
    }

    @Synchronized
    fun putColor(albumId: Long, color: Int) {
        entries[albumId]?.color = color
    }
}

class AlbumDetailsViewModel(
    private val repository: RealRepository,
    private val albumId: Long
) : ViewModel(), IMusicServiceEventListener {
    private val albumDetails = MutableLiveData<Album>()

    init {
        var hasCachedAlbum = false
        AlbumDetailsCache.get(albumId)?.let { cachedAlbum ->
            albumDetails.value = cachedAlbum
            hasCachedAlbum = true
        }
        fetchAlbum(delayMs = if (hasCachedAlbum) REFRESH_DELAY_AFTER_CACHE_HIT_MS else 0L)
    }

    private fun fetchAlbum(delayMs: Long = 0L) {
        viewModelScope.launch(IO) {
            if (delayMs > 0) delay(delayMs)
            val album = repository.albumByIdAsync(albumId)
            // بنبعت القيمة للصفحة بس لو جديدة أو اتغيرت، عشان مانعيدش بناء
            // الصفحة على الفاضي وهي بتعمل أنيميشن.
            if (AlbumDetailsCache.put(albumId, album)) {
                albumDetails.postValue(album)
            }
        }
    }

    fun getAlbum(): LiveData<Album> = albumDetails

    fun getArtist(artistId: Long): LiveData<Artist> = liveData(IO) {
        val artist = repository.artistById(artistId)
        emit(artist)
    }

    fun getAlbumArtist(artistName: String): LiveData<Artist> = liveData(IO) {
        val artist = repository.albumArtistByName(artistName)
        emit(artist)
    }

    fun getMoreAlbums(artist: Artist): LiveData<List<Album>> = liveData(IO) {
        artist.albums.filter { item -> item.id != albumId }.let { albums ->
            if (albums.isNotEmpty()) emit(albums)
        }
    }

    override fun onMediaStoreChanged() {
        fetchAlbum()
    }

    override fun onServiceConnected() {}
    override fun onServiceDisconnected() {}
    override fun onQueueChanged() {}
    override fun onPlayingMetaChanged() {}
    override fun onPlayStateChanged() {}
    override fun onRepeatModeChanged() {}
    override fun onShuffleModeChanged() {}
    override fun onFavoriteStateChanged() {}
}
