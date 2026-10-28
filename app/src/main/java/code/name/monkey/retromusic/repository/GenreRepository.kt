/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.repository

import android.content.ContentResolver
import android.database.Cursor
import android.provider.BaseColumns
import android.provider.MediaStore.Audio.Genres
import code.name.monkey.retromusic.Constants.IS_MUSIC
import code.name.monkey.retromusic.Constants.baseProjection
import code.name.monkey.retromusic.extensions.getLong
import code.name.monkey.retromusic.extensions.getStringOrNull
import code.name.monkey.retromusic.model.Genre
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil

interface GenreRepository {
    fun genres(query: String): List<Genre>

    fun genres(): List<Genre>

    fun songs(genreId: Long): List<Song>

    fun song(genreId: Long): Song
}

class RealGenreRepository(
    private val contentResolver: ContentResolver,
    private val songRepository: RealSongRepository
) : GenreRepository {

    // Genre id -> MediaStore genre ids that hold its songs (rebuilt on every genres() call)
    @Volatile
    private var sourceIdsByGenreId: Map<Long, List<Long>> = emptyMap()

    // Splits "Rock, Metal" / "Pop; Dance" / "R&B/Soul" into separate genres ("&" is NOT a separator)
    private val genreSeparator = Regex("\\s*[,;/]\\s*")

    override fun genres(query: String): List<Genre> {
        return mergedGenres().filter { it.name.equals(query.trim(), ignoreCase = true) }
    }

    override fun genres(): List<Genre> {
        return mergedGenres()
    }

    override fun songs(genreId: Long): List<Song> {
        // The genres table only stores songs that have a genre specified,
        // so we need to get songs without a genre a different way.
        if (genreId == -1L) return getSongsWithNoGenre()
        return sourceIdsFor(genreId)
            .flatMap { songRepository.songs(makeGenreSongCursor(it)) }
            .distinctBy { it.id }
    }

    override fun song(genreId: Long): Song {
        val sourceIds = sourceIdsFor(genreId)
        return songRepository.song(makeGenreSongCursor(sourceIds.firstOrNull() ?: genreId))
    }

    private fun sourceIdsFor(genreId: Long): List<Long> {
        sourceIdsByGenreId[genreId]?.let { return it }
        // Registry is empty/stale (e.g. after process restore): rebuild it once
        mergedGenres()
        return sourceIdsByGenreId[genreId] ?: if (genreId > 0) listOf(genreId) else emptyList()
    }

    private class GenreGroup(val name: String) {
        val sourceIds = linkedSetOf<Long>()
        val songIds = linkedSetOf<Long>()

        // false when one of the source genres also contains other genres (e.g. "Rock, Metal")
        var exclusive = true
    }

    private fun mergedGenres(): List<Genre> {
        val sources = arrayListOf<Pair<Long, String>>()
        makeGenreCursor()?.use { cursor ->
            if (cursor.moveToFirst()) {
                do {
                    sources.add(
                        cursor.getLong(Genres._ID) to (cursor.getStringOrNull(Genres.NAME) ?: "")
                    )
                } while (cursor.moveToNext())
            }
        }

        val groups = linkedMapOf<String, GenreGroup>()
        for ((id, rawName) in sources) {
            val songIds = getSongIds(id)
            if (songIds.isEmpty()) continue
            val parts = rawName.split(genreSeparator).map { it.trim() }.filter { it.isNotEmpty() }
            val names = if (parts.isEmpty()) listOf(rawName) else parts
            for (part in names) {
                val group = groups.getOrPut(part.lowercase()) { GenreGroup(part) }
                group.sourceIds.add(id)
                group.songIds.addAll(songIds)
                if (names.size > 1) group.exclusive = false
            }
        }

        val usedIds = hashSetOf<Long>()
        val registry = hashMapOf<Long, List<Long>>()
        val result = arrayListOf<Genre>()
        for ((key, group) in groups) {
            // Keep the original MediaStore id when the genre maps 1:1 to it, otherwise a synthetic negative id
            var id = if (group.sourceIds.size == 1 && group.exclusive) {
                group.sourceIds.first()
            } else {
                -2L - (key.hashCode().toLong() and 0x7fffffffL)
            }
            while (!usedIds.add(id)) id--
            registry[id] = group.sourceIds.toList()
            result.add(Genre(id, group.name, group.songIds.size))
        }
        sourceIdsByGenreId = registry

        val comparator = compareBy<Genre, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
        return if (PreferenceUtil.genreSortOrder.contains("DESC", ignoreCase = true)) {
            result.sortedWith(comparator.reversed())
        } else {
            result.sortedWith(comparator)
        }
    }

    private fun getSongIds(genreId: Long): List<Long> {
        val ids = arrayListOf<Long>()
        try {
            contentResolver.query(
                Genres.Members.getContentUri("external", genreId),
                arrayOf(BaseColumns._ID),
                IS_MUSIC,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    do {
                        ids.add(cursor.getLong(BaseColumns._ID))
                    } while (cursor.moveToNext())
                }
            }
        } catch (e: SecurityException) {
            // no permission -> no songs
        }
        return ids
    }

    private fun getSongsWithNoGenre(): List<Song> {
        val selection =
            BaseColumns._ID + " NOT IN " + "(SELECT " + Genres.Members.AUDIO_ID + " FROM audio_genres_map)"
        return songRepository.songs(songRepository.makeSongCursor(selection, null))
    }

    private fun makeGenreSongCursor(genreId: Long): Cursor? {
        return try {
            contentResolver.query(
                Genres.Members.getContentUri("external", genreId),
                baseProjection,
                IS_MUSIC,
                null,
                PreferenceUtil.songSortOrder
            )
        } catch (e: SecurityException) {
            return null
        }
    }

    private fun makeGenreCursor(): Cursor? {
        val projection = arrayOf(Genres._ID, Genres.NAME)
        return try {
            contentResolver.query(
                Genres.EXTERNAL_CONTENT_URI,
                projection,
                null,
                null,
                PreferenceUtil.genreSortOrder
            )
        } catch (e: SecurityException) {
            return null
        }
    }
}
