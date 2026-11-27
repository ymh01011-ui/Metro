/*
 * Copyright (c) 2026 Metro contributors.
 *
 * Word animation and scroll timing adapted from Metrolist (GPL-3.0),
 * https://github.com/MetrolistGroup/Metrolist
 * The scroll spring there is based on YouLyPlus (MIT).
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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import java.text.BreakIterator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.sqrt

/**
 * Apple Music style synced lyrics: a soft wipe runs through each word, sung syllables lift,
 * long words swell and glow letter by letter, and lines follow each other on a spring.
 *
 * The view runs its own frame clock. Give it [positionProvider], [isPlayingProvider] and
 * [setLyrics]; nothing else has to push progress into it.
 */
class AppleLyricsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Playback position in milliseconds; negative when unknown. */
    var positionProvider: (() -> Long)? = null

    var isPlayingProvider: (() -> Boolean)? = null

    /** Called with a line's start time when it is tapped. */
    var onSeek: ((Long) -> Unit)? = null

    var textColor: Int = Color.WHITE
        set(value) {
            field = value
            invalidate()
        }

    var textSizeSp: Float = 30f // dp, not scaled by the system font size
        set(value) {
            field = value
            requestRebuild()
        }

    var typeface: Typeface = Typeface.DEFAULT_BOLD
        set(value) {
            field = value
            requestRebuild()
        }

    /** Where the top of the current line rests, as a fraction of the view height. */
    var anchorFraction: Float = ANCHOR_FRACTION
        set(value) {
            field = value
            invalidate()
        }

    var horizontalPaddingDp: Float = 28f
        set(value) {
            field = value
            requestRebuild()
        }

    /** Shown in the middle when there are no lyrics. */
    var emptyLabel: String = ""
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val scaledDensity = context.resources.displayMetrics.scaledDensity

    private var lyrics: WordLyrics? = null
    private var rows: List<Row> = emptyList()
    private var tops = FloatArray(0)
    private var emSize = 0f

    // Playback clock, smoothed between the player's coarse position updates
    private var position = 0f
    private var hasPosition = false
    private var lastFrameNanos = 0L

    // Scrolling
    private var targetIndex = -1
    private var snapNext = true
    private var manual = false
    private var manualScroll = 0f

    // Interlude dots: three dots shown above the next line while nobody is singing
    private var dotsRowShown = -1
    private var dotsAmount = 0f
    private var dotsFrom = 0L
    private var dotsTo = 0L
    private val dotsPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var touching = false
    private var lastTouch = 0L
    private val scroller = OverScroller(context)

    private val mainPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val backgroundPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val translationPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    /** Draws glyphs into their cached masks. */
    private val maskPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    /** Draws the cached masks. Filtering lets a mask move by fractions of a pixel. */
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private var wipeShader: LinearGradient? = null
    private var wipeShaderLit = 0
    private var wipeShaderUnlit = 0
    private var wipeShaderWidth = 0f

    private val frameTick = Runnable { invalidate() }

    // The frame's start time in nanoseconds. Choreographer only offers it to callbacks.
    private var callbackNanos = 0L
    private val frameCallback = Choreographer.FrameCallback { callbackNanos = it }

    /** The start of the frame being drawn; the clock is used when no recent one is known. */
    private fun currentFrameNanos(): Long {
        val clock = System.nanoTime()
        val frame = callbackNanos
        Choreographer.getInstance().postFrameCallback(frameCallback)
        return if (frame != 0L && clock - frame in 0L..50_000_000L) frame else clock
    }

    init {
        isVerticalFadingEdgeEnabled = true
        setFadingEdgeLength((48 * density).toInt())
    }

    fun setLyrics(newLyrics: WordLyrics?) {
        lyrics = newLyrics
        scroller.forceFinished(true)
        manual = false
        hasPosition = false
        requestRebuild()
    }

    // ---------------------------------------------------------------------------------------
    // Model
    // ---------------------------------------------------------------------------------------

    /** The stretch of one visual line that a syllable covers, in reading direction. */
    private class Stop(
        val visualLine: Int,
        val startMs: Long,
        val endMs: Long,
        val from: Float,
        val to: Float,
        val rtl: Boolean,
        var first: Boolean = false,
        var last: Boolean = false
    )

    /** Per-letter motion of a long word. */
    private class Emphasis(
        val wordStartMs: Long,
        val delayMs: Float,
        val holdSec: Float,
        val responseSec: Float,
        val intensity: Float,
        val glow: Float,
        val spread: Float,
        /** The glow has its own clock: the part of the word the letter is in. */
        val glowStartMs: Long,
        val glowSpanMs: Float
    )

    /** Text drawn in one call: a syllable, a joined-script word, or one emphasised letter. */
    private class Piece(
        val start: Int,
        val end: Int,
        val visualLine: Int,
        val x: Float,
        val width: Float,
        val baseline: Float,
        val rtl: Boolean,
        val startMs: Long,
        val emphasis: Emphasis? = null,
        /** The rise of a plain piece lasts from startMs to endMs. */
        val endMs: Long = startMs + 1000L
    ) {
        // The glyphs (and their glow) rendered once, so that moving them costs no text layout
        var mask: Bitmap? = null
        var glowMask: Bitmap? = null
        /** Where the top left corner of the masks sits in the block, in whole pixels. */
        var maskLeft = 0f
        var maskTop = 0f
        var maskTried = false
        /** The masks are drawn this many times larger, so that a swelling letter stays sharp. */
        var maskScale = 1f
    }

    private class Block(
        val layout: StaticLayout,
        val paint: TextPaint,
        val pieces: List<Piece>,
        val stops: List<Stop>
    ) {
        var hasMasks = false
        var masksReady = false
    }

    private class Row(
        val line: LyricLine,
        val main: Block,
        val background: Block?,
        val translation: StaticLayout?,
        val rightAligned: Boolean,
        /** The row stays lit until this time. */
        val activeUntilMs: Long
    ) {
        var activation = 0f
        var scale = 1f
        var dim = 0.33f
        val layers = ArrayList<Layer>()

        fun offset(now: Long): Float {
            var sum = 0f
            val iterator = layers.iterator()
            while (iterator.hasNext()) {
                val layer = iterator.next()
                val progress = ScrollSpring.value((now - layer.startedAt - layer.delayMs).toFloat())
                if (progress >= 1f) iterator.remove() else sum += layer.distance * (1f - progress)
            }
            return sum
        }
    }

    /** A scroll step that is still on its way. Steps add up, so retargeting stays smooth. */
    private class Layer(val distance: Float, val startedAt: Long, val delayMs: Long)

    /**
     * The line-scroll spring of Apple Music-like Lyrics (AMLL): mass 1, damping 15, stiffness 100,
     * which is a damping ratio of 0.75 and about 3% overshoot. Apple Music itself was measured
     * at a damping ratio of 0.75-0.85 for the full-screen player.
     */
    private object ScrollSpring {
        private const val MASS = 1.0
        private const val DAMPING = 15.0
        private const val STIFFNESS = 100.0
        private const val DONE_MS = 1300f
        private val omega = sqrt(STIFFNESS / MASS)
        private val zeta = DAMPING / (2.0 * sqrt(STIFFNESS * MASS))
        private val damped = omega * sqrt(1.0 - zeta * zeta)
        private val k = zeta / sqrt(1.0 - zeta * zeta)

        /** 0 -> 1 progress of a spring that starts at rest; it overshoots 1 a little. */
        fun value(ms: Float): Float {
            if (ms <= 0f) return 0f
            if (ms >= DONE_MS) return 1f
            val t = ms / 1000.0
            val decay = exp(-zeta * omega * t)
            return (1.0 - decay * (cos(damped * t) + k * kotlin.math.sin(damped * t))).toFloat()
        }
    }

    // ---------------------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------------------

    private fun requestRebuild() {
        rows = emptyList()
        targetIndex = -1
        dotsRowShown = -1
        dotsAmount = 0f
        snapNext = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) requestRebuild() else snapNext = true
    }

    private val sidePadding: Float
        get() = horizontalPaddingDp * density

    private fun buildRows() {
        val data = lyrics ?: return
        val textWidth = (width - sidePadding * 2).toInt()
        if (textWidth <= 0) return
        emSize = textSizeSp * density
        mainPaint.textSize = emSize
        backgroundPaint.textSize = emSize * 0.6f
        translationPaint.textSize = emSize * 0.6f
        listOf(mainPaint, backgroundPaint, translationPaint).forEach {
            it.typeface = typeface
            // Letters are measured and drawn one by one: a ligature such as "fi" would give the
            // first letter the width of both and throw the second onto the next letter
            it.fontFeatureSettings = "liga 0, clig 0"
        }

        rows = data.lines.mapIndexed { index, line ->
            val alignment =
                if (line.isOpposite) Layout.Alignment.ALIGN_OPPOSITE
                else Layout.Alignment.ALIGN_NORMAL
            val main = buildBlock(line, mainPaint, textWidth, alignment, emphasise = true)
            val background = line.background?.let {
                buildBlock(it, backgroundPaint, textWidth, alignment, emphasise = false)
            }
            val translation = line.translation?.let {
                layoutText(it, translationPaint, textWidth, alignment)
            }
            val paragraphRtl = main.layout.lineCount > 0 &&
                    main.layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT

            val end = max(line.endMs, line.background?.endMs ?: 0L)
            val next = data.lines.getOrNull(index + 1)?.startMs
            val activeUntil = when {
                next == null -> end + LINE_HOLD_MS
                // A long pause: the line goes out and the interlude dots take over
                next - end >= INTERLUDE_MIN_MS -> end + LINE_TAIL_MS
                // Otherwise the line stays lit until the next one starts
                next >= end -> next
                else -> end + LINE_TAIL_MS
            }
            Row(line, main, background, translation, paragraphRtl != line.isOpposite, activeUntil)
        }
        tops = FloatArray(rows.size)
    }

    @Suppress("DEPRECATION")
    private fun layoutText(
        text: String,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment
    ): StaticLayout {
        val metrics = paint.fontMetrics
        val extra = LINE_HEIGHT_EM * paint.textSize - (metrics.descent - metrics.ascent)
        return StaticLayout(text, paint, width, alignment, 1f, extra, false)
    }

    private fun buildBlock(
        line: LyricLine,
        paint: TextPaint,
        width: Int,
        alignment: Layout.Alignment,
        emphasise: Boolean
    ): Block {
        val text = line.text
        val layout = layoutText(text, paint, width, alignment)
        val pieces = ArrayList<Piece>()
        val stops = ArrayList<Stop>()

        // Syllables with no white space between them form one word
        var first = 0
        val words = line.words
        while (first < words.size) {
            var last = first
            while (last + 1 < words.size && words[last + 1].start <= words[last].end) last++
            addWord(words.subList(first, last + 1), text, layout, paint, emphasise, pieces, stops)
            first = last + 1
        }

        for (i in stops.indices) {
            stops[i].first = i == 0 || stops[i - 1].visualLine != stops[i].visualLine
            stops[i].last = i == stops.lastIndex || stops[i + 1].visualLine != stops[i].visualLine
        }
        return Block(layout, paint, pieces, stops)
    }

    /** Part of a syllable that sits on one visual line. */
    private class Segment(val word: LyricWord, val start: Int, val end: Int, val line: Int)

    private fun addWord(
        syllables: List<LyricWord>,
        text: String,
        layout: StaticLayout,
        paint: TextPaint,
        emphasise: Boolean,
        pieces: MutableList<Piece>,
        stops: MutableList<Stop>
    ) {
        val segments = ArrayList<Segment>(syllables.size)
        for (syllable in syllables) {
            var start = syllable.start
            while (start < syllable.end) {
                val line = layout.getLineForOffset(start)
                var end = min(syllable.end, layout.getLineEnd(line))
                if (end <= start) end = syllable.end
                segments.add(Segment(syllable, start, end, line))
                start = end
            }
        }

        val wordText = text.substring(syllables.first().start, syllables.last().end)
        val joined = wordText.any { isJoiningScript(it) }
        val emphasisStarts = emphasisClusters(wordText, syllables, emphasise && !joined)

        var letterIndex = 0
        var from = 0
        while (from < segments.size) {
            val visualLine = segments[from].line
            var to = from
            while (to + 1 < segments.size && segments[to + 1].line == visualLine) to++

            // The part of the word on this visual line is measured as one run
            val runStart = segments[from].start
            val runEnd = segments[to].end
            val advances = FloatArray(runEnd - runStart)
            paint.getTextWidths(text, runStart, runEnd, advances)
            val runWidth = advances.sum()
            val rtl = layout.isRtlCharAt(runStart)
            val paragraphRtl =
                layout.getParagraphDirection(visualLine) == Layout.DIR_RIGHT_TO_LEFT
            val anchor =
                if (rtl == paragraphRtl) layout.getPrimaryHorizontal(runStart)
                else layout.getSecondaryHorizontal(runStart)
            val runLeft = if (rtl) anchor - runWidth else anchor
            val baseline = layout.getLineBaseline(visualLine).toFloat()

            fun widthOf(start: Int, end: Int): Float {
                var sum = 0f
                for (i in start until end) sum += advances[i - runStart]
                return sum
            }

            fun leftOf(start: Int, end: Int): Float =
                if (rtl) runLeft + runWidth - widthOf(runStart, end)
                else runLeft + widthOf(runStart, start)

            for (i in from..to) {
                val segment = segments[i]
                val left = leftOf(segment.start, segment.end)
                val segmentWidth = widthOf(segment.start, segment.end)
                // A syllable broken over two lines shares its time between them by length
                val word = segment.word
                val length = max(1, word.end - word.start)
                val duration = max(MIN_WORD_MS, word.endMs - word.startMs)
                val startMs = word.startMs + duration * (segment.start - word.start) / length
                val endMs = word.startMs + duration * (segment.end - word.start) / length
                val begin = if (rtl) -(left + segmentWidth) else left
                stops.add(Stop(visualLine, startMs, endMs, begin, begin + segmentWidth, rtl))

                if (emphasisStarts == null && !joined) {
                    // The one exception to "parts of a long word do not wave": a part that is a
                    // single letter, held long enough, waves on its own
                    val syllable = segment.word
                    val single = syllables.size > 1 && syllable.end - syllable.start == 1
                    val clusters = if (single && emphasise) {
                        emphasisClusters(
                            text.substring(syllable.start, syllable.end),
                            listOf(syllable),
                            true
                        )
                    } else null
                    if (clusters == null) {
                        pieces.add(
                            Piece(
                                segment.start, segment.end, visualLine, left, segmentWidth,
                                baseline, rtl, startMs, endMs = endMs
                            )
                        )
                    } else {
                        pieces.add(
                            Piece(
                                segment.start, segment.end, visualLine, left, segmentWidth,
                                baseline, rtl, syllable.startMs,
                                emphasisFor(listOf(syllable), 0, 1)
                            )
                        )
                    }
                }
            }

            if (emphasisStarts != null) {
                val base = syllables.first().start
                val partOf = emphasisStarts.map { cluster ->
                    syllables.firstOrNull {
                        base + cluster.first >= it.start && base + cluster.first < it.end
                    }
                }
                for (i in emphasisStarts.indices) {
                    val start = base + emphasisStarts[i].first
                    val end = base + emphasisStarts[i].second
                    if (start < runStart || end > runEnd) continue
                    // The glow goes by the part of the word the letter is in
                    val part = partOf[i]
                    val countInPart = max(1, partOf.count { it === part })
                    val indexInPart = partOf.subList(0, i).count { it === part }
                    pieces.add(
                        Piece(
                            start, end, visualLine, leftOf(start, end), widthOf(start, end),
                            baseline, rtl, syllables.first().startMs,
                            emphasisFor(
                                syllables, letterIndex++, emphasisStarts.size,
                                part, indexInPart, countInPart
                            )
                        )
                    )
                }
            } else if (joined) {
                // Drawing the word in one piece keeps its letters connected
                pieces.add(
                    Piece(
                        runStart, runEnd, visualLine, runLeft, runWidth, baseline, rtl,
                        segments[from].word.startMs, endMs = segments[to].word.endMs
                    )
                )
            }
            from = to + 1
        }
    }

    /** Letter ranges for a word that is held long enough to be emphasised, otherwise null. */
    private fun emphasisClusters(
        word: String,
        syllables: List<LyricWord>,
        allowed: Boolean
    ): List<Pair<Int, Int>>? {
        if (!allowed || word.any { isCjk(it) }) return null
        val sung = syllables.sumOf { it.endMs - it.startMs }
        if (sung < EMPHASIS_MIN_MS) return null
        val breaks = BreakIterator.getCharacterInstance()
        breaks.setText(word)
        val clusters = ArrayList<Pair<Int, Int>>()
        var graphemes = 0
        var start = breaks.first()
        var end = breaks.next()
        while (end != BreakIterator.DONE) {
            graphemes++
            if (clusters.isNotEmpty() && word[start] in TRAILING_PUNCTUATION) {
                clusters[clusters.lastIndex] = clusters.last().first to end
            } else {
                clusters.add(start to end)
            }
            start = end
            end = breaks.next()
        }
        return if (graphemes in 1..EMPHASIS_MAX_LETTERS) clusters else null
    }

    /** [part] is the part of the word the letter is in: the glow goes by its timing. */
    private fun emphasisFor(
        syllables: List<LyricWord>,
        index: Int,
        count: Int,
        part: LyricWord? = null,
        indexInPart: Int = index,
        countInPart: Int = count
    ): Emphasis {
        val start = syllables.first().startMs
        val seconds = max(0.001f, (syllables.last().endMs - start) / 1000f)
        val partStart = part?.startMs ?: start
        val partSeconds = part?.let { max(0.001f, (it.endMs - it.startMs) / 1000f) } ?: seconds
        val partDelay = min(partSeconds / countInPart * 0.4f, 0.4f)
        val delay = min(seconds / count * 0.4f, 0.4f)
        val middle = (count - 1) / 2f
        return Emphasis(
            wordStartMs = start,
            delayMs = (index + 1) * delay * 1000f,
            holdSec = 2f * seconds / count,
            responseSec = min(3f, seconds),
            intensity = (seconds - 1f).coerceIn(0f, 1f),
            glow = 0.45f * ((partSeconds - 0.8f) / 0.4f).coerceIn(0f, 1f),
            spread = (index - middle) / max(1f, middle),
            glowStartMs = partStart + ((indexInPart + 1) * partDelay * 1000f).toLong(),
            glowSpanMs = max(
                1f,
                2000f * partSeconds / countInPart + 2000f * min(3f, partSeconds)
            )
        )
    }

    private fun isJoiningScript(c: Char): Boolean {
        val code = c.code
        // Hebrew, Arabic and friends, Indic and South East Asian scripts, presentation forms
        return code in 0x0590..0x109F || code in 0x1780..0x18AF ||
                code in 0xFB1D..0xFDFF || code in 0xFE70..0xFEFC
    }

    private fun isCjk(c: Char): Boolean {
        val code = c.code
        return code in 0x2E80..0x9FFF || code in 0xAC00..0xD7AF || code in 0xF900..0xFAFF
    }

    // ---------------------------------------------------------------------------------------
    // Frame
    // ---------------------------------------------------------------------------------------

    private fun updateClock(elapsed: Float, playing: Boolean): Boolean {
        val raw = positionProvider?.invoke() ?: -1L
        if (raw < 0L) return false
        if (!hasPosition) {
            hasPosition = true
            position = raw.toFloat()
            return false
        }
        val predicted = if (playing) position + elapsed else position
        val error = raw - predicted
        return if (abs(error) > SEEK_THRESHOLD_MS) {
            position = raw.toFloat()
            true
        } else if (!playing) {
            position = raw.toFloat()
            false
        } else {
            // The player reports coarsely, so the clock runs on its own and is only nudged
            // towards it. The nudge is limited in speed, which keeps motion even and means
            // the clock can never step back.
            val pull = error * (1f - exp(-elapsed / CLOCK_TAU_MS))
            val limit = elapsed * CLOCK_MAX_SLEW
            position = predicted + pull.coerceIn(-limit, limit)
            false
        }
    }

    /** The line the view should be resting on. It moves a little ahead of the singing. */
    private fun findTarget(now: Long): Int {
        var index = 0
        var heldBack = false
        for (i in 1 until rows.size) {
            val start = rows[i].line.startMs
            val previous = rows[i - 1].line
            // Line-timed lyrics have no pause between lines, so they always lead
            val previousEnd = if (previous.words.isEmpty()) 0L else previous.endMs
            val due = max(start - SCROLL_LEAD_MS, min(previousEnd, start))
            if (now < due) break
            // A line that starts while the current one is still being sung does not scroll;
            // the view moves on when the line after that starts
            if (!heldBack && start < rows[index].line.endMs) {
                heldBack = true
            } else {
                index = i
                heldBack = false
            }
        }
        return index
    }

    private fun computeTops() {
        val gap = emSize * 0.35f
        var y = 0f
        for (i in rows.indices) {
            val row = rows[i]
            if (i == dotsRowShown) y += dotsSpace() * dotsAmount
            tops[i] = y
            y += row.main.layout.height
            row.background?.let { y += (it.layout.height + emSize * BG_GAP_EM) * row.activation }
            row.translation?.let { y += it.height + gap * 0.5f }
            y += emSize * ROW_GAP_EM
        }
    }

    private fun restingScroll(index: Int): Float {
        val dots = if (index == dotsRowShown) dotsSpace() * dotsAmount else 0f
        return tops[index] - dots - height * anchorFraction
    }

    private fun dotsSpace(): Float = emSize * DOTS_SPACE_EM

    /**
     * The row that the interlude dots sit above, or -1. Like AMLL, the dots are for a pause of
     * at least 4 seconds (before the first line the dots stop a little before it starts).
     */
    private fun findInterlude(time: Long): Int {
        var next = -1
        var previousEnd = 0L
        for (i in rows.indices) {
            val row = rows[i]
            if (time >= row.line.startMs - HIGHLIGHT_LEAD_MS && time < row.activeUntilMs) return -1
            if (row.line.startMs > time) {
                if (next < 0) next = i
            } else {
                previousEnd = max(previousEnd, row.line.endMs, row.line.background?.endMs ?: 0L)
            }
        }
        if (next < 0) return -1
        val end = rows[next].line.startMs - if (next == 0) 250L else 0L
        if (end - previousEnd < INTERLUDE_MIN_MS || time < previousEnd || time >= end) return -1
        dotsFrom = previousEnd
        dotsTo = end
        return next
    }

    private fun drawInterludeDots(canvas: Canvas, now: Long, resting: Float) {
        val row = rows[dotsRowShown]
        val rowTop = tops[dotsRowShown] - if (manual) manualScroll else resting - row.offset(now)
        val radius = emSize * DOT_RADIUS_EM
        val centerY = rowTop - dotsSpace() * dotsAmount + radius
        val span = (dotsTo - dotsFrom).toFloat()
        val elapsed = position - dotsFrom
        val progress = if (span > 0f) (elapsed / span).coerceIn(0f, 1f) else 1f
        // The three dots light up one after another, the group breathes, and at the end the
        // dots shrink away just before the line starts
        val closing = ((dotsTo - position) / DOT_CLOSE_MS).coerceIn(0f, 1f)
        val breath = 1f + DOT_BREATH * kotlin.math.sin(2.0 * PI * elapsed / DOT_BREATH_MS).toFloat()
        val scale = breath * (0.5f + 0.5f * closing)
        val pitch = radius * DOT_PITCH
        val total = pitch * 2f + radius * 2f
        val indent = emSize * DOT_INDENT_EM
        val startX = if (row.rightAligned) width - sidePadding - indent - total
        else sidePadding + indent
        val rtlText = row.main.layout.lineCount > 0 &&
                row.main.layout.getParagraphDirection(0) == Layout.DIR_RIGHT_TO_LEFT
        val centerX = startX + total / 2f
        canvas.save()
        canvas.scale(scale, scale, centerX, centerY)
        for (i in 0 until 3) {
            val lit = (progress * 3f - i).coerceIn(0f, 1f)
            val alpha = (DOT_UNLIT + (1f - DOT_UNLIT) * lit) * dotsAmount * closing
            dotsPaint.color = withAlpha(alpha)
            // In right-to-left text the first dot is the one on the right
            val dotX = if (rtlText) startX + total - radius - i * pitch
            else startX + radius + i * pitch
            canvas.drawCircle(dotX, centerY, radius, dotsPaint)
        }
        canvas.restore()
    }

    private fun moveTarget(index: Int, now: Long, jumped: Boolean) {
        if (index == targetIndex) return
        val old = targetIndex
        targetIndex = index
        if (manual) return
        if (old < 0 || snapNext) {
            rows.forEach { it.layers.clear() }
            return
        }
        val distance = restingScroll(index) - restingScroll(old)
        if (abs(distance) > height * 2f) {
            rows.forEach { it.layers.clear() }
            return
        }
        // All lines move together, with no delay between them
        for (i in rows.indices) {
            rows[i].layers.add(Layer(distance, now, 0L))
        }
    }

    /** Leaves manual scrolling and glides back to the current line. */
    private fun followAgain(now: Long) {
        if (!manual) return
        manual = false
        scroller.forceFinished(true)
        if (targetIndex !in rows.indices) return
        val distance = restingScroll(targetIndex) - manualScroll
        rows.forEach {
            it.layers.clear()
            it.layers.add(Layer(distance, now, 0L))
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        removeCallbacks(frameTick)
        if (rows.isEmpty()) buildRows()
        if (rows.isEmpty()) {
            drawEmptyLabel(canvas)
            return
        }

        // Time comes from the frame itself, not from when this code happens to run, so a
        // late frame does not make the motion speed up and slow down
        val frameNanos = currentFrameNanos()
        val now = frameNanos / 1_000_000L
        val gapNanos = if (lastFrameNanos == 0L) 0L else max(0L, frameNanos - lastFrameNanos)
        val elapsed = (gapNanos / 1_000_000f).coerceIn(0f, 100f)
        // After a pause in drawing the position is taken from the player as it is
        if (gapNanos > STALE_FRAME_MS * 1_000_000L) hasPosition = false
        lastFrameNanos = frameNanos
        val playing = isPlayingProvider?.invoke() == true
        val jumped = updateClock(elapsed, playing)
        val time = position.toLong()

        var settling = false
        val snap = snapNext
        // During a long pause in the singing the view rests on the next line, with the dots
        // above it (as AMLL does)
        val interlude = findInterlude(time)
        if (interlude >= 0) dotsRowShown = interlude
        val nextTarget = if (interlude >= 0) interlude else findTarget(time)
        for (i in rows.indices) {
            val row = rows[i]
            val active = time >= row.line.startMs - HIGHLIGHT_LEAD_MS && time < row.activeUntilMs
            val activationTarget = if (active) 1f else 0f
            val distance = abs(i - nextTarget)
            // The line the view scrolls to grows while it scrolls, not when it is sung
            val scaleTarget = if (i == nextTarget) ACTIVE_SIZE_RATIO else 1f
            val dimTarget = when {
                active || manual -> UNLIT
                distance <= 2 -> UNLIT
                distance == 3 -> 0.28f
                else -> 0.22f
            }
            if (snap) {
                row.activation = activationTarget
                row.scale = scaleTarget
                row.dim = dimTarget
            } else {
                row.dim = approach(row.dim, dimTarget, elapsed, 250f)
                if (row.dim != dimTarget) settling = true
                row.activation = approach(row.activation, activationTarget, elapsed, 120f)
                row.scale = approach(row.scale, scaleTarget, elapsed, 200f)
                if (row.activation != activationTarget || row.scale != scaleTarget) settling = true
            }
        }
        val dotsTarget = if (interlude >= 0) 1f else 0f
        dotsAmount = if (snap) dotsTarget else approach(dotsAmount, dotsTarget, elapsed, 250f)
        if (dotsAmount != dotsTarget) settling = true
        if (dotsAmount == 0f && interlude < 0) dotsRowShown = -1
        computeTops()

        if (manual) {
            if (scroller.computeScrollOffset()) {
                manualScroll = scroller.currY.toFloat()
                lastTouch = now
                settling = true
            } else if (!touching && now - lastTouch > MANUAL_TIMEOUT_MS) {
                targetIndex = nextTarget
                followAgain(now)
            }
        }
        moveTarget(nextTarget, now, jumped)
        snapNext = false

        // Glyph masks are made a few at a time for the lines about to be sung, and dropped
        // for lines that are far away
        var budget = MASKS_PER_FRAME
        for (i in rows.indices) {
            val row = rows[i]
            if ((i >= nextTarget - 1 && i <= nextTarget + 2) || row.activation > 0f) {
                budget = warmMasks(row.main, budget)
                row.background?.let { budget = warmMasks(it, budget) }
            } else {
                if (row.main.hasMasks) releaseMasks(row.main)
                row.background?.let { if (it.hasMasks) releaseMasks(it) }
            }
        }

        val resting = restingScroll(targetIndex)
        for (i in rows.indices) {
            val row = rows[i]
            val offset = row.offset(now)
            if (row.layers.isNotEmpty()) settling = true
            val rawTop = tops[i] - if (manual) manualScroll else resting - offset
            // At rest, lines sit on whole pixels so that they are as sharp as plain text
            val top = if (!manual && row.layers.isEmpty()) round(rawTop) else rawTop
            val bottom = (tops.getOrNull(i + 1) ?: (tops[i] + height.toFloat())) - tops[i] + top
            if (bottom < -emSize || top > height + emSize) continue
            drawRow(canvas, row, top, position)
        }

        if (dotsRowShown in rows.indices && dotsAmount > 0.01f) drawInterludeDots(canvas, now, resting)

        if (playing || settling) {
            postInvalidateOnAnimation()
        } else {
            // Paused and at rest: only look now and then for a seek or for playback resuming
            postDelayed(frameTick, IDLE_FRAME_MS)
        }
    }

    private fun approach(value: Float, target: Float, elapsed: Float, tauMs: Float): Float {
        val next = value + (target - value) * (1f - exp(-elapsed / tauMs))
        return if (abs(target - next) < 0.002f) target else next
    }

    private fun drawEmptyLabel(canvas: Canvas) {
        if (emptyLabel.isEmpty() || width <= 0) return
        translationPaint.textSize = 18 * scaledDensity
        translationPaint.typeface = typeface
        translationPaint.shader = null
        translationPaint.color = withAlpha(0.6f)
        val layout = layoutText(emptyLabel, translationPaint, width, Layout.Alignment.ALIGN_CENTER)
        canvas.save()
        canvas.translate(0f, (height - layout.height) / 2f)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun withAlpha(alpha: Float): Int {
        val value = (Color.alpha(textColor) * alpha.coerceIn(0f, 1f)).toInt()
        return textColor and 0x00FFFFFF or (value shl 24)
    }

    private fun drawRow(canvas: Canvas, row: Row, top: Float, time: Float) {
        val gap = emSize * 0.35f
        canvas.save()
        canvas.translate(round(sidePadding), top)
        val layoutWidth = row.main.layout.width.toFloat()
        canvas.scale(
            row.scale, row.scale,
            if (row.rightAligned) layoutWidth else 0f, row.main.layout.height / 2f
        )

        drawBlock(canvas, row.main, row.activation, time, 1f, row.dim)
        var y = row.main.layout.height.toFloat()

        val background = row.background
        if (background != null) {
            if (row.activation > 0.01f) {
                canvas.save()
                canvas.translate(0f, y + emSize * BG_GAP_EM * row.activation)
                // Background vocals are quieter and only appear while their line is active
                drawBlock(canvas, background, row.activation, time, row.activation * 0.75f, row.dim)
                canvas.restore()
            }
            y += (background.layout.height + emSize * BG_GAP_EM) * row.activation
        }

        row.translation?.let {
            canvas.save()
            canvas.translate(0f, y + gap * 0.5f)
            translationPaint.textSize = emSize * 0.6f
            translationPaint.shader = null
            translationPaint.color = withAlpha(row.dim + 0.2f * row.activation)
            it.draw(canvas)
            canvas.restore()
        }
        canvas.restore()
    }

    private fun drawBlock(
        canvas: Canvas,
        block: Block,
        activation: Float,
        time: Float,
        opacity: Float = 1f,
        base: Float = UNLIT
    ) {
        val paint = block.paint
        paint.shader = null
        val lit = withAlpha((base + (1f - base) * activation) * opacity)
        val unlit = withAlpha(base * opacity)

        // Line-timed lyrics, and rows at rest, are a single colour
        if (block.pieces.isEmpty() || activation <= 0f) {
            paint.color = if (block.pieces.isEmpty()) lit else unlit
            block.layout.draw(canvas)
            return
        }

        // Where the wipe is: the syllable being sung, or the last one that was
        var currentIndex = -1
        for (i in block.stops.indices) {
            if (block.stops[i].startMs <= time) currentIndex = i else break
        }
        val current: Stop? = block.stops.getOrNull(currentIndex)
        // The soft edge reaches further ahead of the wipe than behind it, so the next word
        // starts to light up before the wipe arrives
        val trail = emSize * WIPE_TRAIL_EM
        val lead = emSize * WIPE_LEAD_EM
        val gradient = trail + lead
        var wipeLine = -1
        var wipe = 0f
        if (current != null) {
            val progress =
                ((time - current.startMs).toFloat() / max(1L, current.endMs - current.startMs))
                    .coerceIn(0f, 1f)
            val from = current.from
            // The sweep runs on to the start of the next syllable (spaces included), so it
            // never restarts at a word: its speed is the timing of the syllable it is on
            val next = block.stops.getOrNull(currentIndex + 1)
            val to = if (current.last) current.to + trail
            else if (next != null && next.rtl == current.rtl) max(next.from, current.to)
            else current.to
            wipeLine = current.visualLine
            val sweep = from + (to - from) * progress
            // The lead starts at zero at the beginning of each visual line and grows to its
            // full size over the first LEAD_GROW_EM of the line, so nothing lights up early
            var k = currentIndex
            while (k > 0 && !block.stops[k].first) k--
            val grow = ((sweep - block.stops[k].from) / (emSize * LEAD_GROW_EM)).coerceIn(0f, 1f)
            wipe = sweep - lead + lead * easeInOut(grow)
        } else {
            // Before the first syllable nothing is lit
            val first = block.stops.firstOrNull()
            if (first != null) {
                wipeLine = first.visualLine
                wipe = first.from - lead
            }
        }

        for (piece in block.pieces) {
            var lift = 0f
            var shift = 0f
            var scale = 1f
            var glow = 0f
            var settled = true
            val emphasis = piece.emphasis
            if (emphasis == null) {
                val rise = easeInOut(((time - piece.startMs) / LIFT_MS).coerceIn(0f, 1f))
                lift = -0.05f * emSize * rise * activation
                settled = rise >= 1f
            } else {
                val spanMs =
                    max(1f, emphasis.holdSec * 1000f + emphasis.responseSec * 2000f)
                val fraction =
                    ((time - emphasis.wordStartMs - emphasis.delayMs) / spanMs).coerceIn(0f, 1f)
                if (fraction > 0f) {
                    val seconds = spanMs / 1000f * fraction
                    val rising = spring(seconds, emphasis.responseSec)
                    val pulse = if (fraction >= 1f) 0f else {
                        rising * (1f - spring(seconds - emphasis.holdSec, emphasis.responseSec)) *
                                emphasis.intensity * activation
                    }
                    lift = -(0.05f * (if (fraction >= 1f) 1f else rising) + 0.035f * pulse) *
                            emSize * activation
                    shift = emphasis.spread * 0.05f * emSize * pulse
                    scale = 1f + 0.1f * pulse
                }
                val glowFraction =
                    ((time - emphasis.glowStartMs) / emphasis.glowSpanMs).coerceIn(0f, 1f)
                glow = emphasis.glow * glowCurve(glowFraction) * activation
                settled = fraction <= 0f || fraction >= 1f
            }

            val begin = if (piece.rtl) -(piece.x + piece.width) else piece.x
            // Positions cannot be compared across a change of direction: go by time there
            val opposed = current != null && piece.visualLine == wipeLine &&
                    piece.rtl != current.rtl
            val isLit = piece.visualLine < wipeLine || (piece.visualLine == wipeLine &&
                    if (opposed) piece.startMs <= time else begin + piece.width <= wipe - trail)
            val isUnlit = piece.visualLine > wipeLine ||
                    if (opposed) piece.startMs > time else begin >= wipe + lead
            // A piece that has stopped moving sits on whole pixels
            if (settled && activation >= 1f) lift = round(lift)

            val mask = ensureMask(block, piece)
            canvas.save()
            canvas.translate(shift, lift)
            if (scale != 1f) {
                canvas.scale(
                    scale, scale,
                    piece.x + piece.width / 2f, piece.baseline + 0.1f * emSize
                )
            }
            if (mask != null) {
                // Cached glyphs move by fractions of a pixel, so slow motion stays smooth
                canvas.translate(piece.maskLeft, piece.maskTop)
                val maskScale = piece.maskScale
                if (maskScale != 1f) canvas.scale(1f / maskScale, 1f / maskScale)
                val glowMask = piece.glowMask
                if (glow > 0.01f && glowMask != null) {
                    bitmapPaint.shader = null
                    bitmapPaint.color = withAlpha(glow)
                    canvas.drawBitmap(glowMask, 0f, 0f, bitmapPaint)
                }
                if (isLit) {
                    bitmapPaint.shader = null
                    bitmapPaint.color = lit
                } else if (isUnlit) {
                    bitmapPaint.shader = null
                    bitmapPaint.color = unlit
                } else {
                    bitmapPaint.color = Color.BLACK // the shader supplies colour and alpha
                    bitmapPaint.shader =
                        wipeShader(
                            lit, unlit, gradient, wipe - trail, piece.rtl, piece.maskLeft, maskScale
                        )
                }
                canvas.drawBitmap(mask, 0f, 0f, bitmapPaint)
            } else {
                paint.shader = null
                if (isLit) {
                    paint.color = lit
                } else if (isUnlit) {
                    paint.color = unlit
                } else {
                    paint.color = Color.BLACK
                    paint.shader = wipeShader(lit, unlit, gradient, wipe - trail, piece.rtl)
                }
                if (glow > 0.01f) paint.setShadowLayer(GLOW_RADIUS_EM * emSize, 0f, 0f, withAlpha(glow))
                canvas.drawText(
                    block.layout.text, piece.start, piece.end, piece.x, piece.baseline, paint
                )
                if (glow > 0.01f) paint.clearShadowLayer()
            }
            canvas.restore()
        }
        bitmapPaint.shader = null
        paint.shader = null
    }

    // ---------------------------------------------------------------------------------------
    // Glyph masks
    // ---------------------------------------------------------------------------------------

    private fun ensureMask(block: Block, piece: Piece): Bitmap? {
        if (piece.mask == null && !piece.maskTried) buildMask(block, piece)
        return piece.mask
    }

    /** Makes masks for the pieces of a block that have none yet, within this frame's budget. */
    private fun warmMasks(block: Block, budget: Int): Int {
        if (block.masksReady || block.pieces.isEmpty()) return budget
        var left = budget
        for (piece in block.pieces) {
            if (piece.mask != null || piece.maskTried) continue
            if (left <= 0) return 0
            buildMask(block, piece)
            left--
        }
        block.masksReady = true
        return left
    }

    private fun releaseMasks(block: Block) {
        for (piece in block.pieces) {
            piece.mask = null
            piece.glowMask = null
            piece.maskTried = false
        }
        block.hasMasks = false
        block.masksReady = false
    }

    /**
     * Renders a piece once as coverage (alpha only) and, if it glows, its blurred glow too.
     * Colour is applied when the mask is drawn, so the wipe still costs nothing.
     */
    private fun buildMask(block: Block, piece: Piece) {
        piece.maskTried = true
        block.hasMasks = true
        val p = maskPaint
        p.set(block.paint)
        p.shader = null
        p.maskFilter = null
        p.clearShadowLayer()
        p.color = Color.WHITE

        // A letter that swells is rendered at its largest size and drawn smaller when at rest,
        // so that it is not blown up (and blurred) while it swells
        val scale = if (piece.emphasis != null) EMPHASIS_SUPERSAMPLE else 1f
        p.textSize = p.textSize * scale
        val metrics = p.fontMetrics
        val pad = ceil(emSize * MASK_PAD_EM * scale).toInt()
        val top = floor(metrics.top).toInt()
        val height = ceil(metrics.bottom).toInt() - top + pad * 2
        // The whole-pixel part of the position is the mask's corner, the rest stays in the text
        val originX = floor(piece.x)
        val fraction = piece.x - originX
        val width = ceil((fraction + piece.width) * scale).toInt() + pad * 2
        if (width <= 0 || height <= 0 || width.toLong() * height > MAX_MASK_PIXELS) return

        val text = block.layout.text
        val textX = pad + fraction * scale
        val textY = (pad - top).toFloat()
        try {
            val glyphs = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
            Canvas(glyphs).drawText(text, piece.start, piece.end, textX, textY, p)

            var glow: Bitmap? = null
            if ((piece.emphasis?.glow ?: 0f) > 0.01f) {
                p.maskFilter = BlurMaskFilter(
                    max(1f, GLOW_RADIUS_EM * emSize * scale), BlurMaskFilter.Blur.NORMAL
                )
                glow = Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8)
                Canvas(glow).drawText(text, piece.start, piece.end, textX, textY, p)
                p.maskFilter = null
            }
            piece.maskScale = scale
            piece.maskLeft = originX - pad / scale
            piece.maskTop = piece.baseline + (top - pad) / scale
            piece.glowMask = glow
            piece.mask = glyphs
        } catch (e: OutOfMemoryError) {
            // Out of memory: this piece is drawn as plain text instead
            p.maskFilter = null
        }
    }

    /** A soft edge from lit to unlit that starts at [start], counted in reading direction. */
    private fun wipeShader(
        lit: Int,
        unlit: Int,
        width: Float,
        start: Float,
        rtl: Boolean,
        originX: Float = 0f,
        scale: Float = 1f
    ): Shader {
        var shader = wipeShader
        if (shader == null || lit != wipeShaderLit || unlit != wipeShaderUnlit ||
            width != wipeShaderWidth
        ) {
            // The middle stop sits where the sweep really is. It is a little darker than a
            // straight line from lit to unlit, which tones down the light that runs ahead
            val centre = WIPE_TRAIL_EM / (WIPE_TRAIL_EM + WIPE_LEAD_EM)
            val middle = blend(lit, unlit, min(0.95f, centre + WIPE_AHEAD_DIM))
            shader = LinearGradient(
                0f, 0f, width, 0f,
                intArrayOf(lit, middle, unlit), floatArrayOf(0f, centre, 1f),
                Shader.TileMode.CLAMP
            )
            wipeShader = shader
            wipeShaderLit = lit
            wipeShaderUnlit = unlit
            wipeShaderWidth = width
        }
        shaderMatrix.reset()
        if (rtl) shaderMatrix.setScale(-1f, 1f)
        shaderMatrix.postTranslate(if (rtl) -start else start, 0f)
        // Masks are drawn from their own corner, text from the block's
        shaderMatrix.postTranslate(-originX, 0f)
        if (scale != 1f) shaderMatrix.postScale(scale, 1f)
        shader.setLocalMatrix(shaderMatrix)
        return shader
    }

    private fun blend(from: Int, to: Int, t: Float): Int {
        fun channel(shift: Int): Int {
            val a = (from shr shift) and 0xFF
            val b = (to shr shift) and 0xFF
            return (a + (b - a) * t).toInt().coerceIn(0, 255) shl shift
        }
        return channel(24) or channel(16) or channel(8) or channel(0)
    }

    private fun spring(seconds: Float, response: Float): Float {
        if (seconds <= 0f) return 0f
        val x = 2f * PI.toFloat() * seconds / max(0.001f, response)
        return 1f - (1f + x) * exp(-x)
    }

    private fun glowCurve(fraction: Float): Float = when {
        fraction <= 0f || fraction >= 1f -> 0f
        fraction < 0.15f -> 0.8f * fraction / 0.15f
        fraction < 0.30f -> 0.8f + 0.2f * (fraction - 0.15f) / 0.15f
        fraction < 0.60f -> 1f - 0.7f * (fraction - 0.30f) / 0.30f
        else -> 0.3f * (1f - fraction) / 0.40f
    }

    private fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)

    override fun getTopFadingEdgeStrength(): Float = if (rows.isEmpty()) 0f else 1f

    override fun getBottomFadingEdgeStrength(): Float = if (rows.isEmpty()) 0f else 1f

    // ---------------------------------------------------------------------------------------
    // Touch
    // ---------------------------------------------------------------------------------------

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                startManual()
                manualScroll = clampScroll(manualScroll + distanceY)
                invalidate()
                return true
            }

            override fun onFling(
                e1: MotionEvent,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                startManual()
                scroller.fling(
                    0, manualScroll.toInt(), 0, -velocityY.toInt(), 0, 0,
                    restingScroll(0).toInt(), restingScroll(rows.lastIndex).toInt()
                )
                invalidate()
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val row = rowAt(e.y)
                val seek = onSeek
                if (row != null && seek != null) {
                    followAgain(SystemClock.uptimeMillis())
                    seek(row.line.startMs)
                    invalidate()
                } else {
                    performClick()
                }
                return true
            }
        }
    ).apply { setIsLongpressEnabled(false) }

    private fun startManual() {
        if (manual || targetIndex !in rows.indices) return
        // Start from where the current line is on screen right now
        manualScroll = restingScroll(targetIndex) -
                rows[targetIndex].offset(SystemClock.uptimeMillis())
        manual = true
        rows.forEach { it.layers.clear() }
    }

    private fun clampScroll(value: Float): Float =
        value.coerceIn(restingScroll(0), restingScroll(rows.lastIndex))

    private fun rowAt(y: Float): Row? {
        if (targetIndex !in rows.indices) return null
        val now = SystemClock.uptimeMillis()
        for (i in rows.indices) {
            val scroll =
                if (manual) manualScroll else restingScroll(targetIndex) - rows[i].offset(now)
            val top = tops[i] - scroll
            val bottom = top + rows[i].main.layout.height + emSize * ROW_GAP_EM
            if (y >= top - emSize * 0.3f && y < bottom) return rows[i]
        }
        return null
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (rows.isEmpty()) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // The player sheet must not take over a drag that scrolls the lyrics
                parent?.requestDisallowInterceptTouchEvent(true)
                touching = true
                scroller.forceFinished(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touching = false
        }
        lastTouch = SystemClock.uptimeMillis()
        gestures.onTouchEvent(event)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) invalidate() else removeCallbacks(frameTick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(frameTick)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val UNLIT = 0.33f
        const val LINE_HEIGHT_EM = 1.2f
        const val ROW_GAP_EM = 1.17f // gap between two lyric lines, in font sizes
        const val BG_GAP_EM = 0.92f // gap between the main line and its small background line
        const val ANCHOR_FRACTION = 0.27f // where the sung line rests: higher number = lower on screen
        const val WIPE_TRAIL_EM = 0.5f
        const val WIPE_LEAD_EM = 0.5f
        const val INTERLUDE_MIN_MS = 6000L // shortest pause that gets dots
        const val DOTS_SPACE_EM = 1.5f // room the dots take above the next line
        const val DOT_RADIUS_EM = 0.175f
        const val DOT_PITCH = 3.3f // distance between dot centres, in radii
        const val DOT_INDENT_EM = 0.1f // how far the dots sit in from the text's edge
        const val DOT_UNLIT = 0.3f
        const val DOT_BREATH = 0.1f // how much the group grows and shrinks
        const val DOT_BREATH_MS = 4500.0 // one breath
        const val DOT_CLOSE_MS = 600f // the dots shrink away over this long at the end
        const val LEAD_GROW_EM = 1.5f
        const val ACTIVE_SIZE_RATIO = 31f / 30f // the line being sung is a little larger
        const val WIPE_AHEAD_DIM = 0.12f // higher = less light ahead of the sweep // distance over which the lead grows at a line's start
        const val MIN_WORD_MS = 100L
        const val LIFT_MS = 1000f
        const val EMPHASIS_MIN_MS = 1000L
        const val EMPHASIS_MAX_LETTERS = 7
        const val EMPHASIS_SUPERSAMPLE = 1.1f // letters that swell are rendered this much larger
        const val TRAILING_PUNCTUATION = "!?.,;:~…‥。、！？"
        const val HIGHLIGHT_LEAD_MS = 150L
        const val SCROLL_LEAD_MS = 350L
        const val LINE_HOLD_MS = 1000L
        const val LINE_TAIL_MS = 300L
        const val SEEK_THRESHOLD_MS = 250f
        const val MANUAL_TIMEOUT_MS = 3000L
        const val IDLE_FRAME_MS = 250L
        const val STALE_FRAME_MS = 400L
        const val CLOCK_TAU_MS = 250f
        const val CLOCK_MAX_SLEW = 0.12f
        const val MASKS_PER_FRAME = 3
        const val MASK_PAD_EM = 0.3f
        const val GLOW_RADIUS_EM = 0.09f
        const val MAX_MASK_PIXELS = 1_000_000L
    }
}
