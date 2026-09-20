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
package code.name.monkey.retromusic.fragments.artists

import androidx.lifecycle.*
import code.name.monkey.retromusic.interfaces.IMusicServiceEventListener
import code.name.monkey.retromusic.model.Artist
import code.name.monkey.retromusic.repository.RealRepository
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// لما الصفحة تتفتح ببيانات جاية من الكاش، بنأجل إعادة التحميل من MediaStore
// شوية عشان الاستعلام ما ينافسش أنيميشن الفتح على الـ CPU. لو البيانات
// اتغيرت فعلًا الصفحة بتتحدث بعد نص ثانية بس.
private const val REFRESH_DELAY_AFTER_CACHE_HIT_MS = 500L

/**
 * بصمة خفيفة لمحتوى الفنان (الأغاني بترتيبها + الألبومات اللي بتنتمي ليها).
 * بنقارن بيها بدل ما نعتمد على equals() عشان نعرف هل البيانات اتغيرت فعلًا
 * قبل ما نعيد بناء الصفحة من الأول.
 */
internal fun Artist.contentSignature(): Int {
    var hash = 17
    hash = 31 * hash + name.hashCode()
    for (song in songs) {
        hash = 31 * hash + song.id.hashCode()
        hash = 31 * hash + song.title.hashCode()
        hash = 31 * hash + song.albumId.hashCode()
        hash = 31 * hash + song.albumName.hashCode()
        hash = 31 * hash + song.artistName.hashCode()
        hash = 31 * hash + song.trackNumber.hashCode()
        hash = 31 * hash + song.year.hashCode()
        hash = 31 * hash + song.duration.hashCode()
        hash = 31 * hash + song.dateModified.hashCode()
    }
    return hash
}

/**
 * كاش في الذاكرة لآخر [MAX_ENTRIES] فنانين اتفتحت صفحاتهم (الأحدث بيفضل،
 * والأقدم بيتشال لما فنان جديد يدخل). بيخلي الصفحة تظهر ببياناتها فورًا من
 * غير ما تستنى استعلام MediaStore ولا طلب السيرة الذاتية.
 * كمان ArtistsFragment بتحط فيه الفنان اللي داس عليه المستخدم (اللي أصلاً
 * متحمل في القايمة) قبل ما تفتح الصفحة، فحتى أول فتح بيبقى فوري.
 */
object ArtistDetailsCache {
    private const val MAX_ENTRIES = 6

    private class Entry(
        val artist: Artist,
        val signature: Int,
        var biography: String? = null
    )

    private val entries = object : LinkedHashMap<String, Entry>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
            return size > MAX_ENTRIES
        }
    }

    fun keyForId(artistId: Long): String = "id:$artistId"
    fun keyForAlbumArtist(artistName: String): String = "album:$artistName"
    fun keyForMultiArtist(artistName: String): String = "multi:${artistName.lowercase()}"

    @Synchronized
    fun get(key: String): Artist? = entries[key]?.artist

    @Synchronized
    fun getBiography(key: String): String? = entries[key]?.biography

    /** بترجع true لو الفنان جديد أو محتواه اتغير عن اللي متخزن. */
    @Synchronized
    fun put(key: String, artist: Artist): Boolean {
        val signature = artist.contentSignature()
        val existing = entries[key]
        if (existing != null && existing.signature == signature) return false
        entries[key] = Entry(artist, signature, existing?.biography)
        return true
    }

    @Synchronized
    fun putBiography(key: String, biography: String) {
        entries[key]?.biography = biography
    }

    @Synchronized
    fun remove(key: String) {
        entries.remove(key)
    }
}

class ArtistDetailsViewModel(
    private val realRepository: RealRepository,
    private val artistId: Long?,
    private val artistName: String?,
    private val isMultiArtist: Boolean = false
) : ViewModel(), IMusicServiceEventListener {
    private val artistDetails = MutableLiveData<Artist>()
    private val biography = MutableLiveData<String?>()

    @Volatile
    private var biographyRequested = false

    // مفتاح الكاش - لازم يطابق الدوال اللي في ArtistDetailsCache.keyFor*
    // اللي ArtistsFragment بتستخدمها.
    val cacheKey: String = when {
        isMultiArtist && artistName != null -> ArtistDetailsCache.keyForMultiArtist(artistName)
        artistId != null -> ArtistDetailsCache.keyForId(artistId)
        else -> ArtistDetailsCache.keyForAlbumArtist(artistName.orEmpty())
    }

    init {
        // لو الفنان ده في الكاش، بنعرضه فورًا (بشكل متزامن، قبل أول رسم)
        // وبعدين بنراجع في الخلفية لو حاجة اتغيرت.
        var hasCachedArtist = false
        ArtistDetailsCache.get(cacheKey)?.let { cachedArtist ->
            artistDetails.value = cachedArtist
            hasCachedArtist = true
        }
        if (hasCachedArtist) {
            ArtistDetailsCache.getBiography(cacheKey)?.let { cachedBiography ->
                biography.value = cachedBiography
                biographyRequested = true
            }
        }
        fetchArtist(delayMs = if (hasCachedArtist) REFRESH_DELAY_AFTER_CACHE_HIT_MS else 0L)
    }

    private suspend fun loadArtist(): Artist? {
        return when {
            // Multi-artist mode: the artist we want may only appear inside
            // a combined tag (e.g. "Amr Diab, Jana Diab"), not as the
            // exact album_artist value, so we must use the split-aware
            // lookup instead of an exact album_artist match.
            isMultiArtist -> artistName?.let { realRepository.multiArtistByName(it) }
            artistId != null -> realRepository.artistById(artistId)
            artistName != null -> realRepository.albumArtistByName(artistName)
            else -> null
        }
    }

    private fun fetchArtist(delayMs: Long = 0L) {
        viewModelScope.launch(IO) {
            if (delayMs > 0) delay(delayMs)
            val artist = loadArtist() ?: return@launch

            if (artist.songs.isEmpty()) {
                // الصفحة هتقفل نفسها لما توصلها الحالة دي.
                ArtistDetailsCache.remove(cacheKey)
                artistDetails.postValue(artist)
                return@launch
            }

            // بنبعت القيمة للصفحة بس لو جديدة أو اتغيرت، عشان مانعيدش بناء
            // الصفحة على الفاضي وهي بتعمل أنيميشن.
            if (ArtistDetailsCache.put(cacheKey, artist)) {
                artistDetails.postValue(artist)
            }
            fetchBiography(artist.name)
        }
    }

    private fun fetchBiography(name: String) {
        if (biographyRequested) return
        biographyRequested = true
        viewModelScope.launch(IO) {
            val bio = realRepository.artistBiography(name)
            if (bio.isNullOrBlank()) {
                // فشل أو مفيش سيرة - نسمح بمحاولة تانية في التحميل الجاي.
                biographyRequested = false
            } else {
                ArtistDetailsCache.putBiography(cacheKey, bio)
            }
            biography.postValue(bio)
        }
    }

    fun getArtist(): LiveData<Artist> = artistDetails
    fun getBiography(): LiveData<String?> = biography

    override fun onMediaStoreChanged() {
        fetchArtist()
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
