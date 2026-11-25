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

import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * One timed unit: a whole word, or a single syllable of a word.
 * [start] and [end] are character offsets inside [LyricLine.text].
 */
data class LyricWord(
    val start: Int,
    val end: Int,
    val startMs: Long,
    val endMs: Long
)

data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    /** Empty when the line only has line-level timing. */
    val words: List<LyricWord> = emptyList(),
    /** Background vocals sung along with this line. */
    val background: LyricLine? = null,
    /** True for the second singer of a duet (drawn on the other side). */
    val isOpposite: Boolean = false,
    val translation: String? = null
)

data class WordLyrics(val lines: List<LyricLine>) {
    val hasWordTiming: Boolean
        get() = lines.any { it.words.isNotEmpty() }
}

/** Parses TTML (Apple Music style), Enhanced LRC and plain synced LRC. */
object WordLyricsParser {

    /** Returns null when [raw] holds no synced lyrics at all. */
    fun parse(raw: String?): WordLyrics? {
        if (raw == null) return null
        val content = raw.removePrefix("﻿").trim()
        if (content.isEmpty()) return null
        val lines = try {
            if (isTtml(content)) TtmlParser.parse(content) else LrcParser.parse(content)
        } catch (e: Exception) {
            return null
        }
        return if (lines.isEmpty()) null else WordLyrics(lines)
    }

    fun isTtml(content: String): Boolean {
        val head = content.trimStart('﻿', ' ', '\n', '\r', '\t')
        return head.startsWith("<tt") || (head.startsWith("<?xml") && head.contains("<tt"))
    }
}

/** A timed piece of text before it is joined into a line. */
private class Piece(
    var text: String,
    val startMs: Long,
    var endMs: Long,
    var spaceAfter: Boolean = false
)

/** Joins the pieces into the line text and records where every piece sits in it. */
private fun joinPieces(pieces: List<Piece>): Pair<String, List<LyricWord>> {
    val text = StringBuilder()
    val words = ArrayList<LyricWord>(pieces.size)
    for ((index, piece) in pieces.withIndex()) {
        val start = text.length
        text.append(piece.text)
        words.add(LyricWord(start, text.length, piece.startMs, maxOf(piece.endMs, piece.startMs)))
        if (piece.spaceAfter && index != pieces.lastIndex) text.append(' ')
    }
    return text.toString() to words
}

private val WHITESPACE = Regex("\\s+")

// -------------------------------------------------------------------------------------------
// LRC / Enhanced LRC
// -------------------------------------------------------------------------------------------

private object LrcParser {

    private const val TIME = "(\\d{1,3}):(\\d{1,2})(?:[.:](\\d{1,3}))?"
    private val LINE_TAG = Regex("^\\s*\\[$TIME]")
    private val WORD_TAG = Regex("<$TIME>")
    private val OFFSET_TAG = Regex("^\\s*\\[offset:\\s*([+-]?\\d+)\\s*]", RegexOption.IGNORE_CASE)
    private val BACKGROUND_TAG = Regex("^\\s*\\[bg:(.*)]\\s*$", RegexOption.IGNORE_CASE)
    private val AGENT_TAG = Regex("^\\s*(v\\d+):\\s?", RegexOption.IGNORE_CASE)

    private const val UNKNOWN = -1L
    private const val LAST_WORD_MS = 1500L
    private const val LAST_LINE_MS = 5000L

    private class Draft(
        val startMs: Long,
        val plainText: String,
        val pieces: List<Piece>,
        val agent: String?
    ) {
        var background: List<Piece> = emptyList()
        var translation: String? = null
    }

