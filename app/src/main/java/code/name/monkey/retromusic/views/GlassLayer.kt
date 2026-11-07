package code.name.monkey.retromusic.views

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Rect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.PixelCopy
import android.view.ViewGroup
import android.view.Window
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil

/**
 * A pill of real-time glass.
 *
 * Instead of copying the page into a bitmap (what made the old blur lag behind and show parts of
 * the previous page during transitions), the page behind is drawn straight into a GPU RenderNode,
 * blurred and colour-boosted by a RenderEffect, and drawn inside the pill. It is therefore always
 * exactly what is on screen in that very frame. Needs Android 12 (API 31); below that the pill is
 * a flat translucent surface.
 */
class GlassLayer(context: Context) : FrameLayout(context) {

    /** The view whose content shows through the glass (it must not contain this layer). */
    var backdrop: ViewGroup? = null

    /** false = no blur at all (flat pill), used by the "Fastest" material and below Android 12. */
    var live = true

    /** Blur strength in px (sigma). */
    var blurSigmaPx = 8f

    /** 1 = untouched colours, higher = more vivid (Square's "Colour"). */
    var saturation = 1.5f

    /** Veil painted over the blurred page (Square's "Film"). */
    var filmColor = Color.TRANSPARENT

    /**
     * Painted under the page before it is blurred. Needed when the backdrop view itself has no
     * background (a bare list): its gaps are transparent, so the blurred copy would be partly
     * transparent and the sharp page would show through the glass.
     */
    var baseColor = Color.TRANSPARENT

    /** 1 = full film strength; below 1 = less veil (the search bar uses a lighter one) */
    var filmFactor = 1f

    /**
     * true for glass that lives in its own window (popup menus): the backdrop is then in another
     * window, so positions are compared on the screen instead of inside the window.
     */
    var screenCoords = false

    /**
     * true while this layer is faded in and out by its own alpha (popup menus). It then skips the
     * offscreen copy a fade of a blurred view normally needs, and waits until it is fully shown
     * before reading the brightness behind it, so neither costs a frame during the animation.
     */
    var cheapFade = false

    override fun hasOverlappingRendering(): Boolean = !cheapFade

    /** true once the brightness behind the pill has been measured at least once */
    var sampled = false
        private set

    /** true when the pill sits over a bright page: its content should then be dark */
    var onLight = false
        private set

    /**
     * 0 = over a dark page (light ink), 1 = over a bright page (dark ink). It glides between the
     * two instead of jumping, so the content never flashes from one colour to the other.
     */
    var lightness = 0f
        private set

    /** the ink to use over this glass right now, or null until the first measurement */
    val inkColor: Int?
        get() = if (!sampled) null else ColorUtils.blendARGB(Color.WHITE, DARK_INK, lightness)

    /** called on every step of the glide (used by GlassHelper) */
    var onToneChanged: ((Float) -> Unit)? = null

    /** called on every step of the glide (used by the activity for the mini player) */
    var onToneExtra: ((Float) -> Unit)? = null

    private var toneAnimator: ValueAnimator? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastSample = 0L
    private var sampling = false
    private var sampleBitmap: Bitmap? = null
    private val sampleRect = Rect()
    private val spot = IntArray(2)
    private val pixels = IntArray(SAMPLE_W * SAMPLE_H)

