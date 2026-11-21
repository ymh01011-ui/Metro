/*
 * Liquid player background for View-based apps (Metro / RetroMusicPlayer).
 *
 * Re-implementation of the technique used by Metrolist's CPU renderer
 * (MetrolistGroup/Metrolist, GPL-3.0): three copies of the cover rotate at
 * different speeds on a tiny canvas, get oversaturated and darkened, are blurred,
 * then drawn scaled up. Keep the GPL-3.0 attribution when you publish.
 */
package code.name.monkey.retromusic.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class LiquidBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    /** true = white wash over the background (use for light app themes). */
    var isLightTheme: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    private var active: Bitmap? = null
    private var previous: Bitmap? = null
    private var crossfade = 1f
    private var crossfadeAnimator: ValueAnimator? = null

    private var timeMs = 0L
    private var lastTick = 0L
    private var playing = true
    private var running = false

    private var work: Bitmap? = null
    private var workCanvas: Canvas? = null
    private var pixels = IntArray(0)
    private var scratch = IntArray(0)

    private val layerPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG).apply {
        colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(SATURATION) })
    }
    private val fillPaint = Paint()
    private val outPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val matrix = Matrix()
    private val dst = RectF()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.uptimeMillis()
            timeMs += now - lastTick
            lastTick = now
            invalidate()
            postDelayed(this, FRAME_MS)
        }
    }

    /** Call whenever the song changes. Pass the cover bitmap (it is copied). */
    fun setArtwork(bitmap: Bitmap?, animate: Boolean = true) {
        val prepared = bitmap?.let { prepare(it) }
        crossfadeAnimator?.cancel()
        val old = active
        active = prepared
        if (prepared != null && old != null && animate) {
            previous = old
            crossfade = 0f
            crossfadeAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = CROSSFADE_MS
                interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
                addUpdateListener {
                    crossfade = it.animatedValue as Float
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        previous = null
                        crossfade = 1f
                    }
                })
                start()
            }
        } else {
            previous = null
            crossfade = 1f
        }
        updateTicker()
        invalidate()
    }

    /** The motion only runs while music is playing. */
    fun setPlaying(value: Boolean) {
        playing = value
        updateTicker()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateTicker()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(ticker)
        crossfadeAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateTicker()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateTicker()
    }

    private fun updateTicker() {
        val shouldRun = playing && active != null && isAttachedToWindow && isShown
        if (shouldRun && !running) {
            running = true
            lastTick = SystemClock.uptimeMillis()
            postDelayed(ticker, FRAME_MS)
        } else if (!shouldRun && running) {
            running = false
            removeCallbacks(ticker)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return
        if (active == null) {
            canvas.drawColor(if (isLightTheme) Color.WHITE else Color.BLACK)
            return
        }

        val aspect = w.toFloat() / h
        val sw: Int
        val sh: Int
        if (aspect >= 1f) {
            sw = RENDER_DIM
            sh = (RENDER_DIM / aspect).roundToInt().coerceAtLeast(1)
        } else {
            sw = (RENDER_DIM * aspect).roundToInt().coerceAtLeast(1)
            sh = RENDER_DIM
        }
        ensureWork(sw, sh)
        renderFrame(sw, sh)

        // The blurred result is drawn 1.3x larger, centered, to hide blur edges.
        val bw = w * OVERSCAN
        val bh = h * OVERSCAN
        val left = -(bw - w) / 2f
        val top = -(bh - h) / 2f
        dst.set(left, top, left + bw, top + bh)
        canvas.drawBitmap(work!!, null, dst, outPaint)

        if (isLightTheme) canvas.drawColor(Color.argb(153, 255, 255, 255))
    }

    private fun ensureWork(sw: Int, sh: Int) {
        val cur = work
        if (cur != null && cur.width == sw && cur.height == sh) return
        cur?.recycle()
        val bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888)
        work = bmp
        workCanvas = Canvas(bmp)
        pixels = IntArray(sw * sh)
        scratch = IntArray(sw * sh)
    }

    private fun renderFrame(sw: Int, sh: Int) {
        val bmp = work ?: return
        val c = workCanvas ?: return
        c.drawColor(Color.BLACK)

        // Three layers, each turning a full circle at a different speed/direction.
        val rotA = -360f * (timeMs % 130_000L) / 130_000f
        val rotB = 360f * (timeMs % 90_000L) / 90_000f
        val rotC = 360f * (timeMs % 70_000L) / 70_000f

        // Previous cover stays opaque, the new one fades in on top (no dark dip).
        previous?.takeIf { crossfade < 1f }?.let { drawLayers(c, it, 1f, sw, sh, rotA, rotB, rotC) }
        active?.let { drawLayers(c, it, crossfade, sw, sh, rotA, rotB, rotC) }

        // Darken so white text stays readable, plus a faint white lift.
        fillPaint.color = SCRIM_DARK
        c.drawPaint(fillPaint)
        fillPaint.color = SCRIM_LIGHT
        c.drawPaint(fillPaint)

        bmp.getPixels(pixels, 0, sw, 0, 0, sw, sh)
        // Two box passes per axis == triangular kernel of radius 20.
        boxPass(pixels, scratch, sw, sh, BLUR_HALF, true)
        boxPass(scratch, pixels, sw, sh, BLUR_HALF, true)
        boxPass(pixels, scratch, sw, sh, BLUR_HALF, false)
        boxPass(scratch, pixels, sw, sh, BLUR_HALF, false)
        bmp.setPixels(pixels, 0, sw, 0, 0, sw, sh)
    }

    private fun drawLayers(
        c: Canvas,
        src: Bitmap,
        alpha: Float,
        sw: Int,
        sh: Int,
        rotA: Float,
        rotB: Float,
        rotC: Float,
    ) {
        if (alpha <= 0f || src.isRecycled || src.height <= 0) return
        val w = sw.toFloat()
        val h = sh.toFloat()
        val maxDim = (max(w, h) * OVERSCAN).roundToInt().toFloat()
        val pivot = maxDim / 2f
        val offX = -(maxDim - w) / 2f
        val offY = -(maxDim - h) / 2f
        val scale = maxDim / src.height

        layerPaint.alpha = (alpha * 255f).roundToInt().coerceIn(0, 255)

        matrix.setScale(scale, scale)
        matrix.postRotate(rotA, pivot, pivot)
        matrix.postTranslate(offX, offY)
        c.drawBitmap(src, matrix, layerPaint)

        matrix.setScale(scale, scale)
        matrix.postRotate(rotB, pivot, pivot)
        matrix.postTranslate(offX - 0.95f * w, offY - 0.7f * h)
        c.drawBitmap(src, matrix, layerPaint)

        matrix.setScale(scale, scale)
        matrix.postRotate(rotC, pivot, pivot)
        matrix.postTranslate(offX - 0.5f * w, offY + 0.7f * h)
        matrix.postRotate(rotC, w / 2f, h / 2f)
        c.drawBitmap(src, matrix, layerPaint)
    }

    /** Always copies, so a recycled/pooled Glide bitmap can never crash us. */
    private fun prepare(src: Bitmap): Bitmap? {
        if (src.isRecycled) return null
        val copy = src.copy(Bitmap.Config.ARGB_8888, false) ?: return null
        if (copy.height <= MAX_ART_HEIGHT) return copy
        val newW = (copy.width * MAX_ART_HEIGHT.toFloat() / copy.height).roundToInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(copy, newW, MAX_ART_HEIGHT, true)
    }

    private companion object {
        const val RENDER_DIM = 128
        const val OVERSCAN = 1.3f
        const val SATURATION = 2.5f
        const val BLUR_HALF = 10
        const val FRAME_MS = 50L
        const val CROSSFADE_MS = 1000L
        const val MAX_ART_HEIGHT = 256
        const val SCRIM_DARK = 0x80000000.toInt()
        const val SCRIM_LIGHT = 0x0DFFFFFF

        fun boxPass(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
            val lines = if (horizontal) h else w
            val len = if (horizontal) w else h
            val step = if (horizontal) 1 else w
            val lineStep = if (horizontal) w else 1
            val div = radius * 2 + 1
            for (line in 0 until lines) {
                val base = line * lineStep
                var rs = 0
                var gs = 0
                var bs = 0
                for (i in -radius..radius) {
                    val p = src[base + i.coerceIn(0, len - 1) * step]
                    rs += (p shr 16) and 0xFF
                    gs += (p shr 8) and 0xFF
                    bs += p and 0xFF
                }
                for (i in 0 until len) {
                    dst[base + i * step] =
                        (0xFF shl 24) or ((rs / div) shl 16) or ((gs / div) shl 8) or (bs / div)
                    val add = src[base + min(i + radius + 1, len - 1) * step]
                    val rem = src[base + max(i - radius, 0) * step]
                    rs += ((add shr 16) and 0xFF) - ((rem shr 16) and 0xFF)
                    gs += ((add shr 8) and 0xFF) - ((rem shr 8) and 0xFF)
                    bs += (add and 0xFF) - (rem and 0xFF)
                }
            }
        }
    }
}