    fun parse(content: String): List<LyricLine> {
        var offset = 0L
        val drafts = ArrayList<Draft>()
        // Lines without text only mark where the previous line stops
        val stops = ArrayList<Long>()
        var lastAdded: List<Draft> = emptyList()

        for (rawLine in content.lineSequence()) {
            val offsetMatch = OFFSET_TAG.find(rawLine)
            if (offsetMatch != null) {
                offset = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            val background = BACKGROUND_TAG.find(rawLine)
            if (background != null) {
                val pieces = parseWords(background.groupValues[1], UNKNOWN, 0L)
                if (pieces.isNotEmpty()) lastAdded.forEach { it.background = pieces }
                continue
            }

            var rest = rawLine
            val times = ArrayList<Long>(1)
            while (true) {
                val tag = LINE_TAG.find(rest) ?: break
                times.add(toMs(tag))
                rest = rest.substring(tag.range.last + 1)
            }
            if (times.isEmpty()) continue

            var agent: String? = null
            AGENT_TAG.find(rest)?.let {
                agent = it.groupValues[1].lowercase()
                rest = rest.substring(it.range.last + 1)
            }

            val plain = WORD_TAG.replace(rest, "").trim().replace(WHITESPACE, " ")
            if (plain.isEmpty()) {
                stops.addAll(times)
                continue
            }
            val hasWordTags = WORD_TAG.containsMatchIn(rest)
            lastAdded = times.map { time ->
                // A line repeated with several stamps reuses its word stamps, moved in time
                val pieces =
                    if (hasWordTags) parseWords(rest, time, time - times[0]) else emptyList()
                Draft(time, plain, pieces, agent)
            }
            drafts.addAll(lastAdded)
        }

        drafts.sortBy { it.startMs }
        stops.sort()

        // Two lines with the same stamp, only one of them word-timed: the other is a translation
        val merged = ArrayList<Draft>(drafts.size)
        for (draft in drafts) {
            val previous = merged.lastOrNull()
            if (previous != null && previous.startMs == draft.startMs &&
                previous.pieces.isEmpty() != draft.pieces.isEmpty()
            ) {
                val main = if (draft.pieces.isEmpty()) previous else draft
                val other = if (main === draft) previous else draft
                main.translation = other.plainText
                merged[merged.lastIndex] = main
            } else {
                merged.add(draft)
            }
        }

        val firstAgent = merged.firstNotNullOfOrNull { it.agent }
        return merged.mapIndexed { index, draft ->
            var next = UNKNOWN
            for (i in index + 1 until merged.size) {
                if (merged[i].startMs > draft.startMs) {
                    next = merged[i].startMs
                    break
                }
            }
            val stop = stops.firstOrNull { it > draft.startMs && (next == UNKNOWN || it <= next) }
            val limit = stop ?: next

            closeOpenWords(draft.pieces, limit)
            closeOpenWords(draft.background, limit)

            val (text, words) =
                if (draft.pieces.isEmpty()) draft.plainText to emptyList<LyricWord>()
                else joinPieces(draft.pieces)

            val endMs = when {
                words.isNotEmpty() -> words.maxOf { it.endMs }
                limit != UNKNOWN -> limit
                else -> draft.startMs + LAST_LINE_MS
            }

            val backgroundLine = if (draft.background.isEmpty()) null else {
                val (bgText, bgWords) = joinPieces(draft.background)
                LyricLine(bgWords.first().startMs, bgWords.maxOf { it.endMs }, bgText, bgWords)
            }

            LyricLine(
                startMs = (draft.startMs - offset).coerceAtLeast(0L),
                endMs = (endMs - offset).coerceAtLeast(0L),
                text = text,
                words = words.shifted(offset),
                background = backgroundLine?.let {
                    it.copy(
                        startMs = (it.startMs - offset).coerceAtLeast(0L),
                        endMs = (it.endMs - offset).coerceAtLeast(0L),
                        words = it.words.shifted(offset)
                    )
                },
                isOpposite = draft.agent != null && draft.agent != firstAgent &&
                        draft.agent != "v1000",
                translation = draft.translation
            )
        }
    }

    private fun List<LyricWord>.shifted(offset: Long): List<LyricWord> =
        if (offset == 0L) this else map {
            it.copy(
                startMs = (it.startMs - offset).coerceAtLeast(0L),
                endMs = (it.endMs - offset).coerceAtLeast(0L)
            )
        }

    /** The last word of a line often has no closing stamp. */
    private fun closeOpenWords(pieces: List<Piece>, limit: Long) {
        for (piece in pieces) {
            if (piece.endMs != UNKNOWN) continue
            val fallback = piece.startMs + LAST_WORD_MS
            piece.endMs =
                if (limit != UNKNOWN && limit > piece.startMs) minOf(limit, fallback) else fallback
        }
    }

    /**
     * Splits `<00:01.00>Hel<00:01.20>lo <00:01.50>world<00:02.00>`. Every stamp ends the text
     * before it and starts the text after it.
     */
    private fun parseWords(body: String, lineStart: Long, shift: Long): List<Piece> {
        val tags = WORD_TAG.findAll(body).toList()
        if (tags.isEmpty()) return emptyList()
        val pieces = ArrayList<Piece>(tags.size)

        fun add(segment: String, from: Long, to: Long) {
            val text = segment.trim().replace(WHITESPACE, " ")
            if (text.isEmpty()) {
                if (segment.isNotEmpty()) pieces.lastOrNull()?.spaceAfter = true
                return
            }
            if (segment.first().isWhitespace()) pieces.lastOrNull()?.spaceAfter = true
            pieces.add(Piece(text, from, to, segment.last().isWhitespace()))
        }

        val head = body.substring(0, tags.first().range.first)
        val firstTime = toMs(tags.first()) + shift
        add(head, if (lineStart == UNKNOWN) firstTime else lineStart, firstTime)

        for ((index, tag) in tags.withIndex()) {
            val next = tags.getOrNull(index + 1)
            val segment = body.substring(tag.range.last + 1, next?.range?.first ?: body.length)
            add(
                segment,
                toMs(tag) + shift,
                if (next == null) UNKNOWN else toMs(next) + shift
            )
        }
        return pieces
    }

    private fun toMs(match: MatchResult): Long {
        val minutes = match.groupValues[1].toLong()
        val seconds = match.groupValues[2].toLong()
        return minutes * 60_000L + seconds * 1000L + fractionToMs(match.groupValues[3])
    }
}

private fun fractionToMs(fraction: String): Long =
    if (fraction.isEmpty()) 0L else fraction.take(3).padEnd(3, '0').toLong()

// -------------------------------------------------------------------------------------------
// TTML
// -------------------------------------------------------------------------------------------

private object TtmlParser {