    private var node: RenderNode? = null
    private var effect: RenderEffect? = null
    private var effectSigma = -1f
    private var effectSaturation = -1f
    private val here = IntArray(2)
    private val there = IntArray(2)

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            drawGlass(canvas)
        }
        maybeSample()
    }

    /**
     * Like Apple's glass over a bright page: a few times a second the (tiny) area of the pill is
     * read back from the window and its brightness decides whether the content over it must be
     * dark or light. Two thresholds, so it never flickers between the two.
     */
    private fun maybeSample() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || sampling) return
        val now = SystemClock.uptimeMillis()
        if (now - lastSample < 300 || !isShown || alpha < (if (cheapFade) 0.99f else 0.5f)) return
        if (width < 8 || height < 8) return
        val window = findWindow() ?: return
        if (screenCoords) getLocationOnScreen(spot) else getLocationInWindow(spot)
        sampleRect.set(
            spot[0] + width / 8, spot[1] + height / 4,
            spot[0] + width - width / 8, spot[1] + height - height / 4
        )
        val decor = window.decorView
        if (!sampleRect.intersect(0, 0, decor.width, decor.height) ||
            sampleRect.width() < 4 || sampleRect.height() < 4
        ) return
        val bitmap = sampleBitmap
            ?: Bitmap.createBitmap(SAMPLE_W, SAMPLE_H, Bitmap.Config.ARGB_8888).also { sampleBitmap = it }
        lastSample = now
        sampling = true
        try {
            PixelCopy.request(window, sampleRect, bitmap, { result ->
                sampling = false
                if (result == PixelCopy.SUCCESS) onSampled(bitmap)
            }, mainHandler)
        } catch (e: Exception) {
            sampling = false
        }
    }

    private fun onSampled(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, SAMPLE_W, 0, 0, SAMPLE_W, SAMPLE_H)
        var r = 0L
        var g = 0L
        var b = 0L
        for (p in pixels) {
            r += Color.red(p)
            g += Color.green(p)
            b += Color.blue(p)
        }
        val n = pixels.size
        val luminance = ColorUtils.calculateLuminance(
            Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        )
        val light = if (onLight) luminance > 0.30 else luminance > 0.45
        val first = !sampled
        sampled = true
        if (first || light != onLight) {
            onLight = light
            glideTo(if (light) 1f else 0f, animate = !first)
        }
    }

    private fun glideTo(target: Float, animate: Boolean) {
        toneAnimator?.cancel()
        if (!animate) {
            setTone(target)
            return
        }
        toneAnimator = ValueAnimator.ofFloat(lightness, target).apply {
            duration = 450
            addUpdateListener { setTone(it.animatedValue as Float) }
            start()
        }
    }

    private fun setTone(value: Float) {
        lightness = value
        onToneChanged?.invoke(value)
        onToneExtra?.invoke(value)
    }

    override fun onDetachedFromWindow() {
        toneAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    private fun findWindow(): Window? {
        var c: Context? = context
        while (c != null) {
            if (c is Activity) return c.window
            c = (c as? ContextWrapper)?.baseContext
        }
        return null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun drawGlass(canvas: Canvas) {
        val root = backdrop
        if (live && root != null && root.isAttachedToWindow && canvas.isHardwareAccelerated &&
            width > 0 && height > 0
        ) {
            // extra border so the blur has real neighbours at the pill's edges
            val margin = ceil(blurSigmaPx * 3f).toInt()
            val w = width + 2 * margin
            val h = height + 2 * margin

            val n = node ?: RenderNode("metro-glass").also { node = it }
            n.setPosition(0, 0, w, h)
            n.setRenderEffect(currentEffect())

            if (screenCoords) {
                getLocationOnScreen(here)
                root.getLocationOnScreen(there)
            } else {
                getLocationInWindow(here)
                root.getLocationInWindow(there)
            }
            val recording = n.beginRecording(w, h)
            try {
                if (baseColor != Color.TRANSPARENT) recording.drawColor(baseColor)
                // root.draw() does not apply root's own scroll (the parent does that), so a
                // scrolling backdrop such as a NestedScrollView needs it added back here
                recording.translate(
                    (margin + there[0] - here[0]).toFloat() - root.scrollX,
                    (margin + there[1] - here[1]).toFloat() - root.scrollY
                )
                root.draw(recording)
            } finally {
                n.endRecording()
            }

            canvas.save()
            canvas.translate(-margin.toFloat(), -margin.toFloat())
            canvas.drawRenderNode(n)
            canvas.restore()
        }
        if (live && filmColor != Color.TRANSPARENT) canvas.drawColor(filmColor)

        // the page behind keeps moving: draw again on the next frame
        if (live) postInvalidateOnAnimation()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
    private fun currentEffect(): RenderEffect {
        val sigma = blurSigmaPx.coerceAtLeast(0.5f)
        var current = effect
        if (current == null || effectSigma != sigma || effectSaturation != saturation) {
            val blur = RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
            val colours = ColorMatrix()
            colours.setSaturation(saturation)
            current = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(colours), blur)
            effect = current
            effectSigma = sigma
            effectSaturation = saturation
        }
        return current
    }

    companion object {
        val DARK_INK: Int = Color.parseColor("#1C1B1F")
        private const val SAMPLE_W = 32
        private const val SAMPLE_H = 8
    }
}
