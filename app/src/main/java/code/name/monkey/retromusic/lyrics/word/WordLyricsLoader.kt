/*
 * Copyright (c) 2026 Metro contributors.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */
package code.name.monkey.retromusic.lyrics.word

import android.os.Environment
import android.util.Log
import code.name.monkey.retromusic.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File

/**
 * Finds the synced lyrics of a song: a .ttml or .lrc file next to the audio file or in
 * RetroMusic/lyrics, or the lyrics tag inside the audio file.
 */
object WordLyricsLoader {

    private const val TAG = "WordLyricsLoader"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024

    /**
     * Word-timed lyrics win over line-timed ones wherever they are. Between two of the same
     * kind, the first source in the list wins. Returns null when nothing is synced.
     */
    suspend fun load(song: Song): WordLyrics? = withContext(Dispatchers.IO) {
        var lineTimed: WordLyrics? = null
        for (read in sources(song)) {
            val lyrics = WordLyricsParser.parse(read()) ?: continue
            if (lyrics.hasWordTiming) return@withContext lyrics
            if (lineTimed == null) lineTimed = lyrics
        }
        lineTimed
    }

    /** Each source is only read when the ones before it had no word timing. */
    private fun sources(song: Song): List<() -> String?> {
        val audio = File(song.data)
        val folder = File(Environment.getExternalStorageDirectory(), "RetroMusic/lyrics")
        val name = "${song.title} - ${song.artistName}"
        return listOf(
            { readText(File(audio.parentFile, "${audio.nameWithoutExtension}.ttml")) },
            { readText(File(folder, "$name.ttml")) },
            { readText(File(audio.parentFile, "${audio.nameWithoutExtension}.lrc")) },
            { readText(File(folder, "$name.lrc")) },
            { readTag(audio) }
        )
    }

    private fun readText(file: File): String? = try {
        if (file.isFile && file.length() <= MAX_FILE_BYTES) file.readText() else null
    } catch (e: Exception) {
        // Typically a missing storage permission: the next source may still work
        Log.w(TAG, "Cannot read ${file.name}", e)
        null
    }

    private fun readTag(audio: File): String? = try {
        AudioFileIO.read(audio).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
    } catch (e: Exception) {
        Log.w(TAG, "Cannot read the lyrics tag", e)
        null
    }
}