    private const val ROLE_BACKGROUND = "x-bg"
    private const val ROLE_TRANSLATION = "x-translation"

    private class Content {
        val pieces = ArrayList<Piece>()
        val plain = StringBuilder()
        var translation: String? = null
        var background: Content? = null
    }

    fun parse(content: String): List<LyricLine> {
        val factory = DocumentBuilderFactory.newInstance()
        // Attributes are read with their prefixes ("ttm:agent"), as Apple writes them
        factory.isNamespaceAware = false
        // Lyrics files come from outside: no DTDs where the platform parser lets us say so
        try {
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        } catch (e: Exception) {
            // Not supported by Android's parser, which does not load external entities
        }
        if (content.contains("<!DOCTYPE", ignoreCase = true)) return emptyList()

        val document = factory.newDocumentBuilder().parse(InputSource(StringReader(content)))
        val paragraphs = document.getElementsByTagName("p")
        val translations = headerTranslations(document.documentElement)

        val lines = ArrayList<LyricLine>(paragraphs.length)
        var firstAgent: String? = null

        for (i in 0 until paragraphs.length) {
            val p = paragraphs.item(i) as? Element ?: continue
            val parsed = Content()
            readChildren(p, parsed)

            val agent = p.getAttribute("ttm:agent").ifEmpty { null }
            if (firstAgent == null && agent != "v1000") firstAgent = agent

            val (text, words) =
                if (parsed.pieces.isEmpty()) {
                    parsed.plain.toString().trim().replace(WHITESPACE, " ") to emptyList<LyricWord>()
                } else {
                    joinPieces(parsed.pieces)
                }

            val background = parsed.background?.let { bg ->
                if (bg.pieces.isEmpty()) null else {
                    stripBrackets(bg.pieces)
                    val (bgText, bgWords) = joinPieces(bg.pieces)
                    LyricLine(
                        bgWords.first().startMs, bgWords.maxOf { it.endMs }, bgText, bgWords,
                        translation = bg.translation
                    )
                }
            }
            if (text.isEmpty() && background == null) continue

            val begin = parseTime(p.getAttribute("begin"))
                ?: words.firstOrNull()?.startMs ?: background?.startMs ?: continue
            val end = parseTime(p.getAttribute("end"))
                ?: words.maxOfOrNull { it.endMs } ?: background?.endMs ?: begin

            lines.add(
                LyricLine(
                    startMs = begin,
                    endMs = maxOf(end, begin),
                    text = text,
                    words = words,
                    background = background,
                    isOpposite = agent != null && agent != firstAgent && agent != "v1000",
                    translation = parsed.translation
                        ?: translations[p.getAttribute("itunes:key")]
                )
            )
        }
        lines.sortBy { it.startMs }
        return lines
    }

    private fun readChildren(element: Element, out: Content) {
        val children = element.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.TEXT_NODE || node.nodeType == Node.CDATA_SECTION_NODE) {
                val value = node.nodeValue ?: continue
                out.plain.append(value)
                if (value.isNotEmpty() && value.first().isWhitespace()) {
                    out.pieces.lastOrNull()?.spaceAfter = true
                }
                // Punctuation written between two spans belongs to the word before it
                val loose = value.trim()
                if (loose.isNotEmpty()) {
                    out.pieces.lastOrNull()?.let {
                        if (!value.first().isWhitespace()) it.text += loose
                        it.spaceAfter = value.last().isWhitespace()
                    }
                }
                continue
            }
            val child = node as? Element ?: continue
            if (child.tagName != "span") continue

            when (child.getAttribute("ttm:role")) {
                ROLE_BACKGROUND -> {
                    val background = out.background ?: Content().also { out.background = it }
                    readChildren(child, background)
                }
                ROLE_TRANSLATION -> out.translation = child.textContent.trim()
                "" -> readSpan(child, out)
                else -> Unit // romanization and other extras are not shown
            }
        }
    }

    private fun readSpan(span: Element, out: Content) {
        val begin = parseTime(span.getAttribute("begin"))
        val end = parseTime(span.getAttribute("end"))
        val nested = span.getElementsByTagName("span").length > 0
        if (begin == null || end == null || nested) {
            // A grouping span: its children carry the timing
            readChildren(span, out)
            return
        }
        val value = span.textContent ?: return
        out.plain.append(value)
        val text = value.trim().replace(WHITESPACE, " ")
        if (text.isEmpty()) {
            out.pieces.lastOrNull()?.spaceAfter = true
            return
        }
        if (value.first().isWhitespace()) out.pieces.lastOrNull()?.spaceAfter = true
        out.pieces.add(Piece(text, begin, end, value.last().isWhitespace()))
    }

    /** Apple wraps background vocals in brackets; they are already drawn apart. */
    private fun stripBrackets(pieces: MutableList<Piece>) {
        pieces.firstOrNull()?.let { it.text = it.text.removePrefix("(") }
        pieces.lastOrNull()?.let { it.text = it.text.removeSuffix(")") }
        pieces.removeAll { it.text.isEmpty() }
    }

    /** Apple Music keeps translations in the header, keyed by the line's itunes:key. */
    private fun headerTranslations(root: Element): Map<String, String> {
        val result = HashMap<String, String>()
        val groups = root.getElementsByTagName("translation")
        // Only the first language is shown
        val first = groups.item(0) as? Element ?: return result
        val texts = first.getElementsByTagName("text")
        for (i in 0 until texts.length) {
            val text = texts.item(i) as? Element ?: continue
            val key = text.getAttribute("for")
            val value = text.textContent.trim()
            if (key.isNotEmpty() && value.isNotEmpty()) result[key] = value
        }
        return result
    }

    /** Accepts "12.5", "12.5s", "1500ms", "01:02.345" and "1:02:03.4". */
    fun parseTime(value: String): Long? {
        val time = value.trim()
        if (time.isEmpty()) return null
        if (time.endsWith("ms")) return time.dropLast(2).toDoubleOrNull()?.toLong()
        val parts = time.removeSuffix("s").split(':')
        if (parts.size > 3) return null
        var seconds = 0.0
        for (part in parts) {
            seconds = seconds * 60 + (part.toDoubleOrNull() ?: return null)
        }
        return Math.round(seconds * 1000)
    }
}
